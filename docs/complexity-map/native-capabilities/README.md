# Native 能力层复杂度总览

本主题覆盖 JNI 绑定与内存协议、音频媒体解释/decoder、能力相关 bake/cache、逐帧 state、并行 worker 与顶点输出、archive 读取以及 legacy importer。Java 领域 authority、模型资源 lease/音频保留、页面策略和宿主 consumer adapter 分别归 [MN](../java-models-and-network/README.md)、[AP](../java-assets-and-presentation/README.md) 与 [AN](../java-animation-and-integration/README.md)。

本分支新增[独立运行库前置](../../architecture/native-runtime/portable-runtime.md)。默认 archive 已使用 Java 实现，NR-11 的旧 native cache 机制只描述保留的旧 adapter；其余未迁移能力仍适用下文。前置的按能力 provider 和 mandatory Java 基线不改变 Java 领域 authority。

## 阅读路径

| 问题 | 页面 |
|---|---|
| NR 机制的行为与删除失败 | [机制](mechanisms.md) |
| Native 计算、边界和平台的覆盖范围 | [证据、覆盖与根依据](evidence-and-scope.md) |
| JNI、布局、worker、借用和 legacy 的因果关系 | [因果图与牵连候选](causal-graph.md) |
| Cache、archive、补偿机制的简化方向 | [可简化的地方](reduction-pivots.md) |
| Allocator、cache identity 等尚未闭合的前提 | [未决问题](open-questions.md) |

## 子系统拓扑

| 机制簇 | 主要机制 | 职责边界 |
|---|---|---|
| JNI、ABI 与音频 | NR-01–03、14–15 | 一次性绑定、范围/owner 交接、音频解释/decoder、失败收口与 allocator 补偿 |
| Bake、cache 与帧 state | NR-04–07 | SIMD 热布局、serialized cache、借用 frame view 与计划失效 |
| 并行输出 | NR-08–10、16 | 固定区间、完成协议、透明重排和容量空洞清理 |
| Archive 与 legacy | NR-11–13、17 | 借用 extraction、输入边界、typed projection 与图片表示压缩 |

本主题拥有 NR 内部关系。跨子系统关系由[跨子系统关系](../governance/cross-subsystem-relations.md)维护，加载规则见[关系登记入口](../governance/relationship-register.md)。
