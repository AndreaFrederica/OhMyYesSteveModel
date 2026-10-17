"""Stage the optional native ufbx FBX bridge with reproducible source provenance."""
import argparse, hashlib, json, platform, shutil, zipfile
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()
def main():
    parser=argparse.ArgumentParser();parser.add_argument('--build-dir',type=Path,default=ROOT/'build');build=parser.parse_args().build_dir.resolve()
    system={'Windows':'windows','Linux':'linux','Darwin':'macos'}[platform.system()]
    arch={'AMD64':'x64','x86_64':'x64','arm64':'arm64','aarch64':'arm64'}[platform.machine()]
    filename={'windows':'ysmlib_fbx.dll','linux':'libysmlib_fbx.so','macos':'libysmlib_fbx.dylib'}[system]
    binary=build/filename
    if not binary.is_file(): raise SystemExit('Build ysmlib_fbx before packaging')
    manifest=json.loads((ROOT.parent/'wasm-fbx'/'engine'/'sources.json').read_text())
    source=build/'ufbx-source'
    for name,expected in manifest['files'].items():
        if sha(source/name)!=expected: raise SystemExit('Modified ufbx source: '+name)
    dest=build/'fbx-dist'/(system+'-'+arch);dest.mkdir(parents=True,exist_ok=True)
    shutil.copyfile(binary,dest/filename)
    shutil.copyfile(ROOT.parent/'wasm-fbx'/'engine'/'fbx_bridge.c',dest/'fbx_bridge.c')
    for name in ('fbx_details.h','fbx_deform.h','fbx_material_fields.h'):
        shutil.copyfile(ROOT.parent/'wasm-fbx'/'engine'/name,dest/name)
    archive=dest/'fbx-sources.zip'
    with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
        for name in manifest['files']: z.write(source/name,'ufbx/'+name)
        for name in ('fbx_bridge.c','fbx_details.h','fbx_deform.h','fbx_material_fields.h'):
            z.write(ROOT.parent/'wasm-fbx'/'engine'/name,'runtime/wasm-fbx/engine/'+name)
        for name in ('meson.build','meson_options.txt','tools/fetch_ufbx.py','tools/package_fbx.py'):
            z.write(ROOT/name,'runtime/native/'+name)
        z.write(ROOT.parent/'wasm-fbx'/'engine'/'sources.json','runtime/wasm-fbx/engine/sources.json')
        z.write(ROOT.parents[1]/'LICENSE','LICENSE')
    record={'abi':1,'platform':system+'-'+arch,'ufbxRevision':manifest['revision'],'sourceFiles':manifest['files'],
            'files':{p.name:sha(p) for p in dest.iterdir() if p.is_file() and p.name!='provenance.json'}}
    (dest/'provenance.json').write_text(json.dumps(record,indent=2)+'\n')
    print(dest)
if __name__=='__main__': main()
