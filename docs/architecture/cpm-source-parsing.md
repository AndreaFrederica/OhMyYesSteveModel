# CPM 来源解析

Lib 的 `YsmRuntime.cpm()` 提供 Gamepiaynmo CustomModel 与 CustomPlayerModel 的独立来源读取，属于[双后端决定](../product-decisions/decisions/dual-model-backends.md#ddparallel-model-backends)下的格式能力。主 Mod 还把 CPM 目录和 ZIP 导入现有 YSM player `.mxc` 容器，复用标准缓存、预览和原生渲染链路；转换器将完整来源闭包压缩到受控 `cpm-source` chunk，因此脚本在缓存、重启和远程 chunk 加载后仍可恢复。Lib 继续提供静态来源编译、旧版表达式/tickVars 求值和受限新版 JavaScript pose 求值。

## 调用与职责

`CpmProvider` 的 `detect` 检查主 JSON 的格式特征，返回两代来源枚举或空；混合两种特征的输入拒绝。`read` 接收调用者冻结的 `Map<String,ByteData>`，必须包含根 `model.json`；目录/ZIP 枚举可使用已有宿主文件/前置归档能力，读取器不打开网络、文件系统或 Minecraft 资源。`readGeometry` 可独立读取新版引用的 Bedrock 几何。

```java
var format = YsmRuntime.cpm().detect(files.get("model.json"), ReadLimits.DEFAULT);
CpmDocument source = YsmRuntime.cpm().read(files, ReadLimits.DEFAULT);
if (source instanceof CpmDocument.Remake remake) {
    for (var part : remake.parts()) {
        var geometry = part.geometry();
        // Authored source pivots, degrees, cubes and UV; no evaluated pose.
    }
}
```

接口和不可变文档位于 `scene-api`，Java reader 位于独立 `java-cpm` 模块；core 只组合服务。服务没有共享可变解析状态。`CpmValue` 是 JDK-only 来源树，不向宿主暴露 Gson 对象。数字保留来源拼写，字符串和未知字段保留，对象成员及数组顺序不重排；`ObjectValue.members` 包括重复成员，`fields` 为最后成员覆盖的查询视图。`ByteData` 保有来源字节，文档不持有调用方可修改的集合。

## 两代来源

旧版 `CpmDocument.Legacy` 提供 metadata、按源序列保存的骨、boxes/quads 和完整来源树。`position/rotation/scale/texture/color/visible/emissive/physics` 的表达式、`variables/tickVars`、挂点、粒子、物品和第一人称配置保留在相应来源对象；仅检查支持属性的结构，不编译或执行表达式。默认模型可省略 bones。布尔 tick 变量接受 boolean/bool 标记，保留原 Gson 可接受的数值初值，不在读取阶段执行布尔转换。

新版 `CpmDocument.Remake` 提供 metadata、骨架类型/参数、挂点、第一人称选择、部件与纹理引用、附件 addons 和可选 animation.js 文本。空的第一人称列表与未声明的默认列表区分。新版 id 由原宿主的载入参数赋予，文档 metadata.sourceId 留空，不根据显示名猜造业务身份。来源 `size`/`eye_height` 保留为实体碰撞和相机语义；它们不会被误用为 YSM 几何的横纵缩放，动态实体尺寸接线仍由宿主实体提供。

Bedrock 读取器明确接受 1.8.0/1.10.0 的 `geometry.*` 布局与 1.12.0 的 `minecraft:geometry[]` 布局；记录骨绝对 pivot/rotation、cube origin/size/pivot/rotation/uv/inflate/mirror 及原对象。1.8.0 由布局合成用例验证，未取得该版本真实作者模型。未知版本拒绝，未知字段保留；per-face object UV 只保留，不表示已具备原模组未实现的 UV 求值。

两代几何都有骨、方块和 UV，与 YSM MC 风格模型有可共享概念，但来源坐标、变换顺序、根骨绑定和运行语义不同。读取不执行轴反射、pivot 减法、角度换算、UV 烘焙或 ModelData 转换，避免在基础层丢失来源语义。当前 schema/接口为开发能力，没有承诺 wire 稳定。

## 静态编译阶段

静态显示目标是现有 YSM 模型格式，而不是通用 `ScenePackage`。主 Mod 的 `CpmYsmModelAdapter` 将两代来源转换为 YSM `GeoModel`/`Bone`/`CubeLegacy`，然后复用现有 `GeoBuilder`、baked model 和 native renderer；旧版 box/quad 与新版 cube、box/per-face UV 都走这条路径。Legacy 的 host/local X 与 `GeoBuilder` 的 X 反射分开处理，Legacy 旋转按上游 `IBone.getQuaternion()` 的 `Rz(z) * Ry(x) * Rx(y)` 轴顺序映射；Modern/Remake 则保留 `ModelBone.instantiate()` 的绝对 pivot 相对化与 rotation 交换。新版部件的根骨会依据 skeleton attachments/addons、左右臂声明和官方骨名绑定到 head/body/arms/legs 虚拟宿主骨。`ModelSourceDiscovery` 将 `model.json` 根目录标记为 `CPM_RAW_DIRECTORY`，ZIP 中的根 `model.json` 标记为 `CPM_RAW_ARCHIVE`；`CpmModelImporter` 生成 main/arm 几何槽位、图片 blob、标准 player manifest 和 `cpm-source` 来源 chunk。Lib 侧还提供无 Minecraft 依赖的旧版表达式/tickVars 求值和受限新版 `animation.js` proxy（GraalJS，只有 entity/model/bone 显式对象可见）；脚本按原模组的 `init`（一次）、`update`（每次渲染姿态）和 `tick`（每个逻辑 tick）阶段调用，实体输入与左右手物品空状态通过白名单代理提供。主 Mod 在普通 YSM 动画之后，把 CPM position/rotation/scale/visible/color/alpha/emissive 应用到当前帧骨骼。每实体 pose session 还隔离 physics 状态、physicalize 调用和粒子发射时钟；物理参数按上游 `physicalize(elasticity, stiffness, damping, friction, gravity)` 顺序积分，宿主运动项在没有 Lib 运动输入时为零。`CpmFeatures` 将物理、粒子、物品和附件转换为统一的不可变 IR，主 Mod 通过现有粒子 host 消费粒子事件，附件/物品定义则通过同一 IR 暴露给物品层。

## 校验与覆盖报告

JSON streaming 在建立来源树时限制深度、元素和字符串长度，整个包累加来源字节预算。拒绝损坏 UTF-8、非有限数值、非法路径、丢失部件文件、循环父链、非法向量维数和显式挂点的无效引用。两代格式都保留重复 JSON 成员并给出 warning，按上游 Gson 的最后字段覆盖；骨/部件名仍按各代的登记语义处理。旧版 parentIndex 记录父骨引用实际绑定的较早骨序号，不能在后续阶段把同名骨合并。旧版父骨必须先出现；新版独立几何可保留包外父骨符号，完整包读入后在包骨架中验证。

部件纹理优先绑定包内同 stem 的 png/jpg/bmp；同 stem 多扩展名拒绝歧义。包内找不到的有效纹理符号保留为宿主 ResourceLocation，并给出 warning，实际存在性和解码由未来的宿主接入校验。本阶段不解码图像，因此伪造图片字节也不会被解释成已可绘制纹理。

`CompatibilityReport` 仍把原始来源 chunk 标为 `READ`，以便调用方区分“来源可恢复”和“所有原模组外部效果完全等价”；未知主字段诊断，原始文件和嵌套来源树继续保留。静态几何、旧版表达式/tickVars、受限新版脚本 pose、物理近似、粒子事件和附件绑定已有独立运行时路径；纹理切换、粒子自定义材质、物品模型和部分宿主状态仍可能降级为诊断或空实现，不能据此宣称与原模组完全等价。

## 复用与验证

新版几何 reader 的布局/字段解析从上游 MIT 版本适配，使用纯数据代替 Minecraft ModelRenderer；原版权、许可全文和来源说明随 Lib 模块及最终发行物打包。没有把原 OpenGL、游戏骨架算法、JS 引擎或示例素材复制进产品。

单元验证覆盖两代结构、坐标与 UV 保留、默认/空列表、旧版重复成员与父骨绑定、动态字符串及脚本不执行、资源引用、循环与累计预算。独立的可选语料任务读取调用方本地目录，模型素材不进入仓库或玩家发行物。最终 shaded 前置的独立测试验证两代 API、Gson relocation 和 MIT 声明资源。读取成功不构成动画、物理、渲染或联机验收。

## 本地 1.12 语料验证

可用 `:ysm-runtime-java-cpm:upstreamExamples -Pcpm.upstreamRoot=<custom-models>` 验证游戏实例中直接平铺的旧版目录。2026-10-06 对 Cleanroom 实例的 `custom-models` 完成 158 个目录的读取和三帧姿态求值；模型资源不复制进仓库。该目录全部识别为旧版 CustomModel 结构。

## 1.16 格式与公开样本调查

Gamepiaynmo 的 `CustomPlayerModel` `origin/1.16.5` 分支仍使用同一套包入口：根 `model.json`、可选的多个几何 JSON、图片和 `animation.js`。根文件的 `parts` 指向 Bedrock 1.12.0 几何（`minecraft:geometry` 数组），骨架字段包含 `left_arm`/`right_arm` 的骨骼根列表；运行时会选择每个根及其全部后代，因此列表不是一个需要重挂父骨的连续树。`ModelLoader` 还读取 `skeleton.attachments`、`particles`、`items` 和 `animation.js`。源码位于 [Gamepiaynmo/CustomPlayerModel](https://github.com/Gamepiaynmo/CustomPlayerModel/tree/1.16.5)，该仓库声明 MIT。

目前找到的直接迁移工具是 [zerustu/CPM_Converter](https://github.com/zerustu/CPM_Converter)。它明确把 1.12 CPM 转成 1.16.5 包，输出 Bedrock `format_version: 1.16.5` 的 `*.geo.json`，并生成 `animation.js`；物理值原样写入 `model.getBone(...).physicalize(...)`，转换器自己也注明 scale 转换有限制。其源码确认高版本样本应按 `model.json` + `*.geo.json` + `animation.js` 读取，而不是把几何 JSON 当作独立 YSM 模型。

公开可下载的高版本模型样本目前主要属于另一个项目 [tom5454/CustomPlayerModels](https://github.com/tom5454/CustomPlayerModels)，例如 [kayooo-lol/my-minecraft-model](https://github.com/kayooo-lol/my-minecraft-model) 的 `bella.cpmproject` 和 [AbsoluteVendingMachine/diggy-mc-cpm](https://github.com/AbsoluteVendingMachine/diggy-mc-cpm) 的 `diggy.cpmproject`。这些文件是 ZIP 二进制项目，内部是 `config.json`、`description.json`、`anim_enc.json`/动画 JSON 和 PNG，并非 Gamepiaynmo 的 `model.json`/Bedrock 1.16 格式，不能直接标记为 `cpm-remake`。截至本调查没有找到可确认属于 Gamepiaynmo 1.16 的公开模型包；`CPM_Converter` 是目前最可靠的格式样本来源。

## 归档语料与第一人称验证

当前接入已按来源切分：`CpmLegacySceneConverter`/`JavaCpmLegacyPoseSession` 只处理 CustomModel，`CpmModernSceneConverter`/`JavaCpmModernPoseSession` 只处理 CustomPlayerModel；`CpmSceneCompiler` 与 `JavaCpmPoseSession` 只做分派和公共结果封装。YSM 侧对应 `CpmLegacyYsmAdapter`、`CpmModernYsmAdapter` 以及两个 render-pose adapter。

游戏外快照命令为：

```text
./gradlew -p runtime :ysm-runtime-java-cpm:offlineSnapshot \
  -Pcpm.snapshotDir=<model-directory> -Pcpm.snapshotOut=<snapshot.json>
```

快照记录来源类型、规范骨骼 parent/pivot/rotation、primitive 数量和 age=0/10/20 的 pose 值，使用与游戏 bake 相同的 reader、converter 和 pose session。它是后续离线渲染和上游矩阵逐项比较的固定输入。

2026-10-06 使用本地归档 `CPM-Collection` 完成全量无 Minecraft 渲染验证：`Legacy-Models` 的 158 个目录（包括外层目录包裹的 `12-Rubik/'s Cube`）和 `Remake-Models` 的 24 个目录都完成 `model.json`、几何和三帧姿态阶段读取；其中新版 21 个目录包含 `animation.js` 并完成 init/update/tick 求值。归档中明确的 1.16 样本包括 `192-zoroark_1.16.5_v2` 与 `193-prototgen_1.16.5`，另有 `197-lucario`、`199-okami amaterasu`、`200-Ori` 等新版包。

旧版 `fpLeft`/`fpRight`、旧版 `fpList` 以及新版 `skeleton.left_arm`/`right_arm` 现在会被转换为显式 `LeftArm`/`RightArm`/`Background` locator。每个列表成员及其后代作为独立子树并集选择，保留原始父骨和动态父变换，因此多根第一人称列表不会被错误重挂到同一根；缺失的旧版列表成员按上游行为降级为 warning。主 Mod 的第一人称 renderer 对左右臂和背景分别提取该 locator 集合，普通/背景 pass 不会重复绘制完整模型。
