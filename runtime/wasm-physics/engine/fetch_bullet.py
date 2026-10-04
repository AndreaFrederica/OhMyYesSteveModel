"""Fetch a pinned Bullet source snapshot for maintainer builds (never at player startup)."""
import hashlib
import json
from pathlib import Path, PurePosixPath
import shutil
import tarfile
import urllib.request

REVISION = '2c204c49e56ed15ec5fcfa71d199ab6d6570b3f5'
ROOT = Path(__file__).resolve().parents[1]


def fetch():
    work = ROOT / 'build' / 'engine'
    work.mkdir(parents=True, exist_ok=True)
    source = work / ('bullet-' + REVISION)
    marker = source / 'source.json'
    if marker.exists():
        data = json.loads(marker.read_text(encoding='utf-8'))
        if data['revision'] != REVISION:
            raise ValueError('Unexpected cached Bullet revision')
        for relative, expected in data['files'].items():
            if hashlib.sha256((source / relative).read_bytes()).hexdigest() != expected:
                raise ValueError('Modified Bullet source: ' + relative)
        return source
    archive = work / ('bullet-' + REVISION + '.tar.gz')
    if not archive.exists():
        url = 'https://codeload.github.com/bulletphysics/bullet3/tar.gz/' + REVISION
        temporary = archive.with_suffix('.download')
        with urllib.request.urlopen(url, timeout=60) as response, temporary.open('wb') as output:
            shutil.copyfileobj(response, output)
        temporary.replace(archive)
    files = {}
    with tarfile.open(archive) as bundle:
        for member in bundle:
            parts = PurePosixPath(member.name).parts[1:]
            if not member.isfile() or not parts or '..' in parts:
                continue
            if parts[0] not in ('src', 'LICENSE.txt'):
                continue
            relative = PurePosixPath(*parts).as_posix()
            target = source.joinpath(*parts)
            if not target.resolve().is_relative_to(source.resolve()):
                raise ValueError('Unsafe source archive member')
            target.parent.mkdir(parents=True, exist_ok=True)
            payload = bundle.extractfile(member).read()
            target.write_bytes(payload)
            files[relative] = hashlib.sha256(payload).hexdigest()
    if not (source / 'src/BulletSoftBody/btSoftRigidDynamicsWorld.h').exists():
        raise ValueError('Incomplete Bullet source archive')
    marker.write_text(json.dumps({'revision': REVISION,
        'archive_sha256': hashlib.sha256(archive.read_bytes()).hexdigest(),
        'files': files}, indent=2) + '\n', encoding='utf-8')
    return source


if __name__ == '__main__':
    print(fetch(), flush=True)
