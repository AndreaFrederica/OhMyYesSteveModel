# Oh my ysm lib 前置

作者 **AndreaFrederica**。进入世界后按 **F3**，右侧显示实际加载的前置版本和各能力的当前 provider；AVIF 单独标注 `Chicory (JVM/WASM)`。Mod ID 为 `ysm_runtime`。

本项目是独立构建的 Java 17 运行库与 Forge 1.20/1.20.1 前置 Mod。本体只依赖接口和服务入口；前置负责提供实现。当前版本 `0.1.0` 是迁移中的开发制品，已接入归档、BLAKE3、zstd、图像、音频、V3 和 CPU 渲染能力；本体编译、资源生成和客户端 / 服务器启动均不再使用官方 native。

Java 包名、Maven group 与内嵌依赖命名空间统一使用 `cc.sirrus.ysmlib`。

实现原则：

- 优先直接使用 Java/Kotlin 实现。
- 没有合适实现的能力允许以纯 JVM 引擎执行 WASM 兜底；该例外不得引入宿主 native 依赖。
- 可选加速使用 C/C++/Rust/Zig。当前已交付 Meson + Pixi 的 C codec 与 C++ packed renderer。
- 渲染加速优先采用 C++ 算法实现并通过稳定 C ABI 接入；Rust 作为内存安全候选；Zig 保留给小型 ABI 工具和交叉编译 glue。
- 任何 native provider 都必须能被 Java baseline 单独替代。

## 模块

| Gradle 项目 | 职责 |
|---|---|
| `ysm-runtime-api` | JDK-only 归档、codec、图像、音频、历史导入/decoded workspace 接口与限制 |
| `ysm-runtime-java-archive` | V1/V2 AES/zlib、ZIP、7z 的 Java 实现 |
| `ysm-runtime-java-codec` | BLAKE3-256 与 zstd 的 Java 实现 |
| `ysm-runtime-java-image` | PNG/JPEG/WebP/ZTX 的 Java 实现，统一调用 AVIF 托管兜底 |
| `ysm-runtime-wasm-avif` | libavif/libaom 编译到 WASM，由 Chicory 在 JVM 内执行，无 JNI |
| `ysm-runtime-native-codec` | 可选自建 C BLAKE3/zstd adapter，不依赖官方 YSM native |
| `ysm-runtime-java-audio` | Ogg Opus/Vorbis 流式 mono PCM16 decoder，无 JNI/STB |
| `ysm-runtime-concentus` | 固定 revision 的原始 Java Opus 源码，单独构建 |
| `ysm-runtime-java-v3` | V3 envelope、历史 1–32 reader、独立 archival workspace、current schema projection |
| `ysm-runtime-v3d` | V3D 目录物化、manifest、完整性校验、原子发布与原样恢复 |
| `ysm-runtime-tools` | 独立 Java 17 CLI，直接组装 V3 providers；无 Minecraft/渲染依赖 |
| `ysm-runtime-render-api` | backend-neutral bake/state/render API 与共享 JOML 数学 API |
| `ysm-runtime-java-bake` | Java 几何烘焙、透明度分类、四分区与独立缓存 |
| `ysm-runtime-java-render` | Java 状态提取、CPU 顶点与 Vanilla/Iris 布局基线 |
| `ysm-runtime-native-render` | 可选 C++ 整次 draw packed-buffer renderer，Java state 与 renderer 皆可回退 |
| `ysm-runtime-core` | 可在普通 JVM 使用的服务入口 `YsmRuntime` |
| `ysm-runtime-forge` | Forge 加载入口与独立分发 JAR，包含 core、Java 实现和重定位依赖 |
| `portable-integration-tests` | 编译本体的真实 VFS/capture 源码，在没有 Forge/YSM native 的 JVM 验证 |

V1/V2 返回原始文件；V3 是 compiled wire，不进入同一个 archive API。运行库不持有 Minecraft 对象、模型 catalog、网络会话或 GPU 生命周期。每个新增能力必须有 JVM 托管实现和独立验收，再添加可选 native provider。当前没有注册 archive native accelerator。

默认优先使用可用的自建 native；缺失或 ABI / 自检 / 链接失败时，各能力独立回退 JVM。启动时自动查找工作目录下 `ysmlib/natives/<平台>-<架构>/` 的 `ysmlib_codec` 和 `ysmlib_render`，例如 `windows-x64/ysmlib_codec.dll`。可用 `-Dysm.runtime.nativeDir=<目录>` 更改根目录，或用 `ysm.runtime.codecLibrary` / `ysm.runtime.renderLibrary` 指定单库文件。`-Dysm.runtime.javaOnly=true` 跳过全部 native 发现和加载（仍允许 JVM 内 AVIF WASM）。F3 显示实际选择的 provider；目前只有 Hash、Compression 和 packed Render 已有自建 native，State 仍是 Java。

## 构建与安装

在主仓库根目录，设置 `JAVA_HOME` 为 JDK 17 后运行：

```powershell
.\gradlew.bat -p runtime build
```

不需要 `YSM_NATIVE_PATH`，不编译或下载 Minecraft，也不应用主工程的 native 构建门禁。Forge 子模块只编译 loader 注解。`build` 包含归档测试、VFS/capture 测试，以及使用最终 shaded JAR 与 Minecraft 共享 JOML 的 ZIP/7z/BLAKE3/zstd/AVIF/Opus/Vorbis smoke test。算法模块本身不依赖 LWJGL。

安装 `forge/build/libs/ysm-runtime-forge-0.1.0.jar` 到 `mods/`，与修改后的 YSM 本体同时使用。`-thin.jar`、`-sources.jar` 及内部模块 JAR 不是玩家安装包。客户端和服务器均声明必需依赖，版本范围为 `[0.1.0,0.2.0)`；不安装前置时由 Forge 报告缺失依赖。主仓库通过 composite build 编译依赖 core，并把完整前置加入开发运行 classpath，不会将运行库再次 shade 进 YSM。当前尚未发布 Maven 制品。

## 命令行

V3D 工具直接随前置提供，不需要安装 Minecraft 或 YSM 本体：

```powershell
java -jar ysm-runtime-forge-0.1.0.jar decode model.ysm output
java -jar ysm-runtime-forge-0.1.0.jar validate output/<generation>.v3d
java -jar ysm-runtime-forge-0.1.0.jar restore output/<generation>.v3d restored.ysm
```

也可构建轻量独立工具：

```powershell
.\gradlew.bat -p runtime :ysm-runtime-tools:shadowJar
```

产物 `tools/build/libs/ysm-runtime-tools-0.1.0.jar` 使用相同命令，不包含 Forge、图像或渲染栈。

库调用使用 `YsmRuntime.v3d().materialize(source, outputRoot)`；只有文件模块的应用可自行注入 envelope 与 decoded providers。`restore` 恢复原始文件，不编码修改后的 JSON。本体注册 `/ysm v3d export "..."`，写到游戏目录的 `ysm/export/v3d`。详见 [V3D 文档](../docs/architecture/asset-pipeline/v3d.md)。

## 契约与限制

- 归档对象由调用方关闭，不共享跨线程 decoder 状态；读取返回独立 `byte[]`。
- Mod VFS adapter 将它包装为借用 `UniBuffer`，下一次读取或关闭使旧借用失效；capture 在失效前复制，冻结后只暴露只读视图。目录读取使用 Java array buffer。
- 默认限制：源文件/单条目各 256 MiB，最多 65,536 条目，名称最多 65,535 字节，7z decoder 内存上限 256 MiB。调用方仍负责归档对象数量和总体并发预算。
- V1/V2 验证 magic/version、body MD5、字段边界、AES padding、zlib 完整性；ZIP/7z 验证条目大小与 CRC。拒绝路径穿越、重名及文件/目录冲突。这些格式校验不构成内容授权或真实性认证。
- provider 在打开时发生 `LinkageError` 可回退 Java；内容/I/O 错误直接传播。已经返回的归档对象不得在部分读取后透明切换 provider。未来 native provider 必须在交付对象前完成加载、绑定与能力自检。

后续能力、验收顺序与当前缺口见[独立前置与迁移](../docs/architecture/native-runtime/portable-runtime.md)。

## 第三方依赖

V3D 文件模块使用 Gson 2.10.1（Apache-2.0），在前置和工具的独立 JAR 中重定位，许可证随包存放于 `licenses/gson-2.10.1/`。

Commons Compress 1.27.1（Apache-2.0）使用 Commons IO 2.16.1、Codec 1.17.1、Lang 3.16.0（均 Apache-2.0），XZ for Java 1.10 使用 0BSD。BLAKE3 使用 Bouncy Castle 1.80 的底层 digest（MIT-style 许可，不注册 JCE provider），zstd 使用 Aircompressor 0.27（Apache-2.0）。Java 图像模块使用 TwelveMonkeys ImageIO 3.12.0 的纯 Java WebP reader（BSD-3-Clause）。AVIF 使用 Chicory 1.7.5（Apache-2.0）与 ASM 9.9.1（BSD-3-Clause），托管 WASM 内含 libavif 1.4.2、libaom 3.13.1 和 WASI libc/compiler-rt。音频使用 Concentus（BSD-style，revision 见 `concentus/README.md`）与 JOrbis 0.0.17（LGPL-2.0-or-later）。

分发 JAR 将它们重定位到 `cc.sirrus.ysmlib.internal`，各自 LICENSE/NOTICE 保留在 `licenses/<artifact-version>/`。升级依赖时同时更新这些随包文本。Forge loader 仅为 compile-only，不随包复制。

## 可选加速与 AVIF 重建

`native/` 使用 Meson + Pixi，运行 `pixi run test` 构建自己的 `ysmlib_codec` 与 `ysmlib_render` 并执行 C/C++ 测试。Windows 需要已安装 MSVC。源码版本由 wraps 固定，工具依赖由 `pixi.lock` 固定，不选择宿主机器的 zstd。从主仓库运行 Java/native 差分测试：

```powershell
.\gradlew.bat -p runtime :ysm-runtime-native-codec:nativeTest -PcodecLibrary=<自建库的绝对路径>
.\gradlew.bat -p runtime :ysm-runtime-native-render:nativeTest -PrenderLibrary=<自建库的绝对路径>
```

普通 `build` 不构建或加载 native；可选 `nativeTest` 未配置时不会冒充已通过。自建 native 及其 `native/licenses/` 一起分发，不自动打入必需前置。AVIF 的 `decoder.wasm` 是随包托管代码，维护者重建方式与固定版本见 [wasm-avif/README.md](wasm-avif/README.md)。

Windows x64 的可选包 `ysmlib-native-windows-x64.zip` 同时提供 `ysmlib_codec.dll` 和
`ysmlib_render.dll`。将 DLL 放入游戏目录的 `ysmlib/natives/windows-x64/` 后自动启用；也可分别设置 `ysm.runtime.codecLibrary` 与 `ysm.runtime.renderLibrary` 的绝对路径。未安装或加载失败的能力使用 Java。
F3 的 Hash、Compression、State 和 Render 行会显示实际 provider。

## 当前主工程验证

主工程音频测试使用 `src/test/resources/audio-contract` 的冻结 FFmpeg 样本，覆盖短 PCM 缓存阈值、精确阈值、长流循环、非零 Opus granule origin、输入采样率提示。JVM 固定点 Opus 与 FFmpeg 浮点参考以峰值 8 LSB、SNR 至少 70 dB 验证；Vorbis 峰值 2 LSB。PCM frame 数、mono 格式、冷热缓存重放仍严格匹配，不以数值容差放宽时间轴或内容完整性。重新生成样本的工具是 `src/test/tools/generate_audio_contract.py`，普通构建不运行 FFmpeg。
