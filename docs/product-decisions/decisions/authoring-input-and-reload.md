# authoring-input-and-reload

- Requirement: [REQ.create-and-select-models](../requirements/req-create-and-select-models.md#reqcreate-and-select-models)
- Select: Blockbench 创作体系、添加模型与热加载
- Needs:
  - 加载用户来源: [DD.user-model-sources-are-readonly](artifact-storage.md#dduser-model-sources-are-readonly)
  - 热加载发布: [DD.single-current-catalog](catalog-publication.md#ddsingle-current-catalog)
  - 修改已支持作品: [DD.explicit-support-is-stable](model-compatibility.md#ddexplicit-support-is-stable)
  - 内容无效: [DD.local-failure-degradation](failure-isolation.md#ddlocal-failure-degradation)
- Landing:
  - [standard](../../standards/model-schema/assets-and-validation.md)
  - [architecture](../../architecture/model-management/reload-and-publication.md)

## DD.bedrock-creator-ecosystem

- Claim: MC 风格模型采用基岩版的体素几何、Molang、动画与动画控制器创作体系。通用网格创作工作流依据[双后端决定](dual-model-backends.md)，不改变已支持的基岩创作语义。
- Rationale: 体素美术风格契合 Minecraft，基岩版已有设计经验和创作者生态，Blockbench 提供成熟建模工具。复用这些基础降低创作、学习与维护成本。

### BC.supported-bedrock-content-is-authorable

- Claim: 创作者能够把 Blockbench 等工具制作的受支持 geometry、动画、控制器和纹理组织成模型。采用基岩版体系不自动承诺其全部特性。

## DD.custom-content-hot-load

- Claim: 玩家添加和创作者迭代模型后，可以在游戏进程内热加载该内容。
- Rationale: 自定义模型和创作调试需要反复修改与观察。每次变化都重启游戏会显著增加日常创作和使用成本。

### BC.model-edit-can-reload-in-game

- Claim: 已完成验证的新增或更新模型可以通过热加载进入当前可用模型列表与后续使用。无效内容不破坏已有游戏状态。
