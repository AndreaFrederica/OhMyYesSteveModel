# catalog-publication

- Requirement: [REQ.authoritative-model-lifecycle](../requirements/req-authoritative-model-lifecycle.md#reqauthoritative-model-lifecycle)
- Select: 目录与内容完整发布、热加载与已有使用
- Needs:
  - 判定精确内容与本地替代: [DD.model-and-representation-identities](model-identity.md#ddmodel-and-representation-identities)
  - 扫描或隔离本地来源: [DD.user-model-sources-are-readonly](artifact-storage.md#dduser-model-sources-are-readonly)
  - 候选构造失败: [DD.local-failure-degradation](failure-isolation.md#ddlocal-failure-degradation)
- Landing:
  - [architecture](../../architecture/model-management/reload-and-publication.md)
  - [architecture](../../architecture/model-management/ownership-and-lifecycle.md)
  - [architecture](../../architecture/model-management/storage-and-cache.md)

## DD.single-current-catalog

- Claim: 可用模型由完整验证的目录项及其一致索引定义。扫描结果逐项进入不可变当前目录，并保持已有使用有效。
- Rationale: 浏览、选择和实际使用必须对每个当前条目得到一致答案。无需让一个坏模型或长扫描阻挡其他已验证内容可用；但半份条目、混合表示和失败写入仍不能成为有效版本。扫描只有在完整观察根目录后才可确认删除，根目录访问失败不能被解释为模型全部消失。热加载是日常操作，目录变化也不应撤销已经取得并验证的资源。

### BC.catalog-defines-current-content

- Claim: 热加载与来源切换只把完成验证的单项结果，合入一次构造完整的不可变目录与索引。有效项可以在同次扫描结束前逐批发布。坏新项不替换当前有效项。扫描无法完整观察某个根时，不据此删除该根中未确认缺失的旧项。单次扫描内，同一路径或 `ModelId` 冲突由首个完成验证并被 owner 接纳的候选占用，目录和所有反向索引必须引用同一候选。省略或替换只改变当前可见目录；处理原始输入仍服从本包引用的来源保护决策。

### BC.retired-content-remains-valid

- Claim: 模型退出当前目录后，或它的只读来源随后被替换、删除、损坏或暂时不可访问后，已经取得并验证的资源仍可安全完成现有使用。未来需要重新读取该来源的局部操作可以失败。一个使用者结束使用，不能使其他使用者的模型资源失效。

### BC.cache-publishes-only-verified-exact-content

- Claim: 存储只发布验证通过、且属于所需表示的完整结果。中断或失败不得把半写入内容伪装成有效模型，也不得破坏已有有效版本。
