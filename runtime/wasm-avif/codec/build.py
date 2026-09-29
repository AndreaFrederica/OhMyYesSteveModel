"""Rebuild the bundled JVM AVIF fallback. Requires WASI SDK 34, CMake, Ninja and Git.

This is a maintainer task. Gradle/player installations use the checked-in WASM and
never download toolchains or invoke an external executable to decode images.
"""
import argparse
import hashlib
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
AOM_COMMIT = "d772e334cc724105040382a977ebb10dfd393293"
AVIF_COMMIT = "c5240fc79fe5c2407e10afd35f5505ef6333ea49"


def run(*args):
    subprocess.run([str(arg) for arg in args], check=True)


def checkout(folder, url, revision):
    if not folder.exists():
        run("git", "init", folder)
        run("git", "-C", folder, "remote", "add", "origin", url)
        run("git", "-C", folder, "fetch", "--depth", "1", "origin", revision)
        run("git", "-C", folder, "checkout", "--detach", "FETCH_HEAD")
    actual = subprocess.check_output(["git", "-C", str(folder), "rev-parse", "HEAD"], text=True).strip()
    if actual != revision:
        raise SystemExit(f"Unexpected source revision in {folder}: {actual}")
    if subprocess.check_output(["git", "-C", str(folder), "diff", "HEAD", "--"], text=True):
        raise SystemExit(f"Modified upstream sources in {folder}")


def main():
    parser = argparse.ArgumentParser(__doc__)
    parser.add_argument("--sdk", type=Path, required=True)
    parser.add_argument("--aom-source", type=Path)
    parser.add_argument("--avif-source", type=Path)
    parser.add_argument("--jobs", default="8")
    args = parser.parse_args()
    sdk = args.sdk.resolve()
    work = ROOT / "build" / "codec"
    work.mkdir(parents=True, exist_ok=True)
    aom = args.aom_source.resolve() if args.aom_source else work / "aom"
    avif = args.avif_source.resolve() if args.avif_source else work / "libavif"
    checkout(aom, "https://aomedia.googlesource.com/aom", AOM_COMMIT)
    checkout(avif, "https://github.com/AOMediaCodec/libavif.git", AVIF_COMMIT)
    compiler = sdk / "bin" / ("clang.exe" if (sdk / "bin/clang.exe").exists() else "clang")
    version = subprocess.check_output([str(compiler), "--version"], text=True)
    if "wasi-sdk" not in str(sdk) or not (sdk / "share/cmake/wasi-sdk-p1.cmake").exists():
        raise SystemExit("WASI SDK 34 with the preview1 CMake toolchain is required")
    flags = "-mno-simd128 -mexception-handling -mllvm -wasm-enable-sjlj -mllvm -wasm-use-legacy-eh=false"
    options = ["-G", "Ninja", f"-DCMAKE_TOOLCHAIN_FILE={sdk / 'share/cmake/wasi-sdk-p1.cmake'}",
               f"-DCMAKE_MAKE_PROGRAM={shutil.which('ninja')}", "-DCMAKE_BUILD_TYPE=Release",
               f"-DCMAKE_C_FLAGS={flags}", f"-DCMAKE_CXX_FLAGS={flags}"]
    aom_build, avif_build = aom / "build-wasi", avif / "build-wasi"
    run("cmake", "-S", aom, "-B", aom_build, *options,
        "-DAOM_TARGET_CPU=generic", "-DCONFIG_MULTITHREAD=0", "-DCONFIG_RUNTIME_CPU_DETECT=0",
        "-DCONFIG_AV1_ENCODER=0", "-DENABLE_DOCS=OFF", "-DENABLE_TESTS=OFF",
        "-DENABLE_EXAMPLES=OFF", "-DENABLE_TOOLS=OFF", "-DCONFIG_WEBM_IO=0", "-DCONFIG_LIBYUV=0")
    run("cmake", "--build", aom_build, "-j", args.jobs)
    run("cmake", "-S", avif, "-B", avif_build, *options,
        "-DBUILD_SHARED_LIBS=OFF", "-DAVIF_CODEC_AOM=SYSTEM", "-DAVIF_CODEC_AOM_DECODE=ON",
        "-DAVIF_CODEC_AOM_ENCODE=OFF", "-DAVIF_LIBYUV=OFF", "-DAVIF_LIBSHARPYUV=OFF",
        "-DAVIF_ZLIBPNG=OFF", "-DAVIF_JPEG=OFF", f"-DAOM_INCLUDE_DIR={aom}",
        f"-DAOM_LIBRARY={aom_build / 'libaom.a'}")
    run("cmake", "--build", avif_build, "-j", args.jobs)
    output = ROOT / "src/main/resources/cc/sirrus/ysmlib/avif/decoder.wasm"
    output.parent.mkdir(parents=True, exist_ok=True)
    exports = ["malloc", "free", "ysm_avif_read", "ysm_avif_width", "ysm_avif_height", "ysm_avif_pixels"]
    run(compiler, "-O2", *flags.split(), "-mexec-model=reactor", f"-I{avif / 'include'}",
        ROOT / "codec/avif_bridge.c", avif_build / "libavif.a", aom_build / "libaom.a", "-lsetjmp",
        *[f"-Wl,--export={name}" for name in exports], "-Wl,-z,stack-size=1048576",
        "-Wl,--max-memory=536870912", "-Wl,--strip-all", "-o", output)
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    (output.parent / "decoder.sha256").write_text(digest + "  decoder.wasm\n", encoding="ascii")
    print(version.splitlines()[0])
    print(f"{digest}  {output}")


if __name__ == "__main__":
    main()
