# Scene image references

Synthetic RGB/alpha and palette images, decoded by unmodified Pillow 12.3.0.
The manifest records each encoded source hash. The corresponding `.rgba` files
are the independent decoder output, including four TGA origins, RLE packets,
gray+alpha and indexed colors. JPEG permits a two-code-value tolerance because
the Java and Pillow decoders use different IDCT implementations.

Regenerate with `runtime/java-image/tools/generate_scene_image_oracle.py` using
Pillow 12.3.0. Pillow is only a maintainer reference, not a runtime dependency.
The fixtures were created for this project and contain no third-party artwork.
The library tests also verify 16-bit PNG source samples, TGA interleave,
16-bit palette indices and extension alpha semantics independently; these
features are not claimed to be covered by the Pillow comparison.
