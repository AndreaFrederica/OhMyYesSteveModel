<!-- SPDX-License-Identifier: CC0-1.0 -->

# 通用模型配置

此格式处于 unstable 阶段。产品语义见[游戏内编辑](../product-decisions/decisions/dual-model-backends.md#bcmesh-in-game-authoring)。

源文件旁的 `<完整模型文件名>.omysm.json` 使用 UTF-8 JSON，最大 1 MiB。导入后的场景包将它保存为 `_omysm/profile.json`。模型源哈希与配置字节哈希共同决定有配置模型的内容身份；profileId 是配置的 UUID，不作为网络授权依据。没有配置文件时使用格式默认单位和源模型范围。

| 根字段 | 含义 |
| --- | --- |
| schemaVersion | 整数 1；其他版本拒绝 |
| profileId | UUID 字符串 |
| placement | 放置与尺寸 |
| bones | 人体角色或 `bone:自定义名称` 到目标骨引用的映射 |
| actions | YSM 动作名到来源动作的映射 |
| presentation | 描边呈现参数 |
| metadata | 与 YSM 对齐的作品元数据 |
| retarget | 绑定键到 YSM 源骨名的映射、动画源模型及位移比例 |
| transitions | 状态间一次性过渡片段列表 |
| heldItems | 左右持物挂点与第一人称呈现选择 |
| hostPhysics | 世界中的视觉物理：惯性、流体力与方块碰撞 |

placement 包含 `metersPerUnit`（米/源单位）、`sizeMode`（SCALE 或 HEIGHT）、`scale`、`height`（米）、`referenceHeight`（朝上轴的源单位参考高度）、`footY`（朝上轴的源单位脚底参考）、`x/y/z`（米）及 `yaw`（角度）。SCALE 的最终单位比例为 metersPerUnit × scale；HEIGHT 为 height / referenceHeight，不能再次乘 scale。统一放置先在朝上坐标中减去脚底参考，再缩放、转向和加米制偏移。参数必须有限，长度比例严格为正；编辑不改变 Minecraft 碰撞箱。

自定义绑定键使用 `bone:` 前缀，后缀非空，完整键最长 128 字符；可绑定普通骨或 IK 控制骨，不根据键名猜测用途。预设人体角色覆盖 root、hips、spine、chest、neck、head，左右 upper/lower arm、hand、upper/lower leg、foot、toes，独立 leg/toe IK 以及可选手指角色。骨引用包含 `index`、原始 `name`、`parent` 祖先路径、动画旋转轴校正角度 `pitch/yaw/roll`、参考姿态角度 `restPitch/restYaw/restRoll` 和 `[0,1]` 的 `weight`。祖先路径从根到父，由 `索引:原名` 片段用 `/` 连接。索引 -1 为显式不绑定。角色不能重复占用同一索引，名称/祖先变化或 IK/FK 类型不匹配均拒绝。名称归一化仅用于建议，不改变源名称。映射的可编辑性不等于所有源格式已经实现完整动画重定向。

参考姿态角度以目标骨架的全局坐标表示，按 ZYX 顺序（度）旋转目标原始静止骨架的全局朝向。YSM 转移的全局旋转差值左乘该参考朝向，再扣除目标已求值父旋转，输出相对原静止姿态的局部差值；不会重复应用父骨校准或改变骨长。轴校正则只转换动画差值，两者不能互相替代。参考姿态只属于显式 YSM 重定向，不修改来源模型或直接叠加到 VMD。缺省三个参考角为零。自动校准依据源/目标臂段方向生成初值，不能唯一决定手腕扭转，应允许作者调整。

heldItems 包含 `enabled`、`firstPerson`、`left`、`right`。firstPerson 为 `VANILLA`（保留原版第一人称持物）、`MODEL`（使用模型挂点）、`HIDDEN`（隐藏第一人称持物）。每个手部挂点包含 `enabled`、`binding`（bones 中的人体角色或自定义绑定键）、`x/y/z`（转换后骨骼轴中的米）、`pitch/yaw/roll`（ZYX 角度）、正数 `scale`。每次从最终求值骨架取位置/朝向，单位转换不得把物品额外缩小为源模型单位，也不得因源左手坐标镜像物品。挂点偏移与物品随后随整体 placement 和实体尺寸缩放。左右手为解剖学左右，主副手由宿主惯用手分配。缺失/显式未绑定骨不绘制该挂点；第一人称 MODEL 对缺失挂点保留原版该手，避免模型未准备时物品消失。

缺省 heldItems 启用，firstPerson=VANILLA；left/right 分别绑定 leftHand/rightHand，启用，位置 `(0,-0.0625,-0.1)`，角度 `(-90,0,0)`，scale=1。原版物品几何和显示变换由 Minecraft 实现；该配置不承诺第三方武器专用定位器或自定义第一人称动画兼容。

动作值包含 `path`、`clip`、`loop`。path 是包内相对路径、`@ysm/generated/<state>` 或 `REST`；路径通过当前包解析到来源 ID，不依赖导入时的枚举序号。不存在的来源或 clip 拒绝。删除动作映射表示恢复自动选择。动作名不得为空、超过 128 字符或使用保留前缀 `scene/`、`#`。

presentation 包含 `outlines` 布尔值及 `[0,10]` 的 `outlineScale`。当前这些字段控制 MMD 描边；没有对应语义的材质不伪装为支持。

metadata 包含 `name`、`tips`、`license`、`authors`、`links`。license 为 `{type, desc}`；authors 每项为 `{name, role, contacts, comment, avatar}`；contacts 和 links 每项为 `{key, value}`。avatar 是包内相对图片路径，不接受宿主绝对路径。文本最大 65536 字符，整体仍受 1 MiB 限制。默认名称/提示/作者/链接为空，默认许可证类型为 `All Rights Reserved`。导入主 Mod 后使用普通 YSM metadata 展示机制，包括头像 blob。

retarget 包含 `sourceBones`（绑定键到源 YSM 骨骼原名的对象）、正有限数 `translationScale` 和 `sourceModel`。sourceModel 为 `@ysm/default` 或 64 位小写十六进制模型内容哈希，选择该 MC 模型的 player 动画资源；宿主必须获得源资源，缺失或源模型属于通用网格时应明确失败，不能以默认模型冒充。例如 `{"sourceBones":{"head":"Head","leftUpperArm":"LeftArm"},"translationScale":0.625}`。同一源骨可映射到多个不同目标骨，每项有独立权重；同一目标骨仍不可重复占用。目标引用继续位于 bones，不复制到 retarget。骨架绑定不创建动作别名。YSM 适配器以源像素为位置单位，translationScale 表示目标源模型单位 / YSM 像素；与最终 placement 米制缩放分开。输入源动画先求值，再做坐标转换及静止姿态差值换算。静止骨长保留目标值，动画位移按比例转换；父级动画不得重复应用。权重零释放对应骨的驱动权。

transitions 是 `{from, to, animation}` 对象数组。from/to 为非空状态名，最长 128 字符；相同边不可重复，自环拒绝。animation 使用 actions 的字段，但必须是有正时长的来源片段且 loop=false，不接受 REST 或 `@ysm/` 生成动作。首次加载不虚构来源状态；进入匹配边时播放一次，按片段 end-start 时长结束后进入目标状态。新请求立即中断当前片段，以上次请求的目标作为下一条边的来源；不存在边则立即进入新状态。当前格式不包含跨后端姿态混合时长，也不以过渡片段替代混合。

缺省 transitions 读取为空数组，retarget.sourceModel 缺省为 `@ysm/default`。此前生成的 sidecar 可缺省 metadata 与 retarget，读取补上述元数据默认值及 `{"sourceBones":{},"translationScale":1}`，下次保存输出完整字段。这是附加创作文件的可编辑性，不构成旧客户端协议兼容。已出现但类型不合法的字段及未知字段仍拒绝。

hostPhysics 为 `{worldCollision, inertia, fluidDrag, buoyancy, teleportDistance}`。缺省值分别为 `true, 1, 4, 0.85, 4`。inertia 是移动/旋转惯性倍率，fluidDrag 是每秒流体阻力，buoyancy 是视觉浮力倍率，teleportDistance 是以米计的运动历史重置距离。所有数字必须有限且在 `[0,100]` 内，teleportDistance 严格大于零。方块碰撞仅影响来源刚体、软体或 SpringBone，不改变实体 hitbox、移动或伤害。设置保存在附加文件，并可在 Alt+Y 编辑器的“世界物理”页修改。未提供该字段的附加文件读取时补默认值；出现但缺字段、类型非法或含未知字段仍拒绝。

预览物理启停、时间轴位置、相机缩放和调试骨骼选择不进入模型配置。世界环境参数不使编辑器自动启用物理，也不把游戏环境带入独立预览。读取失败应保留文件及错误原因；编辑保存须检查外部修改冲突，使用同目录临时文件与原子替换。源模型原始字节不修改。
