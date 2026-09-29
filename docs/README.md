# 文档总览

YSM 是一个 Minecraft Java 模组，用于将玩家、投射物和载具的模型替换为基岩版模型。本文档集合说明系统现状、职责边界与未完成事项。

## 阅读入口

**初次了解本项目：**

1. [迁移概览](migration-overview.md) — 本次架构迁移的背景与当前形态。
2. [术语表](glossary.md) — 先统一专有名词，便于阅读后续章节。
3. [当前支持状态](status/support-and-verification.md) — 已验证能力、仅完成代码接线的能力，以及尚未验证的范围。

**修改代码之前：**

- [构建指南](build.md) — 前置构建、开发运行与安装包制作。
- [架构总览](architecture/README.md) / [运行模型](architecture/runtime-model.md) — 职责划分、线程与生命周期。
- [产品决策](product-decisions/README.md) — 产品层面的最终依据。与其他文档冲突时以该处为准。
- [文档政策](governance/documentation-policy.md) — 文档写作与信息取舍规则。

**按问题定位：**

| 关注问题 | 参考章节 |
|---|---|
| 模型文件的读取、校验与格式转换 | [资产管线](architecture/asset-pipeline/README.md) |
| 模型可见性、资源归属与释放时机 | [模型管理](architecture/model-management/README.md) / [概念](concepts/model-management.md) |
| 联机同步与模型传输 | [网络](architecture/network/README.md) / [概念](concepts/network.md) |
| 动画状态、Molang 与骨骼输出 | [动画](architecture/animation/README.md) |
| 几何烘焙、顶点输出与 Minecraft 绘制 | [渲染](architecture/rendering/README.md) |
| 独立前置 ysm_runtime 的接入方式 | [独立前置与迁移](architecture/native-runtime/portable-runtime.md) |
| 模型卡、预览与页面资源 | [客户端展示](architecture/client-presentation/README.md) |
| Forge 接入与第三方扩展 | [游戏与扩展接入](architecture/integration/README.md) |
| 扩展兼容性检测 | [扩展兼容性](extension-compatibility.md) |
| 模型兼容与旧格式边界 | [模型兼容](concepts/model-compatibility.md) |
| wire / 格式 / 协议标准 | [独立格式标准](standards/README.md) |

**尚未实现的方向：**

- [独立 Backend](future/independent-backend.md)
- [外部模型源](future/external-model-sources.md)
- [模组动画联动](future/mod-animation-integration.md)
- [GPU Compute Renderer](future/gpu-compute-renderer.md)

**分析材料（不构成产品权威）：**

- [复杂度地图](complexity-map/README.md) — 记录各机制的来源决策与协调成本，用于定位可简化点。仅作分析，不定义产品规则。

## 文档检查

修改文档后运行：

```powershell
python tools/check_docs.py
```

该命令检查相对链接、锚点、页面可达性，以及复杂度地图中的关系一致性。
