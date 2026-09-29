# 证据、覆盖与根依据

分类、Fact/Inference/Hypothesis、节点与计数口径统一见[建模、证据与计数口径](../governance/modeling-and-evidence.md)。本页限定 MN 子系统的模型、资源与网络覆盖。

## 覆盖

| 区域 | 已建图内容 | 未闭合 / 未检查 |
|---|---|---|
| 模型/资源 | 增量目录、激活、精确读取、Ready lease/LRU、Pending interest、纹理发布/admission、default、fallback、失败记忆、converted consumer/prune、声音取得与 encoded/PCM 保留；MN-01–03、05–08、10、17–20、22–25 | 大目录驻留、GC/host/audio backing 回收、真实 remote 音频、全部 shutdown 时序、真实多进程 prune 与最坏 host admission 成本未验证；共享 writer 收益见 Q-10 |
| 网络/状态 | Session、typed request/assembly、dispatch、forced selection、player projection；MN-11–16 | 真实 Forge 双端、全部跨消息时序、持续拥塞与重连未验证 |

本主题记录 22 个生产机制。测试和静态关系只提供可定位的行为断言，不证明真实 Forge/OpenAL handoff、持续拥塞、GPU/host/音频 backing 回收或跨进程文件系统行为已通过。

## 证据入口

[机制正文](mechanisms.md)逐项维护当前行为、直接删除失败与源码/测试入口。[因果图](causal-graph.md)维护 MN 内部边，跨域边进入[跨子系统关系](../governance/cross-subsystem-relations.md)。

## 根依据

本主题使用的根键定义与权威链接只在[根依据总表](../governance/root-register.md)维护。

| 类别 | 本地使用的键 |
|---|---|
| Catalog / 内容 | `D-RELOAD`、`D-CATALOG`、`D-HOLD`、`D-SOURCE`、`D-EXACT`、`D-IDENTITY`、`D-JOIN`、`D-CHUNKS`、`D-REUSE`、`D-META`、`D-VERIFY`、`D-SOUND` |
| 生命周期 / 失败 | `D-DEFAULT`、`D-FALLBACK`、`D-HOST-FALLBACK`、`D-RECOVER`、`D-FAIL`、`D-LOOP`、`D-HOST` |
| 网络 / 授权 / 状态 | `D-SESSION`、`D-SESSION-FAIL`、`D-WIRE`、`D-VALIDATE`、`D-CAPABILITY`、`D-ACCESS`、`D-ADMISSION`、`D-SERVER`、`D-PLAYER`、`D-TRACKING`、`D-SCRIPT` |
| 架构选择 | `A-NOTIFY`、`A-RESOURCE`、`A-AUDIO-RETENTION`、`A-WORK`、`A-FRAME-BUDGET`、`A-TYPED`、`A-DISPATCH`、`A-FORCED`、`A-REPORT`、`A-TEXTURE-PUBLISH`、`A-PUBLISH-BUDGET`、`A-FAILURE-MEMORY`、`A-REMOTE-FILES`、`A-SHARED-CACHE`、`A-CONVERTED-CONSUMERS`、`A-CONTINUOUS-DEMAND` |
| 未闭合根 | `H-FORCED` |

产品键不把当前 queue、cache 或状态表示固化为唯一实现。
