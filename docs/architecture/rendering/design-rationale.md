# 渲染决策理由

本页记录渲染职责与数据布局的工程取舍。产品入口见[一致渲染模型决策](../../product-decisions/requirements/req-render-model-consistently.md)，权威分层见[文档政策](../../governance/documentation-policy.md)。

## 计算层只承担有实际消费者的能力

Catalog、metadata transfer、remote cache 和 selection 由 Java 领域层拥有；渲染能力只消费已经验证、烘焙和求值完成的数据。跨语言系统不要求两侧拥有对称的 identity API 或状态机：在计算侧没有直接消费者时预先复制这些机制，只会产生第二份 authority、生命周期和错误路径。

格式标准仍然约束所有实现。只有计算侧真正需要读取某项格式事实或提供对应能力时，才增加最窄的输入边界；不为架构外观对称提前建立 adapter、session 或 cache 管理 API。

## 派生 cache 按精确输入隔离

同一模型在不同容器、纹理或能力配置下可能产生不同热数据，业务身份不足以证明可复用。精确 key 由[模型管理](../model-management/storage-and-cache.md)生成并封装，渲染侧只提供 [Bake profile](bake-and-partition.md#三种表示)；serialized cache 不提升为跨实现格式。

## 渲染不建立第二套 residency

Entity、GUI 与 required default 复用[模型管理的资源生命周期](../model-management/ownership-and-lifecycle.md)，渲染只持有租约和 entity-local mutable state。另建 target cache、default pin/preload owner 或引用计数会让同一 baked target 出现两套加载、淘汰和关闭判断。临时输出仍由固定 owner 释放，避免容量敏感对象等待 Cleaner。

## 输出路径等价不构成通用恢复

[输出等价契约](../../product-decisions/decisions/geometry-regions.md#bcoutput-paths-are-visually-equivalent)只要求受支持路径保持作品语义。共同求值或绘制阶段的错误不因切换顶点输出路径而消失，恢复仍由受影响目标及故障 owner 判断；另一条输出路径不作为默认可用的恢复机制。
