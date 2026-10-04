#include "physics.h"
#include <jni.h>
extern "C" JNIEXPORT jint JNICALL
Java_cc_sirrus_ysmlib_scene_physics_natives_NativePhysicsProvider_nEnvironment(
    JNIEnv* e,jclass,jlong p,jfloatArray values,jint boxes,jint fluids){
  if(!values||boxes<0||boxes>4096||fluids<0||fluids>4096||e->GetArrayLength(values)!=51+boxes*24+fluids*10)return -1;
  auto* data=e->GetFloatArrayElements(values,nullptr);if(!data)return -3;
  int result=ysm_physics_environment(reinterpret_cast<ysm_physics_world*>(p),data,e->GetArrayLength(values),boxes,fluids);
  e->ReleaseFloatArrayElements(values,data,JNI_ABORT);return result;
}
extern "C" JNIEXPORT jint JNICALL
Java_cc_sirrus_ysmlib_scene_physics_natives_NativePhysicsProvider_nEnvironmentDirect(
    JNIEnv* e,jclass,jlong p,jobject values,jint boxes,jint fluids){
  if(!values||boxes<0||boxes>4096||fluids<0||fluids>4096)return -1;
  auto* data=static_cast<float*>(e->GetDirectBufferAddress(values));auto count=e->GetDirectBufferCapacity(values);
  if(!data||reinterpret_cast<uintptr_t>(data)%alignof(float)!=0||count!=51+boxes*24+fluids*10)return -1;
  return ysm_physics_environment(reinterpret_cast<ysm_physics_world*>(p),data,int32_t(count),boxes,fluids);
}
extern "C" JNIEXPORT jint JNICALL
Java_cc_sirrus_ysmlib_scene_physics_natives_NativePhysicsProvider_nClearEnvironment(
    JNIEnv*,jclass,jlong p){return ysm_physics_clear_environment(reinterpret_cast<ysm_physics_world*>(p));}
template <typename T> static bool aligned(const T *p) {
  return p && reinterpret_cast<uintptr_t>(p) % alignof(T) == 0;
}
// Scoped array access releases earlier acquisitions when a later one fails.
struct FloatArray {
  JNIEnv *env;
  jfloatArray array;
  jfloat *data;
  jint mode = JNI_ABORT;
  FloatArray(JNIEnv *e, jfloatArray a)
      : env(e), array(a),
        data(a ? e->GetFloatArrayElements(a, nullptr) : nullptr) {}
  ~FloatArray() {
    if (data)
      env->ReleaseFloatArrayElements(array, data, mode);
  }
  operator jfloat *() const { return data; }
};
struct IntArray {
  JNIEnv *env;
  jintArray array;
  jint *data;
  IntArray(JNIEnv *e, jintArray a)
      : env(e), array(a),
        data(a ? e->GetIntArrayElements(a, nullptr) : nullptr) {}
  ~IntArray() {
    if (data)
      env->ReleaseIntArrayElements(array, data, JNI_ABORT);
  }
  operator jint *() const { return data; }
};

static ysm_physics_world *world(JNIEnv *e, jlong p) {
  return reinterpret_cast<ysm_physics_world *>(p);
}
extern "C" JNIEXPORT jint JNICALL
Java_cc_sirrus_ysmlib_scene_physics_natives_NativePhysicsProvider_nAbiVersion(
    JNIEnv *, jclass) {
  return ysm_physics_abi();
}
extern "C" JNIEXPORT jlong JNICALL
Java_cc_sirrus_ysmlib_scene_physics_natives_NativePhysicsProvider_nFeatureBits(
    JNIEnv *, jclass) {
  return static_cast<jlong>(ysm_physics_features());
}
extern "C" JNIEXPORT jint JNICALL
Java_cc_sirrus_ysmlib_scene_physics_natives_NativePhysicsProvider_nScalarBits(
    JNIEnv *, jclass) {
  return ysm_physics_scalar_bits();
}
extern "C" JNIEXPORT jint JNICALL
Java_cc_sirrus_ysmlib_scene_physics_natives_NativePhysicsProvider_nThreadCount(
    JNIEnv *, jclass) {
  return ysm_physics_thread_count();
}
extern "C" JNIEXPORT jint JNICALL
Java_cc_sirrus_ysmlib_scene_physics_natives_NativePhysicsProvider_nIterations(
    JNIEnv *, jclass, jlong p, jint n) {
  return ysm_physics_solver_iterations(world(nullptr, p), n);
}
extern "C" JNIEXPORT jlong JNICALL
Java_cc_sirrus_ysmlib_scene_physics_natives_NativePhysicsProvider_nCreate(
    JNIEnv *, jclass, jfloat x, jfloat y, jfloat z, jfloat step) {
  return reinterpret_cast<jlong>(ysm_physics_create(x, y, z, step));
}
extern "C" JNIEXPORT jint JNICALL
Java_cc_sirrus_ysmlib_scene_physics_natives_NativePhysicsProvider_nKinematicFilter(
    JNIEnv *, jclass, jlong p, jboolean enabled) {
  return ysm_physics_kinematic_filter(world(nullptr, p), enabled ? 1 : 0);
}
extern "C" JNIEXPORT void JNICALL
Java_cc_sirrus_ysmlib_scene_physics_natives_NativePhysicsProvider_nDestroy(
    JNIEnv *, jclass, jlong p) {
  ysm_physics_destroy(world(nullptr, p));
}
extern "C" JNIEXPORT jint JNICALL
Java_cc_sirrus_ysmlib_scene_physics_natives_NativePhysicsProvider_nBody(
    JNIEnv *e, jclass, jlong p, jfloatArray a) {
  if (!a)
    return -1;
  jsize n = e->GetArrayLength(a);
  if (!a)
    return -1;
  FloatArray v(e, a);
  if (!v.data)
    return -3;
  auto r = ysm_physics_body(world(e, p), v, n);

  return r;
}
extern "C" JNIEXPORT jint JNICALL
Java_cc_sirrus_ysmlib_scene_physics_natives_NativePhysicsProvider_nPose(
    JNIEnv *e, jclass, jlong p, jint i, jfloatArray a) {
  if (!a)
    return -1;
  FloatArray v(e, a);
  if (!v.data)
    return -3;
  auto r = ysm_physics_set_pose(world(e, p), i, v, e->GetArrayLength(a));

  return r;
}
extern "C" JNIEXPORT jint JNICALL
Java_cc_sirrus_ysmlib_scene_physics_natives_NativePhysicsProvider_nPoses(
    JNIEnv *e, jclass, jlong p, jintArray ids, jfloatArray poses) {
  if (!ids || !poses || e->GetArrayLength(ids) > 65536 ||
      e->GetArrayLength(poses) != e->GetArrayLength(ids) * 7)
    return -1;
  if (!ids)
    return -1;
  IntArray i(e, ids);
  if (!i.data)
    return -3;
  if (!poses)
    return -1;
  FloatArray v(e, poses);
  if (!v.data)
    return -3;
  jsize n = e->GetArrayLength(ids);
  auto r = ysm_physics_set_poses(world(e, p), i, v, n);

  return r;
}
extern "C" JNIEXPORT jint JNICALL
Java_cc_sirrus_ysmlib_scene_physics_natives_NativePhysicsProvider_nKinematic(
    JNIEnv *, jclass, jlong p, jint i, jboolean x) {
  return ysm_physics_set_kinematic(world(nullptr, p), i, x);
}
extern "C" JNIEXPORT jint JNICALL
Java_cc_sirrus_ysmlib_scene_physics_natives_NativePhysicsProvider_nVelocity(
    JNIEnv *e, jclass, jlong p, jint i, jfloatArray a) {
  if (!a)
    return -1;
  FloatArray v(e, a);
  if (!v.data)
    return -3;
  auto r =
      ysm_physics_set_velocity(world(nullptr, p), i, v, e->GetArrayLength(a));

  return r;
}
extern "C" JNIEXPORT jint JNICALL
Java_cc_sirrus_ysmlib_scene_physics_natives_NativePhysicsProvider_nImpulse(
    JNIEnv *e, jclass, jlong p, jint i, jfloatArray a, jboolean local) {
  if (!a)
    return -1;
  FloatArray v(e, a);
  if (!v.data)
    return -3;
  auto r = ysm_physics_apply_impulse(world(nullptr, p), i, v,
                                     e->GetArrayLength(a), local);

  return r;
}
extern "C" JNIEXPORT jint JNICALL
Java_cc_sirrus_ysmlib_scene_physics_natives_NativePhysicsProvider_nJoint(
    JNIEnv *e, jclass, jlong p, jfloatArray a) {
  if (!a)
    return -1;
  FloatArray v(e, a);
  if (!v.data)
    return -3;
  auto r = ysm_physics_joint(world(nullptr, p), v, e->GetArrayLength(a));

  return r;
}
extern "C" JNIEXPORT jint JNICALL
Java_cc_sirrus_ysmlib_scene_physics_natives_NativePhysicsProvider_nConfigureJoint(
    JNIEnv *e, jclass, jlong p, jint i, jfloatArray a) {
  if (!a)
    return -1;
  FloatArray v(e, a);
  if (!v.data)
    return -3;
  auto r = ysm_physics_configure_joint(world(nullptr, p), i, v,
                                       e->GetArrayLength(a));

  return r;
}
extern "C" JNIEXPORT jint JNICALL
Java_cc_sirrus_ysmlib_scene_physics_natives_NativePhysicsProvider_nReset(
    JNIEnv *, jclass, jlong p, jint i) {
  return ysm_physics_reset_forces(world(nullptr, p), i);
}
extern "C" JNIEXPORT jint JNICALL
Java_cc_sirrus_ysmlib_scene_physics_natives_NativePhysicsProvider_nGravity(
    JNIEnv *e, jclass, jlong p, jfloatArray a) {
  if (!a)
    return -1;
  FloatArray v(e, a);
  if (!v.data)
    return -3;
  auto r = ysm_physics_set_gravity(world(nullptr, p), v, e->GetArrayLength(a));

  return r;
}
extern "C" JNIEXPORT jint JNICALL
Java_cc_sirrus_ysmlib_scene_physics_natives_NativePhysicsProvider_nSoft(
    JNIEnv *e, jclass, jlong p, jfloatArray v, jintArray ix, jintArray pins,
    jboolean rope, jfloat mass, jfloatArray cfg) {
  if (!v || !ix || e->GetArrayLength(v) % 3 != 0 ||
      e->GetArrayLength(v) > 3000000 || e->GetArrayLength(ix) > 12000000)
    return -1;
  if (!v)
    return -1;
  FloatArray vf(e, v);
  if (!vf.data)
    return -3;
  if (!ix)
    return -1;
  IntArray iv(e, ix);
  if (!iv.data)
    return -3;
  IntArray pv(e, pins);
  if (pins && !pv.data)
    return -3;
  FloatArray cv(e, cfg);
  if (cfg && !cv.data)
    return -3;
  auto r = ysm_physics_soft(world(nullptr, p), vf, e->GetArrayLength(v) / 3, iv,
                            e->GetArrayLength(ix), pv,
                            pins ? e->GetArrayLength(pins) : 0, rope, mass, cv,
                            cfg ? e->GetArrayLength(cfg) : 0);

  return r;
}
extern "C" JNIEXPORT jint JNICALL
Java_cc_sirrus_ysmlib_scene_physics_natives_NativePhysicsProvider_nSoftCount(
    JNIEnv *, jclass, jlong p, jint i) {
  return ysm_physics_soft_count(world(nullptr, p), i);
}
extern "C" JNIEXPORT jint JNICALL
Java_cc_sirrus_ysmlib_scene_physics_natives_NativePhysicsProvider_nSoftRead(
    JNIEnv *e, jclass, jlong p, jint i, jfloatArray out, jboolean n) {
  if (!out)
    return -1;
  FloatArray v(e, out);
  if (!v.data)
    return -3;
  auto r =
      ysm_physics_read_soft(world(nullptr, p), i, v, e->GetArrayLength(out), n);
  v.mode = r < 0 ? JNI_ABORT : 0;
  return r;
}
extern "C" JNIEXPORT jint JNICALL
Java_cc_sirrus_ysmlib_scene_physics_natives_NativePhysicsProvider_nSoftPin(
    JNIEnv *e, jclass, jlong p, jint i, jint v, jfloatArray a) {
  if (!a)
    return -1;
  FloatArray x(e, a);
  if (!x.data)
    return -3;
  auto r =
      ysm_physics_soft_pin(world(nullptr, p), i, v, x, e->GetArrayLength(a));

  return r;
}
extern "C" JNIEXPORT jint JNICALL
Java_cc_sirrus_ysmlib_scene_physics_natives_NativePhysicsProvider_nSoftAnchor(
    JNIEnv *, jclass, jlong p, jint i, jint v, jint b, jboolean d) {
  return ysm_physics_soft_anchor(world(nullptr, p), i, v, b, d);
}
extern "C" JNIEXPORT jint JNICALL
Java_cc_sirrus_ysmlib_scene_physics_natives_NativePhysicsProvider_nStep(
    JNIEnv *, jclass, jlong p) {
  return ysm_physics_step(world(nullptr, p));
}
extern "C" JNIEXPORT jint JNICALL
Java_cc_sirrus_ysmlib_scene_physics_natives_NativePhysicsProvider_nCount(
    JNIEnv *, jclass, jlong p) {
  return ysm_physics_body_count(world(nullptr, p));
}
extern "C" JNIEXPORT jint JNICALL
Java_cc_sirrus_ysmlib_scene_physics_natives_NativePhysicsProvider_nRead(
    JNIEnv *e, jclass, jlong p, jfloatArray a) {
  if (!a)
    return -1;
  FloatArray v(e, a);
  if (!v.data)
    return -3;
  auto r = ysm_physics_read_bodies(world(nullptr, p), v, e->GetArrayLength(a));
  v.mode = r < 0 ? JNI_ABORT : 0;
  return r;
}

extern "C" JNIEXPORT jint JNICALL
Java_cc_sirrus_ysmlib_scene_physics_natives_NativePhysicsProvider_nBuild(
    JNIEnv *e, jclass, jlong p, jfloatArray bodies, jfloatArray joints) {
  if (!bodies || !joints)
    return -1;
  jsize bn = e->GetArrayLength(bodies), jn = e->GetArrayLength(joints);
  if (bn % 20 || jn % 37 || bn / 20 > 65536 || jn / 37 > 65536)
    return -1;
  FloatArray b(e, bodies);
  if (bn && !b.data)
    return -3;
  FloatArray j(e, joints);
  if (jn && !j.data)
    return -3;
  return ysm_physics_build(world(e, p), b, bn / 20, j, jn / 37);
}
extern "C" JNIEXPORT jint JNICALL
Java_cc_sirrus_ysmlib_scene_physics_natives_NativePhysicsProvider_nImpulses(
    JNIEnv *e, jclass, jlong p, jintArray ids, jfloatArray values) {
  if (!ids || !values)
    return -1;
  jsize n = e->GetArrayLength(ids);
  if (n > 65536 || e->GetArrayLength(values) != n * 7)
    return -1;
  IntArray i(e, ids);
  if (n && !i.data)
    return -3;
  FloatArray v(e, values);
  if (n && !v.data)
    return -3;
  return ysm_physics_impulses(world(e, p), i, v, n);
}
extern "C" JNIEXPORT jint JNICALL
Java_cc_sirrus_ysmlib_scene_physics_natives_NativePhysicsProvider_nReadDirect(
    JNIEnv *e, jclass, jlong p, jobject output) {
  if (!output)
    return -1;
  auto *data = static_cast<float *>(e->GetDirectBufferAddress(output));
  jlong capacity = e->GetDirectBufferCapacity(output);
  if (!aligned(data) || capacity < 0 || capacity > 65536 * 13)
    return -1;
  return ysm_physics_read_bodies(world(e, p), data, int32_t(capacity));
}

extern "C" JNIEXPORT jint JNICALL
Java_cc_sirrus_ysmlib_scene_physics_natives_NativePhysicsProvider_nLoadsDirect(
    JNIEnv *e, jclass, jlong p, jobject ids, jobject values, jboolean force) {
  if (!ids || !values)
    return -1;
  auto *i = static_cast<int32_t *>(e->GetDirectBufferAddress(ids));
  auto *v = static_cast<float *>(e->GetDirectBufferAddress(values));
  jlong n = e->GetDirectBufferCapacity(ids),
        vn = e->GetDirectBufferCapacity(values);
  if (!aligned(i) || !aligned(v) || n < 0 || n > 65536 || vn != n * 7)
    return -1;
  return force ? ysm_physics_forces(world(e, p), i, v, int32_t(n))
               : ysm_physics_impulses(world(e, p), i, v, int32_t(n));
}

extern "C" JNIEXPORT jint JNICALL
Java_cc_sirrus_ysmlib_scene_physics_natives_NativePhysicsProvider_nPosesDirect(
    JNIEnv *e, jclass, jlong p, jobject ids, jobject poses) {
  if (!ids || !poses)
    return -1;
  auto *i = static_cast<int32_t *>(e->GetDirectBufferAddress(ids));
  auto *v = static_cast<float *>(e->GetDirectBufferAddress(poses));
  jlong n = e->GetDirectBufferCapacity(ids),
        pn = e->GetDirectBufferCapacity(poses);
  if (!aligned(i) || !aligned(v) || n < 0 || n > 65536 || pn != n * 7)
    return -1;
  return ysm_physics_set_poses(world(e, p), i, v, int32_t(n));
}
extern "C" JNIEXPORT jint JNICALL
Java_cc_sirrus_ysmlib_scene_physics_natives_NativePhysicsProvider_nSoftReadDirect(
    JNIEnv *e, jclass, jlong p, jint id, jobject output, jboolean normals) {
  if (!output)
    return -1;
  auto *v = static_cast<float *>(e->GetDirectBufferAddress(output));
  jlong n = e->GetDirectBufferCapacity(output);
  if (!aligned(v) || n < 0 || n > 3000000)
    return -1;
  return ysm_physics_read_soft(world(e, p), id, v, int32_t(n), normals);
}

extern "C" JNIEXPORT jint JNICALL
Java_cc_sirrus_ysmlib_scene_physics_natives_NativePhysicsProvider_nPinsDirect(
    JNIEnv *e, jclass, jlong p, jint soft, jobject ids, jobject positions) {
  if (!ids || !positions)
    return -1;
  auto *i = static_cast<int32_t *>(e->GetDirectBufferAddress(ids));
  auto *v = static_cast<float *>(e->GetDirectBufferAddress(positions));
  jlong n = e->GetDirectBufferCapacity(ids),
        pn = e->GetDirectBufferCapacity(positions);
  if (!aligned(i) || !aligned(v) || n < 0 || n > 1000000 || pn != n * 3)
    return -1;
  return ysm_physics_soft_pins(world(e, p), soft, i, v, int32_t(n));
}
extern "C" JNIEXPORT jint JNICALL
Java_cc_sirrus_ysmlib_scene_physics_natives_NativePhysicsProvider_nPins(
    JNIEnv *e, jclass, jlong p, jint soft, jintArray ids,
    jfloatArray positions) {
  if (!ids || !positions)
    return -1;
  jsize n = e->GetArrayLength(ids);
  if (n > 1000000 || e->GetArrayLength(positions) != n * 3)
    return -1;
  IntArray i(e, ids);
  if (n && !i.data)
    return -3;
  FloatArray v(e, positions);
  if (n && !v.data)
    return -3;
  return ysm_physics_soft_pins(world(e, p), soft, i, v, n);
}
extern "C" JNIEXPORT jint JNICALL
Java_cc_sirrus_ysmlib_scene_physics_natives_NativePhysicsProvider_nForces(
    JNIEnv *e, jclass, jlong p, jintArray ids, jfloatArray values) {
  if (!ids || !values)
    return -1;
  jsize n = e->GetArrayLength(ids);
  if (n > 65536 || e->GetArrayLength(values) != n * 7)
    return -1;
  IntArray i(e, ids);
  if (n && !i.data)
    return -3;
  FloatArray v(e, values);
  if (n && !v.data)
    return -3;
  return ysm_physics_forces(world(e, p), i, v, n);
}

extern "C" JNIEXPORT jint JNICALL
Java_cc_sirrus_ysmlib_scene_physics_natives_NativePhysicsProvider_nClamp(
    JNIEnv *e, jclass, jlong p, jintArray ids, jfloat linear, jfloat angular) {
  if (!ids)
    return -1;
  jsize n = e->GetArrayLength(ids);
  if (n > 65536)
    return -1;
  IntArray i(e, ids);
  if (n && !i.data)
    return -3;
  return ysm_physics_clamp_velocities(world(e, p), i, n, linear, angular);
}
extern "C" JNIEXPORT jint JNICALL
Java_cc_sirrus_ysmlib_scene_physics_natives_NativePhysicsProvider_nClampDirect(
    JNIEnv *e, jclass, jlong p, jobject ids, jfloat linear, jfloat angular) {
  if (!ids)
    return -1;
  auto *i = static_cast<int32_t *>(e->GetDirectBufferAddress(ids));
  jlong n = e->GetDirectBufferCapacity(ids);
  if (!aligned(i) || n < 0 || n > 65536)
    return -1;
  return ysm_physics_clamp_velocities(world(e, p), i, int32_t(n), linear,
                                      angular);
}
