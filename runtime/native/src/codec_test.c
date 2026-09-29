#include <blake3.h>
#include <zstd.h>
#include <string.h>

int main(void) {
    static const unsigned char expected[32] = {
        0xaf,0x13,0x49,0xb9,0xf5,0xf9,0xa1,0xa6,0xa0,0x40,0x4d,0xea,0x36,0xdc,0xc9,0x49,
        0x9b,0xcb,0x25,0xc9,0xad,0xc1,0x12,0xb7,0xcc,0x9a,0x93,0xca,0xe4,0x1f,0x32,0x62};
    unsigned char digest[32]; blake3_hasher hash;
    blake3_hasher_init(&hash); blake3_hasher_finalize(&hash, digest, 32);
    if (memcmp(expected, digest, 32) != 0) return 1;
    const char input[] = "independent ysmlib native accelerator";
    char compressed[128], decoded[sizeof(input)];
    size_t size = ZSTD_compress(compressed, sizeof(compressed), input, sizeof(input), 3);
    if (ZSTD_isError(size)) return 2;
    if (ZSTD_decompress(decoded, sizeof(decoded), compressed, size) != sizeof(input)) return 3;
    return memcmp(input, decoded, sizeof(input)) == 0 ? 0 : 4;
}
