"""Author a minimal PMM1 and verify it with nanoem's independent reader and the existing PMD fixture.

The pinned nanoem PMM1 model writer is a FIXME, so it cannot generate this version.
"""
from pathlib import Path
import struct
import subprocess

ROOT = Path(__file__).resolve().parents[1]

def generate(binary):
    data = bytearray()
    def put(fmt, *v): data.extend(struct.pack('<'+fmt, *v))
    def text(value, count): data.extend(value.encode('cp932').ljust(count, b'\0'))
    def key(index=None, frame=0, previous=0, next=0):
        if index is not None: put('i', index)
        put('Iii', frame, previous, next)
    def bone(index=None, frame=0, next=0):
        key(index,frame,next=next);data.extend(bytes([20,20,107,107])*4);put('3f4fB',frame/30,0,0,0,0,0,1,0)
    text('Polygon Movie maker 0001',30);put('3if6B',640,480,250,30,0,1,1,1,1,1)
    put('BB',0,1);text('旧工程',20)
    put('B',0);text('旧工程',20);text('C:\\Dance\\models\\pmd.pmd',256);put('BB5iB2i',0,1,0,0,0,0,0,0,0,30)
    bone(next=2);bone();put('I',1);bone(2,30)
    for _ in range(2): key();put('fB',0,0)
    put('I',0);key();put('BBI',1,0,0)
    for _ in range(2): put('3f4fiBB',0,0,0,0,0,0,1,123,0,0)
    put('2f',0,.25)
    key();put('f3f3f',-30,0,10,0,0,0,0);data.extend(bytes([20,20,107,107])*6);put('BiBI',0,30,0,0)
    put('9fB',0,10,0,0,10,30,0,0,0,0)
    key();put('6fBI',1,1,1,0,-1,0,0,0);put('6f',1,1,1,0,-1,0)
    put('BiB',0,0,0)
    put('4i4B2iB',15,0,0,0,0,1,0,1,0,30,1);text('音楽/dance.wav',256)
    put('2if',0,0,1);text('',256);put('i2if',0,0,0,1);text('',256);put('4Bf2if',0,1,1,1,60,0,0,.5)
    data.extend(bytes([7,8,9]))
    target=ROOT/'src/test/resources/pmm-oracle/project-v1.pmm';target.write_bytes(data)
    subprocess.run([str(binary),'--read',str(target),str(ROOT/'src/test/resources/mmd-oracle/pmd.pmd')],check=True)
    return target
