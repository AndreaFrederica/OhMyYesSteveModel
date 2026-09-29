# Asset Container 规范

Asset Container 是跟资产类型无关的二进制封装层。它描述 schema、chunk、存储编码、对齐和完整性。它不定义模型、缓存、网络或资源所有权。

本规范描述当前 wire version `0.1.0-unstable`（major 0、minor 1、patch 0、qualifier `unstable`）。该格式尚未冻结，不应视为稳定契约。

文中的「必须」「不得」「应」「可以」分别表示规范要求、禁止、建议和可选行为。除特别说明外，多字节整数均为 little-endian，区间均为左闭右开。

## 使用 profile

同一份已验证 metadata 可以按两种 profile 使用：

| Profile | 当前文件包含的内容 | 用途 |
|---|---|---|
| 完整自包含文件 | verification payload 与所有普通 chunk payload | 持久文件、导入和完整离线校验 |
| verified preamble | 只到 verification payload 结束 | 传输描述、索引和由外部数据源提供普通 chunk |

Preamble 不是另一种 wire format。它是完整文件的已验证结构前缀。chunk table 本身不含 inline/standalone 标志、路径、URL 或缓存键。当前规范不定义「部分普通 chunk inline、部分外置」的混合文件，也不允许用任意尾随数据扩展完整文件。

## 基本类型与限制

| 项目 | 当前要求 |
|---|---:|
| 完整文件最大长度 | `134217728` bytes（128 MiB） |
| summary 最大长度 | `16384` bytes |
| base header 长度 | `64` bytes |
| qualifier 固定宽度 | `13` bytes |
| schema id 固定宽度 | `32` bytes |
| schema property 最大数量 | `32767` |
| chunk 最大数量 | `32767`，包含 verification chunk |
| chunk 最大 alignment | `2^8 = 256` bytes |
| BLAKE3 digest 长度 | `32` bytes |

固定字符串和短字符串必须是 ASCII。生产者必须拒绝不能无损编码为 ASCII、含内嵌 NUL、或超过字段宽度的值，不得静默替换字符。固定字符串以 `0x00` 填充到字段宽度；第一个填充字节之后必须全为零。短字符串由一个 `uint8` 长度和相应字节组成，当前允许长度为 `0..127`。

Summary 是 UTF-8 文本，可以为空，不得含 `0x00`，并由一个 `0x00` 终止。它只用于人类可读提示，不是机器可读 metadata，也不得参与身份决策。

## 总体布局

```text
magic
summary + 0x00
header
schema properties
chunk table
verification payload
aligned ordinary chunk payloads    # 仅完整自包含文件
```

Magic 必须逐字节等于：

```text
EF BB BF 59 53 47 50 32 0A 0A
```

其中可见 ASCII 部分为 `YSGP2\n\n`。`YSGP2` 是沿用的历史文件签名，只用于字节级识别。其中的 `2` 不表示本规范的版本 major，也不表示任何项目对本规范的所有权。

派生偏移如下：

```text
headerOffset        = magicSize + summarySize + 1
propertyDataOffset  = headerOffset + headerSize
chunkTableOffset    = propertyDataOffset + propertyDataSize
chunkDataOffset     = chunkTableOffset + chunkTableSize
```

所有加法必须检查溢出。所有 metadata 区域必须位于当前输入内。完整文件还必须验证所有普通 payload 范围；preamble 只要求输入恰好覆盖到 verification payload 结束。

## Header

Base header 从 `headerOffset` 开始：

| 相对偏移 | 长度 | 字段 | 类型 | 当前语义 |
|---:|---:|---|---|---|
| 0 | 2 | `headerSize` | `uint16` | header 总长度，至少 64 |
| 2 | 1 | `majorVersion` | `uint8` | 当前必须为 0 |
| 3 | 2 | `minorVersion` | `uint16` | 当前必须为 1 |
| 5 | 2 | `patchVersion` | `uint16` | 当前必须为 0 |
| 7 | 13 | `qualifier` | fixed ASCII | 当前必须为 `unstable` |
| 20 | 32 | `schema` | fixed ASCII | 上层 schema id |
| 52 | 2 | `schemaPropertyCount` | `int16` | 必须非负 |
| 54 | 2 | `chunkCount` | `int16` | 必须为 `1..32767` |
| 56 | 4 | `propertyDataSize` | `int32` | 必须非负 |
| 60 | 4 | `chunkTableSize` | `int32` | 必须非负 |

`headerSize > 64` 时，剩余字节是未知 header extension。兼容 consumer 应跳过它们；这些字节仍属于 verification input。

Consumer 把四个版本字段组合成 canonical `major.minor.patch[-qualifier]`，然后执行版本准入。当前 profile 必须精确匹配 `0.1.0-unstable`，任一字段不同都必须拒绝。

通用门禁还有一条：当前或候选 qualifier 大小写不敏感地包含 `unstable`、`dev` 或 `snapshot` 时，两个完整原始版本字符串必须严格相等。两侧都不含这些标记时，才可委托该 profile 的稳定版策略。当前 profile 的稳定版策略仍为 exact，不做 minor、patch 或 qualifier 兼容推断。

## Schema properties

Schema property 区域必须恰好包含 `schemaPropertyCount` 个条目，不得有尾随字节：

| 字段 | 类型 | 约束 |
|---|---|---|
| `type` | `int16` | 非负，且在容器内唯一 |
| `value` | short ASCII string | 由上层 schema 解释 |

未知 property type 可以保留或忽略。property 顺序没有语义；上层 schema 不得依赖其序列化顺序。

## Chunk table

Chunk table 必须恰好包含 `chunkCount` 个条目，不得有尾随字节。每个条目依次为：

| 字段 | 类型 | 语义 |
|---|---|---|
| `type` | short ASCII string | 容器内唯一的 chunk 名称 |
| `encoding` | short ASCII string | 存储编码或媒体格式；空串表示直接 payload |
| `size` | `int32` | stored representation 的字节长度，必须非负 |
| `decodeSize` | `int32` | 解码长度或 schema 定义的辅助 metadata |
| `flags` | `int32` | 由上层 schema 定义 |
| `alignmentShift` | `uint8` | payload 对齐为 `1 << alignmentShift`，范围 `0..8` |
| `hash` | 32 bytes | 普通 chunk 的 BLAKE3-256；verification 条目全零 |

`type` 必须唯一。`verification` 是保留名称，必须且只能出现一次，并且必须是第一个条目。

完整文件中所有普通 chunk 均按 table 顺序 inline。Preamble 中没有普通 payload，但 table 仍描述完整逻辑资产；外部数据源必须按同一 descriptor 提供 stored representation。

## Offset 与 alignment

完整文件按下式派生每个 chunk 的 offset：

```text
cursor      = chunkDataOffset
alignment   = 1 << alignmentShift
paddingSize = (alignment - cursor % alignment) % alignment
offset      = cursor + paddingSize
cursor      = offset + size
```

Padding 必须存在且全部为零。它不属于 payload，也不进入普通 chunk hash。offset 必须满足声明的 alignment，payload 范围不得重叠或越界。最后一个 payload 结束位置必须等于完整文件长度；不允许尾随数据。

Verification chunk 的 `alignmentShift` 必须为 0，因此其 offset 等于 `chunkDataOffset`。Preamble 长度固定为：

```text
verification.offset + verification.size
```

## Stored representation、logical content 与 hash

必须区分 stored representation 和 logical content：

- `encoding = "zstd"`：stored representation 由 [RFC 8878](https://www.rfc-editor.org/rfc/rfc8878.html) 定义的 Zstandard 与 skippable frame 组成。`size` 是完整 stream 长度，`decodeSize` 是全部 standard frame 解压后的总长度，chunk hash 覆盖解压后的 logical content。Canonical producer 写一个不依赖外部 dictionary 的 standard frame。consumer 接受一个或多个 standard/skippable frame，但至少包含一个 standard frame，并必须拒绝 dictionary 依赖和未被这些 frame 消费的尾随垃圾。
- 空 encoding：stored bytes 直接就是 logical content。`decodeSize` 通常为 0，hash 覆盖 stored bytes。
- schema 定义的直接媒体 encoding（例如图像格式）：stored bytes 同时作为 logical payload。`decodeSize` 可以由 schema 复用为尺寸 metadata，hash 覆盖 stored bytes。

所有 BLAKE3 值遵循 [BLAKE3: one function, fast everywhere, 2020-01-09](https://github.com/BLAKE3-team/BLAKE3-specs/blob/ea51a3ac997288bf690ee82ac9cfc8b3e0e60f2a/blake3.pdf)，使用无密钥模式的默认 32-byte 输出，不使用 keyed/derive-key 模式或额外 domain separator。生产者必须在 table 中写入与上述 hash 域一致的原始 32 bytes。

消费者必须先检查 stored size，再完成必要的解码、精确 `decodeSize` 和 hash 验证，最后才能暴露 logical content。对外部数据源同样适用——不能因为 metadata 已验证就跳过 payload hash。

未知 encoding 只能交给明确支持它的上层 chunk handler。不得猜测编码，也不得把未知 encoding 当作 zstd。

## Container verification

Verification input 是 `[0, chunkDataOffset)`，包括 magic、summary 及终止符、完整 header、schema properties 和整个 chunk table。它不包括 verification payload、alignment padding 或普通 payload。

本版本定义的 verification profile 为：

| Encoding | Payload | 状态 |
|---|---|---|
| `BLAKE3` | `hash + signLength + sign` | 支持 |
| `ED25519` | 未定义完整 payload 与信任策略 | 仅保留名称，必须按不支持拒绝 |

`BLAKE3` payload 固定为：

```text
byte[32] hash                  # verification input 的 BLAKE3-256
uint16   signLength            # little-endian
byte[signLength] sign
```

Payload 长度必须精确等于 `34 + signLength`。旧 32-byte payload、缺失长度和尾随 bytes 均拒绝。`hash` 同时是 `ContainerId`。`sign` 是可为空的 opaque bytes，当前不定义算法、key id、作者、信任或授权语义。相同长度的 `sign` 改变不影响 `ContainerId`；改变 `signLength` 会改变 chunk table 中的 `size`，从而改变 verification input 和 `ContainerId`。

Verification table entry 还必须满足：`size = 34 + signLength`、`decodeSize = 0`、`flags = 0`、`alignmentShift = 0`、`hash` 全零。未知 verification encoding 必须拒绝。

BLAKE3 verification 只提供意外损坏检测。能够重写文件的攻击者也能重算 digest。它不提供作者身份、授权或抗篡改保证。

## 验证顺序

Metadata reader 必须按以下顺序 fail closed：

1. 检查输入大小、magic 和 summary 终止符。
2. 解析 base header，检查版本、计数、长度和派生偏移。
3. 精确消费 schema property 区域并拒绝重复 type。
4. 精确消费 chunk table，拒绝重复 chunk type 和非法 alignment。
5. 检查首项 verification 的结构约束。
6. 验证 payload 长度、verification input 与 `ContainerId`；调用方提供 expected `ContainerId` 时再做精确比较。
7. 根据调用方声明，确认输入是恰好结束的 preamble，或继续执行完整文件验证。
8. 完整文件逐 chunk 检查零 padding、stored size、解码长度和 hash，并拒绝截断或尾随数据。

仅完成第 1–6 步只能证明 metadata/preamble 完整，不能证明普通资产存在、可解码或满足上层 schema。

## 生产者要求

生产者必须先确定全部 schema properties、chunk descriptors 和 `signLength`，再生成 verification payload。当前 producer 可以写空 `sign`，但必须保留长度字段。完整文件必须按 table 顺序写出所有 payload 和零 padding；preamble 导出必须在 verification payload 后立即结束。生产者不得输出非 ASCII 字符、混合 inline/external payload 或尾随私有数据。

上层 schema 负责定义 chunk type、非 zstd encoding、`decodeSize`、`flags` 和资源引用语义。当前模型 schema 见 [模型 Schema](model-schema/README.md)。测试要求见 [格式一致性](conformance.md)。
