# 未决问题

本页维护 AP 子系统尚未闭合的判断。产品选择以[产品决策树](../../product-decisions/README.md)为 Root Authority，实现事实不自动产生例外。

## Q-04

**用途驱动图片策略的效果与成本证据未闭合。** [图片产品边界](../../product-decisions/decisions/image-policy.md)要求压缩保持整体视觉效果，固定处理顺序由[转换与导出](../../architecture/asset-pipeline/conversion-and-export.md#图像处理的位置)拥有。当前 raw 路径只让 PNG 进入用途策略，合法 JPEG/WebP/AVIF 等既有压缩表示直接复用；PNG 编码成功即采用结果，即使产物变大，仅可处理的 codec I/O 失败才回退原图。Legacy 路径在 decoder 读出 RGBA32/PNG 后立即按角色编码，编码或 round-trip 失败使该 source 导入失败，其他合法压缩表示直接复用。这些行为已与架构对齐，但尚未证明阈值、codec 与两条失败策略满足产品目标。

不再将编码顺序本身视为产品争议。后续调整可以比较复用、缩放、峰值内存与重编码开销，但不能仅以体积缩小证明整体视觉保真，也不能由「成功编码」推导产物成本已改善。需要一组最小验证矩阵：JPEG、细长小 PNG、RGBA32、不同用途尺寸和 Android 策略，检查分辨率、色彩/透明边界、整体视觉、产物大小与峰值内存；同时分别覆盖 raw 的失败回退和 legacy 的 source-local failure。对应机制为 AP-03 与 NR-17。

## 其他证据缺口

AP-05 的真实 3D 选模收益、AP-08 的 offscreen 画面与冷热成本、真实页面反复翻页和 texture 回收仍未验证。这些缺口限制收益与可靠性结论，不将现存机制自动降为 Historical。
