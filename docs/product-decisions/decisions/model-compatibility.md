# model-compatibility

- Requirement: [REQ.preserve-model-semantics](../requirements/req-preserve-model-semantics.md#reqpreserve-model-semantics)
- Select: 导出语义冻结、兼容层级、raw/legacy 迁移
- Needs:
  - 迁移与导出中的署名保留: [DD.visible-creator-attribution](selection-and-attribution.md#ddvisible-creator-attribution)
  - 迁移与导出中的许可声明: [DD.mod-and-model-licenses-are-independent](content-rights.md#ddmod-and-model-licenses-are-independent)
  - 用户主动限制本地呈现: [DD.local-player-controls-presentation](local-presentation-control.md#ddlocal-player-controls-presentation)
  - 负尺寸描边兼容: [DD.authoring-semantics-before-optimization](geometry-regions.md#ddauthoring-semantics-before-optimization)
  - 定位组隐藏兼容: [DD.locator-hiding-is-supported-customization](attachments.md#ddlocator-hiding-is-supported-customization)
  - 默认动画兼容: [BC.default-animation-interface-is-stable](bone-and-controller.md#bcdefault-animation-interface-is-stable)
- Landing:
  - [concept](../../concepts/model-compatibility.md)
  - [architecture](../../architecture/asset-pipeline/conversion-and-export.md)
  - [architecture](../../architecture/model-management/storage-and-cache.md#render-派生物与-cache-only-探测)
  - [status](../../status/known-issues/format-and-schema.md#一致性与转换)
  - [standard](../../standards/asset-container.md#header)
  - [standard](../../standards/model-schema/assets-and-validation.md#animation-与-controller)
  - [standard](../../standards/protocol-v1/README.md#当前网络协议)

## DD.semantic-freeze-at-export

- Claim: 导出物固定当次作品语义。raw 与历史作品迁移保留可唯一解释的受支持行为。
- Rationale: 分享结果需要稳定复现，表示和转换方式不能替创作者重定义作品。缺失信息不能靠猜测补齐。对调用期间外部写入增加锁、重复扫描与变化检测，会增加维护成本，却不改善稳定来源的迁移结果，因此源文件稳定由调用方及用户负责。

### BC.exported-container-is-runtime-snapshot

- Claim: 已有容器的模型行为由它冻结的内容定义，不能因 raw 布局、后续导出规则、缓存重建或内部格式变化而被静默改写。显式 export 从一份已验证输入生成新的完整制品：保留既有 `ModelId`、作者及模型声明和所有仍适用的 stored chunks。已有有效作者 preview 原样保留；否则嵌入可用独立 preview 或本次生成图，并标记为 generated。重新计算 `ContainerId`。输出重开验证并满足 direct-container 准入后，才原子替换目标；失败不能修改来源，也不能破坏已有有效输出。合法但因缺 preview 未进入 catalog 的 direct 输入仍可直接 export；导出能力不得以先发布到目录为前提。

### BC.development-version-qualifiers-are-exact

- Claim: Container、schema、cache ABI 与 protocol 的当前版本或候选版本，只要 patch qualifier 大小写不敏感地包含 `unstable`、`dev` 或 `snapshot`，兼容判断就必须要求两个完整原始版本字符串严格相等。两侧均不含这些标记时，继续使用对应域自己的稳定版策略。本边界不替任何域承诺跨版本兼容，也不允许稳定版策略绕过开发版本门禁。

### BC.inert-input-does-not-gain-future-behavior

- Claim: 历史输入中已明确不生效、也不进入当前制品语义的内容可以静默省略，不为这些值产生无效警告。未来新增能力必须显式引入，不能让旧制品因运行时升级获得此前不存在的行为。

### BC.raw-and-legacy-formats-are-supported

- Claim: 当前兼容承诺包括 raw 和 legacy v1、v2、v3。不能因输入格式较旧，就把已有受支持作品排除在兼容范围之外。

### BC.successful-import-sheds-legacy-authority

- Claim: 历史模型转换为当前制品后，受支持的几何、动画、脚本、纹理及声音语义仍需保留。原容器组织或编码改变，不构成丢失这些行为的理由。

### BC.legacy-source-stability-is-caller-owned

- Claim: Legacy 文件导入以调用期间源文件保持稳定为前提，由调用方及用户负责。并发替换、覆写、截断或增长后的导入结果不作保证，也不承诺检测这些变化。导入器仍须拒绝实际发现的 I/O、格式和校验错误。

### BC.ambiguous-input-fails-locally

- Claim: 损坏、截断或无法唯一解释的输入，必须明确拒绝相应内容。不猜测性修复，也不使普通模型迁移失败升级为游戏启动失败。

## DD.explicit-support-is-stable

- Claim: 按明确支持、未明确维护但未禁止、禁止或不推荐三个级别承担兼容责任。明确支持的可观察行为无条件维护。
- Rationale: 明确支持是创作者可依赖的作品基础。为全部偶然效果承诺无条件兼容，会无限扩大成本。广泛艺术技巧值得尽力保留，典型可以单独提升为明确支持；已劝阻的行为不强迫主线长期维护。

### BC.supported-features-do-not-break

- Claim: 模组更新不得打破已明确支持的可观察模型行为。表示方式、性能优化和技术栈变化均不构成破坏该行为的理由。

### BC.unclassified-features-have-no-absolute-guarantee

- Claim: 未明确支持且未禁止的行为，只受尽力维护约束。将某项技巧提升为明确支持后，才进入无破坏性变化的承诺。

### BC.discouraged-features-may-break

- Claim: 明确禁止或不推荐的特性，可能在任何一次更新后失效，不能据此要求恢复该行为。

### BC.widespread-artistic-techniques-are-best-effort

- Claim: 借助优化或其他实现特性反向形成的广泛艺术技巧，采用 best-effort。其中已明确提升为支持范围的典型技巧，按明确支持契约维护。不能从一个典型推广到整类技巧的无条件兼容。

默认动画复用的 best-effort 边界见 [BC.default-animation-interface-is-stable](bone-and-controller.md#bcdefault-animation-interface-is-stable)。负尺寸描边与定位组隐藏的显式承诺按 Needs 读取。格式和协议仍处于 unstable，不降低这些已明确承诺的级别。
