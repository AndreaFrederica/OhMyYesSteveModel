"""Offline CPM renderer with separate Legacy and Modern coordinate paths."""
from __future__ import annotations
import json, math, sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageChops
try:
    from cpm_source_render import render_png as render_exact_png
except ImportError:
    render_exact_png = None

def mm(a,b): return [[sum(a[i][k]*b[k][j] for k in range(4)) for j in range(4)] for i in range(4)]
def mp(m,p):
    x,y,z=p; return [m[0][0]*x+m[0][1]*y+m[0][2]*z+m[0][3],m[1][0]*x+m[1][1]*y+m[1][2]*z+m[1][3],m[2][0]*x+m[2][1]*y+m[2][2]*z+m[2][3]]
def I(): return [[1,0,0,0],[0,1,0,0],[0,0,1,0],[0,0,0,1]]
def T(x,y,z): return [[1,0,0,x],[0,1,0,y],[0,0,1,z],[0,0,0,1]]
def S(x,y,z): return [[x,0,0,0],[0,y,0,0],[0,0,z,0],[0,0,0,1]]
def qa(axis,d):
    a=math.radians(d)/2; s,c=math.sin(a),math.cos(a)
    return (s,0,0,c) if axis=='x' else (0,s,0,c) if axis=='y' else (0,0,s,c)
def qm(a,b):
    ax,ay,az,aw=a; bx,by,bz,bw=b
    return (aw*bx+ax*bw+ay*bz-az*by,aw*by-ax*bz+ay*bw+az*bx,aw*bz+ax*by-ay*bx+az*bw,aw*bw-ax*bx-ay*by-az*bz)
def qmat(q):
    x,y,z,w=q
    return [[1-2*(y*y+z*z),2*(x*y-z*w),2*(x*z+y*w),0],[2*(x*y+z*w),1-2*(x*x+z*z),2*(y*z-x*w),0],[2*(x*z-y*w),2*(y*z+x*w),1-2*(x*x+y*y),0],[0,0,0,1]]
def rot_xyz(x,y,z):
    """JOML rotateZYX equivalent used by JavaModelState (column vectors)."""
    return qmat(qm(qa('z',z),qm(qa('y',y),qa('x',x))))

def legacy_rot(x,y,z):
    """CustomModel.IBone.getQuaternion: Rz(z) * Ry(x) * Rx(y)."""
    return qmat(qm(qa('z',z),qm(qa('y',x),qa('x',y))))

def source_rot(fmt,r):
    """Map CPM source Euler channels to YSM's baked-pose channels.

    JavaModelState consumes attributes with rotateZYX(z,y,x).  Both CPM
    source paths are reflected through the YSM model frame; Modern source
    Euler channels therefore map to [-x,-y,-z].
    """
    x,y,z=(list(r)+[0,0,0])[:3]
    return legacy_rot(x,y,z) if fmt == 'legacy' else rot_xyz(-x,-y,-z)

def bone_local(fmt,b,p):
    """Reproduce JavaModelState's absolute-pivot conjugation.

    Canonical CPM primitives are already in model coordinates.  A bone's
    static pivot therefore contributes no translation at rest; it only
    defines the point around which authored and animated rotations act.
    """
    pivot=b['pivot']
    # Snapshots carry canonical IR pivots. GeoBuilder reflects X before the
    # JavaModelState sees them for both CPM adapters.
    if fmt == 'modern' or fmt == 'legacy':
        pivot = [-float(pivot[0]), float(pivot[1]), float(pivot[2])]
    e=p if isinstance(p,dict) else {}
    pos=e.get('position',[0,0,0])
    scale=e.get('scale',[1,1,1])
    if fmt == 'legacy' and all(abs(float(x)) < 1e-9 for x in pivot):
        # JavaModelState transformMode=1 custom-bone branch.  The legacy
        # adapter stores the complete source ModelRenderer position in the
        # pose, while the baked geometry has already been reflected in Y.
        r=e.get('rotation',[0,0,0])
        rr=source_rot(fmt,r)
        # CpmLegacyRenderPoseAdapter encodes [x,-y,z] and JavaModelState
        # consumes it as T(-attrX,+attrY,+attrZ).
        tr=[-float(pos[0]),-float(pos[1]),float(pos[2])]
        # IBone.getTransform() is scale -> quaternion rotation -> translation.
        return mm(mm(S(*scale),rr),T(*tr))
    if fmt == 'legacy':
        # Host PlayerBone branch in JavaModelState:
        # T(pivot + delta) * R * T(0,-24,0).
        r=e.get('rotation',[0,0,0]); rr=source_rot(fmt,r)
        tr=[pivot[0]-float(pos[0]),pivot[1]+float(pos[1]),pivot[2]+float(pos[2])]
        return mm(mm(T(*tr),rr),mm(S(*scale),T(0,-24,0)))
    delta=[-float(pos[0]),float(pos[1]),float(pos[2])]
    rest=b.get('rotation',[0,0,0])
    anim=e.get('rotation',[0,0,0])
    effective=[float(rest[i])+float(anim[i]) for i in range(3)]
    return mm(mm(mm(T(pivot[0]+delta[0],pivot[1]+delta[1],pivot[2]+delta[2]),
                    source_rot(fmt,effective)),S(*scale)),
              T(-pivot[0],-pivot[1],-pivot[2]))

def cube(o,s):
    x,y,z=o; dx,dy,dz=s
    return [[x+((i>>0)&1)*dx,y+((i>>1)&1)*dy,z+((i>>2)&1)*dz] for i in range(8)]
FACES=[([0,1,3,2],'zmin'),([4,6,7,5],'zmax'),([0,4,5,1],'ymin'),([2,3,7,6],'ymax'),([0,2,6,4],'xmin'),([1,5,7,3],'xmax')]
def uvrect(uv,size,face):
    u,v=(float(uv[0]),float(uv[1])) if isinstance(uv,list) and len(uv)>=2 else (0,0); dx,dy,dz=[abs(float(x)) for x in size]
    return {'xmin':(u,v+dz,dy,dz),'xmax':(u+dz+dx,v+dz,dy,dz),'ymin':(u+dz,v,dx,dz),'ymax':(u+dz+dx,v+dz,dx,dz),'zmin':(u+dz,v+dz,dx,dy),'zmax':(u+dz+dx+dz,v+dz,dx,dy)}[face]
def tex_for(root,snap,p):
    sym=str(p.get('texture','')); path=snap.get('textures',{}).get(sym)
    if path and (root/path).exists(): return Image.open(root/path).convert('RGBA')
    generated={'thumbnail.png','model-upstream.png','model-ysm.png'}
    files=[x for x in root.rglob('*') if x.suffix.lower() in ('.png','.jpg','.jpeg','.bmp')
           and x.name.lower() not in generated and 'source' not in x.stem.lower()]
    key=sym.lower().replace('tex.',''); hit=[x for x in files if x.stem.lower()==key or key in x.stem.lower() or x.stem.lower() in key]
    return Image.open(max(hit or files,key=lambda x:x.stat().st_size)).convert('RGBA') if (hit or files) else None
def col(img,rect):
    if img is None:return (190,190,190,255)
    u,v,w,h=rect; x0=max(0,min(img.width-1,int(math.floor(u)))); y0=max(0,min(img.height-1,int(math.floor(v)))); x1=max(x0+1,min(img.width,int(math.ceil(u+w)))); y1=max(y0+1,min(img.height,int(math.ceil(v+h))))
    return img.crop((x0,y0,x1,y1)).resize((1,1),Image.Resampling.BOX).getpixel((0,0))
def project(p,scale,cx,cy,flip_y=False):
    x,y,z=p
    if flip_y: y=-y
    yaw=math.radians(-35); pitch=math.radians(22); a=x*math.cos(yaw)-z*math.sin(yaw); d=x*math.sin(yaw)+z*math.cos(yaw); b=y*math.cos(pitch)-d*math.sin(pitch); depth=y*math.sin(pitch)+d*math.cos(pitch); return (cx+a*scale,cy-b*scale,depth)
def render(root,snap,frame,out,size=700):
    fmt=snap.get('format'); bones=snap['bones']; pose=snap.get('poses',{}).get(str(frame),{}).get('values',{}); worlds=[]
    for i,b in enumerate(bones):
        e=pose.get(b['name'],{}) if isinstance(pose.get(b['name'],{}),dict) else {}
        m=bone_local(fmt,b,e); parent=b['parent']; worlds.append(mm(worlds[parent],m) if parent>=0 else m)
    items=[]; pts=[]; exact_primitives=[]
    for p in snap.get('primitives',[]):
        b=bones[p['bone']]; O=p['origin']; V=p['size'];
        # GeoBuilder's legacy cube baker mirrors the X channel for every
        # source family.  Canonical CPM origins are minimum corners before
        # that bake; reproduce the exact emitted vertex frame here.
        verts=cube([-float(O[0])-float(V[0]),float(O[1]),float(O[2])],V)
        if fmt == 'modern':
            cp=p.get('pivot',[0,0,0]); cr=p.get('rotation',[0,0,0])
            if any(abs(float(x))>1e-9 for x in cp) or any(abs(float(x))>1e-9 for x in cr):
                cp = [-float(cp[0]), float(cp[1]), float(cp[2])]
                q=rot_xyz(-float(cr[1]),-float(cr[0]),-float(cr[2]))
                around=mm(mm(T(*cp),q),T(-cp[0],-cp[1],-cp[2]))
                verts=[mp(around,v) for v in verts]
        inf=float(p.get('inflate',0));
        if inf: verts=cube([min(v[j] for v in verts)-inf for j in range(3)],[max(v[j] for v in verts)-min(v[j] for v in verts)+2*inf for j in range(3)])
        if p.get('mirror'):
            for v in verts:v[0]=-v[0]
        verts=[mp(worlds[p['bone']],v) for v in verts]; pts+=verts; img=tex_for(root,snap,p)
        exact_primitives.append({'bone':bones[p['bone']]['name'],'vertices':([[v[0],24-v[1],v[2]] for v in verts] if fmt == 'modern' else verts),
                                'uv':p.get('uv',[0,0]),'texture':p.get('texture',''),
                                'size':V,'raw_size':V,'mirror':bool(p.get('mirror',False)),
                                'texture_size':[snap.get('texture_size',[64,64])[0],snap.get('texture_size',[64,64])[1]],
                                'bone_pivot':bones[p['bone']].get('pivot',[0,0,0]),
                                'pivot':p.get('pivot',[0,0,0]),'rotation':p.get('rotation',[0,0,0])})
        for idx,(face,name) in enumerate(FACES):
            if len({tuple(round(verts[i][k],6) for k in range(3)) for i in face})<3: continue
            rect=uvrect(p.get('uv'),V,name)
            items.append((sum(verts[i][1] for i in face),[verts[i] for i in face],img,rect,col(img,rect),idx))
    if not pts:return
    # Use the same perspective-free, per-texel rasterizer as the source
    # renderer.  The previous crop-and-stretch preview averaged each face's
    # atlas rectangle into a screen-aligned quad, which hid UV orientation
    # errors and visibly overpainted adjacent transparent texels.
    if render_exact_png is not None:
        render_exact_png(root, {'format':fmt,'primitives':exact_primitives,
                                'label':f'CPM canonical {fmt} frame={frame}'}, out, size)
        return
    xs=[v[0] for v in pts]; ys=[(-v[1] if fmt == 'legacy' else v[1]) for v in pts]; span=max(max(xs)-min(xs),max(ys)-min(ys),1); sc=min((size-80)/span,24); cx=size/2-(min(xs)+max(xs))*sc/2; cy=size/2+(min(ys)+max(ys))*sc/2; canvas=Image.new('RGBA',(size,size),(26,29,35,255)); draw=ImageDraw.Draw(canvas)
    for _,face,img,rect,color,_ in sorted(items,key=lambda x:x[0]):
        poly=[project(v,sc,cx,cy,fmt == 'legacy')[:2] for v in face]
        # Paint an atlas crop into the face's screen bounds. This keeps the
        # offline preview useful for texture checks while remaining a small
        # CPU diagnostic renderer (the game path still performs true UV
        # interpolation).
        overlay=Image.new('RGBA',canvas.size,(0,0,0,0)); mask=Image.new('L',canvas.size,0); ImageDraw.Draw(mask).polygon(poly,fill=255)
        if img is not None:
            u,v,w,h=rect; x0=max(0,min(img.width-1,int(math.floor(u)))); y0=max(0,min(img.height-1,int(math.floor(v)))); x1=max(x0+1,min(img.width,int(math.ceil(u+w)))); y1=max(y0+1,min(img.height,int(math.ceil(v+h))))
            crop=img.crop((x0,y0,x1,y1)); bx0=int(min(p[0] for p in poly)); by0=int(min(p[1] for p in poly)); bx1=max(bx0+1,int(max(p[0] for p in poly))); by1=max(by0+1,int(max(p[1] for p in poly)))
            crop=crop.resize((bx1-bx0,by1-by0),Image.Resampling.NEAREST); overlay.alpha_composite(crop,(bx0,by0)); overlay.putalpha(ImageChops.multiply(overlay.getchannel('A'),mask))
        else: ImageDraw.Draw(overlay).polygon(poly,fill=color)
        canvas=Image.alpha_composite(canvas,overlay); draw=ImageDraw.Draw(canvas)
        draw.line(poly+[poly[0]],fill=(20,20,20,220),width=1)
    draw.text((10,10),f"{snap.get('format')} frame={frame} primitives={len(snap.get('primitives',[]))}",fill=(240,240,240,255)); canvas.save(out)
def main():
    if len(sys.argv)<3:raise SystemExit('usage: cpm_offline_render.py <model-dir> <snapshot.json> [output-dir]')
    root=Path(sys.argv[1]); snap=json.loads(Path(sys.argv[2]).read_text()); out=Path(sys.argv[3] if len(sys.argv)>3 else 'build/cpm-renders'); out.mkdir(parents=True,exist_ok=True)
    size=int(sys.argv[4]) if len(sys.argv)>4 else 900
    for f in (0,10,20):render(root,snap,f,out/f'{root.name}-{f:03d}.png',size)
    print(out.resolve())
if __name__=='__main__':main()
