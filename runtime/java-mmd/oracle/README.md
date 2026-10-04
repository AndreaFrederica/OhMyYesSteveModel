# Independent MMD reference fixtures

`build.py` downloads a pinned and SHA-256 checked Saba MIT source archive into
the ignored build directory, preserving its bundled license files. It builds a
headless reference with its MMD implementation and the maintainer's
native Bullet reference libraries. No Saba code or host native executable is
included in the player distribution, and ordinary Gradle tests do not build or
run this helper.

Run the physics module's native oracle build first, then this directory's
`build.py` and `generate_skin_fixture.py`. The latter creates project-owned
synthetic PMX/VMD inputs and freezes source-space bone matrices and deformed
vertices/normals for 61 samples at 60 Hz. Inputs, reference revision and output
hashes are stored with the test resources.

The pinned Saba PMX reader has a QDEF defect: it writes the four weights to
indices `[0,1,3,4]`, skipping index 2 and writing past the array. `build.py`
repairs only those indices to `[0,1,2,3]`, after strict matching against the
pinned source. The QDEF evaluator is unchanged. The output manifest records
this repair and the patched reader's SHA-256; results must not be described as
coming from unmodified upstream Saba.

The fixtures cover BDEF1/BDEF2/BDEF4, SDEF with nonzero C/R0/R1, QDEF,
translation/rotation animation, sparse vertex morphs, nested PMX bones,
ordered bone/group/material morphs and PMD base-morph hierarchy. They do not establish
PMX2.1 soft-body, flip/impulse morph, camera interpolation or MMD application-wide
equivalence. In particular, reference implementations differ on VMD interpolation;
this fixture uses linear Bezier curves and tests interpolation separately.

## PMM project reader reference

`build_pmm.py --cc <C compiler>` fetches the exact MIT core files in
`pmm-sources.json`, verifies every hash, and builds `pmm_oracle.c`. The original
PMM 2 serializer writes a synthetic project with two models, embedded motion,
accessory, camera, light, gravity, shadow and external media paths. The original
reader reads it back. The generator explicitly authors frame-zero keys so the
reference receives valid initial track IDs and unit quaternions.

The pinned PMM 1 model writer contains a FIXME. `pmm_v1_fixture.py` therefore
authors that fixture separately and verifies it with the original PMM reader
using the existing project-owned PMD model as its schema. It includes Japanese
CP932 strings and an explicit unknown tail. No reference source is modified.
These fixtures validate the covered binary layout and channel extraction,
not whole-application PMM visual or physics equivalence.
