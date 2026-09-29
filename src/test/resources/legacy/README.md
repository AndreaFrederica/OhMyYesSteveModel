# Historical V3 dynamic envelope golden

`legacy_v3_dynamic_vector.ysm` is the upstream YesSteveModel-Native historical
dynamic ChaCha fixture, generated with the historical implementation rather
than the decoder under test. It crosses a dynamic parameter boundary.

- Envelope: 12,095 bytes, SHA-256
  `548e5610a57e561f84878882f14dd3557aac1c73c073accc5ee970312d8ac2f3`.
- Plaintext: 12,004 bytes; LE32 version 1, then low bytes of successive
  `std::mt19937_64(0xD15EA5E)` outputs.
- Plaintext SHA-256:
  `3e41fd30736a8b89eb043e8eeefc7cc328491580e88a395ab2c4062df5ad0ea6`.

This is an envelope vector, not a semantically valid historical model.
