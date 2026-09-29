# 动画决策理由

本页记录动画相关的工程取舍。产品入口见[实体动画求值决策](../../product-decisions/requirements/req-evaluate-entity-animation.md)，权威分层见[文档政策](../../governance/documentation-policy.md)。

## 每个动画使用独立派生 chunk

派生资产为每个动画保存一个独立 zstd chunk，动画名称与读取范围一一对应。首次请求某个动画时只需读取、解压、解析并绑定该 chunk，不必加载全部动画，也不需要在共享压缩流中维护二级 offset、局部解压或额外索引状态。

这里选择的是按动画粒度的确定性边界。更细的帧、骨骼或任意 byte-range streaming 会增加调度和局部验证状态，而日常访问没有对应收益。

## 完整发布资产，按需绑定动画

整个派生资产先通过 manifest、chunk table 与结构验证并原子发布，render target 才能 Ready。单个动画的读取、解析和绑定延迟到首次请求。完整发布保证 reader 只能看到旧的完整文件或新的完整文件；按需绑定仍保留 I/O、解压和内存收益。

单动画失败的产品边界见 [BC.animation-failure-is-local](../../product-decisions/decisions/animation-isolation.md#bcanimation-failure-is-local)。在整体结构尚未验证时提前发布局部动画，会将一个文件拆成多个可见提交点并扩大恢复状态。启动时预绑定全部动画则会放弃独立 chunk 的主要价值。

## 源文本与执行结构分开

当前实现将解析放在模型加载期：一次解析把每个位置的 Molang 源变为 `IValue` AST，AST 随 render target 发布为共享只读资源，此后每帧只做 AST 遍历。求值期复用同一个 `ExpressionEvaluator`，identifier 在解析期已绑定到具体 binding 对象或 pooled 名称。tick/render 路径不承担源解析、binding 名称查找或资源构造；entity 一侧只有变量存储、controller 时间线与自身持有的引用。

选择的边界是「解析一次、共享只读、按 entity 隔离可变状态」。如果将源解析或解析结果的所有权放入求值路径，解析成本和资源生命周期会跟随 tick/render 与 entity；替换、失败与释放的边界会从 render target 扩散到每个 entity，而日常收益并不存在——模型的源文本在一次加载后不再变化。这里不声称任何性能结论：解释器的 cold/warm/hot 成本尚未实机测量，见[动画已知问题](../../status/known-issues/animation.md)。

与已回退的 target-scoped AOT 机制相关的取舍（target 级编译发布、bytecode 产物、artifact cache、host ABI 与 closed catalog）随该机制一起删除，本页不为它保留理由。容器仍以 `mixel.common.Program` envelope 携带执行字段，但本项目只写入和读取其中的 `source`。解析与 binding 的所有权见 [Molang runtime](molang-runtime.md#模块与发布流程)。

## 连续采样与离散因果分开

骨骼按累计时间采样可降低远处对象的求值成本。离散指令若只取区间终点，会改变作品逻辑，因此采用[区间推进](controllers-and-playback.md#指令区间推进)。可观察义务见[动画时间语义](../../product-decisions/decisions/animation-sampling.md)与[重复观察隔离](../../product-decisions/decisions/animation-isolation.md)。
