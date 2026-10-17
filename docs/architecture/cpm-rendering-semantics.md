# CPM 原始渲染语义总览

这份文档先描述 Gamepiaynmo 两个 Mod 自己如何从模型包走到顶点提交，再描述 YSM 应该在哪些边界接入。它是实现前置文档；任何转换公式都必须能在这条调用链上找到对应的上游操作。

源码依据：

* Legacy `CustomModel`：`handoff/CustomModel`，主要入口为 `RenderPlayerHandler`、`CustomJsonModel`、`Bone`、`Box`。
* Modern `CustomPlayerModel`：`handoff/CustomPlayerModel/shared`，主要入口为 `ModelLoader`、`ModelPack`、`ModelInstance`、`ModelBase`、`ModelBone`、`ElementParser`、`Skeleton`。

## Legacy CustomModel 的完整链路

### 包载入和静态构造

1. `ModelPack` 找到 `model.json`，读取模型元数据、变量、`skeleton`、`bones`、`fpLeft`/`fpRight`/`fpList` 和附加功能。
2. `Bone.getBoneFromJson` 按文件中的顺序创建骨。父骨必须已经登记；缺省父骨是 `PlayerBone.NONE`。
3. `Box.getBoxFromJson` 立即把 `coordinates=[x,y,z,w,h,d]` 转为 ModelRenderer 顶点，使用 `[-x-w,-y-h,z-d] .. [-x,-y,z]`，同时建立六个面的 UV。`mirror` 会交换 X 顶点并翻转面。
4. `Bone.compile` 把 box 和 quad 编译成 OpenGL display list。纹理尺寸来自当前骨的纹理；骨没有自己的纹理时沿父骨查找。

静态 box 顶点不包含骨骼 position、rotation、scale，也不包含玩家宿主动作。它们要等到每次姿态更新时通过矩阵变换应用。

### 每次实体渲染前的宿主准备

`RenderPlayerHandler.renderModel` 先取得原版 `ModelPlayer`，调用原版模型的 `render` 以建立玩家动作骨架，然后：

1. `setModelPose` 把当前玩家动作写入 `PlayerBone` 对应的原版 ModelRenderer。
2. `CustomJsonModel.updateSkeleton` 读取 Legacy `skeleton` 表达式，对宿主骨执行 `setRotationPoint(-x,-y,z)`。
3. 普通渲染调用 `CustomJsonModel.render(transform, context)`；发光 pass 单独调用 `renderEmissive`。
4. 渲染结束后 `resetSkeleton` 恢复原版宿主点，避免污染下一名实体。

Legacy 默认宿主点来自 1.12 `resetSkeleton`：head/body 为 `(0,0,0)`，左右臂为 `(+5,2,0)`/`(-5,2,0)`，左右腿为 `(+1.9,12,0)`/`(-1.9,12,0)`。这套定义不使用 Modern 的 24 像素根。

### Legacy 姿态矩阵

`CustomJsonModel.update` 的顺序是：

1. 先计算每个 `PlayerBone` 的当前宿主矩阵。
2. `Bone.update` 求值表达式：
   * `position=[x,y,z]` 变为 `[-x,-y,z]`；
   * `rotation=[x,y,z]` 转为弧度；
   * `scale`、visible、颜色、透明度、emissive 和 physics 逐项求值。
3. 普通骨使用 `parentCurrent * bone.getTransform()`。
4. 物理骨使用上一帧和当前帧矩阵插值，并在 tick 阶段更新速度和方向。
5. 根骨的世界矩阵乘上渲染入口传入的 `baseMat`。

`IBone.getQuaternion` 的旋转顺序是 `Rz(z) * Ry(x) * Rx(y)`；不能把 Legacy 三个角直接当成 Bedrock bone rotation。

### Legacy 最终提交

对普通 pass，`CustomJsonModel.render` 遍历 `bones`：

1. 检查父级可见性和当前骨 visible。
2. 绑定当前骨解析出的纹理。
3. 乘上该骨的完整世界矩阵。
4. 调用 display list，提交 box 和 quad 顶点。
5. 非发光 pass 还渲染骨上的 item；粒子在 tick 中由骨矩阵驱动。

第一人称并不重新建立一棵树：`renderArm` 使用左/右 arm 的根选择列表，`renderFp` 使用背景列表；所选骨仍使用原 parent 矩阵。原版第一人称手臂 ModelRenderer 也会单独提交一次。

## Modern CustomPlayerModel 的完整链路

### 包载入

`ModelLoader.loadFromEntry` 扫描包：根 `model.json`、其他 JSON 几何部件、png/jpg/bmp 纹理和 `animation.js`。它先注册纹理，再解析主文件：

* `parts` 把几何文件、纹理名和 `double_face` 关联；
* `skeleton` 记录 skeleton 类型、参数、attachments、左右第一人称列表和 eye bone；
* 根 `particles`、`items`、`addons` 建立独立实例定义；
* `size`、`eye_height`、`default_scale` 进入 ModelInfo；
* `animation.js` 被编译，但此时只取得 `init`、`update`、`tick` 函数。

`BedrockModelParser` 只负责几何外壳选择：`format_version < 1.12` 读取 `geometry.*`，新格式读取 `minecraft:geometry[]`。两种外壳随后都交给 `ElementParser`。

### Modern 静态几何构造

`ElementParser.parseBone` 保存 authored `name`、`parent`、绝对 `pivot`、rotation 和 cubes。`parseBox` 为每个 cube 创建 Minecraft `ModelRenderer`，并按以下规则设置：

* cube pivot 相对 bone pivot；
* origin/size 决定局部顶点；
* cube rotation 写入 `yRot=source.x`、`xRot=source.y`、`zRot=source.z`；
* UV、inflate、mirror 和退化面规则在 ModelRenderer 中保存。

`ModelBone.instantiate` 才做骨层级实例化：

1. `_c` 且没有 parent 时尝试绑定去掉 `_c` 的同名宿主骨；
2. 有自定义父骨时 `position = authoredPivot - parentAuthoredPivot`；否则减 `(0,24,0)`；
3. 执行 `(x,-y,z) * 1/16`；若父级是宿主骨，再减宿主骨 position；
4. bone rotation 保存为 `(source.y,source.x,source.z)`。

因此 Modern bone rotation 和 cube rotation 必须是两个适配通道。

### Modern 实例化和宿主 skeleton

`ModelPack.instantiateModel` 的顺序是：

1. 实例化所有 ModelPart、ModelBone、ParticleEmitter 和 ItemModel；
2. 实例化 `EnumSkeleton`，注册 head/body/arms/legs 等宿主骨；
3. 根据 `attachments` 或 skeleton 默认附件把模型骨加入 attachment 集合；
4. 根据 `skeleton.left_arm`/`right_arm` 或默认列表构造第一人称骨集合；选择某个根时，同时选择它的后代，但不改 parent；
5. 执行脚本 `init`；
6. 建立 physics 矩阵初始状态。

Modern biped 宿主 pivot 来自 `CustomBipedModel` 的 `24 - pivotHeight`、arm interval、leg length 和各 offset。`size`/`eye_height` 不参与 cube 顶点计算。

### Modern tick、update 和物理

* `ModelInstance.tick` 先调用 skeleton tick，再调用脚本 tick/update；物理骨使用上一帧矩阵、当前矩阵、实体运动和 elasticity/stiffness/damping/friction/gravity 更新 current 矩阵；粒子 emitter 同步更新。
* `ModelInstance.update` 计算当前 model-view 矩阵，调用 skeleton update，再对所有骨执行 `bone.update`，生成 partial 矩阵。
* `ModelBase.update` 在每帧调用脚本 `update(entity, model)`，脚本修改的是骨实例状态；渲染阶段只读取已经求值的矩阵。

脚本阶段、物理阶段和静态几何阶段不能合并，否则会重复应用 authored rotation 或把物理结果烘焙进静态模型。

### Modern 最终提交

`ModelBase.render` 调用 `update` 后进入 `doRender`：

1. 根据实体可见性为每个 part 选择 RenderType 和纹理；
2. 对 part 的每个 bone 检查选择集合和层级可见性；
3. 乘骨的 partial 矩阵；
4. 调用 ModelRenderer.render，提交 cube 顶点和 UV；
5. 再按父骨矩阵渲染 item model。

`ModelInstance.renderFirstPerson` 设置 `isRenderingFirstPerson`，用零实体动作更新一次 skeleton，然后只把对应手的选择集合传给 `doRender`。这就是 Modern 第一人称的真实 pass；它不是把模型缩放或重挂到手臂。

## 两代都必须保留的渲染阶段

YSM/ysmlib 的接入必须保持以下边界：

```text
包资源与纹理
  -> source parser
  -> authored geometry / feature IR
  -> host skeleton instantiation
  -> init
  -> tick / physics
  -> per-frame update / script
  -> partial pose matrices
  -> first-person or full-body selection
  -> texture/material pass
  -> cube/quad vertex submission
```

Legacy 和 Modern 只能在“公共生命周期接口”处汇合；以下内容必须保持独立：

* root/host pivot 定义；
* authored position 与 pivot 的解释；
* bone rotation 与 cube rotation 的轴映射；
* first-person 选择规则；
* texture/UV 和面方向；
* physics 矩阵更新；
* 原版宿主动作注入方式。

### UV 边界的不可变约束

两种 CPM 渲染器都把盒子的 `dx/dy/dz` 原值传给 `ModelBox`/`Box`，再用这些值计算六个面的 atlas 端点。转换器不得对尺寸使用 `floor`、`round` 或整数化；现代包中 `4.9995`、`0.001` 和负 `inflate` 都是合法输入，截断尺寸会把 UV 端点移到相邻区域。离线采样也必须把 `u/width`、`v/height` 视为 texel 边界，使用边界内的 texel（floor），不能用 round 把 `u2/v2` 采到下一个 atlas texel。

只有按这条完整链路建立 source oracle 和阶段快照后，离线渲染才有资格作为回归测试。截图本身只能显示最终差异，不能替代对 CPM 原始渲染语义的分析。

## 当前离线 source renderer

`tools/cpm_source_render.py` 直接读取原始模型目录，不读取 YSM snapshot，也不调用 Minecraft。它按上面的两条独立链路输出：

```text
python tools/cpm_source_render.py <model-directory> --kind legacy -o build/cpm-source-renders/legacy.png
python tools/cpm_source_render.py <model-directory> --kind modern -o build/cpm-source-renders/modern.png
```

同时生成同名 `.json`，其中包含每个 primitive 的世界八角点、纹理符号、UV、mirror/double_face 和第一人称选择集合。当前输出是 neutral standing/rest pose；Legacy 表达式分支、Modern `animation.js`、tick physics 和实体动作需要接入独立 pose oracle 后再加入帧序列，不能在渲染器里猜测。

离线相机保留 CPM/ModelRenderer 的“+Y 向下”模型坐标，再把它映射到屏幕；贴图光栅器按上游 `TexturedQuad` 的顶点顺序 `(u2,v1),(u1,v1),(u1,v2),(u2,v2)` 采样，而不是把所有面当作同一个顺序的矩形。这个顺序和六个面的顶点排列是渲染正确性的必要条件。

## 2026-10-08 offline verification finding

`CpmModernRenderPoseAdapter` writes attributes from the complete Modern ScriptBone pixel position, and `JavaModelState` mode 2 consumes those attributes as the exact pivot-relative matrix. The renderer script previously used only static baked rotation/translation and ignored `neutralPose`; therefore any discrepancy after canonical geometry was a world-transform reconstruction error. The offline renderer must reproduce the adapter attribute equations and the mode 2 `translate/rotateZYX/scale/translate` order directly.

### 检查器矩阵分叉（2026-10-08，修改前记录）

已核对两代上游的 IBone/IModelBone.getTransform；compare 仍复制旧矩阵且未消费 Modern pose，必须与 actual renderer 共用 dump world 计算。此共用仅限 YSM 消费端，Legacy/Modern 适配规则仍独立。synthetic host 无 pose 时必须保留 AnimatedGeoBone 默认 attributes，不能把六个 host alias 应用到整条 synthetic grouping 链。Legacy source vertices 已是 ModelRenderer 坐标，比较时不能额外反射 X；Modern 的 GeoBuilder 才额外反射 X。
2026-10-09 Modern world diagnostic: Yukari actual dump 的 body_c 静态 baked corners 可与 source 对齐，但当前 neutralPose 经 adapter/JavaModelState mode 2 后 body_c 仍有 20 px 平移，head_c 仅约 0.354 px 角点误差；这属于 pose/world transform 边界，不是 UV 或 texture sampling。旋转骨同时有最高约 40.494 px 的静态 rest-rotation 误差，必须继续按上游 bone/cube rotation 语义修复。

## 2026-10-09 Modern matrix boundary correction

`JavaModelState` mode 2 使用 `T((pivot-attrX)/16,(pivot+attrY)/16,(pivot+attrZ)/16) * Rz(attrZ) * Ry(attrY) * Rx(attrX) * S * T(-pivot/16)`。这里 pivot 与 attr 必须在同一父局部坐标系。Modern adapter 的 position 编码为 `attr=[-scriptX,-scriptY,scriptZ]`，矩阵平移即 `[scriptX,-scriptY,scriptZ]/16`，对应上游 `(x,-y,z)`。Modern rest rotation 只能在 pose 通道进入该矩阵一次，不能同时保留静态 rest rotation 和 pose delta。

### 2026-10-09 offline atlas/mirror verification finding

Rendering correctness includes the last two stages after world transforms: atlas sampling and face orientation. The offline contract therefore compares source image texels against the exact atlas region and checks each baked face normal as a finite semantic axis vector; cross-product winding is reported separately because mirrored up/down faces may intentionally differ. This keeps texture and mirror failures separate from parser, canonical, adapter, pose, and world-transform failures.

### 2026-10-09 UV endpoint mapping refinement

Texture sampling verification now has two independent parts: source texel equality in the atlas and per-face UV endpoint equality after atlas translation. A rendered image that differs from the source is therefore assigned to UV remap or texture sampling, rather than being conflated with world geometry.

### 2026-10-09 cross-model finding: Legacy sizeAdd and UV semantics

Legacy rendering has separate geometry inflation and UV dimensions. `sizeAdd` belongs to the vertex bounds, while the upstream `Box` polygons retain base dimensions for texture rectangles. Cross-model verification must preserve this distinction.

### 2026-10-09 cross-model finding: pose/world failures

The expanded model set separates texture correctness from transform correctness: Allay wings and Cirno ice branches fail world corner comparison with valid parser/canonical and UV/normal stages. The renderer must report these as pose/world failures.

### 2026-10-09 Legacy source-oracle expression boundary

核对上游 `CustomModel/.../expression/FunctionType.java` 后确认 `todeg` 与 `torad` 是 Legacy 表达式语言的正式函数；`Bone.update()` 在渲染前求值 rotation。离线 source renderer 此前没有实现这两个函数，`-todeg(cos(age*0.3)*0.2+1)` 因异常回退为零，导致 Cirno 的 `wingRight/wingLeft` 子树在 source/YSM 比较中被错误归因于 world transform。该误差发生在 source parser/oracle 的表达式求值阶段，不能修改 Legacy adapter 或添加模型偏移。

### 2026-10-09 face UV/material orientation finding

离线 PNG 与上游 `CustomModel.Box`/`TexturedQuad` 面逐像素检查发现，几何角点集合可以通过而材质仍会贴到错误的面：`GeoBuilder.addBoxQuads()` 对所有格式执行 X 反射，导致 emitted west/east 面的顶点序列与上游 Box 的 `xmax/xmin` 序列不同。原 checker 按 emitted face 序号直接套用 source face 名称，因此只验证了 UV 数值存在，未验证“UV 角点随物理面顶点移动”。修复边界属于 YSM adapter 的面 UV 编码和 texture sampling verification；不得通过模型偏移或镜像整个模型掩盖。每个 box 面现在必须同时满足：物理顶点集合、上游面 rectangle、emitted 顶点顺序对应的 UV corner sequence、atlas 像素采样。

### 2026-10-09 mirror normal contract refinement

GeoBuilder stores semantic axis normals independently of emitted vertex winding. Mirrored boxes, especially up/down faces, may have a cross product with the opposite sign; verification therefore checks finite unit axis normals and mirror direction data, while UV corner order remains a separate gate.

### 2026-10-09 full-library verification execution

全库验收使用 `CpmYsmBatchDump` 一次启动 Java 17 JVM，逐模型输出实际 baked dump；Python 随后逐模型运行 source oracle、world corner、UV、mirror 和 atlas gates。单模型 Gradle 重复启动不属于验证语义，不能作为全库失败原因。批量报告保留 dump/source/compare 的阶段失败，并生成 Legacy/Modern 分开的 contact sheet。

### 2026-10-09 Modern authored-name collision finding

Modern source bone names are case-sensitive and may legally be `body`, `head`, or another vanilla-looking lowercase identifier. The upstream `ModelBone` lookup resolves the model bone namespace before the skeleton namespace, so a custom `body` parent remains the custom bone. Canonical conversion must therefore rename only exact canonical host names and must preserve a parent reference when that exact source name exists. Unconditionally applying `canonicalHost("body") -> "UpperBody"` changes the source hierarchy and is a canonical-converter error.

### 2026-10-09 Modern init visibility finding

Modern `animation.js:init()` is part of the neutral pose contract. The Amaterasu upstream script hides `sword`, `bullet`, and `bulletcolar` during init; a source preview that renders every parsed cube is therefore not a neutral runtime preview. The actual pose session already carries `visible`, so the baked-dump contract must preserve that flag and the offline YSM renderer must omit hidden bones. Dropping visibility after pose evaluation creates a source/YSM visual discrepancy at the pose/render-consumer boundary.

## 2026-10-09 neutral visibility and texture resolution

The render pose is hierarchical: a hidden parent hides all descendants. Source and YSM offline renderers now apply this same hierarchy before rasterization. UVs are authored in CPM logical texture dimensions; when the PNG has a different physical size, Minecraft normalizes by the logical dimensions and samples the physical image at the resulting scale. Atlas remapping must preserve that scale per texture region. A mismatch here is a texture-sampling/UV stage error, independent of geometry matrices.
# 200-Ori Modern rendering finding

For Modern transformMode 2, the render oracle must consume a freshly generated actual dump.  The dump's parent graph and neutralPose are authoritative for the YSM side; source rendering may use raw pivots only to construct the independent upstream oracle.  If a stale dump reports `right_arm_c` under `LeftArm`, the resulting 5 px arm and 24 px leg errors are a world-transform diagnostic, not evidence for a per-model translation.  The verification step must regenerate the dump after converter changes and compare parent identity before comparing cube corners.
### Parent graph at render time

World matrices are evaluated only after the source and baked parent edges have
been compared.  This keeps a topology error separate from pose or world-matrix
errors and prevents a visually plausible matrix from masking a wrong parent.

### Texture coordinate scale

Legacy UV offsets are in the selected texture's pixel coordinate system. The normalization into the generated atlas occurs only after that image size is known; a missing per-bone `textureSize` is not evidence for a 64x64 texture.

### Cube pairing in render verification

World-corner comparison may use a symmetric point-set metric for vertex order, but cube identity comes from the source/baked emission order. A center-only pairing is not a rendering transform and cannot be used when two cubes share a center.

### Atlas logical dimensions

Atlas UV remapping has two dimensions: the physical image rectangle and the adapter's logical cell. The latter is clamped to 64 pixels for YSM atlas layout and is distinct from a Modern model's declared texture size.

### Modern non-biped pose boundary

The neutral host attribute channel is skeleton-specific. `quadruped` exposes
`head`, `body`, and four leg hosts; `slime` and `zombie` expose their own
`body`/biped-compatible host set through the upstream skeleton. A Modern
render pose adapter that seeds only biped host points produces a world-transform
error for valid `*_c` roots even when parser and cube conversion are correct.
The dump and offline renderer must carry the active skeleton type and use the
same host namespace and neutral attributes as the upstream skeleton.

### Modern root transform boundary

A root name is not a locator or attachment declaration. Ordinary roots enter the world matrix at the identity parent; attachment metadata controls item slots and does not rewrite the bone graph. Canonical host name collisions are represented by the adapter's emitted name mapping.
