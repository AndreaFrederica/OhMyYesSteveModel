# CPM 适配器边界与渲染契约

本文是 CPM 接入实现的设计契约。它先固定两个上游项目的渲染语义，再规定 ysmlib 与 YSM 主模组之间的责任边界。实现只能通过本契约暴露的入口连接两代格式；禁止在公共转换器中按字段名猜测另一代格式。

## 依据

* Legacy：`handoff/CustomModel/src/main/java/com/github/gamepiaynmo/custommodel/client/render/model/Bone.java`、`IBone.java`、`Box.java`、`CustomJsonModel.java`、`mixin/RenderPlayerHandler.java`。
* Modern：`handoff/CustomPlayerModel/shared/java/com/gpiay/cpm/model/element/ModelBone.java`、`IModelBone.java`、`ElementParser.java`、`animation/ScriptBone.java`、`model/skeleton/Skeleton.java`、`ModelBase.java`。
* 目标侧：`runtime/scene-api/.../CpmDocument.java`、`CpmGeometry.java`、`CpmPoseSession.java`、`src/main/.../GeoBuilder.java`。

## 两条不可合并的渲染管线

### Legacy CustomModel 1.12

Legacy 的 `Bone.position` 是每帧求值的父级局部 ModelRenderer 位移。上游先执行 `scl(-1,-1,1)`，然后把 position 写入独立的 `ModelRenderer`；`ModelRenderer.setRotationPoint` 由宿主 `RenderPlayerHandler.resetSkeleton` 提供。骨骼旋转由 `IBone.getQuaternion` 按 `Euler(x,y,0)` 再左乘 Z 轴旋转组成。盒子的 coordinates 是反射后的最大角，`Box` 在 X/Y/Z 三个轴上分别构造最小角；quad 是独立的平面路径。

Legacy 静态转换必须因此完成四步：

1. 解析 Legacy 表达式和骨骼树，保留局部 position，不把它解释为 Modern 的绝对 pivot。
2. 用 `resetSkeleton` 的宿主点建立独立 ModelRenderer 根；宿主点和自定义骨骼点分别记录。
3. 按 `Box` 的公式生成骨骼局部静态顶点；自定义骨骼在 YSM IR 中使用零 pivot，动态 session 保留完整的局部 position/rotation/scale，JavaModelState 的 Legacy 分支只执行一次局部变换。宿主骨骼使用预编码的绝对 rotation point。
4. 第一人称使用 `fpLeft`/`fpRight`/`fpList` 选择原树中的子树，不能重挂父级。

实现约束：Legacy 的 `Head`、`Body`、两侧手臂和两侧腿（含 overlay）在 YSM IR 中全部是
`parent = null` 的独立根。`Arm`、`Leg`、`UpperBody` 等 Modern/YSM 合成分组不能插入
Legacy parent 链；否则同一个 1.12 `rotationPoint` 会被每个分组节点重复累加。Legacy
自定义骨只沿原始 `Bone.parent` 链连接，并直接挂到对应的独立宿主根。

### Modern CustomPlayerModel 1.16

Modern 的 geometry bone `pivot` 是 authored 的绝对像素点。`ModelBone.instantiate` 才执行 parent pivot 相减、无自定义父级时减 `(0,24,0)`、Y 反射和 `/16`；有宿主父级时再减宿主骨位置。bone rotation 在实例化时交换 X/Y；cube rotation 由 `ElementParser` 直接写入 Minecraft `ModelRenderer` 的 `yRot=authored.x`、`xRot=authored.y`、`zRot=authored.z`。`ScriptBone` 的 position API 以像素暴露，Y 方向再次取反；rotation API 以交换后的实例通道暴露。

Modern 静态转换必须因此完成四步：

1. 读取 `minecraft:geometry[]` 或旧 `geometry.*` 外壳，但统一交给 Modern geometry reader。
2. 保留 authored absolute pivot 和 authored rotation；转换器在一个明确边界执行 parent/host 减法，不能在 pose session 再次减静态 pivot。
3. bone rotation 与 cube rotation 使用两套明确转换函数，禁止共用一个 `[x,y,z]` 交换函数。
4. `skeleton.left_arm/right_arm` 是“选择骨及其后代”的列表；保留原 parent 链。animation.js 的 init/update/tick、宿主骨同步、物理和第一人称选择属于 Modern session。

## 分层责任

```text
source bytes
  -> LegacyReader / ModernReader                 (ysmlib，读取，不渲染)
  -> CpmLegacySceneConverter / CpmModernSceneConverter (ysmlib，格式专属静态 IR)
  -> CpmStaticModel                              (ysmlib，公共只读结果)
  -> LegacyPoseSession / ModernPoseSession       (ysmlib，动态语义)
  -> LegacyYsmAdapter / ModernYsmAdapter         (YSM，目标几何/locator)
  -> YSM renderer                                (主模组，Minecraft 资源和 pass)
```

公共层只负责：格式识别、生命周期接口、只读 IR 和 `CpmPoseSession` 分派。它不读取 Legacy 字段，也不执行 Modern 脚本。主模组只负责把结果绑定到 YSM 骨骼、纹理、第一人称 pass 和粒子/物品宿主；它不再决定 CPM 坐标符号、pivot 相减或旋转交换。

代码隔离也必须与这张图一致：`CpmLegacySceneConverter` 只接受
`CpmDocument.Legacy`，`CpmModernSceneConverter` 只接受 `CpmDocument.Remake`。
两者各自拥有自己的 host skeleton、primitive conversion、locator 规则和静态
构建器；`CpmSceneCompiler` 只做格式中立的源 IR 和分派。动态侧同样由
`JavaCpmLegacyPoseSession` 与 `JavaCpmModernPoseSession` 分别实现，不能在一个
session 中根据字段猜测另一代格式。这样任何对 Legacy 的坐标修复都不会改变
Modern 的 authored pivot/rotation 语义，反之亦然。

### 多纹理与 UV 图集

CPM 的一个模型可以让不同 primitive 使用不同纹理文件，而 YSM 的 baked geometry 只接受一套全局纹理尺寸和一个 render target 纹理。`CpmTextureAtlas` 在导入边界执行一次确定性的图集转换：收集包内图片，按 CPM texture symbol 建立别名，生成单张 PNG；Legacy/Modern 各自的适配器随后把本 primitive 的 box UV 或 per-face UV 加上该纹理区域的左上角偏移，并把 geometry 的 `texture_width/texture_height` 改为图集尺寸。渲染器只绑定 `cpm_atlas`。

这一步只改变纹理地址空间，不改变 CPM 的面顺序、坐标系、镜像、退化面或 UV 尺寸。未解析的 Modern host texture 和 Legacy `tex.*` 别名使用确定的 fallback 区域；原始 CPM source closure 仍然写入模型容器，便于诊断和重新导入。

## 必须满足的回归不变量

* Legacy 零姿态：head/body=(0,0,0)，左右臂=(±5,2,0)，左右腿=(±1.9,12,0) 的 ModelRenderer 点只出现一次；Box 顶点与上游 `Box` 构造器逐点相同。
* Modern 零姿态：每个 authored pivot 只减一次父 pivot/宿主 `(0,24,0)`；脚本读取到的 position 与 `ScriptBone` 的像素 API 相同。
* Modern bone rotation 和 cube rotation 的转换结果不能相同地复用。
* Modern YSM 几何保留 authored bone rotation 作为 rest rotation；pose session 输出当前 rotation 减 authored rest rotation，避免静态和动态通道重复应用。
* 第一人称选择不改变 parent；渲染选择和骨骼绑定是两个操作。
* 同一模型在离线快照与游戏内 bake 使用相同的静态 IR 和 pose 帧。

## 实现顺序

1. 先把两种静态转换器和两种 pose session 分成独立类，公共 facade 只分派。
2. 为每条管线各写零姿态、宿主点、旋转通道和第一人称树选择测试。
3. 增加离线快照命令，输出静态顶点与三帧 pose，和上游参考矩阵逐项比较。
4. 只有快照通过后才重新构建和部署客户端 JAR。

## 2026-10-08 boundary correction

Modern dump generation belongs to the Modern pose-session boundary: at age `0` with empty inputs it must call the provider session and serialize every returned `BonePose`. Legacy continues to use its independent evaluator. The offline renderer consumes that serialized pose through the Modern adapter equations; no shared Legacy position/rotation conversion or model-specific offset is permitted.

### 检查器矩阵分叉（2026-10-08，修改前记录）

已核对两代上游的 IBone/IModelBone.getTransform；compare 仍复制旧矩阵且未消费 Modern pose，必须与 actual renderer 共用 dump world 计算。此共用仅限 YSM 消费端，Legacy/Modern 适配规则仍独立。synthetic host 无 pose 时必须保留 AnimatedGeoBone 默认 attributes，不能把六个 host alias 应用到整条 synthetic grouping 链。Legacy source vertices 已是 ModelRenderer 坐标，比较时不能额外反射 X；Modern 的 GeoBuilder 才额外反射 X。
2026-10-09 adapter boundary finding: Modern CpmModernYsmAdapter 当前将 authored cube 写入绝对 YSM 坐标，实际上游 ElementParser 先减 bone authored pivot，再由 ModelBone.instantiate/父链完成 world 变换。该绝对写入与保留的 bone rotation 叠加，造成 canonical/adapter 静态误差；修复必须在 Modern adapter 内使用局部 origin/pivot，Legacy adapter 不得复用此规则。

2026-10-09 pose algebra correction: JavaModelState mode 2 的平移项在无旋转时化简为 `[-attrX,+attrY,+attrZ]/16`；因此把完整 ScriptBone position 再加 `ysmPivot` 会重复消费 authored pivot。Modern adapter 的属性通道必须只执行 ScriptBone 的 Y 反射（`attr=[scriptX,-scriptY,scriptZ]`），Legacy 的 `[-x,-y,z]` 和旋转交换保持独立。

## 2026-10-09 Modern adapter boundary correction

Modern parser/canonical converter 只把 authored pivot 差和 cube `ModelRenderer` 局部数据交给 Modern YSM adapter。adapter 不把局部 cube 改成绝对 cube，也不把绝对 authored pivot 当作父局部 pivot。Modern pose session 输出完整 ScriptBone position/rotation，render pose adapter 一次性编码 Java mode 2 attributes；Legacy parser、converter、pose session、render pose adapter 保持独立。

### 2026-10-09 offline atlas/mirror verification finding

The adapter boundary carries normalized atlas UVs and baked face index/normal data. Consumers must not infer mirror from a model-specific offset or from a source entry count. The independent verification tool resolves the source image, locates its copied atlas region, and validates the actual `posIndices` winding and `normal` arrays emitted by the YSM adapter.

### 2026-10-09 UV endpoint mapping refinement

The adapter's texture boundary is the pair `(atlas region, normalized face UV)`. Verification must compare both values against the source primitive's face rectangles, including mirror UV reversal and zero-area face omission rules.

### 2026-10-09 cross-model finding: Legacy sizeAdd and UV semantics

The Legacy adapter boundary carries base primitive dimensions to UV generation and `inflate` to geometry expansion. Consumers and offline oracles must not derive UV extents from the expanded cube bounds.

### 2026-10-09 cross-model finding: pose/world failures

Allay and Cirno failures occur after canonical primitive counts and UV/index construction. Their adapter/pose/world chain requires upstream parent and host-bone inspection before any code change.

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

## 2026-10-09 modern rotation and atlas boundary

The Modern adapter boundary carries complete ScriptBone source pose values into a render-order channel. Rotation channel order is explicit: source `(x,y,z)` becomes render `[xRot,yRot,zRot]=(y,x,z)`. Texture atlas regions also retain the physical image dimensions so logical CPM UVs can be scaled during remap. Legacy does not share these helpers or pose rules.
# Modern non-biped skeleton boundary

The Modern adapter boundary includes the declared skeleton type. Host
resolution is delegated to the upstream `Skeleton` namespace: biped/zombie,
slime, and quadruped have different valid host ids. `CpmModernSceneConverter`,
`JavaCpmModernPoseSession`, and the actual-dump neutral host seed must share
that type-specific namespace. A biped-only pose map is an adapter boundary
violation for quadruped or slime models.

# Modern root attachment boundary

The Modern adapter boundary receives a resolved authored-to-host parent relation from the upstream namespace resolver.  `*_c` root bones are attached through `ModelBase.getBone` exactly as CustomPlayerModel does; authored parent names are resolved before canonical host aliases.  The adapter must not reconstruct this relation from a screenshot, cube coordinates, or a single model name.  Parent graph mismatches are adapter/converter errors and must be reported before pose or UV analysis.
### Parent edge boundary

The source parser/converter owns authored parent identity; the YSM adapter owns
the final baked parent index.  The offline contract compares these two outputs
before invoking the pose session.  No adapter may repair a parent mismatch with
an authored-name heuristic or model-specific translation.

### Texture size boundary

Legacy source parsing owns texture inheritance and image-size fallback. The YSM adapter only maps the resulting UV rectangles into the atlas and must not silently rescale a parser value that came from a default.

### Primitive identity boundary

The converter owns the stable per-bone primitive sequence. The YSM adapter preserves that sequence while changing vertex and UV serialization. Verification must compare the sequence at this boundary before evaluating matrices or atlas coordinates.

### Atlas layout boundary

CpmTextureAtlas owns the logical cell clamp and exposes it to the YSM adapter through each region. Render verification must evaluate UVs using the region cell dimensions, not infer them only from the source geometry declaration.

### Namespace mapping boundary

The Modern converter owns the authored-to-emitted name map for canonical host collisions. The pose and render adapters consume emitted names, while diagnostics retain authored names and map them explicitly. Heuristic root-name attachment is outside the adapter boundary.
