# Oh My YSM Lib 模型配置工具

Java 17 独立工具，默认写入 `模型.pmx.omysm.json`，不修改源模型、纹理或动作。支持 YSM 元数据、目标人体角色绑定、YSM 源骨名绑定、放置/比例和动作来源编辑。原始 `.blend`、`.unitypackage` 需要先导出为支持的模型格式。

在仓库根目录构建：

```powershell
.\gradlew.bat -p runtime :ysm-runtime-scene-tools:shadowJar --console=plain
$tool = 'runtime/scene-tools/build/libs/ysm-runtime-scene-tools-0.1.1.jar'
java -Xmx1g '-Dysm.runtime.javaOnly=true' -jar $tool gui
java -Xmx1g '-Dysm.runtime.javaOnly=true' -jar $tool inspect 'D:/models/avatar/model.pmx'
java -jar $tool init 'D:/models/avatar/model.pmx'
java -jar $tool bind 'D:/models/avatar/model.pmx' --role head --bone 12
java -jar $tool source-bind 'D:/models/avatar/model.pmx' --role head --source Head
java -jar $tool set 'D:/models/avatar/model.pmx' --field retarget.translationScale --value 0.625
java -jar $tool set 'D:/models/avatar/model.pmx' --field placement.sizeMode --value HEIGHT
java -jar $tool set 'D:/models/avatar/model.pmx' --field placement.height --value 1.8
java -jar $tool set 'D:/models/avatar/model.pmx' --field metadata.name --value 'My avatar'
java -jar $tool action 'D:/models/avatar/model.pmx' --name walk --path walk.vmd --clip 0 --loop true
java -jar $tool render 'D:/models/avatar/model.pmx' --action walk --seconds 0.5 --out walk.png
java -jar $tool validate 'D:/models/avatar/model.pmx'
```

示例骨索引和位移比例必须按实际骨架检查；`inspect` 输出原骨名、索引、父级、动作和建议。缺失或歧义不静默绑定。`--out` 将创作结果写入新文件，`--config` 读取另一份配置；`apply --file` 校验并保存完整配置。`pack --out model.yscene` 可选封装原资产和配置，不进行有损 PMX 重编码。

Windows Java 17 若无法正确接收中文模型路径，可把绝对路径保存为 UTF-8 文本，再传 `@path.txt`。GUI 文件选择器不受命令行编码影响。诊断在 stderr，结构化结果在 stdout，失败退出码为 2。查看全部命令使用 `help`。

GUI 可拖动旋转和滚轮缩放；动作页提供真实来源及显式别名的播放、暂停、逐帧和跳转。预览物理始终关闭。当前预览是有深度缓冲的真实几何/骨骼诊断，尚无原纹理和游戏材质效果；原生 YSM 全曲线/Molang 独立求值未完成，`@ysm/generated/` 是基础回退动作，不能用于证明原生动作已重定向。

字段见[配置标准](../../docs/standards/scene-model-profile.md)，职责和限制见[架构](../../docs/architecture/scene-authoring-tools.md)。

## 任意骨骼与状态过渡

预设角色之外使用 `bind MODEL --role bone:skirtLeft --bone 42`，再用 `source-bind MODEL --role bone:skirtLeft --source SkirtLeft` 选择原 YSM 源骨。`retarget.sourceModel` 可通过 set 编辑，默认为 `@ysm/default`；自定义源使用 MC 模型内容哈希，必须在游戏宿主可获得。

`transition MODEL --from idle --to sneaking --path crouch-down.vmd --clip 0` 保存单次过渡；path=AUTO 删除该边。状态动作仍由 action 命令配置；带骨架绑定且未显式覆盖的游戏状态使用真实 YSM 控制器。GUI 的状态过渡页与完整 JSON 编辑使用相同格式。独立工具仍不提供完整 Minecraft 控制器模拟。
