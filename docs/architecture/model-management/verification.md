# Mock 验证架构

Mock 表示测试宿主和输入适配，不表示另写一套模型管理实现。验证复用生产 catalog、session、授权、消息分发与资源路径；fixture source、socket byte carrier 和 logical host callback 只提供受控输入与交接。`SessionCollectionPublication.fullFragments` 的显式 animation map 重载复制输入，普通入口仍读取默认动画；它是无状态组装 seam，不引入第二份 publication authority。产品契约、完整场景清单和某次运行的验收结论不在本页。

## 分层与可证明范围

| 层 | 入口与职责 | 不能替代的证据 |
|---|---|---|
| Domain | `modelManagementMockDomain` 执行 tagged tests，覆盖领域状态、来源变化、请求/页面终态和受控 workload | 不证明物理双端依赖或真实 Forge/渲染接入 |
| Classpath | `MockSupervisor` 以不同物理 classpath 启动普通 Java client/server 子进程，载入各自生产依赖并通过 byte carrier 交互；smoke 验证基础闭环，full 与 exact-100 分别运行系统场景与规模场景 | 不启动 Forge launcher，不证明真实游戏 hook、GPU 或 GUI 行为；smoke 明确不具备完整验收资格 |
| Forge host | `modelManagementMockForge` 由 `HostSupervisor` 启动独立 server、client A/B，`mockHost` 中的薄 probe 经宿主入口驱动并观察行为 | 只证明所运行环境与场景，不覆盖所有 shader、GPU、长期运行或物理回收时限 |
| Evidence closure | `modelManagementMockFinal` 汇总领域、classpath、测试与既有 host 证据，核验身份、产物完整性、变更和文档归属 | 当前是绑定冻结任务包及既定基线的收敛器，不是任意版本的通用全量验收入口；不会自动重跑 Forge 或将历史结果升级成当前通过 |

Classpath 的 full 与 exact-100 各执行两次受控回放并核对输入身份及结果。域内 workload 与物理 exact-100 是不同验证半径，不能互相冒充，也不能据场景名称推导持续并发性能承诺。性能目标适用范围由[验证政策](../../governance/verification-policy.md)定义。

Forge host 包含不打开模型 GUI 的十二模型自动扫描（根级 archive 与子目录各半）、已打开列表的文件夹自动刷新，以及重连后已选模型自动恢复检查。可通过 `YSM_MOCK_CUSTOM_MODELS` 环境变量提供额外样本目录；supervisor 记录其根级 `.ysm` 文件哈希，并只复制到隔离客户端，原样本和玩家存档不参与写入。该场景不替代集成服务端冷启动及其他加载器、整合包的验收。

后台加载改动另由低并发/零预取的 catalog 回归验证完整遍历、逐项进度、坏文件计数和关闭时待处理 inventory 的终结；缓存通道回归用超过预检窗口的未命中来源与被阻塞的冷 worker，验证后续成品命中仍能发布、冷解码保持指定并发、计数闭合，并验证缓存损坏或源哈希变化只转冷路径、预检本身不做解码/重建；render cache 回归验证单次慢 host publication 后不继续消费整批。Forge host 的 `loading-settings-proof` 检查五项设置控件并保存真实设置页、重新扫描时的 HUD 截图。纹理准备、动画槽和 failure registry 另验证取消（含包装后的取消）不污染后续同内容请求，真实错误仍冻结。双客户端回归检查双方实际 render target 的 model ID，不能仅以服务端选择记录或客户端 capability 相同判定显示一致。`pair-render-proof` 在重连后确认两名玩家的实际 target，再各自捕获同屏截图供视觉核验；截图与模型 ID 断言互为补充。这些验证不构成大量用户模型下的帧率/GC 性能结论。

## 构建与进程 ownership

`mockSupport` 提供证据数据与记录工具；`mockSupervisor` 只依赖 support 与自身工具依赖，不加载生产业务或任一 endpoint。`mockCommon` 复用生产能力，`mockClient` 与 `mockServer` 各自增加 side endpoint；运行清单要求本侧 endpoint 存在且对侧 endpoint 不在 classpath 中。`mockHost` 仅加入专用开发启动配置。

`ProcessSupervisor` 负责验证进程的启动、日志、等待与精确进程树清理。测试超时和强制退出用于暴露基础设施故障，不是生产 session retirement、资源取消或 Cleaner 的完成协议。残留进程使证据不可审查，不能作为正常退出。生产 owner 边界仍由[所有权与生命周期](ownership-and-lifecycle.md)定义。

验证任务是显式入口，普通 `check` 不因此覆盖所有 mock 层。`verifyMockHostPackaging` 检查发布 archives 中不存在 mock host/supervisor/evidence/classpath 类、双端入口及专用日志资源，防止测试控制面进入正式包；打包隔离通过不代表业务场景通过。

## 输入与证据门禁

Classpath 与 final 入口通过 `modelManagementMockTaskRoot` 消费冻结任务包。证据绑定输入、Java/native 身份、配置及产物 hash。失败、不可审查与通过必须分别保留，不能只看进程返回或某个旧报告。

Full/exact-100 在启动业务场景前由 `NativeCandidateIdentity` 核验 native manifest、provenance hash 和配置的运行库 SHA-256。版本或二进制与冻结候选不符时拒绝继续，不自动更新候选来消除失配。Forge host 单独记录实际运行库身份；final 只按既定身份和适用声明采纳任务包内的 host 证据。

Final 的任务依赖包含回归测试、domain、classpath smoke/full/exact-100 和 packaging，不包含 Forge 重跑。它还核验冻结的实现与文档基线条件，因此源码或文档演进后不能只传入旧任务包就推定可复用全部结论。具体运行记录与历史采纳说明属于证据包，不在本页固化为长期架构事实。

这些进程与证据协调均为验证专用设施，不增加生产状态 owner，也不计入[生产复杂度地图](../../complexity-map/README.md)的机制数量。
