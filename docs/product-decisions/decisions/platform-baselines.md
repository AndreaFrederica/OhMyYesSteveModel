# platform-baselines

- Requirement: [REQ.platform-availability](../requirements/req-platform-availability.md#reqplatform-availability)
- Select: OS/ISA 基线、独立运行库前置、可选 native、社区与 Android 启动器
- Needs:
  - 普通运行内容失败: [DD.local-failure-degradation](failure-isolation.md#ddlocal-failure-degradation)
- Landing:
  - [architecture](../../architecture/native-runtime/README.md#android-启动器接入)
  - [architecture](../../architecture/native-runtime/portable-runtime.md)
  - [status](../../status/support-and-verification.md)
  - [status](../../status/known-issues/format-and-schema.md#codec导入与平台)

## DD.bounded-platform-support

- Claim: 主线适配支持 Minecraft Java 版的主流游戏平台，采用明确的最低平台组合。其余平台的适配由社区 fork 维护。
- Rationale: 扩大平台矩阵会增加分发体积、验证组合与持续维护成本，而开发者精力有限。适配使用量极低且已停止支持的早期平台还会限制对较新平台能力的利用，因此不为扩大最低范围持续承担这些成本。独立前置及 Java 基线不自动扩大已验收的平台范围。

### BC.minimum-platform-baselines

- Claim: 主线以以下组合为最低可用性基线。运行环境还须能够运行本主线要求的 JDK、Minecraft Java 与 Forge。

| 平台 | 最低系统基线 | 最低 CPU 基线 |
|---|---|---|
| Windows | Windows 7 SP1 | x86-64-v1 |
| Linux | Ubuntu 14.04；Linux 3.13.9 / glibc 2.19 | x86-64-v1 |
| Android | Android 9.0 / API 28 | ARMv8-A |
| macOS | macOS 14.5 | Apple M1 |

这些是适配要求。实际支持与验证进度仍由下游支持状态记录。达到系统与 CPU 基线不等于任意启动器、驱动或模组组合均可用。

### BC.native-distribution-is-self-contained

- Claim: 所有平台的 YSM native 制品对宿主环境的外部运行库依赖仅限内核接口、libc 及其伴生系统库，如 libm、libdl。伴生库仍计入依赖，但通常由系统提供，无需额外处理。C++ runtime 及其余所需库必须由随模组 jar 分发的制品自包含提供，不依赖系统另行提供或要求玩家安装额外运行库。

macOS 同样不得依赖环境提供的动态 libc++。SDK 未提供 libc++ 静态库不构成例外，满足约束的构建方式由下游选择。

### BC.community-platform-support-boundary

- Claim: 其他非主流平台及 Windows XP、Vista、CentOS 7 等已停止支持且使用量极低的目标不属于主线适配范围，由社区 fork 维护。iOS 虽有 Minecraft Java 社区启动器，但其动态库签名机制阻碍本模组的 native 适配，因此也不列入主线支持范围。

### BC.native-provider-api-is-future-capability

- Horizon: future
- Claim: 后续将开发 API，允许其他模组提供 native 库以承接社区平台适配。该能力不视为当前已实现，也不将社区平台纳入主线维护与可用性承诺。

## DD.portable-runtime-prerequisite

- Claim: 本分支将替代运行库作为独立的必需前置 Mod 交付。本体通过能力接口使用它，JVM 托管实现承担完整基础功能，native 提供可选加速；运行时优先选择可用且通过自检的自建 native，没有对应加速或加载失败才回退托管实现，显式禁用 native 时始终使用托管实现。最终彻底移除对官方 native 模块的依赖。
- Rationale: 独立交付便于替换与复用每项能力。保留托管语言基线使没有对应 native 制品或无法加载它的环境仍能使用模型功能。该方向由用户明确选择，不以阶段性实现证明最终目标已完成。

### BC.runtime-prerequisite-distribution

- Claim: 本体声明兼容版本的前置依赖。前置提供所需运行库，玩家不需要另行安装算法依赖。native 加速制品不成为必需前置。缺少前置与缺少可选加速必须具有不同的结果：前者由加载器提示缺失依赖，后者继续使用托管实现。

### BC.managed-baseline-for-every-capability

- Horizon: future
- Claim: 每个能力优先采用 Java 或 Kotlin 实现。确实找不到合适的 JVM 语言实现时，允许由纯 JVM 引擎执行 WASM 作为该能力的托管兜底，引擎和模块不得依赖宿主 native。禁用所有 native 后仍能完成已有模型的导入、显示、动画、音频、重载与分发，不能以关闭功能或仅回退默认玩家模型充当兜底。加速实现不能改变格式、模型 identity、可观察语义或错误隔离契约。

实现进度和验证边界只在下游[迁移状态](../../architecture/native-runtime/portable-runtime.md#验证状态与边界)记录。
过渡期本体仍依赖官方 native 的事实不豁免最终验收要求。

## DD.android-via-community-launchers

- Claim: YSM 在满足前置条件的 Android 社区启动器环境中运行，不把任意移动启动器都列为适配目标。
- Rationale: JDK、Minecraft Java 及 Forge、Fabric、NeoForge 虽未官方支持移动设备，社区适配已形成多个开箱即用且稳定性合格的启动器。复用这些环境可以提供移动基础可用性，无需 YSM 自行承担整个游戏与 Java 运行环境的移植。

### BC.android-launcher-runtime-prerequisite

- Claim: 使用 YSM native 能力的 Android 启动器必须配置 `MOD_ANDROID_RUNTIME`，指向用于存放 YSM native 文件且与 `libjvm.so` 同属一个 native library namespace 的目录，使模组库能够被 JVM 正常加载。此项限制适用于过渡期官方 native 及后续可选加速。完成托管基线后，纯 Java/Kotlin 运行不以 native library namespace 为前提，实际平台可用性仍须单独验收。

社区对 Fabric、NeoForge 的移动适配只解释运行环境来源。本决策仍以 Minecraft 1.20.1 Forge 主线为范围。
