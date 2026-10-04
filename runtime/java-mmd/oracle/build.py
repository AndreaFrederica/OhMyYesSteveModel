"""Maintainer-only independent Saba oracle. Never run by player code or ordinary Gradle tests."""
import argparse
import hashlib
import io
from pathlib import Path, PurePosixPath
import subprocess
import sys
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
REVISION = '29b8efa8b31c8e746f9a88020fb0ad9dcdcf3332'
ARCHIVE_SHA256 = 'd9e49964ef857a871b89c328d0339973c836d5ebf1ff1485ac37867b7332efef'


def patch_qdef_reader(destination):
    """Repair the pinned reader's skipped weight and out-of-bounds fourth write.

    The deformation algorithm remains unmodified. Check the complete branch so
    a different upstream source cannot silently accept this reference repair.
    """
    target = destination / 'src/Saba/Model/MMD/PMXFile.cpp'
    data = target.read_bytes()
    begin = data.index(b'case PMXVertexWeight::QDEF:')
    end = data.index(b'break;', begin)
    branch = data[begin:end]
    old = b'Read(&vertex.m_boneWeights[3], file);\n\t\t\t\t\tRead(&vertex.m_boneWeights[4], file);'
    new = b'Read(&vertex.m_boneWeights[2], file);\n\t\t\t\t\tRead(&vertex.m_boneWeights[3], file);'
    if branch.count(old) != 1:
        raise RuntimeError('Pinned Saba QDEF reader repair no longer matches')
    target.write_bytes(data[:begin] + branch.replace(old, new) + data[end:])


def source():
    cache = ROOT / 'build/oracle'
    cache.mkdir(parents=True, exist_ok=True)
    archive = cache / 'saba.tar.gz'
    if not archive.exists():
        archive.write_bytes(urllib.request.urlopen(
            f'https://codeload.github.com/benikabocha/saba/tar.gz/{REVISION}', timeout=60).read())
    data = archive.read_bytes()
    if hashlib.sha256(data).hexdigest() != ARCHIVE_SHA256:
        raise RuntimeError('Saba source archive hash mismatch')
    destination = cache / 'saba'
    with tarfile.open(fileobj=io.BytesIO(data), mode='r:gz') as tar:
        for member in tar.getmembers():
            components = PurePosixPath(member.name).parts[1:]
            if not components:
                continue
            relative = PurePosixPath(*components)
            if relative.is_absolute() or '..' in components:
                raise RuntimeError('Unsafe reference archive path')
            path = relative.as_posix()
            if not (path.startswith(('src/', 'external/glm/', 'external/spdlog/', 'license/'))
                    or path in ('LICENSE', 'CMakeLists.txt', 'external/CMakeLists.txt')):
                continue
            if member.isfile():
                target = destination.joinpath(*components)
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(tar.extractfile(member).read())
    patch_qdef_reader(destination)
    return destination


def main():
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument('--compiler', default='clang++')
    args = parser.parse_args()
    saba = source()
    sys.path.insert(0, str(ROOT.parent / 'wasm-physics/engine'))
    from fetch_bullet import fetch
    bullet = fetch()
    bullet_build = ROOT.parent / 'wasm-physics/build/engine/native-oracle'
    if not (bullet_build / 'BulletDynamics/libBulletDynamics.a').is_file():
        raise RuntimeError('Build the native Bullet oracle first with wasm-physics/engine/build_oracle.py')
    build = ROOT / 'build/oracle/native'
    subprocess.run(['cmake', '-S', str(ROOT / 'oracle'), '-B', str(build), '-G', 'Ninja',
                    '-DCMAKE_BUILD_TYPE=Release', f'-DCMAKE_CXX_COMPILER={args.compiler}',
                    f'-DSABA_SOURCE={saba}', f'-DBULLET_SOURCE={bullet}', f'-DBULLET_BUILD={bullet_build}'], check=True)
    subprocess.run(['cmake', '--build', str(build), '-j', '8'], check=True)
    print(build / 'saba_oracle.exe')


if __name__ == '__main__':
    main()
