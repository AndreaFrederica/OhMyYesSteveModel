#ifndef YSM_SKINNING_H
#define YSM_SKINNING_H
#include <stdint.h>
#if defined(_WIN32)
#define YSM_SKIN_API __declspec(dllexport)
#else
#define YSM_SKIN_API __attribute__((visibility("default")))
#endif
#ifdef __cplusplus
extern "C" {
#endif
/* ABI 1. Column-major float32 matrices. Input/output vertices are P3,N3,T4.
 * Modes: linear=0, SDEF=1, QDEF=2. Flags: normal=1,tangent=2,inverse normal=4.
 * Success=0, invalid input=-1, allocation/numeric failure=-2. Output is atomic.
 * All lengths count scalars; arrays remain caller-owned for this synchronous call. */
YSM_SKIN_API int ysm_skin_abi(void);
YSM_SKIN_API int ysm_skin(const int32_t *ranges,int ranges_n,const int32_t *joints,int joints_n,
             const float *weights,int weights_n,const int32_t *modes,int modes_n,
             const float *sdef,int sdef_n,const float *palette,int palette_n,
             const float *input,int input_n,float *output,int output_n,int flags,int parallel);
#ifdef __cplusplus
}
#endif
#endif
