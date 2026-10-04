# MMD material shader comparison

Unmodified Saba GLSL sources, revision and hashes in manifest.json, MIT license included.
The GPU check compiles these separately and compares pixels with YSM's production surface program.
Saba uploads vertically flipped images and flips mesh V on import. The test follows those original
conventions for Saba and top-left source images/UV for YSM. It does not rewrite the reference shader.

Six comparisons cover diffuse/ambient, color texture, sphere multiply/add, toon, combined maps,
and separate multiplicative/additive material-morph factors. Sphere alpha is one in the comparisons.
Specular is disabled: the reference's eye-vector convention is not used to validate YSM specular.
Additional UV subtextures and alpha use explicit source-feature assertions because Saba leaves
sphere mode 3 unimplemented. Additional pixel assertions cover per-vertex edge scale, zero-width
outlines, PMX 2.1 point/line precedence, all triangle edges and UV1 vertex RGBA. These are feature
assertions, not Saba comparisons. Raster state restoration is also checked, including both polygon
mode values. Specular and full Minecraft lighting are not covered.
