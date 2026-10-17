#!/usr/bin/env python3
"""Render raw Gamepiaynmo CPM packages without YSM or Minecraft.

This is a source-semantic renderer.  It intentionally has separate Legacy
CustomModel and Modern CustomPlayerModel paths and emits a world-corner JSON
alongside the PNG so the result can be compared to a converter snapshot.
The default frame is the neutral standing pose; expressions/scripts and
physics are reported as dynamic features and are not guessed here.
"""
from __future__ import annotations
import argparse, json, math, re
from pathlib import Path
from typing import Any
from PIL import Image, ImageDraw

EPS = 1e-8
# These are the exact vertex orders used by CustomModel.Box and by the
# ModelRenderer.ModelBox constructor.  The UV endpoints below are the four
# arguments passed to TexturedQuad, including the reversed v range on ymax.
FACES = {
    # cube_vertices uses binary x/y/z order.  These indices are the exact
    # source Box/ModelBox vertex arrays after translating v1..v8 into that
    # order (for example source v3 is binary index 3).
    "xmax": ([5, 1, 3, 7], lambda u,v,dx,dy,dz: (u+dz+dx, v+dz, u+dz+dx+dz, v+dz+dy)),
    "xmin": ([0, 4, 6, 2], lambda u,v,dx,dy,dz: (u, v+dz, u+dz, v+dz+dy)),
    "ymin": ([5, 4, 0, 1], lambda u,v,dx,dy,dz: (u+dz, v, u+dz+dx, v+dz)),
    "ymax": ([3, 2, 6, 7], lambda u,v,dx,dy,dz: (u+dz+dx, v+dz, u+dz+dx+dx, v)),
    "zmin": ([1, 0, 2, 3], lambda u,v,dx,dy,dz: (u+dz, v+dz, u+dz+dx, v+dz+dy)),
    "zmax": ([4, 5, 7, 6], lambda u,v,dx,dy,dz: (u+dz+dx+dz, v+dz, u+dz+dx+dz+dx, v+dz+dy)),
}

def ident(): return [[1.,0.,0.,0.],[0.,1.,0.,0.],[0.,0.,1.,0.],[0.,0.,0.,1.]]
def mm(a,b): return [[sum(a[i][k]*b[k][j] for k in range(4)) for j in range(4)] for i in range(4)]
def mp(m,p):
    return [sum(m[i][k] * ([*p,1.][k]) for k in range(4)) for i in range(3)]
def trans(x,y,z):
    m=ident(); m[0][3]=x; m[1][3]=y; m[2][3]=z; return m
def scale(x,y,z): return [[x,0.,0.,0.],[0.,y,0.,0.],[0.,0.,z,0.],[0.,0.,0.,1.]]
def rx(a):
    a=math.radians(a); c,s=math.cos(a),math.sin(a)
    return [[1,0,0,0],[0,c,-s,0],[0,s,c,0],[0,0,0,1]]
def ry(a):
    a=math.radians(a); c,s=math.cos(a),math.sin(a)
    return [[c,0,s,0],[0,1,0,0],[-s,0,c,0],[0,0,0,1]]
def rz(a):
    a=math.radians(a); c,s=math.cos(a),math.sin(a)
    return [[c,-s,0,0],[s,c,0,0],[0,0,1,0],[0,0,0,1]]
def euler(x,y,z): return mm(mm(rz(z),ry(y)),rx(x))
def legacy_euler(x,y,z):
    # CustomModel's IBone.getQuaternion uses LibGDX
    # setEulerAnglesRad(yaw=x,pitch=y,roll=0), then left-multiplies Rz(z).
    # Legacy therefore uses Rz(z) * Ry(x) * Rx(y), independently of Modern.
    return mm(mm(rz(z),ry(x)),rx(y))
def eval_expr(expr, env=None):
    """Evaluate the constant/host-input subset used by the Legacy preview.

    The production adapter uses CpmExpressionEvaluator.  The offline oracle
    mirrors its arithmetic and conditional operators for a neutral frame so
    authored values such as ``4/3`` and ``if(is_first_person,0,25)`` are not
    silently replaced with defaults.
    """
    if not isinstance(expr,str): return None
    text=expr.strip()
    if not text: return None
    text=re.sub(r'\b(var|tvl|tvc|tvp)\.', '', text)
    text=text.replace('&&',' and ').replace('||',' or ')
    text=re.sub(r'!\s*(?!=)', ' not ', text)
    text=re.sub(r'\btrue\b','1',text,flags=re.I); text=re.sub(r'\bfalse\b','0',text,flags=re.I)
    text=re.sub(r'\bif\s*\(', 'iif(', text, flags=re.I)
    def iif(*a):
        for i in range(0,len(a)-1,2):
            if a[i]: return a[i+1]
        return a[-1] if len(a)%2 else 0
    scope={'iif':iif,'sin':math.sin,'cos':math.cos,'tan':math.tan,'abs':abs,
           'floor':math.floor,'ceil':math.ceil,'sqrt':math.sqrt,'pi':math.pi,
           'min':min,'max':max,'clamp':lambda x,a,b:max(a,min(b,x)),
           # CustomModel's FunctionType exposes these unit conversions and
           # the Java pose session evaluates them at the source boundary.
           # Keeping them here is required for a source/YSM pose oracle;
           # silently treating an expression such as -todeg(cos(age)*.2+1)
           # as zero makes every descendant look like a world-transform
           # failure even when the adapter matrix is correct.
           'todeg':lambda x:x*180.0/math.pi,
           'torad':lambda x:x*math.pi/180.0}
    if env: scope.update(env)
    try: return float(eval(text,{'__builtins__':{}},scope))
    except Exception: return None
def num(v, default=0., env=None):
    if isinstance(v,(int,float)) and math.isfinite(float(v)): return float(v)
    if isinstance(v,str):
        try: return float(v)
        except ValueError:
            value=eval_expr(v,env)
            return default if value is None or not math.isfinite(value) else value
    return default
def vec(v, n=3, default=0., env=None):
    return [num(v[i],default,env) if isinstance(v,list) and i<len(v) else default for i in range(n)]

def strip_json5(text: str) -> str:
    # CPM model.json files commonly contain // comments and trailing commas.
    out=[]; i=0; quote=False; esc=False
    while i<len(text):
        c=text[i]
        if quote:
            out.append(c)
            if esc: esc=False
            elif c=='\\': esc=True
            elif c=='"': quote=False
            i+=1; continue
        if c=='"': quote=True; out.append(c); i+=1; continue
        if c=='/' and i+1<len(text) and text[i+1]=='/':
            i=text.find('\n',i)
            if i<0: break
            out.append('\n'); i+=1; continue
        out.append(c); i+=1
    return re.sub(r',\s*([}\]])',r'\1',''.join(out))
def read_json(path: Path): return json.loads(strip_json5(path.read_text(encoding='utf-8-sig')))

def cube_vertices(o,s):
    x,y,z=o; dx,dy,dz=s
    return [[x+(i&1)*dx,y+((i>>1)&1)*dy,z+((i>>2)&1)*dz] for i in range(8)]

def texture_files(root):
    generated = {'thumbnail.png', 'model-upstream.png', 'model-ysm.png'}
    return [p for p in root.rglob('*') if p.is_file() and p.suffix.lower() in ('.png','.jpg','.jpeg','.bmp')
            and p.name.lower() not in generated and 'source' not in p.stem.lower()]
def texture_name(expr):
    if not isinstance(expr,str): return None
    names=re.findall(r'tex\.([A-Za-z0-9_.-]+)',expr)
    return names[-1] if 'if(' in expr and names else (names[0] if names else expr)
def find_texture(root, name):
    files=texture_files(root); key=(name or '').lower()
    exact=[p for p in files if p.stem.lower()==key or p.name.lower()==key]
    if exact: return exact[0]
    stem=[p for p in files if p.stem.lower()==key.replace('tex.','')]
    if stem: return stem[0]
    return max(files,key=lambda p:p.stat().st_size) if files else None
def load_tex(root,name,cache):
    p=find_texture(root,name)
    if p is None: return None
    if p not in cache: cache[p]=Image.open(p).convert('RGBA')
    return cache[p]

def host_legacy(doc):
    p={'head':[0,0,0],'body':[0,0,0],'right_arm':[-5,2,0],'left_arm':[5,2,0],
       'right_leg':[-1.9,12,0],'left_leg':[1.9,12,0]}
    sk=doc.get('skeleton',{}) if isinstance(doc.get('skeleton',{}),dict) else {}
    for key in list(p):
        v=sk.get(key+'_all')
        if isinstance(v,list) and len(v)>=3:
            p[key]=[-num(v[0]),-num(v[1]),num(v[2])]
    return p

def legacy_source(root,doc):
    bones=doc.get('bones',[]) if isinstance(doc.get('bones',[]),list) else []
    # CustomModel parses the array in source order.  Its id2Bone map is
    # updated after each bone, so a repeated authored id gets a new internal
    # name in the canonical YSM adapter while children before/after the
    # repeat stay attached to the occurrence that was visible when they were
    # parsed.  A plain {id: bone} map silently collapses this important case
    # (Marisa contains two ``layer1`` bones).
    host_ids={'none','head','body','right_arm','left_arm','right_leg','left_leg',
              'head_overlay','body_overlay','right_arm_overlay','left_arm_overlay',
              'right_leg_overlay','left_leg_overlay'}
    current={}; used=set(host_ids); normalized=[]
    for raw in bones:
        if not isinstance(raw,dict) or not raw.get('id'): continue
        authored=str(raw['id']); name=authored
        if name in host_ids:
            name='__cpm_'+name
        if name in used:
            suffix=2; candidate=f'{name}_{suffix}'
            while candidate in used:
                suffix += 1; candidate=f'{name}_{suffix}'
            name=candidate
        parent_authored=raw.get('parent','none')
        if parent_authored in host_ids:
            parent=parent_authored
        else:
            # The original resolver only sees entries already inserted into
            # id2Bone.  Keep the currently mapped occurrence for later
            # references, while retaining an unresolved authored id for a
            # diagnostic rather than guessing a different parent.
            parent=current.get(parent_authored,parent_authored)
        entry=dict(raw); entry['_name']=name; entry['_parent']=parent; entry['_authored_id']=authored
        normalized.append(entry); used.add(name); current[authored]=name
    by={b['_name']:b for b in normalized}
    # Neutral source frame: CPM's preview renderer evaluates variables with
    # is_first_person=false.  Resolve model variables in declaration order,
    # retaining the same names accepted by the Java evaluator.
    env={'is_first_person':0.,'is_first_person_hand':0.,'age':0.,'time':0.}
    raw_vars=doc.get('variables',{}) if isinstance(doc.get('variables',{}),dict) else {}
    for key,value in raw_vars.items():
        result=eval_expr(value,env)
        if result is not None: env[key]=result
    hosts=host_legacy(doc); worlds={}; primitives=[]; notes=[]; texture_cache={}
    def own_texture(b):
        return texture_name(b.get('texture'))
    default_texture = next((own_texture(b) for b in bones if own_texture(b)), None)
    def inherited_texture(b):
        seen=set(); current_b=b
        while isinstance(current_b,dict) and current_b.get('_name') not in seen:
            seen.add(current_b.get('_name'))
            tex=own_texture(current_b)
            if tex: return tex
            parent=current_b.get('_parent','none')
            current_b=by.get(parent)
        return default_texture
    def texture_size(b, tex):
        # CustomModel.Bone.getTextureSize() uses an explicit bone value when
        # present; otherwise ModelPack returns the decoded dimensions of the
        # inherited texture.  The 64x64 default belongs to Modern geometry and
        # must not be used for Legacy boxes.
        explicit = b.get('textureSize')
        if isinstance(explicit, list) and len(explicit) >= 2:
            return vec(explicit, 2, 64, env)
        image = load_tex(root, tex, texture_cache)
        if image is not None:
            return [float(image.width), float(image.height)]
        return [64.0, 64.0]
    visited=set()
    def walk(b):
        name=b['_name']; parent=b.get('_parent','none')
        if name in visited:
            return
        visited.add(name)
        pm=worlds.get(parent,hosts.get(parent,[0,0,0]))
        if isinstance(pm,list) and len(pm)==3 and all(isinstance(x,(int,float)) for x in pm): pm=trans(*pm)
        pos=vec(b.get('position'),3,0,env); pos=[-pos[0],-pos[1],pos[2]]
        rot=vec(b.get('rotation'),3,0,env); scl=vec(b.get('scale'),3,1,env)
        # IBone.getTransform: setToScaling -> rotate -> translate.
        local=mm(mm(scale(*scl),legacy_euler(*rot)),trans(*pos))
        worlds[name]=mm(pm,local)
        # CustomModel.Bone.getTexture() walks the custom parent chain and
        # finally uses ModelPack's base texture.  Child bones commonly omit
        # `texture`; treating that as an empty symbol selects the wrong image.
        tex=inherited_texture(b)
        for box in b.get('boxes',[]) or []:
            c=vec(box.get('coordinates'),6,0,env); add=num(box.get('sizeAdd'),0,env)
            o=[-c[0]-c[3]-add,-c[1]-c[4]-add,c[2]-c[5]-add]
            s=[c[3]+2*add,c[4]+2*add,c[5]+2*add]
            if box.get('mirror'): o[0],s[0]=o[0]+s[0],-s[0]
            # CustomModel.Box expands geometry by sizeAdd, but upstream Quad
            # UV rectangles retain the authored width/height/depth. Preserve
            # both frames so the source oracle cannot feed expanded bounds
            # into UV endpoint calculations.
            primitives.append({'bone':name,'vertices':[mp(worlds[name],v) for v in cube_vertices(o,s)],'uv':vec(box.get('textureOffset'),2,0,env),
                               'texture':tex,'size':s,'raw_size':[c[3],c[4],c[5]],'mirror':bool(box.get('mirror')),'texture_size':texture_size(b, tex)})
        for q in b.get('quads',[]) or []:
            notes.append(f'legacy quad preserved but not yet rasterized: {name}')
    # Normalized entries are parent-first in valid CustomModel packs.  Walk
    # each source entry exactly once so duplicate authored ids do not cause a
    # dict collision or a second traversal of the same root.
    for b in normalized:
        if b.get('_parent','none') not in by: walk(b)
    # A malformed pack can contain a forward reference.  Preserve it in the
    # report instead of dropping geometry, using the same unresolved parent
    # marker as the Java reader's diagnostic path.
    for b in normalized:
        if b['_name'] not in visited: walk(b)
    selected={}
    for key in ('fpLeft','fpRight','fpList'):
        roots=[current.get(x,x) for x in doc.get(key,[]) if x in current or x in by] if isinstance(doc.get(key),list) else []
        s=set(roots); changed=True
        while changed:
            changed=False
            for b in normalized:
                if b.get('_parent') in s and b.get('_name') not in s: s.add(b.get('_name')); changed=True
        selected[key]=sorted(s)
    # Keep authored fields for inspection, but expose canonical names and
    # resolved parent ids in the report so comparisons use the same identity
    # as CpmLegacySceneConverter.
    report_bones=[]
    for b in normalized:
        out={k:v for k,v in b.items() if not k.startswith('_')}
        out['name']=b['_name']; out['parent']=b['_parent']
        report_bones.append(out)
    return {'format':'legacy','bones':report_bones,'worlds':worlds,'primitives':primitives,'first_person':selected,'notes':notes}

def modern_host(doc):
    sk=doc.get('skeleton',{}) if isinstance(doc.get('skeleton',{}),dict) else {}; pa=sk.get('param',{}) or {}
    def n(k,d): return num(pa.get(k),d)
    hosts={'none':[0,0,0], 'head':[0,24-n('head_pivot_height',24),n('head_offset',0)],
            'body':[0,24-n('body_pivot_height',24),n('body_offset',0)],
            'right_arm':[-n('arm_interval',10)/2,24-n('arm_pivot_height',22),n('arm_offset',0)],
            'left_arm':[n('arm_interval',10)/2,24-n('arm_pivot_height',22),n('arm_offset',0)],
            'right_leg':[-n('leg_interval',3.8)/2,24-n('leg_length',12),n('leg_offset',0)],
            'left_leg':[n('leg_interval',3.8)/2,24-n('leg_length',12),n('leg_offset',0)]}
    if sk.get('type') == 'quadruped':
        x=n('leg_interval',6)/2; y=24-n('leg_length',6); z=n('leg_offset',1); fz=z+n('body_length',12)
        hosts.update({'left_back_leg':[x,y,z], 'right_back_leg':[-x,y,z],
                      'left_front_leg':[x,y,fz], 'right_front_leg':[-x,y,fz]})
    if sk.get('type') == 'slime':
        hosts={k:v for k,v in hosts.items() if k in ('none','body')}
    return hosts
def modern_parts(root,doc):
    parts=[]
    for part in doc.get('parts',[]) or []:
        if not isinstance(part,dict): continue
        name=part.get('name',''); f=root/name
        if not f.exists(): f=root/(name+'.json')
        if not f.exists():
            candidates=[x for x in root.glob('*.json') if x.name.lower()!='model.json' and x.stem.lower()==str(name).lower()]
            f=candidates[0] if candidates else None
        if f is None: continue
        geo=read_json(f); ver=str(geo.get('format_version','1.12.0')).split('.')
        old=len(ver)>=2 and (int(ver[0])<1 or int(ver[1])<12)
        models=[]
        if old:
            for k,v in geo.items():
                if str(k).startswith('geometry') and isinstance(v,dict): models.append((v, bool(part.get('double_face'))))
        else:
            models=[(x,bool(part.get('double_face'))) for x in geo.get('minecraft:geometry',[]) or []]
        for model,double in models:
            desc=model.get('description',{}) or {}; tw=num(desc.get('texture_width'),num(model.get('texturewidth'),64)); th=num(desc.get('texture_height'),num(model.get('textureheight'),64))
            parts.append((part,model,double,int(tw),int(th),str(f)))
    return parts
def modern_source(root,doc,pose=None):
    hosts=modern_host(doc); worlds={'none':trans(*hosts['none'])}; authored={}; bones=[]; primitives=[]; notes=[]
    for part,model,double,tw,th,source_file in modern_parts(root,doc):
        for b in model.get('bones',[]) or []:
            if not isinstance(b,dict) or not b.get('name'): continue
            name=b['name']; authored[name]=vec(b.get('pivot'),3,0); bones.append((b,tw,th,part.get('texture')))
    by={b[0]['name']:b[0] for b in bones}
    # Instantiate in source order; upstream packs are parent-before-child.
    pending=list(bones)
    while pending:
        progress=False
        for item in pending[:]:
            b,tw,th,texture=item; name=b['name']; p=b.get('parent')
            if p is None and name.endswith('_c') and name[:-2] in hosts: p=name[:-2]
            if p is None: p='none'
            if p in by and p not in worlds: continue
            pivot=authored[name]; parent_pivot=authored[p] if p in authored else [0,24,0]
            q=(pose or {}).get(name)
            if isinstance(q,list) and len(q) >= 9:
                # ScriptBone exposes the complete authored position in source
                # pixels. ModelBone stores Y reflected internally, so the
                # source render frame uses (x,-y,z) exactly once.
                local=[float(q[0]),-float(q[1]),float(q[2])]
                r=[float(q[3]),float(q[4]),float(q[5])]
                scl=[float(q[6]),float(q[7]),float(q[8])]
            else:
                local=[pivot[0]-parent_pivot[0],-(pivot[1]-parent_pivot[1]),pivot[2]-parent_pivot[2]]
                r=vec(b.get('rotation'),3,0); scl=[1.,1.,1.]
            # If the parent is a host bone ModelBone.instantiate subtracts its
            # already-instantiated position after the Y flip.
            # A lowercase authored bone may share a spelling with a vanilla
            # host (for example this model's custom ``body``).  Upstream
            # ModelBase resolves the authored model namespace first, so only
            # subtract a host point when the parent is not an authored bone.
            if not (isinstance(q,list) and len(q) >= 9) and p in hosts and p!='none' and p not in authored:
                local=[local[i]-hosts[p][i] for i in range(3)]
            # ModelBone.getQuaternion uses yaw=source.x, pitch=source.y,
            # roll=source.z, hence Rz(source.z)*Ry(source.x)*Rx(source.y).
            # The neutral pose already carries ModelRenderer order [y,x,z].
            # Cube rotation is
            # intentionally handled below with its separate ModelRenderer
            # yRot/xRot assignment.
            local_m=mm(mm(trans(*local),euler(r[0],r[1],r[2])),scale(*scl))
            worlds[name]=mm(worlds.get(p,trans(*hosts.get(p,[0,0,0]))),local_m)
            for c in b.get('cubes',[]) or []:
                origin=vec(c.get('origin'),3,0); size=vec(c.get('size'),3,0); cp=vec(c.get('pivot'),3,0)
                # ElementParser cube placement is relative to the bone authored pivot.
                o=[origin[0]-pivot[0],pivot[1]-origin[1]-size[1],origin[2]-pivot[2]]
                inf=num(c.get('inflate'),0); o=[o[0]-inf,o[1]-inf,o[2]-inf]; sz=[size[0]+2*inf,size[1]+2*inf,size[2]+2*inf]
                # ElementParser leaves ModelRenderer x/y/z at zero when a
                # cube has no explicit pivot.  Only an authored cube pivot
                # creates a local translation.
                if 'pivot' in c:
                    cpos=[cp[0]-pivot[0],pivot[1]-cp[1],cp[2]-pivot[2]]
                else:
                    cpos=[0.,0.,0.]
                cube_m=trans(*cpos)
                # Cube rotation is independent from bone rotation.
                if 'rotation' in c:
                    cr=vec(c.get('rotation'),3,0); cube_m=mm(cube_m,euler(cr[1],cr[0],cr[2]))
                verts=[mp(worlds[name],mp(cube_m,v)) for v in cube_vertices(o,sz)]
                primitives.append({'bone':name,'vertices':verts,'uv':vec(c.get('uv'),2,0),'texture':texture,'size':sz,'raw_size':size,'mirror':bool(c.get('mirror')),'texture_size':[tw,th],'double_face':double})
            pending.remove(item); progress=True
        if not progress:
            notes.append('unresolved modern parent chain: '+','.join(x[0].get('name','?') for x in pending)); break
    sk=doc.get('skeleton',{}) or {}; selected={}
    for key in ('left_arm','right_arm'):
        roots=sk.get(key,[]) if isinstance(sk.get(key),list) else [key]
        s=set(roots); changed=True
        while changed:
            changed=False
            for b,_,_,_ in bones:
                if b.get('parent') in s and b.get('name') not in s: s.add(b.get('name')); changed=True
        selected[key]=sorted(s)
    return {'format':'modern','skeletonType':(doc.get('skeleton',{}) or {}).get('type','biped'),
            'bones':[b for b,_,_,_ in bones],'worlds':worlds,'primitives':primitives,'first_person':selected,'notes':notes}

def project(p,scale,cx,cy):
    x,y,z=p; yaw=math.radians(-35); pitch=math.radians(22)
    a=x*math.cos(yaw)-z*math.sin(yaw); d=x*math.sin(yaw)+z*math.cos(yaw)
    b=y*math.cos(pitch)-d*math.sin(pitch); depth=y*math.sin(pitch)+d*math.cos(pitch)
    # CPM/ModelRenderer uses +Y downward.  The screen camera therefore keeps
    # +Y moving down; using cy-b would vertically mirror every model.
    return (cx+a*scale,cy+b*scale,depth)
def bary(a,b,c,p):
    den=(b[1]-c[1])*(a[0]-c[0])+(c[0]-b[0])*(a[1]-c[1])
    if abs(den)<EPS:return None
    w=((b[1]-c[1])*(p[0]-c[0])+(c[0]-b[0])*(p[1]-c[1]))/den
    v=((c[1]-a[1])*(p[0]-c[0])+(a[0]-c[0])*(p[1]-c[1]))/den
    u=1-w-v
    # w belongs to vertex a, v to b, u to c.  UV and depth must use
    # exactly the same order as the triangle, including its shared edge.
    return w,v,u
def raster_triangle(canvas,zbuf,tri,uvs,img):
    xs=[p[0] for p in tri]; ys=[p[1] for p in tri]; x0=max(0,int(math.floor(min(xs)))); x1=min(canvas.width-1,int(math.ceil(max(xs))))
    y0=max(0,int(math.floor(min(ys)))); y1=min(canvas.height-1,int(math.ceil(max(ys))))
    pix=canvas.load()
    for y in range(y0,y1+1):
        for x in range(x0,x1+1):
            bc=bary(tri[0],tri[1],tri[2],(x+.5,y+.5))
            if bc is None or min(bc)<-EPS: continue
            u,v,w=bc; depth=u*tri[0][2]+v*tri[1][2]+w*tri[2][2]; idx=y*canvas.width+x
            if depth>=zbuf[idx]: continue
            if img is None: sample=(180,180,180,255)
            else:
                tx=u*uvs[0][0]+v*uvs[1][0]+w*uvs[2][0]; ty=u*uvs[0][1]+v*uvs[1][1]+w*uvs[2][1]
                # CPM/Minecraft UV endpoints are texel boundaries.  Sampling
                # with round() leaks the adjacent atlas texel at every u2/v2
                # edge and makes a face appear split by diagonal strips.
                sx=max(0,min(img.width-1,int(math.floor(tx)))); sy=max(0,min(img.height-1,int(math.floor(ty)))); sample=img.getpixel((sx,sy))
            # Transparent CPM atlas texels do not occlude geometry behind them.
            if len(sample)>=4 and sample[3] == 0: continue
            zbuf[idx]=depth
            if len(sample)>=4 and sample[3] < 255:
                old=pix[x,y]; a=sample[3]/255.0
                pix[x,y]=tuple(int(sample[i]*a+old[i]*(1-a)) for i in range(3))+(255,)
            else: pix[x,y]=sample
def render_png(root,scene,out,size=900):
    prim=scene['primitives']; allv=[v for p in prim for v in p['vertices']]
    if not allv: Image.new('RGBA',(size,size),(26,29,35,255)).save(out); return
    xs=[v[0] for v in allv]; ys=[v[1] for v in allv]; span=max(max(xs)-min(xs),max(ys)-min(ys),1); sc=min((size-100)/span,28); cx=size/2-(min(xs)+max(xs))*sc/2; cy=size/2-(min(ys)+max(ys))*sc/2
    canvas=Image.new('RGBA',(size,size),(26,29,35,255)); zbuf=[float('inf')]*(size*size); cache={}; faces=[]
    for p in prim:
        verts=p['vertices']; raw=p.get('raw_size',p.get('size',[0,0,0])); dx,dy,dz=[abs(float(x)) for x in raw]; u,v=vec(p.get('uv'),2,0); mirror=p.get('mirror',False)
        for fname,(ids,uvfn) in FACES.items():
            if scene['format']=='modern' and not p.get('double_face'):
                if fname in ('xmax','xmin') and not (dy or dz): continue
                if fname in ('ymin','ymax') and not (dx or dz): continue
                if fname in ('zmin','zmax') and not (dx or dy): continue
            poly=[verts[i] for i in ids]
            if len({tuple(round(x,6) for x in q) for q in poly})<3: continue
            u1,v1,u2,v2=uvfn(u,v,dx,dy,dz)
            # TexturedQuad maps vertex 0 to (u2,v1), then u1/v1, u1/v2,
            # u2/v2.  This is why treating every face as a generic rectangle
            # produced diagonally shifted textures in the previous renderer.
            uv=[(u2,v1),(u1,v1),(u1,v2),(u2,v2)]
            if mirror: poly=poly[::-1]; uv=uv[::-1]
            screen=[project(q,sc,cx,cy) for q in poly]; img=load_tex(root,p.get('texture'),cache)
            # Minecraft's ModelRenderer normalizes UVs by the authored
            # logical texture size, even when the PNG is high resolution.
            # Sample the physical image at that same normalized coordinate.
            if img is not None:
                tw,th=vec(p.get('texture_size'),2,64,0)
                su,sv=img.width/max(float(tw),1),img.height/max(float(th),1)
                uv=[(u*su,v*sv) for u,v in uv]
            faces.append((sum(q[2] for q in screen)/4,screen,uv,img))
    # Painter order provides deterministic visibility for the orthographic diagnostic.
    for _,screen,uv,img in sorted(faces,key=lambda x:x[0],reverse=True):
        raster_triangle(canvas,zbuf,screen[:3],uv[:3],img); raster_triangle(canvas,zbuf,[screen[0],screen[2],screen[3]],[uv[0],uv[2],uv[3]],img)
    draw=ImageDraw.Draw(canvas); draw.text((12,12),scene.get('label',f"CPM source {scene['format']} | primitives={len(prim)}"),fill=(245,245,245,255)); canvas.save(out)

def main():
    ap=argparse.ArgumentParser(); ap.add_argument('model_dir',type=Path); ap.add_argument('-o','--output',type=Path); ap.add_argument('--kind',choices=('auto','legacy','modern'),default='auto'); ap.add_argument('--size',type=int,default=900); ap.add_argument('--pose-json',type=Path, help='actual CPM pose dump used to omit invisible neutral bones')
    a=ap.parse_args(); root=a.model_dir; doc=read_json(root/'model.json'); kind=a.kind
    if kind=='auto': kind='legacy' if 'bones' in doc and 'parts' not in doc else 'modern'
    pose_doc=json.loads(a.pose_json.read_text(encoding='utf-8')) if a.pose_json and a.pose_json.is_file() else {}
    neutral_pose=pose_doc.get('neutralPose',{})
    scene=legacy_source(root,doc) if kind=='legacy' else modern_source(root,doc,neutral_pose)
    if a.pose_json and a.pose_json.is_file():
        pose=neutral_pose
        hidden={name for name,value in pose.items() if isinstance(value,list) and len(value)>=10 and float(value[9]) < 0.5}
        if hidden:
            by={str(b.get('name')):b for b in scene.get('bones',[])}
            hidden_all=set(hidden); changed=True
            while changed:
                changed=False
                for b in scene.get('bones',[]):
                    if b.get('parent') in hidden_all and b.get('name') not in hidden_all:
                        hidden_all.add(b.get('name')); changed=True
            scene['primitives']=[p for p in scene['primitives'] if p.get('bone') not in hidden_all]
            scene['notes'].append('neutral hierarchy visibility applied: '+','.join(sorted(hidden_all)))
    out=a.output or Path('build/cpm-source-renders')/f'{root.name}-{kind}.png'; out.parent.mkdir(parents=True,exist_ok=True); render_png(root,scene,out,a.size)
    report=out.with_suffix('.json'); report.write_text(json.dumps(scene,ensure_ascii=False,indent=2),encoding='utf-8')
    print(out.resolve()); print(report.resolve());
    for n in scene['notes']: print('NOTE:',n)
if __name__=='__main__': main()
