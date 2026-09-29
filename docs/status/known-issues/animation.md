# 动画已知问题

本页只记录当前实现可证实的动画缺口。正常职责和数据流见[动画架构](../../architecture/animation/README.md)。各条按「现象 → 影响 → 目前处理」组织。

## 求值与语义

- **现象**：`isMoving` 当前有逻辑错误。**影响**：依赖该字段的 `CodedAnimationController` 会受影响。**目前处理**：已知缺陷，修复前不应采信其值。
- **现象**：头部 yaw / pitch 输入已经计算，但 coded head-bone 应用仍被禁用。第一人称手臂使用独立且未完成的动画运行时，未共享完整 controller 进度。**影响**：头部跟随和第一人称动画表现不完整。**目前处理**：这两块暂不声明支持。
- **现象**：骨骼基准 snapshot 错误地以 `children_hidden` 初始化 `cubes_hidden`。**影响**：两个可见性通道可能互相污染。**目前处理**：可见性异常时优先排查此处。
- **现象**：Controller 的旋转混合仍保留不完整的历史行为。当前 Schema 的 `State` 已没有 `blend_via_shortest_path` 字段（field 7 空置），reader 一律按 `false` 处理。**影响**：该行为无法由模型表达。**目前处理**：需要该能力时应走新字段加新 schema version。
- **现象**：骨骼绝对轴心由 `ysm.bone_pivot_abs` 提供。**影响**：引用旧 `ysm.bone_absolute_pivot` 名称的模型不会解析到该函数，依赖它的行为缺口仍存在。**目前处理**：模型作者应改用新函数名。
- **现象**：Sound keyframe 与 controller-state sound effect 都进入同一 `SoundInstanceManager`。无冒号名称解析当前 render target 的模型声音；有冒号名称继续播放 Minecraft `SoundEvent`。模型声音的内容与播放主链已有局部自动化覆盖。**影响**：真实 Forge world 的 once-only 触发、音量、OpenAL adoption、设备容量和停止时序仍未验收。`ysm.play_sound` 使用相同路由。**目前处理**：声音链路可用但尚未完整验收。

## Context、线程与生命周期

- **现象**：异步求值没有不可变输入快照，会直接读取 live `Entity`、`level`、`Minecraft` 输入和可选模组 API。**影响**：同次求值可能混入不同时间点数据，也可能违反外部 API 的线程限制。**目前处理**：已知线程风险，修复前不应在异步路径调用外部 API。
- **现象**：Forge world 中 controller、instruction、defer、config 与多 `RenderContext` 的实际 once-only 行为尚未端到端验证。`AnimatableEntity.executeMolangExp` 只把任务排入 `AnimationProcessor` 的待执行队列。**影响**：队列的排空时机与同一次逻辑推进内的执行次数没有实机证据，也没有对应自动化覆盖。**目前处理**：once-only 行为不作为已保证语义。
- **现象**：Canonical pose 可以跨兼容 pass 复用，但当前逐次 draw metadata 可能随输出一起复用。**影响**：例如同帧 shadow pass 可能观察到上一 `RenderContext`。**目前处理**：多 pass 场景需注意 metadata 新鲜度。
- **现象**：同一 render target 内热替换会保留动画状态并依赖烘焙兼容。**影响**：兼容性门禁和第一人称独立运行时尚缺完整闭环。**目前处理**：热替换视为实验能力。
- **现象**：`AnimationProcessor` 原地修改共享 snapshot 与 attribute。**影响**：没有事务 staging、异常回滚或模型 revision 二次校验，失败可能留下部分更新。**目前处理**：失败后状态可能不一致，需重置或重载。

## Molang 与同步

- **现象**：模型内 Molang 已回到本地解释器直接执行 source。加载与绑定会解析 execution-bearing 字段，求值在渲染路径上逐次进行。**影响**：求值路径没有专门的单元测试。热路径的解析/求值成本、allocation、表达式复杂度上限与 tick/render 影响都尚未测量，不能声明主循环成本可接受。expression runtime 在 Minecraft/Forge world 层也没有端到端验证。**目前处理**：性能和稳定性视为未测量。
- **现象**：Roaming 的 full 与 delta 已由玩家状态报告路径携带并在消费端按协议验证。**影响**：网络 inactive storage 仍按模型派生的 32-bit 短键分组，短键碰撞和协议无变量删除语义仍未解决。**目前处理**：已知协议缺口，变量删除不作为支持能力。
- **现象**：`ysm.sync` 只携带有限 F32 参数，参数个数限制为 16。没有目标 identity、独立频率限制；按设计也没有 sequence、ACK、重放或持久化。**影响**：实体缺失、generation/model 不一致或会话变化时事件会丢失。真实双端 relay/disconnect 路径尚未验证。**目前处理**：作为瞬时事件使用，不依赖可靠送达。

## 联动与验证

- **现象**：主线仍直接接线部分战斗、移动、载具和装备 adapter，大量目标未迁入。**影响**：既有接线也未按统一输入边界完成线程审计与实机验收，不能视为正式支持。**目前处理**：见[模组动画联动](../../future/mod-animation-integration.md)。
- **现象**：Java/corpus 测试已覆盖 animation/controller 的 Proto 映射与字段顺序、`Program` envelope 的 oneof tag 与 source 往返、ModelData 的双 Proto codec 互操作、内置目录索引、模型导入与 raw parse、Roaming 变量存储，以及模型声音 retention/playback/handoff 的局部边界。`test` task 最近执行 575 项，0 failure、0 error、9 skipped。**影响**：仍缺 coded / Bedrock / hybrid controller 在真实 entity 上运行、controller config、`ysm.sync`、多人远端 `Entity`、模型热切换、同帧多 `RenderContext`、声音 once-only/音量/host adoption、第一人称共享和长期 reference convergence 的 Minecraft 端到端测试。表达式解释器的解析与求值本身没有直接测试。**目前处理**：自动化测试先覆盖，端到端测试按项补齐。
- **现象**：`BoneAttribute` 与 `BakedModel` preorder、可见性、locator 的跨语言契约缺少独立 golden 验证。**影响**：布局重复定义仍有漂移风险。**目前处理**：补跨语言 golden。

模组恢复边界见[模组动画联动](../../future/mod-animation-integration.md)。渲染侧的 extract、locator 与重入缺口见[渲染已知问题](rendering.md)。
