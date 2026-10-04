#include "physics.h"
#include <float.h>
#include <math.h>
#include <stdio.h>

#define CHECK(x)                                                               \
  do {                                                                         \
    if (!(x)) {                                                                \
      fprintf(stderr, "ABI check failed at %d: %s\n", __LINE__, #x);           \
      return 1;                                                                \
    }                                                                          \
  } while (0)

/* Compiled as C, not C++: validates the public ABI and rejects partial writes.
 */
int main(void) {
  CHECK(ysm_physics_abi() == 6);
  CHECK(ysm_physics_scalar_bits() == 32 && ysm_physics_thread_count() == 1);
  CHECK(!ysm_physics_create(0, NAN, 0, 1.f / 60));
  ysm_physics_world *w = ysm_physics_create(0, -9.8f, 0, 1.f / 60);
  CHECK(w);
  CHECK(ysm_physics_features() == 0x1ff);
  CHECK(ysm_physics_kinematic_filter(0, 1) == -1);
  CHECK(ysm_physics_kinematic_filter(w, 2) == -1);
  CHECK(ysm_physics_kinematic_filter(w, 1) == 0);
  CHECK(ysm_physics_kinematic_filter(w, 0) == 0);
  CHECK(ysm_physics_set_poses(w, 0, 0, 0) == 0);
  CHECK(ysm_physics_set_poses(w, 0, 0, 1) == -1);
  float body[20] = {0, .5f, 0, 0,   0, 2, 0,     0, 0, 0,
                    1, 2,   1, .5f, 0, 1, 65535, 0, 0, .04f};
  CHECK(ysm_physics_body(w, body, 20) == 0);
  CHECK(ysm_physics_kinematic_filter(w, 1) == -1);
  float out[13];
  for (int i = 0; i < 13; ++i)
    out[i] = 123;
  CHECK(ysm_physics_read_bodies(w, out, 12) == -1);
  for (int i = 0; i < 13; ++i)
    CHECK(out[i] == 123);
  int32_t ids[2] = {0, 999};
  float poses[14] = {4, 5, 6, 0, 0, 0, 1, 7, 8, 9, 0, 0, 0, 1};
  CHECK(ysm_physics_set_poses(w, ids, poses, 2) == -1);
  CHECK(ysm_physics_read_bodies(w, out, 13) == 0);
  CHECK(out[0] == 0 && out[1] == 2);
  float env[51] = {0};
  env[0]=env[5]=env[10]=env[15]=env[16]=env[21]=env[26]=env[31]=1;
  env[45]=-9.8f;env[47]=1;env[48]=4;env[49]=.85f;
  CHECK(ysm_physics_environment(w,env,50,0,0)==-1);
  CHECK(ysm_physics_environment(w,env,51,1,0)==-1);
  env[35]=NAN;CHECK(ysm_physics_environment(w,env,51,0,0)==-1);env[35]=0;
  CHECK(ysm_physics_environment(w,env,51,0,0)==0);
  CHECK(ysm_physics_body_count(w)==1);
  CHECK(ysm_physics_clear_environment(w)==0);
  body[7] = body[10] = 0;
  CHECK(ysm_physics_body(w, body, 20) == -1);
  CHECK(ysm_physics_body_count(w) == 1);
  CHECK(ysm_physics_step(w) == 0);
  CHECK(ysm_physics_read_bodies(w, out, 13) == 0 && out[8] < 0);
  ysm_physics_destroy(w);
  w = ysm_physics_create(0, 0, 0, 1.f / 60);
  CHECK(w);
  body[7] = 0;
  body[10] = 1;
  CHECK(ysm_physics_body(w, body, 20) == 0);
  float huge[6] = {0, 0, 0, FLT_MAX, 0, 0};
  CHECK(ysm_physics_apply_impulse(w, 0, huge, 6, 0) == -2);
  for (int i = 0; i < 13; ++i)
    out[i] = 123;
  CHECK(ysm_physics_read_bodies(w, out, 13) == -3);
  for (int i = 0; i < 13; ++i)
    CHECK(out[i] == 123);
  CHECK(ysm_physics_solver_iterations(w, 10) == -3);
  CHECK(ysm_physics_kinematic_filter(w, 0) == -3);
  CHECK(ysm_physics_step(w) == -3);
  ysm_physics_destroy(w);
  ysm_physics_destroy(0);
  return 0;
}
