# 通用网格后端支持报告

Oh My Yes Steve Model（Oh My YSM）在现有 MC 风格后端之外提供通用网格后端。Lib 已实现多种格式的读取、动画与物理求值、派生缓存及独立配置工具；主 Mod 已接入模型目录、选择、Alt+Y 预览与编辑、实体绘制和动作驱动。当前仍有格式语义与游戏验收缺口，不能把这些能力表述为完整 VRChat 或任意复杂骨架兼容。

产品目标由[双后端决定](../product-decisions/decisions/dual-model-backends.md)定义。本报告记录实现和验证范围，不改变该目标。场景格式、逐模型配置和缓存仍处于 unstable 阶段，后续版本可能改变 schema 或缓存 ABI。

## 后端与模块边界

| 层 | 职责 | 当前边界 |
|---|---|---|
| MC 后端 | 原有模型、控制器、Molang 与绘制 | 保留原管线；通用后端不替换其可变状态 |
| Oh My YSM Lib | 格式读取、不可变场景、骨骼与动画求值、独立物理、CPU 准备缓存、CLI/GUI | Java 17 为托管基线；FBX native ufbx 优先，WASM 为回退；Bullet、MikkTSpace 仍按各自能力选择 |
| 主 Mod | 模型 catalog、网络、按键、页面、玩家输入、第一/第三人称、GPU 资源 | 已接线不等于所有格式、世界和第三方 Mod 组合均完成验收 |

主 Mod 的可选 native 能力与旧版官方 native 实现独立。运行不要求安装旧版 native；当前加速不可用时保留相应 Java/WASM 路径。具体资源所有权见[通用网格架构](../architecture/general-mesh-runtime.md)。

## 输入格式与兼容边界

VRChat 是模型使用环境，不是单一文件格式。模型包可能混合 FBX、图片、Unity Prefab、材质、控制器及 VRChat 组件；只取得网格不能还原整个 Avatar。

| 输入 | 已实现内容 | 未完成或限制 |
|---|---|---|
| PMX / PMD | 网格、材质、完整骨架、morph、IK、刚体/关节与软体绑定 | PMX near-anchor 明确拒绝；复杂层序、实时外部亲及实际软体世界表现仍需验证 |
| VMD / VPD | 骨骼、表情、姿态、IK 开关与来源插值；VMD 镜头、灯光、自阴影采样 | 读取光影轨道不代表宿主绘制其效果 |
| PMM 1 / 2 | 项目结构、路径重定位、内嵌动画与模型物理开关 | 完整多模型项目播放、配件及媒体运行尚未完成 |
| glTF / GLB | 节点、skin、morph、动画、核心 PBR / unlit，缺失法线/切线准备 | 尚未实现全部扩展；必要扩展不支持时不能宣称完整兼容 |
| VRM 0 / 1 | 分版本配置、人形控制器重定向、VRMA/局部动作层、表情、视线、约束、扩展 SpringBone、第一人称几何与 MToon 材质 | 三份公开 VRM 0/1 Avatar 的 CPU/GPU 双视图像素与回退已对照；Forge 与 shader-pack 组合另行验收 |
| VRMA | 多动画、人形重定向、髋部尺度、表情与视线 | 缺失目标表情会诊断；跨工具和游戏表现不由单一参考测试保证 |
| FBX ASCII / binary | 层级、pivot、单位、角点属性、skin、morph、动画层/曲线、显式纹理依赖；可选 `_omysm/fbx-mapping.json` 选择明确的 `FbxSkin`，可选 `_omysm/fbx-physics.json` 通过共享 PhysicsWorld 驱动刚性节点，native resident ufbx 直接支持线性、刚性、DQ 和混合 DQ skin 的物理 bone delta | 未配置选择的多 skin 组合、非归一 cluster、带剪切 DQ、FBX 原生约束求解、geometry cache、subdivision、非多边形几何未完成；soft-body native palette 仍在实现；宿主仅做核心 PBR 适配 |
| BVH | 有界骨架读取及源 Euler 通道采样 | 通用跨骨架重定向尚未接入 |
| Gamepiaynmo CustomModel / CustomPlayerModel | 独立 Lib 来源识别、两代结构读取、新版 Bedrock 1.8/1.10/1.12 几何、旧版表达式/tickVars、受限新版 animation.js proxy；主 Mod 可将 box/quad/cube 和 box/per-face UV 转换到现有 YSM GeoBuilder/baked model 数据 | 仍未完成 CPM 物理、粒子/物品宿主、模型 catalog/实体自动接入、网络和完整游戏验收；见 [CPM 来源解析](../architecture/cpm-source-parsing.md) |
| `.blend` | 可读取 Blender 导出的 glTF/GLB 或 FBX | 没有直接 `.blend` 读取器；Modifier、Geometry Nodes、驱动器和工程依赖不能由读取导出网格自动还原 |
| `.unitypackage` | gzip/tar 与 pathname 读取、包内 FBX 和图片提取、Unity `.mat` 的 `_MainTex`/`_BaseMap`/法线/自发光到核心 PBR 的覆盖映射 | 当前选择最大的 FBX；缺少 Prefab/Avatar 候选选择、完整 GUID 依赖闭包、Unity `.anim` / Animator / Shader、PhysBone / Contact / AvatarDescriptor 语义；自定义 Toon Shader 只保留标准 PBR 子集 |

UnityPackage 当前入口会把材质覆盖写入 `_omysm/fbx-mapping.json`，因此包内 FBX 与 `.mat`、图片一起输入时可以先得到有颜色的核心 PBR 模型；它仍不承诺自动还原多 Avatar、多衣装或复杂 Unity 工程。需要这些内容时，应先用源工具导出明确的模型与动作。可选 Blender 导出桥接和完整 Unity 导入编辑流程属于[后续方向](../future/general-mesh-backend.md)。

格式识别、字段保留、行为求值和最终绘制是不同能力。不支持的语义必须诊断或拒绝；保留原始字段不能作为行为已实现的证据。

## 非破坏性配置与导出

模型采用源文件旁的 `.omysm.json` 附加配置，保存 YSM 元数据、单位/目标身高、放置、骨骼绑定、轴和参考姿态、动作状态、过渡及持物挂点。FBX 另可使用 `<文件名>.fbxmap.json` 描述底层 Mesh 到 `FbxSkin` 的选择；它与通用 profile 分层，映射变更会使选定 Skin 和派生几何缓存失效。编辑配置不改写原模型、纹理或动作字节；保存检测冲突并使用同目录原子替换。

`.yscene` 用于封闭场景归档，`.mxc` 用于模型运行容器。配置随场景包保存；标准与覆盖规则见[逐模型配置](../standards/scene-model-profile.md)和[场景包](../standards/scene-package.md)。这些格式不承诺复现 Blender 或 Unity 工程的全部可执行行为。

Alt+Y 提供尺寸/放置、骨骼/动作映射、描边及挂点编辑。进入编辑器默认关闭物理，手动开启后在后台初始化独立实例。Lib 的 CLI 与 Swing GUI 读写同一配置，支持检查、初始绑定、参考骨架校准、动作设置、诊断绘制和打包。调用见[构建指南](../build.md#独立模型配置工具)。

## 动画、映射与持物

来源动画保留曲线和插值信息。完整目标骨架不会被裁剪成几个角色骨；未绑定骨保持来源局部姿态并继承父骨，再由源格式的追加变换、IK 和物理处理。

游戏已接入 YSM 源骨架驱动 MMD 和 VRM：配置指定源骨名及目标骨，运行原 MC 玩家控制器与 Molang，Lib 将已求值的刚性姿态转换到目标静止骨架。有源绑定且状态未被显式覆盖时使用源控制器；显式 VMD/VPD、REST、生成动作及轮盘片段拥有动作选择权。

| 能力 | 当前状态与限制 |
|---|---|
| 骨骼绑定 | 自定义骨名、同源多目标权重、轴校正、位移比例和参考姿态可配置；辅助骨继承链保留 |
| 原生 YSM 重定向 | 内置 walk/run 原曲线通过生产求值与 MMD 变形测试；尚无全部游戏状态的实机姿态对照结论 |
| 复杂源通道 | 刚性接口不能表达绑定骨缩放时明确报错；隐藏、材质、表情尚未映射，twist/swing 分解及 append 所有权协调未完成 |
| 来源动作与状态 | VMD/VPD 或来源 clip 可绑定 YSM 状态；一次性来源过渡可配置，目标变化会打断；尚无 YSM/VMD 跨后端姿态混合 |
| 动作轮盘 | 来源及配置内骨骼/表情动画进入 manifest 白名单；表达式片段独立于身体，额外源 YSM 动作已接线；时间戳恢复已有世界 tick 下的进度，实机联网和自定义源模型仍需验证 |
| 源资产分发 | 配置可选 MC 动画源模型；附属源模型的自动跨客户端分发尚未完成 |
| 手持物 | 最终骨架挂点支持分别按物品/方块 ID/tag、类别、手、状态和视角覆盖；逐手扩展和刀/鞘部件入口已接线，第三方武器画面尚未验收 |
| map 表情与动画 | MMD Morph、节点 Morph、VRM 表情可显式组合；命名关键帧、独立表情调用/释放、眨眼/眼球输入有数值回归；FBX authored adapter 尚未实现，明确拒绝 |
| 手部动作层 | 配置内/来源片段显式骨/Morph 掩码；绑定 MMD 和 VRM 可以在来源身体上叠加当前 YSM 手臂控制器，淡入淡出独立；全部 use/swing 和武器组合需实机验证 |
| 第一人称 | 成功绘制后才消费原版手；MMD 按头骨后代权重过滤独立视图索引，VRM 使用原生 firstPerson 并叠加作者过滤；两者支持相机原点；源模型第一人称专用曲线尚未转移，不能视为 VRChat Avatar 全功能 |

独立 GUI 可播放真实来源动作、暂停、逐帧和跳转，并检查骨架及几何。它采用 CPU 诊断绘制，尚未模拟完整 Minecraft 状态输入、原生 YSM/Molang 或游戏材质，因此不能用它验收全部内置动作和最终颜色。机制见[独立配置工具](../architecture/scene-authoring-tools.md)。

## 渲染与物理

主 Mod 已接入通用 target、实体及预览 host，提供 MMD surface/描边/点线、VRM MToon 0/1 核心 surface，以及 glTF/FBX 核心 PBR/unlit。预览、第一人称和第三人称使用共享静态资源，播放状态与物理实例隔离；同逻辑帧重复绘制不重复推进物理。

普通世界与第一人称已接入 Minecraft 的实际光照贴图，分别采样方块光与天空光，避免露天夜晚因天空光等级 15 被当作满亮。MMD 环境色和描边也参与受光；glTF/VRM 按线性空间处理，明确 unlit 与自发光保留来源语义。Alt+Y、编辑器和纸娃娃使用独立预览照明。独立 GPU 回归覆盖同一天空光坐标的昼夜纹理变化、方块光轴、全暗、透明度、自发光、unlit、描边及纹理/sampler 恢复；这些检查不替代真实 Forge 的天气、维度、药水效果和 shader-pack 验收。

MMD 支持 Java 参考变形、可选 native packed 蒙皮，以及满足设备/预算条件时的 OpenGL 4.3 compute skinning/morph。GPU 不可用或发生失败时从同一逻辑姿态回退 CPU；含 soft-body 的路径使用 CPU。上传 staging/VBO 复用，静态索引保留，动画绑定和物理构建批量化。上述机制不能保证任意重模型的世界帧率。

VRM 已接入预编译 CPU LBS 与可选 OpenGL 4.3 计算蒙皮/稀疏 morph，同姿态故障回退保留表情与物理结果。两个视图共享顶点，索引和未变调色板复用；累计保留内存包含上传 staging。模型元数据、作者、原生许可字段、缩略图与 MToon 描边配置进入现有模型管理/编辑入口。SpringBone 扩展支持 inside sphere/capsule、plane 和 cone/hinge/spherical limit，求解后保留定长约束。

MMD 优先使用已安装的 native Bullet ABI 6；缺失或启动自检失败时使用 JVM/WASM ABI 5。两者接入相同宿主环境接口，支持线/角运动惯性、动态世界重力、流体阻力/视觉浮力、附近方块 VoxelShape 接触及瞬移动量重置。环境形状与来源刚体编号分开，native 每帧一次 direct JNI 发布。VRM SpringBone 接入同一运动/流体输入，并在自己的尾端球体求解器中处理方块接触。环境采样和模型编辑参数属于本体，接口与求解属于 Lib；不改变玩家移动、hitbox 或伤害。

范围限制：采样只覆盖已加载的邻近方块/流体，半径最多八米，各类型最多 4096 个盒；超预算警告。水流方向按一米/秒视觉策略使用，浮力为中心/顶点采样而非体积水动力学；实体受击等通过实际运动轨迹驱动惯性，没有向 Minecraft 回传来源刚体的力。无来源物理的格式不自动生成刚体。

灯光、镜头和阴影读取及时间线数据保留并提供扩展入口；内建灯光/阴影效果暂未完成，由其他 Mod 承接。普通基岩版 GPU Compute Renderer 也未完成，不能与 MMD/VRM 网格 GPU 变形混为一谈。

场景图片目前支持 PNG/JPEG/WebP/ZTX/BMP/TGA。高精度 AVIF、DDS、KTX2、HDR/EXR、CMYK 转换及 BMP 内嵌 PNG/JPEG 未接入场景解码；其他路径的 codec 支持不能直接推导为场景支持。保留来源元数据不表示完成源软件颜色管理。

## 缓存与加载性能

| 阶段 | 可复用结果 | 重启或变更后的工作 |
|---|---|---|
| 来源转换 | 转换后的 `.mxc` | 来源/转换 profile 改变时重建 |
| 文档解析 | 模型、动画、依赖、共享网格和 MMD 轨道绑定 | 缓存失效或不可读取时重新解析 |
| 静态准备 | 初始几何、法线/切线、第一人称视图与 surface 适配 | 影响几何的配置和 profile 改变时重建 |
| 图片与材质 | 解码、预算降采样和按用途转换的像素 | 内容、预算或处理 profile 改变时重建 |
| GPU 与活动会话 | 同进程有效资源可共享 | 新进程仍需 GPU 上传与独立播放/物理初始化 |

生产 loader 已接入持久准备缓存，默认磁盘预算 4096 MiB，单条派生物最多 1024 MiB；缓存有内容/schema/预算校验、原子发布与淘汰。仅修改尺寸、骨骼映射或挂点，不要求重新解析源模型或解码未变纹理。阶段进度和日志区分缓存检查、命中、首次生成、重建原因与 GPU 发布耗时。具体键和失效规则见[存储与缓存](../architecture/model-management/storage-and-cache.md#通用网格派生缓存)。

当前预算降采样与 mipmap 不等于按 Alt+Y 视口、游戏分辨率和距离动态切换的完整纹理 LOD；多级纹理持久缓存仍未完成。

目录刷新已按变化范围处理：sidecar 只指向对应模型，普通文件不扩大到父目录，未变项不重入加载队列并保留转换索引。编辑器临时文件被忽略，保存通知与 watcher 在静默窗口合并；迟到的重复事件若 stamp 未变，不重新加载。贴图、buffer 和动作变化也进入模型失效判断。首次启动、手动 reload、overflow 或根 identity 改变仍完整扫描；当前通用依赖收集不是最小引用闭包，同目录变体可能共同刷新。配置仍参与身份及重新打包，扫描优化不消除单模型转换成本。定向回归已覆盖上述范围与缓存保留，真实大目录和游戏启动总耗时仍需验收。

## 验证证据与使用风险

| 范围 | 已有证据 | 不能推导的结论 |
|---|---|---|
| Java 自动化 | 主项目测试及 Lib Core/MMD/工具测试通过；部分主项目测试跳过 | 不替代游戏交互、平台和第三方兼容验收 |
| 读取与求值 | Khronos、three-vrm、MMD、ufbx 与 Blender 固定参考覆盖几何、曲线、视图、材质参数及轨迹 | 不代表这些格式所有扩展或源软件行为均完成 |
| 独立分发 | Java 17 javaOnly 的前置分发 smoke 覆盖场景、MMD/PMM/BVH/VRM/VRMA/FBX | 不代表实际 Forge 联机及所有运行环境已验收 |
| GPU | 独立 GL3.2 材质/颜色空间/状态恢复与 GL4.3 MMD/VRM 变形对照；部分真实 PMX 的数值和像素检查 | 不代表 shader-pack/MRT/MSAA、全部硬件或游戏世界一致性 |
| VRM Forge 实机 | Windows / Forge 47.4.3 / MC 1.20.1：官方 VRM 0、非零静止轴 VRM 1 的远端导入与选择、YSM 驱动、空手/剑盾、FirstPerson 身体/相机回退；VRM 0 双客户端互相绘制，正常退出无残留测试进程 | 不覆盖所有动作、第三方武器、长期 GPU/RSS 回收或 shader-pack |
| 可选 native | 既有 Windows/Linux x64 物理、蒙皮与分发检查；FBX native ufbx bridge 已通过 Windows x64 构建、真实动画网格 WASM 对照、线性 physics 对照和 DQ/混合 DQ physics 样本测试；连续帧使用 resident ufbx 与复用 DirectBuffer，不经过 JSON/Gson | FBX native 尚未验证 Linux/macOS、Forge 端到端和多线程并行实例；当前 native 上下文仍按单实例有界句柄管理 |
| 持久缓存 | 跨格式命中、禁止重解析/解码、损坏、预算与取消回归 | 不代表扫描、包捕获、GPU 上传和物理初始化可以免除 |

VRM 材质近景回归补充：Alicia VRM 0 曾出现粗灰壳，原因是旧版描边宽度漏乘 `0.01`；原有无 GL 错误检查和 CPU/GPU 一致性未能发现共有着色器错误。已修正版本单位、顶点宽度遮罩（R/G）、材质内描边顺序/深度及明暗贴图混合。新增独立解析期望的像素边界/颜色断言，覆盖单位、宽度遮罩不改变 alpha、缩放及明暗贴图；Alicia/Seed 正反面 768×768 图像留档；Alicia 独立 Forge 场景通过第一人称、镜头回退及第三人称恢复，并检查实机截图。袜子细条纹存在于原始贴图，不属于需要消除的缺陷。这些检查不构成所有 VRM 材质与外部渲染器逐像素一致的保证。

VRM 性能回归在 Intel Iris Xe、256×256 双视图、12 帧预热和 40 帧采样下，Seed/Constraint/Alicia 单实例 GPU p50 分别约 22.8/17.3/19.6 ms，对应 CPU 38.0/41.5/27.8 ms；四实例分别约 77.2/66.7/81.3 ms，对应 CPU 122.5/151.3/96.2 ms。渲染线程分配下降约 66–83%，稳态顶点/索引上传为零；MMD 与 VRM 数值、真实模型双视图像素及故障回退同时通过。该测试计入 GPU 等待，不代表游戏 FPS，也不保证所有模型更快。CPU LBS 独立 10k/50k 顶点、4/8 影响测试相对参考路径耗时减少约 37–54%、分配减少约 92%。复现入口见[构建指南](../build.md#vrm-交互与性能验证)。

性能检查区分冷转换、冷准备、缓存命中、物理初始化、CPU 求值、GPU 上传及绘制；headless 测量与独立 GL 宿主结果不当作 Minecraft FPS。公开参考及校验信息随测试资源维护，构建和独立 GPU 检查见[构建指南](../build.md)。

宿主环境回归覆盖大坐标、匀速/加速、转向、瞬移、重力坐标换算、真实方块接触、流体、软体、重复发布及回放，Lib Core/MMD/VRM/WASM/native 共 225 项测试通过。Windows x64 合成负载（128 刚体关节链、64 软体顶点、25 方块及 25 流体盒）中，环境发布、步进及 packed 读回的 native 中位数约 0.33 ms，WASM 约 6–6.5 ms；真实 Forge 的运动、游泳、墙面接触与帧率仍需实机验收。

优先待验收或补齐的项目包括：大目录实机刷新与启动成本、最小依赖闭包、Unity Avatar 导入流程、复杂骨架重定向、全分辨率/距离纹理 LOD，以及更多模型与动作的真实 Forge 预览颜色、落地/尺寸、持物、第一/第三人称、多人及长期资源回收。尚未完成项应持续保留明确诊断，不能静默当作成功。
