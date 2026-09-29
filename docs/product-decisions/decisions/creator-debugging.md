# creator-debugging

- Requirement: [REQ.create-and-select-models](../requirements/req-create-and-select-models.md#reqcreate-and-select-models)
- Select: 游戏内模型与动画调试反馈
- Needs:
  - 调试失败或副作用: [CON.normal-use-isolation](../shared.md#connormal-use-isolation)
  - 模型迭代后使用: [DD.single-current-catalog](catalog-publication.md#ddsingle-current-catalog)
- Gap: 缺少调试能力的专门架构落点。
- Landing:
  - [status](../../status/support-and-verification.md)

## DD.creator-debug-feedback

- Claim: 模组提供模型与动画调试能力，供创作者验证作品在游戏中的实际表现。
- Rationale: 模型会组合骨骼、动画、控制器与游戏状态，仅看源文件不足以定位游戏内表现问题。直接观察与调试能缩短修改反馈周期。

### BC.debug-model-and-animation

- Claim: 创作者能够在游戏中调试模型和动画，并得到足以定位内容问题的反馈。调试功能仍服从游戏数据与无关玩法的保护边界。
