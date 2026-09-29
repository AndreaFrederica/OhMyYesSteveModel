# 渲染已知问题

本页只记录当前实现可证实的渲染缺口。目标与正常语义见[渲染架构](../../architecture/rendering/README.md)。各条按「现象 → 影响 → 目前处理」组织。

## 视觉与接入

- **现象**：透明排序只覆盖单次模型 draw。**影响**：跨实体、跨模型和跨 draw 的次序仍由上层决定。Iris shadow 不执行透明排序。**目前处理**：多模型透明场景需自行控制顺序。
- **现象**：PBR 效果依赖 Iris 版本、shader pack 和 companion texture 接入。**影响**：仅凭 baked tangent 存在不能保证效果一致。**目前处理**：按具体 shader 组合验收，不做统一承诺。
- **现象**：第一人称手臂原先的 Forge 订阅被整段注释。**目前处理**：恢复入口并按左右 locator 提取；没有对应资源时保留原版。`RenderFirstPlayerBackground` 已按 `Background` locator 通过 ysmlib 提交独立背景几何；卓越前线 `RenderPlayerArmEvent` 已接回同一第一人称 renderer，事件失败时保留原版手臂。
- **现象**：副手普通持物分支误传主手物品、右手显示上下文与右手 locator。**目前处理**：已改为副手物品和左手参数，第三方枪械专用分支保持各自处理。
- **接线核查**：`CustomPlayerRenderer` 已注册持物、鞘翅、肩部鹦鹉、头饰四个 layer，背包在兼容初始化后注册；这些类已实际遍历 `GeoModelState` locator 并提交绘制，并非全部被禁用。缺少 locator、模型动画隐藏、装备为空和用户开关仍可导致不绘制。逐项视觉和第三方组合验收尚不完整。
- **全身第一人称**：保留上游由 FirstPerson 发起、YSM 替换身体并隐藏头部的兼容路径；已修复独立第一人称上下文被 `q.is_first_person` / `ysm.person_view` 误判为第三人称的问题。FirstPerson 2.2.3 + Forge 47.4.3 的纯 JVM 实机验证覆盖非空躯干/双腿、头部隐藏、第三人称恢复和本地禁用回退，截图已核查。RealCamera 与 shader 组合仍未实机验收。`Background` 是独立的 arm 背景分组，不是全身绘制驱动。
- **本轮验证**：2026-09-29 的精简 Forge host 使用纯 JVM 完成左右手、非空背景、每帧背景去重、状态扩展调用和关闭后回退检查；截图已核查。女仆定位矩阵/隐藏规则和稳定乘客座位通过单元测试，背景/双手五种 packed 布局通过 Java/native 字节差分。卓越前线、女仆完整装备与 shader 组合仍需各自实机视觉验收。
- **女仆兼容**：女仆附着点已通过帧快照交给 TLM layer，父骨骼链与多手定位均已接回。女仆 roaming 同步仍是上游既有的未实现能力；第三方完整游戏场景与所有装备组合尚未完成视觉验收。

## 正确性与失败处理

- **现象**：`ModelState.extract` 会先使旧状态失效。**影响**：失败后同一逻辑帧可能不再重试。**目前处理**：不消费失效 pose。native draw 的拒绝、分配失败与链接失败另由前置重试 Java 并隔离加速器；Java 仍拒绝非法输入，不能把加速失败与坏模型输入混为一谈。
- **native 帧边界**：已修复可见骨骼减少后 JNI 仍读到旧容量的问题，并补齐截断帧头检查及运行期 Java 兜底。五种布局的骨骼增减、输出哨兵与非单位相机矩阵验证通过。真实 Forge + FirstPerson 在 native 模式完成全身/双手/背景、视角切换及正常退出，成功 native draw 计数非零且未回退。此前矩阵校验失败的用户报告没有记录原始矩阵值，具体输入触发源仍未独立重现；不能把容量问题的复现当成该报告的完整复现。
- **现象**：Serialized baked cache payload 不能独立证明 SIMD capability 匹配。读取虽校验结构、层级、索引和计数。**影响**：未重新验证几何浮点值的有限性及语义域。Bake 对极端有限输入派生的 plane / tangent 也缺少完整结果域验证。**目前处理**：极端输入不在已验证范围内。
- **现象**：上层必须提供与 position matrix 匹配的 normal matrix。**影响**：native 只校验数值有限，不验证二者一致；该组合目前也没有端到端验证。**目前处理**：由调用方保证配套正确。
- **现象**：`BakeModelOptions.force_translucent` 可能让原本 opaque 的骨骼进入透明分区。**影响**：透明深度准备条件未同步。当前 Java 主加载路径不启用该选项。**目前处理**：启用前需补齐最终排序验证。

## 并发与性能

- **现象**：`ParallelExecutor`、translucent scratch、`VertexConsumer` fallback 与 `NativeRenderer` 的共享 matrix scratch 都不可重入。**影响**：多个 `renderer::Render` 必须全局串行。`ModelState` 原地复用自身 pose 与索引存储，Java view 只在该状态的有效期内可读。**目前处理**：同一输出槽的 Extract、Render、换模与释放必须串行。
- **现象**：调度按不可拆分 `CubeGroup` 数而非实际 quad、PBR 或剔除成本分配任务。**影响**：复杂模型可能出现 worker 尾部不均衡。**目前处理**：性能评估时需考虑该粒度。
- **现象**：剔除分区按最大可见容量预留，并以零值填充未使用槽位。**影响**：这是固定 offset 的当前代价。**目前处理**：接受现状。

## 扩展接线

注册事件在扩展发现结束后发布；modifier 在新状态提取完成后按 target kind 调用。第一人称模型、投射物、载具和女仆均进入相应模型事件窗口。第三方 modifier 的线程安全与行为需要由对应扩展验证。生命周期与取消语义见[游戏与扩展接入](../../architecture/integration/README.md)。

## 待验证场景

CPU renderer 尚未形成可作为支持声明依据的自动化回归与视觉验收闭环。对应 native 实现仅作为可选加速。

在声明支持前，Minecraft 运行验证至少应覆盖：`level` entity 同帧多 pass、`inventory` / `paperDoll` context 的 mutable 输出、本地第一人称 `irisShadow`、模型热切换、`VertexConsumer` fallback、透明与 PBR、非均匀缩放及各 locator layer。还应验证 locator mapping 始终读取对应 `ModelState` 的当前 pose，且旧 Java view 不跨越 Extract 或 close 使用。

Packed normal 的编解码约定见[顶点输出](../../architecture/rendering/vertex-output.md)。约定匹配不能替代 fallback 与 direct 的完整视觉等价验收。视觉验收应比较 Vanilla、Iris 与 Blockbench 基准，并区分几何语义偏差和 shader / 光照环境差异。
