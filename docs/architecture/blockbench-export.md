# Historical YSM 到 Blockbench 的可编辑导出

`YsmRuntime.blockbench()` 提供面向二次编辑的 `.bbmodel` 导出器。它接收 V3 `.ysm` envelope，也可以接收 `JavaV3EnvelopeProvider` 已解出的历史 wire，输出单个 UTF-8 Blockbench free-format 工程；纹理以 PNG data URI 内嵌，因此导出的文件可以独立打开。

导出器直接消费 `HistoricalWireReader` 的规范化 wire 树。它不经过 `HistoricalProjector` 的当前 schema，这样 format 5 的单 quad cube、历史 UV、PBR typed map、骨骼标志以及控制器的原始条件不会在导出前被删除。

几何映射遵循一个可验证的规则：只有恰好六个轴对齐面、法线各不相同、顶点位于同一个轴对齐包围盒面上、且每个面的 UV 是矩形时才生成 Blockbench cube。任何其他 quad（包括 format 5 的特殊几何、旋转面、退化面和非矩形 UV）生成 Blockbench mesh；每个原始 quad 都保留为一个 mesh face，顶点和 UV 不做重建。mesh fallback 会在顶层 `ysm.diagnostics` 记录原因。

骨骼输出为 Blockbench outliner group，保留 parent、pivot、rotation、隐藏状态以及 `cubes_hidden`、`hide_children`、`reset` 等历史标志。动画 tick 转换为 Blockbench 秒（除以 20），同时在 keyframe 的 `ysm_tick`、`ysm_pre`、`ysm_post` 字段中保留历史值；动画原始 wire（包括 delay、weight、instruction 和 sound）也保存在 `ysm.animations`。Molang controller、function、未知 PBR 类型和其他 Blockbench 没有等价编辑器的字段放在顶层 `ysm` sidecar 中；这些字段会生成诊断条目，而不会静默丢弃。

纹理和 PBR 图像使用 `ImageProvider` 解码为 RGBA，再写成标准 PNG data URI。每个纹理对象带有 `ysm_role`、`ysm_source_encoding`、`uv_width` 和 `uv_height`；`ysm.materials` 保存 UV、normal、specular 的 typed 绑定，normal/specular 贴图仍可在工程中独立编辑和追溯。没有 UV 纹理时插入明确命名的 `missing.png`，并记录 `missing_texture` 诊断。

导出同时写入 `source_wire_sha256`，并在 wire 不超过嵌入预算时写入 `source_wire_base64`。因此 Blockbench 工程本身可以作为历史输入的恢复载体；超过预算的源仍会被拒绝静默丢弃，而是写出 `source_wire_omitted` 诊断。

API：

```java
byte[] bbmodel = YsmRuntime.blockbench().exportModel(source);
Files.writeString(path, new String(bbmodel, StandardCharsets.UTF_8));
```

这是“可编辑工程”导出器，不承诺把 Blockbench 重新导回历史 YSM wire。要做审计或完整恢复，应继续使用 `YsmRuntime.decodedWorkspace()`；该路径保留原始 quad mesh、资源字节和 typed map。
