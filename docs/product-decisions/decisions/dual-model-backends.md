# dual-model-backends

- Requirement: [REQ.create-and-select-models](../requirements/req-create-and-select-models.md#reqcreate-and-select-models)
- Select: MC 风格与通用网格并行、外部格式、动画预览及实时物理
- Needs:
  - 运行库与托管基线: [DD.portable-runtime-prerequisite](platform-baselines.md#ddportable-runtime-prerequisite)
  - 作品兼容: [DD.explicit-support-is-stable](model-compatibility.md#ddexplicit-support-is-stable)
  - 可视物理边界: [DD.rendering-only-replacement](visual-gameplay-boundary.md#ddrendering-only-replacement)
- Landing:
  - [future](../../future/general-mesh-backend.md)
  - [status](../../status/general-mesh-backend.md)
  - [authoring](../../architecture/scene-authoring-tools.md)

## DD.parallel-model-backends

- Claim: MC 风格后端与通用网格后端并行。通用网格后端提供独立的模型、动画、预览与实时物理机制，模型加载与可共享基础运行能力属于独立前置 Oh My YSM Lib。新增能力不以替换或削弱现有 MC 模型语义为代价。
- Rationale: 不同创作体系共享网格、骨架和时间等基础概念，但变形、约束、物理与材质行为不同。并行机制允许维护原有作品并完整表达新作品；共享前置使模型能力可以脱离游戏宿主使用。

### BC.general-mesh-preserves-source-semantics

- Claim: 外部模型兼容覆盖明确版本的几何、骨架、变形、动画、约束、材质及实时物理。glTF、MMD、VRM、FBX/BVH 与 Blender、Unity/VRChat 工作流按各自语义处理，不能以静默丢失字段、截断权重或替换变形算法充当完整支持。创作工程通过明确的导出适配接入；未实现行为必须如实说明。

### BC.mesh-runtime-remains-managed

- Claim: 禁用 native 后通用模型的已支持功能仍完整可用，包括动画和实时物理。可选加速不成为模型加载、播放或预览的必需条件。库不持有 Minecraft 对象或游戏 GPU 生命周期。

### BC.mesh-preview-uses-runtime-semantics

- Claim: 动画预览与实际呈现使用相同的模型求值语义。预览支持播放、暂停、时间跳转、逐帧、镜头轨迹和独立会话；物理回放根据历史状态正确重演，多个视图不得重复推进同一实例的模拟。

通用模型的宿主动作应当支持可编辑的 YSM 动作名到来源动画的映射，并接入现有动作轮盘、播放模式、停止、移动打断及服务器授权与分发。动作别名映射不代表传统 MC 骨架曲线已经完成跨骨架重定向；没有对应动作或重定向能力时须明确说明。

游戏状态默认由 YSM 原生控制器和源骨架驱动；允许在逐模型配置中将指定状态显式映射到完整来源动作（例如 VMD），展示动作不受此默认选择限制。状态过渡可指定独立动作片段，须定义播放结束后的目标状态和被新状态中断的行为；过渡片段与姿态混合时长是不同设置。

YSM 原生动作通过源 YSM 骨架与目标骨架的显式绑定进行重定向，人体角色只是初始映射建议，不能限制复杂模型的自定义骨骼。源动画先完成曲线、控制器及宿主输入求值，再换算到目标骨架；动作别名、骨架绑定和单位换算分别可编辑，附加配置应保存这些设置。完整支持不能用基础生成动作替代原曲线，未绑定骨或无法表达的通道须可诊断。

### BC.mesh-in-game-authoring

- Claim: Alt+Y 提供模型编辑入口，在独立预览中编辑单位换算、视觉尺寸、脚底参考、偏移、朝向、人体骨骼映射和动作映射。模型配置保存于对应源模型旁，并随有效模型内容分发；原始模型不被破坏性改写。相机缩放与模型尺寸分开。骨骼映射区分变形骨与 IK 控制骨，歧义或失效引用必须显示。
- Claim: 骨骼绑定应允许单独校准源/目标静止姿态，不能用动画轴修正冒充参考姿态对齐。通用模型支持可配置的左右手物品挂点，包括绑定骨、位置、旋转与物品大小，并允许选择第一人称的原版持物或模型挂点呈现；这些设置保存到逐模型附加配置，原始资产保持不变。第三方武器专用渲染需明确其接入边界。
- Claim: 每次进入编辑器默认关闭预览物理，不创建物理世界或执行预热；仍可检查动画、IK 与 Morph。手动启用时使用独立模拟和明确的时间轴重置/重演语义。该开关只属于编辑会话，不改变已应用模型或持久配置的物理行为。编辑支持草稿、撤销、取消、保存和保存后应用，预览失败不能覆盖有效配置。

### BC.mesh-portable-authoring

- Claim: Lib 提供可脱离 Minecraft 运行的 CLI 和 GUI，用于源模型检查、初步人体骨骼绑定、尺寸/放置与动作映射编辑，以及保留源资产的场景包转换。两种工具和游戏使用同一种逐模型配置及校验语义，支持普通 YSM 的元数据；独立预览应能校验映射后的真实动作，不能仅显示静态模型；默认预览不初始化物理，不能把工程导出或高级材质显示的缺口隐藏成成功转换。

### BC.scene-light-shadow-extension-boundary

- Claim: 灯光和阴影数据须读取、保留并可按时间求值，通过接口提供给其他模组。当前范围不要求 YSM 自行实现其完整效果绘制；缺少效果实现不能伪装成数据丢失或阻断模型、动画与物理功能。
