# 模型兼容与容器语义冻结

兼容可确定解释的旧模型是 YSM 的项目级目标。兼容路径把历史模型或 raw model 单向转换为当前 Asset Container，使其进入同一套模型管理、验证和运行时。它不恢复旧版 native 业务系统，也不让旧加密、缓存或生命周期重新成为新主线的长期组成。

## 兼容目标

对于曾由发布版本有效读取、历史 wire 能够唯一解释且当前模型表示能够无冲突承载的输入，转换必须保留其已定义的可观察模型语义。格式重排、压缩、媒体规范化和内部表示变化本身不构成语义损失。无法恢复的未存储信息、未定义行为和历史 consumer 从未具备的能力也不成为兼容承诺。

旧格式兼容只存在于输入边缘。转换成功后，模型成为普通 current model，由 Java 领域层拥有容器、目录、存储、发布和运行时接入。后续 schema 演进原则上继续读取既有 current payload，而不是要求每次演进都同步修改历史转换器。

具体边界如下：

- 可唯一解释但缺少纹理的 projectile/vehicle target 仍保留 identity、match、ModelData 与其他可表示内容，并以空 texture map 进入 current container。它不合成或借用图片，也不使整个模型迁移失败。相同 current target shape 不再区分输入来源。普通运行时选择到 unavailable replacement 时撤销 YSM 视觉替换并恢复宿主呈现。
- Player target 仍必须至少包含一个合法 texture。所有实际存在的 texture entry 继续通过原有 intrinsic checks。
- 损坏、截断、历史 wire 歧义或目标表示冲突仍须明确失败。
- 语义保持不授权猜测性修复或把未知内容普遍丢弃。任何 representation-changing normalization 都必须有明确、可验证的历史行为边界。
- 当前 unstable Proto profile 允许 consumer 跳过未知字段且不承诺 round-trip，但 producer 只能写已裁决版本的已知字段。这不是历史转换器丢弃可表示模型行为的通用授权。

## 容器语义冻结

Raw model 是作者仍可修改的源资源集合。一次 raw import 成功写出 Asset Container 时，容器中的 logical content 成为该导出物的语义快照和后续兼容 authority。raw 文件组织、转换中间态以及没有进入容器的源表示不再定义这个导出物的运行语义。

这里的「冻结」只描述一次导出物的语义边界，不表示当前 Asset Container、Model Schema 或实现版本已经稳定。相关格式与协议目前处于 unstable 状态，不应视为稳定契约。格式仍可按其版本规则演进，但 reader、迁移器和缓存不能借演进之名静默改变既有容器已经表达的行为。

不生效输入不能在旧制品中休眠等待未来激活。当前精确字段清单、静默省略和新版本规则由[动画与控制器标准](../standards/model-schema/assets-and-validation.md#animation-与-controller)定义。

这一边界也决定 unsupported audio 的处理：

- raw source 仍是作者可修正的输入。转换发现被声明为模型音频、但当前音频边界无法识别的内容时，必须把非致命 warning 暴露给玩家并忽略该音频。不能静默吞掉，也不能让整个模型导入失败。Warning 是源质量反馈，不是容器内的模型语义或持久 loss record。
- 已导出的 legacy v3 容器已经冻结了旧 runtime 的语义。若其中保存了旧 consumer 从未能解码、实际表现始终为「音频缺失」的 bytes，迁移器可以静默省略这些 inert bytes。这修复了旧 producer/consumer 契约缺陷，同时保持冻结的可观察语义。
- 无论音频 bytes 是否可用，能够表示的 animation sound keyframe 都属于模型行为并必须保留。Unknown-audio 规则不扩散为丢弃图片、模型字段、异常引用或其他可表示数据的通用策略。

## 边界

- 兼容目标保护已定义的可观察语义，不保护 undefined behavior。真实 memory-safety 缺陷在危险 operation 的 owner 处修复，不能用 import-time 模型拒绝代替根因修复。
- `ModelId` 同时表达来源身份与业务身份。两个源即使导出相同运行语义，也不因此自动拥有相同身份。精确表示的内部边界见[模型身份架构](../architecture/model-management/catalog-and-sources.md)。
- 当前模型音频能力、legacy adapter 和端到端验证的实际完成度由[当前支持状态](../status/support-and-verification.md)记录。本页描述项目目标，不能被解释为已经实现或验收。
