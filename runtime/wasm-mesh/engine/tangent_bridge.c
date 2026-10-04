/* YSM adapter; the included MikkTSpace algorithm is unmodified. */
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
#include <math.h>
#include "mikktspace.h"

static size_t allocated, budget;
static int exhausted;
typedef union { size_t size; double alignment; } Allocation;
static void *bounded_malloc(size_t size) {
    if (size > budget || allocated > budget - size || size > SIZE_MAX - sizeof(Allocation)) {
        exhausted = 1; return NULL;
    }
    Allocation *p = malloc(sizeof(Allocation) + size);
    if (!p) { exhausted = 1; return NULL; }
    p->size = size; allocated += size; return p + 1;
}
static void bounded_free(void *ptr) {
    if (!ptr) return;
    Allocation *p = (Allocation *)ptr - 1; allocated -= p->size; free(p);
}
#define malloc bounded_malloc
#define free bounded_free
#include "mikktspace.c"
#undef malloc
#undef free

typedef struct { int faces; const float *input; float *output; } Mesh;
static Mesh *mesh(const SMikkTSpaceContext *c) { return c->m_pUserData; }
static int faces(const SMikkTSpaceContext *c) { return mesh(c)->faces; }
static int vertices(const SMikkTSpaceContext *c, int f) { (void)c; (void)f; return 3; }
static void position(const SMikkTSpaceContext *c, float *out, int f, int v) { memcpy(out, mesh(c)->input + (f*3+v)*8, 12); }
static void normal(const SMikkTSpaceContext *c, float *out, int f, int v) { memcpy(out, mesh(c)->input + (f*3+v)*8+3, 12); }
static void texcoord(const SMikkTSpaceContext *c, float *out, int f, int v) { memcpy(out, mesh(c)->input + (f*3+v)*8+6, 8); }
static void tangent(const SMikkTSpaceContext *c, const float *t, float sign, int f, int v) {
    float *out = mesh(c)->output + (f*3+v)*4; memcpy(out, t, 12); out[3] = sign;
}
int ysm_tangent_abi(void) { return 1; }
/* Input/output buffers are included in the same byte limit as algorithm scratch storage. */
int ysm_tangents(const float *input, float *output, int corners, int limit) {
    if (corners <= 0 || corners % 3 || corners > INT32_MAX / 48 || limit <= 0) return -1;
    size_t io = (size_t)corners * 48;
    if (io > (size_t)limit) return -2;
    allocated = io; budget = (size_t)limit; exhausted = 0;
    Mesh m = { corners / 3, input, output };
    SMikkTSpaceInterface callbacks = { faces, vertices, position, normal, texcoord, tangent, NULL };
    SMikkTSpaceContext context = { &callbacks, &m };
    int ok = genTangSpaceDefault(&context);
    if (allocated != io) return -3;
    // Mikk has optional allocation fallbacks; exceeding the caller budget still fails explicitly.
    return exhausted ? -2 : ok ? 0 : -1;
}
