# JVM Bullet physics

This module executes scalar, single-thread Bullet 3.25 inside Chicory 1.7.5 on
Java 17. It does not load JNI, launch an external engine, or require a platform
physics library. Each world owns isolated WASM memory (256 MiB maximum), solver
state and explicit fixed-step time. Rendering and queries never advance time.
Reactor ABI 5 includes host environments and the construction-time RS kinematic-pair filter;
group/mask filtering and ordinary Bullet contacts remain the default. Packed pose
input preserves quaternion scalars, and packed readback copies raw solver values
so evaluated MMD poses normalize them once, matching the native adapter.

The environment capability is `0x100`. Native Bullet remains the preferred
installed accelerator; this reactor provides the same host contacts and forces
when native is absent or rejected. Both build the shared visual policy in
`runtime/native/src/physics_environment.h`; standard Java builds use the bundled
reactor and do not invoke a C++ toolchain.

The public contract is in `scene-api`. Primitive rigid bodies, collision masks,
6DOF spring and other supported joint constructors, triangle/rope soft bodies,
pins, anchors and impulses are exposed. This is a solver substrate, not a claim
that MMD bone scheduling, PMX decoding or the Minecraft host are complete.

Preview worlds record bounded input journals. Checkpoints reconstruct a fresh
solver and replay all commands from initialization, including contacts and soft
bodies, rather than approximating a checkpoint with transforms and velocities.
Restoration is transactional but its cost grows with history. Live worlds can
disable journals with `withoutReplay()`; a live world then cannot create a
checkpoint. A journal budget error is explicit and never silently drops inputs.

Maintainers rebuild the checked-in reactor with WASI SDK 34:

```powershell
python runtime/wasm-physics/engine/build.py --sdk <WASI-SDK-34-directory>
```

The build fetches Bullet commit `2c204c49e56ed15ec5fcfa71d199ab6d6570b3f5`, verifies
cached source hashes, compiles headless libraries and updates the reactor and
SHA-256 sidecar. Bullet's zlib license is bundled in the prerequisite's resources.
The build uses a marked copy with one source repair: Bullet 3.25's bottom-up
soft-body BVH loop does not terminate when its remaining components have no
adjacent pairs. The repair adds adjacency only between BVH roots in that case.
It does not add physical links, weld vertices or turn off collisions. Original
and patched source hashes are included in `ysm-patches.json` in the license
resources. The verified upstream cache remains unchanged.
SDK 34's Preview 1 reactor CRT is selected explicitly; player builds do not need
the SDK, CMake, Python or a C++ compiler.

The independent contact fixture is generated with native Bullet directly:

```powershell
python runtime/wasm-physics/engine/build_oracle.py
```

It is a maintainer operation, separate from ordinary JVM tests. Portability and
performance claims remain limited to the test environments actually exercised.
