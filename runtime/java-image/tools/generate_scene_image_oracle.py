"""Freeze source pixels from Pillow's unmodified decoders; not needed by library consumers/tests."""
from pathlib import Path
import hashlib
import json
import struct
import PIL
from PIL import Image

ROOT = Path(__file__).resolve().parents[1] / 'src/test/resources/scene-image-oracle'
ROOT.mkdir(parents=True, exist_ok=True)
assert PIL.__version__ == '12.3.0', PIL.__version__
rgba = Image.new('RGBA', (3, 2))
rgba.putdata([(231, 7, 19, 255), (1, 127, 254, 128), (16, 40, 200, 0),
              (13, 213, 55, 1), (202, 114, 12, 192), (255, 255, 255, 255)])
cases = []
def freeze(name, mode, **options):
    image = rgba.convert(mode)
    image.save(ROOT/name, **options)
    opened = Image.open(ROOT/name)
    pixels = opened.convert('RGBA').tobytes()
    (ROOT/(name+'.rgba')).write_bytes(pixels)
    cases.append(dict(file=name, width=opened.width, height=opened.height,
                      sha256=hashlib.sha256((ROOT/name).read_bytes()).hexdigest(), reference='Pillow 12.3.0'))

freeze('rgb.tga', 'RGB')
freeze('rgba-rle.tga', 'RGBA', compression='tga_rle')
freeze('gray-alpha.tga', 'LA', compression='tga_rle')
freeze('palette.tga', 'P', compression='tga_rle')
freeze('rgb.bmp', 'RGB')
freeze('palette.bmp', 'P')
freeze('mono.bmp', '1')
freeze('rgba.png', 'RGBA')
freeze('rgba.webp', 'RGBA', lossless=True, exact=True)
freeze('rgb.jpg', 'RGB', quality=95, subsampling=0)
# Pillow's raw modes provide an independent orientation decoder. The source record order is
# unchanged, so each result is a different, explicitly oriented image (no output copied by us).
base = bytearray((ROOT/'rgba-rle.tga').read_bytes())
for origin in (0x00, 0x10, 0x20, 0x30):
    name=f'origin-{origin:02x}.tga'
    data=base.copy(); data[17]=(data[17]&~0x30)|origin
    (ROOT/name).write_bytes(data)
    opened=Image.open(ROOT/name)
    (ROOT/(name+'.rgba')).write_bytes(opened.convert('RGBA').tobytes())
    cases.append(dict(file=name,width=opened.width,height=opened.height,sha256=hashlib.sha256(data).hexdigest(),reference='Pillow 12.3.0'))
# BMP RLE8, bottom-up rows with absolute packet padding and EOL/EOB.
palette=b''.join(bytes((b,g,r,0)) for r,g,b in [(0,0,0),(255,0,0),(0,255,0),(0,0,255)])
rle=bytes([0,3,1,2,3,0,0,0,3,2,0,0,0,1])
offset=14+40+len(palette)
data=struct.pack('<2sIHHI',b'BM',offset+len(rle),0,0,offset)
data+=struct.pack('<IiiHHIIiiII',40,3,2,1,8,1,len(rle),0,0,4,0)+palette+rle
(ROOT/'rle8.bmp').write_bytes(data)
opened=Image.open(ROOT/'rle8.bmp')
(ROOT/'rle8.bmp.rgba').write_bytes(opened.convert('RGBA').tobytes())
cases.append(dict(file='rle8.bmp',width=3,height=2,sha256=hashlib.sha256(data).hexdigest(),reference='Pillow 12.3.0'))
(ROOT/'manifest.json').write_text(json.dumps(cases,indent=2)+'\n',encoding='utf8')
print(f'Wrote {len(cases)} source and independent pixel pairs with Pillow {PIL.__version__}')
