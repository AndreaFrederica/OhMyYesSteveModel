`alpha.avif` and `alpha.rgba` are synthetic test data generated for this project,
covered by the repository license. The 17x13 RGBA source at (x,y) is:
`((17*x+3*y)%256, (23*y+x)%256, (11*x+19*y)%256, (13*x+29*y)%256)`.
Pillow 12.3.0 / libavif 1.4.2 encoded it with quality=100, subsampling=4:4:4,
speed=6. `alpha.rgba` is the independent Pillow native decoder's output, not
the WASM decoder's output. Alpha must match byte-for-byte; RGB allows at most
2/255 per component because Pillow uses libyuv integer YUV conversion and the
WASM build uses libavif scalar conversion. This is not evidence of pixel-exact
equivalence with the old YSM native decoder.
