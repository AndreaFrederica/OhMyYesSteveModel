# glTF surface oracle

`brdf.glsl` is the unmodified Khronos glTF Sample Renderer source pinned by the manifest.
The reference harness calls its Fresnel, GGX and Lambertian functions and composes the
dielectric/metal lobes according to the core glTF specification. Its license is included.

GPU comparisons cover direct illumination with nonzero roughness. Explicit pixel assertions
exercise texture transfer functions, independent UV sets/transforms and alpha modes.
These checks do not establish full renderer, environment-lighting, material-extension or
Minecraft world compatibility. The production shader outputs linear color; framebuffer
composition belongs to the host renderer.
