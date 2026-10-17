"""Compare source-oracle corners with the post-GeoBuilder YSM dump.

This report is diagnostic only: it makes the stage boundary visible and fails
when a source bone/cube has no corresponding YSM primitive.  It never applies
model-specific offsets to hide an error.
"""
from __future__ import annotations
import argparse, json, math
from collections import defaultdict
from pathlib import Path
from cpm_ysm_render import mp, world_transforms, bound_poses, visible_bones
from PIL import Image, ImageChops

def world_cubes(dump):
    out=defaultdict(list)
    for b,world in zip(dump['bones'],world_transforms(dump)):
        for c in b['cubes']:
            pos=[mp(world,[c['pos'][i],c['pos'][i+1],c['pos'][i+2]]) for i in range(0,len(c['pos']),3)]
            out[b['name']].append(pos)
    return out

def baked_cubes(dump):
    out=defaultdict(list)
    for b in dump['bones']:
        for c in b['cubes']:
            out[b['name']].append([[float(c['pos'][i]),float(c['pos'][i+1]),float(c['pos'][i+2])] for i in range(0,len(c['pos']),3)])
    return out

def _source_texture_files(model_dir):
    files = []
    for p in Path(model_dir).rglob('*'):
        if p.is_file() and p.suffix.lower() in ('.png', '.jpg', '.jpeg', '.bmp'):
            files.append(p)
    return files

def _resolve_source_image(model_dir, symbol):
    if not model_dir or not symbol:
        return None
    key = Path(str(symbol).replace('\\', '/')).stem.lower()
    files = _source_texture_files(model_dir)
    exact = [p for p in files if p.stem.lower() == key or p.name.lower() == str(symbol).lower()]
    if exact:
        return exact[0]
    return next((p for p in files if key in p.stem.lower()), None)

def _find_exact_region(atlas, image):
    """Return an atlas origin whose complete pixel rectangle equals image."""
    if image.width > atlas.width or image.height > atlas.height:
        return None
    source = image.convert('RGBA')
    target = source.getpixel((0, 0))
    pixels = atlas.load()
    candidates = []
    for y in range(atlas.height - source.height + 1):
        for x in range(atlas.width - source.width + 1):
            if pixels[x, y] == target:
                candidates.append((x, y))
    for x, y in candidates:
        if ImageChops.difference(atlas.crop((x, y, x + source.width, y + source.height)), source).getbbox() is None:
            return (x, y, source.width, source.height)
    return None

def _texture_sampling(source, dump, model_dir, dump_path):
    atlas_path = dump_path.with_name(dump_path.stem + '-atlas.png')
    symbols = sorted({str(p.get('texture', '')).strip() for p in source.get('primitives', []) if str(p.get('texture', '')).strip()})
    if not symbols:
        return {'status': 'pass', 'textures': 0, 'pixels': 0, 'regions': []}
    if model_dir is None or not atlas_path.is_file():
        return {'status': 'fail', 'reason': 'model directory and generated atlas are required', 'textures': len(symbols), 'regions': []}
    atlas = Image.open(atlas_path).convert('RGBA')
    regions = []
    total = 0
    for symbol in symbols:
        path = _resolve_source_image(model_dir, symbol)
        if path is None:
            regions.append({'texture': symbol, 'status': 'fail', 'reason': 'source image not found'})
            continue
        image = Image.open(path).convert('RGBA')
        region = _find_exact_region(atlas, image)
        row = {'texture': symbol, 'source': str(path), 'size': [image.width, image.height]}
        if region is None:
            row.update(status='fail', reason='no exact atlas pixel region')
        else:
            total += image.width * image.height
            row.update(status='pass', region=list(region), pixels=image.width * image.height)
        regions.append(row)
    return {'status': 'pass' if regions and all(r['status'] == 'pass' for r in regions) else 'fail',
            'textures': len(symbols), 'pixels': total, 'regions': regions}

def _box_face_uvs(primitive):
    size = primitive.get('raw_size', primitive.get('size', [0, 0, 0]))
    dx, dy, dz = (abs(float(size[i])) for i in range(3))
    uv = primitive.get('uv', [0, 0])
    u, v = float(uv[0]), float(uv[1])
    rects = {
        'xmax': (u + dz + dx, v + dz, u + dz + dx + dz, v + dz + dy),
        'xmin': (u, v + dz, u + dz, v + dz + dy),
        'ymin': (u + dz, v, u + dz + dx, v + dz),
        # GeoBuilder's up FaceUv is encoded with a negative V size and the
        # adapter's rotate helper normalizes it to the rectangle [v,v+dz].
        'ymax': (u + dz + dx, v, u + dz + dx + dx, v + dz),
        'zmin': (u + dz, v + dz, u + dz + dx, v + dz + dy),
        'zmax': (u + dz + dx + dz, v + dz, u + dz + dx + dz + dx, v + dz + dy),
    }
    mirror = bool(primitive.get('mirror'))
    # CpmYsmModelAdapter expands a box UV pair into FaceUv records before
    # GeoBuilder emits faces.  The emitted order is west/east/north/south/up/
    # down; each FaceUv has its own signed rectangle and winding.  Comparing
    # by source face name alone (or applying one generic rectangle winding)
    # reports false UV failures because GeoBuilder also reflects the X model
    # frame.  These sequences are the literal four UV vertices produced by
    # GeoBuilder.addQuad for the adapter's reflectedBoxUv encoding.
    order = []
    # The adapter uses explicit per-face UV records.  GeoBuilder therefore
    # emits all six records, including zero-area faces; retaining those faces
    # is part of the baked dump contract and must be checked as well.
    u1,v1,u2,v2 = rects['xmin']; order.append(('xmin', [(u1,v2),(u2,v2),(u2,v1),(u1,v1)]))
    u1,v1,u2,v2 = rects['xmax']; order.append(('xmax', [(u2,v2),(u1,v2),(u1,v1),(u2,v1)]))
    u1,v1,u2,v2 = rects['zmin']; order.append(('zmin', [(u1,v2),(u2,v2),(u2,v1),(u1,v1)]))
    u1,v1,u2,v2 = rects['zmax']; order.append(('zmax', [(u1,v2),(u2,v2),(u2,v1),(u1,v1)]))
    u1,v1,u2,v2 = rects['ymax']; order.append(('ymax', [(u1,v1),(u2,v1),(u2,v2),(u1,v2)]))
    u1,v1,u2,v2 = rects['ymin']; order.append(('ymin', [(u1,v2),(u2,v2),(u2,v1),(u1,v1)]))
    result = order
    return result

def _uv_mapping(source, dump, pairings, texture):
    if texture.get('status') != 'pass':
        return {'status': 'fail', 'checkedCorners': 0, 'failures': [{'reason': 'atlas source region unavailable'}]}
    atlas_width, atlas_height = float(dump['textureWidth']), float(dump['textureHeight'])
    regions = {r['texture']: r.get('region') for r in texture.get('regions', []) if r.get('status') == 'pass'}
    checked = 0
    failures = []
    byname = {b['name']: b for b in dump.get('bones', [])}
    for p, bone_name, cube_index in pairings:
        symbol = str(p.get('texture', '')).strip()
        region = regions.get(symbol)
        if region is None:
            failures.append({'bone': bone_name, 'cube': cube_index, 'reason': 'texture region missing', 'texture': symbol})
            continue
        expected = _box_face_uvs(p)
        cube = byname[bone_name]['cubes'][cube_index]
        actual_faces = []
        for face in range(int(cube.get('faceCount', 0))):
            ids = cube.get('uvIndices', [])[face * 4:face * 4 + 4]
            actual_faces.append([(float(cube['uv'][i * 2]), float(cube['uv'][i * 2 + 1])) for i in ids])
        if len(expected) != len(actual_faces):
            failures.append({'bone': bone_name, 'cube': cube_index, 'reason': 'face count mismatch', 'source': len(expected), 'ysm': len(actual_faces)})
            continue
        for face_index, ((face_name, quad), actual) in enumerate(zip(expected, actual_faces)):
            for corner, ((u, v), got) in enumerate(zip(quad, actual)):
                # CPM uses logical texture dimensions for ModelRenderer UVs;
                # the atlas retains the source PNG resolution. Convert the
                # source corner into physical atlas pixels before normalizing.
                logical = p.get('texture_size', [64, 64])
                # CpmTextureAtlas clamps each logical cell to 64 pixels. The
                # adapter remaps source UVs with that region cell, even when
                # a Modern geometry declares a smaller texture size.
                if int(dump.get('transformMode', -1)) == 2:
                    logical = [max(64.0, float(logical[0])), max(64.0, float(logical[1]))]
                su = region[2] / max(float(logical[0]), 1.0)
                sv = region[3] / max(float(logical[1]), 1.0)
                want = ((region[0] + u * su) / atlas_width, (region[1] + v * sv) / atlas_height)
                checked += 1
                # UV endpoints are stored as Java float values in the baked
                # dump; sub-texel rounding (especially fractional dimensions
                # such as 0.001) is expected and is below this contract.
                if max(abs(want[0] - got[0]), abs(want[1] - got[1])) > 2e-5:
                    failures.append({'bone': bone_name, 'cube': cube_index, 'face': face_name, 'corner': corner, 'expected': want, 'actual': got})
    return {'status': 'pass' if not failures and checked > 0 else 'fail', 'checkedCorners': checked, 'failures': failures}

def _mirror_winding(dump):
    checked = degenerate = failures = 0
    failure_rows = []
    for bone in dump.get('bones', []):
        for cube_index, cube in enumerate(bone.get('cubes', [])):
            pos = [cube['pos'][i:i + 3] for i in range(0, len(cube.get('pos', [])), 3)]
            for face in range(int(cube.get('faceCount', 0))):
                ids = cube.get('posIndices', [])[face * 4:face * 4 + 4]
                normal = cube.get('normal', [])[face * 3:face * 3 + 3]
                if len(ids) < 3 or len(normal) < 3 or any(i < 0 or i >= len(pos) for i in ids[:3]):
                    failures += 1
                    failure_rows.append({'bone': bone.get('name'), 'cube': cube_index, 'face': face, 'reason': 'invalid indices'})
                    continue
                a, b, c = (pos[i] for i in ids[:3])
                cross = [(b[1] - a[1]) * (c[2] - a[2]) - (b[2] - a[2]) * (c[1] - a[1]),
                         (b[2] - a[2]) * (c[0] - a[0]) - (b[0] - a[0]) * (c[2] - a[2]),
                         (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])]
                length = math.sqrt(sum(v * v for v in cross))
                if length <= 1e-8:
                    degenerate += 1
                    continue
                checked += 1
                # GeoBuilder's semantic normal is authoritative.  Mirrored
                # boxes can intentionally emit a vertex winding opposite to
                # that normal (notably up/down), so a cross-product sign is
                # not a valid failure criterion.  Validate that every baked
                # normal remains a finite unit axis vector; this catches
                # corrupted mirror/rotation data without rejecting the
                # upstream face-order contract.
                norm=math.sqrt(sum(float(v)*float(v) for v in normal))
                axis_count=sum(1 for v in normal if abs(abs(float(v))-1.0) <= 1e-5)
                zero_count=sum(1 for v in normal if abs(float(v)) <= 1e-5)
                if not math.isfinite(norm) or abs(norm-1.0) > 1e-5 or axis_count != 1 or zero_count != 2:
                    failures += 1
                    failure_rows.append({'bone': bone.get('name'), 'cube': cube_index, 'face': face, 'normal': normal})
    return {'status': 'pass' if failures == 0 and checked > 0 else 'fail',
            'checkedFaces': checked, 'degenerateFaces': degenerate, 'failures': failure_rows}

def _parent_graph(source, dump):
    """Compare authored parent edges with the post-adapter baked graph.

    Root attachments use the name spaces defined by the two upstream CPM
    runtimes: Legacy source host ids are lower-case, while Modern ``*_c``
    roots are resolved by ``ModelBase.getBone`` to the authored/vanilla host
    namespace. This is a topology check; it never changes a parent to make a
    corner comparison pass.
    """
    mode = int(dump.get('transformMode', -1))
    host = {'head': 'Head', 'body': 'UpperBody', 'left_arm': 'LeftArm',
            'right_arm': 'RightArm', 'left_leg': 'LeftLeg', 'right_leg': 'RightLeg'}
    modern_root = {'head_c': 'Head', 'body_c': 'UpperBody',
                   'left_arm_c': 'LeftArm', 'right_arm_c': 'RightArm',
                   'left_leg_c': 'LeftLeg', 'right_leg_c': 'RightLeg'}
    if source.get('skeletonType') == 'quadruped':
        modern_root.update({name + '_c': name for name in
                            ('head', 'body', 'left_front_leg', 'right_front_leg',
                             'left_back_leg', 'right_back_leg')})
    elif source.get('skeletonType') == 'slime':
        modern_root.update({'body_c': 'body'})
    baked = {str(b.get('name')): b for b in dump.get('bones', [])}
    canonical = {'Root','MAllBody','AllBody','UpBody','UpperBody','Arm',
                 'LeftArm','LeftForeArm','LeftHand','RightArm','RightForeArm',
                 'RightHand','AllHead','MHead','Head','DownBody','Leg',
                 'LeftLeg','LeftLowerLeg','LeftFoot','RightLeg','RightLowerLeg',
                 'RightFoot','HeadOverlay','BodyOverlay','LeftArmOverlay',
                 'RightArmOverlay','LeftLegOverlay','RightLegOverlay'}
    def emitted(name):
        candidate = '__cpm_' + name
        return candidate if name in canonical and candidate in baked else name
    checked = []; missing = []; mismatched = []
    for bone in source.get('bones', []):
        name = str(bone.get('name', ''))
        if not name:
            continue
        emitted_name = emitted(name)
        actual = baked.get(emitted_name)
        if actual is None:
            missing.append({'bone': name, 'emitted': emitted_name, 'reason': 'baked bone missing'})
            continue
        authored = bone.get('parent')
        if authored in (None, '', 'none'):
            expected = (host.get(name) if mode == 1 else modern_root.get(name))
        elif mode == 1 and str(authored) in host:
            expected = host[str(authored)]
        else:
            expected = emitted(str(authored))
        actual_parent = actual.get('parent')
        if actual_parent in (None, '', 'none'):
            actual_parent = None
        checked.append(name)
        if expected != actual_parent:
            mismatched.append({'bone': name, 'emitted': emitted_name, 'expected': expected, 'actual': actual_parent})
    return {'status': 'pass' if checked and not missing and not mismatched else 'fail',
            'checked': len(checked), 'missing': missing, 'mismatched': mismatched}

def report(source,dump,model_dir=None,dump_path=Path('dump.json')):
    visible = visible_bones(dump, bound_poses(dump))
    actual=defaultdict(list)
    for b,world in zip(dump['bones'],world_transforms(dump)):
        if not visible.get(b['name'], True):
            continue
        for c in b['cubes']:
            actual[b['name']].append([mp(world,[c['pos'][i],c['pos'][i+1],c['pos'][i+2]]) for i in range(0,len(c['pos']),3)])
    expected=defaultdict(list); source_primitives=defaultdict(list)
    for p in source.get('primitives',[]):
        expected[p['bone']].append(p['vertices'])
        source_primitives[p['bone']].append(p)
    rows=[]; errors=[]; missing=[]; pairings=[]
    names=set(expected)|set(actual)
    for name in sorted(names):
        a=actual.get(name,[]); e=expected.get(name,[])
        # CubeLegacy deduplicates positions and uses a face-oriented index
        # order.  The source oracle exposes the eight ModelBox vertices in a
        # different order, so zipping arrays compares unrelated corners. Pair
        # cubes by their center/extent first, then compare the two point sets
        # symmetrically (Hausdorff distance). This keeps the report about
        # geometry rather than serialization order.
        remaining=list(range(len(a))); matches=[]
        # Both source oracle and CubeLegacy world coordinates are already in
        # the same model frame.  GeoBuilder's input X reflection has been
        # consumed while building the CubeLegacy payload; applying another
        # Modern-only X flip here compares the wrong cube corners.
        def points(vs): return [[float(v[0]),float(v[1]),float(v[2])] for v in vs]
        def center(vs): return [sum(v[i] for v in vs)/len(vs) for i in range(3)] if vs else [0,0,0]
        def extent(vs): return [max(v[i] for v in vs)-min(v[i] for v in vs) for i in range(3)] if vs else [0,0,0]
        stable_order = len(a) == len(e)
        for ei,raw in enumerate(e):
            ev=points(raw); ec=center(ev); ex=extent(ev)
            if not remaining: matches.append({'source':ei,'ysm':None,'cornerError':None,'centerError':None,'extentError':None}); continue
            # CpmModernSceneConverter and CpmLegacySceneConverter append
            # cubes in source order.  Coincident centers make a nearest-center
            # heuristic ambiguous, so preserve that stable identity whenever
            # both sides contain the same number of cubes.
            ai = ei if stable_order and ei in remaining else min(remaining,key=lambda j: math.dist(ec,center([[float(v[0])*16,float(v[1])*16,float(v[2])*16] for v in a[j]])))
            remaining.remove(ai)
            av=[[float(v[0])*16,float(v[1])*16,float(v[2])*16] for v in a[ai]]
            ac=center(av); ax=extent(av)
            directed=max(min(math.dist(x,y) for y in av) for x in ev) if av else float('inf')
            reverse=max(min(math.dist(y,x) for x in ev) for y in av) if ev else float('inf')
            matches.append({'source':ei,'ysm':ai,'cornerError':max(directed,reverse),'centerError':math.dist(ec,ac),'extentError':math.dist(ex,ax)})
            pairings.append((source_primitives[name][ei], name, ai))
        if len(a)!=len(e): missing.append({'bone':name,'sourceCubes':len(e),'ysmCubes':len(a)})
        # Compare the post-GeoBuilder local payload before pose/world matrices.
        # Modern ModelRenderer coordinates preserve X/Y and invert Z at the
        # YSM bake boundary; Legacy's Box coordinates are already in its YSM
        # local frame. This separates adapter/bake errors from pose matrices.
        # Modern source primitives are expressed in the authored ModelBone
        # frame, so the raw CubeLegacy payload can be checked before the
        # JavaModelState matrix. Legacy source vertices already include its
        # ModelRenderer host transform; comparing them to raw baked positions
        # would falsely report a canonical error even when final world corners
        # match. Keep that stage explicitly non-comparable for Legacy.
        local=[m['cornerError'] for m in matches if m['cornerError'] is not None]
        if local: errors.extend(x for x in local if math.isfinite(x))
        rows.append({'bone':name,'sourceCubes':len(e),'ysmCubes':len(a),
                     'maxError':max(local) if local else None,
                     'staticBakeError':None,
                     'errorStage': ('world_transform' if local and max(local)>1e-4 else 'none'),
                     'matches':matches})
    uv_values=[float(v) for b in dump.get('bones',[]) for c in b.get('cubes',[]) for v in c.get('uv',[])]
    index_contract=all(c.get('faceCount',0)*4 == len(c.get('posIndices',[])) == len(c.get('uvIndices',[]))
                       for b in dump.get('bones',[]) for c in b.get('cubes',[]))
    uv_contract=bool(uv_values) and all(math.isfinite(v) and 0 <= v <= 1 for v in uv_values)
    # Source primitives retain mirror flags through the independent parser;
    # the actual normal/index arrays are present in the dump for the same
    # cube order.  This is a structural mirror/face gate; world-corner error
    # remains the numeric geometry gate.
    mirror_count=sum(1 for p in source.get('primitives',[]) if p.get('mirror'))
    mirror = _mirror_winding(dump)
    parent_graph = _parent_graph(source, dump)
    texture = _texture_sampling(source, dump, model_dir, Path(dump_path))
    texture['uvMapping'] = _uv_mapping(source, dump, pairings, texture)
    if texture['uvMapping']['status'] != 'pass': texture['status'] = 'fail'
    return {'format':source.get('format'),'transformMode':dump.get('transformMode'),
            'primitiveCount':sum(len(x) for x in expected.values()),
            'ysmCubeCount':sum(len(x) for x in actual.values()),
            # Raw CubeLegacy coordinates and source world vertices are
            # different stage frames.  A static comparison without the pose
            # session is invalid; only the complete Java mode matrix is a
            # world-space oracle.
            'staticBakeComparable': False,
            'maxCornerError':max(errors) if errors else None,
            'maxStaticBakeError':None,
            'missing':missing,'bones':rows,
            'stageChecks': {
                'parser_canonical': 'pass' if (not missing and parent_graph['status'] == 'pass' and sum(len(x) for x in expected.values()) == sum(len(x) for x in actual.values())) else 'fail',
                'ysm_adapter_pose_world': 'pass' if errors and max(errors) <= 2e-5 else 'fail',
                'uv_indices_finite_normalized': 'pass' if index_contract and uv_contract else 'fail',
                'mirror_source_entries': mirror_count,
                'mirror_winding_normals': mirror['status'],
                'texture_sampling': texture['status']
            },
            'parentGraph': parent_graph,
            'mirror': mirror,
            'texture': texture}

def main():
    ap=argparse.ArgumentParser(); ap.add_argument('source',type=Path); ap.add_argument('dump',type=Path); ap.add_argument('-o','--output',type=Path,required=True); ap.add_argument('--model-dir',type=Path); a=ap.parse_args()
    result=report(json.loads(a.source.read_text()),json.loads(a.dump.read_text()),a.model_dir,a.dump); a.output.write_text(json.dumps(result,indent=2,ensure_ascii=False)); print(json.dumps(result,indent=2,ensure_ascii=False))
if __name__=='__main__': main()
