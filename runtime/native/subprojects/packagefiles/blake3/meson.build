project('blake3', 'c', version: '1.8.5')
blake3_library = static_library('blake3',
  'c/blake3.c', 'c/blake3_dispatch.c', 'c/blake3_portable.c',
  c_args: ['-DBLAKE3_NO_SSE2', '-DBLAKE3_NO_SSE41', '-DBLAKE3_NO_AVX2', '-DBLAKE3_NO_AVX512', '-DBLAKE3_USE_NEON=0'],
  include_directories: include_directories('c'), pic: true)
blake3_dep = declare_dependency(link_with: blake3_library, include_directories: include_directories('c'))
