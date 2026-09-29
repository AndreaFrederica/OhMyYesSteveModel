# Historical wire fixtures

`generate_historical.cc` is a standalone C++20 writer extracted from
`YesSteveModel-Native/modules/test/src/legacy_historical_decoder_test.cc`.
It writes versions 1–32 plus a v5 quad and a non-finite animation length.
The decoder under test is never used to produce these bytes.

From this directory, compile with a C++20 compiler and pass a fresh output directory:

```
g++ -std=c++20 -O2 generate_historical.cc -o generate_historical
./generate_historical ../resources/historical
```

These are synthetic schema fixtures, not a substitute for real historical models.
The frozen envelope fixture separately checks the encryption and compression layer.
