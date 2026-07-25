# quality 质量与验证索引

## 当前证据

| 证据 | 路径 | Verdict |
|---|---|---|
| Windows 信息源低风险实测 | `../../research/windows/windows-feasibility-report.md` | 部分通过 |
| Android DB-01 10k/100k AVD 报告 | `../../tests/results/performance/android-db01-api36.json` | 有效基线；两项预注册 DB 延迟门通过 |
| Android MVP 完整体验闭环 | `Android-MVP完整体验闭环验证-20260714.md` | conditional_pass；移动来源/Agent/AI 小结在 API 36 AVD 闭环 |
| Android Agent 统一连接体验 | `Android-Agent统一连接体验验证-20260715.md` | conditional_pass；三入口、统一授权、模拟标识、断开和 Release 隔离通过 |
| Android 16 KB 与 UI 验收 | `Android-16KB-UI验收-20260718.md` | conditional_pass；16 KB AVD、64 项总测试中 57 项可执行回归、外部分享/导出恢复与清理、当前深层页面稳定帧和语义树复核通过，物理设备/完整无障碍待补 |
| iOS Shared Core 验证 | `iOS-Shared-Core验证-20260718.md` | conditional_pass；加密本机闭环、媒体边界、录音启动/清理、SwiftUI accessibility contract、来源搜索、跨端导出 wire value、加密导出恢复/清理、Bonjour 发现与真实/演示连接边界、AccessGrant policy、批量导入回滚/幂等、Share Extension target 输入和 Smoke 通过；完整 Xcode/iOS UI/真机/无障碍待补 |
| 双端体验状态矩阵 | `双端体验状态矩阵-20260718.md` | conditional_pass；共同状态规则、逐页恢复动作、本机/Mock 证据和 AccessGrant 本地 scope 门禁已登记；设备级状态、真实来源、完整无障碍待补 |
| M23 配对与连接生命周期验证 | `M23-配对与连接生命周期验证-20260718.md` | conditional_pass；iOS duplicate-key pairing 拒绝、双端 disconnect 契约、既有 Swift→Android AVD 真实传输子门通过；QR/account/reconnect/设备级门禁待补 |

## 当前正本

| 主题 | 路径 | 状态 |
|---|---|---|
| MVP 测试与 AI 评测 | `MVP测试与AI评测策略.md` | v0.1，层级/夹具/旅程/环境/Gate 候选 |
| 验证报告模板 | `MVP验证报告模板.md` | 当前有效，强制前五行 verdict 与可复现证据 |
| Android DB-01 性能探针 | `Android-DB01性能探针说明.md` | API 36 AVD 有效基线；物理设备/16 KB/UI 仍待验证 |

## 后续范围

- 自动化测试策略、AI 评测、性能、安全、同步、迁移、删除传播、Beta 指标和 Gate 验证报告。

## 变更日志

| 日期 | 操作 | 说明 |
|---|---|---|
| 2026-07-13 | 建立 | 创建质量与验证正式文档域 |
| 2026-07-13 | 新增 | 形成 MVP 测试/AI Eval/性能/安全/环境/Gate 正本和验证报告模板 |
| 2026-07-14 | 新增 | 登记 Android DB-01 10k/100k 性能探针、判定门与待验证边界 |
| 2026-07-14 | 验证 | 固化 API 36 AVD 10k/100k 报告；100k FTS P95 177.72 ms、单次提交 P95 215.67 ms，两项硬门通过 |
| 2026-07-14 | 验证 | 移动来源、配对 Agent TLS 写入、显式结构化 AI 小结和重启持久化形成完整合成体验；真实模型/真机/双端仍保留 |
| 2026-07-15 | 验证 | Android Debug 三种普通用户连接入口收敛到统一授权/成功/断开流程，56 项设备回归 49 通过、7 Gate 跳过、0 失败；真实 NSD/QR/账户仍保留 |
| 2026-07-18 | 新增/验证 | Android 16 KB AVD UI 验收报告；52 项可执行测试通过、7 Gate 跳过，补充截图与 UIAutomator 语义证据；物理设备与 TalkBack 仍待补 |
| 2026-07-18 | 新增/验证 | iOS Shared Core 条件通过报告；补充 Keychain/加密本机闭环、媒体密文、照片引用、批量导入回滚与幂等证据；完整 Xcode/iOS UI/真机/VoiceOver 仍待补 |
| 2026-07-18 | 更新/验证 | 双端产品完善继续收口：iOS 来源标签搜索、录音/日历边界和 VoiceOver 文案，Android 状态卡片 Button 语义；Android AVD 回归为 52 通过、7 跳过、0 失败 |
| 2026-07-18 | 更新/验证 | 双端结构化导出状态值统一为 wire value；iOS Smoke 与 Android 单测通过，完整设备级导出/分享验证仍待补 |
| 2026-07-18 | 更新/验证 | iOS 多词搜索与 Android 逐词 AND 契约对齐；日历导入新增用户选择日历及今天/7 天/31 天范围，App 构建、目标解析和 Shared Smoke 通过 |
| 2026-07-18 | 更新/验证 | Android Fake Repository 与 SQLCipher/LIKE 搜索契约对齐；新增跨字段多词单测，Debug 单测/lint/APK 和 16 KB AVD 回归通过 |
| 2026-07-18 | 更新/验证 | Android 普通本机路径按事件数量解析空白/稀疏/正常状态，显式系统状态优先；新增 ExperienceMode 单测，Debug 单测/lint/APK 和 16 KB AVD 回归通过 |
| 2026-07-18 | 更新/验证 | 双端设置来源状态收口：Android UI smoke 验证照片按次选择、日历权限和系统录音能力文案；iOS App target build/目标 parse 验证 Photos/AVAudioSession/EventKit 状态读取 |
| 2026-07-18 | 更新/验证 | 第十三批主动来源与恢复继续收口：Android ACTION_SEND 先确认再落库、导出失败可重试；iOS 文件导入确认、opaque incoming-share handoff 与重新生成导出入口完成；两端构建/Smoke 通过，真实 iOS Share Extension 与设备级回归仍待补 |
| 2026-07-18 | 新增/验证 | 第十四批跨端状态与恢复矩阵：iOS `ExperienceMode` 与 Android 内容状态规则对齐，新增逐页状态/恢复/证据矩阵；Shared XCTest 源码、Android 单测/AVD 作为设备前基线，真机与完整无障碍仍待补 |
| 2026-07-18 | 新增/验证 | 第十五批真实 iOS 分享扩展输入：Shared Core 统一 App Group 路径，新增 Share Extension 源码/Info.plist/entitlements、取消/重试/重复回调和大内容边界；源码解析、bounded Smoke、App build 与 25 项静态输入治理通过，完整 Xcode target/签名/真机 Share Sheet 仍待补 |
| 2026-07-18 | 新增/验证 | 第十六批双端待处理动作恢复：Android Keystore 加密 pending-action 快照、Activity recreation 确认门和损坏 fail-closed 通过；iOS App Group handoff 增加 pending-ID 启动扫描与 Shared Smoke 断言；Android AVD 全量 55 项可执行通过、7 项跳过，完整设备生命周期仍待补 |
| 2026-07-18 | 更新/验证 | 第十六批无障碍收口：修复 iOS 恢复状态卡片将“重试”与状态文本合并的 VoiceOver 风险，新增 14 项 SwiftUI 静态 accessibility contract 检查；VoiceOver/TalkBack 真机读屏仍待补 |
| 2026-07-18 | 新增/验证 | 第十七批双端结构化导出恢复与隐私清理：iOS 新增 Keychain/AES-GCM 版本化待导出快照、重启恢复与主动清理，Android 设置页补齐未完成导出清理；iOS Shared Smoke、Android 定向 UI smoke 和 16 KB AVD 全量 64/57/7/0 通过，系统 Share Sheet/物理设备仍待补 |
| 2026-07-18 | 新增/验证 | 第十八批双端设备连接体验状态：iOS 新增三入口统一的非敏感连接状态、30 天期限、过期/损坏 fail-closed 和显式断开，与 Android PairingExperience 状态/Mock 授权路径对齐；iOS App build/Shared Smoke 通过，Android 16 KB AVD 全量 64/57/7/0 通过，真实 NSD/二维码/账户设备/LAN/Grant 与物理设备仍待补 |
| 2026-07-18 | 新增/验证 | 第十九批双端真实发现适配：Android Release 接入 NSD `_ameme-agent._tcp.`，iOS Shared 接入 Bonjour/Network.framework 发现会话与本地网络声明；发现只产生非敏感候选，授权/Grant/二维码/账户设备/数据传输仍 fail closed；Android Debug/Release 构建与 AVD、iOS Shared/App build/Smoke/目标解析通过 |
| 2026-07-18 | 新增/验证 | 第二十批双端连接入口对齐：iOS 设置页接入 Bonjour 真实发现、授权前候选和显式演示连接；二维码/账户设备未接入时明确失败，真实候选不会保存为连接；iOS Shared/App build、Smoke/目标解析与 Android 既有 NSD/Mock/AVD 基线通过 |
| 2026-07-18 | 新增/验证 | 第二十一批双端 AccessGrant/policy：iOS Shared 增加 canonical Grant 与本地 policy 绑定，Android pairing 持久化 30 天 Personal/`autonomous_memory`/structured `event` scope 并在 endpoint 前置拒绝扩权；两端授权卡片显示拟授权范围。iOS Shared/App build、Smoke/目标解析、Share 输入 25 项和 accessibility 静态契约 14 项通过；Android Debug/Release JVM、lint、assemble、64/57/7/0 AVD、1/1 授权 UI smoke、TalkBack AVD 语义探针与配对 Host/Android/SQLCipher/Today smoke 通过 |
| 2026-07-18 | 新增/验证 | 第二十二/二十三批连接收口：iOS Local Node TLS 1.3/pin/HMAC client、Grant-bound request 与 Swift→Android AVD 真实传输子门通过；随后补齐 iOS duplicate-key pairing 拒绝和 Android/iOS connector disconnect 契约，iOS App build、Shared Smoke、Android Debug/Release JVM 与 lint 通过；QR/account registry、reconnect 和设备级门禁仍待补 |
