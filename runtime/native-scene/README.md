# Optional MMD CPU deformation accelerator

Java 17 adapter for the independently implemented `ysmlib_skinning` C ABI v1.
It accelerates BDEF, SDEF and QDEF using one packed JNI call per deformation.
A persistent pool uses at most four workers for meshes with at least 4096 vertices;
dispatches from different sessions serialize. Morph accumulation shares the Java
oracle and reusable scratch arrays. Published frames own immutable copies.

`YsmRuntime.deformation()` selects this capability independently of Bullet.
`ysm.runtime.skinningLibrary` overrides discovery in
`ysmlib/natives/<platform>-<architecture>/`. Missing libraries, linkage or startup
self-check failure select Java. `ysm.runtime.javaOnly=true` skips discovery.
A stateful physics world never changes solver when skinning falls back.

Build and verify the kernel with `pixi run build` and `pixi run test` in the native
module. `pixi run package-skinning` stages the binary, C header, YSM license,
source archive and SHA-256 provenance. No RS shader or Rust source is copied.

Tests require `-Dysm.native.skinning.library=<absolute-library-path>`; use the same
JDK 17 for Gradle. `:ysm-runtime-forge:skinningDistributionTest` checks four managed
selection cases; `:ysm-runtime-forge:nativeSkinningDistributionTest` checks explicit
override and directory discovery in separate shaded JVMs.

`:ysm-runtime-native-scene:mmdOptimizationBenchmark` measures complete CPU logical
frames against WASM/Java and native Bullet/Java baselines, with scalar and parallel
skinning. It requires both `ysm.native.skinning.library` and
`ysm.native.physics.library`. `ysm.mmd.benchmark.corpus` selects the PMX/VMD root;
`ysm.mmd.benchmark.extraCorpus` adds models, optionally filtered by the filename
glob `ysm.mmd.benchmark.extraModel`. Cross-model motion pairing is an explicit
benchmark input, not evidence of an asset's original motion. Sources are read
locally and are not bundled in a distribution.

`mmdSkinningComparisonBenchmark` performs the additional fully warmed Java/scalar/parallel comparison in deterministic random order.

Host GL verification and upload benchmarks are separate tasks in the main Mod:
`mmdOptimizationGpuVerification` and `mmdOptimizationGpuBenchmark`. They use the
production target owner in a hidden GL context. GPU support requires OpenGL 4.3,
device storage limits and no PMX soft bodies; CPU remains available with
`ysm.mmd.gpuSkinning=false`. Neither test is a Minecraft-world FPS measurement.
