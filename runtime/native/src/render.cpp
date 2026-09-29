#include "render.h"
#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <cstring>
#include <limits>
#include <new>
#include <vector>

namespace ysmlib {
using V3 = std::array<float, 3>;
using M4 = std::array<float, 16>;
using M3 = std::array<float, 9>;
constexpr size_t kBudget = 256u * 1024u * 1024u;

uint32_t u32(const uint8_t* p) {
    return uint32_t(p[0]) | uint32_t(p[1]) << 8 | uint32_t(p[2]) << 16 | uint32_t(p[3]) << 24;
}
float f32(const uint8_t* p) { return std::bit_cast<float>(u32(p)); }
void put32(uint8_t* p, uint32_t x) {
    for (int i = 0; i < 4; ++i) p[i] = uint8_t(x >> (8 * i));
}
void putf(uint8_t* p, float x) { put32(p, std::bit_cast<uint32_t>(x)); }
template <size_t N> std::array<float, N> floats(const uint8_t* p) {
    std::array<float, N> r;
    for (size_t i = 0; i < N; ++i) r[i] = f32(p + 4 * i);
    return r;
}
template <size_t N> bool finite(const std::array<float, N>& v) {
    return std::all_of(v.begin(), v.end(), [](float f) { return std::isfinite(f); });
}
template <size_t N> std::array<float, N * N> mul(const std::array<float, N * N>& a,
                                               const std::array<float, N * N>& b) {
    std::array<float, N * N> r;
    for (size_t col = 0; col < N; ++col) for (size_t row = 0; row < N; ++row) {
        float x = a[(N - 1) * N + row] * b[col * N + N - 1];
        for (int k = int(N) - 2; k >= 0; --k) x = a[k * N + row] * b[col * N + k] + x;
        r[col * N + row] = x;
    }
    return r;
}
V3 transform(const M4& m, V3 v, bool position) {
    V3 r;
    for (int i = 0; i < 3; ++i)
        r[i] = m[i] * v[0] + (m[4 + i] * v[1] + (m[8 + i] * v[2] + (position ? m[12 + i] : 0)));
    return r;
}
V3 transform(const M3& m, V3 v) {
    return {m[0] * v[0] + (m[3] * v[1] + m[6] * v[2]),
            m[1] * v[0] + (m[4] * v[1] + m[7] * v[2]),
            m[2] * v[0] + (m[5] * v[1] + m[8] * v[2])};
}
float dot(V3 a, V3 b) { return a[0]*b[0] + (a[1]*b[1] + a[2]*b[2]); }
V3 normalize(V3 v) {
    float n = dot(v, v);
    if (n > 0 && std::isfinite(n)) {
        float scale = 1 / std::sqrt(n);
        for (auto& x : v) x *= scale;
        return v;
    }
    return {};
}
uint32_t snorm(float x) {
    // Java Math.rint is ties-to-even, independent of the host rounding mode.
    double v = double(x * 127.f);
    if (std::isnan(v)) return 0;
    v = std::clamp(v, -127.0, 127.0);
    double lo = std::floor(v), fraction = v - lo;
    int rounded = int(lo) + (fraction > .5 || (fraction == .5 && (int(lo) & 1)) ? 1 : 0);
    return uint32_t(rounded) & 255;
}
uint32_t pack(V3 v, float w = 0) {
    return snorm(v[0]) | snorm(v[1]) << 8 | snorm(v[2]) << 16 | snorm(w) << 24;
}
bool uniform(const M4& m) {
    V3 a{m[0],m[1],m[2]}, b{m[4],m[5],m[6]}, c{m[8],m[9],m[10]};
    float x = dot(a,a), y = dot(b,b), z = dot(c,c), max = std::max({x,y,z});
    float e = max * 32 * std::numeric_limits<float>::epsilon();
    return max > 0 && std::isfinite(max) && std::abs(x-y) <= e && std::abs(x-z) <= e
        && std::abs(dot(a,b)) <= e && std::abs(dot(a,c)) <= e && std::abs(dot(b,c)) <= e;
}
float det(const std::array<float,4>& a, const std::array<float,4>& b,
          const std::array<float,4>& c, int x, int y, int z) {
    return a[x]*(b[y]*c[z]-b[z]*c[y]) - a[y]*(b[x]*c[z]-b[z]*c[x]) + a[z]*(b[x]*c[y]-b[y]*c[x]);
}
std::array<float,4> facing(const M4& m) {
    std::array<float,4> x{m[0],m[4],m[8],m[12]}, y{m[1],m[5],m[9],m[13]}, w{m[3],m[7],m[11],m[15]};
    return {det(x,y,w,1,2,3), -det(x,y,w,0,2,3), det(x,y,w,0,1,3), -det(x,y,w,0,1,2)};
}
float determinant(const M4& m) {
    return (m[0]*m[5]-m[1]*m[4])*m[10] + (m[2]*m[4]-m[0]*m[6])*m[9]
        + (m[1]*m[6]-m[2]*m[5])*m[8];
}
uint32_t color(uint32_t a, uint32_t b, bool transparent) {
    uint32_t result = 0;
    for (int i = 0; i < 4; ++i) {
        auto x = (a >> (8*i)) & 255, y = (b >> (8*i)) & 255;
        result |= (i == 3 && !transparent ? x : (x*y+127)/255) << (8*i);
    }
    return result;
}
struct Bone {
    bool active = false, uniform = false;
    float orientation = 1;
    uint32_t rgba = 0, glow = 0;
    M4 pose{}, clip{};
    M3 normal{};
    std::array<float,4> facing{};
};
struct Quad {
    std::array<std::array<uint8_t,56>,4> vertices{};
    float depth = -std::numeric_limits<float>::infinity();
};

int render(const uint8_t* g, size_t gs, const uint8_t* f, size_t fs,
           int stride, int mid, uint8_t* output, size_t os) {
    bool vanilla = stride == 36 && mid == 0;
    if (!vanilla && !((stride == 54 || stride == 55 || stride == 56) && mid == 42)
            && !(stride == 56 && mid == 44)) return 1;
    if (gs < 20 || gs > kBudget || fs < 260 || fs > kBudget || !g || !f) return 1;
    if (u32(g) != 0x52534d59 || u32(g+4) != 1 || u32(g+16) > 1) return 1;
    uint32_t bone_count = u32(g+8), cubes = u32(g+12);
    uint32_t active = u32(f+252), vertices = u32(f+256);
    if (bone_count > 1000000 || active > bone_count || fs != 260u + size_t(active)*120
            || vertices % 4 || size_t(vertices)*stride > os || size_t(vertices)*56 > kBudget
            || (vertices && !output) || cubes > (gs-20)/16 || u32(f+248) > 1) return 1;
    M4 model = floats<16>(f), view = floats<16>(f+100), projection = floats<16>(f+164);
    M3 normal = floats<9>(f+64);
    if (!finite(model) || !finite(view) || !finite(projection) || !finite(normal)) return 1;
    const bool shadow = u32(f+248) != 0, pbr = u32(g+16) != 0;
    const bool outer_uniform = uniform(model);
    const float orientation = determinant(model) < 0 ? -1.f : 1.f;
    M4 clip_outer = mul<4>(mul<4>(projection,view),model);
    std::vector<Bone> bones(bone_count);
    for (uint32_t i = 0; i < active; ++i) {
        const uint8_t* row = f + 260 + size_t(i)*120;
        uint32_t index = u32(row), glow = u32(row+108);
        if (index >= bone_count || bones[index].active || (glow != 255 && glow > 15)
                || u32(row+112) > 1 || (f32(row+116) != 1 && f32(row+116) != -1)) return 1;
        auto& b = bones[index];
        M4 pose = floats<16>(row+4);
        M3 n = floats<9>(row+68);
        b.active = true;
        b.pose = mul<4>(model,pose);
        b.normal = mul<3>(normal,n);
        b.clip = mul<4>(clip_outer,pose);
        if (!finite(b.pose) || !finite(b.normal) || !finite(b.clip)) return 1;
        b.facing = facing(b.clip);
        b.uniform = outer_uniform && u32(row+112) != 0;
        b.orientation = orientation * f32(row+116);
        b.rgba = u32(row+104);
        b.glow = glow;
    }
    std::vector<Quad> opaque, transparent;
    size_t cursor = 20;
    for (uint32_t i = 0; i < cubes; ++i) {
        if (gs-cursor < 16) return 1;
        const uint8_t* cube = g+cursor;
        uint32_t index = u32(cube), partition = u32(cube+4), count = u32(cube+8), capacity = u32(cube+12);
        cursor += 16;
        if (index >= bone_count || partition > 3 || capacity > count || count > (gs-cursor)/128) return 1;
        bool cull = partition == 0 || partition == 3;
        if (!cull && capacity != count) return 1;
        const uint8_t* quads = g+cursor;
        cursor += size_t(count)*128;
        const auto& b = bones[index];
        if (!b.active) continue;
        auto& out = partition < 2 ? opaque : transparent;
        if ((opaque.size()+transparent.size()+capacity)*4 > vertices) return 1;
        uint32_t written = 0;
        for (uint32_t q = 0; q < count && written < capacity; ++q) {
            const uint8_t* data = quads+size_t(q)*128;
            if (!finite(floats<32>(data))) return 1;
            V3 n = floats<3>(data+80);
            float face = (n[0]*b.facing[0]+n[1]*b.facing[1]+n[2]*b.facing[2]
                            + f32(data+120)*b.facing[3])*f32(data+124);
            bool back = face <= 0;
            if (cull && back) continue;
            n = transform(b.normal,n);
            if (!b.uniform) n = normalize(n);
            if (back) for (auto& x:n) x = -x;
            uint32_t tangent = 0;
            if (pbr && !shadow) {
                V3 t = floats<3>(data+92);
                t = b.uniform ? transform(b.normal,t) : normalize(transform(b.pose,t,false));
                float w = f32(data+104);
                tangent = pack(t, w == 0 ? 1.f : w*b.orientation*(back ? -1.f : 1.f));
            }
            float mid_u = 0, mid_v = 0;
            for (int v = 0; v < 4; ++v) {
                mid_u += f32(data+48+v*8)*.25f;
                mid_v += f32(data+52+v*8)*.25f;
            }
            Quad quad;
            for (int v = 0; v < 4; ++v) {
                V3 point = transform(b.pose,floats<3>(data+v*12),true);
                if (!finite(point)) return 1;
                auto* dst = quad.vertices[v].data();
                for (int k = 0; k < 3; ++k) putf(dst+k*4,point[k]);
                put32(dst+12,color(u32(f+228),b.rgba,partition>=2));
                putf(dst+16,f32(data+48+v*8)); putf(dst+20,f32(data+52+v*8));
                put32(dst+24,u32(f+236));
                put32(dst+28,b.glow == 255 ? u32(f+232) : (b.glow<<4)|(b.glow<<20));
                put32(dst+32,pack(n));
                if (!vanilla) {
                    std::memcpy(dst+36,f+240,6);
                    putf(dst+mid,mid_u); putf(dst+mid+4,mid_v); put32(dst+mid+8,tangent);
                }
            }
            auto center = floats<3>(data+108);
            float z = b.clip[2]*center[0]+(b.clip[6]*center[1]+(b.clip[10]*center[2]+b.clip[14]));
            float w = b.clip[3]*center[0]+(b.clip[7]*center[1]+(b.clip[11]*center[2]+b.clip[15]));
            float depth = z/w;
            quad.depth = std::isfinite(depth) ? depth : -std::numeric_limits<float>::infinity();
            out.push_back(quad);
            ++written;
        }
        while (written++ < capacity) out.emplace_back();
    }
    if (cursor != gs || (opaque.size()+transparent.size())*4 != vertices) return 1;
    if (!shadow) std::stable_sort(transparent.begin(),transparent.end(),[](const Quad& a, const Quad& b) {
        if (a.depth == 0 && b.depth == 0) return std::signbit(b.depth) && !std::signbit(a.depth);
        return a.depth > b.depth;
    });
    // All validation and allocations completed. Only now publish the complete packed draw.
    size_t offset = 0;
    for (const auto* batch : {&opaque,&transparent}) for (const auto& quad:*batch)
        for (const auto& v:quad.vertices) {
            std::memcpy(output+offset,v.data(),stride);
            offset += stride;
        }
    return 0;
}
} // namespace ysmlib

extern "C" int32_t ysmlib_render_v1(const uint8_t* geometry, size_t geometry_size,
                                    const uint8_t* frame, size_t frame_size,
                                    int32_t stride, int32_t mid_offset,
                                    uint8_t* output, size_t output_size) {
    try {
        return ysmlib::render(geometry,geometry_size,frame,frame_size,stride,mid_offset,output,output_size);
    } catch (const std::bad_alloc&) { return 2; }
      catch (...) { return 1; }
}
