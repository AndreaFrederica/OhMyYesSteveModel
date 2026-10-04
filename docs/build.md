# 构建指南

本分支编译和运行依赖独立前置 **ysm_runtime**（包名与 Maven group 为 `cc.sirrus.ysmlib`）。不需要官方 native、`YSM_NATIVE_PATH` 或 `ysm.native_path`。本体通过 composite build 使用 `runtime/`，所有算法均具备 JVM 基线。迁移证据与验收范围见[独立前置与迁移](architecture/native-runtime/portable-runtime.md)。

## 获取源码与工具

- JDK **17**（完整 JDK，不能只安装 JRE），Git；Gradle 由仓库 Wrapper 下载，无需另外安装。
- 构建会访问 Maven、Forge、CurseMaven 等依赖源。Minecraft 版本为 **1.20.1**，当前验证使用 Forge **47.4.3**。
- Python 3 仅用于文档校验、辅助脚本；普通 JVM 构建不需要 C/C++ 编译器、Pixi、FFmpeg 或 WASI SDK。

```powershell
# 按仓库首页克隆迁移分支后，进入仓库根目录。
# 将 JAVA_HOME 指向本机 JDK 17 安装目录。
java -version
.\gradlew.bat --version
```

Linux/macOS 上对应命令为 `./gradlew`；具备构建工具链不代表该平台游戏兼容已经验收。`runtime/` 随本仓库提交，不是子模块，不需要另外克隆官方 native。首次构建会自动生成不入 Git 的 `local.properties`。

## 开发环境运行

使用 JDK 17，在仓库根目录运行：

```powershell
.\gradlew.bat runClient
.\gradlew.bat runServer
```

开发运行 classpath 包含完整前置。默认自动发现并优先使用自建 native，未安装的能力回退 JVM。单元测试与默认 mockHost 验收显式启用 `ysm.runtime.javaOnly=true`，native 宿主验收需单独开启。Minecraft 自身的 LWJGL/OpenGL/OpenAL 不属于被替代的官方 YSM native。精简开发客户端可运行 `.\gradlew.bat runClient '-Pysm.fast_run=true'`。

## 构建 JAR

```powershell
.\gradlew.bat -p runtime build
.\gradlew.bat build '-Pysm.fast_run=true'
```

安装到 Forge 1.20.1 客户端或服务器的 `mods/`：

| 制品 | 当前路径 |
|---|---|
| YSM 本体，`shadowJar` 发行包 | `build/libs/ysm-3.0-dev-forge+mc1.20.1.jar` |
| Oh my ysm lib 必需前置 | `runtime/forge/build/libs/ysm-runtime-forge-0.1.1.jar` |

不要安装 `build/devlibs/`、thin、sources 或内部算法模块 JAR；同一实例中每个 Mod 只保留一份。本体不嵌入前置，也不打包官方 DLL/SO/dylib。只构建安装包可使用 `.\gradlew.bat shadowJar '-Pysm.fast_run=true'`，但该命令不等于完整测试。

GitHub Release 由版本 tag（例如 `v3.0.0-dev.1`）触发的 Action 构建。它在 Java 17 上先运行前置的完整 `build`，再以精简协作依赖模式运行本体 `build` 和发行包验证，只上传上述两个可安装 JAR 及 `SHA256SUMS`。失败不会创建 Release；同一 tag 重跑会更新制品。也可在 Actions 中指定已有 tag 手动重跑。下载后将两个 JAR 一起安装到 `mods/`；可选自建 native 加速按下节单独构建和安装。

左右手由 YSM 提供；第一人称全身由可选 FirstPerson 模组驱动，已验证版本为 `firstperson-forge-2.2.3-mc1.20.jar`（仓库 `libs/` 内）。它只安装到客户端；前置本身不要求安装 FirstPerson。

发行包使用 Java 17 基线类，不从被合并的依赖自动继承 `Multi-Release` 标记。`verifyMockHostPackaging` 同时拒绝声明多版本却不含版本条目的 JAR，避免 Forge 的 SecureJarHandler 在扫描发行包时因缺少 `META-INF/versions` 而启动失败。前置同时提供格式版本 15 的 `pack.mcmeta`，避免独立实例首次加载时出现缺失资源包元数据警告。

内置默认资源和索引仍由完整生成、materialization、校验任务产生；不跳过这些步骤。普通测试使用仓库中的冻结音频样本，重新生成样本才需要 FFmpeg。V3 全 32 版本样本来自上游独立 C++ 测试写入器，普通构建无需 C++ 编译器。
内置资源生成工具和 JUnit 测试在独立 JVM 中读取 Forge 开发类；若开发 JAR 经过字节码变换仍携带旧签名，它们只对各自的临时 classpath 剥离该失效签名，不修改原始依赖或发行 JAR。

## 可选 native 构建与安装

先安装 [Pixi](https://pixi.sh/)。Windows 还需 Visual Studio 2022 Build Tools 的 C++ 桌面开发组件和 Windows SDK；Meson 会自动启用 MSVC 环境。其他平台的 C/C++ 编译器由 Pixi 环境提供，平台兼容验证范围见[支持状态](status/support-and-verification.md)。

```powershell
Push-Location runtime/native
pixi install --locked
pixi run test
Pop-Location
```

`runtime/native/build/` 生成 Windows 的 `ysmlib_codec.dll` / `ysmlib_render.dll`，Linux 为 `libysmlib_codec.so` / `libysmlib_render.so`，macOS 为对应 `.dylib`。源码依赖由 Meson wraps 固定，工具依赖由 `pixi.lock` 固定。Windows 的 JNI 差分验证：

```powershell
.\gradlew.bat -p runtime :ysm-runtime-native-codec:nativeTest :ysm-runtime-native-render:nativeTest "-PcodecLibrary=$PWD/runtime/native/build/ysmlib_codec.dll" "-PrenderLibrary=$PWD/runtime/native/build/ysmlib_render.dll"
```

将两个 DLL 与 `runtime/native/licenses/` 放入**游戏目录**的 `ysmlib/natives/windows-x64/`，和 `mods/` 同级。其他平台目录遵循 `linux-x64`、`macos-arm64` 等平台标识；不要将 DLL 放进 `mods/`。默认 native 优先，缺失或加载失败时逐能力使用 Java。路径覆盖和 ABI 说明见 [独立前置与迁移](architecture/native-runtime/portable-runtime.md)。

进入世界后按 F3：`Hash` / `Compression` 应为 `native-ysmlib-codec-v1`，`Render` 应为 `native-cpp-render-v1 (packed)`；`State`、`Bake` 等仍为 Java。`Native acceleration: disabled` 表示启动参数带有 `-Dysm.runtime.javaOnly=true`，需在启动器实例或继承的全局 JVM 参数中去掉并重启。要验证纯 JVM 兜底，则主动添加该参数。原生库选型在启动时完成。

## V3D 工具与历史工作区

V3D 工具现属于 ysmlib，可直接运行前置 JAR（Java 17，无需 Minecraft、本体或 Gradle）：

```powershell
java -jar runtime/forge/build/libs/ysm-runtime-forge-0.1.1.jar decode models/example.ysm decoded
java -jar runtime/forge/build/libs/ysm-runtime-forge-0.1.1.jar validate decoded/<generation>.v3d
java -jar runtime/forge/build/libs/ysm-runtime-forge-0.1.1.jar restore decoded/<generation>.v3d restored.ysm
```

独立轻量工具可用下述命令构建：

```powershell
.\gradlew.bat -p runtime :ysm-runtime-tools:shadowJar
```

产物为 `runtime/tools/build/libs/ysm-runtime-tools-0.1.1.jar`，命令相同。

游戏内使用 `/ysm v3d export "migration-test/_Riru.ysm"`，源路径相对 `ysm/custom`，输出在 `ysm/export/v3d`；`validate` / `restore` 接受生成的工作区目录名。命令要求单人房主或 OP 2 级权限，后台执行；多人环境读取服务端本地文件。

原 Gradle 入口继续保留，转发到 ysmlib 工具：

```powershell
.\gradlew.bat v3dWorkspace '-Pv3dSource=D:/models/example.ysm' '-Pv3dOutput=D:/models/decoded'
.\gradlew.bat v3dWorkspace '-Pv3dOperation=restore' '-Pv3dSource=D:/models/decoded/<generation>.v3d' '-Pv3dOutput=D:/models/restored.ysm'
.\gradlew.bat modelManagementMockFinal '-Pysm.fast_run=true'
```

V3D 是显式旁路操作，不修改 Catalog；restore 拒绝覆盖目标文件。完整 schema、媒体保留和编辑后的原样恢复规则见 [V3D](architecture/asset-pipeline/v3d.md)。

`ysm.fast_run` 只关闭可选开发集成 Mod，不跳过内置资源生成或测试。完整验收启动一个真实服务器与两个客户端，证据保存在 `build/model-management-mock/forge`，包括截图、JFR、动作应答和进程清理结果。

全身第一人称兼容可单独加入同一真实 Forge 验收。该选项仅给测试客户端 A 加载仓库内的 FirstPerson 模组，服务器与客户端 B 保持不安装，用于覆盖可选依赖隔离：

```powershell
.\gradlew.bat modelManagementMockForge '-Pysm.fast_run=true' '-Pysm.mockFirstPerson=true'
```

额外检查身体与腿部非空输出、第一人称头部隐藏、切回第三人称的头部恢复和禁用自身模型后的原版回退，并保存对应截图。

客户端 ready 之前还会检查真实 `GameRenderer` 在首次 tick 前的投影：正常初始视角必须有限，临时恢复原版零倍率必须复现非法投影，后续动态 FOV 和手持物 FOV 仍使用原版计算。探针只在独立测试客户端中运行，结束时恢复暂改字段；结果记录在客户端 `ready.json` 的 `startupProjection` 中。

默认宿主验收强制 JVM。自建 native 可通过 `-Pysm.mockNative=true` 启用，配合 `-Pysm.mockNativeDir=<加速库构建目录>` 指定同平台的 codec 与 render 制品。该模式同时断言 native provider 和成功 packed draw 计数；启用 FirstPerson 时，在视角切换后再次检查，不能仅凭没有崩溃认定加速通过。缺失加速库导致的 Java 回退会使此验收失败。

```powershell
.\gradlew.bat modelManagementMockForge '-Pysm.fast_run=true' '-Pysm.mockFirstPerson=true' '-Pysm.mockNative=true' '-Pysm.mockNativeDir=runtime/native/build'
python docs/tools/check_docs.py
```

真实 Forge 自动宿主目前验证的是 Windows 桌面环境，会启动独立测试世界、服务器和两个客户端。它不使用玩家存档；需有可用显示与音频设备。该检查不要与本体编译任务同时执行，以免运行中替换共享 class 输出。普通 JVM 单元测试不要求图形环境。

通用网格 GPU 检查使用隐藏的 OpenGL 上下文，调用生产纹理上传、网格缓冲及 MMD/glTF 材质程序并读回结果。检查包含原 Saba shader 的明确子集和固定 Khronos BRDF 对照、描边/点线/UV、深度/透明排序/颜色空间，以及实际 Lib 播放、target 发布回滚与独立实例关闭。它独立于普通单元测试，也不替代完整 Forge 角色、全部材质和预览验收：

```powershell
.\gradlew.bat sceneTextureGpuVerification '-Pysm.fast_run=true'
```

仅检查材质、光照和光栅状态可运行 `sceneMaterialGpuVerification`。该任务包含 MMD/glTF/MToon 的游戏光照贴图回归：同一天空光等级下更新昼夜 texel、独立方块光、自发光/unlit、描边、透明度和绑定恢复，不需要启动 Minecraft 世界。

该任务使用 Minecraft 同版本的 LWJGL 图形依赖，不启用 YSM native 加速；需要可用的 OpenGL 3.2 驱动。缺少图形上下文时明确失败，不把跳过检查写成通过。

## 独立模型配置工具

Java 17 的 CLI / Swing GUI 单独构建，无需启动 Minecraft：

```powershell
.\gradlew.bat -p runtime :ysm-runtime-scene-tools:shadowJar --console=plain
java -Xmx1g '-Dysm.runtime.javaOnly=true' -jar runtime/scene-tools/build/libs/ysm-runtime-scene-tools-0.1.1.jar gui
```

以 `help` 替换 `gui` 查看 inspect、init、bind、source-bind、calibrate-arms、set、action、validate、render、pack 命令。默认写模型旁的 `.omysm.json`；另存和 pack 拒绝覆盖已有输出。GUI 使用生产来源动作求值与独立诊断绘制，当前完整 YSM 原生曲线和游戏材质显示的缺口见[独立工具架构](architecture/scene-authoring-tools.md)。
