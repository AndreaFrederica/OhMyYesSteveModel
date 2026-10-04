#pragma once
#include <stdint.h>

#if defined(_WIN32)
#define YSM_PHYSICS_API __declspec(dllexport)
#else
#define YSM_PHYSICS_API __attribute__((visibility("default")))
#endif

/* Results: -1 rejected input, -2 non-finite simulation, -3 native exception.
 * Worlds are single-owner and not safe for concurrent calls. Rebuild after
 * -2/-3. */
typedef struct ysm_physics_world ysm_physics_world;

enum ysm_physics_feature {
  YSM_PHYSICS_RIGID = 1,
  YSM_PHYSICS_JOINT = 2,
  YSM_PHYSICS_SOFT = 4,
  YSM_PHYSICS_PIN_ANCHOR = 8,
  YSM_PHYSICS_NORMAL_READBACK = 16,
  YSM_PHYSICS_FINITE_READBACK = 32,
  YSM_PHYSICS_LOCAL_IMPULSE = 64,
  YSM_PHYSICS_KINEMATIC_FILTER = 128,
  YSM_PHYSICS_HOST_ENVIRONMENT = 256
};

/* ABI 6: body=20 floats, joint=37, soft config=34, state=13; host environment added.
 * Poses use
 * position + normalized XYZW; matrices/vertex IDs use source space.
 * Batches
 * preflight every entry. Allocation/simulation failure poisons a world.
 *
 * Empty batches are allowed. destroy accepts NULL, then invalidates the handle.
 */

#ifdef __cplusplus
extern "C" {
#endif
YSM_PHYSICS_API int32_t ysm_physics_abi(void);
YSM_PHYSICS_API uint64_t ysm_physics_features(void);
YSM_PHYSICS_API int32_t ysm_physics_scalar_bits(void);
YSM_PHYSICS_API int32_t ysm_physics_thread_count(void);
YSM_PHYSICS_API int32_t ysm_physics_solver_iterations(ysm_physics_world *,
                                                      int32_t iterations);
YSM_PHYSICS_API ysm_physics_world *ysm_physics_create(float gx, float gy,
                                                      float gz, float step);
YSM_PHYSICS_API void ysm_physics_destroy(ysm_physics_world *);
/* Construction-only; reject mixed kinematic/non-kinematic pairs after
 * group/mask checks. Select before adding rigid or soft bodies. enabled is
 * exactly 0 or 1. */
YSM_PHYSICS_API int32_t ysm_physics_kinematic_filter(ysm_physics_world *,
                                                     int32_t enabled);
YSM_PHYSICS_API int32_t ysm_physics_body(ysm_physics_world *,
                                         const float *config, int32_t count);
YSM_PHYSICS_API int32_t ysm_physics_set_pose(ysm_physics_world *, int32_t body,
                                             const float *pose, int32_t count);
YSM_PHYSICS_API int32_t ysm_physics_set_poses(ysm_physics_world *,
                                              const int32_t *bodies,
                                              const float *poses,
                                              int32_t body_count);
YSM_PHYSICS_API int32_t ysm_physics_set_kinematic(ysm_physics_world *,
                                                  int32_t body,
                                                  int32_t enabled);
YSM_PHYSICS_API int32_t ysm_physics_set_velocity(ysm_physics_world *,
                                                 int32_t body,
                                                 const float *velocity,
                                                 int32_t count);
YSM_PHYSICS_API int32_t ysm_physics_apply_impulse(ysm_physics_world *,
                                                  int32_t body,
                                                  const float *impulse,
                                                  int32_t count, int32_t local);
YSM_PHYSICS_API int32_t ysm_physics_joint(ysm_physics_world *,
                                          const float *config, int32_t count);
YSM_PHYSICS_API int32_t ysm_physics_configure_joint(ysm_physics_world *,
                                                    int32_t joint,
                                                    const float *config,
                                                    int32_t count);
YSM_PHYSICS_API int32_t ysm_physics_soft(
    ysm_physics_world *, const float *vertices, int32_t vertex_count,
    const int32_t *indices, int32_t index_count, const int32_t *pins,
    int32_t pin_count, int32_t rope, float mass, const float *config,
    int32_t config_count);
YSM_PHYSICS_API int32_t ysm_physics_soft_count(const ysm_physics_world *,
                                               int32_t soft);
YSM_PHYSICS_API int32_t ysm_physics_read_soft(const ysm_physics_world *,
                                              int32_t soft, float *output,
                                              int32_t count, int32_t normals);
YSM_PHYSICS_API int32_t ysm_physics_soft_pin(ysm_physics_world *, int32_t soft,
                                             int32_t vertex,
                                             const float *position,
                                             int32_t count);
YSM_PHYSICS_API int32_t ysm_physics_soft_anchor(ysm_physics_world *,
                                                int32_t soft, int32_t vertex,
                                                int32_t body,
                                                int32_t disable_collision);
YSM_PHYSICS_API int32_t ysm_physics_reset_forces(ysm_physics_world *,
                                                 int32_t body);
YSM_PHYSICS_API int32_t ysm_physics_set_gravity(ysm_physics_world *,
                                                const float *gravity,
                                                int32_t count);
YSM_PHYSICS_API int32_t ysm_physics_step(ysm_physics_world *);
/* Header 51 floats, terrain hulls 24 each, fluids 10 each. Separate collision objects do not occupy body IDs.
 * All entries validated before mutation; failure during allocation poisons the world. */
YSM_PHYSICS_API int32_t ysm_physics_environment(ysm_physics_world *,const float *values,int32_t count,int32_t boxes,int32_t fluids);
YSM_PHYSICS_API int32_t ysm_physics_clear_environment(ysm_physics_world *);
YSM_PHYSICS_API int32_t ysm_physics_body_count(const ysm_physics_world *);
YSM_PHYSICS_API int32_t ysm_physics_read_bodies(const ysm_physics_world *,
                                                float *output, int32_t count);
YSM_PHYSICS_API int32_t ysm_physics_build(ysm_physics_world *,
                                          const float *body_configs,
                                          int32_t body_count,
                                          const float *joint_configs,
                                          int32_t joint_count);
YSM_PHYSICS_API int32_t ysm_physics_impulses(ysm_physics_world *,
                                             const int32_t *body_ids,
                                             const float *impulses,
                                             int32_t count);
YSM_PHYSICS_API int32_t ysm_physics_soft_pins(ysm_physics_world *, int32_t soft,
                                              const int32_t *vertices,
                                              const float *positions,
                                              int32_t count);
YSM_PHYSICS_API int32_t ysm_physics_forces(ysm_physics_world *,
                                           const int32_t *body_ids,
                                           const float *forces, int32_t count);
YSM_PHYSICS_API int32_t ysm_physics_clamp_velocities(ysm_physics_world *,
                                                     const int32_t *body_ids,
                                                     int32_t count,
                                                     float max_linear,
                                                     float max_angular);
#ifdef __cplusplus
}
#endif
