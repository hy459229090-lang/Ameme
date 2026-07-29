Verdict: `conditional_pass`
Scope / Build / Commit: Android Release 普通用户 QR v2 系统扫码/显式粘贴、设备密钥、TLS 签发、应用通道认证与同安装重连；实现已收口到 `8d04bc7` 并推送 PR，未公开发布
Gate / Spike ID: `P0-ANDROID-QR-V2-SCANNER-CLIENT-07` / `AG-002`
Owner / Reviewer: Codex 主执行 Agent / 物理扫码、真实 LAN 与独立安全签收待执行
Date / Environment: 2026-07-29；JDK 17、Android SDK 36、API 36 / 16 KB arm64 AVD，320dp 基线设备为 1080×2400 / 130% 字号

# P0 Android 普通用户 QR v2 生产扫码与认证验证

## 结论

Android Release 的“扫描二维码”不再是空入口或模拟连接。当前仓库候选形成以下最小生产链：

1. 用户从设置页主动选择“扫描二维码”；App 调用 Google Play services 的 QR-only
   [Google Code Scanner](https://developers.google.com/ml-kit/vision/barcode-scanning/code-scanner)，
   Manifest 不声明 `CAMERA`，也不在 App 内接收相机帧。
2. 扫描结果只在 Activity/Compose 短时内存中保留，最大 16,384 字符；严格 QR v2 parser
   校验 canonical envelope、五分钟授权期、主机配对期、TLS pin 与 bootstrap secret。
3. 用户看到设备、event-only 权限和二维码确认截止时间；只有点击“允许并连接”后才在
   `noBackupFilesDir` 保存加密 pending 状态。
4. Android Keystore 生成不可导出的 P-256 客户端密钥，以
   `SHA256withECDSA` 完成 possession proof；TLS 1.3 客户端精确钉扎二维码中的 X.509
   SHA-256。
5. 一次性 bootstrap 只签发与二维码 secret 分离、绑定该客户端 key 的应用 credential；
   pending/active 记录由独立 Keystore AES-256-GCM key 包裹，并使用 `AtomicFile` 收敛。
6. App 用新 credential 完成冻结 v1 HMAC 双向 hello，且服务端明确公布
   `create_event` 后才显示“已连接”。普通用户默认不会获得读取、Revision、撤销或长期
   Memory 确认能力。
7. 重启只从加密 pending/active 状态重新执行 bootstrap 或应用认证；展示元数据不会被当作
   已连接。断开、本机 Space 删除、过期、损坏和缺 key 均清除或 fail closed。

无 Google Play services、按需模块不可用、空结果、取消、非法/过期码、网络失败、证书 pin
不匹配或应用认证失败时均不建立连接，并显示可理解状态。连接方式同时提供“粘贴完整配对码”
作为无系统扫码服务时的显式替代：Ameme 不读取剪贴板，用户自行粘贴；输入最大 16,384 字符，
只保留在当前窗口，并在取消或提交时清除。该入口复用同一严格 QR v2 parser、event-only
确认、设备凭据和 TLS 应用认证链，不是开发者 Host 材料或降级 bearer。

本结论不包含物理相机光学扫码、两台物理设备、真实 Wi-Fi/LAN、后台重连或真实用户理解度。
AVD 测试向 UI 注入合成 QR 结果，并在同一虚拟设备 loopback 上运行真实 Keystore/TLS；不得
扩写为物理扫码或真实跨设备传输。

## 实现证据

| 层 | 生产实现 | 已建立的不变量 |
|---|---|---|
| 扫码入口 | `MainActivity.kt`、Manifest、version catalog | QR-only、用户触发、无 App camera permission、Play services 不可用可见、结果有界且消费后清除 |
| 无 Play 替代 | `SettingsScreen.kt` | 只接受用户显式粘贴、不读取剪贴板、16,384 字符上限、取消/提交即清、复用同一严格解析与授权链 |
| 产品授权 | `SettingsScreen.kt`、`AmemeApp.kt` | event-only 明示、授权截止时间、连接前确认、失败不显示连接、启动实际重连、本机删除清凭据 |
| Release 装配 | release `PairingExperienceConnectorProvider.kt` | Release 使用生产连接器；Debug 继续只在显式体验路径模拟 |
| 候选/恢复 | `ProductionPairingExperienceConnector.kt` | 内存中最多一个未确认 QR；确认后保存 pending；应用认证和 `create_event` 协商后才发布状态 |
| 设备凭据 | `AndroidPairingCredentialStore.kt` | Keystore P-256 非导出私钥、AES-GCM 包裹、no-backup 原子记录、过期/损坏/缺 key fail closed |
| 网络 | `AndroidPairingNetworkClient.kt` | TLS 1.3、精确证书 pin、5 秒 I/O 上限、bounded line、bootstrap/server proof、应用 HMAC hello |
| Host TLS 隔离 | `AgentPairingManager.kt` | Local Node server KeyManager 精确绑定 RSA TLS alias，不会选中共存的 P-256 客户端 possession key |

## 可复现验证

### JVM、构建与静态合同

```bash
cd apps/android
env JAVA_HOME=/private/tmp/ameme-jdk17-runtime/Contents/Home \
  ANDROID_HOME=/private/tmp/ameme-android-sdk \
  ./gradlew testDebugUnitTest testReleaseUnitTest \
    compileDebugAndroidTestKotlin lintDebug assembleDebug assembleRelease

cd ../..
python3 scripts/validation/validate_pairing_qr_contract.py
```

新增 JVM 直接覆盖：

- strict bootstrap possession proof、独立 credential、错误过期；
- 应用通道认证前不公布 capability、伪造 server hello fail closed；
- 二维码候选确认前不落盘、另一次扫码替换旧候选、缺 `create_event` 拒绝；
- pending bootstrap 与 active credential 只在真实 reconnect 后恢复；
- 过期候选零持久化、显式断开清凭据。

Debug 与 Release 均为 121 tests / 0 failure / 0 error / 0 skip。Lint、androidTest 编译、
Debug 和 unsigned Release assemble 通过；最终 APK 为 Debug 43,811,576 bytes、
unsigned Release 35,763,367 bytes、androidTest 2,847,128 bytes。

QR 静态合同为 62/62；输出明确
`android_permissionless_system_scanner_wiring_claim=true`，
`android_manual_pairing_code_fallback_claim=true`，
`android_physical_scanner_execution_claim=false`、
`physical_device_execution_claim=false`。

隔离 Python 3.12 统一工作区验证为 28/28；其中 Markdown 43 条链接和治理 182 项均通过。

### API 36 / 16 KB AVD

```bash
cd apps/android
env JAVA_HOME=/private/tmp/ameme-jdk17-runtime/Contents/Home \
  ANDROID_HOME=/private/tmp/ameme-android-sdk \
  ANDROID_SERIAL=emulator-5560 \
  ./gradlew connectedDebugAndroidTest
```

设备为 API 36 arm64 AVD，`getconf PAGE_SIZE=16384`，1080×2400，字号 1.3。实现提交
`8d04bc7` 的完整 XML 精确为
115 discovered / 108 passed / 7 个显式外部门 skipped / 0 failed。

本切片新增并实际执行：

- Keystore pending/active 加密往返、P-256 proof、清理、损坏与过期：2/2；
- 系统扫码入口、event-only 确认、无 Play services 可见错误：3/3；
- 显式粘贴复用 event-only 确认、超长拒绝、失败提交后清除：3/3；
- Manifest 无相机权限且声明 `barcode_ui`：新增断言通过；
- 真实 Android Keystore + TLS 1.3 + QR bootstrap + 独立 credential + 应用 HMAC：
  1/1。

纵向测试使用真实 TLS socket、真实 Keystore key/certificate、真实服务端 bootstrap manager
和真实 Release 客户端组件；只把 loopback/合成 payload 记为仓库设备执行证据。

## 首次失败、根因与修复

| 首次失败 | 根因 | 修复与重跑 | 未解决影响 |
|---|---|---|---|
| Gradle 找不到 Java，随后找不到 SDK | 当前 shell 未继承仓库隔离工具链 | 显式固定 JDK 17、SDK 36；完整构建与测试通过 | 仅复现环境 |
| 首轮 Kotlin 编译拒绝 `Signature.initSign` 的通用 `Key` | API 要求 `PrivateKey` | Keystore 读取后精确校验并转换为 `PrivateKey`；双变体编译通过 | 无 |
| 旧 QR 静态门仍断言 Release provider 为 `null` | 验证器冻结在 scanner client 未实现的历史状态 | 改为校验生产 provider、系统扫码、凭据存储、TLS/pin、恢复和否定性物理 claim；61/61 | 无 |
| 新 UI 测试误导入 `assertDoesNotExist` | 当前 Compose v2 API 以节点扩展提供，无需显式 import | 删除无效 import；androidTest 编译和设备 3/3 通过 | 无 |
| 首轮纵向 TLS 测试在 bootstrap 后应用重连失败 | P-256 客户端 key 与 RSA 服务端 key 共处 Android Keystore；默认 KeyManager 选错 EC alias，TLS 尝试未获授权的 digest 后关闭 listener | 新增 alias-pinned server KeyManager，只允许专用 RSA TLS alias；同一测试通过，随后完整 112 项回归 0 fail | 真实 OEM TLS provider 仍需物理设备矩阵 |
| 新候选替换单测首次使用不存在的 `copy()` | pairing material 是验证型普通 class，不是 data class | 用完整构造器建立第二个严格 pairing；同一测试通过 | 无 |
| 旧 HEAD `1acc1af` Android CI 的 Event 详情 UI 用例找不到刚保存的动态标题 | 用例只等待保存弹窗消失，没有等待异步写入后的 Event 进入 LazyColumn 语义树 | `7ec7a16` 在点击前对 `today-list` 执行精确 `performScrollToNode` 并断言显示；本地完整回归与远端 Android CI `30457406008` 均通过 | 最终 head 仍需独立 CI 签收 |
| 手工替代静态门首轮期待字面 `testTag("pairing-method-manual")` | 生产组件通过参数传入 tag，验证器把等价实现误写成字面形状 | 改为校验稳定 tag 值、输入/提交 tag、长度、resolver 与无剪贴板读取；62/62 | 无 |
| 清空输入测试首轮使用 `assertTextEquals("")` 失败 | `OutlinedTextField` 为空时仍在 `Text` 语义中包含 label/错误文案，实际 `EditableText` 已为空 | 对 `SemanticsProperties.EditableText` 精确断言空值；目标 6/6、完整回归通过 | 无 |
| 定向 runner 后的两次全量运行分别在 1/115、0/115 被强杀 | 另一仓库会话同时在同一 serial 启动 instrumentation；logcat 明确显示第二个 `start instr` 杀死首个进程 | 不删除失败；等待对方结束后独占重跑 | 无产品影响 |
| AVD 冷重启首轮两个既有 UI 等待超时 | 高负载冷启动下 Today 就绪和恢复按钮语义未在旧固定窗口内出现；其余 106 个可执行用例通过 | 设备稳定后组合态 115/108/7/0；随后干净 worktree 再次完整通过 | 仍需物理设备性能门 |

失败证据没有删除，也没有放宽验收门。

## 未关闭 Gate

- 物理 Android 相机对屏幕/纸面 QR 的对焦、反光、低光、缩放、取消与旋转；
- 一台 Android 扫另一台 Android/iOS/桌面 Host 的真实 Wi-Fi/LAN、网络切换与后台重连；
- 无 Google Play services 的物理设备上对显式粘贴路径、输入法和 TalkBack 的真实兼容验证；
- OEM Keystore/StrongBox 差异、锁屏后的 key 可用性、进程 kill、系统升级与 30 天过期；
- 共享账户 Grant registry、跨设备撤销传播、peer 删除和审计汇总；
- 真实 TalkBack、真实用户对 event-only 授权的理解、T0 Pilot；
- 签名、Play internal、商店 Data safety、安全 reviewer、事故与回滚签收。

## 判定

`P0-ANDROID-QR-V2-SCANNER-CLIENT-07` 关闭了仓库内 Android Release “普通用户无法扫描或在
无系统扫码服务时显式粘贴，并建立经过认证的 QR v2 连接”的可执行空白；完整 AVD 回归同时
捕获并修复了 TLS alias 跨域选择和提交后输入清理证据问题。结论保持 `conditional_pass`：
仓库候选可复跑，但物理扫码、无 Play 真机、真实 LAN、真实用户和发布 Gate 仍为 `hold`。
