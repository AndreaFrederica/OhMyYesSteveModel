# BVH runtime

The managed reader preserves joint identity, hierarchy, offsets, End Sites,
source channel order, frame interval and every scalar sample. Names may be
duplicated or include namespaces. Parsing and evaluation are iterative and
bounded. Source coordinates and units must be supplied by the caller because
BVH does not declare them.

Evaluation adds positional channels to the offset and concatenates rotations
in the declared Euler order. Subframe sampling is explicitly STEP or linear
source channels; it never reduces authored full rotations to shortest-arc
quaternion keys. Result matrices use the shared `ScenePose` interface. This
module reads skeleton motion; retargeting to a different skeleton is a separate
operation and is not inferred from joint names.

The layout and transform reference is Jeff Lander's BVH description hosted by
the University of Wisconsin graphics course:
https://research.cs.wisc.edu/graphics/Courses/cs-838-1999/Jeff/BVH.html
