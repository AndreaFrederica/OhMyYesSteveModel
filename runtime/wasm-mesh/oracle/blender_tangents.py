"""Freeze Blender 4.5.3 loop tangents. Ordinary tests never require Blender."""
import hashlib
import json
import math
from pathlib import Path
import bpy

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / 'src/test/resources/blender-tangents'

def sample(name, positions, faces, uv, smooth):
    mesh = bpy.data.meshes.new(name)
    mesh.from_pydata(positions, [], faces)
    mesh.update()
    for polygon in mesh.polygons:
        polygon.use_smooth = smooth
    layer = mesh.uv_layers.new(name='NormalMapUV')
    for loop in mesh.loops:
        layer.data[loop.index].uv = uv[loop.index]
    # calc_tangents adds a CustomData layer and can invalidate previously held RNA layer wrappers.
    source_uv = [tuple(layer.data[loop.index].uv) for loop in mesh.loops]
    mesh.calc_tangents(uvmap='NormalMapUV')
    result = {'name': name, 'position': [], 'normal': [], 'uv': [], 'tangent': []}
    for loop in mesh.loops:
        result['position'].extend(mesh.vertices[loop.vertex_index].co)
        result['normal'].extend(loop.normal)
        result['uv'].extend(source_uv[loop.index])
        result['tangent'].extend([*loop.tangent, loop.bitangent_sign])
    bpy.data.meshes.remove(mesh)
    return result

def main():
    if bpy.app.version != (4, 5, 3):
        raise RuntimeError('Pinned Blender 4.5.3 required')
    OUTPUT.mkdir(parents=True, exist_ok=True)
    positions = [(x, y, .3*math.sin(x*.8)*math.cos(y*.7)) for y in range(4) for x in range(5)]
    faces = []
    for y in range(3):
        for x in range(4):
            v = y*5+x
            faces.extend([(v, v+1, v+5), (v+1, v+6, v+5)])
    uv = []
    for face in faces:
        for v in face:
            x, y, z = positions[v]
            # Mirrored islands share a boundary; loop output must not be averaged by position.
            uv.append((abs(x-2)/2 + .13*y, y/3 + .05*x*x))
    refs = [sample('curved_mirrored_smooth', positions, faces, uv, True),
            sample('curved_flat', positions, faces, uv, False)]
    bent = [(x+.15*math.sin(y), y, z+.4*math.cos(x+y)) for x, y, z in positions]
    refs.append(sample('morph_flat', bent, faces, uv, False))
    path = OUTPUT / 'corners.json'
    path.write_text(json.dumps(refs, indent=2)+'\n', encoding='utf-8')
    manifest = {'reference': 'Unmodified Blender 4.5.3 Mesh.calc_tangents',
                'fixture': 'Project-owned synthetic triangle meshes; no third-party model assets',
                'files': {path.name: hashlib.sha256(path.read_bytes()).hexdigest()}}
    (OUTPUT / 'manifest.json').write_text(json.dumps(manifest, indent=2)+'\n', encoding='utf-8')

if __name__ == '__main__':
    main()
