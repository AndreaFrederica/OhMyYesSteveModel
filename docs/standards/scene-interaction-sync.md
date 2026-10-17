<!-- SPDX-License-Identifier: CC0-1.0 -->

# 通用模型交互同步

本协议建立在[当前玩家状态与 Roaming](protocol-v1/README.md)之上，仍处于 unstable 阶段。配置内语义定义见[模型交互格式](scene-model-features.md)。它不增加跨模型访问权或身体动作白名单。

静态表情变量名为 `ysm_face_` 加语义名称 UTF-8 的 SHA-256 前 10 字节的小写十六进制，共 29 字符。数值 0 表示释放，`1+weight` 表示持有 `[0,1]` 权重；值 1 主动抑制，值 2 表示完全应用。未提供变量等同释放。输入须有限且在合法范围内；只解析当前模型所声明的语义名称。

独立表情动画保存三个变量：`ysm_face_animation` 为当前配置内 expression-only 动画名称按 Unicode 排序的一基槽号，正号表示循环，负号表示单次，0 为释放；`ysm_face_time_hi` 与 `ysm_face_time_lo` 分别为启动世界 gameTime 的高位和低 16 位整数。启动 tick 为 `hi*65536+lo`，elapsed 为 `max(0,(gameTime+partial-start)/20)` 秒，循环按 duration 取模，单次结束释放。两个 float 必须精确表达对应非负整数；接收者不根据槽号索引身体片段。FULL 包含这三个值，中途加入可恢复当前相位。Roaming 继续遵守每模型 64 变量及名称 32 字符限制；宿主写入前检查余量，不能让超限表现为静默丢失同步。

身体动作以 `scene/<64位SHA-256>/(loop|once)` 为授权 identity。普通来源 digest 输入为包内来源路径、换行及 clip 索引；`@ysm/` 虚拟来源使用完整虚拟 ID 替代路径。可附 `~<16位小写十六进制nonce>` 以区分重复点击，服务器授权始终匹配 base identity。

带时间的 nonce 从高到低分为 `0x59` 标记、40 位启动世界 tick、16 位点击序号。标记命中后，接收者使用该启动 tick 恢复来源播放进度；其他 nonce 仅表示重启。时间戳只决定视觉动画相位，不授权来源 ID，也不代表服务器模拟或伤害时间。缺失的旧式时间信息从接收时开始播放。世界 tick 在同一 level 内解释，负进度夹为零；新世界/模型仍遵守原状态 owner 清理。

YSM 源动作在宿主 CAP 控制器初始化时使用同一 elapsed，MMD 来源切换对来源时间设置 offset 并保留模拟世界。跨客户端帧插值、传输延迟及自定义源模型资源可用性仍影响最终观感，不能把此时间编码当作物理状态同步。
