#include "render.h"
#include <jni.h>

extern "C" JNIEXPORT jint JNICALL
Java_cc_sirrus_ysmlib_render_natives_NativeRenderProvider_nAbiVersion(JNIEnv*, jclass) { return 1; }

extern "C" JNIEXPORT jint JNICALL
Java_cc_sirrus_ysmlib_render_natives_NativeRenderProvider_nRender(
        JNIEnv* env, jclass, jobject geometry, jobject frame, jint stride, jint mid, jobject output) {
    if (!geometry || !frame || !output) return 1;
    jlong gs = env->GetDirectBufferCapacity(geometry), fs = env->GetDirectBufferCapacity(frame),
          os = env->GetDirectBufferCapacity(output);
    if (gs < 0 || fs < 0 || os < 0) return 1;
    return ysmlib_render_v1(static_cast<const uint8_t*>(env->GetDirectBufferAddress(geometry)), size_t(gs),
                           static_cast<const uint8_t*>(env->GetDirectBufferAddress(frame)), size_t(fs),
                           stride,mid,static_cast<uint8_t*>(env->GetDirectBufferAddress(output)),size_t(os));
}
