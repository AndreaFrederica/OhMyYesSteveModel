# 格式、Schema 与编解码已知问题

本页记录当前实现与[格式一致性要求](../../standards/conformance.md)之间的重要缺口，不修改标准本身。各条按「现象 → 影响 → 目前处理」组织。

## 一致性与转换

- **现象**：Asset Container 与 Model Schema（当前均为 `0.1.0-unstable`）均缺跨实现 portable binary golden。当前 Java generic container 与 model profile 都使用该精确版本。**影响**：同一实现自产自读不能证明标准版本互操作。**目前处理**：格式尚未冻结，待 golden 就绪后再评估互操作声明。
- **现象**：Canonicalizer profile 已覆盖冻结 capture、无符号 UTF-8 排序和 little-endian records。**影响**：仍缺跨 source adapter 的公开 golden。**目前处理**：以现有 capture 测试覆盖。
- **现象**：Verification payload 的 hash/signLength 结构和精确 preamble 已经接好。opaque `sign` 不提供作者、授权或抗篡改保证。固定字符串 ASCII、图像 payload 长度及 metadata 一致性仍有严格校验偏差。**影响**：完整性校验可用，真实性保障不足；个别校验点可能放过不应接受的输入。**目前处理**：按现状使用，偏差点逐步收严。
- **现象**：raw 几何转换会误写 visible bounds offset。**影响**：转换后的模型范围信息可能错误。**目前处理**：已知缺陷，修复转换器前不应采信该范围数据。
- **现象**：Model Schema 允许 unspecified 且无 thumbnail。Local Catalog 另按实际来源拒绝缺有效内嵌 preview 的 direct 成品；内部 raw/builtin/legacy 转换结果保留 schema 合法缺图。**影响**：该来源矩阵已有 Java 局部测试。**目前处理**：尚缺完整产品入口与跨平台媒体组合验收。

## Proto presence

- **现象**：全部 source Proto 已完成正向证据审计。当前 Model Schema import closure、统一网络协议和 baked manifest 均由对应快照或 fixture 覆盖。证据来自 legacy V3 格式规范、legacy native serializer、source adapter 的明确省略分支和协议 absence 契约。**影响**：这些证据只能推出「可缺省」。C++ 普通成员和嵌套聚合初始化会把缺省输入物化为默认对象，因此未见 nullable 不能推出 non-null。当前 strict profile 仍要求其余 ordinary singular 在 `build()` / `parseFrom(...)` 时存在，并禁止 partial value 进入业务路径。**目前处理**：按正向证据执行，不做反向推断。
- **现象**：`Info.metadata` 与 `Author.avatar` 继续以显式 `optional` 表达历史合法缺省。`Info.properties` 与 `Info.settings` 是 current schema 的 ordinary required message。**影响**：raw 与 legacy producer 都必须物化它们；immutable builder/parse 在 publication 前拒绝缺失。Preview 与 icon source 独立以显式 `optional` 表达命名图像 absence。**目前处理**：producer 按此规则实现。
- **现象**：历史 `Animation.start_delay`、`loop_delay` 与 `override_previous_animation` 是显式 nullable，当前 raw parser 也能读取。**影响**：业务层没有 consumer；当前 Model Schema 不承载它们，转换器按裁决静默丢弃且不告警。这不改变当前可观察语义。**目前处理**：未来若支持，必须以新 schema version 和新字段显式引入，不能重新解释既有 current container。
- **现象**：其他显式 absence 已由现有表示或验证规则承载。**影响**：`BoneKeyFrame.post` 用 empty repeated 表示等于 `pre`；PBR extension、blend transition linear value、export/preview/GUI resource 与 PlayerState DELTA 使用已标记的 `optional`；replacement asset absence 由 map/repeated membership 表达；缺失顶层 player 由当前 M2 的 player-target 不变量拒绝。**目前处理**：此处不据此反推未标字段为 non-null。
- **现象**：Model Schema import closure 当前有 8 个 repeated numeric/bool primitive，均位于 geometry Proto 且显式声明 `[packed = true]`。当前没有 repeated enum。**影响**：repeated string/message 与 map 不属于该门禁。Snapshot checker 已把该显式标记作为 source/snapshot 一致性之外的独立门禁。**目前处理**：维持现状。

## Codec、导入与平台

- **现象**：生产调用、构建与内置资源生成已迁至独立 `ysm_runtime` 前置，不再依赖官方 native。V1/V2、ZIP、7z、VFS/capture 使用 Java 基线；Windows 已交付自建 C codec 与 C++ packed-buffer renderer 的可选加速。Windows Forge dedicated server 与双客户端已在禁用全部可选 native 加速时完成加载、模型显示、同步和声音验收。**影响**：上游 C++ 的图像、音频、归档和 legacy 加速尚未全部接入自建 provider；当前 native 包只覆盖 codec 与整次 draw renderer。**目前处理**：按[迁移表](../../architecture/native-runtime/portable-runtime.md#验证状态与边界)逐能力补齐，并先以 Java 基线保证功能。
- **现象**：`ZTX` 是未冻结的私有图像格式。**影响**：PNG、JPEG、WebP、AVIF 与各运行平台的 encode / decode 组合尚未系统验证。**目前处理**：解码成功不能视为像素完全一致。
- **现象**：legacy v1/v2 已接到 archive→raw→current 路径。raw/current 音频写出与重开有独立 fixture 自动化。真实语料中的 12 个文件已完成格式分类。**影响**：缺可信历史声音包装的 Java 端到端验证。**目前处理**：继续补真实语料验收。
- **现象**：v3 加密容器已接通[同步流式单向导入](../../architecture/asset-pipeline/conversion-and-export.md#历史输入的单向投影)。native 真实语料覆盖 185 个容器、inner version 1/4/9/15 和 778 个 sound field。Java current staging/reopen 与音频 projector 自动化已通过。**影响**：仍缺可信历史 v3 包装到 Java、远端、实际游戏和跨平台产品入口的端到端验收。**目前处理**：按支持状态清单逐项补齐。
- **现象**：上游参考 native 代码在 MSVC 19.51 的 fresh Release source build 会在既有 GNU-style `asm volatile` 处失败。**影响**：它不再是本分支的构建或运行依赖。自建可选 codec 加速库已另行构建与测试。**目前处理**：其余平台仍须分别验证。
- **现象**：Codec 与 archive 验证尚未覆盖全部产品平台。**影响**：存在 native 构建目标或库文件不等于产品入口已经接通或验收。**目前处理**：按平台逐项验证。

模型音频 profile 已启用。普通 raw producer 与 legacy projection 都能写出 `Common.sounds` / `stream-N`。Java staging/reopen 会复核 descriptor、logical bytes 与媒体解释。客户端按需取得并播放。当前自动化使用独立 Ogg Vorbis/Opus fixture 覆盖 raw/current、legacy projector、JNI 解码和 runtime 局部边界。真实 legacy v1/v2/v3 包装、remote session、Minecraft/OpenAL 设备与跨平台组合仍缺端到端验收。完整边界见[当前支持状态](../support-and-verification.md)。
