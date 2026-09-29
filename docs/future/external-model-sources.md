# 外部模型源

通用外部模型源尚未实现。没有可用的 provider 注册、配置、目录获取、资产 binding、凭据或生产 transport。预留词汇与组合边界不能被文档或 UI 当作已支持功能。

外部模型源与[独立 Backend](independent-backend.md)正交。Backend 是未来的玩家状态权威和可选模型服务；外部源只提供模型目录与资产，可以在 Local Only 或游戏服会话中独立存在，也不能授予游戏服权限。

## 最小接入边界

一个可用 provider 至少需要：

- 稳定 source ID、source kind、明确优先级和独立 cursor。URL、镜像地址和显示名不得充当身份。
- 产生不可变 catalog contribution，保留 namespace、path、descriptor 与逐 offer access policy。
- 按 typed subject/selector 获取资产，并支持取消、关闭、限额与 stale-context 拒绝。
- 以一次 provider context 原子绑定 catalog、cursor、凭据和 asset resolver。迟到结果不得跨刷新提交。
- 将已验证资源接入现有 remote/cache 与 publication 语义。不直接修改全局 catalog 或 render cache。
- 区分内容来源与状态授权。能下载同一 hash 不等于获得使用许可。

模型源接口应保持 Java 领域化。native 可以提供 archive、codec、hash 或图片处理能力，但不得持有 provider、凭据、catalog 或刷新状态。

## 信任要求

外部 descriptor 与资产一律视为不可信输入，必须执行与当前容器、schema 和资产传输相同的大小、结构、解码长度和内容 hash 校验。

Provider 还需隔离凭据、来源配额、并发和磁盘预算。若允许 URL 获取，必须约束 scheme、redirect、DNS rebinding、内网地址和超时，避免把客户端变成任意网络代理。

缓存只能复用经过内容验证的对象。来源私有 metadata、授权结果和 cursor 不能因 hash 相同而跨来源共享。错误、取消和关闭必须只影响对应 provider context。

## 进入当前设计的条件

只有同时完成 provider 生命周期、catalog contribution、asset resolver、缓存身份、凭据与安全策略、取消/关闭闭环和端到端验证后，才能把一种外部源加入当前来源表。

仅增加枚举、配置项或空 contribution 不构成实现，也不得先用假 binding 占位。
