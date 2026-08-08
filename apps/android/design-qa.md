# Android 视觉实现 QA（更新于 2026-07-29）

- Verdict：`pass`（AVD / 仓库工程候选）；物理 OEM 设备与真实 TalkBack 保持 `hold`。
- 设计基线：当前稳定 Material 3 生产表达；不以 `1.5.0-alpha` 的 Material 3 Expressive API 作为发布依赖。
- 运行基线：Compose BOM `2026.06.00`、Material 3 `1.4.0`、API 36 / Android 16 / 16 KB page-size AVD。
- 对照输入：`outputs/ameme-mobile-audit-20260726/design-qa/android-before-vs-current.png`。
- 字号输入：`outputs/ameme-mobile-audit-20260726/design-qa/android-font130-vs-font200.png`。
- 本轮 Search 证据：API 36 / 16 KB arm64 AVD，320dp、`font_scale=1.3`；91 discovered / 84 passed / 7 外部门 skipped / 0 failed，完整 UI 套件 12/12。

## 结论

当前实现关闭了旧版 Android 视觉中最明显的紫色卡片、胶囊状态标签、无语义齿轮占位和过度容器化问题。Today 保持一个高强调“记录”操作、平面时间流、清楚的演示边界和必要的核验卡；iOS 与 Android 共享产品语义，但保留各自原生平台表达。

在 130% 与 200% 字号的同屏对照中，标题、日期、事件标题、详情、时间、状态和底部 FAB 没有横向碰撞或不可逆裁切；200% 字号按可滚动内容自然增加纵向高度。搜索与设置保持独立可点击语义，事件状态不只依赖颜色。

2026-07-29 的 Search 复盘发现一个真实 P1：顶部条件区固定、结果区单独滚动时，空数据与小屏/大字号组合会让底部复用入口不可达。当前整页已改为单一 `LazyColumn`，日期控件等宽，空态、范围说明与单一“把记忆用起来”行项目共同滚动；四种意图使用 Material 3 原生 `AlertDialog`，没有复制 iOS 毛玻璃。320dp / 130% 字号最终截图中“起始 / 结束”、入口、副标题、空态和四个意图均无裁切。

## 强制对照

| Surface | 结果 | 证据与判断 |
|---|---|---|
| 字体与层级 | pass | 系统字体、大标题、正文和状态层级清楚；大字号不压缩成不可读的横向时间行 |
| 间距与布局 | pass | 20–24 dp 主边距、平面分隔、单列时间流；无多余装饰卡，核验卡只承载真实待办 |
| Viewport resilience | pass | 130% / 200% 字号截图无标题—操作碰撞；Search 使用单一滚动容器，320dp / 130% 下复用入口与空态均可达 |
| 颜色与状态 | pass | 暖中性色为内容底，teal 只用于主操作/时间/关键语义；深浅色 token 均由 MaterialTheme 提供 |
| 图标 | pass | 可见图标已迁移到官方 Material Symbols XML 资源；已移除停止维护的 `material-icons-extended` |
| Copy | pass | “演示数据 · 不写入本机”、事实状态和核验说明各司其职，不把模拟位置或计划写成事实 |
| 状态与交互 | pass | 搜索、设置、事件详情、记录 FAB、核验与复用入口可操作；真实与固定 Mock 数据路径隔离，演示模式不写生产复用遥测 |
| 无障碍 | pass（自动化） | 语义树含独立搜索/设置/记录按钮和完整事件状态；200% 字号设备截图与 Compose UI 回归通过 |
| AI shortcut artifacts | pass | 无手绘 SVG、emoji 图标、装饰性 blob、假头像或无功能的通用圆角卡片 |

## 已关闭发现

1. `P1 / icons`：旧 `material-icons-extended` 已不再是 Android 官方推荐路径。实现改为官方 Material Symbols VectorDrawable，并在 `AmemeSymbols` 集中提供 Compose painter。
2. `P1 / responsiveness`：事件的时间、标题、详情和状态在大字号下曾有横向拥挤风险；当前 accessibility font scale 使用纵向信息结构。
3. `P1 / hierarchy`：旧版多个紫色/高饱和卡片争夺注意力；当前仅“记录”保留最高强调，待核验使用功能性容器。
4. `P2 / content`：演示标识从大块提示卡收敛为一行，同时保留完整辅助功能说明。
5. `P1 / Search reachability`：固定条件区与独立结果列表会在空态/小屏/大字号下隐藏后续操作；整页改为单一 `LazyColumn`，设备 UI smoke 通过。
6. `P2 / cross-platform expression`：复用意图不使用 iOS 式玻璃或横向四按钮，而采用单一扁平行项目 + Material 3 原生 `AlertDialog`。

## 发布保留门

- 至少一台 Android 14+ 主流 OEM 物理设备执行显示大小、200% 字号、深色、横竖屏、相机/分享、权限撤销和进程恢复。
- 真实 TalkBack 人工检查朗读顺序、焦点回落、滚动容器、FAB 与核验操作；UIAutomator/Compose semantics 不能替代人工读屏。
- 内部分发签名、Data safety、后台/电量限制和真实 LAN/来源仍按 release checklist 单独验收。
