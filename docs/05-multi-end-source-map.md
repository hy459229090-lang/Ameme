# Ameme 多端信息源地图 v0.2

更新日期：2026-07-13\
状态：官方能力矩阵已补充；Windows 文件事件和 Chrome activeTab 已实测，其余需要设备和样本验证

## 1. 结论摘要

1. Desktop 可以承载持续采集，但录屏和读取界面内容都应视为显式高权限能力。
2. iOS 最自然的路径是 Journaling Suggestions、照片选择器、分享菜单、HealthKit 分项授权和用户主动补充，而非后台读取其他 App。
3. Android 比 iOS 开放，但后台服务、广播和跨 App 数据仍受到系统限制；稳定产品应优先使用系统 Picker、Share Intent、Health Connect/官方 API 和可见前台服务。
4. 企业 IM 通常需要应用安装、OAuth scope、敏感权限或管理员同意，不等同于个人用户授权后即可读取所有聊天。
5. 照片、截图、音频、PDF、账单和页面都可以接入；结构化 JSON 只是其中一种输入形式。
6. Chrome `activeTab` 已证明一键 Capture 可以不声明全站 host permissions；用户触发前扩展看不到当前页 URL/标题，跨 origin 后旧授权撤回。
7. Limitless/Rewind 的收购、区域退出和捕获停用说明全屏/全音频持续采集具有持续经营风险，不进入首版默认路径。
8. 端优先级先看独特事件覆盖增益，再看用户规模：Native Mobile 获取现实生活与手机系统数据，MCP/Skill/API 获取 Agent 工作流上下文；Web/小程序/插件扩大触达和补充特定来源。独立 Native Desktop 暂不作为首版前提。

## 2. 可行性标记

- A：官方能力明确，适合进入早期实测。
- B：官方能力存在，但需要高权限、组织审批或明显交互。
- C：主要依赖用户导入、截图/OCR 或不稳定页面解析。
- D：没有合适的官方个人接口，或不应在当前阶段接入。

## 3. Desktop 信息源

| 来源 | 原始形态 | 获取方法 | 标记 | 推荐默认策略 | 主要限制 |
|---|---|---|---|---|---|
| 前台应用/窗口 | 应用名、标题、时间 | Win32/UI Automation；macOS Accessibility/窗口信息 | A/B | 仅保存元数据，支持应用排除 | 标题可能含敏感内容；应用兼容性不一 |
| 界面文本 | UI 树、文本块 | Windows UI Automation；macOS Accessibility；OCR 降级 | B | 指定应用授权，端侧抽取 | 并非所有应用提供完整可访问树 |
| 屏幕画面 | 图片/视频帧 | Windows Graphics Capture；macOS ScreenCaptureKit | B | 默认关闭；用户选择窗口/显示器；明显采集状态 | 权限高、存储大、可能捕获他人和密码 |
| 文件变更 | 路径、事件、文件内容 | FileSystemWatcher/USN；macOS FSEvents；用户选定目录 | A | 默认只监听选定目录和元数据，内容按需解析 | Windows 合成 create/change/rename/delete 已连续复跑；真实目录仍有噪声、公司政策和二进制限制 |
| 浏览器活动 | URL、标题、DOM、选中内容、截图 | Browser Extension + 当前页面授权 | A | 首先使用 `activeTab` 一键模式；持续模式另行授权 | 合成页低权限闭环已实测；PDF、内置页、登录页、商店发布仍待验证 |
| Git/终端 | commit、diff、命令、路径、输出 | Git hooks/CLI；Shell integration | A/B | Git 事实可自动；命令正文默认谨慎 | 命令和输出可能含密钥，终端兼容复杂 |
| 日历 | 结构化事件、参与人 | Microsoft Graph、Google Calendar、CalDAV/EventKit | A/B | OAuth 分项授权 | 企业管理员、参与人隐私 |
| 会议音频 | 音频、转录、说话人 | 系统音频/麦克风采集或会议平台 API | B | 单次会议显式开启和录音提示 | 法律/同意、说话人识别、资源消耗 |
| 本地文档 | DOCX/PDF/PPTX/XLSX/图片等 | 用户目录、文件打开/修改事件、解析器 | A | 原文件留本地，派生摘要按空间策略 | 版权、二进制解析、版本和删除传播 |

官方证据：

- [Windows Graphics Capture](https://learn.microsoft.com/en-us/windows/apps/develop/media-authoring-processing/screen-capture)
- [Windows UI Automation](https://learn.microsoft.com/en-us/uwp/api/windows.ui.uiautomation)
- [macOS ScreenCaptureKit](https://developer.apple.com/documentation/screencapturekit/capturing-screen-content-in-macos)
- [macOS File System Events](https://developer.apple.com/documentation/coreservices/file_system_events)

## 4. Mobile 信息源

| 来源 | 原始形态 | iOS 路径 | Android 路径 | 标记 | 推荐默认策略 |
|---|---|---|---|---|---|
| 生活事件建议 | 组合事件、照片、地点、活动等 | Journaling Suggestions，用户选择后才传给 App | 无完全等价统一能力，需要分来源组合 | A(iOS)/C(Android) | 用户点选后接入，不后台猜测全部生活 |
| 照片/视频 | 图片、视频、时间和有限元数据 | Photos Picker/受限照片权限 | Photo Picker/Storage Access Framework | A | 用户选择或指定相册；原图同步单独开关 |
| 分享内容 | 文本、URL、图片、文件 | Share Extension/Shortcuts | Android Sharesheet/Intent | A | 一键 Capture 的主入口 |
| 健康/运动 | 步数、运动、睡眠等 | HealthKit 分类型授权 | Health Connect；旧 Google Fit 路线需关注迁移 | A/B | 独立 Health 空间，不跨用途默认分析 |
| 地点/移动 | 坐标、访问、活动区间 | Core Location 分级授权 | Location APIs 分级授权 | B | 默认保存事件级地点，不长期保存原始轨迹 |
| 截图/账单 | 图片、PDF、CSV | 分享/文件选择/OCR | 分享/文件选择/OCR | A/C | 用户主动导入，先本地分类和脱敏 |
| 通知 | 通知文本/应用 | iOS 第三方无法通用读取其他 App 通知 | Notification Listener 需高敏权限 | D(iOS)/B(Android) | 不作为跨平台核心来源 |
| 后台跨 App 活动 | 应用使用行为 | 高度受限 | UsageStats/Accessibility 等高敏权限且政策风险高 | D/B | 不作为首版默认能力 |
| 语音补充 | 音频、转录 | App 内录音/系统快捷方式 | App 内录音/系统分享 | A | 明显录音状态，短音频优先 |

官方证据：

- [Apple Journaling Suggestions](https://developer.apple.com/documentation/journalingsuggestions)
- [Apple Photos Picker](https://developer.apple.com/documentation/PhotoKit/selecting-photos-and-videos-in-ios)
- [Apple HealthKit](https://developer.apple.com/documentation/healthkit)
- [Android 后台执行限制](https://developer.android.com/about/versions/oreo/background.html)
- [Android 常用 Intent 和分享/文件选择](https://developer.android.com/guide/components/intents-common)

## 5. Communication 信息源

| 来源 | 原始形态 | 官方获取路径 | 标记 | 产品判断 |
|---|---|---|---|---|
| Slack | JSON 消息、线程、文件、事件 | Slack App + OAuth scopes + Events/Web API | B | 只接入授权用户可见范围；需单独核验 AI、存储和数据保留政策 |
| Microsoft Teams | ChatMessage、频道、会议等 | Microsoft Graph delegated/application/resource-specific permissions | B | 很多消息权限需要管理员同意，企业版路线与个人版路线分开 |
| 飞书 | JSON 消息、群、文档、日历、会议 | 自建/商店应用、机器人、OpenAPI、事件订阅、敏感权限 | B | Bot 收到的消息与“读取用户全部历史”不是一回事；群全量消息是敏感权限 |
| 企业微信 | 消息/会话存档 | 企业应用与会话内容存档能力 | B/D | 面向企业合规场景，不应假设个人用户可以直接授权全部历史 |
| 微信个人聊天 | 本地专有数据、导出/截图 | 缺乏适合普通第三方产品的官方全量读取接口 | D/C | 只考虑用户主动转发、截图或官方导出；不绕过客户端保护 |
| Email | MIME、正文、附件、线程 | Gmail API、Microsoft Graph Mail、IMAP/OAuth | A/B | 用户授权可行，但内容高敏；优先让用户选择邮箱/标签/时间范围 |

官方证据：

- [Slack Events API 与 OAuth 权限模型](https://api.slack.com/events-api)
- [Slack App Developer Policy](https://docs.slack.dev/developer-policy/)
- [Microsoft Graph 权限参考](https://learn.microsoft.com/en-us/graph/permissions-reference)
- [飞书接收消息事件与权限](https://open.feishu.cn/document/server-docs/im-v1/message/events/receive?lang=zh-CN)

## 6. Life services 信息源

| 来源 | 原始形态 | 获取方法 | 标记 | 产品判断 |
|---|---|---|---|---|
| Steam | 游戏、游玩时间、成就、统计 | Steam Web API，受可见性和 key/应用权限影响 | A/B | 适合验证“游戏经历”来源，但不能覆盖所有游戏平台 |
| 主机/手游 | API、个人主页、截图、战绩页 | 官方 API（若有）、用户绑定、分享截图/OCR | B/C | 按游戏逐个 Connector，不设计一个万能游戏接口 |
| 支付/账单 | CSV、PDF、邮件、截图、App 页面 | 官方个人导出、邮件、文件导入、截图/OCR | C | 财务空间独立；不使用非官方抓取登录态 |
| 音乐/播客 | 播放历史、收藏 | 平台 OAuth/API、系统建议、用户分享 | A/B | 可形成情境线索，原始播放流水无需永久保存 |
| 地图/出行 | 行程、地点、票据 | 平台导出/API、邮件、日历、截图 | B/C | 事件级摘要优先，原始轨迹默认短期 |
| 可穿戴设备 | 健康/运动样本 | HealthKit、Health Connect、厂商 API | A/B | 通过系统健康仓库优先，减少逐厂商接入 |

官方证据：

- [Steam Web API Overview](https://partner.steamgames.com/doc/webapi_overview)
- [Steam IPlayerService](https://partner.steamgames.com/doc/webapi/iplayerservice)

## 7. 非结构化输入的统一处理入口

所有来源先进入内容识别，而不是要求 Connector 输出统一业务字段：

| 输入 | 首步处理 | 可能输出 |
|---|---|---|
| 截图/照片 | EXIF + OCR + 视觉分类 + 敏感区域检测 | 账单、地点、人物候选、游戏战绩、文档内容 |
| 音频 | 语音活动检测 + 转写 + 说话人分段 | 对话、会议、用户主动表达 |
| 网页 | URL/DOM/正文抽取；失败时截图 OCR | 阅读、搜索、交易、游戏或工作事件 |
| 文件 | MIME/扩展名识别 + 对应解析器 | 文档修改、项目产出、账单或素材 |
| JSON/API | Schema 识别 + Connector 映射 | 高置信 Event/Entity |
| 文本/聊天 | 来源和会话边界 + 内容分类 | 决定、承诺、关系、主题候选 |
| 视频 | 元数据 + 关键帧 + 可选音轨转写 | 事件片段和媒体回忆 |

处理失败时保留：原始对象引用、来源、时间、敏感度、失败原因和重试策略；不强行生成事件。

## 8. 每个信息源必须进入同一个 DayLedger，并配一个补漏入口

低权限采集和事件覆盖不是互相替代关系。各端只获取适合自己的证据，统一形成 EventCandidate/DayLedger，再用自然入口补足该端难以获取的事件：

| 端 | 默认低权限获取 | 同端反馈入口 | 主要补足类型 |
|---|---|---|---|
| Desktop | 选定目录/仓库元数据、前台应用、主动 Capture | 每日 Inbox、全局快捷键“漏了这个” | 决策理由、项目状态、方法、阻塞 |
| Browser | `activeTab` 用户触发后的当前页 | 保存时补“为什么重要/学到了什么” | 发现、认知变化、项目关联 |
| iOS/Android | Share、Picker、系统分项授权 | 通知卡片、语音补一句、每日遗漏 | 生活时刻、人物、情绪、意义 |
| IM/会议 | 转发、@Bot、授权会议/频道 | 决定/承诺卡确认责任人和期限 | 决策、承诺、关系上下文 |
| MCP/Skill/Agent | 按 purpose 请求最小 ContextPack | 任务后有用/缺失/过时/越权反馈 | 使用价值、新结论；exact Grant 内写 Event/Revision，推断保留 inferred 状态 |

详细机制见 `product/记忆反馈与类型覆盖框架.md`。首版不以“后台全量读取”换取类型覆盖，而以反馈逐渐学习个人规则。

## 9. 需要实测后才能定案的事项

1. macOS 在常用浏览器、Office、IDE、终端和 IM 中的可访问文本覆盖率；Windows UIA 仍需扩大真实样本。
2. 连续采集对 CPU、电池、磁盘和用户信任的真实影响。
3. iOS Journaling Suggestions 能提供的事件类型、地区和设备覆盖。
4. Android 厂商后台限制对通知、位置和定时处理的影响。
5. Slack、Teams、飞书等平台对长期存储及 AI 处理消息数据的最新政策。
6. 微信、支付宝等个人账单导出的稳定格式和用户操作成本。
7. Steam 之外的游戏服务是否存在面向个人可用的稳定官方接口。

这些事项在完成代码或设备验证前均不得写入 MVP 的“已可用能力”。

逐项证据、权限和 Prototype 判定见 `research/R1官方能力证据矩阵_20260713.md`；Chrome 实测见 `../research/browser/active-tab-spike-report.md`。
