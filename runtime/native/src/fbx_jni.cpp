#include <cstdint>
#include <cstring>
#include <jni.h>
#include <mutex>
#include <string>
#include <vector>

extern "C" int ysm_fbx_abi(void);
extern "C" int ysm_fbx_native_abi(void);
extern "C" int ysm_fbx_load(const void *, int, int, int, int, int);
extern "C" int ysm_fbx_apply_skin_selection(const uint32_t *, const uint32_t *,
                                            size_t);
extern "C" int ysm_fbx_evaluate(int, double);
extern "C" int ysm_fbx_evaluate_nodes(int, double, float *, float *, float *,
                                      float *, size_t);
extern "C" int ysm_fbx_snapshot(int, int);
extern "C" const char *ysm_fbx_output(void);
extern "C" int ysm_fbx_output_size(void);
extern "C" const char *ysm_fbx_error(void);
extern "C" void ysm_fbx_destroy(void);
extern "C" int ysm_fbx_geometry_counts(int *, int *, int *, int *, int *,
                                       int *);
extern "C" int ysm_fbx_geometry_write(float *, size_t, float *, size_t, float *,
                                      size_t, float *, size_t, int *, int *,
                                      size_t, float *, float *, float *,
                                      float *, size_t, int, const float *,
                                      size_t);

namespace {
std::mutex mutex;
thread_local std::string last_error;
std::vector<jbyte> resident_source;
std::vector<int> resident_mesh_selection, resident_skin_selection;
struct ReadOnlyBytes {
  JNIEnv *env;
  jbyteArray array;
  jbyte *data;
  jsize length;
  ReadOnlyBytes(JNIEnv *e, jbyteArray a)
      : env(e), array(a), data(nullptr), length(a ? e->GetArrayLength(a) : 0) {
    if (array && length) data = e->GetByteArrayElements(array, nullptr);
  }
  ~ReadOnlyBytes() {
    if (data) env->ReleaseByteArrayElements(array, data, JNI_ABORT);
  }
  bool valid() const { return !length || data != nullptr; }
};
void fail(const char *value) {
  last_error = value ? value : "Native FBX operation failed";
}
jbyteArray result(JNIEnv *env) {
  int size = ysm_fbx_output_size();
  if (size < 0) {
    fail(ysm_fbx_error());
    return nullptr;
  }
  auto *data = reinterpret_cast<const jbyte *>(ysm_fbx_output());
  auto out = env->NewByteArray(size);
  if (!out) {
    fail("Native FBX result allocation failed");
    return nullptr;
  }
  if (size)
    env->SetByteArrayRegion(out, 0, size, data);
  return out;
}
bool prepare_source(JNIEnv *env, jbyteArray input, jint memory, jint output,
                    jint elements, jint strings, int stack, double seconds,
                    bool evaluate, bool follow) {
  if (!input || memory <= 0 || output <= 0 || elements <= 0 || strings <= 0) {
    fail("Invalid native FBX budgets");
    return false;
  }
  ReadOnlyBytes bytes(env, input);
  if (!bytes.valid()) { fail("Native FBX source access failed"); return false; }
  bool same = resident_source.size() == (size_t)bytes.length &&
              (!bytes.length || std::memcmp(resident_source.data(),
                                             bytes.data, (size_t)bytes.length) == 0) &&
              resident_mesh_selection.empty();
  if (!same) {
    ysm_fbx_destroy();
    resident_source.clear();
    if (bytes.length) resident_source.assign(bytes.data, bytes.data + bytes.length);
    resident_mesh_selection.clear();
    resident_skin_selection.clear();
    if (ysm_fbx_load(bytes.data, bytes.length, memory, output, elements, strings) <
        0) {
      fail(ysm_fbx_error());
      resident_source.clear();
      ysm_fbx_destroy();
      return false;
    }
  }
  if (evaluate && ysm_fbx_evaluate(stack, seconds) < 0) {
    fail(ysm_fbx_error());
    return false;
  }
  if (ysm_fbx_snapshot(evaluate ? 0 : 1, follow ? 1 : 0) < 0) {
    fail(ysm_fbx_error());
    return false;
  }
  return true;
}
jbyteArray source(JNIEnv *env, jbyteArray input, jint memory, jint output,
                  jint elements, jint strings, int stack, double seconds,
                  bool evaluate, bool follow) {
  if (!prepare_source(env,input,memory,output,elements,strings,stack,seconds,evaluate,follow)) return nullptr;
  return result(env);
}
jobject source_direct(JNIEnv *env, jbyteArray input, jint memory, jint output,
                      jint elements, jint strings) {
  if (!prepare_source(env,input,memory,output,elements,strings,-1,0,false,false)) return nullptr;
  int size=ysm_fbx_output_size();if(size<0){fail(ysm_fbx_error());return nullptr;}
  return env->NewDirectByteBuffer((void*)ysm_fbx_output(),(jlong)size);
}
} // namespace
extern "C" JNIEXPORT jint JNICALL
Java_cc_sirrus_ysmlib_scene_fbx_FbxNativeReactor_nAbi(JNIEnv *, jclass) {
  return ysm_fbx_abi() == 2 && ysm_fbx_native_abi() == 1 ? 1 : 0;
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_cc_sirrus_ysmlib_scene_fbx_FbxNativeReactor_nSource(JNIEnv *e, jclass,
                                                         jbyteArray b, jint m,
                                                         jint o, jint n,
                                                         jint s) {
  std::lock_guard<std::mutex> lock(mutex);
  return source(e, b, m, o, n, s, -1, 0, false, false);
}
extern "C" JNIEXPORT jobject JNICALL
Java_cc_sirrus_ysmlib_scene_fbx_FbxNativeReactor_nSourceDirect(
    JNIEnv *e, jclass, jbyteArray b, jint m, jint o, jint n, jint s) {
  std::lock_guard<std::mutex> lock(mutex);
  return source_direct(e, b, m, o, n, s);
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_cc_sirrus_ysmlib_scene_fbx_FbxNativeReactor_nEvaluate(
    JNIEnv *e, jclass, jbyteArray b, jint m, jint o, jint n, jint s, jint stack,
    jdouble seconds, jboolean follow) {
  std::lock_guard<std::mutex> lock(mutex);
  return source(e, b, m, o, n, s, stack, seconds, true, follow);
}
extern "C" JNIEXPORT jintArray JNICALL
Java_cc_sirrus_ysmlib_scene_fbx_FbxNativeReactor_nPrepareSelected(
    JNIEnv *e, jclass, jbyteArray b, jint m, jint o, jint n, jint s,
    jintArray meshIds, jintArray skinIds) {
  std::lock_guard<std::mutex> lock(mutex);
  if (!b || m <= 0 || o <= 0 || n <= 0 || s <= 0) {
    fail("Invalid native FBX budgets");
    return nullptr;
  }
  ReadOnlyBytes bytes(e, b);
  if (!bytes.valid()) { fail("Native FBX source access failed"); return nullptr; }
  if (!meshIds || !skinIds ||
      e->GetArrayLength(meshIds) != e->GetArrayLength(skinIds)) {
    fail("Invalid FBX Skin selection arrays");
    return nullptr;
  }
  jsize count = e->GetArrayLength(meshIds);
  std::vector<jint> meshes((size_t)count), skins((size_t)count);
  if (count) {
    e->GetIntArrayRegion(meshIds, 0, count, meshes.data());
    e->GetIntArrayRegion(skinIds, 0, count, skins.data());
  }
  bool same = resident_source.size() == (size_t)bytes.length &&
              (!bytes.length || std::memcmp(resident_source.data(),
                                             bytes.data, (size_t)bytes.length) == 0) &&
              resident_mesh_selection.size() == (size_t)count &&
              resident_skin_selection.size() == (size_t)count &&
              std::equal(resident_mesh_selection.begin(),
                         resident_mesh_selection.end(), meshes.begin()) &&
              std::equal(resident_skin_selection.begin(),
                         resident_skin_selection.end(), skins.begin());
  if (!same) {
    ysm_fbx_destroy();
    resident_source.clear();
    if (bytes.length) resident_source.assign(bytes.data, bytes.data + bytes.length);
    resident_mesh_selection.assign(meshes.begin(), meshes.end());
    resident_skin_selection.assign(skins.begin(), skins.end());
    if (ysm_fbx_load(bytes.data, bytes.length, m, o, n, s) < 0 ||
        ysm_fbx_apply_skin_selection((const uint32_t *)meshes.data(),
                                     (const uint32_t *)skins.data(),
                                     (size_t)count) < 0) {
      fail(ysm_fbx_error());
      resident_source.clear();
      resident_mesh_selection.clear();
      resident_skin_selection.clear();
      ysm_fbx_destroy();
      return nullptr;
    }
  }
  int draws = 0, positions = 0, normals = 0, tangents = 0, bitangents = 0,
      nodes = 0;
  if (ysm_fbx_geometry_counts(&draws, &positions, &normals, &tangents,
                              &bitangents, &nodes) < 0) {
    fail(ysm_fbx_error());
    return nullptr;
  }
  jint values[] = {draws, positions, normals, tangents, bitangents, nodes};
  jintArray out = e->NewIntArray(6);
  if (!out) {
    fail("Native FBX layout allocation failed");
    return nullptr;
  }
  e->SetIntArrayRegion(out, 0, 6, values);
  return out;
}
static void *direct(JNIEnv *env, jobject buffer, size_t required,
                    const char *name) {
  if (!buffer) {
    fail((std::string("FBX ") + name + " buffer is null").c_str());
    return nullptr;
  }
  void *ptr = env->GetDirectBufferAddress(buffer);
  jlong bytes = env->GetDirectBufferCapacity(buffer);
  if (!ptr || bytes < 0 || (size_t)bytes < required) {
    fail((std::string("FBX ") + name + " direct buffer is too small").c_str());
    return nullptr;
  }
  return ptr;
}
extern "C" JNIEXPORT jint JNICALL
Java_cc_sirrus_ysmlib_scene_fbx_FbxNativeReactor_nGeometryEvaluate(
    JNIEnv *e, jclass, jint stack, jdouble seconds, jboolean follow,
    jobject positions, jobject normals, jobject tangents, jobject bitangents,
    jobject drawNodes, jobject drawMeshes, jobject local, jobject world,
    jobject geometryLocal, jobject geometryWorld) {
  std::lock_guard<std::mutex> lock(mutex);
  if (ysm_fbx_evaluate(stack, seconds) < 0) {
    fail(ysm_fbx_error());
    return -1;
  }
  int draws = 0, p = 0, n = 0, t = 0, b = 0, nodes = 0;
  if (ysm_fbx_geometry_counts(&draws, &p, &n, &t, &b, &nodes) < 0) {
    fail(ysm_fbx_error());
    return -1;
  }
  float *pp =
      (float *)direct(e, positions, (size_t)p * sizeof(float), "positions");
  float *nn = (float *)direct(e, normals, (size_t)n * sizeof(float), "normals");
  float *tt =
      (float *)direct(e, tangents, (size_t)t * sizeof(float), "tangents");
  float *bb =
      (float *)direct(e, bitangents, (size_t)b * sizeof(float), "bitangents");
  int *dn =
      (int *)direct(e, drawNodes, (size_t)draws * sizeof(int), "draw nodes");
  int *dm =
      (int *)direct(e, drawMeshes, (size_t)draws * sizeof(int), "draw meshes");
  float *ll = (float *)direct(e, local, (size_t)nodes * 12 * sizeof(float),
                              "local matrices");
  float *ww = (float *)direct(e, world, (size_t)nodes * 12 * sizeof(float),
                              "world matrices");
  float *gl =
      (float *)direct(e, geometryLocal, (size_t)nodes * 12 * sizeof(float),
                      "geometry local matrices");
  float *gw =
      (float *)direct(e, geometryWorld, (size_t)nodes * 12 * sizeof(float),
                      "geometry world matrices");
  if (!pp || !nn || !tt || !bb || !dn || !dm || !ll || !ww || !gl || !gw)
    return -1;
  if (ysm_fbx_geometry_write(pp, p, nn, n, tt, t, bb, b, dn, dm, draws, ll, ww,
                             gl, gw, nodes, follow ? 1 : 0, nullptr, 0) < 0) {
    fail(ysm_fbx_error());
    return -1;
  }
  return 0;
}
extern "C" JNIEXPORT jint JNICALL
Java_cc_sirrus_ysmlib_scene_fbx_FbxNativeReactor_nEvaluateNodes(
    JNIEnv *e, jclass, jint stack, jdouble seconds, jint nodes, jobject local,
    jobject world, jobject geometryLocal, jobject geometryWorld) {
  std::lock_guard<std::mutex> lock(mutex);
  if (nodes <= 0) {
    fail("Invalid FBX node count");
    return -1;
  }
  size_t bytes = (size_t)nodes * 12 * sizeof(float);
  float *ll = (float *)direct(e, local, bytes, "local matrices");
  float *ww = (float *)direct(e, world, bytes, "world matrices");
  float *gl =
      (float *)direct(e, geometryLocal, bytes, "geometry local matrices");
  float *gw =
      (float *)direct(e, geometryWorld, bytes, "geometry world matrices");
  if (!ll || !ww || !gl || !gw)
    return -1;
  if (ysm_fbx_evaluate_nodes(stack, seconds, ll, ww, gl, gw, (size_t)nodes) <
      0) {
    fail(ysm_fbx_error());
    return -1;
  }
  return 0;
}
extern "C" JNIEXPORT jint JNICALL
Java_cc_sirrus_ysmlib_scene_fbx_FbxNativeReactor_nGeometryWrite(
    JNIEnv *e, jclass, jboolean follow, jobject positions, jobject normals,
    jobject tangents, jobject bitangents, jobject drawNodes, jobject drawMeshes,
    jobject local, jobject world, jobject geometryLocal, jobject geometryWorld,
    jobject physics) {
  std::lock_guard<std::mutex> lock(mutex);
  int draws = 0, p = 0, n = 0, t = 0, b = 0, nodes = 0;
  if (ysm_fbx_geometry_counts(&draws, &p, &n, &t, &b, &nodes) < 0) {
    fail(ysm_fbx_error());
    return -1;
  }
  float *pp =
      (float *)direct(e, positions, (size_t)p * sizeof(float), "positions");
  float *nn = (float *)direct(e, normals, (size_t)n * sizeof(float), "normals");
  float *tt =
      (float *)direct(e, tangents, (size_t)t * sizeof(float), "tangents");
  float *bb =
      (float *)direct(e, bitangents, (size_t)b * sizeof(float), "bitangents");
  int *dn =
      (int *)direct(e, drawNodes, (size_t)draws * sizeof(int), "draw nodes");
  int *dm =
      (int *)direct(e, drawMeshes, (size_t)draws * sizeof(int), "draw meshes");
  float *ll = (float *)direct(e, local, (size_t)nodes * 12 * sizeof(float),
                              "local matrices");
  float *ww = (float *)direct(e, world, (size_t)nodes * 12 * sizeof(float),
                              "world matrices");
  float *gl =
      (float *)direct(e, geometryLocal, (size_t)nodes * 12 * sizeof(float),
                      "geometry local matrices");
  float *gw =
      (float *)direct(e, geometryWorld, (size_t)nodes * 12 * sizeof(float),
                      "geometry world matrices");
  float *pd = (float *)direct(e, physics, (size_t)nodes * 16 * sizeof(float),
                              "physics deltas");
  if (!pp || !nn || !tt || !bb || !dn || !dm || !ll || !ww || !gl || !gw || !pd)
    return -1;
  int result =
      ysm_fbx_geometry_write(pp, p, nn, n, tt, t, bb, b, dn, dm, draws, ll, ww,
                             gl, gw, nodes, follow ? 1 : 0, pd, (size_t)nodes);
  if (result < 0) {
    fail(ysm_fbx_error());
    return -1;
  }
  return result;
}
extern "C" JNIEXPORT jstring JNICALL
Java_cc_sirrus_ysmlib_scene_fbx_FbxNativeReactor_nError(JNIEnv *e, jclass) {
  return e->NewStringUTF(last_error.c_str());
}
extern "C" JNIEXPORT void JNICALL
Java_cc_sirrus_ysmlib_scene_fbx_FbxNativeReactor_nRelease(JNIEnv *, jclass) {
  std::lock_guard<std::mutex> lock(mutex);
  ysm_fbx_destroy();
  resident_source.clear();
  resident_mesh_selection.clear();
  resident_skin_selection.clear();
}
