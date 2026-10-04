"""Generate project-owned PMX/VMD inputs and freeze independent Saba deformation results."""
import argparse
import hashlib
import json
import math
from pathlib import Path
import struct
import subprocess

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'src/test/resources/mmd-oracle'


class Wire:
    def __init__(self): self.data=bytearray()
    def p(self,fmt,*values): self.data.extend(struct.pack('<'+fmt,*values))
    def f(self,*values): self.p('f'*len(values),*values)
    def text(self,value):
        encoded=value.encode('utf-8');self.p('i',len(encoded));self.data.extend(encoded)
    def fixed(self,value,size): self.data.extend(value.encode('ascii').ljust(size,b'\0'))
    def names(self,name): self.text(name);self.text(name)


def inputs(morphs=False):
    pmx=Wire();pmx.data.extend(b'PMX ');pmx.f(2.1);pmx.p('9B',8,1,0,1,1,1,1,1,1)
    pmx.names('skin-oracle');pmx.text('YSM project-owned synthetic fixture');pmx.text('No third-party model content')
    pmx.p('i',6)
    for i,kind in enumerate([0,1,2,3,4,3]):
        pmx.f(1+i*.2,.5+i*.1,.25,0,1,0,0,0);pmx.p('B',kind)
        if kind==0: pmx.p('b',0)
        elif kind in (1,3): pmx.p('bb',0,1);pmx.f(.3)
        else: pmx.p('bbbb',0,1,0,1);pmx.f(.1,.2,.3,.4)
        if kind==3: pmx.f(.2,.4,.1,.7,.2,-.1,-.3,.8,.4)
        pmx.f(1)
    pmx.p('i6B',6,0,1,2,3,4,5);pmx.p('i',0)
    pmx.p('i',1);pmx.names('material');pmx.f(.8,.7,.6,1,1,1,1,32,.2,.2,.2);pmx.p('B',31)
    pmx.f(0,0,0,1,1);pmx.p('bbBBB',-1,-1,0,1,0);pmx.text('');pmx.p('i',6)
    pmx.p('i',2)
    for i,name in enumerate(['root','tip']):
        pmx.names(name);pmx.f(0,i,0);pmx.p('biH',0 if morphs and i==1 else -1,i,0x1e);pmx.f(0,1,0)
    pmx.p('i',5 if morphs else 1);pmx.names('shape');pmx.p('BBi',4,1,2)
    for index in [3,4]: pmx.p('B',index);pmx.f(.15,-.2,.3)
    if morphs:
        pmx.names('bone');pmx.p('BBi',4,2,1);pmx.p('b',1);pmx.f(.1,.2,.3,0,0,math.sin(math.pi/6),math.cos(math.pi/6))
        for op,name in [(0,'multiply'),(1,'add')]:
            pmx.names(name);pmx.p('BBibB',4,8,1,-1,op)
            pmx.f(*([1.5,.8,.7,.6,.5,.4,.3,.2,.4,.6,.8,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1] if op==0 else [.1,.2,.3,.1,.2,.3,.4,2,.1,.2,.3,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0]))
        pmx.names('group');pmx.p('BBi',4,0,4)
        for index,weight in [(0,.3),(1,.7),(2,.4),(3,.5)]:pmx.p('b',index);pmx.f(weight)
    for _ in range(4): pmx.p('i',0)  # display, rigid bodies, joints, soft bodies
    vmd=Wire();vmd.fixed('Vocaloid Motion Data 0002',30);vmd.fixed('skin-oracle',20);vmd.p('I',4)
    for name in ['root','tip']:
        for frame in [0,30]:
            vmd.fixed(name,15);vmd.p('I',frame)
            if frame==0: vmd.f(0,0,0,0,0,0,1)
            elif name=='root': vmd.f(.25,.1,-.2,0,math.sin(math.pi/6),0,math.cos(math.pi/6))
            else: vmd.f(-.15,.2,.3,0,0,math.sin(-math.pi/4),math.cos(-math.pi/4))
            cp=bytearray(64)
            for c in range(4): cp[c+8]=127;cp[c+12]=127
            vmd.data.extend(cp)
    vmd.p('I',6 if morphs else 2)
    for name in (['shape','bone','group'] if morphs else ['shape']):
        for frame,weight in [(0,0),(30,1)]: vmd.fixed(name,15);vmd.p('If',frame,weight)
    for _ in range(4):vmd.p('I',0)
    return pmx.data,vmd.data


def pmd_input():
    p=Wire();p.data.extend(b'Pmd');p.f(1);p.fixed('pmd-oracle',20);p.fixed('Project-owned PMD fixture',256)
    p.p('I',6)
    for i in range(6):
        p.f(1+i*.2,.5+i*.1,.25,0,1,0,0,0);p.p('HHBB',0,1,30,0)
    p.p('I6H',6,0,1,2,3,4,5);p.p('I',1);p.f(.8,.7,.6,1,32,1,1,1,.2,.2,.2);p.p('BBI',0,1,6);p.fixed('',20)
    p.p('H',2)
    for i,name in enumerate(['root','tip']):
        p.fixed(name,20);p.p('HHBH',65535 if i==0 else 0,65535,1,0);p.f(0,i,0)
    p.p('HH',0,2)
    p.fixed('base',20);p.p('IB',6,0)
    for i in range(6):p.p('I',i);p.f(1.05+i*.2,.5+i*.1,.25)
    p.fixed('shape',20);p.p('IB',2,4)
    for i in [3,4]:p.p('I',i);p.f(.15,-.2,.3)
    p.p('BHB',1,1,1);p.fixed('Root',50);p.p('IHBHB',2,0,1,1,1);p.p('B',0)
    for i in range(10):p.fixed(f'toon{i+1:02}.bmp',100)
    p.p('II',0,0);return p.data


def main():
    parser=argparse.ArgumentParser(__doc__)
    parser.add_argument('--oracle',type=Path,default=ROOT/'build/oracle/native/saba_oracle.exe')
    args=parser.parse_args();OUT.mkdir(parents=True,exist_ok=True)
    files=[]
    for stem in ['skin','morph']:
        pmx,vmd=inputs(stem=='morph');(OUT/f'{stem}.pmx').write_bytes(pmx);(OUT/f'{stem}.vmd').write_bytes(vmd)
        subprocess.run([str(args.oracle),str(OUT/f'{stem}.pmx'),str(OUT/f'{stem}.vmd'),str(OUT/f'{stem}.csv'),'60','0'],check=True)
        files.extend(OUT/f'{stem}.{extension}' for extension in ['pmx','vmd','csv'])
    (OUT/'pmd.pmd').write_bytes(pmd_input());(OUT/'pmd.vmd').write_bytes(inputs()[1])
    subprocess.run([str(args.oracle),str(OUT/'pmd.pmd'),str(OUT/'pmd.vmd'),str(OUT/'pmd.csv'),'60','0'],check=True)
    files.extend(OUT/f'pmd.{extension}' for extension in ['pmd','vmd','csv'])
    hashes={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in files}
    (OUT/'sources.json').write_text(json.dumps({'reference':'https://github.com/benikabocha/saba','revision':'29b8efa8b31c8e746f9a88020fb0ad9dcdcf3332',
        'referenceRepair':'PMXFile.cpp QDEF weight indices [0,1,3,4] corrected to [0,1,2,3]; deformation unchanged',
        'readerSha256':hashlib.sha256((ROOT/'build/oracle/saba/src/Saba/Model/MMD/PMXFile.cpp').read_bytes()).hexdigest(),
        'coordinates':'MMD source coordinates; Saba Z reflection removed','fixture':'Project-owned synthetic model and motion','sha256':hashes},indent=2)+'\n')


if __name__=='__main__':main()
