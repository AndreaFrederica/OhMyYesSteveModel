# 迁移概览

## 背景

旧版 YSM 的架构是在闭源、模型保护与缩小攻击面等约束下逐步形成的。为将模型格式、缓存、同步与加密逻辑隐藏在 native 层，Java 与 C++ 深度绑定：模型解析、缓存、同步、导出、烘焙、解码与渲染等大量职责最终落在 C++ 侧。

该系统能够完成从模型读取到多人同步与渲染的完整流程，但代价持续上升：

- 修改功能往往需要同时理解两侧实现，中间还隔着业务级 JNI。
- 业务语义位于 C++ 内，调试、跨版本适配与自动化测试都较困难。
- 缺少系统文档，C++ 门槛又高，维护长期集中在少数人员身上。
- 保密要求进一步限制了代码交流，新人难以循序参与。
- 对熟悉内部实现的维护者而言，继续扩展同样困难，部分工作长期停滞。
- 这套复杂设计主要服务于旧版保护体系；项目开源后不再产生对等价值。

旧架构是特定约束下的合理解法。但项目开源后继续沿用，等于将已经失效的安全成本转化为长期技术债。因此新版工作不是修补旧系统，而是重新划定职责边界，使主要业务能够被普通 Java 模组开发者理解、测试和扩展。

## 迁移方向

| 旧路线 | 新路线 |
|---|---|
| C++ 拥有模型业务与同步状态 | Java 拥有格式语义、目录、缓存策略、同步、导出、动画与生命周期 |
| 缓存 ID、模型密钥、会话密钥与加密缓存共同定义身份 | `ModelId` 定义模型身份；`ContainerId` 定义网络、存储与私有烘焙缓存中的精确表示 |
| JNI 传递业务对象并跨语言驱动生命周期 | 传递 Protobuf、布局明确的 buffer 与 typed handle，按 owner / view 契约交接 |
| 以整包模型缓存为同步单元 | metadata 优先；资产按用途请求、校验、缓存与回收 |

能力层仅保留可替换计算能力：压缩、hash、图像、归档、bake / extract / render。它不拥有来源、权限、会话或缓存策略。

兼容可确定解释的旧模型属于项目级目标，但旧格式兼容限制在单向输入边缘，不会将旧加密、密钥或 native 模型管理带回新主线。Raw source 写出 Asset Container 是一次导出物的语义冻结点；迁移保护其已定义的可观察语义，不承诺保留无运行效果的历史表示。完整边界见[模型兼容与容器语义冻结](concepts/model-compatibility.md)。

## 当前进度

独立前置 **ysm_runtime**（显示名 *Oh my ysm lib*，包名 `cc.sirrus.ysmlib`）已接入归档、BLAKE3、zstd、图像、音频、V3 导入与 CPU 渲染。本体编译、内置资源生成、客户端与服务器启动均不再依赖官方 native。

已形成的主线：

- Asset Container、Model Schema 与当前 protocol 的标准和实现入口。
- raw capture、builtin / custom / auth 本地目录、完整候选 reload、converted / remote 存储、共享资源租约与 Cleaner 所有权，均已在 Java 接线。
- Forge 游戏服模式已切换到唯一 `EventNetworkChannel` 路径：model session、远端目录、逐请求授权、按需资产分发、玩家 / 实体状态与控制消息。
- 从 Java 动画到 bake / extract / render 的 CPU 渲染主路径。
- v3 加密容器到当前容器的[单向导入](architecture/asset-pipeline/conversion-and-export.md#历史输入的单向投影)。

仍待完成：

- 模型音频仍需真实 legacy v1/v2/v3 来源、远端会话、真实 Minecraft/OpenAL 设备、stream pool 容量与长期回收验收。
- 将 x64 ISA 基线降至 x86-64-v1 并适配 Windows 7，属于平台迁移范围。
- 第一人称、附着 layer、透明渲染与模组联动等旧能力的完整恢复。
- 格式与协议 golden、真实双端会话、在线 delta、授权矩阵、远端资产、断线竞态、恶意输入、跨平台与 Minecraft 视觉验收。
- 独立 Backend、通用外部模型源与 GPU Compute Renderer 等后续方向。

「已接线」只表示主要逻辑存在，不代表稳定、完整或已经通过实机验证。权威边界见[当前支持状态](status/support-and-verification.md)。

## 已知问题

- [格式、Schema 与编解码](status/known-issues/format-and-schema.md)
- [网络与同步](status/known-issues/network.md)
- [模型管理](status/known-issues/model-management.md)
- [动画](status/known-issues/animation.md)
- [渲染](status/known-issues/rendering.md)

## 分工建议

模型管理横跨目录、缓存、网络、所有权与失败恢复，代码中还包含大量 AI 辅助生成的部分。由人工长期记忆全部局部细节的成本过高。

建议分工如下：人工负责目标、边界、顶层决策、评审与质量门禁；AI 负责细化设计、实现、测试与文档。

但 AI 参与不构成正确性证明，该项建议也不能成为接受低质量代码的理由。
