#!/usr/bin/env python3
"""Render CPM source semantics and the YSM conversion side by side.
This is a small CPU oracle for coordinate/hierarchy regressions; it does not
load Minecraft or reuse YSM implementation code.
"""
import json, math, sys
from pathlib import Path
from PIL import Image, ImageDraw

S=5.0
W,H=720,720

def mmul(a,b):
    return [[sum(a[i][k]*b[k][j] for k in range(4)) for j in range(4)] for i in range(4)]
def ident(): return [[1 if i==j else 0 for j in range(4)] for i in range(4)]
def trans(x,y,z):
    m=ident();m[0][3]=x;m[1][3]=y;m[2][3]=z;return m
def scale(x,y,z):
    return [[x,0,0,0],[0,y,0,0],[0,0,z,0],[0,0,0,1]]
def rot(rx,ry,rz):
    # CPM Quaternion.setEulerAnglesRad(yaw,pitch,roll) followed by
    # mulLeft(rollQuaternion) yields Rz(roll) * Ry(yaw) * Rx(pitch).
    x,y,z=[math.radians(v) for v in (rx,ry,rz)]
    cx,sx=math.cos(x),math.sin(x);cy,sy=math.cos(y),math.sin(y);cz,sz=math.cos(z),math.sin(z)
    rxm=[[1,0,0,0],[0,cx,-sx,0],[0,sx,cx,0],[0,0,0,1]]
    rym=[[cy,0,sy,0],[0,1,0,0],[-sy,0,cy,0],[0,0,0,1]]
    rzm=[[cz,-sz,0,0],[sz,cz,0,0],[0,0,1,0],[0,0,0,1]]
    return mmul(mmul(rzm,rym),rxm)
def legacy_rot(x,y,z):
    # CustomModel.IBone.getQuaternion(): setEulerAnglesRad(yaw=x,
    # pitch=y, roll=0), then mulLeft(Rz(z)).  This is distinct from the
    # Modern/Bedrock authored X/Y/Z order used by the other renderer.
    return rot(y,x,z)
def point(m,p):
    return tuple(sum(m[i][k]*([*p,1][k]) for k in range(4)) for i in range(3))
def cube_points(o,s):
    x,y,z=o;dx,dy,dz=s
    return [(x+ix*dx,y+iy*dy,z+iz*dz) for ix in (0,1) for iy in (0,1) for iz in (0,1)]
def nums(v):
    out=[]
    for x in (v or [0,0,0]):
        if isinstance(x,(int,float)):
            out.append(float(x)); continue
        if isinstance(x,str):
            try: out.append(float(x)); continue
            except ValueError: pass
            try: out.append(float(eval(x, {"__builtins__": {}}, {}))); continue
            except Exception: pass
        out.append(0.0)
    return out
def host_pivots(doc):
    # CustomModel's RenderPlayerHandler.resetSkeleton() is the authority for
    # the 1.12 host frame.  It is not the Modern CPM 24-height convention.
    # OriginalBone.getPosition() exposes the live ModelRenderer point
    # directly. The sign inversion only applies to skeleton overrides.
    p={'head':(0,0,0),'body':(0,0,0),'left_arm':(5,2,0),'right_arm':(-5,2,0),
       'left_leg':(1.9,12,0),'right_leg':(-1.9,12,0)}
    sk=doc.get('skeleton',{})
    for key,name in [('head_all','head'),('body_all','body'),('left_arm_all','left_arm'),('right_arm_all','right_arm'),('left_leg_all','left_leg'),('right_leg_all','right_leg')]:
        v=sk.get(key)
        if isinstance(v,list) and len(v)>=3 and all(isinstance(a,(int,float)) for a in v):
            # CustomModel updates ModelRenderer.setRotationPoint(-x,-y,z).
            x,y,z=v[:3];p[name]=(-x,-y,z)
    return p
def legacy_oracle(doc):
    hosts=host_pivots(doc); bones=doc.get('bones',[]); by={b.get('id'):b for b in bones}
    world={}; out=[]
    def walk(b):
        parent=b.get('parent','none')
        if parent in by: pm=world[parent]['matrix']; pp=world[parent]['pivot']
        else:
            hp=hosts.get(parent,(0,0,0)); pm=ident();pm=trans(*hp);pp=hp
        # Bone.update() evaluates position and applies scl(-1,-1,1) before
        # IBone.getTransform().
        pos=b.get('position',[0,0,0]); lp=(float(pos[0]),float(pos[1]),float(pos[2]))
        lp=(-lp[0],-lp[1],lp[2])
        piv=tuple(pp[i]+lp[i] for i in range(3))
        # Legacy IBone.getTransform is scale -> rotate -> translate.
        r=nums(b.get('rotation',[0,0,0])); scl=nums(b.get('scale',[1,1,1]))
        local=mmul(mmul(scale(*scl),legacy_rot(r[0],r[1],r[2])),trans(*lp))
        mat=mmul(pm,local)
        world[b['id']]={'matrix':mat,'pivot':piv}
        for box in b.get('boxes',[]):
            c=box.get('coordinates',[0,0,0,0,0,0]); add=float(box.get('sizeAdd',0) or 0)
            # CustomModel.Box uses x=-c0, y=-c1 and z=c2 as its maximum
            # corner, so its minimum corner is [-c0-w,-c1-h,c2-d].
            o=(-float(c[0])-float(c[3])-add,-float(c[1])-float(c[4])-add,float(c[2])-float(c[5])-add); sz=(float(c[3])+2*add,float(c[4])+2*add,float(c[5])+2*add)
            out.append(([point(mat,q) for q in cube_points(o,sz)],b['id']))
        for child in bones:
            if child.get('parent')==b.get('id'):walk(child)
    roots=[b for b in bones if b.get('parent','none') not in by]
    for b in roots: walk(b)
    return out
def legacy_ysm(doc):
    hosts=host_pivots(doc); bones=doc.get('bones',[]); by={b.get('id'):b for b in bones}; world={}; out=[]
    def walk(b):
        parent=b.get('parent','none')
        if parent in by: pm=world[parent]['ymatrix']; pp=world[parent]['pivot']
        else:
            hp=hosts.get(parent,(0,0,0)); pm=trans(*hp); pp=hp
        pos=b.get('position',[0,0,0]);lp=(float(pos[0]),float(pos[1]),float(pos[2]));
        r=nums(b.get('rotation',[0,0,0])); scl=nums(b.get('scale',[1,1,1]));
        # The Legacy adapter stores [x,-y,z] and JavaModelState consumes it
        # as T(-attrX,+attrY,+attrZ), reproducing [-x,-y,z].
        local_ysm=mmul(mmul(scale(*scl),legacy_rot(r[0],r[1],r[2])),trans(-lp[0],-lp[1],lp[2]))
        ymat=mmul(pm,local_ysm)
        world[b['id']]={'ymatrix':ymat,'pivot':tuple(pp[i]+lp[i] for i in range(3))}
        for box in b.get('boxes',[]):
            c=box.get('coordinates',[0,0,0,0,0,0]); add=float(box.get('sizeAdd',0) or 0)
            org=(float(c[0]),-float(c[1])-float(c[4]),float(c[2])-float(c[5]))
            o=(-org[0]-float(c[3])-add,org[1]-add,org[2]-add);sz=(float(c[3])+2*add,float(c[4])+2*add,float(c[5])+2*add)
            # Bone pivot is emitted with x handedness flipped.
            # JavaModelState: T(pivot) * R(attributes) * T(-pivot).
            out.append(([point(ymat,q) for q in cube_points(o,sz)],b['id']))
        for child in bones:
            if child.get('parent')==b.get('id'):walk(child)
    roots=[b for b in bones if b.get('parent','none') not in by]
    for b in roots:walk(b)
    return out
def project(p):
    # Minecraft's player model renderer presents +Y downward in its local
    # mesh frame; flip that display axis for the offline camera so the
    # oracle has the same upright orientation as the in-game thumbnail.
    x,y,z=p; y=-y
    return (W/2+(x-z)*S*4,H*0.88-(y+(x+z)*.3)*S*4)
def draw(items,path,title):
    im=Image.new('RGB',(W,H),(28,28,32));d=ImageDraw.Draw(im);d.text((12,10),title,fill='white')
    cols={}
    for verts,name in items:
        cols.setdefault(name,(80+(len(cols)*47)%170,100+(len(cols)*71)%130,180))
        pts=[project(v) for v in verts]
        for i,j in [(0,1),(0,2),(0,4),(7,6),(7,5),(7,3),(1,3),(1,5),(2,3),(2,6),(4,5),(4,6)]:
            d.line((pts[i],pts[j]),fill=cols[name],width=1)
    im.save(path)

def main():
    p=Path(sys.argv[1]);doc=json.loads(p.read_text(encoding='utf-8'))
    if 'bones' not in doc: raise SystemExit('legacy model.json expected')
    a=legacy_oracle(doc);b=legacy_ysm(doc)
    errors=[max(math.dist(x,y) for x,y in zip(left,right)) for (left,_),(right,_) in zip(a,b)]
    draw(a,str(p.with_name(p.stem+'-upstream.png')),'CPM upstream semantic oracle')
    draw(b,str(p.with_name(p.stem+'-ysm.png')),'YSM converted semantic oracle')
    print('wrote',p.with_name(p.stem+'-upstream.png'),p.with_name(p.stem+'-ysm.png'),
          'boxes',len(a),len(b),'max_point_error',max(errors,default=0),
          'mean_box_error',sum(errors)/len(errors) if errors else 0)
if __name__=='__main__':main()
