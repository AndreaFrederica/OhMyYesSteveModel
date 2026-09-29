# 运行模型

YSM 运行时同时存在进程级基础设施、client/server 侧服务、exact connection session、共享模型资源和逐实体状态。它们的结束条件不同；同一 JVM 中的集成服务端也不能把 client 与 server 的 mutable facts 合并。

## 生命周期与线程

| 范围 | Owner 与进入窗口 | 可变事实与退出边界 |
|---|---|---|
| 前置运行库就绪 | 模组构造阶段预热 `YsmRuntime` | 能力选择决定 Java 业务是否继续初始化；详见[独立前置与迁移](native-runtime/portable-runtime.md) |
| 进程基础设施 | `CommonEvent.onSetupEvent()` 的 `enqueueWork` 初始化 `ModelRuntime`，再初始化 `NetworkHandler` | `ModelSystem` 提供 builtin contract、storage、进程 Catalog 与 server runtime；不拥有每个远端 session 的状态 |
| 客户端服务 | `ClientSetupEvent.onClientSetup()` 的 `enqueueWork` 初始化兼容适配、locator 和 `ClientModelService` | Render owner 发布 client catalog 与完成资源；required default 的私有构造与发布见[默认模型](model-management/default-model.md) |
| 游戏服务端 | `ServerStartingEvent.onServerInit()` 响应 `ServerAboutToStartEvent` 创建 `ServerModelService` | Server game owner 裁决 session、catalog 与玩家事实；`ServerStoppingEvent` 关闭服务 |
| 客户端连接 session | `ClientLoggedEvent` 的 `LoggingIn` / `LoggingOut` 进入 `ClientSessionRuntime` | 以 exact `Connection` 匹配 begin/disconnect；连接退出只释放该 session 的 query、request、assembly 与效果资格，共享 catalog/runtime work 可继续 |
| 实体与 draw | `EntityModelBinding`、`AnimatableEntity`、`GeoModelState` | 绑定、播放和输出槽按 entity 隔离；动画与 extract 的调度见[帧执行](rendering/frame-execution.md) |

这里的 Forge 事件是 YSM 的接入窗口。FML 扫描、类加载和事件调度内部属于外部 loader 边界；表格不额外假定未由 YSM 建立的第三方监听器先后顺序。

## 不可变 Proto 发布边界

Java 使用 immutable QuickBuffers generated message 作为解析和构造后的 payload value；nested `Builder` 是唯一 generated mutation boundary。Builder 与 parse cursor 只存在于局部构造、转换或 decode seam，不跨 catalog、resource、network handler 或计算 publication boundary。业务路径只发布完整 `build()` / `parseFrom(...)` 结果；无效消息在所属输入边界失败并被上层隔离，不以 partial value 进入领域状态。

已发布的 nested message、repeated 与 map view 可以共享。Bytes 字段默认复制 input remaining range；每次 accessor 返回归零、cursor 独立的 read-only view，physical backing ownership 仍遵循[计算边界与内存](native-runtime/jni-and-memory.md)。从既有值派生少量字段时使用 wither，多字段或条件重构使用局部 `toBuilder()` 后重新发布。只有继续承担 validation、canonicalization、domain execution、lookup complexity、trust boundary 或 resource ownership 的 mapper/view 才保留；这类对象引用不可变 payload 或建立可重建索引，不再为了隔离 mutable Proto 深拷贝整棵 message。

当前 strict profile 把未标 `optional` 的 ordinary singular 字段作为完整 message 所必需，缺失时 `build()` / `parseFrom(...)` 失败；上层只隔离整条无效消息，不发布 partial value。Presence 审计只用显式 nullable 或明确 absence 分支证明字段可缺省，不从 C++ 聚合初始化反推 non-null；剩余 source Schema 差异见[格式与 Schema 问题](../status/known-issues/format-and-schema.md#proto-presence)。计算边界继续只接收 protobuf bytes、显式 buffer owner 或 typed handle；QuickBuffers message、Builder 与 allocator 都不跨 ABI。

## 工作线程与发布线程

有限工作 admission、worker 限制与 owner 发布统一见 [Reload 与发布](model-management/reload-and-publication.md)，纹理 Ready 门禁见[所有权与生命周期](model-management/ownership-and-lifecycle.md)。

| 执行环境 | 可做的事 | 交接要求 |
|---|---|---|
| Client/server game owner | 修改所属侧的 catalog、session、选择和绑定状态 | 先提交权威事实，再发起通知或后续工作 |
| Catalog finite worker | 在固定 source fact 上连续执行发现后的读取、转换、验证与 converted/prune I/O | 不等待 runtime worker、网络或 host；terminal 只入 Catalog event queue |
| Client/server runtime worker | 在网络输入已齐全后连续执行 cache probe/read/commit、load、bake、preview decode/encode 与 export I/O | 两侧和 Catalog 容量独立；不等待其他 worker 或 host callback，terminal 只入对应 runtime queue |
| Java 动画 worker | 用当前 entity generation 的 runtime instance（解释器内存、roaming 结构与 controller 状态）对已调度 entity 求值并 extract | 同一 entity 串行；observation 不能提交 effect，完成输出后才供渲染使用，也不能任意访问 GPU |
| Server dispatch worker | 发送已 accepted 的 physical transmission | 独占 outbound cursor 与调度；不接管游戏权限裁决 |
| 渲染计算 worker | 消费冻结的 render task 并写指定输出区间 | 不读取 `Entity`、Molang、Minecraft texture 或 `VertexConsumer` |
| Minecraft render thread | 纹理注册与上传、调用 render adapter、提交顶点 | Ready 发布之前完成所需纹理准备；GPU cleanup 按所属线程执行 |

## 结束与替换

连接关闭、catalog 替换、租约关闭、计算对象释放不是同一件事。Client disconnect 保留客户端服务、进程 Catalog、runtime cache 与 intrinsic default，只结束旧连接的查询、请求和效果资格并投影最新 local catalog；server stop 结束 game server service，但不把 client mutable facts 合并进同一个 owner。

Exact-session close、进程 owner disposition 和 Cleaner 的物理回收边界由[所有权与生命周期](model-management/ownership-and-lifecycle.md)统一定义；失败对页面、模型或整个 session 的影响见[失败处理](model-management/failure-and-recovery.md)。不要从 GC 完成、共享线程池空闲或 cache 命中推断 session 已结束。
