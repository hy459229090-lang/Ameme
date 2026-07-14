# Ameme Mobile 原生 UI 与流畅性基线 v0.2

> 文档状态：已接受；品牌、平台组件和初始性能预算已冻结，达成情况待真机验证\
> 更新日期：2026-07-14\
> 适用范围：iOS 与 Android Native Mobile MVP\
> 决策：统一产品语义和数据契约，不追求两端像素一致；两端优先使用各自系统原生 UI 框架、控件、手势和可访问性能力。

## 1. 这条原则解决什么

Ameme 的核心体验是快速浏览一天、补充记录、搜索历史和管理授权。MVP 不用一套自定义跨平台皮肤覆盖系统行为，而是把工程和设计精力优先放在：

1. 启动后尽快看到本地 `今天`。
2. 长时间线持续滚动仍然稳定。
3. 搜索输入、键盘、日期选择、返回和弹层符合平台习惯。
4. 保存补充立即反馈，不等待同步、转写或 AI 整理。
5. VoiceOver、TalkBack、动态字体、减少动态效果和系统权限流程默认可用。

## 2. 统一什么，不统一什么

| 必须统一 | 允许并要求平台差异化 |
|---|---|
| `今天`、历史搜索、事件详情、补充、设置的任务语义 | 导航栏、返回方式、搜索框位置与展开行为 |
| Event、DayLedger、RecallQuery、Revision 和状态枚举 | 列表控件、日期选择器、菜单、Sheet、Dialog、Snackbar/Alert |
| 事件层级、日期分组、范围说明和错误含义 | 字体、图标、间距、触控反馈、系统动画和安全区处理 |
| 权限、空间、同步、删除和来源解释 | 权限弹窗、系统设置跳转、照片/文件选择器 |
| 核心任务完成路径和埋点语义 | iOS 与 Android 的组件树、页面转场和平台适配代码 |

现有低保真是共同语义图，不是跨平台像素稿。它确认信息层级、入口、状态和动作，但不能要求两个客户端照图自绘相同控件。

## 3. 平台原生组件映射

| 场景 | iOS 原生方向 | Android 原生方向 | 共同约束 |
|---|---|---|---|
| 页面壳与返回 | SwiftUI `NavigationStack`、系统 Toolbar、系统返回手势 | Compose `Scaffold` / Top App Bar、Navigation Compose、预测性返回 | 返回保留查询、草稿和滚动状态 |
| 历史搜索 | SwiftUI `.searchable` 与系统搜索放置规则 | Material 3 Search 组件与系统 IME | 同一 `RecallQuery`，范围始终可见 |
| 事件日流 | `List` 或具备原生滚动/可访问性的惰性列表 | `LazyColumn` 与 Material 列表语义 | 按日分组、增量分页、不为每项造重卡片 |
| 日期/范围 | 原生 Sheet/Popover + 系统日期或日历选择能力 | Material 3 Date Picker / Date Range Picker | 只改变同一日流的锚点或范围 |
| 补充记录 | 安全区单一悬浮 Button + 原生 Sheet | 单一 Material FAB + `ModalBottomSheet` | 一个悬浮入口展开四种方式，不设常驻输入框/Tab Bar |
| 设置入口 | Toolbar 菜单后 push 或 sheet；不使用与返回手势冲突的边缘抽屉 | Top App Bar 菜单；需要时使用 `ModalNavigationDrawer` | 设置不占主导航，按钮始终可达 |
| 反馈与确认 | Inline status、Progress、Alert、`confirmationDialog`、Sheet | Inline status、Progress、Snackbar、AlertDialog、Bottom Sheet | 失败说明已有内容是否安全和下一动作 |
| 图标与字体 | SF Symbols、系统字体、语义颜色、Dynamic Type | Material Icons、Material 3 typography/颜色、系统字体缩放 | 品牌只做有限语义强调，不替换系统可访问性 |
| 照片/文件/权限 | Photos Picker、Share Sheet、文件选择器与系统权限 | Photo Picker、Sharesheet、系统文件选择器与权限 | 默认只读取用户选定对象，拒绝后主闭环仍可用 |

### 3.1 不建立跨平台自定义 UI 壳

- MVP 不使用 Flutter、React Native 或 WebView 作为两端主要 UI 渲染层。
- 共享 Schema、命令、查询和部分领域逻辑仍可评估，但共享代码不能拥有平台导航、控件渲染、系统权限弹窗或返回手势。
- 只有事件时间线等核心产品表达确实无法由系统组件满足时，才增加轻量自定义布局；仍需保留原生滚动、焦点、字体缩放和辅助功能语义。

## 4. 品牌与视觉约束

Ameme MVP 的品牌关键词是：简洁、高效、安静、可信、流畅。ChatGPT Mobile 只作为交互品质参考：一屏聚焦一个主要任务、次级能力渐进展开、输入即时响应；不复制其标志、配色、排版、图标或具体界面。

- 使用系统字体、SF Symbols/Material Icons、平台原生语义色和系统圆角/阴影层级。
- 浅色主强调色为 `Memory Teal #0D6B5B`；深色对应色为 `#72D6B5`。强调色只用于主动作、选中和关键状态，不铺满大面积背景。
- 页面背景与内容层级优先使用平台系统背景/分组背景；事件不默认包进厚重卡片。
- 动效只解释状态变化：保存、展开、录音、局部插入和同步；遵循系统 Reduce Motion，禁止装饰性长动画。
- 品牌差异主要来自信息组织、事件语义、可信状态和单悬浮记录入口，不靠重度自绘组件。

## 5. 流畅性产品约束

1. **本地首帧**：`今天`、已缓存 DayLedger、来源状态和最近搜索状态从本地读取；网络、同步、向量搜索和 LLM 不得阻塞首个可用页面。
2. **增量加载**：事件日流、历史日流和媒体缩略使用惰性渲染、分页和稳定 ID；索引更新时保留已存在结果。
3. **即时保存反馈**：语音、文字、照片或分享提交后先保存 SourceObject/UserAddendum 并显示“已保存”，转写、归并和同步异步完成。
4. **主线程克制**：OCR、转写、缩略、索引、Summary 重算和同步不在 UI 主线程执行；局部状态更新不重建整条日流。
5. **系统输入与手势**：键盘、返回、滚动回弹、焦点、Sheet、触觉和减少动态效果由平台能力承担，不自造相似行为。
6. **状态恢复**：从详情或系统选择器返回时恢复原日期、查询、滚动位置和草稿；进程被回收后至少能回到安全的本地状态。
7. **电量优先**：后台位置、同步和模型任务按系统调度及机会式信号工作；UI 流畅不能用持续后台运行换取。

## 6. 初始体验预算与 Gate

Prototype 必须在双端真机记录以下指标。以下数值是研发前体验预算，用于发现架构不成立和触发降级；没有真机报告前不得写成“已达成”：

| 域 | 必测指标 |
|---|---|
| 启动 | 冷启动到 `今天` 可用、热启动恢复、首个可滚动帧 |
| 渲染 | 页面慢帧/卡顿、主线程长任务、长日流内存、媒体缩略抖动 |
| 输入 | 搜索输入响应、键盘出现/收起、补充入口展开、保存确认延迟 |
| 查询 | 本地首屏结果、翻页、日历跳转、索引更新期间可用性 |
| 稳定 | Crash-free、iOS hang、Android ANR、进程回收恢复 |
| 资源 | 前后台 CPU、内存、网络、存储增长和电量影响 |

| 预算项 | MVP 初始门槛 |
|---|---:|
| 触控到可见反馈 | `<=100ms` |
| 冷启动到本地 `今天` 可用 | `<=1200ms` |
| 热启动恢复 | `<=500ms` |
| 主动补充本地保存确认 | `<=300ms` |
| 本地搜索首屏 | `<=700ms` |
| 关键旅程慢帧占比 | `<5%` |
| Crash-free session | `>=99.5%` |
| 已确认保存的数据丢失 | `0` |

验收不能只看平均值；至少区分冷/热启动、低端/主流设备、数据稀疏/长日流、本机/部分同步和离线状态。

## 7. 对当前低保真的解释

1. `今天`、右上搜索入口、统一历史日流、待核验、小结和补充语义继续有效。
2. 低保真中的圆形“补充记录”成为正式语义：两端均保留一个悬浮记录按钮，点击后由原生 Sheet 展开文字、语音、照片和导入。
3. “侧滑设置”降为共同的二级设置语义：iOS 使用 Toolbar 入口后 push/sheet；Android 可使用 Drawer，但不能把边缘手势作为唯一入口。
4. 搜索、日期范围、加载、空白和部分失败状态分别使用两端原生组件表达，不要求像素一致。
5. 后续视觉探索只处理品牌强调、媒体语言和少量产品特征，不再重新发明基础控件。

## 8. 官方实现依据

- Apple Human Interface Guidelines：搜索应有清晰入口和范围，并优先使用系统提供的搜索体验。\
  <https://developer.apple.com/design/human-interface-guidelines/searching>
- SwiftUI 通过 `NavigationStack` 与 `.searchable` 提供平台自适应导航和搜索。\
  <https://developer.apple.com/documentation/swiftui/understanding-the-navigation-stack>\
  <https://developer.apple.com/documentation/swiftui/adding-a-search-interface-to-your-app>
- Android Jetpack Compose Material 3 提供 Search、App Bar、List、Date Picker、Bottom Sheet 等原生组件及自适应布局。\
  <https://developer.android.com/develop/ui/compose/components>\
  <https://developer.android.com/develop/ui/compose/designsystems/material3>
