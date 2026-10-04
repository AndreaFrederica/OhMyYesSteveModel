<!-- SPDX-License-Identifier: CC0-1.0 -->

# 场景包与通用网格 Schema

本页定义 `ysmlib/scene-package` 与 `ysm/general-mesh` 的 `0.1.0-unstable` 格式。两者均按完整原始版本字符串精确准入。读取成功只证明本页的结构，不证明每种来源行为已经求值或绘制；实现覆盖见[支持状态](../status/general-mesh-backend.md)。

## 场景包

场景包是单卷 ZIP32，推荐扩展名 `.yscene`。它保存模型、动画和依赖的原始字节，不把来源统一转为有损中间格式。所有访问限于包内文件；原文件中的网络地址或工作站路径不授予外部访问权。

归档必须具有完整中央目录及结束记录，无前缀、隐藏间隙、重叠内容或尾随数据。局部头与中央目录的名称、方法和 flags 必须一致；size/CRC 在局部头或 data descriptor 中与目录相符，解压后再验证。只允许 STORED 和 DEFLATED，不允许加密、分卷及 ZIP64。允许 ZIP 注释与 extra fields，但 extra fields 不得覆盖 UTF-8 文件名语义。

文件名按 UTF-8、大小写敏感匹配，不做 Unicode 归一化。名称使用 `/` 分隔，禁止空路径段、`.`、`..`、绝对路径、反斜杠、冒号和 NUL；同名文件/目录与文件占据祖先目录均无效。目录条目可以省略，存在时长度必须为零。消费者必须在解码前检查条目数量与累计声明长度，并在解码时检查实际累计字节预算。

根目录必须包含 UTF-8 的 `scene.json`，不能把它声明为源文件。其余文件全部保留，包括许可证和尚未使用的作者资产。JSON 禁止重复键、未配对 Unicode surrogate 和非有限数；当前版本拒绝未知或缺失字段，不把未来选项隐式加入旧制品。

```json
{
  "schema": "ysmlib/scene-package",
  "version": "0.1.0-unstable",
  "model": {"id": "avatar", "path": "models/avatar.pmx", "format": "pmx"},
  "settings": {"metersPerUnit": 0.08, "scene": -1, "fbxSkinSpace": "BIND_WORLD"},
  "animations": [{"id": "dance", "path": "motions/dance.vmd", "format": "vmd"}],
  "relocations": [{"owner": "models/avatar.pmx", "reference": "C:\\author\\tex.png", "target": "textures/tex.png"}]
}
```

以上所有键必需。数组可为空，`id` 为非空白字符串，模型与动作的 ID 在整个清单内唯一，`path` 必须指向现存文件。`model.format` 允许 `gltf`、`vrm`、`fbx`、`pmx`、`pmd`、`pmm`；`animations[].format` 允许 `gltf`、`fbx`、`vmd`、`vpd`、`vrma`、`bvh`。`gltf` 包括 JSON glTF 与 GLB，由源内容区分。格式声明不承诺任意动作与任意骨架可以自动重定向。

`metersPerUnit` 是大于零的有限数，显式规定求值输出一个几何单位所对应的米；在物理模拟外应用。MMD 求解继续使用源 MMD 单位；FBX 求值输出已经换算成米，通常填写 `1`。示例中的 `0.08` 是作者设置，不是格式默认值。`scene` 必须为整数且不小于 `-1`；非负值选择源场景，`-1` 显式选择全部节点。没有 scene 概念的源格式须使用 `-1`。`fbxSkinSpace` 只允许 `BIND_WORLD` 或 `FOLLOW_MESH_NODE`，分别保留 FBX 世界绑定与网格 bind-pose 运动补偿，不按文件名推断。

每个 relocation 的 `owner` 和 `target` 都是现存包内文件名，`reference` 是原始引用字符串。映射按 owner 与 reference 精确匹配，同一对不能出现两次。owner 不是全局搜索路径：另一个模型文件不会自动获得该映射。

依赖解析先查显式映射。没有映射时，glTF/VRM 使用相对 URI，百分号解码恰好一次，禁止 scheme、authority、query 和 fragment；MMD/FBX 使用字面文件路径，仅把反斜杠视为分隔符。相对依赖从 owner 的目录解析，允许 `..` 返回包内上级，但不能逃出包根。绝对路径和网络引用只能通过显式 relocation 指向已有包内文件。缺失依赖使相应来源的加载失败，不从玩家文件系统补读。

读包校验清单引用与归档完整性；模型读取器随后校验原文件及其实际依赖。清单闭包与来源语义闭包是两个检查层次，不能把前者当作后者已通过。

MMD 的内置 toon 是来源格式的显式例外：PMX 共享 toon 标志选择 Lib 固定的十张默认图片，编号为 `0..9`。PMD 的 toon 引用先按上述规则解析包内文件，只有缺失的裸文件名 `toon01.bmp` 至 `toon10.bmp`（名称比较忽略 ASCII 大小写）可以回退到对应内置图片；没有可选 toon 表时按材质编号生成默认名。空 toon 路径表示没有 toon。已存在但损坏的图片、带目录的引用、普通颜色/球面贴图引用和其他缺失路径不适用回退。内置资源带随库发布的来源、版本和哈希清单，也受图片解码与宿主存储预算限制。

## 通用网格容器

`ysm/general-mesh` 使用现有 [Asset Container](asset-container.md)，property 0 为精确版本 `0.1.0-unstable`，property 1 为 producer，property 2 为完整小写 ModelId。它与 `mixel/character` 是两个显式 schema，不能通过旧 Manifest 中的未知字段激活。

通用网格容器复用[当前 Manifest wire](model-schema/proto/manifest/manifest.proto)的身份、作者、许可、展示图、target role 与公共资源描述。Manifest 仍是首个普通 direct chunk，metadata prefix、内容 hash、完整 ModelId/ContainerId 及 preview source 的规则仍适用。复用 wire 不改变旧 schema 的字段含义。

每个 target 的正 `blob_id` 指向一个完整场景包；其材质、纹理和动作包含在包中，Manifest 的 target `textures` 必须为空。所有 target 仍须具有明确 kind，存在唯一 id 为 `player` 的 player target。`readDefinition` 只适用于 MC schema；通用网格消费者必须按新 schema 读取场景包，不能尝试用 `ModelData` 解析后猜测格式。

场景包 blob 可以直接存储或使用容器支持的压缩。消费者在分配和解码前检查逻辑长度，再验证容器 hash、场景包结构与源文件内容。导出保留 schema、ModelId、原作者和许可声明；复制原 stored chunks 不改变模型后端。几何/材质与运行设置由场景包及对应源格式解释，MC 专属动画、cubes 或纹理选择语义不进入该 target。

当前宿主只完成新 schema 的读写分派与场景包入口，资源发布、M2 来源语义闭包、渲染和联机实测尚未全部接入；不能以本页作为这些能力已经可用的声明。
