# Ameme Share Extension target

该目录提供真实 iOS Share Extension target 使用的入口、Info.plist 和 App Group entitlements：

- `ShareViewController.swift` 只把一项文字/URL、图片或 PDF 写入 `group.com.ameme.ios/IncomingShares`；正文不进入 URL。
- 保存完成后只打开 `ameme://incoming-share/<UUID>`，由主 App 再展示“保存到本机/取消分享”确认页。
- 空分享、多项分享、不支持类型、超过 16,384 字符或 32 MB、App Group 不可用和重复生命周期均 fail closed；读取失败提供重试或取消。

`apps/ios/project.yml` 会生成完整 `Ameme.xcodeproj`：Extension 链接 `AmemeShared`、以 `ShareViewController` 为 principal class，并嵌入主 App；App 与 Extension 使用同一个 App Group。静态工程接入不等于签名或设备通过，发布前仍须在带有效 provisioning 的 iOS Simulator/真机上验证 Share Sheet、App Group entitlement、返回主 App 与重复生命周期。
