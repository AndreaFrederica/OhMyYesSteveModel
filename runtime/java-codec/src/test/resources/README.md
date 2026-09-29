# Codec interoperability fixtures

`blake3-test-vectors.json` is the official unmodified BLAKE3 test vector set:
https://github.com/BLAKE3-team/BLAKE3/blob/1.8.2/test_vectors/test_vectors.json
Input bytes repeat `i % 251`; tests compare the first 32 digest bytes over every
published input length, including multiple chunk/tree boundaries.

Zstandard files were generated independently with python-zstandard 0.25.0,
libzstd 1.5.7. Payload: `bytes(range(251))*1800` followed by 24,000 bytes from
successive `random.Random(9927).randrange(256)` calls (475,800 bytes total).
The stored payload allows byte-for-byte validation without assuming Java and
Python random generators agree.

- `zstd-level1.zst`: level 1, content size, no checksum.
- `zstd-level19-checksum.zst`: level 19, content size and checksum.
- `zstd-no-size.zst`: level 3, no frame content size.
- `zstd-concatenated.zst`: default-level independent frames split at byte 180,000.

Tests also produce ignored `build/interop/java-zstd.zst` for decoding with an
independent libzstd implementation. That external check is recorded separately;
the Java round trip alone is not evidence of cross-implementation conformance.
