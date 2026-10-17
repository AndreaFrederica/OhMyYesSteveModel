"""Render the post-GeoBuilder YSM geometry dump.

The dump is produced by ``gradlew cpmYsmDump`` and contains the exact
CubeLegacy vertices, face indices, normals and normalized UVs consumed by the
YSM Java bake path.  This renderer intentionally does no CPM cube conversion.
"""
from __future__ import annotations
import argparse, json, math
from pathlib import Path
from PIL import Image, ImageDraw
def mm(a,b): return [[sum(a[i][k]*b[k][j] for k in range(4)) for j in range(4)] for i in range(4)]
def mp(m,p): return [m[0][0]*p[0]+m[0][1]*p[1]+m[0][2]*p[2]+m[0][3],m[1][0]*p[0]+m[1][1]*p[1]+m[1][2]*p[2]+m[1][3],m[2][0]*p[0]+m[2][1]*p[1]+m[2][2]*p[2]+m[2][3]]
def tm(x,y,z): return [[1,0,0,x],[0,1,0,y],[0,0,1,z],[0,0,0,1]]
def rot(x,y,z):
    cx,sx=math.cos(x),math.sin(x); cy,sy=math.cos(y),math.sin(y); cz,sz=math.cos(z),math.sin(z)
    rx=[[1,0,0,0],[0,cx,-sx,0],[0,sx,cx,0],[0,0,0,1]]; ry=[[cy,0,sy,0],[0,1,0,0],[-sy,0,cy,0],[0,0,0,1]]; rz=[[cz,-sz,0,0],[sz,cz,0,0],[0,0,1,0],[0,0,0,1]]
    return mm(mm(rz,ry),rx)

def project(p, scale, cx, cy):
    x,y,z=p; yaw=math.radians(-35); pitch=math.radians(22)
    a=x*math.cos(yaw)-z*math.sin(yaw); d=x*math.sin(yaw)+z*math.cos(yaw)
    b=y*math.cos(pitch)-d*math.sin(pitch); depth=y*math.sin(pitch)+d*math.cos(pitch)
    return (cx+a*scale, cy+b*scale, depth)
def bary(a,b,c,p):
    den=(b[1]-c[1])*(a[0]-c[0])+(c[0]-b[0])*(a[1]-c[1])
    if abs(den)<1e-9:return None
    x,y=p; u=((b[1]-c[1])*(x-c[0])+(c[0]-b[0])*(y-c[1]))/den
    v=((c[1]-a[1])*(x-c[0])+(a[0]-c[0])*(y-c[1]))/den
    return u,v,1-u-v
def texture(root, dump_path):
    atlases=sorted(dump_path.parent.glob(dump_path.stem+'-atlas.png'))
    if atlases: return Image.open(atlases[0]).convert('RGBA')
    generated={'thumbnail.png','model-upstream.png','model-ysm.png'}
    files=[p for p in root.rglob('*') if p.is_file() and p.suffix.lower() in ('.png','.jpg','.jpeg','.bmp') and p.name.lower() not in generated]
    return Image.open(max(files,key=lambda p:p.stat().st_size)).convert('RGBA') if files else None
def raster(canvas,zbuf,tri,uv,img):
    x0=max(0,int(math.floor(min(p[0] for p in tri)))); x1=min(canvas.width-1,int(math.ceil(max(p[0] for p in tri))))
    y0=max(0,int(math.floor(min(p[1] for p in tri)))); y1=min(canvas.height-1,int(math.ceil(max(p[1] for p in tri))))
    pix=canvas.load()
    for y in range(y0,y1+1):
      for x in range(x0,x1+1):
        q=bary(*tri,(x+.5,y+.5))
        if q is None or min(q)<-1e-7: continue
        a,b,c=q; depth=a*tri[0][2]+b*tri[1][2]+c*tri[2][2]; at=y*canvas.width+x
        if depth>=zbuf[at]: continue
        if img is None: color=(180,180,180,255)
        else:
          u=a*uv[0][0]+b*uv[1][0]+c*uv[2][0]; v=a*uv[0][1]+b*uv[1][1]+c*uv[2][1]
          color=img.getpixel((max(0,min(img.width-1,int(u*img.width))),max(0,min(img.height-1,int(v*img.height)))))
        if color[3]==0: continue
        zbuf[at]=depth; old=pix[x,y]; alpha=color[3]/255
        pix[x,y]=tuple(int(color[i]*alpha+old[i]*(1-alpha)) for i in range(3))+(255,)
HOST_ALIASES = {'head':'Head','body':'UpperBody','left_arm':'LeftArm',
                'right_arm':'RightArm','left_leg':'LeftLeg','right_leg':'RightLeg',
                'left_front_leg':'left_front_leg','right_front_leg':'right_front_leg',
                'left_back_leg':'left_back_leg','right_back_leg':'right_back_leg'}
CANONICAL_HOSTS = set(('Root MAllBody AllBody UpBody UpperBody Arm LeftArm LeftForeArm LeftHand '
                      'RightArm RightForeArm RightHand AllHead MHead Head DownBody Leg LeftLeg '
                      'LeftLowerLeg LeftFoot RightLeg RightLowerLeg RightFoot HeadOverlay BodyOverlay '
                      'LeftArmOverlay RightArmOverlay LeftLegOverlay RightLegOverlay').split())

def sm(s): return [[s[0],0,0,0],[0,s[1],0,0],[0,0,s[2],0],[0,0,0,1]]

def bound_poses(dump):
    names={b['name'] for b in dump['bones']}; result={}
    for name,pose in dump.get('neutralPose',{}).items():
        candidates=[name,HOST_ALIASES.get(name)] + ['_'*i+'__cpm_'+name for i in range(9)]
        target=next((n for n in candidates if n in names),None)
        if target is not None: result[target]=(name,pose)
    return result

def visible_bones(dump, bindings):
    """ModelBone.update() propagates parent visibility to every descendant."""
    by = {b['name']: b for b in dump.get('bones', [])}
    memo = {}
    def visible(name, stack=None):
        if name in memo: return memo[name]
        stack = set() if stack is None else stack
        if name in stack: return False
        stack.add(name)
        binding = bindings.get(name)
        own = not (binding is not None and len(binding[1]) >= 10 and float(binding[1][9]) < 0.5)
        parent = by.get(name, {}).get('parentIndex', -1)
        if isinstance(parent, str): parent = parent if parent in by else -1
        if isinstance(parent, str): own = own and visible(parent, stack)
        elif parent is not None and parent >= 0:
            own = own and visible(dump['bones'][parent]['name'], stack)
        memo[name] = own
        return own
    return {name: visible(name) for name in by}

def legacy_local(bone, binding):
    pivot=bone['pivot']; r=bone['rotate']; p=[0,0,0]; s=[1,1,1]
    if binding:
        name,q=binding
        if name in CANONICAL_HOSTS: p=q[:3]
        else: p=[q[0],-q[1],q[2]]; r=[math.radians(q[4]),math.radians(q[3]),math.radians(q[5])]; s=q[6:9]
    if all(x==0 for x in pivot): return mm(mm(sm(s),rot(*r)),tm(-p[0]/16,p[1]/16,p[2]/16))
    return mm(mm(tm((pivot[0]-p[0])/16,(pivot[1]+p[1])/16,(pivot[2]+p[2])/16),rot(*r)),tm(0,-24/16,0))

def modern_local(bone, binding):
    pivot=bone['pivot']; attr=[0,0,0]; r=[0,0,0]; s=[1,1,1]
    if binding:
        name,q=binding
        s=q[6:9]
        # Only the exact YSM canonical host names use the host branch.  A
        # custom Modern bone may legally be named lowercase ``body`` or
        # ``head``; Java CustomEntity resolves that authored bone through the
        # normal Modern adapter, so HOST_ALIASES must not classify it as a
        # host merely by spelling.
        if bone['name'] in CANONICAL_HOSTS or name in HOST_ALIASES:
            # CustomEntity's canonical-host branch writes the host position
            # directly and negates its already-radian rotation.  The dump
            # stores that same attribute channel for neutral parity.
            attr=q[:3]; r=[-math.radians(x) for x in q[3:6]]
        else:
            # CpmModernRenderPoseAdapter: attr=(-scriptX,-scriptY,scriptZ)
            # and complete ScriptBone rotation in radians.
            attr=[-q[0],-q[1],q[2]]; r=[math.radians(q[3]),math.radians(q[4]),math.radians(q[5])]
    # JavaModelState mode 2, in the same order and signs as the JOML code.
    return mm(mm(mm(tm((pivot[0]-attr[0])/16,(pivot[1]+attr[1])/16,(pivot[2]+attr[2])/16),rot(*r)),sm(s)),tm(*[-v/16 for v in pivot]))

def world_transforms(dump):
    byname={b['name']:i for i,b in enumerate(dump['bones'])}; worlds=[]; poses=bound_poses(dump)
    if dump['transformMode']==2 and not dump.get('neutralPose'):
        raise ValueError('Modern actual dump requires neutralPose; regenerate with CpmYsmActualDump')
    for bone in dump['bones']:
        local=(legacy_local if dump['transformMode']==1 else modern_local)(bone,poses.get(bone['name']))
        parent=bone.get('parentIndex',bone.get('parent',-1))
        if isinstance(parent,str): parent=byname[parent]
        worlds.append(mm(worlds[parent],local) if parent is not None and parent>=0 else local)
    return worlds

def render(root,dump,out,size,dump_path):
    cubes=[]; allv=[]; worlds=world_transforms(dump); bindings=bound_poses(dump); visible=visible_bones(dump, bindings)
    for bone,world in zip(dump['bones'],worlds):
      binding=bindings.get(bone['name'])
      if not visible.get(bone['name'], True):
        continue
      for cube in bone['cubes']:
        pos=[mp(world,[float(x) for x in cube['pos'][i:i+3]]) for i in range(0,len(cube['pos']),3)]
        faces=[]
        for f in range(cube['faceCount']):
          ids=cube['posIndices'][f*4:f*4+4]; uids=cube['uvIndices'][f*4:f*4+4]
          faces.append((ids,[(cube['uv'][u*2],cube['uv'][u*2+1]) for u in uids]))
        cubes.append((pos,faces)); allv.extend(pos)
    if not allv: Image.new('RGBA',(size,size),(26,29,35,255)).save(out); return
    # CubeLegacy stores model coordinates in blocks; CPM source previews use
    # pixels.  Scale only the diagnostic camera, leaving UV and depth math in
    # the exact dumped coordinate space.
    view=[[p[0]*16,p[1]*16,p[2]*16] for p in allv]
    xs=[p[0] for p in view]; ys=[p[1] for p in view]; span=max(max(xs)-min(xs),max(ys)-min(ys),1); scale=min((size-100)/span,28); cx=size/2-(min(xs)+max(xs))*scale/2; cy=size/2-(min(ys)+max(ys))*scale/2
    canvas=Image.new('RGBA',(size,size),(26,29,35,255)); z=[float('inf')]*(size*size); img=texture(root, dump_path); faces=[]
    for pos,fs in cubes:
      for ids,uv in fs:
        poly=[project([pos[i][0]*16,pos[i][1]*16,pos[i][2]*16],scale,cx,cy) for i in ids]; faces.append((sum(p[2] for p in poly)/4,poly,uv))
    for _,poly,uv in sorted(faces,key=lambda x:x[0],reverse=True):
      raster(canvas,z,poly[:3],uv[:3],img); raster(canvas,z,[poly[0],poly[2],poly[3]],[uv[0],uv[2],uv[3]],img)
    ImageDraw.Draw(canvas).text((12,12),f"YSM actual transformMode={dump['transformMode']} bones={len(dump['bones'])}",fill=(245,245,245,255)); canvas.save(out)
def main():
    ap=argparse.ArgumentParser(); ap.add_argument('model_dir',type=Path); ap.add_argument('dump',type=Path); ap.add_argument('-o','--output',type=Path,required=True); ap.add_argument('--size',type=int,default=900); a=ap.parse_args()
    render(a.model_dir,json.loads(a.dump.read_text()),a.output,a.size,a.dump); print(a.output.resolve())
if __name__=='__main__': main()
