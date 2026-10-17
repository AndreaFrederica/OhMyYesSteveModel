"""Fetch the exact ufbx revision used by the WASM FBX bridge."""
import hashlib, json, sys, urllib.request, shutil
from pathlib import Path

root=Path(__file__).resolve().parents[1]
manifest=json.loads((root.parent/'wasm-fbx'/'engine'/'sources.json').read_text())
out=Path(sys.argv[1]).resolve();out.mkdir(parents=True,exist_ok=True)
for name,expected in manifest['files'].items():
    target=out/name
    if not target.exists():
        url=f"https://raw.githubusercontent.com/ufbx/ufbx/{manifest['revision']}/{name}"
        with urllib.request.urlopen(url,timeout=45) as response: data=response.read()
        target.write_bytes(data)
    if hashlib.sha256(target.read_bytes()).hexdigest()!=expected:
        raise SystemExit(f'ufbx source hash mismatch: {name}')
engine=root.parent/'wasm-fbx'/'engine'
adapter=out.parent/'fbx-engine';adapter.mkdir(parents=True,exist_ok=True)
for name in ('fbx_bridge.c','fbx_details.h','fbx_deform.h','fbx_material_fields.h'):
    shutil.copyfile(engine/name,adapter/name)
shutil.copyfile(out/'ufbx.h',adapter/'ufbx.h')
print(out,flush=True)
