"""Configure Meson reproducibly; only this native project uses Pixi."""
import os
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
prefix = Path(os.environ.get('CONDA_PREFIX', sys.prefix))
candidates = [prefix / 'Library', prefix, Path(os.environ.get('JAVA_HOME', ''))]
jdk = next((p.resolve() for p in candidates if (p / 'include/jni.h').is_file()), None)
if jdk is None:
    raise SystemExit('A JDK 17 containing include/jni.h is required')
command = ['meson', 'setup', str(root / 'build'), str(root), f'-Djdk_home={jdk}']
if (root / 'build/meson-private/coredata.dat').exists():
    command.append('--reconfigure')
if os.name == 'nt':
    command.append('--vsenv')
subprocess.run(command, check=True)
