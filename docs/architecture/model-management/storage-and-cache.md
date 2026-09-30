# Storage 与 cache

模型管理的 backing 包括 direct container 的只读来源、raw source 转换对象、intrinsic default 的内存驻留、remote metadata/chunk cache、独立 preview cache 和 baked render 派生物。用户输入位置不属于缓存；direct 延迟读取可因原件后续变化局部失败，已经取得并验证的资源不依赖重新打开原件。

用户来源与缓存的处置边界见 [DD.user-model-sources-are-readonly](../../product-decisions/decisions/artifact-storage.md#dduser-model-sources-are-readonly)。下文 converted index/object、remote、preview 与 baked cache 均为派生缓存；用户 direct container 和 raw source 不属于这些缓存对象。

Raw source 只通过 conversion seam 进入模型管理。Discovery 先冻结本次实际读取的 source closure，同一份 immutable capture 同时驱动身份计算与容器写出。当前容器写出并验证成功后，Catalog 从该容器取得后续运行语义，不为同一导出物重新解释 raw 文件组织或转换中间态。

Capture、历史投影与 staging 的内部数据流见[转换与导出](../asset-pipeline/conversion-and-export.md)，读取层次见[容器与分层验证](../asset-pipeline/container-and-validation.md)。

兼容 adapter 也只能在这一输入 seam 将可确定解释的历史内容投影为当前 conversion input。投影成功后使用相同的 current container 验证、Catalog、storage 和生命周期；legacy envelope、加密、cache、session 或历史模型管理对象不随结果进入新主线。

## 客户端音频保留

声音 source 只保存精确 representation、stream descriptor 与 locator，不保存 encoded bytes、fetcher、session callback 或播放位置。真实播放需求先按当前访问资格解析实际 content，再通过现有 chunk 读取、remote cache 或 typed transfer 完整取得并验证目标 stream。浏览 catalog 和创建 render target 不预读音频。新的 acquire 即使命中内存保留也重新检查当前访问资格。

`ClientAudioRuntime` 用一个 64 MiB 内存账本统一保留 encoded 或完整 mono PCM。每项按实际强持有 bytes 计一次，访问顺序 LRU，距最近成功 acquire 满 30 秒失去命中资格。短于 4 秒的内容只有在真实播放正常到达 decoder EOF、frame 数完整且候选仍有发布资格时，才以 PCM 原子替代 encoded cache 引用；等于或长于 4 秒只保留 encoded。超预算单项仍可由当前播放消费但不进入保留，活动 consumer 也不阻止 cache 撤销自己的引用。

该账本不是磁盘 cache、server runtime 或物理内存上限。活动 encoded、首播 PCM 候选、临时缓冲、host 预取和等待 GC 的 backing 都不在 64 MiB 账面内。缓存撤引用与[播放/host/物理回收](ownership-and-lifecycle.md#模型音频生命周期)分别闭合。

## Direct container 的精确读取实例

`ModelSourceResolver` 在用户来源上直接打开和完整验证 direct container，不再将原件复制到 converted object，也不创建路径锁、stable backing 或清理状态。Catalog 记录实际来源 kind；direct publication 还要求 Manifest 声明并完整解码有效内嵌 preview，独立 preview cache 不能补足准入。

Runtime 接纳 file-backed content 时建立一个精确读取实例。每次 worker 开始盘读前检查该实例是否已被 owner 标为 Corrupted，再用同一打开句柄核对文件长度、range、stored/decode size、encoding 与 hash。访问失败或内容验证失败只向 owner 投递原实例的 provenance。owner tick 提交后，该实例单向失效；已经排队但尚未开始的读取不能借旧 admission 越过标记。标记前已开始且最终完整验证成功的读取仍有效，新来源实例不继承旧标记。

Catalog 移除只撤销索引持有，不清除旧实例；已经完成的 target、bytes 或其他资源拥有自己的不可变依赖。用户替换、删除或损坏原件因此只影响未来需要读取的局部操作，不回写来源、不销毁旧资源，也不引入全局路径 failure table。

## Cache roots

派生 cache 按归属分布在两个 root：

```text
<game-dir>/ysm/cache/converted/...                实例本地 root：converted 内容
<game-dir>/ysm/cache/previews/...                 实例本地 root：独立 preview cache
<game-dir>/ysm/cache/bake/...                     实例本地 root：baked render 派生物
<platform-app-data>/ysm/cache/remote/...          平台级 root：remote 已验证内容
```

`<platform-app-data>` 是平台级应用数据位置：Windows 取 `%LOCALAPPDATA%`，macOS 取用户 Library 的 Application Support，其余取用户 home 下的 `.local/share`。remote cache 的平台级 root 为该位置下的 `ysm/cache`。该位置不存在、不能创建或不可写时，remote 退回 `<game-dir>/ysm/cache/remote`。converted、preview 与 baked 只属于当前游戏实例，没有平台级替代位置。每个 `AtomicSharedCache` 实例只守卫一个内容 root：目标必须留在该 root 内，同 key lock 位于 `<root>/locks`，因此不同 root 的 cache 不会共享锁目录。

## Converted storage

```text
<game-dir>/ysm/cache/converted/index.bin
<game-dir>/ysm/cache/converted/<model-id>/<container-id>.mxc
```

Converted 内容属于当前游戏实例，因此不进入平台级 root。消费者登记与对象 lock 同样以 `<game-dir>/ysm/cache` 定位。

Raw conversion 的 index entry 为 `ModelId, ContainerId, rawRelativePath, fullModVersion`。复用要求 source probe、完整模组版本、路径、对象位置和当前 Model Schema 验证全部精确匹配。旧 schema 对象从 raw source 重建；不迁移旧 payload，也不保留兼容 reader。精确 converted object 的坏读只返回失败，不删除当前目标；后续验证通过的同一精确对象可原子覆盖，替换失败保留旧文件。可变 index 仍按自身一致性规则更新，不能套用精确对象的并发赢家规则。

上述 converted-source mapping 与对象路径保持既有 byte/layout，不进行迁移。它们证明内容来自 local discovery，因此 remote publication 可以在完整打开并确认同一 `ModelId` 后复用不同 `ContainerId` 的对象；该资格不扩展到 remote storage。

每个参与 shared converted 的进程持有实际 OS 锁登记。新消费者只在 client join 或 server start 等首次使用点等待正在进行的 prune；startup 不等待。首次 scan 结束后，Catalog 在短期清理许可内按本次保留集合执行一次保守 prune；获取许可失败或仍有其他消费者时跳过。清理只触及已归属的 converted 派生物，不触及用户来源、remote、preview 或 baked cache。进程退出释放锁；残留登记文件不冒充活跃进程，不使用 PID、心跳或过期时间恢复。

`AtomicSharedCache` 的同 key JVM/文件锁仍服务 baked 等独立派生 writer；direct container 不再是其消费者。不能由共享 helper 推定各 cache 的 identity、失效与清理语义已经统一。

### V3 源哈希与转换复用

V3 输入在导入前由 ysmlib `V3dCache.capture` 一次性取得有界、只读快照和源 SHA-256；哈希与实际解码使用同一份 bytes，不以长度、mtime 或历史 ModelId 代替源内容判定。`LegacyConversionCache` 在 `<game-dir>/ysm/cache/legacy/converted/<key>.receipt` 记录当前转换结果，key 包含源 SHA-256、完整本体版本、host staging profile、envelope profile 与 projection profile。普通 converted index/object 的布局与首次 prune 规则不变；每次命中仍生成当前来源的 index candidate。

Receipt 只在 `.mxc` 提交且全部 payload 验证成功后原子发布，记录精确 `ModelFileIdentity` 和完整 stored-file SHA-256。命中时用同一打开句柄重读当前 schema/Manifest、核对精确身份、全文件哈希和读取前后的文件状态；字节与已验证制品相同才跳过扫描阶段的逐块 Zstd 解压。普通运行时按需 chunk 读取继续执行各自验证。损坏或缺失的 receipt/object 是 miss，不因读取失败删除原件；访问失败仍是错误。不同路径的相同源可以共享转换结果，实际来源和使用资格仍由 Catalog 决定。

成品 miss 时，`YsmRuntime.v3dWire()` 在 `<game-dir>/ysm/cache/legacy/v3d/<source-sha256>-p<decoder-profile>.v3d` 复用验证过的 uncompressed wire，经 `LegacyImportProvider.importWire` 投影并重新生成 `.mxc`。只有 wire 缺失、损坏或 envelope profile 变化才重新解密解压；projection 或本体版本变化只使成品失效。同一源的 JVM/进程文件锁覆盖查找、V3D 物化、转换与 receipt 发布，避免并行 owner 重复导入。此自动缓存不生成工具用的 decoded JSON 工作区，不改变 `.mxc` 的运行语义权威。

`legacy` 目录是独立可重建的派生缓存，暂不自动清理；首次 converted prune 不触及它。已被 prune 的 `.mxc` 会使对应 receipt miss，然后从 V3D 重建。删除该缓存会让下一次加载重新建立缓存。V1/V2 与 raw directory 的 capture/compile 管线不使用 V3 decoder。

## Remote storage

Remote storage 不维护 index，当前 remote publication 是唯一发现入口：

```text
<remote-root>/<model-id>/metadata/<container-id>/metadata.bin
<remote-root>/<model-id>/<chunk-hash>.bin
<remote-root>/<model-id>/<chunk-hash>-zst.bin
```

`remote-root` 由 owner 配置并在 store 构造时解析为固定 physical root。模型和 chunk 路径只由已验证的固定长度 hash 编码派生。远端 descriptor 中的 encoding 只能选择实现内固定的 direct 或 zstd 文件后缀，不能提供路径、URL 或 provider。每次 read、corrupt invalidation 和 atomic publication 都重新解析实际 parent/file，要求 physical result 仍在 root 下。read 使用同一 no-follow file handle 取得长度和 bytes；publication 逐级创建并验证目录、在提交前复核目录 identity，再在该目录内原子替换。指向 root 外的 symlink/reparse point 因此在任何 filesystem sink 前成为 access failure，而不是 host path capability。

Metadata probe 只读取 exact identity 目录中的 `metadata.bin`，按[分层验证](../asset-pipeline/container-and-validation.md)校验连续 prefix。缺失为 miss，已证明无效才视作 corrupt，不读取旧布局。

Remote probe 始终以完整 `ModelFileIdentity` 为 key，同模型其他 remote cache 条目不能替代 publication。prefix 的内存所有权见[representation 对象](catalog-and-sources.md#身份与-representation-对象)。

普通 chunk 使用 content-addressed 路径，读取时按[stored/decoded 验证规则](../asset-pipeline/container-and-validation.md)重新验证。合法的不同 zstd representation 可以复用。本地 same-`ModelId` 内容也须按目标 chunk table 验证实际 hash。

Metadata 与 chunk 均先验证，再写 sibling temp，并以同目录 atomic replacement 作为唯一 commit point；提交前复核 physical directory identity。不支持 atomic move 或替换失败时不提交新版本，旧有效文件继续保留。

Remote storage 只保留已验证 bytes，不携带 session authority。`RemoteModelStore` 在 runtime worker 内同步 exact-path read/write。坏读只返回 miss 或分类失败，不删除当前目标；当前 exact session 有资格时至多补取一次，验证通过后原子替换。写失败不撤回已经有效的内存结果。Store 不持有 fetcher、grants 或 session query table，因此无需 side-wide retirement、process-global path map 或跨 session 锁。

## 独立 preview storage

自动生成或远端取得的图片位于 game-local cache root 的 `previews/<ContainerId>.image`。Key 只含 `ContainerId`，不含 server、图片 hash、generation、target profile 或编码版本；这是有意的弱关联。完整媒体解码限制为 encoded bytes 不超过 16 MiB、宽高各不超过 4096、像素数不超过 16 Mi，支持的自描述静态图片可替换同 key 内容。

读取优先使用声明且有效的内嵌 thumbnail，再探测独立 cache。坏 cache 只造成当前图片 miss，不删除文件、不标记模型损坏。后续有效图经同目录临时文件、完整验证与原子替换提交；失败保留旧文件且不撤回有效内存图。该 cache 当前没有自动扫描或定时清理，长期空间成本独立于 target 30/60 和 converted 首次 prune。

## Render 派生物与 cache-only 探测

私有 baked cache 明确排除 `ModelId`。Geometry key 由 `ContainerId`、resource name、基础纹理 hash、序列化几何大小、bake 选项与 renderer/runtime profile 派生；animation key 由 `ContainerId`、definition hash、render target、animation set 与 animation ABI 派生。完整 `CACHE_ABI` 继续参与目录、profile 与 hash domain，不共享 stable patch namespace、不扫描旧 ABI 目录，也不迁移已有 cache。当前或候选 cache/schema qualifier 大小写不敏感地包含 `unstable`、`dev` 或 `snapshot` 时，复用前必须要求完整原始版本字符串严格相等；baked payload 重开时由相同 schema 门禁执行。路径和进程共享锁同样以 `ContainerId` 分区，因此同一 `ModelId` 的不同容器不会误复用派生物。Catalog activation 另按[representation 查找顺序](catalog-and-sources.md)执行；Ready content 的普通 Resource 查找仍按 memory/GPU、baked cache、verified content、remote chunk cache 和 server transfer 逐层取得。

Expression 不经过模型管理 cache：模型携带 Molang source，由客户端在加载与运行时本地解析和求值，因此本页的派生物 cache 不包含 expression 产物。

Remote catalog 的模型卡先查 Ready 或执行 exact cache-only probe：metadata prefix、目标 definition 和引用的 model chunks 必须全部按当前 publication identity 验证，才可建立 3D target。任一 model chunk 缺失、损坏或不可访问时先展示内嵌/独立/远端 preview；只有连续 hover 超过产品门槛才尝试离线 target，且不为 model body 发起网络请求。Cache-only Flight 不加入普通在线 Flight 的 interest，也不能将离线 miss 固化到后续正常请求。
