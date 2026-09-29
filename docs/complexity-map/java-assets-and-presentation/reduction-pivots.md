# 可简化的地方

本页列出由 AP 子系统主导的简化方向。跨子系统支点在各自 owner 页面维护，此处仅作导航。

| 支点 | 最小改变 → 可减少部分 | 剩余义务、成本与最小验证 |
|---|---|---|
| V-10 重新选择帧预算/预览 | 放宽 frame budget 或仅用静态卡片，可分别缩减 MN-13 或 AP-05 的局部状态。 | 前者不能消除所有大动作，且需验证宿主背压；后者改变选模体验，但 AP-04 页面需求与 AP-08 图片取得/export 仍在。中等成本；分别测试 frame/queue 与真实选模价值，不联合修改以免无法归因。 |

相关跨域支点：

- [表示替代规则](../java-models-and-network/reduction-pivots.md)会影响 AP-02 的分层验证，但由 MN 的表示选择主导。
- [显式拥有 archive bytes](../native-capabilities/reduction-pivots.md)可减少 AP-01 的跨域复制时点耦合，但不删除输入一致性。
- [维护与复杂度简化规则](../governance/maintenance.md)定义替代承接和旧路径退出条件。
