# V3D 历史源旁路缓存

V3D 是 legacy V3 输入边缘的可逆 sidecar/workspace，当前目录 profile 为 unstable。它不进入 Catalog，也不替代 `.mxc`。成功迁移后的运行语义仍由当前制品定义，遵循 [BC.successful-import-sheds-legacy-authority](../../product-decisions/decisions/model-compatibility.md#bcsuccessful-import-sheds-legacy-authority)。V1/V2 继续通过 archive → raw 管线，不使用 V3 wire decoder。

## 数据与职责

三层数据分别是 exact source、exact historical wire 和独立历史 schema 的 decoded view。文件协议由独立 `ysm-runtime-v3d` 模块中的 `cc.sirrus.ysmlib.v3d.V3dCache` 实现，只依赖能力 API、JDK 和 Gson；注入 `V3EnvelopeProvider` 与 `DecodedWorkspaceProvider`。`YsmRuntime.v3d()` 生成全部三层；仅注入 envelope provider 的构造器生成前两层。本体旧 `V3dCache` 仅保留 buffer/调用兼容适配，不持有目录协议或 manifest 类型。无需加载 native；调用方负责调用期间源文件稳定以及同一 cache root 的写入串行化。

## 工具与游戏指令

`ysm-runtime-tools` 提供独立可执行 JAR，入口 `cc.sirrus.ysmlib.tools.V3dTool`。它只组装 V3 providers 与 V3D 文件模块，不初始化图像、音频、渲染或 Minecraft。前置 Mod JAR 也包含同一 CLI，可直接通过 `java -jar` 使用。支持 `decode`、`validate`、`restore`；退出码为 0 成功、1 操作失败、2 参数错误。旧 Gradle 任务只转发到这个入口。

游戏入口为 `/ysm v3d export "相对 ysm/custom 的模型路径"`、`/ysm v3d validate "工作区目录名.v3d"`、`/ysm v3d restore "工作区目录名.v3d"`。单人房主或服务端 OP 2 级可用；联机时操作的是服务端文件，不是远端玩家本地文件。导出根为 `<game>/ysm/export/v3d`，恢复根为 `<game>/ysm/export/restored`，不会扫描进 Catalog。命令适配仅在本体；文件内容处理统一调用 ysmlib。拒绝绝对路径、越界与符号链接；同一进程只接收一个后台任务，繁忙时立即提示。完成反馈返回服务器线程，并检查玩家连接是否仍为原连接。恢复不会覆盖目标文件；编辑 decoded view 后仍允许原样恢复。

- `source/original.ysm` 保存输入的逐字节副本。`restoreOriginal` 校验 archival source/wire 和依赖元数据后复制它到一个新文件。编辑 decoded view 不阻止恢复原文件，拒绝覆盖已有文件；不重新加密或压缩。
- `source/wire.bin` 保存解密、去混淆、dirty-zstd 规范化及解压后的原始 bytes，包括开头的 little-endian inner version。Capture 验证 envelope/checksum/frame EOF 与 inner version 1–32，不声称历史模型语义已经通过验证。
- `v3d.json` 保存格式名 `ysm-v3-decoded`、format version、decoder profile、source SHA-256/size、outer/inner version 以及 wire SHA-256/raw size。`integrity.json` 保存 source/wire 摘要与 wire 对 source hash、decoder profile 的依赖。

独立 `java-v3` 的 envelope provider 返回 Java 拥有的 plaintext，受 256 MiB 上限约束；source 上限为 69,206,016 bytes。宿主 adapter 包装 `ArrayBuffer` 并关闭 `DecodedWire`。生产 `.mxc` 导入也使用该 provider，再经过 bounded reader 与 schema projector；写出、重开与历史 identity 契约不变。

## 缓存与发布

正常 V3 导入现在使用两级复用：先按源 SHA-256 与转换 profile 复用已验证 `.mxc`；成品 miss 时通过 `YsmRuntime.v3dWire()` 和 `LegacyImportProvider.importWire()` 从已解压 wire 重新投影。自动缓存位于游戏目录 `ysm/cache/legacy`，与显式导出工作区分离。`capture()` 提供源 bytes 与哈希的同一不可变快照；`readWire()` 校验生成物并验证实际交给 projector 的 bytes。转换 receipt、失效规则和并发锁见 [Storage 与 cache](../model-management/storage-and-cache.md#v3-源哈希与转换复用)。

`materialize(source, cacheRoot)` 返回 `<source-sha256>-p<decoder-profile>-d<parser-profile>.v3d`；仅 capture 模式不含 `-d`。同源同 profile 时，先校验支持的 manifest、固定文件路径、实际文件大小、两个 SHA-256、inner version 与完整 dependency metadata，全部符合才复用，不再 decode 或重写。历史 model identity 不替代 source freshness。

构建使用 root 内的 `.v3d-pending-*` 临时目录，写完并重读校验后执行原子目录 rename。文件系统不支持原子 rename 时失败，不退化为递归复制。源或 profile 变化产生新的不可变 generation，已有有效目录不被替换。损坏的同名目录先原子移到 `.v3d-invalid-*`，发布失败时尝试恢复；隔离内容保留以便检查，不参与缓存命中。进程中断最多留下 pending/invalid 目录或缺失的目标，不发布半成品。该协议不承诺断电持久性；清理旧 generation/隔离内容由调用者负责。

## 独立历史解码与物化

`DecodedWorkspaceProvider` 是仅依赖 JDK 的能力接口，输出相对路径和只读 bytes，由宿主负责文件写入、哈希、原子发布（现由 ysmlib 的 V3D 文件模块承担）。`JavaDecodedWorkspaceProvider` 使用 historical reader 的 archival 路径，不调用 `HistoricalProjector`、不调用图像编码器、不依赖 Minecraft 或当前 protobuf。运行时导入仍可进行目标表示规范化，但 archival 路径保留所有已读字段和原编码媒体。

`legacy/model.json` 声明 `LegacyV3Decoded` schema version 1。它保留版本、历史 identity、export/order 信息与各类资源引用。1–18 版保存原始 model type 列表、optional 资源列表和独立 source hash maps，不将旧箭模型/纹理名或缺失 metadata 提前合并到当前模型语义。19–32 版保存 common/player/replacement/info 结构。可选字段缺失表示 wire optional absent；列表中的 absent 元素保留 null。map 用有序 `{ "$map": [{"key": ..., "value": ...}] }` 表示，保留键类型和插入次序。非有限 float32 用原始位模式 `$float32` 表示，有限值按 float32 往返精度写出；原始整数保持读入的位宽（uint32/uint64 使用 Java 有符号数的同一位模式）。这些结构独立于当前 Model Schema，不能当作 Bedrock authoring JSON。

- geometry JSON 保存层级、五个骨骼标志、pivot/rotation、visible bounds、脚本和 cube zero-size；quad 数值写入 `.mesh`。格式为 LE32 magic `0x314d5359`、LE32 quad count，每个 quad 23 个 little-endian float32：normal xyz，再顺序写四个 vertex 的 xyzuv。V5 保留历史 global cube count；不猜测原始 cube authoring 声明。
- animations/controllers/metadata/lang 写 JSON；functions 写原始 UTF-8 `.molang`。
- PNG/JPEG/WebP/AVIF 和 Ogg 原 bytes 直接复制；未知/不可播放的历史音频也保留 archival bytes。wire 的图像 enum 仅允许 RGBA/PNG/JPEG/WebP/AVIF（1–5），不凭空扩展历史格式为 ZTX。
- RGBA 写 canonical type-2 TGA：32-bit BGRA8、top-left、8 alpha bits、无 RLE/色彩元数据；`original_rgba_sha256` 绑定原 RGBA。RGBA→BGRA 只换通道次序，不缩放或做色彩转换。

文件采用按遍历次序分配的安全编号名，原用户名称保留在 JSON map key，避免路径穿越、Windows 保留名和重复名字覆盖。manifest 与 integrity 包含 parser/schema profile、wire 依赖及每个 decoded 文件的大小和 SHA-256。完整 `validate` 检测编辑与损坏，拒绝符号链接。编辑 view 不损坏 archival recovery；作为缓存再次物化时，旧 modified 目录保留在隔离目录。

历史 parser profile 变化产生新 generation；current compiler 变化只重建 `.mxc`，renderer 变化只重建 renderer cache。当前新 generation 会重新读取源，尚未优化跨 profile 的 wire 复用。编辑后重新编码新 V3 是原计划单独列出的未来 encoder，不属于恢复原始 V3 操作。
