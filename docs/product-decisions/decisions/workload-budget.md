# workload-budget

- Requirement: [REQ.preserve-gameplay-and-availability](../requirements/req-preserve-gameplay-and-availability.md#reqpreserve-gameplay-and-availability)
- Select: tick/render 阻塞、后台并发、资产分发与 gameplay 消息竞争、MSPT 软指标
- Needs:
  - 比较设备表现: [DD.high-end-quality-with-basic-availability](device-quality.md#ddhigh-end-quality-with-basic-availability)
- Landing:
  - [design](../../governance/verification-policy.md#性能验证)
  - [architecture](../../architecture/rendering/frame-execution.md#低延迟同步与并发边界)
  - [architecture](../../architecture/model-management/reload-and-publication.md)
  - [architecture](../../architecture/runtime-model.md#工作线程与发布线程)
  - [architecture](../../architecture/network/asset-transfer.md#分发负载保护)
  - [design](../../architecture/network/design-rationale.md#资产分发保护复用既有-owner)
  - [status](../../status/known-issues/rendering.md#并发与性能)

## DD.main-loop-has-priority

- Claim: 模型加载、转换、求值、渲染和同步的工作量，必须服从游戏主循环的资源需要。
- Rationale: 客户端帧率和服务端 tick 是所有玩家共用的基础体验。阻塞等待、主线程重任务和过量后台并发，都会把局部模型需求扩散为全局卡顿。把工作搬到后台本身，不能消除这一成本。

### BC.no-main-loop-starvation

- Claim: 正常模型工作不得阻塞 tick/render 线程等待异步结果，也不得以无界主线程任务或过量后台并发挤占游戏主循环。无法承受的工作应局部拒绝或降级。Catalog 与 runtime resource 的重负载容量必须相互独立。每个后台任务连续完成一条输入已齐全、worker-safe、有界且不等待其他 worker、网络或 host callback 的处理链。长期业务状态只由对应 owner 在 tick 中消费不可变完成事实后提交。Host 完成可以触发新的独立工作，但不能让原 worker 挂起等待交接。

## DD.gameplay-traffic-precedes-asset-distribution

- Claim: 服务端模型资产分发必须让位于 gameplay 业务消息的正常传输。不能以模型取得速度换取其他游戏内容的游玩体验受损。
- Rationale: 模型资产与游戏业务共享网络资源。分发占满带宽或积压发送，会把模型加载成本扩散到原版及其他模组的游玩过程。资源竞争时接受模型取得变慢或局部分发被拒绝，以保护玩家正常游戏。

### BC.asset-distribution-does-not-block-gameplay

- Claim: 服务端模型资产分发占用的带宽，不得导致 gameplay 业务消息阻塞，进而影响其他游戏内容的游玩体验。分发压力增大时，应限制、延后或局部拒绝资产工作。此约束独立于 MSPT 等性能软目标，tick 耗时达标不能替代 gameplay 消息传输保护。

## DD.performance-targets-guide-optimization

- Claim: 性能参考设备、并发规模与耗时指标，是优化排序和后续演进的软目标。平台适配范围与最低可用性要求另行成立。
- Rationale: 需要共同的目标来比较模组开销、为后续优化排序并探测容量上限。这些目标不等于任意内容下的服务保证，软指标也不放宽普通错误隔离与游戏数据保护。

### BC.server-performance-objectives

- Claim: 服务端性能优化按以下规模和耗时目标评估。所有数值均属于软约束。

| 场景 | 优化与观测目标 |
|---|---|
| 100 名玩家以内 | 正常运作；重载模型列表并分发模型时，平均 MSPT 增量低于 5 ms，lag spike 不超过 10 ms |
| 200 名玩家 | 面向未来 Bukkit 插件端适配的前瞻测试，不据此扩大当前平台支持承诺 |

MSPT 指每个服务端 tick 的处理耗时。10 ms 是不应突破的优化目标，与其他数值一样属于软目标。
