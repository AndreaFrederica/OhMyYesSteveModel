# 注意

## 内置模型许可

内置模型资产不属于 Apache License 2.0。

`assets/ysm/builtin/**/ysm.json` 中的 `metadata.license` 是每个内置模型资产的权威许可证声明，`metadata.authors` 保留其作者与归属信息。

## 项目图标许可

`logo/main.png` 与 `logo/Lib.png` 及安装包中的对应图标不属于 Apache License 2.0，单独适用 [LICENSE-ARTWORK.md](LICENSE-ARTWORK.md)，保留版权并授予有限展示与随项目分发许可。项目名称可自由使用；名称的使用不授予图标权利。其他资产与第三方作品继续适用各自的许可声明。

## 第三方许可证与来源声明

本仓库包含通过源码拷贝、修改或构建期 shading 纳入的第三方作品。第三方作品保留其原始许可证和版权声明，不因位于本仓库内而改为 Apache License 2.0。

### 直接拷贝或修改的 Java 源码

| 仓库内范围 | 上游项目 | 许可证 | 快照说明 | 随包许可证 |
|---|---|---|---|---|
| `com/elfmcys/ysm/lib/concentus` | [Concentus](https://github.com/lostromb/concentus) | BSD-style Opus/Concentus terms | 无法从当前源码证明确切 revision | `licenses/concentus` |
| `com/elfmcys/ysm/lib/gagravarr` | [VorbisJava / Gagravarr](https://github.com/Gagravarr/VorbisJava) | Apache-2.0 | 无法从当前源码证明确切 revision | `licenses/vorbis-java-core` |
| `com/elfmcys/ysm/geckolib3` | [GeckoLib](https://github.com/bernie-g/geckolib) | MIT | 包含本项目修改；无法从当前源码证明确切 revision | `licenses/geckolib` |
| `com/elfmcys/ysm/mclib` | [McLib](https://github.com/mchorse/mclib) | MIT | 无法从当前源码证明确切 revision | `licenses/mclib` |
| `com/elfmcys/ysm/molang` | [Mocha](https://github.com/unnamed/mocha) | MIT | 包含本项目修改；无法从当前源码证明确切 revision | `licenses/molang` |

### 随包依赖

本分支的独立 `ysm_runtime` 前置另行打包 Apache Commons Compress/IO/Codec/Lang、XZ for Java、Bouncy Castle、Aircompressor、TwelveMonkeys、Chicory/ASM、Concentus 与 JOrbis。AVIF WASM 制品内含 libavif/libaom/WASI runtime；版本、来源与许可见 [runtime/README.md](runtime/README.md)。重定位类、WASM 及独立许可证随前置 JAR 提供，不随本体重复打包。JOrbis 的对应源码随包提供；自建可选 native 的 BLAKE3/zstd 许可保留在 `runtime/native/licenses/`。

构建会将 QuickBuffers runtime relocate 后打入最终制品，其 Apache-2.0 文本位于 `licenses/quickbuffers`。

`licenses/` 还包含随 Java 制品分发的 native 与其他第三方组件的许可证文本。

Legacy native importer 内嵌了格式兼容所需的修改版 CityHash 64-bit 实现与
ChaCha/HChaCha 参考实现。其来源和许可分别见 `licenses/cityhash` 与
`licenses/legacy-chacha`；这些声明必须与三个平台的主 native 库一同分发。

Native 项目的第三方组件、版本和许可证清单由其自身仓库维护：[THIRD_PARTY_LICENSES.md](https://github.com/YesSteveModel/YesSteveModel-Native/blob/dev/THIRD_PARTY_LICENSES.md)。

本清单是合规辅助材料，不替代各上游许可证正文。
