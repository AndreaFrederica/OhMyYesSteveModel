# 资源加载与释放矩阵

本页按 game server 与 client 的 owner 边界，汇总模型在主要运行事件中的 catalog materialization、资源取得和引用撤销行为。它不将 catalog entry、metadata representation、完整 render target 与磁盘 cache 合并为同一种「已加载」状态；各阶段的完成条件分别由 [Catalog 与来源](catalog-and-sources.md)、[Storage 与 cache](storage-and-cache.md)和[所有权与生命周期](ownership-and-lifecycle.md)定义。

矩阵使用以下资源分类：

- `default`：intrinsic default。它是进程级可靠性基线，不参加普通 Catalog reload 或 session 驱逐。
- `builtin`：除 intrinsic default 外，由模组随附的普通 builtin 模型。
- `catalog`：来自 local `custom` / `auth` source 的模型。Catalog 本身是 authority，不是另一种模型 payload。
- `remote_cache`：客户端持久化的、按 exact identity 验证的 remote metadata/chunk 及其可复用派生物；它不携带 session authority。
- `remote`：当前 exact server session 发布并允许客户端取得的 representation 与 target 资源。

`player_request` 表示玩家或玩家实体对某个 render target 产生持续资源需求，不专指一个 Forge 事件。`player_select` 表示选择意图及其 server 裁决；选择与资源取得是独立状态，不能由选择成功推断 target 已经 Ready。

## Game server

Game server 只拥有 `default`、`builtin` 和 local `catalog` 内容。`remote_cache` 与 `remote` 是客户端为消费 server publication 建立的资源类型，不进入 server 矩阵。

| 事件 | `default` | `builtin` | `catalog` |
|---|---|---|---|
| `startup` | 进程基础设施完整物化 server 职责所需的只读内容；不承担客户端 bake、texture 或 GPU 成本。 | 只载入 builtin contract 与描述；普通 builtin 尚未扫描和物化。 | 不扫描、不加载。 |
| `server_start` | 已常驻，无新增加载。 | 首个 authorized consumer 启动 Catalog scan；逐项发现、转换、验证并发布，但不构造客户端 render target。 | 扫描 `custom` / `auth` source，逐项转换、验证并发布。 |
| `player_join` | 可作为 intrinsic selection 与 fallback；不作为普通 publication entry 分发。 | 为 exact player connection 建立 session publication、private lookup 与 grants；不因 join 读取或发送模型 body。 | 同 `builtin`。 |
| `catalog_reload` | 固定项不被替换、删除或重新物化。 | 重扫并逐项提交 current Catalog；向 active session 发布 delta。Catalog retirement 只撤 owner 引用，不追溯撤销已接纳读取或 transfer。 | 同 `builtin`；坏新项不替换旧有效项，无法完整观察 root 时不根据缺失观察删除旧项。 |
| `player_request` | 不作为普通 remote asset request 目标；客户端使用自身 resident default。 | 校验 current session authority 与 access，打开精确 source，只读取并发送目标实际依赖的 metadata/chunk 闭包。 | 同 `builtin`。 |
| `player_select` | 接受 intrinsic-default selection，不加载或发送资源。 | 校验 current session 中的模型、授权与 texture 后提交 selection；selection 本身不读取或发送模型 body。 | 同 `builtin`。 |
| `player_quit` | 进程级内容保留。 | 撤销该 exact connection 的 session snapshot 与 lookup，取消其 accepted transfer；全局 Catalog 和其他玩家不受影响。 | 同 `builtin`。 |
| `server_stop` | `ServerStoppingEvent` 只关闭 game server service，不关闭进程级模型基础设施，因此该内容仍由进程 owner 持有。 | 关闭全部 server session、transfer、dispatch 与 Catalog subscription；进程 Catalog 不在该事件中关闭。 | 同 `builtin`。 |

Server 侧成本按阶段分离：`startup` 支付 default 只读内容物化，`server_start` 支付 local source 扫描、转换与验证，`player_join` 只建立 session authority，`player_request` 才读取并发送单一目标所需的资源闭包。`player_select` 不隐式发起资源传输。

## Client

Client 同时拥有进程级 local Catalog、exact connection 的 remote projection、共享 render-target runtime 以及无 authority 的磁盘 cache。连接到 remote server 时 local Catalog 仍继续扫描和持有完整内容，但公开 client view 不与 remote projection 合并。

| 事件 | `default` | `builtin` | `catalog` | `remote_cache` | `remote` |
|---|---|---|---|---|---|
| `startup` | 完整物化并完成全部 required target 的 bake、texture publication 与 residency 验证；required lease 持续到 client service 关闭。 | 只载入 builtin contract 与描述；普通 builtin 尚未扫描、物化或 bake。 | 不扫描、不加载。 | 不扫描；`RemoteModelStore` 尚未按连接需求惰性打开。 | 无连接、无 remote authority。 |
| `player_join` | 始终常驻；进入 remote 协商时公开 view 先切到 intrinsic-default-only。 | 启动或继续 local scan；Active remote session 中仍持有，但不与 remote view 合并。已验证的同源内容可以满足 publication activation。 | 同 `builtin`。 | 惰性打开并按 exact identity 探测 metadata prefix；cache hit 可以满足 activation，但不提供跨 session 授权。 | 接收 publication 并逐 entry 激活；local/cache 未命中时可以取得缺失 metadata prefix，不因 join 下载全部模型 body。 |
| `catalog_reload` | 固定项不替换、不卸载。 | Local reload 重扫并替换 current local snapshot；remote session 中只更新持续持有的 local snapshot，不直接改写公开 remote view。 | 同 `builtin`。 | 不清空、不整体重建；新 activation 仍按 exact identity 探测。 | 收到 server delta 时只重建受影响 entry 的 activation；旧 Pending owner 被取消，未变 Ready 可以保留，不预取 body。 |
| `player_request` | 直接取得既有 Ready lease，不产生内容磁盘或网络 I/O。 | 从 local content 或 baked cache 取得目标 geometry、texture 与 animation，完成 bake/host publication 后进入 Ready cache。 | 同 `builtin`。 | 先 exact 探测 metadata、目标 chunks 与 baked 派生物；闭包完整时可以离线 Ready。 | Cache miss 且连续 demand 达到门槛后，只取得目标实际依赖的资源闭包；验证并提交 cache 后完成 bake 与 host publication。 |
| `gui_display` | 直接复用 resident target。 | 模型卡先查 Ready 或 cache-only；持续 hover 超过展示门槛后，允许从 local content 加载完整 target。 | 同 `builtin`；独立 preview 缺失时可以进入受限的本地 preview 生成。 | 探测独立 preview 与 exact cache-only target，不建立 model-body 网络请求。 | 页面持续需求可以取得独立 preview；3D hover 只允许 Ready/cache-only/offline target，不为模型卡下载 model body。 |
| `player_select` | 切换到 resident default，并释放上一模型的 consumer lease。 | Selection 与 resource demand 独立推进；复用 Ready 或按需取得 target。 | 同 `builtin`。 | 首先尝试 offline exact cache。 | Cache 缺件时，连续 demand 达到门槛后可以发起在线 target closure 请求；selection 发送不等待资源完成。 |
| `player_quit` | 保留到 client service 或进程关闭。 | Local Catalog、已验证 content 与合法 Ready cache 保留；公开 view 立即恢复最新 local snapshot。 | 同 `builtin`。 | 磁盘内容保留，store 不因单次 disconnect 清空。 | 撤销 exact-session authority，取消 Pending transfer/assembly，并释放页面、实体与选择 demand 的 lease；不携带 session capability 的已完成 Ready 可以进入 unused LRU。 |

Client 的「释放」分为三个不同终点：

1. 页面、实体或选择需求结束时，只释放自己的 `ResourceLease` 或 Pending interest。
2. 最后一份 consumer 退出后，普通 Ready target 进入 unused LRU；PC 最多保留 60 个，移动端最多保留 30 个，intrinsic default 不参加该驱逐。
3. LRU 或其他 owner 撤销最后一份强引用后，GPU 资源才由固定 owner 或 Cleaner 完成物理回收；该回收没有时间上界。

`player_quit`、Catalog replacement 和 server-side stop 都不清空 remote、preview 或 baked 磁盘 cache。Remote authority 退出时立即失效，但这不能反向证明相同内容的已验证 cache bytes 或合法 Ready target 已经物理释放。

## 相关边界

- Default 的启动闭包与固定驻留见[默认模型](default-model.md)和[模型降级决策](../../product-decisions/decisions/model-fallback.md)。
- 首次 scan、reload、local/remote 投影切换与 server publication 见[Reload 与发布](reload-and-publication.md)。
- Join 只先取得描述、模型 body 按需分发的产品边界见[模型分发决策](../../product-decisions/decisions/model-distribution.md)；activation 见[Catalog 同步](../network/catalog-sync.md)。
- GUI preview、cache-only hover 与 selection 分离见[客户端展示](../client-presentation/README.md)。
- Lease、unused LRU、session close 与物理回收见[所有权与生命周期](ownership-and-lifecycle.md)。
