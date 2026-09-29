#include <avif/avif.h>
#include <stdint.h>
#include <stdlib.h>

// One isolated WASM instance per request. No file, socket or native host access.
static uint32_t image_width, image_height;
static uint8_t* image_pixels;

int ysm_avif_read(const uint8_t* input, uint32_t size, int decode) {
    free(image_pixels);
    image_pixels = NULL;
    image_width = image_height = 0;
    avifDecoder* decoder = avifDecoderCreate();
    if (!decoder) return AVIF_RESULT_OUT_OF_MEMORY;
    decoder->codecChoice = AVIF_CODEC_CHOICE_AOM;
    decoder->maxThreads = 1;
    decoder->ignoreExif = AVIF_TRUE;
    decoder->ignoreXMP = AVIF_TRUE;
    decoder->imageSizeLimit = 64 * 1024 * 1024;
    decoder->imageDimensionLimit = 65535;
    avifResult result = avifDecoderSetIOMemory(decoder, input, size);
    if (result == AVIF_RESULT_OK) result = avifDecoderParse(decoder);
    if (result == AVIF_RESULT_OK) {
        image_width = decoder->image->width;
        image_height = decoder->image->height;
        if (!image_width || !image_height || (uint64_t)image_width * image_height > 64 * 1024 * 1024)
            result = AVIF_RESULT_INVALID_IMAGE_GRID;
    }
    if (result == AVIF_RESULT_OK && decode) {
        result = avifDecoderNextImage(decoder);
        if (result == AVIF_RESULT_OK) {
            avifRGBImage rgb;
            avifRGBImageSetDefaults(&rgb, decoder->image);
            rgb.format = AVIF_RGB_FORMAT_RGBA;
            rgb.depth = 8;
            rgb.alphaPremultiplied = AVIF_FALSE;
            rgb.chromaUpsampling = AVIF_CHROMA_UPSAMPLING_BEST_QUALITY;
            rgb.maxThreads = 1;
            rgb.rowBytes = image_width * 4;
            image_pixels = malloc((size_t)rgb.rowBytes * image_height);
            if (!image_pixels) result = AVIF_RESULT_OUT_OF_MEMORY;
            else {
                rgb.pixels = image_pixels;
                result = avifImageYUVToRGB(decoder->image, &rgb);
            }
        }
    }
    avifDecoderDestroy(decoder);
    if (result != AVIF_RESULT_OK) { free(image_pixels); image_pixels = NULL; }
    return result;
}

uint32_t ysm_avif_width(void) { return image_width; }
uint32_t ysm_avif_height(void) { return image_height; }
uint8_t* ysm_avif_pixels(void) { return image_pixels; }
