# Managed MMD runtime

This module owns PMX/PMD/VMD/VPD/PMM reading, MMD mesh projection and PMX/PMD animation
evaluation. `MmdPlayer` combines the same morph/bone/IK stages with a caller's
`PhysicsProvider`. It has no Minecraft, GPU, JNI or subprocess dependency.
The distribution supplies Bullet WASM through the pure JVM provider.

Playback uses source model units, a default gravity of 98 units/s² and an
explicit fixed step. Read-only frames never advance the solver. Fractional
preview time samples animation against the last simulated body state.
Backward seeks rebuild and replay the complete rig and solver, including
contacts, motors, soft nodes and morph impulses. Work budgets reject an
operation rather than dropping time. Overall model scale belongs to the host.

The runtime is still under implementation. PMD playback retains its own bone
types, base morph, knee constraints and IK order. PMX soft near-anchor behavior remains unverified and is
explicitly rejected. Joint fields, uncommon append ordering, soft ropes and
source-software equivalence require further independent fixtures. There is no
Minecraft import UI, renderer or live preview integration yet. See the project
support status for the current scope.

PMM 1 and 2 use a separate typed project document containing model/accessory
keys, camera/light/gravity/shadow channels, media paths, editor state and explicit
unknown bytes. PMM 1 requires a `PmmModelResolver` because its track counts/names
come from the original model. PMM 2 declares its own schema. Embedded tracks are
compiled at MMD's 30 fps, independently of the editor's preferred display FPS.
Outside-parent, camera attachment and animated-gravity channels are preserved;
a multi-object project playback/physics session and media rendering still need
host integration. `ProjectAssetResolver` in java-scene supports explicit file
and root relocation into a caller-supplied asset capability. It never opens a
foreign workstation path directly.

## Reference algorithms and licensing

Bone append semantics and the three Euler constraint orders in the managed
CCD solver were developed using babylon-mmd's MIT implementation as a reference
(`ccb750db2998ed1067017eaf67f3fc987cfba6f8`). Its IK implementation in turn
acknowledges Saba. These acknowledgements and both MIT license notices are
included in the distributed prerequisite. YSM's solver adds bounded work,
antipodal handling and small-angle `atan2` evaluation; it does not copy
babylon-mmd's silent 256-iteration clamp.

The maintainer-only Saba oracle is described in `oracle/README.md`. Source
readers and evaluation algorithms can have independent defects; the oracle's
manifest explicitly identifies its minimal QDEF reader repair.

PMM layouts were implemented with nanoem's MIT core document reader as a
reference. Its application layer is not linked or copied into this module.
The prerequisite includes the core MIT notice. `oracle/build_pmm.py` authors
a synthetic scene using its pinned, unmodified PMM 2 writer and verifies it
with its reader. PMM 1 uses a separately authored file checked by that reader,
since the reference's PMM 1 model writer is unfinished. Ordinary tests consume
the frozen files and never load the reference executable.
