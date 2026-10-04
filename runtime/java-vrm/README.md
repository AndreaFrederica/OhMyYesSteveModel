# Portable VRM profiles

This module belongs to Oh My YSM Lib. It has no Minecraft, GPU, JNI or process dependency at runtime.

`VrmReader` interprets VRM 0.0 and VRM 1.0 independently on top of the bounded glTF reader. `VrmDocument` retains the original glTF source, dependencies and extension metadata alongside typed human bones, expressions, material bindings, first-person annotations, gaze parameters, node constraints and springs. Source profiles are not a claim of complete avatar rendering support.

`VrmExpressionEvaluator` evaluates morph, material and texture bindings from immutable base values. Unknown input expression keys and material properties without a declared base fail explicitly. VRM 1 procedural overrides respect binary output and same-category exclusion. Custom expressions may opt into an override category.

`VrmConstraints` consumes explicit local rotations and matrices. It evaluates rotation, roll and aim dependencies without decomposing world matrices or losing scale signs. Matrix-form source nodes retain their matrices. The dependency graph distinguishes local and world reads and detects cycles iteratively.

`VrmSpringSolver` provides an isolated managed Verlet simulation. Its snapshot owns the complete previous/current tail state and simulated rotations. Sampling does not advance simulation; a player must supply positive fixed time steps. VRM 1 uses explicit joint pairs and allows intervening nodes. VRM 0 uses root subtrees, the first child for each parent and a seven-centimeter virtual tail for terminal bones. Sphere/capsule collision radii use source world units. Legacy collider offset Z is converted during evaluation, while imported source values remain intact.

The `oracle` directory contains maintainer-only generation tools. `python oracle/build.py` verifies pinned npm archives and runs unmodified three-vrm and three.js against project-authored inputs. Ordinary Java tests consume the frozen outputs without Node or network access. Sources and licenses are recorded with the fixtures. Differential coverage is specific to those inputs; it does not establish full real-model compatibility.

`VrmEvaluator` combines animation, gaze, expressions and constraints; `VrmPlayer` adds fixed-step springs with backward replay and read-only fractional sampling. Legacy gaze curves are evaluated as Hermite curves rather than discarded. A failed forward source restores the previous complete spring state and published frame.

`VrmaRetarget` maps normalized humanoid motion to target rest axes, scales hips translation, compensates for source-only optional bones and maps expressions and gaze. Original clips and source fields remain available. Unbound expressions produce compatibility diagnostics. The pinned VRM/VRMA file loaders provide 61 differential frames, and the standalone distribution exercises retargeting without Forge or YSM native.

`VrmFirstPerson` compiles immutable per-node draw lists. Every positive head-descendant influence is considered, including influence sets after the first four. Filtering retains source attributes, morphs and skinning; strip/fan winding and line boundaries are preserved. The original loader supplies a separate visibility oracle.

`VrmMaterialEvaluator` prepares versioned material parameters, texture transfer functions, UV rules and render states. MToon 0 retains its exact queue, original shader parameters and extra texture slots. Legacy texture offset/scale storage is converted to shader `_ST` ordering; its scroll-before-rotation and turns-per-second behavior remain distinct from MToon 1. Shader colors remain in source space. MToon 1 follows the schema's white shade default with an explicit diagnostic because the prose and three-vrm use black. Differential material tests use explicit colors. Source-only extensions and custom shaders are reported, not silently declared evaluated.

Host preview/rendering, first-person camera integration and material shaders remain in progress. See the repository's general-mesh support status and work log.
