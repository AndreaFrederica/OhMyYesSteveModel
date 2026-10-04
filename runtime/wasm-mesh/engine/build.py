"""Maintainer-only build. The bundled MikkTSpace reactor runs entirely inside the JVM."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import urllib.request

ROOT = Path(__file__).resolve().parents[1]

def main():
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument('--sdk', required=True, type=Path)
    sdk = parser.parse_args().sdk.resolve()
    if (sdk / 'VERSION').read_text().splitlines()[0].strip() != '34.0':
        raise ValueError('WASI SDK 34.0 is required')
    manifest = json.loads((ROOT / 'engine/sources.json').read_text())
    source = ROOT / 'build/engine/source'
    source.mkdir(parents=True, exist_ok=True)
    for name, expected in manifest['files'].items():
        path = source / name
        if not path.exists():
            url = f'https://raw.githubusercontent.com/mmikk/MikkTSpace/{manifest["revision"]}/{name}'
            with urllib.request.urlopen(url, timeout=45) as response:
                data = response.read()
            if hashlib.sha256(data).hexdigest() != expected:
                raise ValueError('Source hash mismatch: ' + name)
            path.write_bytes(data)
        if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise ValueError('Modified MikkTSpace source: ' + name)
    output = ROOT / 'src/main/resources/cc/sirrus/ysmlib/mesh/mikktspace.wasm'
    output.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run([str(sdk / 'bin/clang.exe'), '-O2', '-std=c11', '-mno-simd128', '-ffp-contract=off',
        '-I' + str(source), str(ROOT / 'engine/tangent_bridge.c'), '-nostartfiles',
        str(sdk / 'share/wasi-sysroot/lib/wasm32-wasip1/crt1-reactor.o'),
        '-Wl,--entry=_initialize', '-Wl,--export=_initialize', '-Wl,-z,stack-size=2097152',
        '-Wl,--initial-memory=4194304', '-Wl,--max-memory=536870912', '-Wl,--strip-all',
        *['-Wl,--export=' + symbol for symbol in ['malloc', 'free', 'ysm_tangent_abi', 'ysm_tangents']],
        '-lm', '-o', str(output)], check=True)
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    output.with_suffix('.sha256').write_text(digest + '  mikktspace.wasm\n', encoding='ascii')
    licenses = ROOT.parent / 'forge/src/main/resources/licenses/mikktspace'
    licenses.mkdir(parents=True, exist_ok=True)
    # Both files retain the original license notice, and make the exact algorithm inspectable.
    for name in manifest['files']:
        shutil.copyfile(source / name, licenses / name)
    shutil.copyfile(ROOT / 'engine/sources.json', licenses / 'sources.json')
    print(digest, output, flush=True)

if __name__ == '__main__':
    main()
