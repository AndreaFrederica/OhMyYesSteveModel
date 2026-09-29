# 当前支持状态

「已接线」只表示主要代码链路存在，不代表稳定、完整或通过实机、跨平台及跨实现验证。格式与协议要求以[一致性标准](../standards/conformance.md)为准。

| 能力 | 当前边界 |
|---|---|
| Asset Container / Model Schema `0.1.0-unstable` | 标准已定义 canonical `ModelId`、扩展 verification payload、preamble 与完整模型 profile；Java 主路径已接线核心结构、direct/zstd、BLAKE3、冻结 capture 和严格 preamble。generic container 与 model profile 均精确写出 / 接受 `0.1.0-unstable`，尚未完成该版本跨实现 conformance。见[格式问题](known-issues/format-and-schema.md) |
| ED25519 verification | 不支持 |
| Protocol `0.3.0-unstable` | Forge `EventNetworkChannel` 的 bounded frame、exact-connection session closure、四类 collection typed full/delta、三类 model-distribution request、七类 typed fragment、server-private forced selection、全局 dispatch 及既有玩家 / entity / control path 已接线。Player-state report/update 与收藏快照已退出 sequence/revision，报告机会采用 best-effort。Schema/registry、typed producer/consumer、业务 bounds/assembly、replacement/late outcome、per-child admission/cancel、三种 source retry/close、dispatch closure 与完整 unsigned transfer ID range 已有自动化覆盖；真实 Forge 的 no-channel/buffer handoff、同 UUID replacement、逐观察者异常、LAN/集成 server frame、真正 version mismatch、长期资源回收及 secondary-login 单 session 仍缺机器可判定 observation。见[网络问题](known-issues/network.md) |
| Java 模型管理 | 进程 Catalog 的 startup default、首次消费登记 / scan / prune、逐项验证与 owner-tick 增量发布、direct 精确读取实例、remote/converted 非破坏性坏读、exact session 查询释放、独立 runtime、完整 target 租约与 30/60 unused LRU 已接线；GUI/local/每个 remote entity 的 0.3/0.7 秒连续需求也已接入。自动化与 thin Forge host 覆盖主要局部分支；真实大目录、多进程 prune、广泛 reload、长期资源回收、成本对照和完整 LAN 交错仍需独立验收。见[模型管理问题](known-issues/model-management.md) |
| Preview 与 export | Direct 成品内嵌 preview 准入、内部转换缺图例外、`ContainerId` 独立图片 cache、特化 presentation、真实 target/bake→256×256 offscreen draw/readback→worker encode，以及 `/ysm export` 的直接输入与 `.mxc` 完整容器重封装已接线。Client tick 的单一总 admission 覆盖 probe、Ready/Flight 或 detached load、host pixels、encode/persist/export 和终态；专服已有图片 export 由 server tick 接纳完整 worker。Owner-pause、共享容量、terminal release、host/encode 失败关闭、导出 route/后缀和当前容器再发现已有自动化覆盖；真实 Forge 画面、游戏内命令、完整失败矩阵、长期图片空间与冷暖成本仍未完成最终验收 |
| Java 动画运行时 | animation、coded / Bedrock / hybrid controller、玩家状态、骨骼输出与模型声音触发主链已接线。模型 execution-bearing 字段携带 Molang source 字符串，由 `molang` 的 lexer/parser/`ExpressionEvaluator` 与 `client.animation.molang` 的 binding 本地逐次解析求值。Roaming 由 `LocalRoamingStruct` / `RemoteRoamingStruct` 记录并消费 full 与 delta，`AnimationProcessor.putRemoteStruct` 将它们交给 entity 的 processor。Java 测试最近执行 575 项，0 failure、0 error、9 skipped。表达式解释器仍没有专门单元测试，也没有 Minecraft/Forge world 级、多 pass、`ysm.sync`、config-action、声音 once-only 或性能测量。见[动画问题](known-issues/animation.md) |
| CPU 渲染 | 本体通过独立前置的 `render-api` 调用 bake / extract / render；烘焙和状态提取使用 JVM，packed 顶点输出优先自建 native-render，缺失时回退 java-render。UV 0/6/17/28/29、六面 / 反向 cube、镜像非均匀缩放、PBR 切线、透明排序、Vanilla/Iris 字节布局测试通过；背景/左右手交替的五布局 Java/native 非空字节差分通过。女仆附着矩阵、隐藏背包不回退与乘客座位稳定序号有自动化覆盖。第三方 shader/PBR 组合视觉差分未测。见[渲染问题](known-issues/rendering.md) |
| 图像 codec | 由前置提供：PNG、JPEG、WebP、AVIF、ZTX 解码；编码覆盖无损 ZTX。AVIF 使用 Chicory JVM/WASM，与独立参考 RGB 分量最多相差 2/255。JPEG XL 不支持 |
| 当前产品运行入口 | 本分支已完成 Windows x64、Java 17、Forge 47.4.3 的纯 JVM 与自建 native 实机场景。托管基线不再加载官方 YSM native，也不继承其 SSE4.1 检测；JVM 可移植性仍以目标平台的 JDK、游戏、加载器及图形/音频环境可用为前提。Linux、macOS、Android/社区启动器、其他架构和旧版 Windows 尚未完成本分支实机验收；native 构建目标不等于产品支持。目标基线与适配边界见[平台适配现状与原则](../product-decisions/requirements/req-platform-availability.md) |
| legacy v1/v2 | Archive 到统一 raw parser、当前音频写出与重开已有自动化；真实语料中 12 个文件已按 header 精确识别为 raw，但仍缺带可信历史 provenance 的 Java 端到端声音包装验证 |
| **模型音频** | **Raw directory/archive、current export/reopen、legacy 投影、严格 Ogg Vorbis/Opus 解释、按需本地 / remote chunk 取得、64 MiB encoded/PCM 统一保留以及 Minecraft `AudioStream` 播放主链已接线。冻结 fixture 覆盖阈值、精确 frame、冷 / 热一致、独立播放、两周期 loop、取消与 channel handoff 局部边界；真实 legacy v1/v2/v3 包装、remote session、Minecraft/OpenAL 设备、stream pool N+1、heap retaining path 与 GC/Cleaner 最终回收尚未验收，不能据自动化声明端到端支持已通过** |
| v3 加密模型 | 同步、流式的单向导入已接线。native 真实语料覆盖 185 个加密容器及 inner version 1/4/9/15，全部完成解密、反混淆、解压、反序列化和 current 投影，并覆盖 778 个 sound field。Java current staging/reopen 与音频 projector 自动化已通过，但仍缺把可信历史 v3 包装接到 Java、远端和实际游戏播放的端到端、跨平台验收。见[格式问题](known-issues/format-and-schema.md) |
| 独立 Backend / 通用外部模型源 / GPU renderer | 均未实现。见 [future](../future/independent-backend.md) |

总体使用风险见[项目概览](../README.md)。
