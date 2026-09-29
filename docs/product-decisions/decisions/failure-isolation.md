# failure-isolation

- Requirement: [REQ.preserve-gameplay-and-availability](../requirements/req-preserve-gameplay-and-availability.md#reqpreserve-gameplay-and-availability)
- Select: 普通内容失败、数据保护、startup 与连接隔离
- Needs:
  - 模型会话版本或验证失败: [BC.version-failure-is-session-local](session-failure.md#bcversion-failure-is-session-local)
  - 判断可选联动的具体降级或人工例外: [BC.incompatible-integration-is-optional](integration-risk.md#bcincompatible-integration-is-optional)
  - 处理本地来源或缓存失败: [DD.user-model-sources-are-readonly](artifact-storage.md#dduser-model-sources-are-readonly)
  - 涉及目标呈现降级: [DD.default-model-is-reliability-baseline](model-fallback.md#dddefault-model-is-reliability-baseline)
  - 涉及默认兜底本身失败: [CON.default-failure-exception](../shared.md#condefault-failure-exception)
- Landing:
  - [architecture](../../architecture/rendering/vertex-output.md)
  - [architecture](../../architecture/model-management/failure-and-recovery.md)
  - [status](../../status/known-issues/rendering.md#正确性与失败处理)

## DD.local-failure-degradation

- Constraints: [CON.normal-use-isolation](../shared.md#connormal-use-isolation)
- Claim: 正常使用中的失败必须降级受影响的内容或功能，并保留游戏主逻辑的可用性。
- Rationale: YSM 提供视觉与创作价值。一个模型或一项联动失效不应让玩家失去整个游戏、服务端或其他内容，局部降级使故障代价与失败对象相称。可选联动或模型会话的价值不抵偿整个游戏启动或连接失败。共享渲染管线服务世界与其他模组，一次绘制也不能污染后续内容。

### BC.ordinary-failure-keeps-game-running

- Claim: 普通模组错误不得造成游戏崩溃、阻断 startup 或主动终止正常 Minecraft 连接。局部拒绝、停用或默认模型回退必须保持其他游戏逻辑正常。

### BC.protect-game-data

- Claim: 失败与降级不得破坏内存中的游戏实时数据、磁盘存档或与 YSM 无关的内容状态。

### BC.render-failure-does-not-corrupt-scene

- Claim: 正常使用中的模型失败不得遗留会污染后续内容的半完成输出、错误绘制状态或失效资源。降级也不能造成其他实体或世界整体的错误呈现。
