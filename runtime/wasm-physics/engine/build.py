"""Maintainer build of the headless Bullet reactor; ordinary Gradle builds use the bundled WASM."""
import argparse
import hashlib
from pathlib import Path
import shutil
import subprocess
from fetch_bullet import fetch, ROOT
from patch_bullet import prepare

def main():
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--jobs', default='8')
    parser.add_argument('--debug-names', action='store_true', help='Retain names in the maintainer build; do not update the bundled player reactor')
    args = parser.parse_args()
    sdk = args.sdk.resolve()
    if (sdk / 'VERSION').read_text().splitlines()[0].strip() != '34.0':
        raise SystemExit('WASI SDK 34.0 is required')
    source = prepare(fetch())
    build = ROOT / 'build/engine/wasi'
    def run(*command): subprocess.run([str(c) for c in command], check=True)
    run('cmake','-S', ROOT/'engine','-B',build,'-G','Ninja',
        f'-DCMAKE_TOOLCHAIN_FILE={sdk}/share/cmake/wasi-sdk-p1.cmake',
        f'-DCMAKE_MAKE_PROGRAM={shutil.which("ninja")}', '-DCMAKE_BUILD_TYPE=Release',
        f'-DBULLET_SOURCE={source}',f'-DYSM_PHYSICS_DEBUG_NAMES={"ON" if args.debug_names else "OFF"}')
    run('cmake','--build',build,'--target','physics','-j',args.jobs)
    if args.debug_names:
        print(build/'physics.wasm',flush=True)
        return
    output=ROOT/'src/main/resources/cc/sirrus/ysmlib/physics/bullet.wasm'
    output.parent.mkdir(parents=True,exist_ok=True)
    shutil.copyfile(build/'physics.wasm',output)
    digest=hashlib.sha256(output.read_bytes()).hexdigest()
    output.with_suffix('.sha256').write_text(digest+'  bullet.wasm\n',encoding='ascii')
    license_dir=ROOT.parent/'forge/src/main/resources/licenses/bullet-3.25'
    license_dir.mkdir(parents=True,exist_ok=True)
    shutil.copyfile(source/'LICENSE.txt',license_dir/'LICENSE.txt')
    shutil.copyfile(source/'ysm-patches.json',license_dir/'ysm-patches.json')
    print(digest,output,flush=True)

if __name__=='__main__': main()
