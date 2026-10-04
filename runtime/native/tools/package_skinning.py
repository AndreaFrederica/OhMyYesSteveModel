"""Package the independently implemented optional skinning kernel and verifiable sources."""
import argparse
import ctypes
import hashlib
import json
import platform
import shutil
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--build-dir', type=Path, default=ROOT / 'build')
    build = parser.parse_args().build_dir.resolve()
    system = {'Windows': 'windows', 'Linux': 'linux', 'Darwin': 'macos'}[platform.system()]
    arch = {'AMD64': 'x64', 'x86_64': 'x64', 'arm64': 'arm64', 'aarch64': 'arm64'}[platform.machine()]
    filename = {'windows': 'ysmlib_skinning.dll', 'linux': 'libysmlib_skinning.so', 'macos': 'libysmlib_skinning.dylib'}[system]
    binary = build / filename
    if ctypes.CDLL(str(binary)).ysm_skin_abi() != 1:
        raise ValueError('Incompatible skinning ABI')
    destination = build / 'skinning-dist' / (system + '-' + arch)
    destination.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(binary, destination / filename)
    shutil.copyfile(ROOT / 'src/skinning.h', destination / 'skinning.h')
    shutil.copyfile(ROOT.parents[1] / 'LICENSE', destination / 'YSM-LICENSE.txt')
    sources = ['src/skinning.h', 'src/skinning.cpp', 'src/skinning_jni.cpp', 'src/skinning_abi_test.c',
               'meson.build', 'meson_options.txt', 'pixi.toml', 'pixi.lock', 'tools/build.py', 'tools/package_skinning.py']
    manifest = {name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest() for name in sources}
    with zipfile.ZipFile(destination / 'skinning-sources.zip', 'w', zipfile.ZIP_DEFLATED) as archive:
        for name in sources:
            archive.write(ROOT / name, name)
        archive.write(ROOT.parents[1] / 'LICENSE', 'LICENSE')
    (destination / 'BUILD.txt').write_text(
        'Extract skinning-sources.zip and use a JDK 17 JNI include directory.\n'
        'Standalone Linux: g++ -std=c++20 -O3 -ffp-contract=off -fPIC -shared -pthread '
        'src/skinning.cpp src/skinning_jni.cpp -I<jdk>/include -I<jdk>/include/linux -o libysmlib_skinning.so\n'
        'Standalone Windows (MSVC developer shell): cl /std:c++20 /EHsc /O2 /fp:strict /MT /LD '
        '/I<jdk>/include /I<jdk>/include/win32 src/skinning.cpp src/skinning_jni.cpp /Fe:ysmlib_skinning.dll\n'
        'The archived full-project Meson file documents the original build configuration; '
        'its other targets need the full repository. No third-party kernel source is required.\n')
    files = [filename, 'skinning.h', 'YSM-LICENSE.txt', 'skinning-sources.zip', 'BUILD.txt']
    record = {'abi': 1, 'platform': system + '-' + arch, 'algorithm': 'YSM Java deformation oracle; independent implementation',
              'threading': 'bounded persistent pool, at most 4 workers; serial dispatch between calls',
              'sources': manifest, 'files': {name: hashlib.sha256((destination / name).read_bytes()).hexdigest() for name in files}}
    (destination / 'provenance.json').write_text(json.dumps(record, indent=2) + '\n')
    for name, expected in record['files'].items():
        if hashlib.sha256((destination / name).read_bytes()).hexdigest() != expected:
            raise ValueError('Staged artifact hash mismatch')
    with zipfile.ZipFile(destination / 'skinning-sources.zip') as archive:
        for name, expected in manifest.items():
            if hashlib.sha256(archive.read(name)).hexdigest() != expected:
                raise ValueError('Source archive mismatch')
    print('Verified skinning distribution: ' + str(destination))

if __name__ == '__main__':
    main()
