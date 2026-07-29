# iOS 视觉实现 QA（更新于 2026-07-29）

- Verdict：历史 Today 基线 `pass`；2026-07-29 新 Search 复用 UI 为 `conditional_pass`，等待本轮完整 Xcode/XCUITest/截图重新签收。物理 iPhone/iPad 与真实 VoiceOver 保持 `hold`。
- 设计基线：用户选定的第一版方向，再做减法；内容优先，Liquid Glass 只用于导航与主操作控制层。
- 运行基线：Xcode 26.6、iOS 26.5、iPhone 17 Pro Simulator；iOS 18–25 使用系统 Material 降级。
- 对照输入：`outputs/ameme-mobile-audit-20260726/design-qa/ios-reference-vs-final-profiles-30193059096.png`；最终运行附件以 GitHub iOS workflow run `30193059096` 的 `ameme-ios-test-evidence` 为准。
- 本轮变更：Search 进一步按第一版方向减法，只保留一个扁平“把记忆用起来”入口，随后使用原生 `confirmationDialog` 与结果 Sheet；本机只有 Command Line Tools，不能沿用历史运行附件冒充新 UI 已渲染通过。

## 结论

实现保留参考方向的暖白画布、宽松留白、单列时间流、一个顶部搜索/设置玻璃控制面和一个右下记录控制；没有把正文列表、状态或总结全部玻璃化。Today 的信息密度比参考图更高，是因为实现必须同时证明 4 种事实状态、来源说明与固定 Mock 边界，但视觉强调仍只落在导航控制和主操作。

默认字号、浅色模式下，内容顺序、边距、字重、分隔和主操作无 P0/P1 问题。深色 XXXL 对照首次发现搜索/设置 SF Symbols 随正文比例放大后互相挤压；最终运行截图确认装饰图标保持适合 44 pt 触控目标的视觉尺寸，而可朗读标签和正文继续随 Dynamic Type 放大。演示提示改为单个可换行文本，避免大字号时三个碎片分别换行。

本轮 Search 没有增加装饰性玻璃卡、四按钮横向条或新的视觉 token。Liquid Glass 继续只属于 iOS 26 的导航/主操作控制面；复用入口是平面内容行，意图选择与结果承载由系统弹层负责。源码、Shared/App 包级 build、生产 Smoke、目标 parse 和静态无障碍契约已通过；只有完整 Xcode 新运行产出后，才能把本轮 `conditional_pass` 升级为 Simulator 工程 `pass`。

## 强制对照

| Surface | 结果 | 证据与判断 |
|---|---|---|
| 字体与层级 | pass | 系统字体；“今天”→日期/数量→事件标题/详情→状态层级清楚，正文支持 Dynamic Type |
| 间距与布局 | pass | 20 pt 主边距、平面事件分隔、总结默认折叠；内容未被玻璃容器化 |
| Viewport resilience | pass | 默认、深色 XXXL、竖屏和横屏 XCUITest；大字号事件切换纵向结构并可滚动 |
| 颜色与状态 | pass | 暖白/系统深色画布，teal 为主操作与已记录状态，待核验保留独立状态文字；不只依赖颜色 |
| Glass / surfaces | pass | iOS 26 使用原生 `glassEffect` 与 `.glassProminent`；iOS 18–25 使用系统 Material fallback；无手绘玻璃 |
| 图标 | pass | SF Symbols；搜索/设置视觉尺寸不再随 XXXL 放大，但各自保留 44×44 pt 触控和独立辅助功能标签 |
| Copy | pass | “演示数据 · 不写入本机”简短可见，完整隔离说明由辅助功能标签和设置页承载 |
| 状态与交互 | conditional_pass | 历史 Today/Search/Settings/Event/Capture/Summary XCUITest 通过；新复用入口、系统选择器、结果 Sheet、Event 跳转与反馈已写入本轮 UI Test，等待 CI 执行 |
| 无障碍 | conditional_pass | 历史 XXXL/深色/旋转自动化与本轮 33 项静态契约通过；新弹层的 VoiceOver 焦点仍需完整 Xcode/真机 |
| AI shortcut artifacts | pass | 无 emoji、假 SF Symbol、手绘 SVG、装饰 blob、无功能圆角卡片或自定义玻璃材质 |

## 已关闭发现

1. `P1 / visual hierarchy`：旧版大量 Material 卡片与胶囊状态使界面像历史 iOS；当前只让控制层使用 Liquid Glass，事件回到平面内容。
2. `P1 / responsiveness`：深色 XXXL 下搜索/设置图标曾重叠；图标视觉尺寸改为固定 18 pt，触控目标和语义不缩小。
3. `P1 / test contract`：横屏后再次查找远端固定 LazyVStack 行会受虚拟化影响；测试先在竖屏证明最后事件可达，横屏验证可见事件结果与设置操作，不把实现细节当用户闭环。
4. `P2 / content density`：演示提示与总结占据过多首屏空间；提示压缩为一行，总结默认折叠。
5. `P2 / status density`：视觉状态标签缩短为“已记录 / 计划 / 待核验”，辅助功能仍朗读完整事实状态。
6. `P1 / reuse density`：四种复用意图不再并列堆在 Search 内容区，只保留一个平面入口，再使用系统 `confirmationDialog` 渐进展开。

## 发布保留门

- 至少一台 iPhone 与一台 iPad 物理设备执行深浅色、默认/XXXL、横竖屏、低电量/离线、照片/语音/分享、权限撤销和进程恢复。
- 真实 VoiceOver 人工检查标题层级、事件阅读顺序、滚动后焦点、Sheet dismiss、记录入口与核验动作；XCUITest 标签不能替代人工读屏。
- 签名、Provisioning、App Group、Share Sheet、TestFlight、隐私清单与商店材料继续按 release checklist 验收。
