# Android Agent 统一连接体验验证（2026-07-15）
- Verdict：`conditional_pass`
- Scope/Build：Android Debug 普通用户连接体验、Release 模拟能力隔离；0.1.0
- Gate/Spike：MVP 工程实现体验切片；不关闭 LAN-SYNC-01
- Owner/Reviewer：Codex 总控 / 产品负责人待体验 Review
- Date/Environment：2026-07-15，Windows 11，Android API 36 x86_64 AVD `emulator-5556`

## 目标与预注册口径

| 口径 | 通过条件 | 结果 |
|---|---|---|
| 普通用户入口 | 同网自动发现、扫码、账户设备三种入口可见，不要求密钥、JSON、IP 或端口 | 通过 |
| 统一授权 | 三种入口都先形成候选，再显示设备、Agent、方式和允许范围，由用户确认 | 通过 |
| 体验诚实 | Debug 明确显示“不建立真实网络连接”，不创建 Event、Grant 或访问审计 | 通过 |
| 状态管理 | 成功态保存非敏感元数据，可断开；畸形状态 fail closed 并删除 | 通过 |
| 发布隔离 | Release 不提供 synthetic connector，手工 TLS 凭据只在 Debug 开发者入口 | 通过 |
| 回归 | Android JVM、Lint、Debug/Release/Test APK 与普通设备回归无未解释失败 | 通过 |

## 环境与数据

- Android：Kotlin、Jetpack Compose Material 3，`minSdk 34` / `targetSdk 36`。
- 设备：API 36 x86_64 AVD `ameme_source_pack_api36`，序列号 `emulator-5556`。
- 数据：仅固定 `Ameme Desktop`、`Codex` 和“写入结构化工作记录”等合成元数据；没有真实账户、二维码、IP、密钥、位置、照片、音频或工作区内容进入该体验状态。
- 网络：体验 Connector 不创建 socket；本次验证不要求云服务、物理 LAN、NSD、扫码器或 Account Identity。

## 可复现命令

```powershell
$env:JAVA_HOME='C:\Users\N33131\AppData\Local\Programs\Microsoft\jdk-17-ameme'
$env:ANDROID_HOME='C:\Users\N33131\AppData\Local\Android\Sdk'
$env:ANDROID_SDK_ROOT=$env:ANDROID_HOME
cd E:\Ameme\apps\android

.\gradlew.bat --no-daemon testDebugUnitTest testReleaseUnitTest lintDebug assembleDebug assembleRelease assembleDebugAndroidTest
$env:ANDROID_SERIAL='emulator-5556'
.\gradlew.bat --no-daemon connectedDebugAndroidTest
```

定向复验使用：

```powershell
.\gradlew.bat --no-daemon connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.ameme.android.AmemeUiSmokeTest'
.\gradlew.bat --no-daemon connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.ameme.android.data.transport.PairingExperienceStoreInstrumentedTest'
```

## 结果

| 断言 | 实际结果 | Verdict |
|---|---|---|
| 三入口收敛 | Debug 单元测试遍历 `LanDiscovery/QrCode/AccountDevice`，三者均生成相同有界候选、能力和固定成功对象 | pass |
| Release 隔离 | Release provider 返回 `null`；Release APK 可组装；独立 Release 单测覆盖 | pass |
| UI 主路径 | 5/5：既有 Today/Search/Capture/Delete 3 条 + 三入口可见 + 同网授权/成功/断开 2 条 | pass |
| 状态持久化 | 2/2：仅保存 7 个非敏感字段；保存/读取/清除通过；未知方式与意外 `secret` 被 fail closed 清空 | pass |
| Android 普通设备回归 | 56 项发现：49 通过、7 项显式 Gate 跳过、0 失败 | pass |
| JVM/Lint/构建 | Debug/Release Kotlin、Debug/Release 单测、Lint、Debug/Release/Test APK 通过 | pass |
| 人工视觉检查 | 设置卡片、三入口 sheet、统一授权、成功提示和连接后状态在可见 AVD 中检查；模拟边界可见 | pass |

## 失败记录与限制

| Severity | 记录 | 影响 | 处理 |
|---|---|---|---|
| 工具超时 | 首轮组合 Gradle 命令在外层 120 秒时限终止，没有返回代码失败 | 不能据此判定通过 | 使用缓存和更长时限完整复跑，最终退出码 0 |
| 待实现 | NSD/Network.framework、真实扫码交换、账户设备列表、设备证明和统一 Grant registry 未实现 | 普通用户目前只能体验流程，不能真实配对 | 保留 LAN-SYNC-01 与 Agent 后续任务，不升级本报告结论 |
| 待验证 | 物理设备、后台、电量、隔离 Wi-Fi/热点、恶意 peer、iOS 未验证 | 不能用于公开发布 Gate | 后续真机与 Mac 证据关闭 |

现有手工 TLS 1.3/certificate pin/HMAC 通路已有独立 Host→Android→SQLCipher 证据，本切片只是把该入口下沉至 Debug 开发者选项；该证据不能替代自动发现、二维码或账户连接。

## 隐私和证据处理

- 只使用 synthetic 元数据和测试 AVD；没有 Restricted 或真实参与者数据。
- 连接偏好不保存秘密、JSON、IP、端口或正文；测试对未知字段执行 fail closed 清除。
- 截图只用于本次本地视觉 Review，不提交 Git；仓库只保留命令、数量和无正文断言。

## Review 结论

继续把该流程作为普通用户连接体验基线，并允许产品负责人直接在当前 Debug APK 中体验。结论保持 `conditional_pass`：不能声称三种真实连接方式、数据传输、云服务、物理 LAN 或发布能力已经完成。
