#ifndef YSM_BULLET_MATH_H
#define YSM_BULLET_MATH_H
#include <math.h>
#include <stdint.h>
#include <string.h>
#ifdef __cplusplus
extern "C" {
#endif
float ysm_math_atan2f(float y, float x);
float ysm_math_asinf(float x);
float ysm_math_atanf(float x);
#ifdef __cplusplus
}
#endif
/* Private musl support operations, with memcpy to avoid aliasing violations. */
#define GET_FLOAT_WORD(word, value)                                            \
  do {                                                                         \
    float ysm_math_value = (value);                                            \
    memcpy(&(word), &ysm_math_value, sizeof(float));                           \
  } while (0)
#define FORCE_EVAL(value)                                                      \
  do {                                                                         \
    volatile float ysm_math_evaluation = (value);                              \
    (void)ysm_math_evaluation;                                                 \
  } while (0)
#endif
