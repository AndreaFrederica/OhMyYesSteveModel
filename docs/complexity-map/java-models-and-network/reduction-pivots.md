# 可简化的地方

本页列出由 MN 子系统主导的简化方向。影响范围广不等于可以直接删除。

| 支点 | 最小改变 → 可减少部分 | 剩余义务、成本与最小验证 |
|---|---|---|
| V-03 表示替代规则 | 如果所有来源都要求 exact，MN-03 的非 exact 候选及 publication/actual 双表示例外可缩减。 | **产品语义变化**，需重新裁决 D-IDENTITY/D-REUSE。AP-02、MN-05、NR-05、Pending/Ready、旧 lease 均有独立需求。中高成本；同源重封装会增加下载。核验替代矩阵及实际带宽，不能据 R=4 宣称可删四机制。 |
| V-13 核实后收缩 publication 调度 | 测量 ordinary terminal 的实际 burst/host 开销，比较现有限额、较简单 owner 调度与上游错峰，MN-19 的排队/guard 组合可能缩减。 | MN-18 全 component sample-ready 与 exact-current 门禁不能删除；terminal 计数已覆盖 stale/失败但不限制单次 upload 耗时或 live bytes。取消限额可能增加卡顿，新增限额也会增加状态。中等成本；混合 stale/失败/成功并覆盖 required bootstrap，测处置次数、峰值驻留和 owner 耗时。 |

默认内存 resident 与「整份 descriptor 先验证后构造」已经是当前基线，因此旧临时 handoff 和 rejected-prefix rollback 不再作为简化点保留。它们的退出不删除 MN-08 的 required startup、MN-12 的 typed coverage、MN-14 的 accepted source close 或 NR-05 的 baked serialize/read。

Target 构造阶段的独立失败表已并入 MN-20 的 exact registry，解释器也不再保留 per-program 失败记忆，因此重复失败资格的简化点不再保留。退出不删除 MN-20 的显式重试资格、AP-02 的内容验证或 MN-10 的目标降级。

不因按需机制影响范围广而恢复已被拒绝的入服全量下载；数千模型的带宽/驻留成本由[分发决策](../../product-decisions/decisions/model-distribution.md)明确说明。帧预算/preview 的 V-10 由 [AP 简化页](../java-assets-and-presentation/reduction-pivots.md)统一记录；授权例外的收窄条件仍受本主题 [Q-09](open-questions.md#q-09) 约束。
