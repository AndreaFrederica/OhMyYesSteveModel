#include <jni.h>
#include <blake3.h>
#include <zstd.h>
#include <stdint.h>
#include <stdlib.h>

#define JNI_METHOD(name) Java_cc_sirrus_ysmlib_codec_natives_NativeCodecProvider_##name

static void fail(JNIEnv* env, const char* message) {
    jclass type = (*env)->FindClass(env, "java/io/IOException");
    if (type != NULL) (*env)->ThrowNew(env, type, message);
}

JNIEXPORT jint JNICALL JNI_METHOD(nAbiVersion)(JNIEnv* env, jclass type) { return 1; }

JNIEXPORT jbyteArray JNICALL JNI_METHOD(nHash)(JNIEnv* env, jclass type, jbyteArray input) {
    jsize size = (*env)->GetArrayLength(env, input);
    jbyte* bytes = (*env)->GetByteArrayElements(env, input, NULL);
    if (bytes == NULL) return NULL;
    uint8_t digest[BLAKE3_OUT_LEN];
    blake3_hasher hasher;
    blake3_hasher_init(&hasher);
    blake3_hasher_update(&hasher, bytes, (size_t)size);
    blake3_hasher_finalize(&hasher, digest, BLAKE3_OUT_LEN);
    (*env)->ReleaseByteArrayElements(env, input, bytes, JNI_ABORT);
    jbyteArray result = (*env)->NewByteArray(env, BLAKE3_OUT_LEN);
    if (result != NULL) (*env)->SetByteArrayRegion(env, result, 0, BLAKE3_OUT_LEN, (const jbyte*)digest);
    return result;
}

static jbyteArray codec(JNIEnv* env, jbyteArray input, jint parameter, jint budget, int decompress) {
    if (budget < 0) { fail(env, "Invalid Zstd output budget"); return NULL; }
    jsize size = (*env)->GetArrayLength(env, input);
    size_t capacity = decompress ? (size_t)parameter : ZSTD_compressBound((size_t)size);
    if (capacity > (size_t)budget || capacity > INT32_MAX) {
        fail(env, "Zstd output budget exceeded"); return NULL;
    }
    void* output = malloc(capacity == 0 ? 1 : capacity);
    if (output == NULL) { fail(env, "Zstd allocation failed"); return NULL; }
    jbyte* bytes = (*env)->GetByteArrayElements(env, input, NULL);
    if (bytes == NULL) { free(output); return NULL; }
    size_t written = decompress ? ZSTD_decompress(output, capacity, bytes, (size_t)size)
                                : ZSTD_compress(output, capacity, bytes, (size_t)size, parameter);
    (*env)->ReleaseByteArrayElements(env, input, bytes, JNI_ABORT);
    if (ZSTD_isError(written) || (decompress && written != capacity)) {
        const char* message = ZSTD_isError(written) ? ZSTD_getErrorName(written) : "Zstd decoded size mismatch";
        free(output); fail(env, message); return NULL;
    }
    jbyteArray result = (*env)->NewByteArray(env, (jsize)written);
    if (result != NULL) (*env)->SetByteArrayRegion(env, result, 0, (jsize)written, output);
    free(output);
    return result;
}

JNIEXPORT jbyteArray JNICALL JNI_METHOD(nCompress)(JNIEnv* env, jclass type, jbyteArray input, jint level, jint budget) {
    return codec(env, input, level, budget, 0);
}
JNIEXPORT jbyteArray JNICALL JNI_METHOD(nDecompress)(JNIEnv* env, jclass type, jbyteArray input, jint size) {
    return codec(env, input, size, size, 1);
}
