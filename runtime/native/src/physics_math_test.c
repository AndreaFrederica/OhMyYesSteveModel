#include "math/ysm_bullet_math.h"
#include <stdio.h>
static float value(uint32_t bits) {
  float result;
  memcpy(&result, &bits, 4);
  return result;
}
static uint32_t bits(float value) {
  uint32_t result;
  memcpy(&result, &value, 4);
  return result;
}
int main(void) {
  /* Independent WASI SDK 34 libm outputs, including CRT one-ULP disagreements.
   */
  if (bits(ysm_math_atan2f(value(0x3eafc42b), value(0x3f15fdb6))) !=
          0x3f07ae5a ||
      bits(ysm_math_asinf(value(0xbebc532a))) != 0xbec0da54 ||
      bits(ysm_math_atan2f(-0.0f, 1.0f)) != 0x80000000 ||
      bits(ysm_math_atan2f(0.0f, -1.0f)) != 0x40490fdb ||
      !isnan(ysm_math_asinf(2.0f))) {
    fprintf(stderr, "Portable joint math disagrees with WASI oracle\n");
    return 1;
  }
  return 0;
}
