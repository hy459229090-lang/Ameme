# Android 16 KB 与 UI 验收（2026-07-18）

- Verdict：`conditional_pass`
- Scope：Android Debug APK 的 API 36 16 KB AVD 安装、连接回归、核心本机闭环、外部分享/导出待处理动作恢复、动态字体/横屏和截图/语义树审查。
- Environment：`ameme-api36-ps16k`，Android 16 / SDK 36 / arm64-v8a，`adb shell getconf PAGE_SIZE` 为 `16384`。
- Data：真实本机输入使用合成文本；演示模式使用明确标注的 Fake Repository，不写入真实 SQLCipher 数据库。

## 可复现命令

```sh
env JAVA_HOME=/tmp/ameme-jdk17/Contents/Home \
    ANDROID_HOME=/tmp/ameme-android-sdk \
    ANDROID_SDK_ROOT=/tmp/ameme-android-sdk \
    ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon

env JAVA_HOME=/tmp/ameme-jdk17/Contents/Home \
    ANDROID_HOME=/tmp/ameme-android-sdk \
    ANDROID_SDK_ROOT=/tmp/ameme-android-sdk \
    ./gradlew :app:connectedDebugAndroidTest --no-daemon
```

## 结果

| Gate | 结果 | 证据 |
|---|---|---|
| Debug 单测、lint、APK | pass | `testDebugUnitTest`、`lintDebug`、`assembleDebug` 均成功；APK 为 `apps/android/app/build/outputs/apk/debug/app-debug.apk` |
| 16 KB 安装与启动 | pass | 16 KB AVD 安装并启动 `com.ameme.android/.MainActivity`；页大小为 `16384` |
| 连接回归 | pass | `connectedDebugAndroidTest`：最新 64 项总测试中 57 项可执行测试通过、7 项显式 Gate 跳过、0 失败；新增导出快照清理与设备连接期限 UI smoke；报告目录为 `app/build/reports/androidTests/connected/debug/` |
| 本机闭环 | pass | 记录文字→Today→Search/日期范围→详情→删除影响范围；保存成功关闭记录 Sheet，加载中入口禁用，失败保留可重试状态 |
| Mock 闭环边界 | pass | 设置页“使用演示数据”明确说明不写入真实本机数据库；演示入口和连接/导出/删除边界可见 |
| 空白/稀疏状态 | pass | Android 普通本机路径按活动事件数自动显示 `空白`、`稀疏` 或 `正常`；加载、可恢复错误、离线、部分范围和权限受限状态优先保留；手动体验状态仍由 Mock/测试覆盖 |
| 真实/Mock 搜索一致性 | pass | Fake Repository 与 SQLCipher/LIKE 搜索均按最多 16 个空白分隔词逐词 AND 匹配；Android 单测覆盖跨字段命中与不完整查询不命中 |
| 外部分享确认 | pass | ACTION_SEND 解析后先显示“保存分享内容？”确认页；UI smoke 验证未确认前没有事件行，确认后才写入 Today；取消和写入失败均不创建或覆盖原有记录 |
| 导出/分享恢复 | pass | 导出快照和待确认分享均保存在 Android Keystore 加密的 `noBackupFilesDir` 快照中；Activity 重建 UI smoke 验证确认前无事件，新增清除导出快照 UI smoke；仓库恢复完成后才可保存；取消/写入失败/损坏快照不修改源数据 |
| 视觉与语义树 | conditional pass | 已保存并检查 Onboarding、Today、Capture、Text Entry、Event Detail、Delete Impact、Search、Date Picker、Settings 截图及 UIAutomator 树；另在 font scale 1.30 与强制横屏下确认 Today、搜索、设置和记录入口仍可见/可操作；Settings UI smoke 另验证来源权限/能力状态文案；Material 日期选择器已统一为中文日历区域 |

## 视觉/无障碍审查结论

- 通过：层级、留白、卡片与主按钮风格保持一致；稀疏 Today 状态有明确解释；删除页说明影响范围；日期选择器的月份、星期、标题和未选择状态与中文产品界面一致。
- 通过：本机事件数量变化会在普通路径中同步反映为空白、稀疏或正常，不再把空库表达为“已整理 0 件事”；加载和错误状态仍保留独立说明。
- 通过：关键入口存在可读 content description，包括搜索、设置、记录、事件行和删除流程；当前 AVD 的 UIAutomator 节点确认记录 FAB 与事件行本身都是带中文描述的可点击 Button；异常状态卡片的“查看”入口也声明 Button 角色；记录入口在仓库加载完成前不可操作，避免启动竞态。
- 通过：恢复中的分享确认页明确说明本机加密节点正在恢复，并禁用保存按钮；恢复完成后才允许确认，避免用户把生命周期竞态误解为保存成功。
- 限制：动态字体 1.30 与横屏只在 API 36 AVD 上完成当前 Today 语义/视觉检查，仍未在 TalkBack 或物理设备上完成完整读屏、系统字体极限、后台恢复和 OEM 兼容性验收；不能据此宣称完整无障碍通过。

## 第十九批设备级补充证据（2026-07-18）

- `/tmp/ameme-android-device-fontscale-130.png`：系统 font scale `1.30`，Today 空白态、日期、搜索/设置按钮和记录入口均可见；UIAutomator 保留对应中文节点。
- `/tmp/ameme-android-device-rotation-landscape.png`：强制横屏 `rotation=1`，Today 内容仍在可滚动容器内，搜索/设置/记录入口均为可点击 Button；复原后已将设备字体恢复为 `1.0`。
- 这两项只证明当前 AVD 的布局与语义不立即失效，不替代 Android 物理设备、TalkBack、后台和完整 Dynamic Type/OEM 验收。

## 第十二批深层页面复核（2026-07-18）

- Evidence root：`/tmp/ameme-audit-20260718-goal-3/`；本轮只使用该目录下重新捕获的截图和 UIAutomator XML，未混入历史截图。
- 复核结论：稳定帧覆盖 Onboarding、Today、记录 Sheet、文字输入、真实文字事件、事件详情、详情底部/删除入口、删除影响、删除完成、Search、开始/结束日期选择器、Settings 顶部与隐私/导出底部；未发现 P0/P1 视觉或交互问题。
- 设计观察：Today 的稀疏状态、来源/仅本机标签、删除影响范围、日期筛选的禁用/可用状态、演示数据不写入真实库和结构化导出边界均可读；日期选择器和 Settings 底部偶有短暂过渡渲染帧，等待约 2 秒后稳定，不作为缺陷。

| 步骤 | 当前运行证据 | 一般健康度 |
|---|---|---|
| 1. Onboarding → Today | `/tmp/ameme-audit-20260718-goal-3/01-onboarding.png`、`02-today.png` | pass；入口、空白态解释和主记录按钮可见 |
| 2. Today → 记录 Sheet → 文字输入 | `03-capture.png`、`04-text-entry.png`、`05-text-filled.png` | pass；四种来源、隐私提示、禁用/启用保存状态清晰 |
| 3. 真实文字 → Today → 事件详情 | `06-today-event.png`、`07-event-detail.png`、`08-event-detail-bottom.png` | pass；用户原话、时间、用户陈述、仅本机、Revision 和来源解释完整 |
| 4. 删除影响 → 确认删除 → 完成 | `09-delete-impact.png`、`10-delete-progress.png` | pass；影响范围、确认前状态、完成状态和返回入口清晰 |
| 5. Search → 日期范围 | `11-search.png`、`12-start-date-picker.png`、`13-start-date-selected.png`、`14-search-start-selected.png`、`15-end-date-picker.png`、`16-end-date-picker-stable.png` | pass；开始/结束日期状态、中文日历、取消/应用和无结果说明可见 |
| 6. Settings 顶部 → 底部 | `17-settings-top.png`、`19-settings-bottom-stable.png`、`21-settings-privacy-bottom-stable.png` | pass；来源权限、演示数据、AI 边界、TLS 配对、导出、删除和诊断说明分层清楚 |

- 语义检查：`onboarding-ui.xml`、`today-ui.xml`、`capture-ui.xml`、`text-entry-ui.xml`、`today-event-ui.xml`、`event-detail-ui.xml`、`delete-impact-ui.xml`、`delete-progress-ui.xml`、`search-ui.xml`、`start-picker-ui.xml`、`end-picker-stable-ui.xml`、`settings-top-ui.xml`、`settings-bottom-stable-ui.xml`、`settings-privacy-bottom-stable-ui.xml` 均保存在同一 evidence root；不能替代 TalkBack 手势、动态字体、旋转和物理设备测试。

## 第二十一批 AccessGrant 增量（2026-07-18）

- Android 新增 `AgentAccessGrant`/`AgentAccessGrantPolicy`：开发者配对创建时保存 30 天的 Personal/`autonomous_memory`/structured `event` 本地批准范围；恢复时缺少或损坏策略会撤销配对。
- `MemoryRepositoryAgentLocalNodeEndpoint` 在 repository 写入前校验 caller/Grant、期限、撤销、purpose、space 和 data type；新增 JVM 单测覆盖最小 scope、过期/撤销、policy bind 和 endpoint 扩权拒绝。
- 连接授权卡片新增“拟授权范围”文案，与 iOS 对齐；这不把发现候选、演示连接或配对范围策略写成共享 Grant registry/真实数据传输。
- M21 最新 Kotlin 变更已使用 `/tmp/ameme-jdk17`、Android SDK 36 和 API 36 16 KB arm64 AVD 复验：Debug/Release JVM、lint、assemble 通过；全量连接回归 64 总计 / 57 可执行通过 / 7 显式跳过 / 0 失败；新增授权范围 UI 定向 smoke 1/1 通过；配对 Host→TLS/HMAC→Android→SQLCipher→Today smoke 通过，重启后事件仍可见且未用 ADB 注入；TalkBack AVD 语义探针完成 Today→设置→三入口→授权卡片→演示成功双击路径，关键节点均可读且可操作。仅保留物理设备/OEM TalkBack、后台、旋转和真实 QR/账户/共享 Grant/数据传输门禁。

## 未关闭门禁

- iOS 仍需完整 Xcode/iOS SDK 下的 Simulator/真机构建、截图、VoiceOver 和本机闭环证据。
- Android 仍需至少一台物理 Android 14+ 设备，覆盖 OEM 日历/录音、权限撤销、进程死亡、后台、真实 LAN 与性能。
- 7 项连接/性能/来源 Gate 仍按现有规则跳过，不升级为真实联网、真实宿主、真实模型或发布通过。
