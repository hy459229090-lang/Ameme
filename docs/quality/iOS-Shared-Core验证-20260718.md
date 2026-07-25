# iOS Shared Core 验证（2026-07-18）

- Verdict：`conditional_pass`
- Scope：`AmemeShared` 本机加密事件存储、媒体保护、来源敏感度、日历批量导入一致性、可选日历/范围导入、文本/音频文件导入确认、opaque incoming-share handoff 与启动恢复扫描、加密结构化导出恢复/清理、三入口设备连接体验状态、真实 Bonjour 发现与授权前候选、显式演示连接、AccessGrant/本地 policy 绑定、Android/Python 对齐的 Local Node channel golden、Grant-bound `create_event` request builder、Network.framework TLS 1.3 client、macOS Swift client→Android API 36 16 KB AVD 跨端真实传输子门、Share Extension target 输入、日期/关键词/来源查询、跨端逐词搜索契约、Revision、摘要、跨端 wire value 导出、删除和演示隔离；iOS 录音启动/空数据校验、临时文件与音频会话生命周期、VoiceOver 状态文案源码收口。
- Environment：macOS Command Line Tools；Swift 6.0 toolchain；使用确定性 Smoke 密钥，不触碰真实 Keychain 数据。
- Limitation：当前环境没有完整 Xcode/iOS SDK、XCTest runtime、iOS Simulator 或真机，因此不能把本报告升级为 iOS App/UI/权限/VoiceOver/设备级通过。

## 可复现命令

```sh
swift build --package-path apps/ios --target AmemeShared
swift run --package-path apps/ios AmemeSharedSmoke
swift build --package-path apps/ios --target AmemeApp
swiftc -frontend -parse -target arm64-apple-ios18.0-simulator \
  apps/ios/AmemeApp/AmemeApp.swift \
  apps/ios/Tests/AmemeSharedTests/LocalMemoryStoreTests.swift
swiftc -frontend -parse -target arm64-apple-ios18.0-simulator \
  apps/ios/ShareExtension/ShareViewController.swift
python scripts/validation/validate_ios_share_extension_inputs.py
```

## 结果

| Gate | 结果 | 证据 |
|---|---|---|
| Shared 编译 | pass | `swift build --target AmemeShared` 成功 |
| App 源码构建 | conditional pass | `swift build --target AmemeApp` 通过当前 macOS MacStub；不是 iOS Simulator 构建 |
| iOS 目标源码解析 | pass | `swiftc -frontend -parse -target arm64-apple-ios18.0-simulator` 通过；当前 sysroot 仍为 MacOSX |
| Shared Smoke | pass | `AmemeSharedSmoke` 通过：加密重载、存储恢复、空白/稀疏/正常状态解析、媒体密文、照片不透明引用、范围查询、Revision、摘要、导出、导出恢复快照密文往返/清理、删除、批量导入成功/重复/失败回滚、演示隔离、handoff pending-ID 重启可发现、AccessGrant 最小 scope 与 policy 绑定、Android/Python Local Node channel golden、Grant-bound request builder，以及模拟连接往返/真实发现候选不得晋升为连接 |
| 来源标签与多词搜索 | pass | Smoke 与 XCTest 源码确认事件的 `sourceLabel` 参与关键词搜索，空白分隔词按最多 16 词逐词 AND 匹配，与 Android 搜索契约对齐 |
| 日历选择范围 | conditional pass | iOS 源码已与 Android 对齐为用户选择日历和今天/7 天/31 天范围，再以 `eventkit://` 稳定引用批量提交；当前无 EventKit/Xcode/真机环境，权限与真实日历内容仍待设备验证 |
| 文件导入确认 | conditional pass | 文本/音频文件选择后先在记录页展示文件名、文本预览或音频边界说明；取消不写入事件，确认才调用 Shared Core；App target build/目标 parse 通过，XCUITest 与真实分享扩展仍待完整 Xcode |
| incoming-share handoff | conditional pass | `IncomingShareHandoffStore` 只生成含 UUID 的 `ameme://incoming-share/<id>` URL，正文/文件留在共享目录；`pendingIDs()` 允许 App 启动扫描未完成 handoff，损坏记录 fail closed；App target build、目标 parse、Shared Smoke 和 XCTest 源码覆盖文本/文件回读、大小边界与删除；真实 App Group/Share Extension target 仍待 Xcode |
| Share Extension target 输入 | conditional pass | `ShareViewController.swift`、Share Extension `Info.plist`、App/Extension entitlements 已就位；静态输入治理 25 项通过，扩展源码和 App 目标解析通过；完整 Xcode target、签名、App Group 运行时和 Share Sheet 真机路径仍待补 |
| SwiftUI accessibility contract | conditional pass | 静态契约覆盖搜索/设置/日期/记录/编辑/删除/录音/恢复标签、事件行、删除进度和恢复状态中的独立重试动作；当前工具链只能证明源码契约，VoiceOver 手势、焦点和 Dynamic Type 仍待设备 |
| 导出 wire value | pass | iOS Smoke/XCTest 与 Android 单测确认状态/事件类型/证据状态输出契约值，不暴露 Swift lowerCamel 或 Kotlin enum 名 |
| 导出恢复快照 | conditional pass | `PendingExportStore` 使用 Keychain 密钥、AES-GCM AAD、版本化 envelope、原子替换、64 MB 上限和损坏 fail closed；Smoke 验证磁盘不含导出正文、往返和清理；App 设置提供恢复/清除；系统 Share Sheet 中断、完整 Xcode 和真机文件提供者仍待补 |
| 设备连接体验状态 | conditional pass | `AgentConnectionMethod`/`AgentExperienceConnection` 统一三种入口和非敏感展示字段；`AgentExperienceConnector` 将 Bonjour 真实发现、授权前候选和显式 `SimulatedAgentExperienceConnector` 分层；注入 `AgentLocalNodeNetworkClient` 后才允许真实连接状态落库；`AgentExperienceStore` 覆盖版本、能力、30 天期限、过期清理、损坏清理；二维码/账户设备/共享 registry、真实 host 和真机发现仍未接入 |
| AccessGrant 与本地策略 | conditional pass | `AgentAccessGrant`/`AgentAccessGrantPolicy` 对齐 canonical schema，Shared Smoke 与 XCTest 源码覆盖 caller/Grant 绑定、最小 scope、policy bind、过期和撤销；`AgentLocalNodeChannelCodec` 逐字通过 Android/Python golden，`buildCreateEventRequest` 在序列化前做 Grant scope gate，Network.framework client 做 TLS pin/HMAC/session/frame 校验；共享 registry、撤销传播和设备级传输仍待接入 |
| Swift→Android AVD Local Node 实连 | pass（跨端子门） | `smoke_ios_network_to_android.py` 临时 provision Android pairing，运行 `AmemeLocalNodeSmoke` 完成 Swift Network.framework → TLS 1.3/certificate pin/HMAC → Grant-bound `create_event` → Android SQLCipher，并确认事件出现在 Today；配对 secret、ADB forward、临时 `.build` 在 finally 清理；这不是 iOS Simulator/真机或物理设备证据 |
| 录音资源与保存边界 | conditional pass | `VoiceRecorder` 只有真实启动才进入录音状态，空录音不保存；结束/取消/页面离开均进入统一清理路径，删除临时 `.m4a` 并释放 iOS 音频会话；已由 App 目标编译和源码解析验证，真实麦克风生命周期仍待设备 |
| XCTest/XCUITest | 待补 | 当前 Command Line Tools 报 `no such module 'XCTest'`；完整 Xcode 后需补真实单测、UI、权限拒绝/撤销和恢复 |

## 数据与体验结论

- 日历导入先构造 `MemoryEventDraft`，按稳定 `eventkit://` 引用批量写入；重复引用不产生重复事件，写入失败不会留下部分事件。
- 照片默认只保存 Photos 不透明引用；音频恢复文件使用 AES-GCM 密文；照片、音频、日历主动来源标记为 `confidential`。
- 本机密钥通过 Keychain 实现；Smoke 使用注入密钥只验证存储行为，不证明真实 Keychain 权限、迁移或设备丢失恢复。
- 该报告支持 Shared Core 条件通过，不替代 iOS Simulator/真机、VoiceOver、Dynamic Type、旋转、权限撤销、进程回收和性能证据。
