"""Maintainer-only scalar ufbx reactor build. Players use the bundled module inside the JVM."""
import argparse
import hashlib
import json
import re
from pathlib import Path
import shutil
import subprocess
import urllib.request

ROOT = Path(__file__).resolve().parents[1]

def sources():
    manifest = json.loads((ROOT / 'engine/sources.json').read_text())
    target = ROOT / 'build/engine/source'
    target.mkdir(parents=True, exist_ok=True)
    for name, expected in manifest['files'].items():
        path = target / name
        if not path.exists():
            url = f'https://raw.githubusercontent.com/ufbx/ufbx/{manifest["revision"]}/{name}'
            with urllib.request.urlopen(url, timeout=45) as response:
                data = response.read()
            if hashlib.sha256(data).hexdigest() != expected:
                raise ValueError('Downloaded ufbx source hash mismatch: ' + name)
            path.write_bytes(data)
        if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise ValueError('Modified ufbx source: ' + name)
    return target

def main():
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument('--sdk', type=Path, required=True)
    args = parser.parse_args()
    sdk = args.sdk.resolve()
    if (sdk / 'VERSION').read_text().splitlines()[0].strip() != '34.0':
        raise ValueError('WASI SDK 34.0 is required')
    source = sources()
    header = (source / 'ufbx.h').read_text()
    mapping = (ROOT / 'engine/fbx_material_fields.h').read_text()
    for struct, field, macro in [('ufbx_material_fbx_maps', 'ufbx_material_map', 'FBX_MAP'),
                                 ('ufbx_material_pbr_maps', 'ufbx_material_map', 'PBR_MAP'),
                                 ('ufbx_material_features', 'ufbx_material_feature_info', 'FEATURE')]:
        body = header.split('typedef struct '+struct+' {', 1)[1].split('} '+struct+';', 1)[0]
        expected = re.findall(r'\b'+field+r'\s+(\w+)\s*;', body)
        actual = re.findall(macro+r'\((\w+)\)', mapping)
        if expected != actual:
            raise ValueError('Incomplete FBX material field mapping: '+struct)
    output = ROOT / 'src/main/resources/cc/sirrus/ysmlib/fbx/ufbx.wasm'
    output.parent.mkdir(parents=True, exist_ok=True)
    symbols = ['malloc','free','ysm_fbx_abi','ysm_fbx_load','ysm_fbx_destroy','ysm_fbx_evaluate',
               'ysm_fbx_snapshot','ysm_fbx_output','ysm_fbx_output_size','ysm_fbx_error']
    subprocess.run([str(sdk / 'bin/clang.exe'), '-O2', '-std=c11', '-mno-simd128', '-fno-strict-aliasing',
        '-DUFBX_NO_STDIO', '-DUFBX_NO_THREADS', '-I'+str(source), str(ROOT / 'engine/fbx_bridge.c'), str(source / 'ufbx.c'),
        '-nostartfiles', str(sdk / 'share/wasi-sysroot/lib/wasm32-wasip1/crt1-reactor.o'),
        '-Wl,--entry=_initialize', '-Wl,--export=_initialize', '-Wl,-z,stack-size=2097152',
        '-Wl,--initial-memory=16777216', '-Wl,--max-memory=536870912', '-Wl,--strip-all',
        *['-Wl,--export='+name for name in symbols], '-lm', '-o', str(output)], check=True)
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    output.with_suffix('.sha256').write_text(digest+'  ufbx.wasm\n', encoding='ascii')
    licenses = ROOT.parent / 'forge/src/main/resources/licenses/ufbx'
    licenses.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source / 'LICENSE', licenses / 'LICENSE.txt')
    shutil.copyfile(ROOT / 'engine/sources.json', licenses / 'sources.json')
    print(digest, output, flush=True)

if __name__ == '__main__':
    main()
