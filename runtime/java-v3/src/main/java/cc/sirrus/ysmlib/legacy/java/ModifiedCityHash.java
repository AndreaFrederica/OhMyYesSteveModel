/* Copyright (c) 2011 Google, Inc.
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package cc.sirrus.ysmlib.legacy.java;

/** Historical YSM constants and reversed two-word reduction are wire semantics. */
final class ModifiedCityHash {
  private static final long K0 = 0xE4986A230E5AAA17L,
      K1 = 0x91AF10802CAB25A5L,
      K2 = 0xAF29CE778879D9C7L,
      MUL = Long.parseUnsignedLong("16001129912037256081");

  static long seeded(byte[] bytes, int offset, int length, long seed) {
    return h16(hash(bytes, offset, length) - K2, seed);
  }

  private static long r(long x, int n) {
    return Long.rotateRight(x, n);
  }

  private static long mix(long x) {
    return x ^ (x >>> 47);
  }

  private static long h16(long high, long low) {
    return h16(low, high, MUL);
  }

  private static long h16(long u, long v, long mul) {
    long a = (u ^ v) * mul;
    a ^= a >>> 47;
    long b = (v ^ a) * mul;
    b ^= b >>> 47;
    return b * mul;
  }

  static long le64(byte[] b, int o) {
    long v = 0;
    for (int i = 7; i >= 0; i--) v = (v << 8) | (b[o + i] & 255L);
    return v;
  }

  static int le32(byte[] b, int o) {
    return (b[o] & 255) | ((b[o + 1] & 255) << 8) | ((b[o + 2] & 255) << 16) | (b[o + 3] << 24);
  }

  private static long[] weak(byte[] d, int o, long a, long b) {
    long w = le64(d, o), x = le64(d, o + 8), y = le64(d, o + 16), z = le64(d, o + 24);
    a += w;
    b = r(b + a + z, 21);
    long c = a;
    a += x + y;
    b += r(a, 44);
    return new long[] {a + z, b + c};
  }

  private static long hash(byte[] d, int o, int n) {
    if (n <= 16) {
      if (n >= 8) {
        long m = K2 + n * 2L, a = le64(d, o) + K2, b = le64(d, o + n - 8);
        return h16(r(b, 37) * m + a, (r(a, 25) + b) * m, m);
      }
      if (n >= 4) {
        long a = Integer.toUnsignedLong(le32(d, o));
        return h16(n + (a << 3), Integer.toUnsignedLong(le32(d, o + n - 4)), K2 + n * 2L);
      }
      if (n > 0) {
        int a = d[o] & 255, b = d[o + (n >>> 1)] & 255, c = d[o + n - 1] & 255;
        return mix((a + (b << 8)) * K2 ^ (n + (c << 2)) * K0) * K2;
      }
      return K2;
    }
    if (n <= 32) {
      long m = K2 + n * 2L,
          a = le64(d, o) * K1,
          b = le64(d, o + 8),
          c = le64(d, o + n - 8) * m,
          e = le64(d, o + n - 16) * K2;
      return h16(r(a + b, 43) + r(c, 30) + e, a + r(b + K2, 18) + c, m);
    }
    if (n <= 64) {
      long m = K2 + n * 2L,
          a = le64(d, o) * K2,
          b = le64(d, o + 8),
          c = le64(d, o + n - 24),
          e = le64(d, o + n - 32),
          f = le64(d, o + 16) * K2,
          g = le64(d, o + 24) * 9,
          h = le64(d, o + n - 8),
          i = le64(d, o + n - 16) * m;
      long u = r(a + h, 43) + (r(b, 30) + c) * 9,
          v = ((a + h) ^ e) + g + 1,
          w = Long.reverseBytes((u + v) * m) + i,
          x = r(f + g, 42) + c,
          y = (Long.reverseBytes((v + w) * m) + h) * m,
          z = f + g + c;
      a = Long.reverseBytes((x + z) * m + y) + b;
      b = mix((z + a) * m + e + i) * m;
      return b + x;
    }
    int tail = o + n - 64;
    long x = le64(d, tail + 24),
        y = le64(d, tail + 48) + le64(d, tail + 8),
        z = h16(le64(d, tail + 16) + n, le64(d, tail + 40));
    long[] v = weak(d, tail, n, z), w = weak(d, tail + 32, y + K1, x);
    x = x * K1 + le64(d, o);
    for (int end = o + ((n - 1) & ~63); o < end; o += 64) {
      x = r(x + y + v[0] + le64(d, o + 8), 37) * K1;
      y = r(y + v[1] + le64(d, o + 48), 42) * K1;
      x ^= w[1];
      y += v[0] + le64(d, o + 40);
      z = r(z + w[0], 33) * K1;
      v = weak(d, o, v[1] * K1, x + w[0]);
      w = weak(d, o + 32, z + w[1], y + le64(d, o + 16));
      long t = z;
      z = x;
      x = t;
    }
    return h16(h16(v[0], w[0]) + mix(y) * K1 + z, h16(v[1], w[1]) + x);
  }
}
