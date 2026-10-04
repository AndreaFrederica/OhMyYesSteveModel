#include "physics.h"
#include <cassert>
#include <cmath>
int main() {
  auto *w = ysm_physics_create(0, -9.8f, 0, 1.0f / 60);
  assert(w);
  float c[20] = {0, 0.5f, 0, 0, 0, 1, 0,      0, 0, 0,
                 1, 2,    1, 0, 0, 1, 0xffff, 0, 0, 0.04f};
  assert(ysm_physics_body(w, c, 20) == 0);
  float v[9] = {-1, 1, 0, 1, 1, 0, 0, 2, 0};
  int ix[3] = {0, 1, 2};
  int pin[1] = {0};
  float cfg[34] = {1,    0.1f, 0, 0,    0,    0,    0.2f, 0, 1, 0.1f, 1,
                   0.7f, 0.1f, 1, 0.5f, 0.5f, 0.5f, 0.5f, 0, 4, 0,    4,
                   1,    1,    1, 0,    0x11, 0,    0,    0, 1, 1};
  cfg[31] = 1;
  cfg[32] = 65535;
  cfg[33] = .02f;
  int soft = ysm_physics_soft(w, v, 3, ix, 3, pin, 1, 0, 1, cfg, 34);
  assert(soft == 0);
  assert(ysm_physics_soft_count(w, soft) == 3);
  assert(ysm_physics_soft_pin(w, soft, 0, v, 3) == 0);
  assert(ysm_physics_step(w) == 0);
  float o[13];
  assert(ysm_physics_read_bodies(w, o, 13) == 0);
  float sv[9];
  assert(ysm_physics_read_soft(w, soft, sv, 9, 0) == 0);
  ysm_physics_destroy(w);
  auto *oracle = ysm_physics_create(0, -9.8f, 0, 1.0f / 60);
  assert(oracle);
  float floor[20] = {1, 10, 0.5f, 10,   0,     -0.5f, 0,      0, 0, 0,
                     1, 0,  0,    0.5f, 0.04f, 1,     0xffff, 0, 0, 0.04f};
  assert(ysm_physics_body(oracle, floor, 20) == 0);
  float ball[20] = {0, 0.5f, 0, 0,    0, 3, 0,      0, 0, 0,
                    1, 2,    1, 0.5f, 0, 1, 0xffff, 0, 0, 0.04f};
  assert(ysm_physics_body(oracle, ball, 20) == 1);
  const int marks[] = {10, 20, 30, 40, 50, 60};
  const float expected[][6] = {{0, 2.8502779f, 0, 0, -1.63333333f, 0},
                               {0, 2.42833328f, 0, 0, -3.26666713f, 0},
                               {0, 1.73416638f, 0, 0, -4.90000105f, 0},
                               {0, 0.767777145f, 0, 0, -6.53333521f, 0},
                               {6.45317868e-7f, 0.484224647f, 9.03942521e-7f,
                                5.29098952e-6f, 0.236631259f, 7.66248741e-6f},
                               {1.55179339e-6f, 0.498306155f, 2.33743208e-6f,
                                5.89172305e-6f, 0.0254083872f, 9.73080114e-6f}};
  for (int i = 0; i < 60; i++) {
    assert(ysm_physics_step(oracle) == 0);
    for (int m = 0; m < 6; m++)
      if (i + 1 == marks[m]) {
        float t[26];
        assert(ysm_physics_read_bodies(oracle, t, 26) == 0);
        for (int k = 0; k < 6; k++)
          assert(std::fabs(t[13 + (k < 3 ? k : k + 4)] - expected[m][k]) <
                 0.00001f);
      }
  }
  ysm_physics_destroy(oracle);
  auto *joints = ysm_physics_create(0, -9.8f, 0, 1.0f / 60);
  assert(joints);
  float fixed[20] = {0, 0.1f, 0, 0, 0, 0, 0,      0, 0, 0,
                     1, 0,    0, 0, 0, 1, 0xffff, 0, 0, 0.04f};
  float dynamic[20] = {0, 0.1f, 0, 0, 0, 1, 0,      0, 0, 0,
                       1, 2,    1, 0, 0, 1, 0xffff, 0, 0, 0.04f};
  assert(ysm_physics_body(joints, fixed, 20) == 0);
  assert(ysm_physics_body(joints, dynamic, 20) == 1);
  float jc[37] = {0, 0, 1, 0,  0,  0,  0,  0, 0, 1,    0,  0,  0,
                  0, 0, 0, 1,  -1, -1, -1, 1, 1, 1,    -1, -1, -1,
                  1, 1, 1, 10, 10, 10, 2,  2, 2, 0.5f, 1};
  for (int type = 0; type < 6; type++) {
    jc[0] = static_cast<float>(type);
    int id = ysm_physics_joint(joints, jc, 37);
    assert(id >= 0);
    if (type == 3) {
      float o[15] = {3, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1};
      assert(ysm_physics_configure_joint(joints, id, o, 15) == 0);
    }
    if (type == 4) {
      float o[7] = {4, 1, 2, 10, 0, 0, 0};
      assert(ysm_physics_configure_joint(joints, id, o, 7) == 0);
    }
    if (type == 5) {
      float o[7] = {5, 0.8f, 0.3f, 1, 1, 2, 10};
      assert(ysm_physics_configure_joint(joints, id, o, 7) == 0);
    }
  }
  ysm_physics_destroy(joints);
}
