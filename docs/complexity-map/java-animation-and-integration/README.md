# Java 动画、绘制交接与扩展复杂度总览

这一块管的是：实体各自的求值状态、调度与采样、多 pass 输出、动作副作用、模型声音怎么交给宿主播放、controller 兜底、可选扩展隔离，以及 Java 到宿主顶点 consumer 的交接。

模型资源和声音保留归 [MN](../java-models-and-network/README.md)。native worker、帧状态、透明重排和音频解码器归 [NR](../native-capabilities/README.md)。

## 从问题找页面

| 想知道什么 | 看哪页 |
|---|---|
| AN 机制各自在防什么错 | [机制](mechanisms.md) |
| 动画、绘制交接、扩展覆盖到哪 | [证据、覆盖与根依据](evidence-and-scope.md) |
| 求值、多 pass、副作用、输出桥之间谁逼出谁 | [因果图与牵连候选](causal-graph.md) |
| 调度、姿态、输出路径、扩展检查哪里能简化 | [可简化的地方](reduction-pivots.md) |
| 哪些兼容、性能、线程语义还没定 | [未决问题](open-questions.md) |

## 这块内部怎么分

| 一组 | 机制 | 管什么 |
|---|---|---|
| 实体状态与调度 | AN-01–04 | 只读资源可共享，可变求值状态实体私有；异步、限频、多 pass 分别协调 |
| 动作与 controller | AN-05/06/19/20 | 动作能力、模型覆盖/内建动作桥、延后参数快照、声音交给宿主 |
| 扩展隔离 | AN-08–10/13 | 历史混合、检查器、加载前隔离、还没闭合的 fence |
| 宿主输出交接 | AN-11/12 | direct/fallback 两条 consumer 路径，成功后才提交 |

AN 内部的关系写在本目录。跨子系统关系统一写在[跨子系统关系](../governance/cross-subsystem-relations.md)。加载规则见[关系登记入口](../governance/relationship-register.md)。
