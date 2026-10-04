# Blender reference

`weighted-morph.fbx` and `weighted-morph.json` are project-generated fixtures, licensed under Apache-2.0 with this project. `manifest.json` records Blender's build and content hashes. The generating script is [blender_fixture.py](../../../../oracle/blender_fixture.py).

The reference positions come directly from Blender's evaluated dependency graph before any FBX re-import. The model combines a shape key, animated bone hierarchy and five nonzero skin weights per vertex. The 25 frames are compared in metres after the explicit Blender-to-Y-up basis conversion. Ordinary tests read the frozen reference and do not launch Blender.
