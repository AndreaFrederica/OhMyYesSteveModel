# JVM AVIF fallback

Chicory 1.7.5 executes the bundled WASM as JVM bytecode on Java 17. It does not
load JNI, a host WASM runtime, or system codecs. This module is the AVIF-only
exception to the Java/Kotlin-first baseline; it is not the acceleration tier.

The build uses libavif 1.4.2 (`c5240fc79fe5c2407e10afd35f5505ef6333ea49`),
libaom 3.13.1 (`d772e334cc724105040382a977ebb10dfd393293`), and WASI SDK 34
(LLVM `895aa2c896ada719451be2e3673c83da8ddf1141`, wasi-libc
`2e6fb9d8ee0cdf9e431fbcabe8af3115de000a13`). Sources are unmodified; the bridge is ours.
Codec implementations are single-threaded scalar C. CMake/Ninja build upstream
libraries; the separate optional native accelerator uses Meson/Pixi.

With Git, CMake and Ninja available, run from the repository root:

```powershell
python runtime/wasm-avif/codec/build.py --sdk <WASI-SDK-34-directory>
```

The script fetches pinned source revisions, builds the reactor, and updates
`src/main/resources/cc/sirrus/ysmlib/avif/decoder.wasm` and its SHA-256 sidecar.
WASM exception handling must use `-wasm-use-legacy-eh=false`; old EH opcodes are
not supported by this JVM engine. Ordinary Gradle builds consume the bundled
artifact and never download the SDK or execute external decoders.

Every operation has isolated decoder/memory state (512 MiB linear-memory cap,
1 MiB stack, 256 MiB input/output budgets). WASI receives no directories,
environment or inherited streams. Only the first image is decoded, matching
the current still-image adapter. YUV conversion uses libavif scalar code with
best-quality upsampling and unpremultiplied RGBA8 output. Presentation encoding
belongs to `java-image`, which produces ZTX, not AVIF.

Tests decode every builtin AVIF, reject malformed/truncated input and metadata
mismatches, and compare a generated transparency fixture against independent
Pillow/libavif output. Alpha is exact; RGB tolerates at most 2/255 for the
scalar-versus-libyuv conversion difference. Matching the old official native
decoder and model identity still needs separate regression validation.

License texts for libavif (including bundled third-party notices), libaom
and PATENTS, WASI libc/compiler-rt, and Chicory are in the Forge module's
`src/main/resources/licenses/` and included in the prerequisite JAR.
