# Optional Bullet accelerator

Java 17 adapter for the independently built `ysmlib_physics` library. Native ABI 6
uses float32, one solver thread and feature bits `0x1ff`. Each world owns its Bullet
objects; no STL types or C++ exceptions cross the public C ABI. Runtime selection
prefers an installed accelerator and falls back to JVM/WASM on startup rejection.

Install the verified binary under `ysmlib/natives/windows-x64/ysmlib_physics.dll`
or `ysmlib/natives/linux-x64/libysmlib_physics.so`, relative to the game working
directory. Keep the distribution's licenses, source archive and provenance with
the binary. `ysm.runtime.nativeDir` changes the discovery root;
`ysm.runtime.physicsLibrary` overrides the single library path.
`ysm.runtime.javaOnly=true` skips discovery/loading. F3 reports the provider,
ABI/features and cached fallback reason. A failed running world remains isolated;
startup fallback never transfers a partially advanced world to another solver.

Use an explicit native library when validating the accelerator:

```powershell
$env:JAVA_HOME='<JDK-17-directory>'
.\gradlew.bat '-Dysm.native.physics.library=<absolute-library-path>' -p runtime :ysm-runtime-native-physics:test --configuration-cache
```

From `runtime/native`, `pixi run package-physics` builds and tests the library and
stages its binary, public header, licenses, source archive and SHA-256 provenance.
Portable joint atan2/asin sources have their own pinned hashes and original
permissive notices. Ordinary Java builds do not compile this library.

`PhysicsSpec.World.withKinematicFilter(true)` selects the RS collision policy
before adding objects. Group/mask checks remain active; a mixed pair containing
exactly one kinematic object is excluded. The policy is immutable after structure
creation and is retained when replay creates a replacement world. Ordinary Bullet
contacts remain the default. MMD settings independently select joints and linked
body collision behavior.

`PhysicsWorld.environment` publishes actual host placement, inertial derivatives,
gravity, fluid volumes/flow and convex terrain in one JNI call. Live worlds reuse a
direct buffer; preview journals own copies and replay environment changes. Terrain
objects occupy a separate bounded pool, not source body IDs. Native and WASM use
the same `runtime/native/src/physics_environment.h` policy. Source self-collision
masks and the optional mixed-kinematic filter do not disable host terrain contact.
`hostPhysicsBenchmark` measures publication, stepping and packed readback together.

Worlds support scalar and reusable direct-buffer transfers. Direct scalar views
must have native byte order and alignment. Input positions are preserved; output
positions advance only after successful readback. Pose transfers preserve already
unit quaternion scalars; the MMD binding explicitly normalizes solver rotations
once when constructing evaluated poses. Late invalid batch entries are rejected
before mutation, and readback validation precedes output publication.

The `MmdPlayer.packed(...)` factories are used by the shared runtime's PMX/PMD
playback services, including package preview and live sessions. They reuse pose/impulse/pin and
rigid/soft readback buffers. The regular constructors retain their existing path.
Source programs, VPD, expression and IK overrides use the same rig and morph
evaluation; Java continues to own model semantics and the published logical frame.

`ysm-runtime-forge:physicsDistributionTest` validates managed execution, absent,
missing and invalid libraries in fresh JVMs using only the shaded prerequisite.
With `-Dysm.native.physics.library=<absolute-library-path>`, run
`ysm-runtime-forge:nativePhysicsDistributionTest` to validate both explicit and
directory discovery, actual packed JNI calls, PMX/PMD parity, package frame reuse,
replay and live-session boundaries. These tests do not replace Forge world testing.

Preview journals have a fixed memory budget. A snapshot restores by replaying
into a candidate world and swaps only on success; live worlds disable the journal.
Non-finite results from step, impulse or accumulated force isolate the failed
world. It can only recover from an earlier snapshot; arbitrary setters cannot
resume a failed solver.

`physicsCorpus` reads local PMX/VMD assets in place. Set
`-Dysm.native.physics.corpus=<directory>` to choose the corpus,
`-Dysm.native.physics.corpus.extended=true` for layer/overlay/impulse/RS-policy
variants, and `-Dysm.native.physics.corpus.packed=true` for packed players.
Generated VPD overlays and injected morphs are identified as variants and do not
stand in for authentic files containing those features.

`physicsBenchmark` measures headless cold initialization, frame timing, Java
allocation and JNI calls. Run it separately from tests with `--no-parallel
--max-workers=1`; these measurements do not predict Forge/GPU frame rates.

The remaining host and platform work is tracked in the
[support report](../../docs/status/general-mesh-backend.md#验证证据与使用风险).
