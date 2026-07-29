Verdict: `conditional_pass`
Scope / Build / Commit: `ameme.agent-pairing-bootstrap.v2`、Android server credential registry、iOS 设备专属凭据恢复、冻结的 `ameme.agent-local-node.channel.v1` 应用通道；工作区未提交状态
Gate / Spike ID: `P0-QR-ONE-TIME-BOOTSTRAP-V2-11`
Owner / Reviewer: Codex 主执行 Agent / 配对认证、凭据恢复与 Release 隔离复核
Date / Environment: 2026-07-29；macOS Command Line Tools、隔离 Python 3.12、API 36 / 16 KB arm64 AVD；无双端物理设备或真实 LAN

# P0 QR 一次性 Bootstrap 与设备凭据轮换验证

## 结论

实施前安全审计发现的长期 QR bearer 已被仓库内的 v2 bootstrap 模型替代。当前普通用户二维码只包含最长 5 分钟的 bootstrap ID/secret；Android 将其包裹持久化，并在首次有效请求中同时验证 bootstrap HMAC、P-256 客户端私钥持有签名、pairing/device/TLS pin 绑定，然后原子写入 `consumed`、客户端公钥/摘要和独立生成的 30 天 channel credential。QR secret 从不进入冻结的应用通道。

为处理服务端已提交但响应丢失，Android 只在最长 5 分钟 receipt 窗口内允许同一公钥重新取得同一已签发 credential；不同公钥、过期 bootstrap 和 v1 envelope 均失败关闭。Release 不再生成或持久化 Debug developer bearer；Debug Host smoke 使用独立开发凭据，不读取 QR secret。

iOS 在网络请求前把 envelope、P-256 私钥和 key thumbprint 保存到 `kSecAttrAccessibleWhenUnlockedThisDeviceOnly` Keychain。启动时可以用同一 key 重试 pending bootstrap，或加载已签发 credential 重新完成 TLS 1.3/pin/HMAC 应用通道认证；断开和本机 Space 删除会清除该记录。这里的“设备 key 绑定”精确指签发时验证私钥持有、Android 保存绑定记录、iOS 设备专属存储并在恢复时复核 key thumbprint。当前冻结应用通道仍使用已签发 bearer，并未在每次重连时再次执行 P-256 持有证明。

仓库工程子门判定 `conditional_pass`。静态契约、双端编解码、Android Keystore/消费状态、iOS Keychain pending→active→expiry、AVD 上 Swift→Android v2 bootstrap→四操作应用通道和 Debug Host 独立凭据路径已执行。Android 相机扫码生产 client、共享账户 Grant/revocation registry、两台物理设备扫码/真实 LAN/后台、iOS 最终 head XCTest 和发布签名仍为真实 Gate。

## 实现边界

| 能力 | 当前实现 | Verdict |
|---|---|---|
| QR envelope | `ameme-pairing-v2:` 严格 canonical JSON，5 分钟 bootstrap 与 30 天 pairing expiry 分离，v1 拒绝 | `pass`（仓库契约） |
| 服务端一次性消费 | Android 持久 `pending/consumed`，首次签发与消费同一 preferences commit，不同 key 重放拒绝 | `pass`（静态 + AVD instrumentation） |
| 响应丢失恢复 | 最长 5 分钟且仅同一 P-256 公钥可重取同一 credential | `pass`（AVD instrumentation） |
| 凭据分离 | QR bootstrap secret 与签发的应用 channel secret 不同；应用 listener 从不接受 bootstrap secret | `pass`（静态 + 跨端 AVD smoke） |
| Release 隔离 | Release pairing 没有 developer bearer；签发前可仅监听 bootstrap，签发后只加载 QR credential | `pass`（Release source/build + AVD instrumentation） |
| iOS 进程重启 | pending/active 状态保存在 device-only Keychain，pending 使用同一私钥重试，active 重新认证后才恢复 UI 连接 | `conditional_pass`（本机 Keychain Smoke；XCTest 源码未在最终 head 执行） |
| 每次重连 P-256 证明 | 冻结应用通道仍使用签发 bearer；P-256 只约束签发与同 key receipt retry | `not_claimed` |
| Android 扫码客户端 | Android 当前产品角色是 Local Node host/QR 输出端，没有相机扫码→生产 client transport | `hold` |
| 账户/共享 Grant | pairing-scoped 本机 policy 仍是 trust root，没有共享 registry 或跨设备撤销传播 | `hold` |
| 物理设备/LAN | 本轮只有 API 36 / 16 KB AVD、ADB forward 与本机 Keychain | `hold` |

## 可复现命令与结果

### 静态与工作区

```sh
/private/tmp/ameme-project-py312/bin/python \
  scripts/validation/validate_pairing_qr_contract.py

/private/tmp/ameme-project-py312/bin/python \
  scripts/validation/run_workspace_validation.py
```

- QR v2 静态 Gate：54/54。
- 机器输出保持 `server_enforced_one_time_secret_claim=true`、`persistent_reconnect_credential_rotation_claim=true`、`release_developer_bearer_absent_claim=true`。
- 同一输出保持 `android_camera_scanner_claim=false`、`account_or_shared_grant_registry_claim=false`、`physical_device_execution_claim=false`。
- 统一 workspace：28/28 Gate；Markdown 43、治理 180，0 error / 0 warning。
- Android Agent Revision validator 在 runtime 权限交集抽成 helper 后，首次因旧字符串 marker 失败；校验器改为同时断言 helper 调用和 `dataTypes.intersect`，实现语义未回退，重跑 87 项通过。

### Android

```sh
export JAVA_HOME=/private/tmp/ameme-toolchains-20260726/jdk17/Contents/Home
export ANDROID_HOME=/private/tmp/ameme-toolchains-20260726/android-sdk
export ANDROID_SDK_ROOT=/private/tmp/ameme-toolchains-20260726/android-sdk

cd apps/android
./gradlew --no-daemon \
  testDebugUnitTest testReleaseUnitTest \
  assembleDebug assembleRelease assembleDebugAndroidTest lintDebug
```

- Debug 与 Release JVM XML 合计 220/220；单 variant 为 110/110，0 fail / 0 skip。
- Debug/Release、androidTest 编译和 Lint 通过；Lint 为 0 error、29 warning、1 hint。
- APK：Debug 41,190,345 bytes；unsigned Release 33,592,286 bytes；androidTest 2,874,573 bytes。
- `AgentPairingManagerInstrumentedTest` 在 API 36 / 16 KB AVD 定向 7/7：一次性消费、同 key receipt retry、不同 key 拒绝、过期清理、Release 无 developer bearer、仅签发凭据激活等均通过。
- 全量 instrumentation 的最终 XML 为 103 discovered / 96 passed / 7 显式外部门 skipped / 0 failed。首次全量在 `LocalEventDatabaseInstrumentedTest.capturedBatchPreflights...` 处目标进程被 signal 9 终止且无 assertion trace；同用例定向 1/1 及随后全量重跑通过。该记录保留，不把 AVD 写成物理设备。
- Material 3 设置页把存储/同步、当前 Agent 和接收其他设备拆成三段，普通 QR 默认不展示 Debug 开发者材料；Today、设置与 QR 均已在 `font_scale=1.3` 下截图人工复核。该视觉证据不替代 TalkBack 或物理扫码。

### iOS

```sh
swift build --package-path apps/ios --target AmemeShared
swift build --package-path apps/ios --target AmemeApp
swift build --package-path apps/ios --target AmemeSharedSmoke
swift build --package-path apps/ios --target AmemeLocalNodeSmoke
swift run --package-path apps/ios AmemeSharedSmoke

swiftc -frontend -parse \
  apps/ios/Tests/AmemeSharedTests/AgentPairingBootstrapTests.swift

plutil -lint apps/ios/Ameme.xcodeproj/project.pbxproj
```

- Shared/App/SharedSmoke/LocalNodeSmoke 构建通过。
- Shared Smoke 实际执行跨端 v2 golden、signed client hello/篡改拒绝，以及唯一 service 名的 device-only Keychain pending 保存、按 pairing 和通用启动恢复、active 转换、active 重载、过期自动清理和显式清除。
- 新 XCTest 源码与 Xcode project 引用通过 parse/plist 校验；当前机器只有 Command Line Tools，`swift test` 真实失败于缺少 `XCTest`，所以不声明新测试已由完整 Xcode 执行。

### 跨端动态子门

```sh
/private/tmp/ameme-project-py312/bin/python \
  scripts/dev/agent/smoke_ios_network_to_android.py \
  --adb /private/tmp/ameme-toolchains-20260726/android-sdk/platform-tools/adb \
  --serial emulator-5560
```

Swift Network.framework→Android API 36 / 16 KB AVD 实际输出：

```json
{
  "status": "passed",
  "transport": "ios-qr-v2-bootstrap-to-android-local-node",
  "bootstrap_credential_separated": true,
  "bootstrap_issuance_client_key_bound": true,
  "qr_user_path_connected": true,
  "tls_hmac_grant_bound": true,
  "bounded_visible_events": true,
  "append_revision": true,
  "exact_event_and_revision_undo": true,
  "content_logged": false
}
```

该子门使用 ADB forward 和合成 Event，证明 v2 bootstrap 后可以用独立 credential 建立冻结 v1 应用通道；不证明相机光学扫码、物理 LAN、真实用户、第三方 Host 或后台。

Debug Host→Android smoke 使用隔离 Python 3.12 重跑并通过 durable/local-only、Today 可见、重启保留、ADB Event 注入为 false、QR bootstrap secret 未用于应用通道、Debug developer credential 独立。系统 Python 3.9 首次因不支持要求的 TLS 1.3 失败；runner 现会在执行前准确拒绝该环境。固定 UI 等待还曾在 App/数据库启动慢时失败，改为有界状态轮询后通过。

## 安全与恢复复核

- bootstrap request 同时绑定 protocol、bootstrap/pairing/device ID、TLS certificate pin、client public key/thumbprint、nonce、sequence、P-256 possession signature 和 bootstrap HMAC。
- server response 绑定同一 client nonce/key、server nonce、credential ID/secret/expiry，并由 bootstrap HMAC 认证。
- Android 将 bootstrap secret、Debug/QR channel credential 分别以不同 AAD 由 Android Keystore AES-GCM 包裹；非 secret pairing 文件不包含任何 bearer。
- Release 默认 `enableDeveloperCredential=false`，runtime 也只接受 `DeviceBootstrapV2` credential；Debug developer credential 与 QR credential 分开。
- iOS credential Keychain record不含 Event 正文、query、对象 ID、Grant payload 或审计；断开、过期、损坏和 Space 删除路径失败关闭。
- 签发后的 channel bearer 仍是可用至 pairing expiry 的 bearer。设备 key 记录提高签发、响应丢失恢复和本机存储边界，但当前协议不声称 bearer 被复制后仍必须同时出示 P-256 私钥。

## 首次失败与处理

| 首次失败 | 根因 | 处理 / 重跑 |
|---|---|---|
| v1 审计发现 QR secret 可在 30 天 listener 循环重放 | QR 与长期 channel 使用同一 bearer | 引入 v2 bootstrap、服务端消费账本与独立签发 credential；v1 QR fail closed |
| Swift Smoke 迁移时编译失败 | fixture 仍使用 v1 envelope 字段 | 同步 v2 golden/bootstrap builder，Shared Smoke 重跑通过 |
| 首次 v2 网络 Smoke `transportFailed` | bootstrap listener 完成后，Android 重建应用 listener 存在短暂竞态 | 仅 transport failure 使用 100/250/500 ms 有界重试并创建新 client；重跑通过 |
| 一次 AVD 在设备测试中离线，cleanup 也失败 | 虚拟设备生命周期异常 | 新建隔离 AVD、保留失败、重新执行 |
| 系统 Python TLS 1.3 不可用 | Command Line Tools Python/OpenSSL 不支持 `TLSVersion.TLSv1_3` | runner 增加能力预检；固定隔离 Python 3.12 后通过 |
| Host smoke 固定等待后找不到 UI | App/SQLCipher/runtime 启动时间波动 | 改为有界 UI polling/scroll；重跑通过 |
| 首次全量 instrumentation signal 9 | 测试目标进程被系统终止，无 assertion failure | 同用例定向 1/1，随后全量 103/96/7/0 |
| 定向视觉测试后全量出现 `UiAutomationService already registered/connecting` | 前一 instrumentation 的 UI automation 服务未完成断开，权限授予和 runner teardown 被污染 | 擦除并重启专用 AVD，恢复 16 KB/320dp/130% 字号后重跑 |
| 干净 AVD 首次重跑在数据库用例中被系统强杀 | 设备日志显示另一 Gradle instrumentation 同时重装同包并启动 2 项 QR UI 测试，不是数据库断言失败 | 等并发任务退出后在独占 AVD 重跑；103 项启动口径、7 项外部门 skipped、0 assertion failure，Gradle 成功 |
| workspace 停在 Agent Revision marker | 权限交集被等价抽成 helper，validator 仍依赖旧行文本 | 同时断言 helper 调用与交集实现；workspace 28/28 |

## 保留 Gate

- 在至少一台 iPhone 与一台 Android 物理设备上执行真实相机扫码、二维码泄露/并发抢占、重复扫描、过期、响应丢失、进程终止、后台、撤销和真实 LAN。
- 用完整 Xcode 在当前最终 head 执行新增 bootstrap/Keychain XCTest；本机 parse 和 Shared Smoke 不替代 XCTest。
- 若 Beta 要求 Android 作为 client，需要新增 Android 相机扫码→production client transport；当前产品只证明 Android host/iOS client 的非对称角色。
- 用共享账户 Grant/revocation registry 替代 pairing-scoped trust root，并验证跨设备撤销、Space 删除传播和审计汇总。
- 若要求凭据被复制后仍不可使用，需为冻结应用通道设计每次连接的 P-256 proof-of-possession 或硬件不可导出 key 派生；当前 v2 只声明签发时 key-bound。
- 签名、Provisioning、商店、真实用户、真实读屏和发布负责人签收继续 `hold`。

## Verdict

`P0-QR-ONE-TIME-BOOTSTRAP-V2-11` 关闭了仓库实现中“5 分钟 UI/parser 过期被误当作服务端一次性”的 P0 缺口：Release QR bearer 只能用于服务端原子消费的一次性 bootstrap，并轮换为独立、签发时客户端 key-bound 的应用 credential；进程重启与响应丢失有有界恢复路径。由于最终 head iOS XCTest、Android client 扫码、共享 Grant 和双端物理设备/LAN 未完成，仓库工程结论为 `conditional_pass`，封闭 Beta 外部门仍为 `hold`。
