# 当前网络协议

当前应用与 Forge transport version 均为 `0.3.0-unstable`。全部消息使用统一的 `ysm.network` Proto package。入口为 [model session](proto/network/model_session.proto)、[common](proto/network/common.proto)、[player state](proto/network/player_state.proto)、[control](proto/network/control.proto) 与 [Minecraft state](proto/network/minecraft_state.proto)。Proto package 不替代 transport version。

当前或对端 transport qualifier 大小写不敏感地包含 `unstable`、`dev` 或 `snapshot` 时，兼容判断要求两个完整原始版本字符串严格相等。两侧均不含这些标记时才委托 protocol 的稳定版策略，当前该策略仍为 exact。

协议尚未冻结；client 与 server 必须来自同步构建。实现只保留当前 reader/writer，不协商、探测、双读或兼容 `0.2.0-unstable` wire。

## Frame

首字节固定为：

```text
messageTag = (messageId << 1) | frameKind

bit 7..1  messageId      # 0..127
bit 0     frameKind      # 0 = simple, 1 = full
```

Simple frame 用于无 attachment 的短 protobuf：

```text
uint8  messageTag
byte[] rawProtobuf
```

`rawProtobuf` 必须小于 30 KiB，不压缩且必须被 parser 精确消费。

Full frame 用于较大 protobuf 或 attachment：

```text
uint8  messageTag
uint8  flags                       # bit 0: protobuf 使用 zstd
uint32 decodedProtoSize            # little-endian
uint32 storedProtoSize             # little-endian
uint32 attachmentSize              # little-endian
byte[storedProtoSize] storedProto
byte[attachmentSize] attachment
```

约束如下：

- `storedProtoSize + attachmentSize < 30 * 1024`；tag 与 13-byte full header 不计入 body。
- `decodedProtoSize <= 1 MiB`，同时不得超过注册消息上限。
- 未知 flag、负值、加法溢出、声明长度与实际剩余 bytes 不一致均在分配、解压和 Proto parse 前拒绝。
- protobuf 大于 64 bytes 时 producer 才尝试 zstd level 10；只有结果严格更小时设置 bit 0。Attachment 不做协议层压缩。
- zstd 不使用 dictionary，必须精确产生 `decodedProtoSize` 并拒绝尾随压缩数据。Raw protobuf 的 decoded/stored size 必须相同。
- Proto parser 与 frame decoder 都必须精确消费各自边界。所有多字节 wire 整数均为 little-endian。

Frame transport 不解释 transfer、resource key、member、sequence、offset、completion、authorization 或 publication。Forge 外层压缩可以绕过，但不能改变上述 YSM frame 边界。Transport version 不匹配只关闭 YSM session 并进入 intrinsic-default-only，不主动断开 Minecraft connection。

## 消息注册

Model session 的 decoded Proto 上限均为 1 MiB：

| ID | 方向 | Proto | attachment |
|---:|---|---|---|
| 0 | S2C | `ServerHello` | forbidden |
| 1 | C2S | `SessionResponse` | forbidden |
| 2 | S2C | `SessionFullFragment` | forbidden |
| 3 | S2C | `SessionDeltaFragment` | forbidden |
| 4 | C2S | `MetadataPrefixRequest` | forbidden |
| 5 | C2S | `ModelChunkRequest` | forbidden |
| 6 | C2S | `PresentationPageRequest` | forbidden |
| 7 | C2S | `ResourceTransferCancel` | forbidden |
| 8 | S2C | `ResourceTransferFailure` | forbidden |
| 9 | C2S | `SelectModelRequest` | forbidden |
| 10 | S2C | `SelectModelResult` | forbidden |
| 11 | S2C | `MetadataPrefixFragment` | forbidden |
| 12 | S2C | `ChunkFragment` | required |
| 13 | S2C | `PreviewFragment` | conditional |
| 14 | S2C | `IconFragment` | conditional |
| 15 | S2C | `PackCoverFragment` | conditional |

既有消息保持以下 identity、方向和上限：

| ID | 方向 | Proto | decoded 上限 |
|---:|---|---|---:|
| 16 | C2S | `PlayerStateReport` | 128 KiB |
| 17 | S2C | `PlayerStateUpdate` | 128 KiB |
| 18 | S2C | `StarredModelsSnapshot` | 768 KiB |
| 19 | C2S | `UpdateStarredModelRequest` | 1 KiB |
| 20 | C2S | `EntityAnimationActionRequest` | 8 KiB |
| 21 | S2C | `ExecuteMolangEvent` | 64 KiB |
| 22 | C2S | `SubmitRouletteExpressionRequest` | 8 KiB |
| 23 | C2S | `EmitMolangSync` | 1 KiB |
| 24 | S2C | `MolangSyncEvent` | 4 KiB |
| 25 | C2S | `SwingHandRequest` | 256 B |
| 26 | S2C | `ProjectileModelState` | 32 KiB |
| 27 | S2C | `VehicleModelState` | 32 KiB |

28..127 当前未注册，必须在进入业务 handler 前拒绝。每个 ID 的方向、Proto 类型和 attachment policy 都是 wire contract 的一部分。

Proto 中显式 `optional` 的 ordinary singular 字段区分 absent、present-default 与 present-nondefault。consumer 不能只读取默认 getter 后丢失 presence。Repeated/map 用 empty 表达无元素，real oneof 用 case 表达选择。对 PlayerState DELTA，section 或字段 absent 表示不修改该状态，present-default 表示把该值写为默认值。FULL、section reset 与无效组合仍按下文状态契约验证，不能用 partial message 绕过。

当前 exact-version、同步构建的 unstable profile 不承诺保留或 round-trip 未知 Proto 字段。Producer 只能发送已注册 schema 中的字段和 enum 值。consumer 可以跳过未知字段，但未知 enum、message ID 和 oneof 分支不得映射成已知操作。新增 wire surface 必须提升并裁决 transport/schema version，不能依赖旧 peer 透传。

## Expression action 与本地求值

表达式只经显式 ingress 进入 wire。模型容器内的执行字段是本地解析的源字符串，不走网络。`ExecuteMolangEvent` 与 `SubmitRouletteExpressionRequest` 是携带表达式字符串的两条控制消息。`EmitMolangSync` / `MolangSyncEvent` 只携带数值，不携带表达式。

`ExecuteMolangEvent` 是 S2C 的表达式入口，只携带目标 `EntityRef` 列表与原始 Molang 表达式字符串。client 在当前 connection、当前 level 上按 entity ID 解析目标，对 player 目标在本地解析表达式并立即求值。解析失败只记本地错误，不产生跨连接状态。该消息不携带 model、target generation、module、config 或 catalog identity，也不建立需要释放的 lease。

`SubmitRouletteExpressionRequest` 是 C2S 请求，只有 `optional EntityRef target`、原始表达式字符串与其在 control 中的顺序。server 验证表达式长度上限 4096、目标为非玩家 entity 且发送者可控制该目标，再把同一表达式转发为可见客户端的 `ExecuteMolangEvent`。表达式始终由接收端在当前 entity 的本地 runtime 上解析和求值。wire 不建立独立 program 身份，也没有跨版本表达式契约。

`EmitMolangSync` / `MolangSyncEvent` 是同步对，只携带有界数量的有限 F32，保持 wire order。server 在转发前验证参数个数上限 16 且全部为有限值，再把 subject 与参数广播给可见客户端和发送者自身。接收 client 在当前 subject entity 上调用本地 Molang sync 入口。它是瞬时事件，不同步普通变量、Roaming 全量状态或骨骼姿态，也不获得 ACK、重放或持久化语义。

表达式字符串本身没有针对注释、绑定、循环或递归的独立 wire 约束。求值限制来自本地 Molang 解释器和 runtime 边界。

## Connection 与 model session

发现 YSM channel 后，server 发送只含 exact protocol version 与 roaming policy 的 `ServerHello`，client 以 `SessionResponse` 接受或拒绝。Wire 不携带 `session_id`。来源玩家与 exact Forge `Connection` 是唯一连接 identity。每条 connection 至多有一个 model session。pending hello 重试复用同一 offer。session active 后 `/ysm ping` 只做无状态连通性探测，不替换 owner 或重置 publication/transfer 状态。

Client 将 `ACCEPT` 成功提交给当前 exact connection 后，C2S 业务消息即可进入发送门禁。server 只在同一 connection 的 model session 已处理该响应并进入 active 后执行。业务消息包括 Selection、PlayerState、收藏和控制请求，即 ID 9、16、19、20、22、23、25。该资格不等待 collection publication，也不因其 assembly、commit 或 activation 失败而撤销。ID 4..7 的 catalog/resource 请求仍服从已提交 publication 及各自 admission。非法 frame、协议不匹配、变更后的重复 Hello、断连或 connection replacement 会撤销业务资格。该顺序复用同一 connection 的 transport order 与 owner-thread active 复核，不新增 wire ACK、generation、重放或待发队列。

Model-session publication 与 client 发起的 model distribution 各自拥有 connection-scoped、非零、按 unsigned 顺序单调且不复用的 `uint64` ID stream。首次 publication 必须是 transfer ID 1 的 full。新 connection 才重置 stream。合法范围是 `1 ... 0xffff_ffff_ffff_ffff`，只有发出 unsigned maximum 后才耗尽。此后拒绝新 work 并要求重连，不 wrap 到 zero，也不引入 generation、history、ACK、replay 或 resync。

## Collection publication

`SessionFullFragment` 与 `SessionDeltaFragment` 每个 packet 只包含一个 typed collection operation。接收端按 `transfer_id` 与 `sequence` 组装，要求一个 final、从 0 开始的完整 coverage，并在完整验证后一次发布。相同 physical duplicate 是 no-op。冲突 duplicate、gap、多个 final、final 后数据或旧/未知 ID 使 owner 按其失败语义终结。

接收端限制为每个 publication 至多 65,536 packets、128 MiB decoded Proto。catalog/grant 各至多 16,384 records，pack/default-animation 各至多 4,096 records。单个 packet 仍受 1 MiB Proto 与 frame `< 30 KiB` 限制。

Full 必须用 `FULL` 显式覆盖 catalog、grants、pack presentation、default animation 四个 collection，包括空 collection。Delta 只允许 `CLEAR`、`REMOVE`、`ADD`。`ADD`/`REMOVE` payload 必须非空，`REMOVE` 只携带自然 key，`CLEAR` 无 payload。Collection 顺序固定为 catalog、grants、pack、default animation。delta op 顺序固定为 `CLEAR`、`REMOVE`、`ADD`。同一 collection/op 的 packets 必须连续，key 在 packet 内及跨连续 packet 按 hash unsigned bytes 或 normalized UTF-8 bytes 严格递增，任何重复或冲突都拒绝。Grant operation 每个 producer packet 只携带一个 32-byte `ModelId`，因此保持未压缩 simple frame。其他 collection packet 可使用物理 frame 压缩。

Collection publication 不包含、等待、提交或协调 selection。Selection 继续只由既有 ID 17 `PlayerStateUpdate.model` / `ModelSelectionState` 同步。ID 9/10 只处理选择请求及 disposition，不是第二条同步 authority。

## Typed model distribution

一个 `MetadataPrefixRequest`、`ModelChunkRequest` 或 `PresentationPageRequest` 对应一个独立 `data_transfer_id`、queue admission、cancel 和 success/failure/cancel terminal。完整 descriptor 能放入一个 physical frame 时必须只使用一个 transfer。只有 descriptor 超帧时，metadata/model/page action owner 才按 canonical member 顺序拆成有限个完整 child requests。Parent 没有 wire ID。children 独立接纳，允许 earlier success 后 later `BUSY`。内部聚合、取消与 backing 所有权见[资产传输](../../architecture/network/asset-transfer.md#typed-request-与-action-owner)。

请求和 receiver-local limits 如下：

| 业务 | member key 与 canonical order | 每 transfer 上限 |
|---|---|---|
| metadata prefix | 32-byte `container_id`，unsigned bytes 严格递增 | 1..16,384 members；16,384 fragment records；128 MiB retained bytes |
| model chunk | exact model/container identity + normalized chunk name，name UTF-8 bytes 严格递增 | 1..256 members；16,384 fragment records；128 MiB stored；512 MiB decoded |
| presentation page | typed `(preview|icon|pack-cover, slot)`，各 typed list slot 严格递增；preview member 为 `slot/model_id/container_id`，icon/cover 保留 descriptor | 1..256 members；16,384 fragment records；128 MiB actual stored；非 preview descriptor decoded 合计 256 MiB；单 preview 16 MiB encoded、4096 单边、16 Mi pixels |

Metadata、model chunk、icon 与 pack cover descriptor 中每个 hash 固定 32 bytes。stored/decoded size 为正且算术不得溢出。chunk name 与 pack hierarchy 必须 canonical。Preview 不携带 name/hash/size/encoding，只验证 slot、32-byte model/container identity 与 canonical order。

Server 在推进 connection-local high-water 和触碰 source/native/allocation/retention 之前完成完整 structural validation。推进 high-water 后再按当前 session 验证 catalog identity、authorization、允许的 presentation selector 与适用 source facts。整份业务请求验证完成后才创建 source plan。

Metadata 使用 per-container sequence。model chunk 与 presentation data 使用 byte offset。Receiver 只保留已请求的 typed member。相同 duplicate 是 no-op。冲突 duplicate、overlap、越界、coverage gap、非法 final 或未知 member 使 child terminal。Presentation 每个 slot 必须是 exact data coverage 或一个 `(offset=0, final=true, empty attachment)` 的 `UNAVAILABLE` outcome。Preview 的唯一 final range 动态确定 encoded size。完整 coverage 和媒体解码通过后只按 `ContainerId` 提交独立 cache，不要求图片 hash、模型绑定或服务器隔离。Icon/cover 及 model/metadata 仍须通过 descriptor stored/decode boundary、hash、container/Manifest identity 与格式验证。

每个 child 独立接纳。容量拒绝返回一个 `BUSY`，不同 children 不作跨 transfer 原子接纳或回滚。内部 enqueue 所有权交接、source/cursor 与锁外关闭机制见[资产传输](../../architecture/network/asset-transfer.md#全局-dispatch-owner)。

## 安全与能力边界

主动对抗对象是恶意客户端。Serverbound frame/request 必须先验证 ID、方向、attachment、count、length、hash width、算术、typed selector、canonical path、compression descriptor、authorization 与当前 source facts，再允许 client-controlled 值驱动 source 获取、大 allocation、解压、native work 或长期 retention。拒绝不能创建无 owner capability，也不能影响其他 connection/session。

Client 信任已连接 server 的业务选择，但仍把远端内容当作有界数据：

- metadata、chunk 与 preview 只进入固定 container/Proto/image parser、本地 Molang 解析与求值边界、BLAKE3、zstd、bake 和 render operation。preview 的弱关联不放宽其它内容验证。模型与远端 packet 不能提供 JVM class、loader path、Java member descriptor、provider object 或任意本地 cache path。远端字符串也不选择 native library、plugin 或反射 capability。
- 远端只提供已验证 hash、固定 encoding selector 或 canonical pack hierarchy，不能提供 host path。remote cache 与 pack source 的 physical confinement 分别见[Storage](../../architecture/model-management/storage-and-cache.md)和[资产传输](../../architecture/network/asset-transfer.md#server-admission-与-source-closure)。
- 没有协议字段可直接触发 process/shell execution、动态加载、JNDI/naming/expression lookup、external entity/resource resolution、任意 URL/network resolution 或任意宿主路径。
- 日志使用参数化 data，不把远端值作为 lookup/interpolation template。

这是 process-capability isolation 与普通内容正确性边界，不是对恶意 server 的 bandwidth、CPU、累计 transfer 或内存耗尽防护。协议不增加 aggregate connection cap、timeout、sandbox、policy engine、recovery runtime，也不声称能够排除依赖当前或未来的未知漏洞。

## 选择、授权与状态顺序

Remote catalog 可展示未授权 entry。metadata、preview 与 icon 要求当前 catalog membership。普通 chunk 与普通选择按当前 catalog、grants 和 `restricted_auth` 接纳。Admission 后撤权只阻止新请求，不改写 accepted transfer。产品边界见[model-authorization](../../product-decisions/decisions/model-authorization.md)。Server-private forced-selection 的当前行为与未决例外见[Catalog 同步](../../architecture/network/catalog-sync.md#selection-与-request-admission)。

每个 client session 的首个已接纳 `PlayerStateReport` 必须是 FULL。同一 exact model session 保存这个真实 baseline，没有 baseline 的 DELTA 必须拒绝。DELTA 只修改出现字段，后续周期 FULL 是独立机会，不因先前失败立即重发。`PlayerStateReport`、`PlayerStateUpdate` 和 `StarredModelsSnapshot` 不携带 sequence/revision。本地提交与 peer 接纳分开解释，合法消息可以遗漏、重复、延迟或按不同到达顺序应用。发送、接收处理或广播失败都不得创建 ACK、重放、待发、失败恢复或回滚状态。`ModelId` 必须恰好 32 bytes。
