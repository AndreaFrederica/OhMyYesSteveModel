"""Generate PMM fixture with pinned, unmodified nanoem MIT code. Never run by players or normal tests."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import urllib.request
from pmm_v1_fixture import generate

ROOT = Path(__file__).resolve().parents[1]
ORACLE = ROOT / 'oracle'

def main():
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument('--cc', default='gcc')
    args = parser.parse_args()
    manifest = json.loads((ORACLE / 'pmm-sources.json').read_text())
    cache = ROOT / 'build/oracle/pmm'
    for name, entry in manifest['files'].items():
        target = cache / name
        if not target.exists():
            target.parent.mkdir(parents=True, exist_ok=True)
            with urllib.request.urlopen(entry['url'], timeout=35) as response:
                data = response.read()
            if hashlib.sha256(data).hexdigest() != entry['sha256']:
                raise ValueError('Downloaded source differs: ' + name)
            target.write_bytes(data)
        if hashlib.sha256(target.read_bytes()).hexdigest() != entry['sha256']:
            raise ValueError('Modified source: ' + name)
    binary = cache / 'pmm_oracle.exe'
    subprocess.run([args.cc, '-std=c11', '-O2', '-I', str(cache), str(ORACLE / 'pmm_oracle.c'),
        *[str(cache / p) for p in ['nanoem/nanoem.c', 'nanoem/ext/mutable.c', 'nanoem/ext/document.c']],
        '-lm', '-o', str(binary)], check=True)
    output = ROOT / 'src/test/resources/pmm-oracle'
    output.mkdir(parents=True, exist_ok=True)
    fixture = output / 'project.pmm'
    subprocess.run([str(binary), str(fixture)], check=True)
    v1 = generate(binary)
    (output / 'sources.json').write_text(json.dumps({'reference': manifest['revision'],
        'generator': 'Unmodified nanoem PMM writer and reader; YSM authored synthetic scene',
        'pmm1': 'YSM authored PMM1 verified by original nanoem reader with project-owned PMD schema; upstream PMM1 model writer is not implemented',
        'files': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in [fixture,v1]}}, indent=2)+'\n')

if __name__ == '__main__':
    main()
