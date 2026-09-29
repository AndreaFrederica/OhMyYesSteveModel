# selection-and-attribution

- Requirement: [REQ.create-and-select-models](../requirements/req-create-and-select-models.md#reqcreate-and-select-models)
- Select: GUI 模型选择、模型列表与创作者署名
- Needs:
  - 处理模型许可与内容责任: [DD.mod-and-model-licenses-are-independent](content-rights.md#ddmod-and-model-licenses-are-independent)
  - 联机浏览或选择: [DD.selection-permission-is-separate-from-asset-access](model-authorization.md#ddselection-permission-is-separate-from-asset-access)
  - 选择当前模型: [DD.single-current-catalog](catalog-publication.md#ddsingle-current-catalog)
  - 保留模型声明语义: [DD.semantic-freeze-at-export](model-compatibility.md#ddsemantic-freeze-at-export)
  - GUI 工作与失败: [DD.local-failure-degradation](failure-isolation.md#ddlocal-failure-degradation)
- Landing:
  - [standard](../../standards/model-schema/manifest-and-identity.md#info-与展示信息)
  - [architecture](../../architecture/network/catalog-sync.md#selection-与-request-admission)
  - [architecture](../../architecture/client-presentation/README.md)

## DD.visual-model-selection

- Claim: 模型选择是面向普通玩家的 GUI 能力。
- Rationale: 玩家需要理解当前有哪些模型，并主动切换外观。把日常选择交给直观列表和交互，比要求了解文件组织、内部标识或调试命令更符合这一使用场景。

### BC.gui-selects-available-model

- Claim: GUI 展示当前模型列表，并允许玩家发起选择。多人模式的最终可用性和选择结果服从服务端裁决，不以打开列表为由加载全部模型内容。卡片优先展示有效 preview 或已就绪的 3D target。缓存未命中时，连续悬停同一模型超过 0.3 秒才尝试离线取得完整 target。

### BC.gui-demand-follows-current-intent

- Claim: GUI 翻页与每个玩家实体的模型需求，分别按当前连续意图计时。当前缺件且上一页面或选择持续不超过 0.7 秒时，本次必须连续停留超过 0.7 秒，才发送对应页面媒体、selection 或运行资源请求。首次没有前项、前项已持续更久，以及已有有效内容，不增加等待。内部页面重建、异步 miss 或无关目录发布，不重置同一真实意图。快速切换只保留当前尚未发送的效果。Selection 与资源请求可以独立推进，不得彼此等待或共享一份可变状态机。

模型展示、资产取得和自行选择的权限见[分发与选择权限](model-authorization.md#ddselection-permission-is-separate-from-asset-access)。GUI 不得把可浏览或已取得资产的模型，误示为玩家有权选择。

## DD.visible-creator-attribution

- Claim: 模型的来源创作者信息必须随模型向玩家展示，并在模组可影响的处理和呈现范围内保持不被篡改或丢失。
- Rationale: 自定义内容来自独立创作者，模型分享和选择不能切断作品与创作者之间的关联。只有显示入口、却在处理过程中允许署名被覆盖，仍会使作品失去归属。保护已有声明可以复用模型信息的保留与传递，不要求制作组承担作者身份认证或模组外部文件的防篡改保证。

### BC.attribution-travels-with-model

- Claim: 对输入中已有的来源创作者署名，模组控制的导入、转换、加载、导出、分享和 GUI 展示必须保留其归属含义。不得擅自改写、替换、丢弃，也不得让无关设置与内容覆盖。内部表示变化不能成为丢失署名的理由。

### BC.attribution-protection-has-an-input-boundary

- Claim: 署名保护以模组取得的输入声明为边界。不认证声明者身份或作品权属，也不保证阻止外部工具修改文件。输入已缺失或在模组外部被篡改时，不承诺恢复真实署名，不得以猜测作者或改署制作组来补齐信息。
