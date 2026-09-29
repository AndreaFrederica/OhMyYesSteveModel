package cc.sirrus.ysmlib.legacy.java;

/** Format-specific XChaCha with dynamic chunk parameters; not a general cryptography API. */
final class HistoricalChaCha {
  private static final long SEED = Long.parseUnsignedLong("11973692811708214211");
  private int[] state;
  private int rounds, blocks;

  HistoricalChaCha(byte[] password) {
    parameters(ModifiedCityHash.seeded(password, 0, password.length, SEED));
    int[] h = constants();
    for (int i = 0; i < 8; i++) h[i + 4] = ModifiedCityHash.le32(password, i * 4);
    for (int i = 0; i < 4; i++) h[i + 12] = ModifiedCityHash.le32(password, 32 + i * 4);
    h = rounds(h, rounds);
    state = constants();
    System.arraycopy(h, 0, state, 4, 4);
    System.arraycopy(h, 12, state, 8, 4);
    state[14] = ModifiedCityHash.le32(password, 48);
    state[15] = ModifiedCityHash.le32(password, 52);
  }

  int chunkSize() {
    return blocks * 64;
  }

  void decrypt(byte[] data, int offset, int length, boolean complete) {
    for (int start = 0; start < length; start += 64) {
      int[] working = rounds(state, rounds);
      for (int i = 0; i < 16; i++) working[i] += state[i];
      int count = Math.min(64, length - start);
      for (int i = 0; i < count; i++)
        data[offset + start + i] ^= (byte) (working[i / 4] >>> ((i % 4) * 8));
      if (++state[12] == 0) state[13]++;
    }
    if (complete) {
      long hash = ModifiedCityHash.seeded(data, offset, length, SEED);
      parameters(hash);
      for (int i = 0; i < 12; i++) state[i + 4] ^= (int) (hash >>> ((i % 2) * 32));
    }
  }

  private void parameters(long hash) {
    blocks = (int) Long.remainderUnsigned(hash, 64) + 64;
    rounds = ((int) Long.remainderUnsigned(hash, 3) + 1) * 10;
  }

  private static int[] constants() {
    int[] s = new int[16];
    s[0] = 0x61707865;
    s[1] = 0x3320646e;
    s[2] = 0x79622d32;
    s[3] = 0x6b206574;
    return s;
  }

  private static int[] rounds(int[] input, int rounds) {
    int[] x = input.clone();
    for (int i = 0; i < rounds; i += 2) {
      qr(x, 0, 4, 8, 12);
      qr(x, 1, 5, 9, 13);
      qr(x, 2, 6, 10, 14);
      qr(x, 3, 7, 11, 15);
      qr(x, 0, 5, 10, 15);
      qr(x, 1, 6, 11, 12);
      qr(x, 2, 7, 8, 13);
      qr(x, 3, 4, 9, 14);
    }
    return x;
  }

  private static void qr(int[] x, int a, int b, int c, int d) {
    x[a] += x[b];
    x[d] = Integer.rotateLeft(x[d] ^ x[a], 16);
    x[c] += x[d];
    x[b] = Integer.rotateLeft(x[b] ^ x[c], 12);
    x[a] += x[b];
    x[d] = Integer.rotateLeft(x[d] ^ x[a], 8);
    x[c] += x[d];
    x[b] = Integer.rotateLeft(x[b] ^ x[c], 7);
  }
}
