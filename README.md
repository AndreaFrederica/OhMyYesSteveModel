# Yes Steve Model

本 fork 使用独立前置 **Oh my ysm lib**（`ysm_runtime`，作者 AndreaFrederica）替代官方 YSM native。编译、内置资源生成和运行都接入我们的库；各能力提供 JVM 基线，可用的自建 native 优先加速。当前 native 覆盖 BLAKE3、zstd 和 packed 顶点输出，其余能力仍使用 JVM。

从源码构建和安装请看 **[构建指南](docs/build.md)**；库的模块划分见 **[runtime/README.md](runtime/README.md)**。当前对接本仓库的 YSM fork，尚不支持直接替换未经修改的官方 Mod。已验证范围和未完成项见[当前支持状态](docs/status/support-and-verification.md)。

使用 JDK 17，在 PowerShell 中克隆并构建：

```powershell
git clone --branch feature/v3d-decoded-cache https://github.com/AndreaFrederica/YesSteveModel.git
cd YesSteveModel
.\gradlew.bat -p runtime build
.\gradlew.bat build '-Pysm.fast_run=true'
```

安装本体 `build/libs/ysm-3.0-dev-forge+mc1.20.1.jar` 和前置 `runtime/forge/build/libs/ysm-runtime-forge-0.1.0.jar`。可选 native、FirstPerson 和开发启动步骤见构建指南。

## ⚠️ 警告

当前公开版本还未完成，不保证稳定性、数据安全、跨平台行为、API、代码结构或后续版本兼容性。请勿用于生产环境或重要存档，测试前务必备份游戏目录、世界和模型。

所有公开格式、Schema、内部协议和缓存布局均尚未冻结，在正式发布前大概率会有 break change，并且不做向后兼容。

## 项目状态

本项目正在进行大规模重构，目前公开代码主要用于审阅、协作和验证设计。

目前仅协作者可贡献代码，如有贡献意愿可加入 YSM 开发者交流群了解详情。须知当前代码结构还未稳定，贡献者的本地开发进程可能得跟着主线一起重构。待模型管理和网络协议完成开发、旧版能力完成迁移，将会开放贡献。

更多信息见 [迁移概览](docs/migration-overview.md) 

## 与旧版相比

| 类别     | 重点                                                                                                                                                    |
| ------ |---------------------------------------------------------------------------------------------------------------------------------------------------------|
| 新增能力   | 公开的模型资产标准；细粒度资产分发；动态资源管理；更多平台支持。                                                                                        |
| 既有能力改进 | 模型业务回归 Java，native 收缩为能力层；内容身份、连接、资源所有权、失效和恢复边界显式化。                                                              |
| 兼容验收   | 左右手、背景模型和 FirstPerson 全身兼容已恢复，并完成 JVM/native 代表性实机验证；其他附着 layer、第三方模组与 shader 组合仍需逐项验收。 |
| 迁移重点   | 重构 molang 引擎；扩展 API；将 x64 基线降至 x86-64-v1；适配 Windows 7。模组联动、手臂模型、layer 等旧代码迁移。 |
|        |                                                                                                                                                         |
| 未来方向   | 模型签名、通用外部模型源、GPU Compute Pipeline、独立 Backend。                                                                                |

## 文档

- [完整索引](docs/README.md)
- [构建指南](docs/build.md)
- [术语表](docs/glossary.md) / [文档政策](docs/governance/documentation-policy.md)
- [独立格式标准](docs/standards/README.md)
- [产品决策](docs/product-decisions/README.md)
- [架构总览](docs/architecture/README.md) / [运行模型](docs/architecture/runtime-model.md)
- [当前支持状态](docs/status/support-and-verification.md)

## 许可证

- 除另有声明的内容外，本仓库的原创代码按 [Apache License 2.0](LICENSE) 开源。
- Asset Container Spec、Model Schema、规范性 Proto 快照及一致性要求是独立于 YSM 和 Minecraft 的标准，按 [CC0 1.0 Universal](LICENSES/CC0-1.0.txt) 发布。
- 内置模型资产不属于 Apache-2.0；每个资产目录中的 `ysm.json` 是其许可证的权威清单。
- 项目包含直接拷贝或修改的第三方代码以及随包依赖，详见[NOTICE.md](NOTICE.md)。

Apache-2.0 不覆盖上述独立标准、内置资产或第三方作品；对应文件中的单独声明优先。
