package cc.sirrus.ysmlib.legacy.java;

/** Exact std::mt19937_64 sequence used by historical envelopes. */
final class Mt19937 {
  private final long[] state = new long[312];
  private int index = 312;

  Mt19937(long seed) {
    state[0] = seed;
    for (int i = 1; i < 312; i++)
      state[i] = 6364136223846793005L * (state[i - 1] ^ (state[i - 1] >>> 62)) + i;
  }

  long next() {
    if (index == 312) {
      for (int i = 0; i < 312; i++) {
        long x = (state[i] & 0xffffffff80000000L) | (state[(i + 1) % 312] & 0x7fffffffL);
        state[i] = state[(i + 156) % 312] ^ (x >>> 1) ^ ((x & 1) != 0 ? 0xb5026f5aa96619e9L : 0);
      }
      index = 0;
    }
    long y = state[index++];
    y ^= (y >>> 29) & 0x5555555555555555L;
    y ^= (y << 17) & 0x71d67fffeda60000L;
    y ^= (y << 37) & 0xfff7eee000000000L;
    y ^= y >>> 43;
    return y;
  }
}
