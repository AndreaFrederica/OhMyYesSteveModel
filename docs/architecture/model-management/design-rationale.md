# 模型管理决策理由

本页记录模型管理的工程取舍。权威分层见[文档政策](../../governance/documentation-policy.md)，产品选择见[产品决策树](../../product-decisions/README.md)。

## 分离模型身份与容器表示

[模型身份决策](../../product-decisions/decisions/model-identity.md#ddmodel-and-representation-identities)允许本地同源替代，同时要求远端精确匹配。[统一 identity 与 representation 对象](catalog-and-sources.md#身份与-representation-对象)将文件身份、已验证 view 和字节所有权放在一起，避免多个 pair wrapper、byte array 和 parsed view 竞争同一事实。

## 只保留一个当前 Catalog

来源先按既定优先级形成本次扫描候选；同一路径或同一 `ModelId` 由首个完成验证并被 owner 接纳的候选占用。Worker 完成顺序允许一次扫描内的冲突赢家不确定，避免为只影响等价冲突项的排序增加全局 barrier、优先级队列或持久化赢家。

Worker 离线完成 probe、capture、conversion 和验证，owner game thread 按处置预算将完整单项批量合成唯一不可变 snapshot。Catalog 内部用 `ContentBinding` 强持有当前 `ModelContent`；旧内容由已有 Resource 或 lease 的对象可达性保活。这个边界既让读者始终观察完整一致状态，又不让一个慢项阻挡其他已验证内容，并避免第二张 current map、公开版本值和强制失效旧 lease。

目录发布与扫描失败的产品边界见 [DD.single-current-catalog](../../product-decisions/decisions/catalog-publication.md#ddsingle-current-catalog)，用户输入保护见 [DD.user-model-sources-are-readonly](../../product-decisions/decisions/artifact-storage.md#dduser-model-sources-are-readonly)。执行流程见 [Reload 与发布](reload-and-publication.md)和 [失败处理](failure-and-recovery.md)。

## 有限工作保持 domain-owned

有限 worker 占用 capacity 后再等待 network、owner thread、同容量 child 或其他 Flight/publication，会在 saturation 与 shutdown 时形成 wait cycle。通用 runtime 若同时拥有 executor、cleanup 和 terminal，又会成为第二 completion authority，使 domain owner 无法独立证明 accepted work 已闭环。因此采用[owner admission 与同步 dependent stages](reload-and-publication.md)，independent items 仍分别提交；不将整个 Catalog 或 builtin set 串成一个任务。

## Capture 复用同一解析路径

Capture 深拷贝本次读取实际依赖的 source closure，再将冻结结果适配回同一 parser 的 scan/compile 路径。这样身份计算后不必重新读取可变输入，也不需要维护一套容易漂移的第二 parser。no-reread 与解析语义一致由同一个机制同时保证。

## 精确存储与单文件 metadata 提交

Converted/remote storage 按精确 container hash 隔离表示；私有 baked cache 还覆盖实际产物输入和 runtime profile，并明确排除 `ModelId`。模型身份不能证明某次 bake 使用了相同容器、纹理、选项或 ABI，因此拿它作为派生 cache key 会跨表示误复用。缓存完整发布见 [DD.single-current-catalog](../../product-decisions/decisions/catalog-publication.md#ddsingle-current-catalog)，用户输入与缓存处置边界见 [DD.user-model-sources-are-readonly](../../product-decisions/decisions/artifact-storage.md#dduser-model-sources-are-readonly)。

PREAMBLE 与 Manifest 在请求、验证、激活和 cache 命中中没有独立价值。Manifest 被约束为 verification 后第一个 ordinary chunk 后，二者形成一个稳定的连续 metadata prefix。同一段 bytes 可以直接传输，并以单文件原子替换作为唯一 commit point。两文件布局加 `ready` marker、只传 Manifest 或兼容读取旧布局都会重新制造半完成组合和第二套提交语义。

Metadata 不再拥有专用的 32 MiB admission 和预复制路径；完整容器、frame、queue、buffer 与溢出检查已经提供真实资源边界。为未出现的超大 metadata 增加独立 streaming 或 limit 状态机，只会扩大普通路径。物理布局见 [Storage 与 cache](storage-and-cache.md)，wire 限制见 [当前网络协议](../../standards/protocol-v1/README.md)。

## Typed distribution 同时约束 capability

固定 protobuf 类型与业务 owner 使接收数据只能选择预先实现的能力，避免将内容描述符变成通用 host capability。产品底线见[trust-boundaries](../../product-decisions/decisions/trust-boundaries.md)，能力范围见[协议](../../standards/protocol-v1/README.md)，接纳次序见[资产传输](../network/asset-transfer.md)，磁盘 confinement 见[Storage](storage-and-cache.md)。

## 分离 Ready 可达性与 Pending interest

[Ready 与 Pending 分离](ownership-and-lifecycle.md)避免将 residency 和 work 拼成一个 cell state product，也使 consumer release 不会变成共享 target 的物理关闭权。可达 lease 因而不会被其他 owner 提前销毁后继续经 JNI 使用；代价是物理回收没有同步时限。

纹理全部 sample-ready 后才提交 Ready，避免为 post-publication completion、degraded PBR 或 rollback 建立第二套 authority；失败期间可继续使用旧 Ready 或 intrinsic fallback。

## Failed Flight 不成为恢复状态

保留 failed cell 会将一次执行终态变成后续请求的命中依据，并延长旧 interest 与 transfer 的生命周期。因此[Pending registry](ownership-and-lifecycle.md)在终态移除 exact Flight，允许后续 acquire 重建；诊断由所属 domain 保存，不承担 completion authority。产品失败边界见[failure-isolation](../../product-decisions/decisions/failure-isolation.md)，处置结果见[失败处理](failure-and-recovery.md)。

## Exact session 不退休共享 owner

连接退出只让 exact session 的查询、请求、assembly 和迟到效果终态，不等待或清空进程 Catalog、runtime worker、Ready cache 与 host resource。[所有权边界](ownership-and-lifecycle.md)以精确 connection/request 和不可变 terminal 隔离新旧 session，避免 global epoch、cross-side coordinator、elapsed timeout 或共享 drain barrier；进程 owner 只在进程关闭时终结自己的 worker。

## 默认内容不建立磁盘缓存

在[默认基线契约](../../product-decisions/decisions/model-fallback.md#bcdefault-startup-is-required)要求每次全量物化、最终只读驻留的前提下，运行容器直接从 jar 输入生成内存 backing。临时容器不能节省物化，反而增加目录清理与 delegate 移交，并容易诱发跳过物化的错误优化。Baked 派生物仍按自身精确 profile 和 cache 规则处理，不因容器驻留方式获得例外。当前构造见[Intrinsic default](default-model.md)。

## 容器身份不逸出模型管理与网络

容器重新封装不应迫使动画、渲染、实体或业务层改变身份判断；精确表示只服务传输、存储和私有派生物复用。接口范围统一见[身份与 representation 对象](catalog-and-sources.md#身份与-representation-对象)。
