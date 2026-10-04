# 模型管理已知问题

本页只记录当前模型管理实现的未闭环能力。正常职责见[模型管理架构](../../architecture/model-management/README.md)。各条按「现象 → 影响 → 目前处理」组织。

- **现象**：进程 Catalog、converted 消费登记、增量 publication、direct 精确读取实例、remote/converted store、独立 consumer lease、30/60 unused target LRU 和 Cleaner fallback 已有自动化门禁。**影响**：目录观察、大目录、生产多进程 prune、长时间资源/图片回收及更广泛 reload 组合仍需独立验收。**目前处理**：自动化测试先覆盖，长时与大目录场景后续补齐。
- **现象**：局部刷新已限定到变化来源，保留未变 content、转换索引和来源诊断，编辑器临时文件与重复通知已有回归。**影响**：首次/手动完整扫描、watcher overflow 与根变化仍需目录遍历；通用依赖收集尚非最小闭包，同目录变体可能共同失效。**目前处理**：继续验证真实大目录、目录联接、外部工具写入和长时间 watcher 行为；无事件或刻意恢复全部元数据时执行完整 reload。
- **现象**：Client 与 game server 共享不可变 local content，而 exact session、client/server runtime 和授权仍是独立 owner。Remote session 通过验证后的 lean full/delta 建立 authority，再逐 entry 激活 Ready content。**影响**：远端查询表不驻留进进程 Catalog。**目前处理**：按此边界继续。
- **现象**：Remote catalog、模型资产下载、preview cache 和 0.3/0.7 秒连续需求已经接好。**影响**：当前 `0.3.0-unstable` wire 下的真实 LAN/集成 server 模型切换、共享 target demand、页面关闭、在线 delta、授权矩阵、动画资产、pack cover 和重连组合仍缺完整机器可判定验证。**目前处理**：按组合清单补真实环境验收。
- **现象**：`/ysm export` 能处理未准入缺图 direct 输入并生成完整可重开 `.mxc` 制品。cold miss 已接入真实 target/bake、256×256 offscreen draw/readback 和 worker encode。**影响**：真实 Forge 视觉输出、游戏内命令交互、各阶段文件/host 故障矩阵、长期 cache 空间与最坏合法图片/target 成本仍未验。无 client renderer 时仅内嵌图/cache 命中可导出。**目前处理**：导出能力可用但尚未完整验收。
- **现象**：正常 owner 的显式 close 在最后一份 demand 释放后的下一 client tick 触发 Pending cancel；server 在下一 dispatch boundary 停止后续 fragment。**影响**：已经提交给 transport 的 frame 不可撤回。遗漏 close 时的 Cleaner fallback 也不保证时限。**目前处理**：owner 必须执行显式 close。
- **现象**：Cleaner 不保证回收时限。**影响**：低内存和退出过程仍需实机验证 render-thread 销毁与 native/buffer 兜底没有双释放。**目前处理**：退出与低内存路径继续验证。
