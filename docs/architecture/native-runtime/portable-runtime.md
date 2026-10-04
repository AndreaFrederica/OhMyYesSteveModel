# 独立前置与迁移

本分支依据[独立运行库前置](../../product-decisions/decisions/platform-baselines.md#ddportable-runtime-prerequisite)将替代能力放入独立前置 **ysm_runtime**。JVM 托管实现是每个能力的必需基线，native 是同一能力接口下的可选加速。归档、BLAKE3、zstd、图像、音频、V3 导入与 CPU 渲染均已接入 JVM 实现。

测试宿主使用本体 fork，直接修改调用点和构建工具。兼容未经修改的官方 Mod 不属于当前接入目标。

基线优先使用 Java/Kotlin；仅在没有合适的 JVM 语言实现时，允许由纯 JVM 引擎执行 WASM 作为兜底。WASM 不承担 native 加速角色。前置命名空间为 `cc.sirrus.ysmlib`。

## 当前结构

`runtime/` 是独立 Gradle build，本体通过 composite build 使用它。

| 模块 | 职责 |
|---|---|
| `api` | 仅依赖 JDK：归档、codec、图像、音频、历史导入 / decoded workspace 接口 |
| `java-archive` | V1/V2、ZIP、7z 的 Java 实现 |
| `java-codec` | 纯 Java BLAKE3 / zstd |
| `native-codec` | 可选自建 C BLAKE3/zstd，使用 Meson + Pixi |
| `java-image` + `wasm-avif` | PNG/JPEG/WebP/ZTX；AVIF 由 Chicory 在 JVM 内执行 libavif/libaom WASM |
| `java-audio` + `concentus` | 固定点 Java Opus 与 Java Vorbis，流式 mono PCM16 |
| `java-v3` | V3 envelope、历史 wire reader、archival workspace、current schema 投影 |
| `v3d` | V3D 目录物化、manifest、完整性、原子发布与原样恢复 |
| `tools` | 独立 CLI，无 Minecraft / 渲染依赖 |
| `render-api` / `java-bake` / `java-render` | 烘焙、帧状态、Java CPU 顶点基线 |
| `native-render` | 可选 C++ 整次 draw packed-buffer 加速；Java 状态仍为基线 |
| `scene-api` / `wasm-physics` / `native-physics` | 独立场景/物理契约、Bullet 托管基线与可选 JNI 加速；MMD 调度、模型后端接入状态见[双后端状态](../../status/general-mesh-backend.md) |
| `core` | 普通 JVM 服务入口 `YsmRuntime` |
| `forge` | `@Mod("ysm_runtime")` 与完整分发 JAR |
| `portable-integration-tests` | 不依赖 Forge / 官方 native，验证本体真实适配代码 |

要点：

- 本体声明必需前置依赖；前置不依赖本体；算法模块不依赖 Forge / Minecraft。
- 第三方 Java 库只随前置打包并 relocate，本体不重复 shade。
- WASM 不加载 JNI、不启动外部进程、不依赖系统解码库。
- `RawModelImporter` 的文件来源经 `ArchiveFileSystem` 进入归档服务。
- V3 provider 返回只读、拥有副本的 payload；宿主负责 `.mxc` 写入、重开校验与发布。

V1/V2 返回原始文件；V3 是 compiled wire，不与 raw archive 共用同一 archive API。[V3D](../asset-pipeline/v3d.md) 在导入边缘提供已解压 wire 缓存，首次 capture 使用 Java envelope decoder；成品或 wire 命中时跳过 envelope decode。运行语义仍由当前 `.mxc` 决定。

## 前置可见信息

显示名 **Oh my ysm lib**，作者 AndreaFrederica。进入世界后按 **F3**，右侧显示加载器提供的名称、版本，以及 core 提供的只读能力诊断；作者信息只出现在模组元数据中。

诊断不执行解码、文件扫描或 native 自检。它表示当前选择的 provider，不表示该格式已经被使用，也不表示 Minecraft 的图形 / 音频没有 native。AVIF 单独标注为 `Chicory (JVM/WASM)`。

## 验证状态与边界

| 能力 | 当前状态 | 验证与范围 |
|---|---|---|
| V1/V2、ZIP、7z、文件捕获 | Java 基线已接入 | 合成 archive 通过完整 raw 导入，ModelId 与目录输入一致；真实历史 V1/V2 语料尚未提供 |
| BLAKE3 | Java provider | 官方长度向量全部通过；27 个内置模型与 6 个真实 V3 的 ModelId / 容器重开通过 |
| zstd 与容器 chunk | Java provider | libzstd 双向样本通过；完整容器语料与异常组合仍待补充 |
| PNG/JPEG/WebP/AVIF/ZTX | 图像适配已接前置 | 内置图像均可在 JVM 解码；解码成功不等于像素完全一致 |
| Ogg Opus/Vorbis | probe 与两种 decoder 已在前置 | 帧数 / 裁剪 / PCM 差分通过；真实 SoundEngine 完整读取并关闭 stream |
| V3 envelope / wire / 投影 | `java-v3` 已接入生产 | 动态 envelope golden、32 版 C++ writer 样本、6 个真实模型通过 |
| bake / 帧状态 / CPU 顶点 | `render-api` 等；本体只调用接口 | Java 基线与 C++ packed-buffer renderer；UV 多模式、六面 / 反向 cube、镜像缩放、PBR 切线、顶点布局测试通过；代表性整次 draw 已做 Java/native 字节差分；shader 组合视觉未测 |
| 启动 / 内存 | JDK direct buffer；不加载官方库 | Windows dedicated server 与双客户端启动、重连、正常退出通过；长时间运行与其他平台未测 |

每次迁移以能力接口为单位，不创建统一的万能 JNI 门面。native 实现不得成为模型 identity、catalog、资源生命周期或网络会话的权威。需要状态的能力由会话 owner 持有。加速失败只影响对应能力。

默认优先使用可用的自建 native；缺失或 ABI / 自检 / 链接失败时，各能力独立回退 JVM。启动时自动查找工作目录下 `ysmlib/natives/<平台>-<架构>/` 的 `ysmlib_codec`、`ysmlib_render` 和 `ysmlib_physics`，例如 `windows-x64/ysmlib_codec.dll`。可用 `-Dysm.runtime.nativeDir=<目录>` 更改根目录，或用 `ysm.runtime.codecLibrary` / `ysm.runtime.renderLibrary` / `ysm.runtime.physicsLibrary` 指定单库文件。`-Dysm.runtime.javaOnly=true` 跳过全部 native 发现和加载（仍允许 JVM 内 AVIF / Bullet WASM）。F3 显示实际选择的 provider；Hash、Compression、packed Render 与 Physics 已有自建 native，State 仍是 Java；Physics 同时显示缓存的启动回退原因。

## 可选 native 加速路线

当前 `native/` 交付自建 C BLAKE3/zstd、C++ packed-buffer renderer 与 Bullet 物理（Meson + Pixi），安装对应平台制品后默认参与启动。renderer 的稳定 C ABI 输入冻结几何、帧状态、矩阵、布局和输出 byte region，输出只属于本次调用；Java 状态 owner 不跨边界转移。物理选择、会话和验证范围见[通用网格运行库](../general-mesh-runtime.md)。

在 C++、Rust、Zig 三个候选中，当前优先 C++：现有渲染算法与 SIMD 生态最接近，工具链也最成熟。ABI 只暴露 `extern "C"` 的 opaque handle / flat struct，不把 STL、异常或模板类型穿过边界。Rust 适合作为内存安全第二候选；Zig 保留给小型 glue。

兼容调用仍可返回 `List<Vertex>`；Minecraft 热路径使用 `renderInto` 的整次 packed-buffer 输出，避免逐顶点 JNI 与对象分配。OpenGL 提交仍留在宿主；当前只验证代表性几何的字节一致性，尚未宣称全模型性能收益。

复用帧缓冲区时，传入 JNI 的视图容量必须等于当前帧长度，不能包含上一帧多余的骨骼记录。native 在完成所有验证前不写输出；拒绝输入、分配失败或链接失败时，前置记录原因并将本次绘制及后续绘制交给 Java 渲染器，其他能力不降级。Java 基线仍校验输入，不会将非法矩阵伪装成有效绘制。诊断中的 provider 随降级更新；成功 native draw 计数排除启动自检与 Java 回退，用于实际游戏验证。

AVIF 的 WASM 每次调用拥有独立内存和 decoder，最多 512 MiB 线性内存；输入与 RGBA 输出分别受 256 MiB 限制。与独立参考比较时 alpha 完全一致，RGB 分量最多相差 2/255；这不构成旧官方 decoder 的像素一致性证明。

音频先验证拥有副本的 Ogg 全流，再以 packet 流解码，持有的 PCM 限于单包。输出为 signed mono PCM16 little-endian，保留原采样率、pre-skip、output gain 与末页裁剪。PCM frame 数必须精确匹配探测时间轴。

## 最终验收要求

最终验收需从安装目录移除所有官方 native，并禁用前置的全部 native 加速，在真实 Forge 客户端与 dedicated server 上验证：V1/V2/V3 与当前容器导入、缓存、模型显示 / 动画 / 附件、声音、重载、多人同步、释放和错误隔离。随后逐个打开 accelerator 做同一套差分与性能验证。

「无 native 可用」指同等功能仍然完整，不仅是游戏未崩溃或回退默认玩家模型。

不能以移除 native 可用性检查代替迁移验收。内置模型的生成、完整 materialization 与校验必须保留并由替代库驱动。27 个内置模型完整 materialization 已通过，默认动画 407 项；独立测试和构建成功仍不能证明完整游戏通过。

## 渲染与历史格式模块边界

`render-api` 声明不可变 `Geometry` / `BakedModel`、`BakeProvider`、`ModelState`、`Renderer`、`RenderProvider`。JOML 1.10.5 是共享数学 API；Minecraft 自带同一 API，前置不重复打包。

`java-bake` 负责层级 / UV / 透明度 / 四分区与独立 cache codec；`java-render` 负责状态提取、locator、法线、剔除、透明排序与 Vanilla/Iris 顶点布局。本体只负责 Quickbuf 与 Minecraft `VertexConsumer` / 纹理适配。baked cache profile 为 `ysmlib-java-bake-1`，不会复用旧 native 内存布局 cache。

`java-v3` 内部按 envelope、有界 historical reader、schema projector 分开。V1/V2 raw archive 不经 V3 parser；V3 不回退为 raw archive。独立库不持有游戏 catalog。历史 native ownership / protocol 只保留在测试源码。

内置声音全量校验直接使用前置 `PcmStream`，不引用 Minecraft 客户端 `AudioStream`，因此 dedicated server 与 headless 构建工具采用同一验证路径。开发运行以 `modRuntimeOnly` 注册前置，保证 Forge loader 识别必需依赖。

## 最近一轮验收记录

`build modelManagementMockFinal` 与独立 runtime 全套任务通过。主测试 612 项（603 通过、9 条件跳过），processor 11 项通过；domain 7 项通过，supervisor 26 项通过。runtime 101 项通过；3 个 native opt-in 项另以 `nativeTest` 显式加载自建库全部通过。

Forge 场景判定通过：483 次真实 Java render JFR 事件；Opus 383998 PCM bytes、Vorbis 352798 PCM bytes 均由真实 SoundEngine 读取并关闭；服务器与两个客户端正常停止。游戏全程 `ysm.runtime.javaOnly=true`。

V3D CLI 对真实 `_Riru.ysm` 生成 439 个 decoded 文件，restore 与原始 767111 bytes 逐字节一致。六个真实 V3 均完成完整 workspace 与恢复验证。

上述证据不表示所有历史语料或第三方 shader 视觉完全等价；编辑后重新编码新 V3 仍是独立的未来工具。


## MMD 变形加速

独立前置的变形服务与物理 solver 分别选择。`YsmRuntime.deformation()` 支持 Java 基线和可选 native BDEF/SDEF/QDEF；`ysm.runtime.skinningLibrary` 指定单库，或在与 physics 相同的平台目录发现 `ysmlib_skinning`。启动加载/自检失败独立回退 Java，`javaOnly` 跳过发现，F3 展示缓存的实现与原因。蒙皮不拥有物理时间、catalog 或模型生命周期。

native 变形和 CPU/GPU 宿主基准使用 JDK 17。构建与独立分发沿用 native 模块的 Pixi/Meson 流程，`package-skinning` 打包 C ABI1、许可证、源码与 provenance；Java 适配层随 shaded 前置分发。GPU pipeline 由主 Mod 实例拥有，不能放进无 GL 的前置。机制见[通用网格架构](../general-mesh-runtime.md)，已覆盖平台与剩余验收见[支持报告](../../status/general-mesh-backend.md#验证证据与使用风险)。

JAR 更新不会自动安装独立 DLL。在 Windows x64 实例中，可将已验证分发包的 `ysmlib_physics.dll` 和 `ysmlib_skinning.dll` 放入游戏工作目录下的 `ysmlib/natives/windows-x64/`，保留对应源码、许可证与 provenance。默认发现无需修改启动参数；`-Dysm.runtime.javaOnly=true` 可禁用所有 native，未安装或启动自检失败继续使用 WASM 物理和 Java 蒙皮。已经加载的 DLL 需要重启游戏才会重新选择。GPU 变形独立于这两个 DLL；首次实际 compute 成功会记录 `MMD GPU skinning active`，初始化失败或设备不支持会报告 CPU 回退，不能只凭 JAR 包含 shader 判断实际启用。
