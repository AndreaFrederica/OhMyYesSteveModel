# Java 资产与页面复杂度总览

本主题覆盖输入冻结、容器准入、转换交付、音频内容投影、页面短命需求、preview 与显式 export。模型资源生命周期、Catalog/session 与网络传输归 [MN](../java-models-and-network/README.md)。旧格式解码、图片提前压缩和音频 decoder 归 [NR](../native-capabilities/README.md)。动画求值不在本主题范围内。

## 阅读路径

| 问题 | 页面 |
|---|---|
| AP-01 至 AP-09 的机制行为与删除失败 | [机制](mechanisms.md) |
| 本主题覆盖范围与根依据 | [证据、覆盖与根依据](evidence-and-scope.md) |
| 局部因果、删除边界与牵连候选 | [因果图与牵连候选](causal-graph.md) |
| 可实施的机制简化方向 | [可简化的地方](reduction-pivots.md) |
| 尚未闭合的产品判断与证据缺口 | [未决问题](open-questions.md) |

## 子系统拓扑

| 机制簇 | 主要机制 | 职责边界 |
|---|---|---|
| 输入冻结与验证 | AP-01/02/03/09 | 冻结实际解析闭包，分层验证，并对图片和模型音频分别应用用途策略 |
| 转换交付与来源准入 | AP-06/07/09 | staging 重开验证、音频投影与 direct 来源准入不进入 schema reader |
| 页面与 hover 需求 | AP-04/05 | 只保存当前意图，过滤短命浏览，不为浏览触发普通远端下载 |
| Preview 与 export | AP-08 | 独立图片 cache、有限 operation admission 与完整导出 |

本主题拥有 AP 内部关系。跨子系统关系由[跨子系统关系](../governance/cross-subsystem-relations.md)维护，加载规则见[关系登记入口](../governance/relationship-register.md)。
