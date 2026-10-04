"""Maintainer-only native reference fixture. Not used by player installations or ordinary tests."""
import argparse
import subprocess
from fetch_bullet import fetch, ROOT

parser=argparse.ArgumentParser(__doc__)
parser.add_argument('--compiler',default='clang++')
parser.add_argument('--c-compiler',default='clang')
args=parser.parse_args()
source=fetch(); build=ROOT/'build/engine/native-oracle'
subprocess.run(['cmake','-S',str(ROOT/'engine'),'-B',str(build),'-G','Ninja',
    '-DCMAKE_BUILD_TYPE=Release',f'-DCMAKE_CXX_COMPILER={args.compiler}',f'-DCMAKE_C_COMPILER={args.c_compiler}',
    f'-DBULLET_SOURCE={source}'],check=True)
subprocess.run(['cmake','--build',str(build),'--target','bullet_oracle','-j','8'],check=True)
executable=build/('bullet_oracle.exe' if (build/'bullet_oracle.exe').exists() else 'bullet_oracle')
result=subprocess.check_output([str(executable)])
output=ROOT/'src/test/resources/bullet-3.25-contact.csv'
output.parent.mkdir(parents=True,exist_ok=True);output.write_bytes(result)
print(output)
