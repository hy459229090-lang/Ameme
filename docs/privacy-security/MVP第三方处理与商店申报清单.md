# Ameme MVP 第三方处理与商店申报清单 v0.2

> 文档状态：已接受的发布门禁；release allow-list 当前为空，任何依赖入包前必须逐项批准\
> 更新日期：2026-07-14\
> 原则：供应商/SDK 未选择不是“无需审查”，而是 release build 不得包含相应第三方处理。

## 1. 第三方登记模板与当前状态

| 类别 | 候选/状态 | 可能数据 | 必须证据 | 当前门禁 |
|---|---|---|---|---|
| 账户/身份 | 未选择 | email/用户 ID/设备会话 | MFA、区域、日志、删除、接管响应 | blocked for release |
| LAN Peer Sync | 无第三方数据处理者；使用系统网络能力 | 已授权结构化 Event、可选 selected Raw | 设备认证、加密会话、发现隐私、权限与删除传播 | blocked for P4 真机证据 |
| 数据云存储 | MVP 不采用 | 无 | 构建/网络抓包证明无记忆内容数据平面 | future decision only |
| 模型/OCR/STT | 未选择 | 获准最小内容 | 不训练/保留、区域、子处理者、删除、价格、Eval | blocked for cloud model |
| Crash/性能 | 未选择；优先自建/平台聚合 | stack、设备/版本、trace | 无正文配置、采样、保留、网络抓包 | 第三方 SDK 默认禁止 |
| 产品分析 | 未选择；MVP 可无 SDK | 非内容事件 | 独立匿名 ID、禁采字典、可关闭、删除 | 第三方 SDK 默认禁止 |
| Push | Apple/Google 系统服务候选 | push token、无正文通知 | token 生命周期、禁敏感 payload | P2 真机 |
| 邮件/通知 | 未选择 | email、模板、状态 | 无内容邮件、退订/安全通知、区域 | 非核心可后置 |
| 地图/反地理编码 | 未选择；MVP 优先系统/本地标签 | 位置 | query 是否留存、精度最小化、缓存/删除 | 默认不接第三方 |
| 支持/客服 | 未选择 | 用户主动支持包 | 用户预览、临时访问、审计、删除 | 无内容后台 |

每个实际条目必须补：legal entity、服务/SDK/version、数据/目的/角色、处理区域/传输、保留/备份、训练/广告、子处理者、访问控制、删除、事故通知、DPA/条款链接、退出/迁移、商店披露和负责人。网络抓包与构建 SBOM 必须能证明清单完整。

## 2. iOS 申报准备

根据最终实现逐项核对：

| Apple 类别/能力 | Ameme 候选 | 需要确认 |
|---|---|---|
| User Content | 文字、照片、音频、日历/Agent 产物 | 是否离开设备、是否 linked、purpose |
| Location | 近似/精确、机会式后台 | 前台/后台、精度、实际上传字段 |
| Health & Fitness | 活动/锻炼/睡眠等 | 首个公开版本必备；是否仅设备处理、HealthKit 资格与真实读取语义 |
| Identifiers | 账户/随机设备/遥测 ID | 不使用广告 ID；遥测身份分离 |
| Diagnostics/Usage | crash/performance/product events | 第三方 SDK、内容禁采、保留 |
| Sensitive Info/Other | 事件动态敏感内容 | 以最保守的实际处理申报 |

Release CI/人工流程需要：生成 archive privacy report、校验 `PrivacyInfo.xcprivacy`、核对 required-reason API/第三方 manifest、App Store Connect App Privacy 和隐私政策一致。Apple 官方说明 privacy manifest 需要列出采集类型/用途，Xcode 可聚合第三方 SDK 生成报告。[Privacy manifest files](https://developer.apple.com/documentation/bundleresources/privacy-manifest-files)、[App Store Connect privacy](https://developer.apple.com/help/app-store-connect/manage-app-information/manage-app-privacy)

## 3. Android / Google Play 申报准备

| Data safety 类别/权限 | Ameme 候选 | 需要确认 |
|---|---|---|
| Location approximate/precise | A0–A3 机会式位置 | collected/shared/ephemeral、后台声明和用途 |
| Health and fitness | Health Connect/活动/睡眠 | 首个公开版本必备；Health apps declaration、明确同意与真实读取语义 |
| Photos/videos/audio/files | Picker、录音、分享 | 用户选择范围、云处理/Raw sync |
| Calendar | 选定日历 | 权限/范围/用途、是否同步 |
| User IDs/device IDs | 账户/随机设备 ID | 不读取持久硬件 ID/广告 ID |
| App activity/diagnostics | 非内容埋点/性能 | 第三方 SDK、删除、可关闭 |

Data safety 必须包含 App 和第三方代码的实际行为；Google Play 说明闭测/公开/生产轨道通常也需要准确表单与隐私政策。[Data safety](https://support.google.com/googleplay/android-developer/answer/10787469)

## 4. 构建门禁

1. Dependency/SBOM 与本清单比对；未登记网络域名/SDK 构建失败。
2. Release 网络抓包覆盖启动、授权拒绝、补充、同步、模型、删除、注销；未解释请求阻断。
3. Privacy manifest/Data safety/隐私政策/产品内说明由同一数据清单生成或人工差分。
4. SDK 初始化必须在对应同意后；分析/Crash SDK 不得先于用户选择采集内容元数据。
5. 健康与位置只在对应用户同意后加载/访问系统 API；健康五类是首个公开版本发布条件，证据不足时阻断发布，不能静默关闭后继续发布。
6. 供应商条款/SDK 更新触发隐私、安全和商店差分 Review，不能只做版本升级。
