# device-quality

- Requirement: [REQ.platform-availability](../requirements/req-platform-availability.md#reqplatform-availability)
- Select: 受限设备基础可用性、高端效果、帧率边界
- Needs:
  - 判定最低适配范围: [BC.minimum-platform-baselines](platform-baselines.md#bcminimum-platform-baselines)
  - 评估工作量与性能目标: [DD.main-loop-has-priority](workload-budget.md#ddmain-loop-has-priority)
- Landing:
  - [design](../../governance/verification-policy.md#性能验证)
  - [status](../../status/support-and-verification.md)
  - [status](../../status/known-issues/rendering.md#并发与性能)

## DD.high-end-quality-with-basic-availability

- Claim: 在选定平台范围内，优化优先发挥高端设备的性能与视觉能力，同时为达到最低基线的老旧和移动等受限设备保留基础可用性。
- Rationale: 高端设备承载优质视觉效果的主要优化价值，受限设备则需要能参与基本模型玩法。两者不必承担相同效果与处理成本，基础可用性与高端优化都应保留各自价值。

### BC.basic-device-reference

- Claim: 基础体验观测设备参考为 Intel 第八代桌面 i5、核显和 4 GB 内存，用于比较性能与优化效果。实际最低平台要求由平台基线定义，受限设备保留基本模型使用能力即可。

### BC.basic-availability-preserves-high-end-capabilities

- Claim: 最低基线保障基础可用性，不承诺所有模组内容、高级效果均可用或性能达标。不得为统一到最低基线而削减高端设备的内容呈现能力或性能，也不得阻止高端设备利用较新的平台能力。

### BC.frame-rate-depends-on-content

- Claim: 实际客户端帧率受模型规模、同屏玩家数量与运行环境影响，YSM 不作严格帧率承诺。性能目标用于优化，不能作为任意内容和规模下的帧率保证。

性能观测的软约束与服务端优化目标见[保持游戏正常运行](../requirements/req-preserve-gameplay-and-availability.md)。
