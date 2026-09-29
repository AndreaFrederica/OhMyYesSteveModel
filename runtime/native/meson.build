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
strict_fp = cpp.get_argument_syntax() == 'msvc' ? ['/fp:strict'] : ['-ffp-contract=off']
render_core = static_library('ysmlib_render_core', 'src/render.cpp', cpp_args: strict_fp,
                            gnu_symbol_visibility: 'hidden', pic: true)
shared_library('ysmlib_render', 'src/render_jni.cpp', link_with: render_core,
               include_directories: jni_includes, gnu_symbol_visibility: 'hidden', install: true)
