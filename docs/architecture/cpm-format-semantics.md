# CPM 三种来源模型格式的源码语义对照

本文记录 Gamepiaynmo 两个 MIT 项目的源码事实，以及 YSM/ysmlib 当前的接入策略。文中把“源码事实”和“当前适配策略”分开写；适配策略不是上游格式定义，后续修改必须先回到上游源码核对。

## 对照范围与源码版本

| 名称 | 本文含义 | 关键源码 |
|---|---|---|
| Legacy CustomModel | 1.12.x `CustomModel` 的 `model.json`/目录格式 | `handoff/CustomModel`，`CustomJsonModel.java`、`Bone.java`、`Box.java`、`RenderPlayerHandler.java` |
| Modern 旧几何封装 | `CustomPlayerModel` 中 `format_version` 小于 1.12 的 `geometry.*` 对象 | `handoff/CustomPlayerModel/shared/java/com/gpiay/cpm/model/element/BedrockModelParser.java` 的 `parseLegacyModel` |
| Modern 新几何封装 | 1.12+/1.16 使用 `minecraft:geometry` 数组的 Bedrock 几何 | 同一 `BedrockModelParser.java` 的 `parseBedrockModel` |
| YSM 目标模型 | YSM `GeoModel`/`Bone`/`CubeLegacy` 和 Lib 的 CPM IR | `src/main/.../CpmYsmModelAdapter.java`、`runtime/scene-api/.../CpmDocument.java`、`CpmGeometry.java`、`CpmCompiledModel.java` |

源码快照：`CustomModel` `9dd4bc3031f4f958fdc3b3895ca4c56afa642bba`，`CustomPlayerModel` `95f076641bfa2de4b776fcd5848f273f76afbaac`。两个上游仓库的许可证文件和仓库页面均声明 MIT；发行包应继续附带原许可证和来源说明。

## 1. Legacy CustomModel

### 1.1 顶层定义

`CustomJsonModel.java:30-66` 定义的主字段是：

| 字段 | 上游含义 |
|---|---|
| `modelId`, `modelName`, `author`, `version` | 模型身份和显示元数据 |
| `hide` | 隐藏原版玩家骨骼或渲染特征 |
| `variables`, `tickVars` | 表达式变量；`tickVars` 在逻辑 tick 更新 |
| `skeleton` | 覆盖原版玩家骨骼的 rotation point |
| `eyeHeight`, `boundingBox` | 实体视线高度和碰撞盒配置 |
| `bones` | 自定义骨骼数组，顺序就是旧版 reader 的绑定顺序 |
| `fpLeft`, `fpRight`, `fpList` | 第一人称左右臂和背景绘制列表 |

每个 bone 由 `Bone.getBoneFromJson`（`client/render/model/Bone.java:63-113`）读取：

```json
{
  "id": "hair",
  "parent": "head",
  "position": [x, y, z],
  "rotation": [x, y, z],
  "scale": [x, y, z],
  "visible": true,
  "color": [r, g, b],
  "alpha": 1,
  "emissive": false,
  "boxes": [{"textureOffset": [u, v], "coordinates": [x, y, z, dx, dy, dz], "sizeAdd": 0, "mirror": false}],
  "quads": [],
  "particles": [],
  "items": [],
  "physics": []
}
```

`parent` 缺省时绑定 `PlayerBone.NONE`；写出不存在的父骨会被上游拒绝（`Bone.java:70-75`）。position、rotation、scale、visible、color、alpha、emissive 和 physics 都可以是表达式数组，不是只接受常量。

### 1.2 Legacy 坐标和旋转

上游每次更新 bone 时执行（`Bone.java:229-243`）：

```java
position = expression(position).scl(-1, -1, 1);
rotation = expression(rotation) * (PI / 180);
```

因此 Legacy authored position `[x,y,z]` 在原版 renderer 中首先变成 `[-x,-y,z]`，单位仍是像素；渲染时 `PlayerBone.getTransform` 再按 `1/16` 缩放（`PlayerBone.java` 的 transform 实现）。上游 `IBone.getQuaternion`（`client/render/model/IBone.java:24-29`）使用：

```java
setEulerAnglesRad(rotation.x, rotation.y, 0)
    .mulLeft(setFromAxisRad(0, 0, 1, rotation.z));
```

这意味着 Legacy 的旋转不能直接当作 Bedrock `[x,y,z]` 角度送进 YSM；必须保留其轴顺序和反射语义。

### 1.3 Legacy box/quad

`Box.getBoxFromJson`（`Box.java:87-113`）把 `coordinates` 解读为 `[x, y, z, width, height, depth]`，先变成：

```text
xMin = -x - width - sizeAdd
xMax = -x + sizeAdd
yMin = -y - height - sizeAdd
yMax = -y + sizeAdd
zMin =  z - depth - sizeAdd
zMax =  z + sizeAdd
```

`mirror` 会交换 X 方向顶点并翻转所有面（`Box.java:40-44, 69-73`）。Legacy `quads` 使用同样的负 X/负 Y 约定，但只有一个平面；它不是 Bedrock cube 的零深度特例，法线和顶点顺序必须单独保留。

适配到 YSM 时直接复现上游的 ModelRenderer 局部顶点。GeoBuilder 的 cube
输入是最小角定义，因此 Legacy 的 canonical origin 为
`[x,-y-height,z-depth]`，生成的顶点正好是上面的三组区间。Legacy 自定义
骨骼不能把 `position` 烘成绝对 pivot：它们在 canonical IR 中使用零 pivot，
pose session 每帧输出完整的 source position，主模组编码为
`[-position.x,+position.y,+position.z]`，由 `JavaModelState` 的 Legacy local
branch 执行与上游相同的 `scale * R * T(position)`。

宿主 PlayerBone 的 canonical pivot 要预先编码为
`[-raw.x,24+raw.y,raw.z]`。GeoBuilder 再反射 X，宿主分支的
`T(pivot)*R*T(0,-24,0)` 得到原始 1.12 rotation point `[raw.x,raw.y,raw.z]`。
这样 position、宿主点和父级旋转各只消费一次。

### 1.4 Legacy 宿主骨骼

`RenderPlayerHandler.resetSkeleton`（`mixin/RenderPlayerHandler.java:323-346`）在每次渲染前把原版宿主 rotation point 重置为：

```text
head       = ( 0,  0, 0)
body       = ( 0,  0, 0)
right_arm  = (-5,  2, 0)
left_arm   = ( 5,  2, 0)
right_leg  = (-1.9, 12, 0)
left_leg   = ( 1.9, 12, 0)
```

覆盖层使用独立的 ModelRenderer：`head_overlay`、`body_overlay`、左右臂和
左右腿 overlay 分别有独立宿主点；1.12 `resetSkeleton` 中
`right_arm_overlay` 的 Z 点是 `10`，其余覆盖层沿对应主体点。

若 `skeleton` 中出现 `head_all`、`body_all`、`right_arm_all`、`left_arm_all`、`right_leg_all` 或 `left_leg_all`，`CustomJsonModel.updateSkeleton`（`CustomJsonModel.java:279-298`）每帧执行 `setRotationPoint(-x, -y, z)`。这是 Legacy 的宿主绑定定义；不能把默认宿主当成 Modern 的 `24-height` 公式。

## 2. Modern CustomPlayerModel 的共同模型包

### 2.1 `model.json` 包结构

`ModelLoader.loadFromEntry`（`ModelLoader.java:58-87`）按文件后缀组织模型包：

* 根文件必须是 `model.json`；
* 其他 `.json` 是几何部件；
* `.png`、`.jpg`、`.bmp` 是纹理；
* 名为 `animation.js` 的文件是脚本；
* 根 `parts` 数组把部件名、纹理名和 `double_face` 绑定起来（`ModelLoader.java:177-223`）。

根 `model.json` 的 `skeleton` 对象包括 `type`、`param`、`attachments`、可选 `left_arm`/`right_arm` 和 `eye_position`（`ModelLoader.java:118-173`）。`parts` 不是一棵新骨骼树；它引用几何文件，几何文件中的 parent/pivot 才是骨骼层级定义。

### 2.2 Modern 的运行实例和动态语义

`ModelPack.instantiateBase`（`ModelPack.java:58-93`）先实例化部件、骨骼、粒子和物品，再编译 `animation.js` 并取得 `init`、`update`、`tick` 三个函数。`instantiateModel`（`ModelPack.java:113-150`）随后创建宿主 skeleton、附件绑定和第一人称骨骼集合。

因此以下内容属于运行时语义，不能在几何 reader 中静态猜出来：

* `init`：模型实例创建后执行一次；
* `update`：每次渲染姿态更新；
* `tick`：逻辑 tick 更新；
* `physicalize`：在骨骼实例上启用物理参数；
* `particles`、`items`、`attachments`：分别由运行时功能层消费。

Legacy 宿主点与 Modern 的 `pivot` 语义不同：Legacy 的默认 head/body
rotation point 是 `[0,0,0]`（canonical pivot `[0,24,0]`），手臂是
`[±5,2,0]`（canonical pivot `[-raw.x,26,0]`，由 GeoBuilder 反射回原始 X）。Legacy 自定义
骨骼的 `position` 仍是父级局部变换；编译阶段才会把它累积成 YSM 的绝对
pivot，不能在源格式层把它误读成 Modern 的绝对 pivot。

### 2.3 Modern `size` 与 `default_scale`

`ModelInfo.parseModelInfo`（`ModelInfo.java:233-282`）把：

* `default_scale` 读入 `defaultScale`；
* `size.standing/sneaking/swimming` 读入 `EntitySize`；
* `eye_height` 读入每种姿态的视线高度。

`getEntitySize` 和 `getEntityEyeHeight`（`ModelInfo.java:285-297`）只把 `size`/`eye_height` 用于实体尺寸和眼高，并按调用者的 `scale` 缩放。它们不是 mesh 的几何缩放。YSM 转换只把 `default_scale` 映射到模型缩放；没有实现 CPM 实体碰撞盒 provider 时，`size` 和 `eye_height` 应保留在来源诊断中，不能伪装成 mesh scale。

## 3. Modern 旧几何封装（`geometry.*`, format < 1.12）

`BedrockModelParser.fromJson`（`BedrockModelParser.java:11-39`）读取 `format_version`。当主版本小于 1 或次版本小于 12 时，`legacy=true`，随后读取所有 key 名以 `geometry` 开头的对象（`parseLegacyModel`，`BedrockModelParser.java:42-61`）。其结构为：

```json
{
  "format_version": "1.8.0",
  "geometry.foo": {
    "texturewidth": 64,
    "textureheight": 64,
    "bones": []
  }
}
```

旧封装的 `texturewidth`/`textureheight` 是 geometry 对象的直接字段；骨和 cube 的字段语义由同一个 `ElementParser` 处理。因此它与新封装共享 cube 级坐标和旋转规则，但外层 JSON key、纹理尺寸字段和可包含的几何对象数量不同。

## 4. Modern 新几何封装（`minecraft:geometry[]`, 1.12+/1.16）

当 `format_version` 不满足旧条件时，reader 只接受 `minecraft:geometry` 数组（`BedrockModelParser.java:28-34`）：

```json
{
  "format_version": "1.12.0",
  "minecraft:geometry": [
    {
      "description": {"identifier": "geometry.foo", "texture_width": 64, "texture_height": 64},
      "bones": []
    }
  ]
}
```

新封装把纹理尺寸放在每个 geometry 的 `description` 中（`parseBedrockModel`，`BedrockModelParser.java:63-90`）。1.16 项目使用的是这个封装；根 `model.json` 仍负责 skeleton、parts、attachments 和脚本。

## 5. Modern bone/cube 的精确定义

### 5.1 骨骼

`ElementParser.parseBone`（`ElementParser.java:13-57`）读取 `name`、`parent`、`pivot`、`rotation` 和 `cubes`。pivot 是 authored 的绝对模型像素坐标，不是已经减过父骨的局部位移；parent 是字符串名称。

`ModelBone.instantiate`（`ModelBone.java:27-64`）才把绝对 pivot 转为运行时局部位置：

1. 有 `ModelBone` 父级时减去父级 authored position；
2. 没有自定义父骨时减去 `(0, 24, 0)`；
3. 执行 `scl(1, -1, 1).scl(0.0625)`；
4. 对没有自定义父骨但有宿主父级的情况，再减去宿主父级 position；
5. 运行时旋转保存为 `new Vector3d(rotation.y, rotation.x, rotation.z)`，也就是交换 authored X/Y 通道（`ModelBone.java:48-60`）。

此外，名称以 `_c` 结尾且未声明 parent 时，`ModelBone.instantiate` 会尝试把去掉 `_c` 的同名骨作为父级（`ModelBone.java:29-35`）。这是上游特殊绑定规则，不能被通用 parent 缺省逻辑覆盖。

### 5.2 骨骼旋转与 cube 旋转是两个通道

骨骼的 `rotation` 由 `ModelBone.instantiate` 交换 X/Y；cube 的 `rotation` 则在 `ElementParser.parseBox` 中直接写入 Minecraft `ModelRenderer`：

```java
bone.yRot = authored[0];
bone.xRot = authored[1];
bone.zRot = authored[2];
```

对应源码 `ElementParser.java:78-84`。因此不能用同一套 `[x,y,z]` 转换处理 bone rotation 和 cube rotation。

### 5.3 cube

`ElementParser.parseBox`（`ElementParser.java:59-146`）读取：

* `origin`：cube 起点；
* `size`：宽、高、深；
* `pivot`：cube rotation pivot；
* `rotation`：cube 自身角度；
* `uv`：旧封装通常为 `[u,v]`；
* `inflate`、`mirror`：膨胀和镜像；
* 新旧封装均可经过同一 parser 生成带旋转的 `ModelRenderer`。

解析后，cube 相对骨 pivot 的位置为：

```text
bone.x = cubePivot.x - bonePivot.x
bone.y = bonePivot.y - cubePivot.y
bone.z = cubePivot.z - bonePivot.z
x      = origin.x - bonePivot.x
y      = bonePivot.y - origin.y - size.y
z      = origin.z - bonePivot.z
```

源码对应 `ElementParser.java:70-75` 和 `ElementParser.java:115-121`。面选择遵循退化面规则：某一尺寸为零时相应面可能被省略；`double_face` 会使反向面保留（`ElementParser.java:123-139`）。

## 6. Modern 宿主 skeleton 的定义

`CustomBipedModel` 构造函数（`skeleton/model/CustomBipedModel.java:22-30`）明确写出默认 biped 宿主 pivot：

```text
head       = (0, 24 - head_pivot_height, head_offset)
body       = (0, 24 - body_pivot_height, body_offset)
right_arm  = (-arm_interval/2, 24 - arm_pivot_height, arm_offset)
left_arm   = ( arm_interval/2, 24 - arm_pivot_height, arm_offset)
right_leg  = (-leg_interval/2, 24 - leg_length, leg_offset)
left_leg   = ( leg_interval/2, 24 - leg_length, leg_offset)
```

参数默认值在 `CustomBipedModel.java:45-56`。这组值和 Legacy 的 `resetSkeleton` 完全不同；Modern 的 `24 - height` 只能用于 Modern biped skeleton。

## 7. 第一人称、附件、粒子、物品和物理

| 能力 | Legacy CustomModel | Modern CustomPlayerModel | Lib/YSM 当前归属 |
|---|---|---|---|
| 第一人称 | `fpLeft`、`fpRight`、`fpList`，成员按骨 id 查找 | `skeleton.left_arm`、`skeleton.right_arm`；未声明时由 skeleton 默认列表提供 | Lib 读取并产生 `Locator`；主 Mod renderer 决定 pass 和裁剪 |
| 宿主附件 | `attached`/feature 绑定及 player feature | `skeleton.attachments`，另有 `addons` | Lib 统一成 attachment binding；宿主层映射装备/披风/翅膀等槽位 |
| 粒子 | bone 内 `particles`，Legacy 表达式驱动 | 根 `particles`，由 `ParticleEmitter` 实例化 | Lib 保存定义并输出事件；主 Mod 的粒子 host 负责 Minecraft 生成 |
| 物品 | bone 内 `items` | 根 `items`，`ItemModel` | Lib 保存 parent/item/enchanted；主 Mod 物品层消费 |
| 物理 | Legacy bone `physics`，`Bone.update/tick` 更新参数 | `ModelBone.Instance.physicalize(elasticity, stiffness, damping, friction, gravity)` | Lib 保留参数和独立 pose session；当前物理为无 Minecraft 依赖的近似求值 |
| 表达式/脚本 | Legacy expression parser、`variables`、`tickVars` | `animation.js` 的 `init/update/tick` | Lib 执行 Legacy 表达式和受限 Modern JS；几何 reader 不执行动态语义 |

第一人称列表是“选择要绘制的骨及其后代”，不是把这些骨重新挂到一棵新树；`ModelPack.instantiateModel` 的 `boneList` 构造（`ModelPack.java:140-150`）保留原 parent 关系。

## 8. YSM 目标格式和转换边界

### 8.1 YSM 几何事实

`GeoBuilder.java:68-81` 把普通 YSM bone 的 pivot/rotation 写入目标数据：pivot 的 X 反射，rotation 为 `(-x, -y, +z)` 弧度。cube 顶点在 `GeoBuilder.java:110-147` 预烘焙；cube rotation/pivot 在 `GeoBuilder.java:341-383` 围绕 pivot 旋转后写入 baked positions。

`JavaModelState.java:59-84` 决定运行时 pose matrix。`transformMode=1` 是 Legacy 专用路径，`transformMode=2` 是 Modern 专用路径；普通 YSM 使用 mode 0。这个 mode 是 YSM 的适配标记，不属于 CPM 原始 JSON。

### 8.2 Lib 侧事实

* `CpmDocument` 保存来源闭包、metadata、原始 JSON 树和两代专用结构；读取阶段不执行表达式、脚本、物理或宿主资源。
* `CpmGeometry` 保存 authored 像素/角度的 Modern geometry，不提前烘焙 YSM 坐标。
* `CpmCompiledModel` 是静态编译 IR；`compile` 保留来源语义，`compileCanonical` 才添加 YSM player host skeleton、locator 和 Legacy/Modern 绑定策略。
* 这使得 parser、source IR、静态 mesh 转换和每实体动态 pose 可以独立测试。

### 8.3 主 Mod 侧事实

`CpmYsmModelAdapter` 现在只做格式分派：Legacy 进入 `CpmLegacyYsmAdapter`，Modern 进入 `CpmModernYsmAdapter`。两条转换器分别调用 `compileCanonical`，再调用 `GeoBuilder`；公共 facade 不再包含两代格式的坐标分支。

Legacy 转换器的策略是：

1. Legacy 使用其独立的 ModelRenderer 宿主点和静态 box/quad 转换；运行时模式为 `1`；
2. Legacy 自定义骨骼 pivot 固定为零，Box/quad 先按上游最大角公式转换为 GeoBuilder 最小角，完整 position/rotation 由 Legacy pose session 输出；宿主骨骼才使用绝对 pivot。

Modern 转换器的策略是：

1. Modern 使用 `transformMode=2`，宿主 pivot 来自 `CustomBipedModel` 的 `24 - 参数`；
2. Modern `size` 不映射到 mesh scale，只使用 `default_scale`；
3. Modern bone rotation 与 cube rotation 使用不同函数：bone rest channel 按 `ModelBone.getQuaternion` 和 YSM X 反射编码为 `[source.x,source.y,-source.z]`，cube 单独按 `ElementParser` 的 `yRot=source.x,xRot=source.y,zRot=source.z` 编码为 `[source.y,source.x,-source.z]`；
4. 原始骨名与 YSM 保留宿主骨名冲突时，Lib 使用 `__cpm_` 前缀，并由主 Mod 的 authored-name binding 恢复 source-to-host 关系；
5. 第一人称/附件通过 locator 进入现有 YSM renderer，而不是在 parser 中硬编码 Minecraft 渲染调用。

动态姿态同样分派到 `JavaCpmLegacyPoseSession` 或 `JavaCpmModernPoseSession`；`JavaCpmPoseSession` 只负责生命周期转发。主 Mod 不再拥有 Legacy/Modern 的脚本求值、pivot 相减或 rotation channel 规则。

这些是当前适配实现的选择，必须和上面列出的上游源码事实分开维护。若游戏截图与上游原模组不一致，优先检查对应的源码路径和离线顶点/矩阵对照，而不是继续增加经验性偏移。

## 9. 容易混淆但不能合并的定义

| 误合并 | 实际差异 |
|---|---|
| Legacy position 与 Modern pivot | Legacy 是表达式求值后 `(-x,-y,z)` 的局部 position；Modern pivot 是绝对 authored pivot，实例化时才减父 pivot/宿主 `(0,24,0)`。 |
| Legacy 默认宿主与 Modern biped 宿主 | Legacy head/body 为零点、四肢为原版 rotation point；Modern 使用 `24 - height` 及 interval/offset 参数。 |
| Modern bone rotation 与 cube rotation | bone rotation 在 `ModelBone.instantiate` 交换 X/Y；cube rotation 在 `ElementParser.parseBox` 写成 `yRot,xRot,zRot`。 |
| `size.standing` 与 `default_scale` | 前者是实体 hitbox，后者是模型实例缩放；前者不能直接改 mesh。 |
| 第一人称骨列表与父重挂 | 列表选择骨及其后代，原 parent 保留；不能把所有列表成员直接改挂到 `LeftArm`/`RightArm`。 |
| 旧 geometry 与新 geometry | 共享 ElementParser 语义，但外层布局和 texture size 字段位置不同；`geometry.*` 不能按 `minecraft:geometry[]` 读取。 |

## 10. 当前验证要求

每次修改 CPM 转换时至少需要：

1. 用固定上游源码路径重新确认定义；
2. 用 Legacy、Modern 旧 geometry、Modern 新 geometry 各一个模型跑 parser/IR 测试；
3. 对宿主 pivot、bone parent、cube 顶点、cube rotation、first-person locator 做数值断言；
4. 单独运行 `:ysm-runtime-java-cpm:test`，避免多个 Gradle daemon 并行导致类路径假失败；
5. 用 Java 17 编译主 Mod 和 runtime，再在客户端验证 Legacy 与 Modern 样本。

本文不把“能读取 JSON”表述为“完整兼容原 Mod”。完整兼容还需要逐项验证脚本输入、粒子材质、物品渲染、物理状态、碰撞盒/眼高和第一人称 pass。
YSM rotation boundary: Modern geometry keeps authored bone rotation as rest rotation. JavaCpmModernPoseSession emits current script/physics rotation minus that rest rotation; GeoBuilder and render pose therefore apply the authored channel exactly once.

## 2026-10-08 offline verification finding

The upstream Modern boundary is `ModelBone.instantiate` followed by `ScriptBone.getPositionX/Y/Z` in `handoff/CustomPlayerModel`: authored pivot is converted to a local, reflected runtime position, and the script API exposes that complete position in pixels. The source parser and canonical converter preserve this distinction, but the actual dump omitted the Modern pose-session result, so an offline consumer could not verify the neutral runtime pose. This is a pose export gap, not a parser or model-specific offset.

### 检查器矩阵分叉（2026-10-08，修改前记录）

已核对两代上游的 IBone/IModelBone.getTransform；compare 仍复制旧矩阵且未消费 Modern pose，必须与 actual renderer 共用 dump world 计算。此共用仅限 YSM 消费端，Legacy/Modern 适配规则仍独立。synthetic host 无 pose 时必须保留 AnimatedGeoBone 默认 attributes，不能把六个 host alias 应用到整条 synthetic grouping 链。Legacy source vertices 已是 ModelRenderer 坐标，比较时不能额外反射 X；Modern 的 GeoBuilder 才额外反射 X。
2026-10-09 Modern YSM dump finding: 实际 dump 对 Yukari 显示，当前把 cube origin 写成 24-origin.y-size.y 的绝对位置会让无旋转骨的静态 CubeLegacy 顶点看似正确，但它绕过了上游 ElementParser.parseBox 的骨局部 onePivot-origin-size 语义；嵌套旋转骨因此在 canonical/adapter 边界产生最高约 32.5 px 的静态误差。后续转换必须按上游 authored pivot 计算局部 origin，不能按单模型补偿。

## 2026-10-09 coordinate representation correction

上游 Modern 的组件坐标有两个边界：`ModelBone.instantiate()` 产生相对父骨或 vanilla 宿主的实例化 position；`ElementParser.parseBox()` 产生相对当前 bone authored pivot 的 `ModelRenderer` 局部 cube origin/pivot。把局部 cube 写成绝对 origin，同时再用绝对 pivot 矩阵，会重复消费 authored 位移并使嵌套链碎裂。Modern canonical converter 保留 cube 局部坐标，并把 YSM bone pivot 编码成相对父骨的 authored pivot；Legacy 不复用该转换。

### 2026-10-09 offline atlas/mirror verification finding

The coordinate boundary is only complete when texture sampling and mirror winding are verified from the actual baked dump. A normalized UV range or a generated atlas file alone does not prove that a source texture pixel is copied to the atlas region consumed by `CubeLegacy`; the verification must locate the source image in the deterministic atlas and compare every source texel. Mirror verification must also inspect the baked position index winding and face normals, skipping only geometrically degenerate zero-area faces. A source `mirror` count is insufficient as a pass criterion.

### 2026-10-09 UV endpoint mapping refinement

Exact image copying is necessary but not sufficient. For every matched source primitive and baked cube, the six emitted face UV quads must equal the source face UV rectangles translated by the located atlas region and normalized by the atlas dimensions. This catches a texture-region or UV-remap error even when the atlas contains an exact copy of the source image.

### 2026-10-09 cross-model finding: Legacy sizeAdd and UV semantics

Upstream CustomModel.Box expands vertex bounds by `sizeAdd`, but its Quad UV rectangles continue to use the original width, height, and depth. A source oracle must therefore carry both `raw_size` and expanded geometry `size`; using expanded size for UV is a source-oracle error and falsely assigns the discrepancy to the adapter.

### 2026-10-09 cross-model finding: pose/world failures

Additional baked dumps expose genuine world-stage failures: Modern Allay wing primitives and Legacy Cirno ice/bone branches do not match source world corners while parser/cube counts and UV structure remain valid. These must be diagnosed through upstream parent/pose semantics; no model-specific offset is admissible.

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

## 2026-10-09 neutral visibility and rotation contract

Modern `ModelBone.update()` computes `hierarchyVisible = parent.isVisible() && visible`; an offline pose dump must therefore suppress every descendant of a hidden bone. Directly filtering only the named hidden bone renders hidden sword/bullet descendants and is a pose-stage error. The neutral dump now carries visibility and offline consumers propagate it through the authored parent chain.

Modern `ModelBone.instantiate()` stores internal rotation as `(source.y, source.x, source.z)`, while `ScriptBone` exposes source channels `(source.x, source.y, source.z)`. The render boundary must encode ModelRenderer order `[xRot,yRot,zRot] = [source.y,source.x,source.z]`, yielding `Rz(source.z) * Ry(source.x) * Rx(source.y)`.
# 200-Ori Modern host attachment audit

The offline 200-Ori dump exposed a boundary defect that must be classified before implementation changes.  The source geometry declares `left_arm_c`, `right_arm_c`, `left_leg_c`, and `right_leg_c` as root bones (their pivots are the vanilla biped attachment points), while descendants use authored parent names.  CustomPlayerModel resolves a root `_c` bone through `ModelBase.getBone` and the vanilla skeleton, then keeps authored descendants in the model namespace.  A converter must therefore preserve the root attachment selected by the upstream resolver and must not infer an opposite-side host from a compact canonical name or from a stale dump.

The actual neutral ScriptBone channel is the result after `ModelBone.instantiate`, animation.js at age 0, and host pose seeding.  It is not interchangeable with the raw Bedrock pivot or with a source renderer's synthetic fallback.  A comparison that sees only arm/leg descendants diverge while head/body descendants match is classified as host attachment/world transform until a freshly generated actual dump proves otherwise.
### Parent graph verification

Offline conversion treats the authored parent graph as data.  A Modern `*_c`
bone is resolved with the upstream `ModelBase.getBone()` namespace order and
is checked against the baked parent edge; duplicate attachment records cannot
replace that edge.  The verification report therefore records every authored
bone edge and fails the parser/canonical stage when an edge is missing or
different.

### Legacy texture size fallback

Legacy `Bone.getTextureSize()` delegates to `ModelPack.getTextureSize()` when a bone does not provide `textureSize`; the value is the decoded image dimensions. The source parser must resolve the inherited texture first and use that image size, rather than assuming the Modern/Bedrock 64x64 default.

### Stable primitive order

The upstream Modern compiler appends cubes in source order and the YSM adapter emits them in that same bone-local order. Offline comparison must pair equal-count primitives by index before using center/extent diagnostics; nearest-center matching is ambiguous for overlapping or coincident cubes and can falsely classify UV or geometry errors.

### Atlas cell minimum

The YSM atlas uses a minimum logical cell of 64 pixels. Modern geometry declaring a smaller logical texture is therefore remapped with `max(64, textureSize)`; source/YSM UV comparison must use that adapter value while preserving the authored UV offsets.

### Modern non-biped skeleton host namespace

Upstream `CustomPlayerModel` does not limit the Modern skeleton namespace to the
biped hosts. `EnumSkeleton` also instantiates `zombie`, `slime`, and
`quadruped`; their `Skeleton` implementations register host ids such as
`body`, `head`, `left_front_leg`, `right_front_leg`, `left_back_leg`, and
`right_back_leg`. A root `*_c` bone is resolved through that active skeleton's
`ModelBase.getBone` namespace. Therefore a converter must select host ids from
the declared skeleton type and must not silently apply biped-only `_c` rules.

### Modern ordinary roots and canonical-name collisions

CustomPlayerModel only applies the special host lookup to a root whose name ends in `_c`. A normal root such as `body_sword` remains parentless unless its authored parent is explicit. Separately, an authored bone whose name equals a canonical host is renamed in the YSM namespace; parent verification must compare its emitted `__cpm_` identity while preserving authored parent references.
