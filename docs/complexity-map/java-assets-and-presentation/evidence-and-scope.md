# 证据、覆盖与根依据

分类、Fact/Inference/Hypothesis、节点与计数口径统一见[建模、证据与计数口径](../governance/modeling-and-evidence.md)。本页限定 AP 子系统的证据范围和所消费的根键。

## 覆盖

| 已建图内容 | 未闭合 / 未检查 |
|---|---|
| Capture、metadata/chunk 验证、图片策略、音频投影、连续页面/hover、离线 3D、staging、direct 准入、preview cache、offscreen 生成与 export；AP-01–09 | 全格式 golden、真实 legacy 音频包装、真实翻页/纹理回收、offscreen 画面与成本未验证；AP-06 以现有架构交付规则为据 |

本主题记录 9 个生产机制。音频 fixture 的 raw/current/projector 局部测试已运行；完整来源矩阵、真实 GUI、GPU texture 回收和画面结果仍受[支持与验证边界](../../status/support-and-verification.md)限制。

## 证据入口

机制事实、直接删除失败和局部未闭合项由[机制正文](mechanisms.md)逐项维护。AP 内部边由[本地因果图](causal-graph.md)维护，跨域边进入[跨子系统关系](../governance/cross-subsystem-relations.md)。

## 根依据

本主题消费以下根键。定义与权威链接只在[根依据总表](../governance/root-register.md)维护。

| 类别 | 本地使用的键 |
|---|---|
| 产品 / 外部约束 | `D-SOURCE`、`D-JOIN`、`D-CHUNKS`、`D-META`、`D-VERIFY`、`D-IDENTITY`、`D-FREEZE`、`D-MIGRATION`、`D-IMAGE`、`D-SOUND`、`D-GUI` |
| 架构选择 | `A-CAPTURE`、`A-IMAGE`、`A-PAGE`、`A-PREVIEW`、`A-CONTINUOUS-DEMAND`、`A-ONE-ARTIFACT`、`A-DIRECT-ADMISSION`、`A-PREVIEW-CACHE`、`A-WORK` |
| 跨子系统前提 | `A-ARCHIVE` / NR-11 的借用覆盖会增加 AP-01 的及时复制约束 |

产品键只摘要上游判断，不授权本主题重新定义产品行为。
