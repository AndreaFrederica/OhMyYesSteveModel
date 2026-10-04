project('ysmlib-native', 'c', 'cpp', version: '0.1.0', meson_version: '>=1.9',
        default_options: ['c_std=c11', 'cpp_std=c++20', 'buildtype=release', 'default_library=static', 'b_vscrt=mt'])

jdk = get_option('jdk_home')
if jdk == ''
  error('Pass -Djdk_home with a JDK, or use pixi run build')
endif
platform_include = 'linux'
if host_machine.system() == 'windows'
  platform_include = 'win32'
elif host_machine.system() == 'darwin'
  platform_include = 'darwin'
endif
fs = import('fs')
jdk_include = fs.relative_to(jdk / 'include', meson.current_source_dir())
jni_includes = include_directories(jdk_include, jdk_include / platform_include)

blake3 = subproject('blake3').get_variable('blake3_dep')
zstd = subproject('zstd',
                  default_options: ['bin_programs=false', 'bin_tests=false', 'bin_contrib=false',
                                    'multi_thread=disabled', 'static_runtime=true',
                                    'legacy_level=0', 'zlib=disabled', 'lzma=disabled', 'lz4=disabled']).get_variable('libzstd_dep')
shared_library('ysmlib_codec', 'src/codec_jni.c', include_directories: jni_includes,
               dependencies: [blake3, zstd], install: true)
codec_test = executable('codec_conformance', 'src/codec_test.c', dependencies: [blake3, zstd])
test('codec-conformance', codec_test)

cpp = meson.get_compiler('cpp')
strict_fp = cpp.get_argument_syntax() == 'msvc' ? ['/fp:strict', '/std:c++20'] : ['-ffp-contract=off']
# Keep the first verified accelerator on the same scalar solver path as WASM.
# MSVC otherwise enables Bullet's SIMD solver implicitly in btScalar.h.
physics_args = strict_fp + ['-D__BT_DISABLE_SSE__', '-DBT_NO_PROFILE', '-DBT_THREADSAFE=0']
bullet_manifest = meson.current_build_dir() / 'bullet_sources.txt'
bullet_list = run_command('python', '-c',
  'from pathlib import Path; print(Path("' + bullet_manifest + '").read_text(), end="")', check: true).stdout().strip().split('\n')
bullet_src = files(bullet_list)
bullet_include_root = bullet_list[0].split('/src/')[0] / 'src'
bullet_includes = include_directories(fs.relative_to(bullet_include_root, meson.current_source_dir()), 'src/math')
physics_math = static_library('ysmlib_physics_math',
                             'src/math/atan2f.c', 'src/math/atanf.c', 'src/math/asinf.c',
                             dependencies: meson.get_compiler('c').find_library('m', required: host_machine.system() != 'windows'),
                             c_args: cpp.get_argument_syntax() == 'msvc' ? ['/fp:strict'] : ['-ffp-contract=off'],
                             gnu_symbol_visibility: 'hidden', pic: true)
bullet_core = static_library('ysmlib_bullet_core', bullet_src,
                             cpp_args: physics_args,
                             link_with: physics_math,
                             include_directories: bullet_includes,
                             gnu_symbol_visibility: 'hidden', pic: true)
render_core = static_library('ysmlib_render_core', 'src/render.cpp', cpp_args: strict_fp,
                            gnu_symbol_visibility: 'hidden', pic: true)
shared_library('ysmlib_render', 'src/render_jni.cpp', link_with: render_core,
               include_directories: jni_includes, gnu_symbol_visibility: 'hidden', install: true)

skinning_core = static_library('ysmlib_skinning_core', 'src/skinning.cpp', cpp_args: strict_fp,
                              dependencies: dependency('threads'), gnu_symbol_visibility: 'hidden', pic: true)
shared_library('ysmlib_skinning', 'src/skinning_jni.cpp', link_with: skinning_core,
               include_directories: jni_includes, gnu_symbol_visibility: 'hidden', install: true)
skinning_abi_test = executable('skinning_abi_conformance', 'src/skinning_abi_test.c', link_with: skinning_core, link_language: 'cpp')
test('skinning-c-abi', skinning_abi_test)

physics_core = static_library('ysmlib_physics_core', 'src/physics.cpp',
                              cpp_args: physics_args,
                              link_with: bullet_core,
                              include_directories: bullet_includes,
                              gnu_symbol_visibility: 'hidden', pic: true)
shared_library('ysmlib_physics', 'src/physics_jni.cpp', link_with: physics_core,
               include_directories: jni_includes,
               gnu_symbol_visibility: 'hidden', install: true)
physics_test = executable('physics_smoke', 'src/physics_test.cpp', link_with: physics_core)
test('physics-smoke', physics_test)
physics_abi_test = executable('physics_abi_conformance', 'src/physics_abi_test.c', link_with: physics_core, link_language: 'cpp')
test('physics-c-abi', physics_abi_test)
physics_math_test = executable('physics_math_conformance', 'src/physics_math_test.c', link_with: physics_math)
test('physics-math-oracle', physics_math_test)
