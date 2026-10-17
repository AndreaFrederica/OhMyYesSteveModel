# CPM 文档驱动验证契约

这份契约把 `cpm-format-semantics.md` 中的源码事实变成实现必须满足的检查。它定义“转换正确”的判定方法；离线模型批处理只用于把真实语料代入这些检查，不负责定义正确性。

## 证据顺序

每一条规则必须按下面顺序维护：

1. 上游源码文件和行号是语义证据。
2. 本文的公式是不变量。
3. Lib source IR、YSM adapter 和 pose session 各自提供断言。
4. 最后才用归档模型做回归渲染。

如果截图和断言冲突，先定位断言对应的源码证据；禁止通过给单个模型添加偏移来消除截图差异。

## 静态几何契约

### Legacy CustomModel

来源：`CustomModel/.../Bone.java`、`Box.java`、`RenderPlayerHandler.java`。

对每个 bone：

* source `position=[x,y,z]` 在上游更新后必须是 `[-x,-y,z]`，并保持父级局部变换。
* source `rotation=[x,y,z]` 必须保持上游 `Rz(z) * Ry(x) * Rx(y)` 的顺序。
* `coordinates=[x,y,z,w,h,d]` 的包围范围必须是
  `[-x-w,-y-h,z-d] .. [-x,-y,z]`；`sizeAdd` 和 `mirror` 只能改变这一步。
* 自定义骨的 authored pivot 必须为零；position 只能由 Legacy pose branch 在运行时消费一次。
* 宿主 pivot 必须来自 1.12 `resetSkeleton`；不得套用 Modern 的 `24-height` 公式。

### Modern CustomPlayerModel

来源：`BedrockModelParser.java`、`ElementParser.java`、`ModelBone.java`、`CustomBipedModel.java`。

对每个 geometry：

* `format_version < 1.12` 只能从 `geometry.*` 对象读取；`>= 1.12` 只能从 `minecraft:geometry[]` 读取。
* bone `pivot` 是 authored 的绝对像素坐标；实例化时才减父 pivot 或 `(0,24,0)`，再执行 Y 反转和 `1/16` 缩放。
* bone rotation 与 cube rotation 必须走两个函数。bone 先按 `ModelBone.instantiate` 交换 X/Y；cube 按 `yRot=source.x, xRot=source.y, zRot=source.z`。
* cube 的 origin/size/pivot 必须按 `ElementParser.parseBox` 计算，不能把 origin 当作已经相对父骨的坐标。
* 名称以 `_c` 结尾且无 parent 时，必须绑定去掉 `_c` 的宿主骨。
* `default_scale` 只影响 mesh scale；`size` 和 `eye_height` 不得改变几何顶点。

## 动态姿态契约

* parser 只保存 source expression/script；`init`、`update`、`tick` 的执行只能发生在对应 pose session 阶段。
* Modern pose session 输出 authored rest rotation 的增量，不能再次输出静态 authored rotation。
* Legacy pose session 输出完整的 source position/rotation/scale，Legacy render branch 只能消费一次。
* Legacy 的 source rotation 通道在进入 `JavaModelState.rotateZYX` 前交换 X/Y；Modern 不得复用这个映射。
* physics、particles、items、attachments 具有独立的 IR 和生命周期；它们不能通过几何转换器中的隐式偏移实现。

## 第一人称契约

第一人称列表表示“选择根骨及其后代”，保留原 parent 关系。验证器必须检查：

* Legacy `fpLeft`、`fpRight`、`fpList` 与 Modern `skeleton.left_arm`、`right_arm` 分别映射到对应 locator；
* 列表之外的骨不会因为被选择的骨而整棵模型重复绘制；
* 左右臂和背景 pass 的选择集合稳定，缺失成员只产生诊断。

## 必须输出的阶段快照

每个测试输入都必须能输出以下可比较数据：

```text
source: 原始字段和来源类型
parsed: source IR 中的 parent/pivot/rotation/cube/uv
compiled: YSM bone parent name + sorted parentIndex/cube/transformMode/locator
pose: age=0,10,20 的动态 position/rotation/scale
world: 每个 cube 八个角点和面法线
texture: atlas 尺寸、每个面的 UV 区间和 mirror/double_face
```

比较必须在 `source oracle -> parsed -> compiled -> pose -> world` 的每个边界进行。只比较最终截图不足以区分 parser、adapter、pose 或相机投影的错误。

## 验收门槛

修改 CPM 转换后，必须先通过：

1. Legacy、Modern 旧 geometry、Modern 新 geometry 各一组公式单测；
2. 上述五阶段快照的数值比较；
3. 纹理 atlas UV 和镜像/双面检查；
4. 本地归档全量读取和姿态运行；
5. 离线渲染 contact sheet 作为人工回归材料。

任何一项失败都只能修复对应契约或上游语义，不能给单个模型加特判偏移。模型包数量增加时，只增加语料覆盖，不改变判定标准。

## 当前缺口

当前 `tools/cpm_source_render.py` 是按源码公式重写的 CPU 预览器，**不是原始 Mod 的实机截图，也尚不是完整的独立 oracle**。2026-10-08 用户指出左右两侧均错后，确认旧图不能作为静态几何和贴图基准：三角形插值返回 C/B/A 权重，却按 A/B/C 采样 UV 和深度；canonical 旧预览把贴图裁片拉伸至屏幕包围框；Legacy 表达式缩放和快照 scale 字段存在遗漏。

### 2026-10-08 离线修复的前置检查

1. 光栅器在 A/B/C 三个顶点必须分别返回 `(1,0,0)`、`(0,1,0)`、`(0,0,1)`；同一平面沿对角线拆分后不得出现纹理或深度接缝。这是数学不变量，不由模型截图反推。
2. `CustomModel/Box.java` 构造函数的六组顶点与 `Quad.java` 构造函数的 UV 必须成对保存；inflate 改变位置，不改变用于 atlas 展开的原始宽高深。显式 textureSize 是归一化分母，无此字段时 `Bone.getTextureSize()` 取实际图片尺寸。
3. `CustomJsonModel.fromJson()` 的 parent 按 source order 解析，重复 ID 不得被 Python 字典折叠。注意原版 `update()` 和 `render()` 还用 authored ID 索引矩阵表：内部唯一名只能用于报告，不能据此声称重复 ID 的全部运行语义已经复现。
4. `Bone.update()` 求值 scale（包括 `4/3`）、position、rotation、visible；快照必须输出这些字段。人工补入 scale 的旧 JSON 仅为诊断实验，不计作 Java 实际导出证据。
5. source 公式生成的顶点与 canonical 模拟生成的顶点一致，只能证明两个 CPU 路径相符，不能替代实际 Java bake/render 或原版 Mod 的顶点捕获。不得把 canonical 侧换成 source 算法后宣称转换通过。

后续仍需实际 bake/render 的独立顶点捕获、完整表达式/脚本与 tick physics oracle；旧图与 v6 实验图均不列入验收产物。

### YSM 离线图的边界（2026-10-08）

`tools/cpm_offline_render.py` 当前接收的是 `CpmOfflineSnapshot` 的 canonical IR。它在 Python 中重新实现一个 `bone_local()`，因此输出的是“canonical 近似图”，不是 YSM 的实际图。这个函数没有执行 `GeoBuilder.build()`、`RuntimeGeometryMapper` 和 `JavaModelState.extract()`，也没有读取 YSM `Cubes.cubesLegacy` 中已经烘焙的顶点。

这会产生一个确定的错位来源：上游 Modern `ModelBone.instantiate()` 会在实例化时执行以下步骤，而 canonical 快照没有这些运行时结果：

* 无 parent 的 `*_c` 骨绑定到去掉 `_c` 的宿主骨；
* 子骨位置减去父骨 authored pivot，根骨位置减去 `(0,24,0)`，再做 `(1,-1,1)/16`；
* bone rotation 先交换 X/Y，cube rotation 另由 `ElementParser.parseBox()` 设置到 `ModelRenderer`；
* YSM `GeoBuilder` 还会反射 X、把 cube 顶点写入 1/16 render units，并在运行时以 `transformMode=2` 消费绝对 pivot。

因此“Legacy/Modern CPM source 图正确、YSM 离线图错误”并不能证明 CPM 文件错误，也不能证明 adapter 已经通过；它首先证明离线 YSM 图走了另一套近似变换。验收必须改为：先导出 `CpmYsmModelAdapter.load()` 产生的真实 `GeoModel`（bone parent/pivot/rotate、每个 `CubeLegacy` 的 pos/indices/UV），再按 `JavaModelState` 的对应 transform mode 计算 world corners，最后和 source oracle 逐 primitive 对比。没有这份实际 YSM baked dump，任何 YSM 离线 PNG 都只能标为诊断图，不能用于判断转换是否正确。

## 2026-10-08 verification contract addition

For each Modern and Legacy sample, the dump must contain a non-empty neutral pose for every runtime bone returned by the corresponding independent pose session. Offline rendering must compare source/YSM cube values after parser, canonical conversion, adapter/pose, and world-transform stages separately; UV atlas coordinates and texture samples are checked independently. A passing PNG alone is insufficient, and client JAR compilation remains blocked until these numeric checks pass.

### 检查器矩阵分叉（2026-10-08，修改前记录）

已核对两代上游的 IBone/IModelBone.getTransform；compare 仍复制旧矩阵且未消费 Modern pose，必须与 actual renderer 共用 dump world 计算。此共用仅限 YSM 消费端，Legacy/Modern 适配规则仍独立。synthetic host 无 pose 时必须保留 AnimatedGeoBone 默认 attributes，不能把六个 host alias 应用到整条 synthetic grouping 链。Legacy source vertices 已是 ModelRenderer 坐标，比较时不能额外反射 X；Modern 的 GeoBuilder 才额外反射 X。
2026-10-09 verification finding: 最新实际 dump 比较必须同时报告可比较时的 staticBakeError 与 final cornerError。Modern Yukari 当前 maxStaticBakeError=40.4937（canonical/adapter/rest rotation），body_c 的静态 baked corners 已对齐但 neutral world transform 仍偏 20 px；Legacy source 顶点已经包含 1.12 ModelRenderer host transform，因此 raw baked payload 的静态阶段标记为不可直接比较，最终 maxCornerError=1.0667e-6。在 Modern 两个阶段均降至数值阈值前，不能编译或部署客户端 JAR。

## 2026-10-09 required stage checks

Modern 验证必须分别报告 source parser、canonical converter、YSM adapter/bake、pose/render adapter、world transform、UV/mirror/atlas、locator。source parser/canonical 阶段检查 authored pivot 差与 cube 局部 origin/pivot；adapter/bake 检查 CubeLegacy 局部角点；pose 阶段逐项检查 ScriptBone 到 Java attributes 和 mode 2 矩阵；world 阶段递归父子矩阵比较每个 cube 角点。任何阶段失败都必须归因到该阶段，不能用模型偏移或截图判断通过。

### 2026-10-09 offline atlas/mirror verification finding

A CPM verification report passes texture sampling only after exact source-to-atlas pixel equality, and passes mirror only after emitted normals are finite unit axis vectors with the expected mirror direction; emitted winding is reported separately because up/down faces may have the opposite cross-product sign. Reports must expose these as separate stage checks so a failure can be assigned to texture sampling or mirror/index construction rather than reported as an undifferentiated coordinate error.

### 2026-10-09 UV endpoint mapping refinement

The `texture_sampling` gate passes only when source image pixels and every emitted face UV endpoint agree with the atlas mapping. If image copying passes but endpoint mapping fails, the report must identify UV remap as the failing stage.

### 2026-10-09 cross-model finding: Legacy sizeAdd and UV semantics

Verification records `raw_size` separately from expanded bounds. The UV gate uses `raw_size`, matching upstream CustomModel.Box, while the world-corner gate uses expanded vertices.

### 2026-10-09 cross-model finding: pose/world failures

The verification set now includes cross-model failure cases. A report must keep parser/canonical, adapter/pose/world, UV, and texture stages independent so Allay wing and Cirno ice failures remain actionable.

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

## 2026-10-09 verification additions

The offline contract checks: (1) neutral visibility propagation over the full parent chain, (2) source/YSM world corners after the complete Modern pose matrix, (3) normalized UV indices, (4) atlas texture-region mapping with logical-to-physical texture scale, and (5) mirror normals. A pass requires the per-cube world Hausdorff error to remain below `2e-5`; hidden subtrees are excluded from the active cube set.
# Verification contract: Modern host graph

Every Modern actual dump verification first checks that each source bone has the same resolved parent as the dump.  The check uses the upstream CustomPlayerModel resolver and the current model.json/geo source.  A mismatch fails `parser_canonical` or `ysm_adapter_pose_world` with a `parent_graph` diagnostic and blocks visual conclusions.  Only after the graph matches are neutralPose, world corners, UV indices, mirror winding, and texture sampling compared.
### Parent graph gate

Every offline comparison emits `parentGraph` with checked, missing, and
mismatched edges.  `parser_canonical` passes only when geometry ownership and
the complete parent graph both pass.  This gate runs for Legacy and Modern and
is independent of corner, UV, mirror, and texture checks.

### Texture-size gate

The UV check must use the same selected texture dimensions as the upstream Legacy box builder. A failure caused by a wrong fallback is reported at the parser/texture stage with the texture symbol and source image, and is not hidden by clipping normalized UVs.

### Skeleton-type host gate

Modern verification must read the declared skeleton type and verify every root
`*_c` host edge against the corresponding upstream skeleton namespace. The test
set must include at least one quadruped and one slime/zombie fixture (or an
equivalent synthetic document). Parser/canonical checks can pass while the
neutral pose/world stage fails if the host point map is accidentally biped-only;
that failure is classified as `ysm_adapter_pose_world`.

### Primitive pairing gate

For each bone with equal source and baked cube counts, the verifier pairs cube `i` with cube `i`. It records a fallback diagnostic pairing only when counts differ. This prevents overlapping cubes from creating a false parser, world-transform, or UV failure.

### Atlas region gate

UV verification applies the same `max(64, logicalTextureSize)` cell rule as CpmTextureAtlas. A physical image match alone does not prove correct UV mapping; the region logical dimensions must also agree.

### Namespace-aware parent gate

Parent verification maps a source bone to its exact emitted name, including the `__cpm_` collision prefix. For ordinary Modern roots it expects a null parent; only `_c` roots use the upstream host lookup. Attachment declarations are checked separately from parent edges.
