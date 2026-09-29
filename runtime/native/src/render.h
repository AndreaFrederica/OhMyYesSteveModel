#ifndef YSMLIB_RENDER_H
#define YSMLIB_RENDER_H
#include <stddef.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
/* Borrowed little-endian buffers. No pointer is retained. Output is unchanged on failure.
 * Result: 0 success, 1 invalid input, 2 allocation failure. No exception crosses this ABI. */
int32_t ysmlib_render_v1(const uint8_t* geometry, size_t geometry_size,
                         const uint8_t* frame, size_t frame_size,
                         int32_t stride, int32_t mid_offset,
                         uint8_t* output, size_t output_size);
#ifdef __cplusplus
}
#endif
#endif
