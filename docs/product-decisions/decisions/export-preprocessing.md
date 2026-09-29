# export-preprocessing

- Requirement: [REQ.portable-model-artifact](../requirements/req-portable-model-artifact.md#reqportable-model-artifact)
- Select: Raw 导出验证、消歧与去除无关文件
- Needs:
  - 读取、转换或排除原始文件: [DD.user-model-sources-are-readonly](artifact-storage.md#dduser-model-sources-are-readonly)
  - 已有导出物的语义: [DD.semantic-freeze-at-export](model-compatibility.md#ddsemantic-freeze-at-export)
  - 普通无效输入: [DD.local-failure-degradation](failure-isolation.md#ddlocal-failure-degradation)
- Landing:
  - [standard](../../standards/model-schema/assets-and-validation.md#分层验证与发布)
  - [concept](../../concepts/model-compatibility.md#容器语义冻结)

## DD.export-preprocesses-assets

- Claim: Raw 导出在形成模型制品之前完成资产预处理。
- Rationale: 错误、歧义和无关文件越晚发现，越容易在所有使用者处重复付出代价。提前校验、排除无关内容并适当压缩资产，可减小制品与分发成本，也使冻结的内容成为明确的运行输入。

### BC.export-validates-declared-assets

- Claim: 导出检查资产、引用与模型定义的正确性，并消除受支持规则能唯一消除的歧义。无法唯一解释的内容不得被当作有效模型静默导出。

### BC.export-removes-unrelated-cost

- Claim: Raw 内与模型无关的文件不进入制品，不能让每次模型分享和使用都为无关内容付费。
