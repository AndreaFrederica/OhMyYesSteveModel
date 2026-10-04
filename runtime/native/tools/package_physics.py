"""Stage a standalone optional physics accelerator with verified sources and provenance."""
import ctypes
import hashlib
import json
from pathlib import Path
import platform
import shutil
import zipfile
import argparse

ROOT = Path(__file__).resolve().parents[1]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--build-dir', type=Path, default=ROOT / 'build')
    build = parser.parse_args().build_dir.resolve()
    system = {'Windows': 'windows', 'Linux': 'linux', 'Darwin': 'macos'}[platform.system()]
    arch = {'AMD64': 'x64', 'x86_64': 'x64', 'arm64': 'arm64', 'aarch64': 'arm64'}[platform.machine()]
    filename = {'windows': 'ysmlib_physics.dll', 'linux': 'libysmlib_physics.so',
                'macos': 'libysmlib_physics.dylib'}[system]
    binary = build / filename
    library = ctypes.CDLL(str(binary))
    library.ysm_physics_features.restype = ctypes.c_uint64
    if (library.ysm_physics_abi(), library.ysm_physics_features(),
            library.ysm_physics_scalar_bits(), library.ysm_physics_thread_count()) != (5, 0xff, 32, 1):
        raise ValueError('Refusing to package an incompatible physics binary')

    source = ROOT / '.cache' / 'bullet'
    record = json.loads((source / 'source.json').read_text())
    patch = json.loads((source / 'ysm-patches.json').read_text())
    files = dict(record['files'])
    if patch['revision'] != record['revision']:
        raise ValueError('Patch revision mismatch')
    if files[patch['file']] != patch['upstream_sha256']:
        raise ValueError('Patch upstream hash mismatch')
    files[patch['file']] = patch['patched_sha256']
    native_patch = json.loads((source / 'ysm-native-patches.json').read_text())
    if files[native_patch['file']] != native_patch['upstream_sha256']:
        raise ValueError('Native math patch upstream hash mismatch')
    files[native_patch['file']] = native_patch['patched_sha256']
    for relative, expected in files.items():
        if sha(source / relative) != expected:
            raise ValueError('Modified packaged Bullet source: ' + relative)

    math_source = ROOT / 'src' / 'math'
    math_record = json.loads((math_source / 'source.json').read_text())
    for relative, entry in math_record['files'].items():
        if sha(math_source / relative) != entry['sha256']:
            raise ValueError('Modified portable math source: ' + relative)
    destination = build / 'physics-dist' / (system + '-' + arch)
    destination.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(binary, destination / filename)
    shutil.copyfile(ROOT / 'src' / 'physics.h', destination / 'physics.h')
    license_dir = destination / 'licenses'
    license_dir.mkdir(exist_ok=True)
    shutil.copyfile(source / 'LICENSE.txt', license_dir / 'Bullet-LICENSE.txt')
    shutil.copyfile(ROOT.parents[1] / 'LICENSE', license_dir / 'YSM-LICENSE.txt')
    shutil.copyfile(source / 'ysm-patches.json', license_dir / 'Bullet-patches.json')
    shutil.copyfile(source / 'ysm-native-patches.json', license_dir / 'Bullet-native-patches.json')
    shutil.copyfile(math_source / 'source.json', license_dir / 'Math-source.json')
    # Preserve the original permissive notices with the binary, as well as in sources.
    (license_dir / 'Math-NOTICES.txt').write_text('\n\n'.join(
        (math_source / name).read_text().split('#include')[0]
        for name in sorted(math_record['files'])))
    archive = destination / 'physics-sources.zip'
    with zipfile.ZipFile(archive, 'w', compression=zipfile.ZIP_DEFLATED) as bundle:
        for relative in sorted(files):
            bundle.write(source / relative, 'verified-bullet/' + relative)
        for relative in ['source.json', 'ysm-patches.json', 'ysm-native-patches.json']:
            bundle.write(source / relative, 'verified-bullet/' + relative)
        own_sources = list((ROOT / 'src').rglob('*')) + list((ROOT / 'subprojects/packagefiles').rglob('*'))
        own_sources += list((ROOT / 'subprojects').glob('*.wrap'))
        own_sources += [ROOT / relative for relative in ['meson.build', 'meson_options.txt',
                        'pixi.toml', 'pixi.lock', 'tools/build.py', 'tools/package_physics.py']]
        for path in sorted(p for p in own_sources if p.is_file()):
            bundle.write(path, 'runtime/native/' + path.relative_to(ROOT).as_posix())
        for name in ['fetch_bullet.py', 'patch_bullet.py']:
            bundle.write(ROOT.parent / 'wasm-physics/engine' / name, 'runtime/wasm-physics/engine/' + name)
        bundle.write(ROOT.parents[1] / 'LICENSE', 'LICENSE')
    build_info = json.loads((build / 'meson-info' / 'intro-buildoptions.json').read_text())
    toolchain = json.loads((build / 'meson-info' / 'intro-compilers.json').read_text())
    manifest = {'abi': 5, 'features': 0xff, 'scalarBits': 32, 'solverThreads': 1,
                'platform': system + '-' + arch, 'bulletRevision': record['revision'],
                'bulletArchiveSha256': record['archive_sha256'], 'patch': patch,
                'nativePatch': native_patch, 'portableMath': math_record,
                'sourceFiles': files, 'compiler': toolchain,
                'buildType': next(v['value'] for v in build_info if v['name'] == 'buildtype'),
                'floatingPointPolicy': 'strict; float32; scalar solver; portable joint atan2/asin; one thread',
                'files': {p.relative_to(destination).as_posix(): sha(p)
                          for p in sorted(destination.rglob('*'))
                          if p.is_file() and p.name != 'provenance.json'}}
    (destination / 'provenance.json').write_text(json.dumps(manifest, indent=2) + '\n')
    # Reopen every staged file; the manifest describes the bytes actually shipped.
    for relative, expected in manifest['files'].items():
        if sha(destination / relative) != expected:
            raise ValueError('Staged file hash mismatch: ' + relative)
    print(destination)
    print('binary SHA-256:', manifest['files'][filename])


if __name__ == '__main__':
    main()
