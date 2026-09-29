# 计算边界：独立前置 ysm_runtime

本页说明算法能力的归属、调用方式与失败边界。模型业务权威、公开格式定义、GPU draw 与平台支持矩阵不在本页范围。

当前生产路径使用独立前置 **ysm_runtime**（显示名 *Oh my ysm lib*，包名 `cc.sirrus.ysmlib`）。官方 C++ native 不再是启动依赖。下文「上游 native 分层」仅用于理解历史结构与参考算法，不是当前 fork 的启动流程。

## 能力分工

Java 领域层拥有模型来源、身份、目录、预算、存储、发布、生命周期与网络会话。

ysm_runtime 提供可替换能力：

| 能力 | 职责 | 调用方仍负责 |
|---|---|---|
| 归档 | V1/V2、ZIP、7z 解包、目录枚举、文件 bytes 借出 | 来源选择、路径语义、capture、模型解析 |
| hash / 压缩 | BLAKE3、zstd | identity 输入选择、业务预算、存储提交 |
| 图像 | PNG/JPEG/WebP/AVIF/ZTX 解码，ZTX 等编码 | 用途与质量策略、buffer 归属、Minecraft 纹理创建与上传 |
| 音频 | Ogg Opus/Vorbis 结构解释、mono PCM16 | 内容取得、每播放 owner、与游戏声道交接 |
| V3 / 历史导入 | envelope、历史 wire、单向投影、decoded workspace | 文件读写、staging、重开验证、进入当前目录 |
| V3D | 目录物化、manifest、完整性、原子发布、原样恢复 | 导出时机、输出路径、权限 |
| bake / render | 静态烘焙、帧状态提取、CPU 顶点 | 动画求值、`RenderType`、`VertexConsumer`、GPU 生命周期 |

约束如下：

- 每项能力必须先有 JVM 基线；native 仅是可选加速。
- 一次解码或渲染过程中不能无状态更换 provider。
- 加速失败只影响对应能力，并自动回退 Java。
- 计算层不持有模型 catalog、资源生命周期或网络会话的权威。

模块拆分、构建、安装与第三方许可见运行库目录中的 `runtime/README.md`。迁移进度与验收边界见[独立前置与迁移](portable-runtime.md)。

## 加载与一次性绑定

本体声明必需前置依赖；前置不依赖本体。算法模块不依赖 Forge / Minecraft。

本体中的 `natives.*` 为过渡适配层，将 `YsmRuntime` 的能力接口转换为业务代码已有的入口名。该层不再执行 `System.load` 加载官方库。

Buffer 与借用规则见[计算边界与内存](jni-and-memory.md)。

## 失败如何返回

打开能力时发生 `LinkageError` 可以回退 Java。内容错误与 I/O 错误直接向上传播；损坏的 V1/V2 不得被当作其他格式再次尝试。

已经返回的归档对象不得在部分读取后透明切换 provider。未来的 native provider 必须在交付对象前完成加载、绑定与能力自检。

返回值约定仍然有效：调用成功不代表外部状态已提交。例如渲染成功后才由 Java 写入 `VertexConsumer` 或推进顶点区；模型 Ready 与纹理发布始终由 Java 资源 owner 决定。错误最终对页面、模型或会话的影响见[失败处理](../model-management/failure-and-recovery.md)。

## 上游 native 分层（参考）

官方 native 内部分层如下，仅作参考：

| 层 | 职责 |
|---|---|
| 通用 runtime 基础 | buffer、分配、CPU 能力、日志 |
| 生成 Proto 类型 | schema 值类型 |
| codec | hash、压缩、归档、图像、音频 |
| gfx bake / renderer | 烘焙与 CPU 顶点 |
| java | JNI 描述符、注册、引用、handle |
| legacy | 历史容器解码与投影 |
| lib JNI glue | 聚合为唯一可加载共享库 |

依赖方向为单向：算法不得反向依赖 glue。Java 仍是来源、身份、预算、存储、发布与生命周期的权威；上游 native 子系统之间也不能借共享库聚合关系交换这些业务状态。

## Android 启动器接入

Android 的前提条件由[平台决策](../../product-decisions/decisions/platform-baselines.md#bcandroid-launcher-runtime-prerequisite)定义。托管基线完成后，纯 Java 运行不以 native library namespace 为前提；实际平台可用性仍须单独验收。
