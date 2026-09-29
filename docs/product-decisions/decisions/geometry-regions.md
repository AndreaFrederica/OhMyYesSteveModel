# geometry-regions

- Requirement: [REQ.render-model-consistently](../requirements/req-render-model-consistently.md#reqrender-model-consistently)
- Select: 受支持表面语义、艺术技巧例外与输出等价
- Needs:
  - 维护既有作品: [DD.explicit-support-is-stable](model-compatibility.md#ddexplicit-support-is-stable)
  - 骨骼姿态输入: [DD.hierarchical-bone-animation](bone-and-controller.md#ddhierarchical-bone-animation)
- Landing:
  - [architecture](../../architecture/rendering/bake-and-partition.md#四逻辑分区)
  - [architecture](../../architecture/rendering/vertex-output.md#变换与面语义)
  - [architecture](../../architecture/rendering/vertex-output.md)
  - [status](../../status/known-issues/rendering.md#正确性与失败处理)
  - [standard](../../standards/conformance.md#视觉一致性验收)

## DD.authoring-semantics-before-optimization

- Claim: 优化和输出路径变化，必须保持受支持的几何、表面、材质与姿态语义，并维护明确接纳的艺术技巧例外。
- Rationale: 创作者依赖 Blockbench 的受支持效果，不能为内部优化重做作品。负尺寸描边借助剔除优化反向形成艺术效果，属于已明确接纳的典型例外，必须保留其背面外壳。这不把所有类似技巧提升为无条件支持。完整正尺寸不透明块可省去被遮挡的背面；不完整块和透明正尺寸块则须保留可见背面。不同输出路径不能变成需要分别适配的作品格式。

### BC.authoring-surfaces-remain-equivalent

- Claim: 受支持模型的形状、骨骼姿态、UV、正反面和透明语义，应与创作结果一致。Minecraft 的环境光照、游戏特效与 shader 差异，不自动构成逐像素相同的承诺。

### BC.negative-size-keeps-outline-semantics

- Claim: 三轴均为负的有效 Cube（含三轴镜像），保留通过正面剔除形成背面外壳的视觉语义。不能仅因尺寸为负而拒绝、取绝对值或改变可见面。

### BC.culling-uses-original-cube-structure

- Claim: 完整性按 Cube 的原始六面结构判断，不随纹理透明洞或全透明面省略而重定义。完整块的正尺寸资格要求三轴均正，负尺寸资格要求三轴均负。零厚度不获得这两类完整块剔除资格。

### BC.culling-has-explicit-observation-scope

- Claim: 一轴、二轴镜像及从模型内部观察，不属于上述可见面保证范围。三轴镜像属于明确维护的负尺寸正面剔除表现。

### BC.opaque-positive-cube-culls-back

- Claim: 完整、完全不透明的正尺寸块使用背面剔除。

### BC.complete-negative-cube-culls-front

- Claim: 完整负尺寸块维持其正面剔除语义，无论它属于不透明还是透明区域。

### BC.visible-backfaces-are-double-sided

- Claim: 不完整的块和透明的正尺寸块，不剔除其可见背面，以免观察角度改变时作品缺面。

### BC.output-paths-are-visually-equivalent

- Claim: 在相同模型、姿态和宿主绘制条件下，不同受支持输出路径保持几何、正反面、光照方向、透明及材质输入语义等价。
