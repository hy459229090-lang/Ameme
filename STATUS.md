# Ameme 状态看板

> 最后更新：2026-07-26
> 事实来源：`JOBS/JOBS-Ameme.md` 与各 Gate 验证材料。

## 当前阶段

- 生命周期：Discovery Gate 仍在补证，M24 双端仓库工程候选已完成；M1–M6 全面研发获批，iOS/Android 的真实本机与固定 Mock 核心闭环、短时 QR→Local Node 受限写入、可复现构建/测试和模拟器/AVD 设备门已形成。
- 当前 Gate：仓库工程候选 `pass`，Gate 1 / 物理设备 Beta / 公开商店发布继续 `hold`。不得把 Simulator/AVD、合成数据、Mock、ADB forward 或静态无障碍检查写成物理真机、真实用户或公开发布通过。
- 产品 MVP：`今天` 为唯一默认主页，单悬浮记录按钮展开文字/语音/照片/导入，右上搜索进入统一历史日流；数据稀疏是默认状态。品牌为简洁、高效、安静、可信、流畅；双端使用原生控件。
- Agent：统一 `ameme-memory` Skill 与本地 MCP Host 已形成六模式、授权内自动读写、撤销、预算控制和 14-case 风险评测。Android 提供同网发现、扫码、账户设备三种统一入口；iOS 可扫描 Android 五分钟 QR envelope 并在用户确认后建立 TLS 1.3/pin/HMAC/Grant-bound Local Node 通道，Swift→Android→SQLCipher→Today 真实 AVD 子门通过。Debug Mock 始终明确不联网，发现候选不会被写成已连接。当前 QR 仍是 pairing-scoped policy，不是共享账户 Grant registry；账户设备服务、共享撤销传播、后台、append/undo/recall、物理设备传输与第三方生产宿主仍未实现。
- 同步/账户：同账户已批准设备在 LAN 内点对点同步；身份服务不保存记忆内容；无用户密钥 UX，也无全设备丢失后的旧数据恢复。
- 工程实现：Android API 36 16 KB arm64 AVD 上 `PAGE_SIZE=16384`、`font_scale=1.3`，本轮 `connectedDebugAndroidTest` 为 67 discovered / 60 passed / 7 显式 Gate skipped / 0 failed；Debug/Release JVM、Lint、APK 和测试 APK 同轮通过。iOS 已有 XcodeGen App、静态 Shared Core、嵌入式 Share Extension、Unit/UI Test targets 与共享 scheme；本地 Shared/App build 和 Shared Smoke 通过，GitHub macOS 15 + Xcode 16.4 完成 App/Extension build、22 Unit + 1 UI Tests 和 4 张当前截图。14/14 workspace gate、iOS project 44、Share inputs 30、iOS accessibility 25、QR contract 32 和治理 143 均 0 error。真实 OpenAI live 按设计跳过；物理设备、签名、生产账户/模型与商店发布不在这些通过结论内。
- 本轮跨端收口：iOS SwiftUI 源码已补齐与 Android 当前进度对应的本机记录、Today/Search/Capture/Event/Settings/Delete 体验；Android 修复搜索设置入口、事件补充 Revision、删除真实执行、空白页小结和不可操作/误导性设计文案。iOS Shared/App 包级源码构建通过；Android `testDebugUnitTest`、`lintDebug`、`assembleDebug` 通过，lint 无阻断项。
- 第八至二十四批双端产品完善：两端均支持不污染真实数据的演示模式、日期范围搜索、Revision、结构化导出、恢复/清理、严格配对与显式断开；完整 Xcode/iOS SDK 已由 CI 接管真实 App/Share Extension build、Unit/UI Tests 与截图附件。本机仍只有 Command Line Tools；iOS/Android 物理设备、真实 TalkBack/VoiceOver、签名/Provisioning 和商店流程未闭合。
- 当前里程碑：M24 状态为 `done`；PR #1 最新实现提交与最终 PR head 的 workspace、iOS、Android CI 已签收，状态文档提交不改变实现范围，PR #1 可人工 review。详见 `docs/quality/M24-双端真实构建与设备交付验证-20260726.md`。

## 发布保留门

- 工程候选已完成；下一阶段只允许在签名账户、双端物理设备和发布负责人到位后进入物理设备 Beta，不得把当前自动化证据扩写成公开发布通过。

| 工作 | 状态 | 下一步 |
|---|---|---|
| 项目工作区与上线工作流 | done | Git 基线 `115690b` 已建立 |
| 总体框架评审 | in_progress | 作为 MVP 讨论基线；体验闭环候选并入本轮设计原则评审 |
| MVP 范围与体验原则 | accepted | 品牌、单悬浮记录入口、双端页面、统一 Skill、六条旅程和指标已接受 |
| MVP 三线并行设计与技术方案 | accepted | 产品、交互、契约、架构、安全、质量和工程计划完成全链审计 |
| MVP 研发前准备 Goal | done | owner/local gap 清零；13 项正式决策已登记（ADR-003 被替代）；Skill/Schema/治理/链接/编译门禁通过，只保留 Spike/外部证据 |
| MVP 工程实现 | done | M24 双端仓库工程候选 pass；物理 iOS/Android、真实读屏、签名/商店、账户共享 Grant、后台、正式用户和生产模型继续作为发布保留门 |
| R0 用户与场景研究 | plan_ready | 按产品决策有意后置 |
| R1 多端信息源调研 | plan_ready | 按产品决策有意后置，后续只做 MVP 定向补证 |
| Event/每日事件记录模型 | plan_ready | v0.4 保留情绪/关系/重要性独立字段，但禁止其提高事实置信度或触发扩权 |
| Prototype 候选评审 | plan_ready | D1–D10 已回填；等待 Gate 1 输入与研发实施证据 |

## 已验证

- Windows 可获得前台窗口元数据和 UI Automation 部分结构；文件事件探针修复竞态后连续 3 次通过 create/change/rename/delete。
- Chrome/Edge Extension、Git 和显式 Capture 是可继续实验的来源。
- UI Automation 覆盖不一致，不能作为统一正文采集方案。
- Chrome `activeTab` 在隔离 Chromium 合成页中完成低权限闭环，跨 origin 后旧授权撤回。
- FORMALdoc 只读快照证明 Git、状态看板、Job 和文件元数据的新鲜度/权威不同；它被定位为专业桌面来源对照，不代表普通用户。
- 全屏/全音频持续采集不进入首版默认路径；Windows Capture 探针仍阻塞。

## 阻塞/待确认

- 首批目标用户已初步选择 AI 使用者，但尚未完成真实访谈验证。
- Prototype 不设用户最小来源门槛；工程夹具需覆盖单来源和数据稀疏日。
- 不含 Git/工作区的通用一天样本尚未建立；正式参与者来源和访谈安排未确认。
- iOS Journaling Suggestions 尚无物理真机证据；Android Picker/Share/Calendar/Voice/Agent/AI 小结当前已有 API 36 16 KB arm64 AVD UI/连接回归，但无物理设备、OEM、后台或物理 LAN 证据。
- Windows 截屏 API 探测仍为 `RuntimeException`。
- instrumentation-only Codex seed 仍保留为测试辅助；新的 paired Host smoke 已不依赖它写 Event，但当前只使用合成事件和 ADB 端口转发，不证明真实 Codex 会话采集、后台运行或物理跨设备传输。
- 产品负责人决策已关闭：LAN/P2P 无用户数据云、账户归属无用户密钥 UX、Health 公开 MVP、结构化导出 P1、M1–M6 全面研发、系统版本、Agent 自动读写、Raw+SourceLocator、单悬浮入口和 Pilot/真机投入均已 accepted。
- 仍待不可替代证据：真实用户、双端真机、SQLCipher 16 KB 与 UI/真机性能、真实 LAN/分布式删除、真实宿主 Skill、真实模型 AI/成本、共享 Grant/QR/账户授权与数据传输、目标市场法律与商店/供应商审核。

## 下一执行门

M24 仓库工程候选为 `pass`：Android 最新本地设备回归为 67 discovered / 60 passed / 7 显式 Gate skipped / 0 failed，Debug/Release 单测、Lint 和 APK 同轮通过；iOS/Android 的短时 QR envelope 已经由 Swift Network.framework→TLS 1.3/pin/HMAC→Grant-bound `create_event`→Android SQLCipher/Today 完成真连接子门；iOS 完成真实 Xcode App/Extension build、22 Unit + 1 UI Tests 与超大字体/深色/旋转截图复核。下一执行门由发布负责人执行双端物理设备、真实 VoiceOver/TalkBack、签名/Provisioning、OEM/来源/后台、物理 LAN、商店申报和回滚清单。Gate 1 与公开发布继续 `hold`。
