# Managed mesh algorithms

This module runs the pinned, unmodified MikkTSpace algorithm inside the JVM with Chicory.
It does not load a native library, spawn a process, access a filesystem or own GPU objects.
Each invocation owns isolated memory. Source preparation belongs to the scene module;
the reactor accepts triangle corners and returns one XYZW tangent per corner.

Maintainers rebuild the bundled reactor with `engine/build.py --sdk <WASI SDK 34.0>`.
The source manifest pins both files and hashes. The separate adapter bounds algorithm
allocations, including optional allocation fallbacks, and includes input/output in that budget.
Original license notices and algorithm sources are included in the distribution.

The Blender oracle script freezes unmodified `Mesh.calc_tangents` output for project-owned
smooth/flat curved meshes, mirrored UV islands and a displaced target. Normal tests read
the frozen fixtures and require neither Blender nor a C compiler.
