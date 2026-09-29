# 证据、覆盖与根依据

分类、证据等级、节点与计数口径统一见[建模、证据与计数口径](../governance/modeling-and-evidence.md)。

## 覆盖

| 已建图内容 | 未闭合 / 未检查 |
|---|---|
| Entity、异步/多 pass、动作门禁、defer 排空、模型声音 handoff、混合补丁、checker、输出桥；AN-01–06、08–13、19–20 | Forge/联动、第三方线程安全、完整数值/视觉、真实 OpenAL/设备容量未验证；AN-19 在 Forge 实机中的排空时机未核验 |

本主题记录 14 个生产机制。声音 handoff 双序已有局部自动化；Forge once-only、真实 host adoption/容量、第三方 consumer、真实多 pass 画面和跨线程可见性仍需运行验证。

## 证据入口

[机制正文](mechanisms.md)逐项记录 Fact、Inference、Hypothesis 与直接删除失败。[因果图](causal-graph.md)维护 AN 内部边，跨域边进入[跨子系统关系](../governance/cross-subsystem-relations.md)。

## 根依据

本主题消费的根键定义与权威链接见[根依据总表](../governance/root-register.md)。

| 类别 | 本地使用的键 |
|---|---|
| 动画产品约束 | `D-ENTITY`、`D-EFFECT`、`D-SOUND`、`D-SAMPLE`、`D-CONTROLLER`、`D-VANILLA-ACTION`、`D-AN-FALLBACK` |
| 失败与宿主边界 | `D-EXT`、`D-OUTPUT`、`D-HOST`、`D-LOOP` |
| 架构选择 | `A-AN-01`、`A-AN-02`、`A-AN-03`、`A-AN-04`、`A-NATIVE`、`A-DEFER` |
| 未闭合根 | `H-BLEND`、`H-FENCE` |

模型资源 lease、native view 与 worker completion 是跨子系统前提，不转移到 AN 成为第二 authority。
