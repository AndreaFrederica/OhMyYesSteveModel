"""Configure Meson reproducibly; only this native project uses Pixi."""
import os
from pathlib import Path
import subprocess
import sys
import importlib.util
import hashlib
import shutil
import json
import argparse

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(__doc__)
parser.add_argument('command', nargs='?', default='configure', choices=['configure'])
parser.add_argument('--build-dir', type=Path, default=root / 'build')
build_dir = parser.parse_args().build_dir.resolve()
math_record = json.loads((root / 'src/math/source.json').read_text())
for relative, entry in math_record['files'].items():
    if hashlib.sha256((root / 'src/math' / relative).read_bytes()).hexdigest() != entry['sha256']:
        raise RuntimeError('Modified portable joint math source: ' + relative)
# Native builds use the same hash-verified Bullet snapshot as the WASM
# maintainer build. Fetching is configure-time only; player startup never does
# network or filesystem discovery.
wasm_fetch = root.parent / 'wasm-physics' / 'engine' / 'fetch_bullet.py'
spec = importlib.util.spec_from_file_location('ysm_fetch_bullet', wasm_fetch)
fetch_module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fetch_module)
bullet_source = fetch_module.fetch()
# Keep the verified disconnected-soft-body BVH repair identical to the WASM oracle.
patch_path = root.parent / 'wasm-physics' / 'engine' / 'patch_bullet.py'
sys.modules['fetch_bullet'] = fetch_module
patch_spec = importlib.util.spec_from_file_location('ysm_patch_bullet', patch_path)
patch_module = importlib.util.module_from_spec(patch_spec)
patch_spec.loader.exec_module(patch_module)
bullet_source = patch_module.prepare(bullet_source)
# Short paths also keep MSVC-generated object/PDB filenames below its path limit.
# The source above was verified; never reuse a modified local build copy.
verified_cpp = sorted(p.relative_to(bullet_source) for p in bullet_source.glob('src/**/*.cpp')
                      if any(part in ('LinearMath', 'BulletCollision', 'BulletDynamics', 'BulletSoftBody') for part in p.parts))
native_bullet_source = root / '.cache' / 'bullet'
for source_file in sorted(bullet_source.rglob('*')):
    if not source_file.is_file():
        continue
    target = native_bullet_source / source_file.relative_to(bullet_source)
    if source_file.relative_to(bullet_source).as_posix() == 'src/LinearMath/btScalar.h':
        continue  # Write the final derived header once below, without changing mtime on a no-op build.
    target.parent.mkdir(parents=True, exist_ok=True)
    if not target.exists() or hashlib.sha256(target.read_bytes()).digest() != hashlib.sha256(source_file.read_bytes()).digest():
        shutil.copyfile(source_file, target)
# Joint Euler extraction must use the oracle's float libm, rather than each
# operating system's CRT. Keep this derived native-only header patch auditable.
scalar_path = native_bullet_source / 'src/LinearMath/btScalar.h'
original_scalar = (bullet_source / 'src/LinearMath/btScalar.h').read_bytes()
patched_scalar = original_scalar.replace(b'#define BT_SCALAR_H', b'#define BT_SCALAR_H\n#include "ysm_bullet_math.h"', 1)
for function in ('atan2f', 'asinf'):
    needle = ('return ' + function + '(').encode()
    if patched_scalar.count(needle) != 1:
        raise RuntimeError('Pinned Bullet math hook no longer matches: ' + function)
    patched_scalar = patched_scalar.replace(needle, ('return ysm_math_' + function + '(').encode())
if not scalar_path.exists() or scalar_path.read_bytes() != patched_scalar:
    scalar_path.write_bytes(patched_scalar)
bullet_source = native_bullet_source
(bullet_source / 'ysm-native-patches.json').write_text(json.dumps({
    'file': 'src/LinearMath/btScalar.h',
    'upstream_sha256': hashlib.sha256(original_scalar).hexdigest(),
    'patched_sha256': hashlib.sha256(patched_scalar).hexdigest(),
    'repair': 'Use WASI-compatible float atan2/asin for joint Euler extraction'
}, indent=2) + '\n')
manifest = build_dir / 'bullet_sources.txt'
sources = [str((bullet_source / relative).resolve()).replace('\\', '/') for relative in verified_cpp]
manifest.parent.mkdir(parents=True, exist_ok=True)
manifest.write_text('\n'.join(sources) + '\n', encoding='utf-8')
prefix = Path(os.environ.get('CONDA_PREFIX', sys.prefix))
candidates = [prefix / 'Library', prefix, Path(os.environ.get('JAVA_HOME', ''))]
jdk = next((p.resolve() for p in candidates if (p / 'include/jni.h').is_file()), None)
if jdk is None:
    raise SystemExit('A JDK 17 containing include/jni.h is required')
command = ['meson', 'setup', str(build_dir), str(root), f'-Djdk_home={jdk}']
if (build_dir / 'meson-private/coredata.dat').exists():
    command.append('--reconfigure')
if os.name == 'nt':
    command.append('--vsenv')
subprocess.run(command, check=True)
