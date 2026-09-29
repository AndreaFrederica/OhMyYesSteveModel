# 渲染系统

YSM 将玩家、投射物、载具的模型渲染为基岩版模型。渲染管线拆为 bake / extract / render 三阶段：静态几何预计算、逐帧骨骼状态提取、顶点输出。

Java 负责模型、纹理、[动画求值](animation.md)、`RenderType`、`VertexConsumer` 与资源生命周期。CPU 烘焙、状态提取、调度与顶点生成位于独立前置 ysm_runtime 中；native 实现仅作为可选加速，不是当前主实现。Minecraft 最终负责上传与 draw。

YSM `BakedModel` 及其磁盘 cache 均为内部派生格式，不属于 [Model Schema](../standards/model-schema/README.md)，也与 Minecraft 同名类型无关。

## 设计目标

- 将静态分析与数据整理前移到 bake，使 extract 只形成逐帧状态，render 只处理本次 draw。
- 小负载避免调度开销，大负载并行生成顶点。允许 `level` entity 主路径提前求值和提取状态。
- 以 Blockbench 预览效果为视觉基准，在平台与渲染管线约束内尽量保持姿态、面朝向、UV、材质和透明表现一致。
- 以同一几何语义适配 Vanilla、Iris 和通用 `VertexConsumer` fallback。优化不能牺牲可见面、UV、法线、切线或透明层次的正确性。
- 保持三阶段语义稳定，同时允许 CPU 数据布局和执行策略继续替换。

## 本地呈现边界

本机的模型与第一人称手臂屏蔽、恢复原版表现及音效调节，由 [DD.local-player-controls-presentation](../product-decisions/decisions/local-presentation-control.md#ddlocal-player-controls-presentation) 定义。本页渲染管线说明适用于启用 YSM 替换的绘制。产品控制契约不表示具体开关已完成实现或验证。

## 三阶段管线

```mermaid
flowchart LR
    Input["几何、纹理像素与烘焙选项"] --> Bake["bake<br/>静态预计算与分区"]
    Bake --> Baked["不可变 BakedModel"]
    Baked --> Extract["ModelState::Extract<br/>pose、可见集合与 RenderSchedule"]
    Frame["动画骨骼结果"] --> Extract
    Extract --> Snapshot["有效 ModelState"]
    Snapshot --> Render["renderer::Render<br/>变换、剔除与顶点写入"]
    Draw["draw context"] --> Render
    Target["RenderType → VertexConsumer"] --> Render
    Render --> Buffer["VertexConsumer → Minecraft / Iris draw"]
```

`BakeModel` 构建 `BakedModel`；`ModelState::Extract` 形成逐帧可见状态与 `RenderSchedule`；`renderer::Render` 通过 direct 或 `VertexConsumer` fallback 输出顶点。这些名称均为内部契约。

详细流程、数据与线程契约见[渲染架构](../architecture/rendering/README.md)。尚未实现的 GPU 方向见 [GPU Compute Renderer](../future/gpu-compute-renderer.md)。
