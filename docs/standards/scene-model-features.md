<!-- SPDX-License-Identifier: CC0-1.0 -->

# 模型表情与交互配置

这是 [逐模型配置](scene-model-profile.md) 中的 `features` 对象，仍处于 unstable 阶段。产品依据为[游戏内创作契约](../product-decisions/decisions/dual-model-backends.md#bcmesh-in-game-authoring)。出现 features 时下列字段及嵌套记录的字段均须完整提供；未知字段拒绝。缺省 features 使用空映射、空列表、BONE 坐标空间、关闭程序控制及无第一人称过滤。

| 字段 | 值 |
|---|---|
| expressions | 语义名称到表情组合的对象 |
| animations | 名称到配置内关键帧动画的对象 |
| sourceAnimations | 名称到宿主 YSM 源动作的对象 |
| itemRules | 持物规则数组 |
| layers | 动作层规则数组 |
| socketSpace | BONE 或 RETARGET_REFERENCE |
| procedural | 自动眨眼和眼球控制 |
| firstPerson | 第一人称过滤及原点 |

名称非空、最长 128 字符；所有浮点数有限。动作、表情与骨引用保存明确身份，不能根据近似名称静默重绑。

## 表情与关键帧

每个 expression 为 `{targets, blocks, fadeIn, fadeOut}`。targets 数组非空，每项 `{kind,node,index,name,weight}`，weight 在 `[0,1]`。同一组合不能重复目标。MMD_MORPH 使用 `node=-1` 和原始 Morph 索引、原名；NODE_MORPH 使用拥有网格的 node 和该网格 Morph 索引、原名，所有 primitive 的对应身份须一致；VRM_EXPRESSION 使用 `node=index=-1` 与该版本 VRM 的表情 key。失效索引、名称变化、格式不相容均拒绝。blocks 列出已定义的语义表情；非零输入抑制对应通道，也抑制被阻断的自动眨眼。fadeIn/fadeOut 为非负秒数。

输入权重零表示主动抑制，缺省输入表示释放所有权。淡出混回当时来源值，不能清空全模型 Morph。同目标的组合贡献取最大值。VRM_EXPRESSION 输入继续经过源版本的 binary、override、材质与 UV 规则。VRM 的 NODE_MORPH 是显式直接形变，在 VRM 表情求值后覆盖指定节点目标；不隐式触发 VRM 材质或 override 规则，释放时恢复当前来源表情形变。需要版本表情语义时应使用 VRM_EXPRESSION。

每个 animation 为 `{duration,framesPerSecond,tracks}`，duration 与 framesPerSecond 严格为正，tracks 非空。每轨为 `{kind,binding,property,interpolation,keys}`。kind 为 BONE 或 EXPRESSION；BONE binding 引用 bones 键，EXPRESSION 引用 expressions 键。不能重复同一 kind/binding/property 通道。

| property | 值与单位 |
|---|---|
| TRANSLATION | 三分量；骨骼的来源单位；MMD 是相对静止骨架的局部差值，glTF/VRM 是节点局部值 |
| ROTATION | `[x,y,z,w]` 非零四元数；MMD 是局部旋转差值，glTF/VRM 是局部旋转 |
| SCALE | 三分量节点局部缩放；MMD 明确拒绝 |
| IK_ENABLED | 单分量 0 或 1，MMD IK 控制骨开关 |
| MORPH_WEIGHTS | EXPRESSION 专用，单分量 `[0,1]` |

interpolation 为 LINEAR 或 STEP。旋转 LINEAR 使用归一化四元数插值。key 为 `{time,value}`；time 为非负秒数，严格递增且不超过 duration，value 宽度须匹配属性。调用 ID 为 `@ysm/profile/<name>`，clip=-1。仅含表情轨的轮盘动作作为独立表情动画播放，骨骼与表情组合按完整身体片段播放；独立 Lib 接口可显式请求表情片段并传入采样时间。

sourceAnimations 每项为 `{animation,duration,framesPerSecond}`；animation 为源 YSM 动作原名，后两项是正数。调用 ID 为 `@ysm/source/<name>`，要求 sourceBones。此入口由 Minecraft 宿主运行原控制器；独立 Lib 不具备 YSM/Molang 宿主，不能伪造预览成功。自定义源模型须在接收方资源系统可用，配置中的哈希本身不包含它的资产。

## 持物与方块规则

每个 itemRule 为 `{id,priority,match,socket,display,renderer,parts}`。priority 为整数；最高优先级匹配规则胜出，同优先级后条胜。无匹配使用该手 heldItems 默认挂点。socket 完整替换默认挂点，使用 [HandSocket](scene-model-profile.md) 的所有字段。parts 为额外呈现同一物品的数组，每项 `{socket,display,renderer}`，例如刀与鞘的独立挂点。

match 为 `{items,tags,blocks,blockTags,categories,hand,view,state,useAction}`：

| 条件 | 语义 |
|---|---|
| items / blocks | 分别是物品、所持 BlockItem 的方块注册 ID 数组 |
| tags / blockTags | 分别是物品标签、方块标签 ID 数组，两个注册表域不混用 |
| categories | 类别字符串数组，可与具体 ID/tag 同时约束 |
| hand | ANY、LEFT、RIGHT、MAIN、OFF；解剖学左右与惯用手区分 |
| view | ANY、FIRST_PERSON、THIRD_PERSON |
| state | ANY、IDLE、USING、SWINGING、CHARGED；宿主判定次序为使用、挥动、充能、空闲 |
| useAction | 空字符串不约束，否则匹配 Minecraft UseAnim 名，如 BOW、BLOCK、EAT、DRINK |

各数组内为 OR，数组/手/状态等维度之间为 AND，空数组不约束。ID 格式为 `namespace:path`；可用模组标签定义更多分类，不根据翻译名猜类别。

宿主内建类别为 item（非空物品）、block、torch、lantern、stairs、slab、fence、fence_gate、wall、door、trapdoor、bed、chest、shulker_box、falling_block、plant、leaves、glass、sign、skull、sword、tool、pickaxe、axe、shovel、hoe、bow、crossbow、shield、trident、fishing_rod、food、drink、gun、slashblade。同一物品可属于多个类别；标签和具体 ID 可以覆盖类别分类不足的模组物品。

例如，match 以下对象选择副手持有的橡木楼梯，并限定方块标签；作者可将它用在独立的 itemRule 中：

```json
{"items":[],"tags":[],"blocks":["minecraft:oak_stairs"],"blockTags":["minecraft:stairs"],"categories":["stairs"],"hand":"OFF","view":"THIRD_PERSON","state":"ANY","useAction":""}
```

display 为 INHERIT（当前视角相应左右手变换）、FIRST_PERSON、THIRD_PERSON、FIXED 或 NONE。heldItems 的总启用及第一人称 VANILLA/MODEL/HIDDEN 仍控制宿主是否呈现；NONE 有意隐藏该手。renderer 是扩展键，vanilla 使用原版物品呈现。扩展逐手报告 PASS/HANDLED/HIDDEN；一个手的接管不能吞掉另一个手。内建 slashblade:blade、slashblade:sheath 可分开绘制，SlashBlade OBJ 厘米单位由适配器转米，socket.scale 是额外倍率。TACZ/Superb Warfare 的专用路径仅在相应模组可用时接管。

socketSpace=BONE 保留最终来源骨骼轴；RETARGET_REFERENCE 在显式 YSM 驱动骨上抵消该绑定的全局参考旋转；VRM 还抵消节点的全局静止轴，再应用挂点偏移。控制器层按混合顺序计算参考权重，挂点用该权重从单位旋转插值到逆参考旋转，使淡入淡出连续；全权重与完整 YSM 驱动一致。来源 VMD 骨不无条件扣除参考姿态。此设置不猜测火把或弓的视觉朝向；模型作者仍可对具体物品和状态设置旋转。

## 动作层与程序控制

layer 为 `{id,priority,match,animation,bones,expressions,weight,speed,fadeIn,fadeOut,mode}`。match 使用上述规则；animation 使用 actions 的记录，bones 和 expressions 是显式掩码，至少有一个非空；weight 在 `[0,1]`，speed 正数，fade 为非负秒数。宿主每手选择最高优先级规则，合并同一规则后按优先级顺序混合。层保留自身时钟，身体动作改变不重启该时钟；退出淡出，释放给当时来源。只选手臂骨不能隐式取得脸部通道。

mode 为 ANY、SOURCE_CLIP（仅来源身体片段状态）或 YSM_CONTROLLER（仅完整 YSM 控制器驱动状态）。层可引用配置内片段或同骨架来源动画。glTF/VRM 外部文档具有不同节点域，不能直接按索引套用；VRMA 层通过其 humanoid 语义重定向到 VRM，表达式掩码引用 profile 语义名，再选中其原生 VRM 目标。非 MMD 的语义表情层只能来自配置内片段或 VRMA。宿主 `@ysm/controller`、clip=-1 表示当前已求值的 YSM 姿态层，适用于已绑定的 MMD 或 VRM 骨骼且 expressions 必须为空；可将手臂应用于 VMD、VRMA 或配置内身体动作，同时保留原有 hold/use/swing 控制器。它不进入轮盘来源目录。

procedural 为 `{autoBlink,blink,interval,duration,eyeTracking,leftEye,rightEye,maxEyeAngle}`。blink 引用已定义的表情；interval/duration 为正秒数且 duration<interval；phase 由 profileId 确定，同一帧多视图与跳转结果一致。leftEye/rightEye 为骨绑定键或空字符串，maxEyeAngle 为正角度；MMD 眼球输入限幅后应用，VRM 继续使用源 LookAt 规则。格式缺省关闭两项控制；宿主可为无作者配置的 VRM 根据原生能力启用。来源/VRMA 已拥有的 blink 通道优先于自动眨眼。缺省 blink="blink"、interval=4、duration=0.15、空眼骨、maxEyeAngle=20。

firstPerson 包含 `{hiddenMaterials,originBinding,x,y,z,hiddenBones,boneThreshold}`，并支持下述可选偏移字段。hiddenMaterials 是原始材质索引数组；originBinding 是骨键或空，x/y/z 是米制原点偏移，骨原点独立于 heldItems 启用开关。hiddenBones 是 MMD 或 VRM 骨键数组，包含后代；boneThreshold 在 `(0,1]`，默认 0.5。顶点归一化影响权重达到阈值即被排除，包含该顶点的三角形从第一人称索引列表剔除。材质索引域与第三人称几何保留；MMD 骨过滤支持三角网格；VRM 在原生 firstPerson 规则之上追加隐藏，覆盖刚性后代和蒙皮影响，保留支持拓扑的连接与绕序；其他格式明确拒绝。双视图来自同一动画和物理帧，不能重复推进物理。

originBinding 的重定心仅用于相机空间的 RenderHandEvent 呈现。FirstPerson Mod 的身体 pass 已包含玩家到相机的位置及身体旋转，只使用正常 placement 加 x/y/z，不能再次减去头骨高度。同一模型在当前 render sequence 成功提交身体 pass 后，手部入口复用该提交结果和逐手替换位，不重复绘制整个人物；仅安装/启用兼容 Mod 不代表已经提交成功。未提交时相机空间回退按当前相机俯仰和相机相对身体的 yaw 放置人物；没有 originBinding 时使用实际眼高。物品与身体使用同一入口的放置矩阵。

自动迁移的 map 保持 originBinding 为空，沿用玩家眼高。MMD 头骨枢轴通常在颈部，不能把名为 head 的骨键自动当成眼睛位置；显式骨原点需要作者提供并验证相机位置。头部几何过滤与相机原点选择独立，清空 originBinding 不取消 hiddenBones。

firstPerson 的可选 originX/originY/originZ 默认均为 0，表示 originBinding 骨的局部源单位视点偏移；最终骨矩阵（含旋转、缩放）先变换该点，再转为米制并应用 placement。它不受持物参考轴校正影响，只在相机空间回退的重定心中使用。VRM 0 默认使用原生 firstPersonBone/offset（转换 legacy Z），VRM 1 使用 head 的 LookAt offsetFromHeadBone；缺失时可用双眼骨的静止中点，仍缺失则保留玩家眼高。作者显式配置优先。VRM 0 原生视点若指定非人形骨，保留为独立骨键，不将它接入身体控制器。

firstPerson 还支持可选的 cameraX/cameraY/cameraZ，单位为米，旧文件缺省均为 0。它们只作用于 RenderHandEvent 的相机空间回退，在相机相对身体旋转后、模型 placement 前叠加；X 向身体右侧，Y 向上，Z 向身体后方。没有 originBinding 时，cameraY 在玩家眼高定位的基础上叠加；显式骨原点时不再扣眼高。FirstPerson Mod 的世界身体、第三人称、物品栏和小人预览均不受这三个值影响。自动 VRM 默认配置和自动迁移配置可显式使用 cameraZ=0.25 模拟标准 FirstPerson 的身体后移，作者可以逐模型调整或设为 0；宿主没有固定的后移常量。

例如可在 features.firstPerson 中保留头部过滤并微调相机回退：

```json
{"hiddenMaterials":[],"originBinding":"","x":0,"y":0,"z":0,"hiddenBones":["head"],"boneThreshold":0.5,"cameraX":0,"cameraY":0,"cameraZ":0.25}
```

通用模型如果资产旁存在 `.omysm.json`，或 VRM 使用自动生成的人形配置，物品由 profile 规则和 `RenderSceneItemEvent` 渲染；旧的实体物品层不会再次绘制。其他没有 map 的模型使用旧的 SlashBlade、TACZ 和 Superb Warfare 兼容层作为回退，避免同一物品叠加两次。
