"""Run pinned three-vrm in Node to author frozen reference outputs; not part of player runtime or ordinary tests."""
import base64
import hashlib
import io
import json
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]

def main():
    manifest = json.loads((ROOT / 'oracle/packages.json').read_text())
    cache = ROOT / 'build/oracle'
    cache.mkdir(parents=True, exist_ok=True)
    hashes = {}
    for name, info in manifest['packages'].items():
        archive = cache / (name.replace('/', '-').replace('@', '') + '.tgz')
        if not archive.exists():
            with urllib.request.urlopen(info['url'], timeout=60) as response:
                archive.write_bytes(response.read())
        data = archive.read_bytes()
        integrity = 'sha512-' + base64.b64encode(hashlib.sha512(data).digest()).decode()
        if integrity != info['integrity']:
            raise ValueError('Reference package hash mismatch: ' + name)
        hashes[name] = hashlib.sha256(data).hexdigest()
        target = cache / 'node_modules' / name
        # Restore every source from the checked archive on every oracle run; never trust a modified cache.
        with tarfile.open(fileobj=io.BytesIO(data), mode='r:gz') as tar:
            for entry in tar.getmembers():
                parts = PurePosixPath(entry.name).parts
                if not parts or parts[0] != 'package' or '..' in parts or entry.issym() or entry.islnk():
                    raise ValueError('Unexpected reference archive entry')
                if entry.isfile():
                    destination = target.joinpath(*parts[1:])
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    destination.write_bytes(tar.extractfile(entry).read())
    shutil.copyfile(ROOT / 'oracle/generate.mjs', cache / 'generate.mjs')
    output = ROOT / 'src/test/resources/three-vrm-oracle'
    output.mkdir(parents=True, exist_ok=True)
    subprocess.run(['node', str(cache / 'generate.mjs'), str(output)], check=True)
    (output / 'sources.json').write_text(json.dumps({
        'packages': manifest['packages'], 'sha256': hashes,
        'generator': 'YSM authored inputs; unmodified published three-vrm and three.js packages',
        'files': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in output.iterdir() if p.is_file() and p.name != 'sources.json'}
    }, indent=2) + '\n', encoding='utf-8')

if __name__ == '__main__':
    main()
