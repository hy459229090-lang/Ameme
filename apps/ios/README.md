# Ameme iOS

Ameme 的 iOS 原生 MVP，使用 SwiftUI、iOS 18+ 原生控件和本地优先存储。

## 当前实现

- SwiftUI 原生导航壳：首次引导、今天、历史搜索、事件详情、设置和删除进度
- 单一悬浮“记录”入口：文字、照片、语音、文本/音频文件和主动日历导入
- 文本/音频文件导入先进入确认预览；取消不会创建事件，确认前不写入本机事件链。真实 iOS Share Extension 已接入可复现的 Xcode App/Extension target；系统 Share Sheet 的最终签名与真机证据仍作为发布门禁，不会把文件选择器冒充为系统分享扩展
- iOS App 已提供安全的 incoming-share handoff：Share Extension 可将受控 JSON/文件写入 App Group 后打开只含 UUID 的 `ameme://incoming-share/<id>` URL，App 再展示同一确认页；正文和文件不会放进 URL。App 启动还会扫描共享目录中未完成的 UUID，在 URL 交接后被系统终止时恢复同一确认页；损坏 handoff fail closed，不触碰本机事件。App/Extension 已共享同一 App Group entitlement 并由 `Ameme.xcodeproj` 嵌入；签名团队和真机 Share Sheet 仍需发布设备验收
- `ShareExtension/` 包含真实 `ShareViewController`、Share Extension `Info.plist`、App/Extension App Group entitlements 和接入说明；源码覆盖文字/URL、图片、PDF、取消、重试、重复生命周期、空内容、不支持类型和 16,384/32 MB 边界。XcodeGen 2.46.0 会从 `project.yml` 重建含 App、Extension、Unit Tests 与 UI Tests 的完整工程，CI 会拒绝生成漂移
- 今天日流的空白、稀疏、本机可用、来源异常等状态；小结位于事件流末尾
- 空白/稀疏/正常状态按与 Android 相同的本机事件数量规则解析；加载和可恢复错误优先保留，不把受限来源写成空库
- 历史搜索的关键词与开始/结束日期范围筛选，结果按日期分组并显示“仅本机”范围；关键词同时匹配标题、正文、来源标签和用户补充，空白分隔的多个词按 Android 同样的逐词 AND 规则搜索
- 历史搜索支持开始/结束日期范围筛选，结果仍保持同一条按日分组的日流
- 事件补充、来源/状态说明和删除影响；删除不会伪造未接入设备的传播确认
- 待核验/计划事件支持确认“已发生”或保留为计划，并写入新的 Revision
- 设置可载入固定演示数据；演示数据不写入真实本机加密库，便于完整体验和验收
- 设置可生成并分享结构化 JSON 导出；受限事件与原始媒体文件默认不包含
- 结构化导出在等待系统 Share Sheet 时会先保存为 Keychain/AES-GCM 加密恢复快照；App 重启后可重新分享同一快照，也可以在设置中主动清除；快照不写入导出正文明文，不修改本机事件
- 结构化 JSON 导出的状态、事件类型和证据状态使用跨端统一 wire value，便于 Android/iOS 互相消费
- 设置中的来源边界、隐私说明和 Agent 三入口连接体验；同一局域网入口调用 `_ameme-agent._tcp` 的 Bonjour/Network.framework 发现并展示授权前候选，二维码入口可扫描 Android 端 5 分钟配对码并在授权确认后建立 TLS 1.3/HMAC 通道，账户设备未接入时明确失败；只有显式“试用演示连接（不联网）”才保存模拟状态，真实发现不会自动授权
- Agent 三入口统一保存为受限的非敏感体验连接状态（设备、Agent、能力、方式、30 天期限和模拟标记），损坏或过期状态自动断开；密钥、端点、证书 pin、正文和真实网络连接不进入该状态
- `AgentAccessGrant`/`AgentAccessGrantPolicy` 与 canonical AccessGrant 对齐 caller/Grant 绑定、purpose、Personal space、structured event、期限和撤销语义；`AgentLocalNodeChannelCodec` 与 Android/Python golden 逐字一致，`AgentLocalNodeNetworkClient` 提供 TLS 1.3、证书 pin、HMAC session/frame、序列/nonce 校验和 Grant-bound `create_event` builder。设置授权卡片显示拟授权范围，Shared Smoke 与 XCTest 源码验证协议、最小 scope、policy 绑定、过期和撤销；共享 Grant registry、真实 advertiser/host 和设备级传输仍待补
- 配对材料解析先经过严格 JSON grammar/duplicate-key 检查，再进入 Foundation；Agent connector 断开会显式关闭 active client/channel 并随后清除展示状态，避免 malformed pairing 或“只保存元数据”被误报为活跃 session。非模拟连接不会在进程重启后仅凭展示元数据恢复为“已连接”；账户设备 registry 和安全重连协议仍是后续门禁
- 设置中的来源状态读取 Photos、AVAudioSession 和 EventKit 原生权限；明确按次照片选择、录音入口和日历只读权限，拒绝后仍可使用文字与文件导入
- 使用 Keychain 保存 AES-GCM 密钥；事件 JSON 与音频恢复文件在 Application Support 中加密，照片默认只保存 Photos 不透明引用、不复制系统原图
- 只有录音真实启动且产生非空内容才会保存；结束、取消或离开录音页都会删除临时音频并释放音频会话，再交给 Shared Core 加密保存
- 日历导入使用 EventKit 稳定引用；用户主动选择可读日历和今天/7 天/31 天范围后按批次一次提交，重复导入跳过已存在引用，任何本机持久化失败都会回滚整批计划
- Dynamic Type、VoiceOver 标签、系统字体、原生 Sheet、系统选择器和减少动态效果友好控件
- `AmemeSharedSmoke` 可在当前 macOS Command Line Tools 环境验证本机记录、空白/稀疏/正常状态、关键词/范围查询面、Revision、小结、结构化导出恢复快照（密文往返/清理）、删除、批量导入回滚、演示数据隔离和 opaque incoming-share handoff；它不替代 iOS UI/真机验收
- `AmemeLocalNodeSmoke` 配合 Android API 36 16 KB AVD 可验证 Swift Network.framework client → Android Local Node 的 TLS 1.3、certificate pin、HMAC session/frame、Grant-bound `create_event` 和 Today 可见性；这是跨端真实传输子门，不替代 iOS Simulator/真机与物理设备验收

## 打开与运行

在安装完整 Xcode 16.4+ / iOS 18.5 SDK 的 Mac 上打开已生成的工程，选择共享 `Ameme` scheme 运行或测试：

```text
apps/ios/Ameme.xcodeproj
```

目标设备：iOS 18+，Bundle ID：`com.ameme.ios`。

工程源由 XcodeGen 2.46.0 固定在 `project.yml`；修改 target 后先重建并确认生成文件没有漂移：

```bash
xcodegen generate --spec apps/ios/project.yml
git diff --exit-code -- apps/ios/Ameme.xcodeproj
```

真实构建与测试：

```bash
xcodebuild \
  -project apps/ios/Ameme.xcodeproj \
  -scheme Ameme \
  -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO \
  build
```

仓库的 iOS GitHub Actions 会在 iOS 18.5 Simulator、深色模式和无障碍超大字体下执行单元/UI 测试，保存当前运行截图到 `.xcresult`。本地环境若只有 Command Line Tools，仍不能把 Shared Smoke 当作完整 Xcode、Simulator、签名或真机证据。

在没有完整 XCTest runtime 的当前环境，可先运行共享核心 smoke gate：

```bash
swift run --package-path apps/ios AmemeSharedSmoke
```

跨端 Swift→Android AVD smoke（需要已构建 Android APK、ADB 设备和固定 SDK 路径）：

```bash
env PYTHONPATH=/tmp/ameme-python312-deps \
  /Users/riche/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/bin/python3 \
  scripts/dev/agent/smoke_ios_network_to_android.py \
  --adb /tmp/ameme-android-sdk/platform-tools/adb \
  --serial emulator-5554
```

该 smoke 使用临时注入的测试密钥验证加密事件写入/重载、日期范围查询、Revision、导出、删除和演示隔离；它不替代 iOS 真机上的 Keychain、权限、VoiceOver 或 XCUITest 证据。

当前仅 Command Line Tools 的机器上，`swift test --package-path apps/ios` 会因不提供 `XCTest` 模块而失败；这属于本地环境证据缺口，不把失败改写为测试通过。完整 XCTest 由 `Ameme.xcodeproj` 的 CI/设备门禁执行。

## 数据边界

本地事件、来源定位和用户补充只在主动操作后写入本机。系统权限仅在用户实际点击照片、录音、日历或文件导入时请求。当前 iOS 端可通过 Android 短时二维码建立真实 Local Node client 通道；真实 LAN advertiser/host、账户设备 registry、共享 Grant 撤销传播、HealthKit 适配器和 AI 网关仍未接入，设置页会明确显示这些能力的状态，不伪造连接成功。

不要把真实照片、音频、日历、健康数据或密钥提交到仓库。
