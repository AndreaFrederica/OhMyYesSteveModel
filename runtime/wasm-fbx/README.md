# Portable FBX reactor

This module is part of Oh My YSM Lib. `SceneProvider` exposes immutable FBX source documents, bounded evaluation sessions, explicit texture dependencies and shared `GeometryFrame` projection. Minecraft host integration is still separate work.

The scalar reactor uses unmodified ufbx source pinned by revision and per-file SHA-256 in `engine/sources.json`. It executes under Chicory in Java 17. No JNI, subprocess, native discovery, preopened directory or inherited environment is used. The file callback always refuses implicit external file access. Texture dependencies use the caller's `AssetResolver`; `ProjectAssetResolver` can explicitly relocate workstation paths and quote literal percent/hash characters. Geometry caches still require a separate adapter.

The internal snapshot preserves source element properties and connections, node/geometry matrices, per-corner attributes (including optional W), all UV/color sets, original faces and triangulation, skin weights and per-vertex DQ blending, animation layers, curves/tangents/extrapolation, texture paths/content, intermediate morph shapes, Blender shape weights, named FBX/PBR material maps/features, cameras, lights/shadows, constraint targets and reader warnings. Public records own double data and original encoded bytes. Snapshot JSON is a private transfer protocol, not a model interchange format or a new authored artifact schema.

Source scenes remain immutable. Each animation evaluation creates a separate ufbx scene and replaces the previous evaluated scene only after successful evaluation. Memory/output/element budgets and string limits constrain the bridge. The reactor has a 512 MiB linear-memory ceiling; allocator and transfer budgets are smaller. These bounds are not a performance guarantee for real avatars.

The draw adapter keeps authored corner normals, all skin influences and UV directions. Geometry is evaluated per node instance before explicit conversion to float draw data; source doubles remain accessible. Material indices use the instance's material table. Consecutive faces are grouped without reordering transparent source geometry. The shared draw frame is already deformed and must not be skinned a second time.

`BIND_WORLD` follows FBX world binding. `FOLLOW_MESH_NODE` additionally compensates mesh-node motion using the source bind pose, matching the upstream Maya references marked `transform_skin`. This is an explicit source-workflow choice. Missing compensation bind poses fail. Non-normalized cluster modes, DQ transforms containing shear, multiple skins, constraints, geometry caches, subdivision and non-polygon geometry are explicitly rejected for playback. Their original bytes/properties remain available. These are unfinished compatibility cases, not a claim that the requested full backend is complete.

Maya OBJ source exports verify intermediate shapes, linear/DQ/blended-DQ positions and mesh-instance motion; 25 Blender depsgraph frames verify combined morph and five-weight skinning; upstream light reference values verify animated color/intensity. Separate tests verify authored normals, source retention, aggregate budgets, time jumps and the actual shaded distribution without Forge/YSM native. Full DCC normal/shader image matching, custom shaders and complex deformation chains need further validation.

Maintainers rebuild the module with:

```text
python engine/build.py --sdk <WASI-SDK-34-directory>
```

Ordinary builds and tests consume the bundled reactor. Test inputs come from the same pinned ufbx repository, with hashes, source URLs and license in the fixture directory. Upstream source tests also provide static pivot reference values. `oracle/blender_fixture.py` creates project-owned FBX and frozen Blender depsgraph output with five weights per vertex and shape keys; regenerating this reference requires Blender 4.5.3, never a player runtime dependency. These tests do not establish full FBX compatibility.
