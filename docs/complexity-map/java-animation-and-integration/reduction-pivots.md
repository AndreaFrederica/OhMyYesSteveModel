# 可简化的地方

本页列出由 AN 子系统主导的调度、姿态、输出与扩展简化方向。跨域后果在相邻子系统页面导航。

| 支点 | 最小改变 → 可减少部分 | 剩余义务、成本与最小验证 |
|---|---|---|
| V-02 同帧计算调度 | 按现有契约处理跨阶段等待；若保留同帧 fork/join，需先明确 Q-01 范围。比较 owner 安全采样/已完成结果、inline 或较少 native 模式，AN-02 的本帧等待及 NR-09 部分 ready/mode 组合可能缩减。 | 不可单删 wait；实体顺序、动作次数、过期结果、lease、固定输出和透明仍在。高成本；多帧结果槽会增加状态且可能改变延迟。先阻塞 worker 检查不消费半帧，再按规模对照性能。Tick 采样+插值需重新裁决当前采样契约。 |
| V-05 可复用 pose 与 draw 参数分开 | 缩小 A-AN-02 中会被 pass 改写的共享输入，AN-04 恢复条件及 context 污染可减少；明确动作提交窗口可缩减 AN-05 的散落门禁。 | AN-01 的实体历史和 AN-05 的动作次数不消失；不同 context 的真实姿态差异必须保持。中高成本；level→shadow→inventory→level、相机变化、隐藏 locator、transition/defer/sync 样本。仅加 wrapper 不算简化。 |
| V-06 统一消费者或计算输出 | 改变 A-NATIVE 的交接方式，或仅保留一种顶点桥，部分 AN-11 adapter 分支可消失。 | NR-01/02/15 仍有 codec/archive/legacy 消费者，AN-12 宿主完整性独立；删 direct 有吞吐/材质代价，删 fallback 缩 consumer 范围。高风险且缺收益数据，当前不优先；先做两路径几何/normal/透明/材质对照。 |
| V-11 收窄扩展或授权例外 | 实际扩展证据支持较窄检查面时缩 AN-09；若 Q-09 拒绝隐式特权，可缩 MN-15。 | 两者相互独立：AN-10 可选降级必留；普通 grants 可能增加自行选择权，不能直接替 forced-only 访问。中高迁移成本；先检查真实 API 变动样本和受限访问/撤权/重连矩阵。 |

V-02/V-06 同时影响 native worker 与输出能力，但实体调度和宿主 consumer 仍由 AN 解释；NR 的能力布局与并行区间不能随 Java 路径变化自动删除。Allocator/fence 的联合核实见 [NR 简化方向](../native-capabilities/reduction-pivots.md)。
