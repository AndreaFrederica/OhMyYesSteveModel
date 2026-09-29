# artifact-storage

- Requirement: [REQ.portable-model-artifact](../requirements/req-portable-model-artifact.md#reqportable-model-artifact)
- Select: 用户来源只读、独立预览与缓存处置边界
- Needs:
  - 判断精确身份与本地例外: [DD.model-and-representation-identities](model-identity.md#ddmodel-and-representation-identities)
  - 兼容旧输入: [DD.semantic-freeze-at-export](model-compatibility.md#ddsemantic-freeze-at-export)
  - 失败写入: [DD.local-failure-degradation](failure-isolation.md#ddlocal-failure-degradation)
- Landing:
  - [architecture](../../architecture/model-management/storage-and-cache.md)
  - [architecture](../../architecture/model-management/catalog-and-sources.md)
  - [architecture](../../architecture/model-management/failure-and-recovery.md)
  - [architecture](../../architecture/asset-pipeline/conversion-and-export.md)
  - [standard](../../standards/asset-container.md#stored-representationlogical-content-与-hash)

## DD.user-model-sources-are-readonly

- Claim: Local catalog 的用户来源目录与原始文件是只读输入。模型管理只能修改或删除自己管理的缓存。
- Rationale: 用户来源可能是作品的唯一原件。内容错误或暂时不可访问，都不赋予模组处置它的权利；缓存则是可重建的派生物。区分输入与缓存，让加载、转换和失败处理不会把局部错误升级为作品丢失。访问失败也不能证明缓存已经损坏。

### BC.local-catalog-input-is-never-mutated

- Claim: 无论正常加载、转换、热加载、内容损坏还是访问失败，YSM 都不得修改、覆盖或删除 local catalog 的用户来源目录与原始文件。更新内存中的模型目录，或转换生成独立产物，都不改变这一输入保护边界。

### BC.cache-maintenance-does-not-own-sources

- Claim: 模型管理的写入、替换、失效清理和淘汰，仅可作用于其管理的 converted、remote、baked、preview 等缓存，不得延伸到用户来源。Remote、精确 converted 和独立 preview 的坏读，只使本次读取 miss 或失败，不删除、移动或隔离当前目标。后续验证通过的同一精确内容或同一 `ContainerId` 图片可以原子覆盖；替换失败必须保留旧目标。可变转换索引、其它已有 cache 及首次 converted prune，继续按各自的独立处置规则工作。不能从这一非破坏性读边界推导统一清理权限。

### BC.generated-preview-is-an-independent-cache

- Claim: 按实际需求或显式 export 生成的 preview 存入独立缓存。正常浏览和运行不得回写或替换来源容器。缓存只按 `ContainerId` 弱关联图片。可解码替换图可以展示并进入后续 export，但图片存在不授予模型使用权，也不放宽模型、内嵌图片、icon 或 pack cover 的既有完整性验证。不同容器图片的长期空间回收，属于该缓存自身的维护责任，不得借普通 target 驻留或 converted prune 隐式处置。
