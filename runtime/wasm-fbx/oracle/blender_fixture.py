"""Project-owned fixture and frozen reference from Blender's unmodified evaluated depsgraph.

Run with Blender 4.5.3 --background --factory-startup --python blender_fixture.py.
Ordinary Java tests do not require Blender or execute this script.
"""
import hashlib
import json
import math
from pathlib import Path
import bpy
from mathutils import Vector

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / 'src/test/resources/blender-oracle'


def main():
    if bpy.app.version != (4, 5, 3):
        raise RuntimeError('This reference is pinned to Blender 4.5.3')
    OUTPUT.mkdir(parents=True, exist_ok=True)
    bpy.ops.object.select_all(action='SELECT')
    bpy.ops.object.delete(use_global=False)
    scene = bpy.context.scene
    scene.render.fps = 24
    scene.frame_start, scene.frame_end = 1, 25
    vertices, faces = [], []
    for ring in range(6):
        for side in range(6):
            a = side * math.tau / 6
            vertices.append((math.cos(a) * .3, math.sin(a) * .3, ring * .4))
    for ring in range(5):
        for side in range(6):
            a = ring * 6 + side
            b = ring * 6 + (side + 1) % 6
            faces.append((a, b, b + 6, a + 6))
    data = bpy.data.meshes.new('WeightedMorphMesh')
    data.from_pydata(vertices, [], faces)
    data.update()
    obj = bpy.data.objects.new('Avatar', data)
    scene.collection.objects.link(obj)
    for polygon in data.polygons:
        polygon.use_smooth = True
    uv = data.uv_layers.new(name='SurfaceUV')
    for loop in data.loops:
        vertex = data.vertices[loop.vertex_index].co
        uv.data[loop.index].uv = (math.atan2(vertex.y, vertex.x) / math.tau + .5, vertex.z / 2)
    obj.shape_key_add(name='Basis')
    morph = obj.shape_key_add(name='Bulge')
    for i, point in enumerate(morph.data):
        point.co.x += .14 * (1 + math.sin(i * .37))
        point.co.y += .1 * math.cos(i * .23)
        point.co.z += .07 * math.sin(i * .11)
    for frame, value in [(1, 0), (13, .8), (25, .2)]:
        morph.value = value
        morph.keyframe_insert('value', frame=frame)
    armature = bpy.data.armatures.new('Rig')
    rig = bpy.data.objects.new('Rig', armature)
    scene.collection.objects.link(rig)
    bpy.context.view_layer.objects.active = rig
    rig.select_set(True)
    bpy.ops.object.mode_set(mode='EDIT')
    for i in range(5):
        bone = armature.edit_bones.new('Joint' + str(i))
        bone.head = (0, 0, i * .4)
        bone.tail = (0, 0, (i + 1) * .4)
        if i:
            bone.parent = armature.edit_bones['Joint' + str(i - 1)]
    bpy.ops.object.mode_set(mode='OBJECT')
    for i in range(5):
        group = obj.vertex_groups.new(name='Joint' + str(i))
        for vertex in data.vertices:
            weights = [1 / (1 + abs(vertex.co.z - j * .4) * 2) for j in range(5)]
            group.add([vertex.index], weights[i] / sum(weights), 'REPLACE')
    modifier = obj.modifiers.new('Skin', 'ARMATURE')
    modifier.object = rig
    modifier.use_deform_preserve_volume = False
    for i, bone in enumerate(rig.pose.bones):
        bone.rotation_mode = 'XYZ'
        for frame in [1, 13, 25]:
            phase = (frame - 1) / 24
            bone.rotation_euler = (.22 * math.sin(phase * math.pi + i * .3), .13 * phase * (i - 2), .17 * phase)
            bone.keyframe_insert('rotation_euler', frame=frame)
    material = bpy.data.materials.new('Surface')
    material.diffuse_color = (.3, .6, .8, 1)
    data.materials.append(material)
    scene.frame_set(1)
    bpy.ops.object.select_all(action='DESELECT')
    obj.select_set(True)
    rig.select_set(True)
    bpy.context.view_layer.objects.active = obj
    bpy.ops.export_scene.fbx(filepath=str(OUTPUT / 'weighted-morph.fbx'), use_selection=True,
        object_types={'MESH', 'ARMATURE'}, use_mesh_modifiers=False, add_leaf_bones=False,
        bake_anim=True, bake_anim_use_all_actions=False, bake_anim_use_nla_strips=False,
        bake_anim_step=1, bake_anim_simplify_factor=0, axis_forward='-Z', axis_up='Y', path_mode='STRIP')
    refs = []
    for frame in range(1, 26):
        scene.frame_set(frame)
        graph = bpy.context.evaluated_depsgraph_get()
        evaluated = obj.evaluated_get(graph)
        mesh = evaluated.to_mesh(preserve_all_data_layers=True, depsgraph=graph)
        try:
            positions = []
            for loop in mesh.loops:
                p = evaluated.matrix_world @ mesh.vertices[loop.vertex_index].co
                positions.extend((p.x, p.z, -p.y))
            # The FBX exporter preserves the scene's absolute frame time (frame / fps).
            refs.append({'frame': frame, 'seconds': frame / 24, 'positions': positions})
        finally:
            evaluated.to_mesh_clear()
    (OUTPUT / 'weighted-morph.json').write_text(json.dumps(refs, separators=(',', ':')) + '\n')
    manifest = {'generator': 'Blender evaluated depsgraph', 'version': bpy.app.version_string,
        'buildHash': bpy.app.build_hash.decode(), 'fixtureLicense': 'Apache-2.0 (project-generated fixture)',
        'space': 'Blender world (x,y,z) mapped to right-handed Y-up (x,z,-y), metres',
        'note': 'Five influences per vertex; shape keys and linear armature deformation evaluated by Blender before export comparison.',
        'files': {name: hashlib.sha256((OUTPUT / name).read_bytes()).hexdigest() for name in ['weighted-morph.fbx', 'weighted-morph.json']}}
    (OUTPUT / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    print('Saved FBX + 25 original Blender depsgraph frames to', OUTPUT)


main()
