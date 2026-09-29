# 网络与同步已知问题

本页只记录当前实现与[当前网络协议](../../standards/protocol-v1/README.md)的落地缺口。正常职责见[网络架构](../../architecture/network/README.md)。各条按「现象 → 影响 → 目前处理」组织。

- **现象**：当前协议是 `0.3.0-unstable`，只支持同步构建且没有旧 wire reader。**影响**：真实 Forge 双端的同构建 full/delta/resource/player-state、旧版本 peer 拒绝、secondary-login 单 session 及多人/重连/退出世界组合仍缺机器可判定验证。**目前处理**：协议处于 unstable 状态，不应视为稳定契约；实机组合按项补齐。
- **现象**：Exact-connection model session、四 collection typed publication、metadata/model/presentation 三类 request、七类 typed fragment、本地 assembly/terminal owner、逐项授权、server-private forced selection 和唯一 global dispatch 已通过自动化门禁。descriptorless preview 还覆盖动态 final range、overlap/gap、16 MiB 单图、128 MiB 实收聚合、内嵌/cache/`UNAVAILABLE` 与 icon/cover descriptor 隔离。**影响**：现有 unit/contract 与 thin Forge host 证据不是新 preview 的真实 LAN frame 或完整产品 oracle。**目前处理**：自动化测试先覆盖，真实双端验证后续补齐。
- **现象**：协议有意不维护 catalog revision、缺号检测或 resync。**影响**：exact connection 会丢弃重连前的迟到工作；但同一 active connection 内普通消息的残余跨消息乱序仍可能保留较旧 collection view。**目前处理**：需要实机确认实际表现。
- **现象**：Local、YSM channel 缺席、版本不匹配、intrinsic-default-only fail-closed，以及迟到 typed fragment/player-state/entity/notice、preview replacement 与页面关闭的真实 Forge 排队组合尚未测试。**影响**：边界场景行为未知。**目前处理**：按场景清单补实机覆盖。
- **现象**：独立 Backend 未实现。**影响**：不属于当前验证范围。**目前处理**：见[独立 Backend](../../future/independent-backend.md)。
