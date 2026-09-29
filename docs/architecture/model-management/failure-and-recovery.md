# 失败处理

Local reload 以完整 candidate 提交，remote activation 按 entry 隔离。来源匹配规则见[Catalog 与来源](catalog-and-sources.md)，会话失败边界见[session-failure](../../product-decisions/decisions/session-failure.md)。

用户来源保护与缓存处置边界见 [DD.user-model-sources-are-readonly](../../product-decisions/decisions/artifact-storage.md#dduser-model-sources-are-readonly)，完整目录发布见 [DD.single-current-catalog](../../product-decisions/decisions/catalog-publication.md#ddsingle-current-catalog)。

| 失败位置 | 结果 |
|---|---|
| 单个 local source parse / conversion / validation | 记录诊断；坏新项不替换当前有效项，其他已验证完成项仍可发布 |
| root discovery 或 inventory 完整性无法证明 | 保留已发布完成项，但不根据该根的缺失观察删除旧项 |
| 普通 reload 正在进行 | 返回 `BUSY` |
| local candidate 已证明内容无效 | 按稳定顺序尝试下一个 same-`ModelId` candidate，再尝试 exact remote cache/server |
| local/cache 访问失败 | 当前 entry 或读取实例为 transient Failed/Corrupted，不删除、移动或猜测修复 backing；已验证资源继续有效 |
| metadata prefix 无效、缺失或超过通用上限 | 整个 entry Failed，不产生部分 Ready content |
| metadata request 返回 `NOT_FOUND`、`BUSY` 或 `UNAVAILABLE` | 当前 entry 为 transient Failed；其中 `NOT_FOUND` 可以是 immediate cutover 后旧 tuple 被 supersede，不能冻结为 content failure |
| server full/delta 编码或 enqueue 失败 | 保留已经提交的 server authority；不重试、不回滚、不关闭 session，下一次 delta 从 server current snapshot 继续 |
| remote full，或 delta 的 wire/domain 自身无效 | 关闭 remote owners，session 进入 intrinsic-default-only |
| remote delta 结构有效但与 client 旧 baseline 不兼容 | 丢弃该 delta 并保留当前 client authority；不主动 resync |
| remote delta remove 指向不存在项 | 成功 no-op，只记录 debug 诊断 |
| publication remove 或 exact tuple replacement | 关闭旧 entry owner 并拒绝 stale completion |
| requested texture 不在当前已验证 representation，但 target 仍有可用 texture | 保持 request 不变，在 client render manager 内依次选择有效 requested、有效 manifest default、manifest 稳定顺序中的第一项作为 effective texture |
| render target 不存在，或 projectile/vehicle target 没有 texture | 当前 scoped replacement 不激活，既有 projectile/vehicle owner 撤销 YSM 视觉替换并恢复宿主绘制；不扩成 alias、默认模型替代、修复状态或全局宽松 lookup |
| player target 没有 texture | 在 M1 reader fail closed，不能进入 catalog 或借用其他 target 的 texture |
| expression 解析或求值失败 | 按 target、logical action 与 entity runtime 隔离；单个表达式失败不牵动 Catalog entry、其他 caller 或已发布内容 |
| disconnect、protocol close 或切回 LOCAL | 关闭 exact session 的查询、请求与投影；共享 catalog/runtime work 不整体退休，client 立即投影最新 local snapshot |
| server session close | 取消该 exact session 的 accepted typed transfers，由 global dispatch owner single-close packets，并释放 session snapshot/index |

`ActivationSnapshot` 的 Failed 分为 deterministic content failure 与 transient access failure。失败彼此隔离。Pending 不构造 `ClientCatalogEntry`；Failed 只公开 hierarchy path、access 与错误且不可选择。只有显式 retry/reload，或相关内容、权限、连接、宿主能力变化，才为昂贵失败建立新的尝试资格。切走切回、时间经过或逐帧观察不清除 marker。系统不会定时自动重试。

Metadata transfer 的取消与终态见[资产传输](../network/asset-transfer.md)，验证职责见[分层验证](../asset-pipeline/container-and-validation.md)，cache 的完整可见性和替换失败边界见[Storage](storage-and-cache.md)。

Failed/cancelled Flight 退出命中集合，后续 acquire 可建立新 Flight。exact interest、late terminal 与 Ready 保活统一见[所有权与生命周期](ownership-and-lifecycle.md)。

Cache-only model-card probe 的 miss、访问失败或取消同样是 transient：它只让当前卡片保持 2D，不能发布不完整 target，也不能污染随后带网络能力的 normal Flight。普通请求到来时可建立新的 exact interest 并从 remote cache 或 server 继续获取。

消费者在新 authoritative target Pending 或 Failed 时可保留旧 Ready lease。humanoid 没有旧 lease 时使用 intrinsic default；projectile/vehicle 没有可用 replacement 时保持或恢复宿主呈现。Textureless non-player target 仍属于已发布 container 的 logical content，但不进入主动 preload，也不创建 render lease、默认 YSM replacement、持久 unavailable 标记或 publication rollback。Requested texture 与由已验证 manifest 导出的 effective texture 始终分离：fallback 不修改 `ResourceRequest`，不回写 authoritative selection，也不触发仅为消除名称漂移的 exact fetch、ACK/reconciliation、持久 alias 或 cache repair。Disconnect 会撤销 remote-only catalog projection 与 selection residue，随后 UI、entity 与 resource lookup 共同切回进程 Catalog 的最新 local authority。

## 恢复探测的调度边界

按[低成本恢复契约](../../product-decisions/decisions/model-fallback.md#bcdegraded-replacement-can-recover)，每帧仅做廉价资格判断。昂贵重试只由明确 retry/reload 或相关失败条件变化触发，不让同一已知失败因切回或逐帧同步重复加载、烘焙或绘制。Entity、program、resource 和 preview 的失败资格保持各自局部范围，不合并为全局失败表。
