# Ameme 状态看板

> 最后更新：2026-07-18
> 事实来源：`JOBS/JOBS-Ameme.md` 与各 Gate 验证材料。

## 当前阶段

- 生命周期：Discovery Gate 仍在补证，MVP 工程实现进行中；M1–M6 全面研发获批，当前已集成契约、参考 Core/Raw/队列、同步、R0 AI/固定评测，以及 Android 加密 Local Event Node、显式来源、配对 Agent 写入和结构化今日小结。
- 当前 Gate：Gate 1 `hold`。产品主链已改为 DayLedger 事件覆盖优先；桌面 Spike 和 FORMALdoc 只能证明专业来源，通用来源与正式用户证据仍不足。
- 产品 MVP：`今天` 为唯一默认主页，单悬浮记录按钮展开文字/语音/照片/导入，右上搜索进入统一历史日流；数据稀疏是默认状态。品牌为简洁、高效、安静、可信、流畅；双端使用原生控件。
- Agent：统一 `ameme-memory` Skill 与本地 MCP Host 已形成六模式、授权内自动读写、撤销、预算控制和 14-case 风险评测。Android 普通用户连接已提供同网发现、扫码、账户设备三种统一入口；Debug 保留明确不联网的模拟路径，Release 已接入真实 NSD 发现适配器但发现后授权仍 fail closed。iOS 设置页现在将同一局域网入口接到 Bonjour/Network.framework 发现，将二维码/账户设备错误与显式演示连接分开；两端都不把发现候选写成已连接。M21 新增双端 AccessGrant/本地批准策略模型，Android 配对恢复与 `create_event` 写入前会校验 caller/Grant、purpose、space、data type、期限和撤销；M22 新增 iOS 与 Android/Python golden 一致的 Local Node 编解码、Grant-bound `create_event` builder 和 TLS 1.3/pin/HMAC Network.framework client 边界；这仍是配对范围策略，不是共享账户 Grant registry。真实 TLS 1.3/certificate pin/HMAC listener、Keystore 凭据和 SQLCipher 持久幂等仍由 Android Debug 开发者路径验证；API 36 AVD 的真实纵向 smoke 证明 Host 写入 Today、重启保留且 ADB 不注入 Event。二维码/账户设备服务、共享 Grant registry、后台、append/undo/recall、真实 iOS 设备传输与第三方宿主仍未实现。
- 同步/账户：同账户已批准设备在 LAN 内点对点同步；身份服务不保存记忆内容；无用户密钥 UX，也无全设备丢失后的旧数据恢复。
- 工程实现：Android API 36 16 KB arm64 AVD 上确认页大小 16,384，最新 `connectedDebugAndroidTest` 为 64 项总测试中 57 项可执行测试通过、7 项显式 Gate 跳过、0 失败；Debug/Release JVM、lint、assemble 以及 M21 授权范围 UI 定向 smoke（1/1）均通过。新增真实本机↔演示数据 UI 往返、按钮/事件行语义断言、设置来源权限/能力状态断言、外部分享先确认再落库、导出失败恢复、Activity recreation 待处理分享恢复和待导出快照清理断言。新增 Android Keystore 加密 pending-action 快照、损坏 fail-closed、独立清理和仓库恢复前禁用确认。新增三入口选择、统一授权/成功/断开、非敏感状态 fail closed 和 Release provider 为空的测试；M21 新增 AccessGrant scope/期限/撤销与 Android endpoint 集成测试并已真实编译执行。iOS 新增受限 opaque incoming-share handoff、App 侧确认页和可装配的 Share Extension 源码/Info.plist/双端 App Group entitlements；App Group handoff 目录现在可在 App 启动时扫描未完成 UUID，Shared Smoke 验证文本回读、URL 不泄漏正文、清理、重启可发现和 16,384/32 MB 边界；结构化导出现在有 Keychain/AES-GCM 加密恢复快照和显式清理入口；设备连接体验现在用结构化非敏感状态保存方式、设备、Agent、能力和期限，过期/损坏自动断开；最新 iOS Shared/App build、Smoke、目标源码解析也覆盖 AccessGrant policy 绑定。真实 Xcode target、签名和系统分享页仍未证明。另有 DB-01 有效性能报告、移动来源体验包、Android→本地网关 1/1 和配对 Agent E2E。覆盖 SQLCipher/Keystore/WAL/v1→v6/space/Revision、Photo/Share/Calendar/Voice、FTS/LIKE/keyset、DayLedger/Summary、持久 Agent 幂等、TLS 配对和 10k/100k 容量。真实 OpenAI live 因无密钥/显式开关按设计跳过；这些证据仍不等于双端真机、真实三种连接、生产 Raw/LAN/后台或发布通过。
- 本轮跨端收口：iOS SwiftUI 源码已补齐与 Android 当前进度对应的本机记录、Today/Search/Capture/Event/Settings/Delete 体验；Android 修复搜索设置入口、事件补充 Revision、删除真实执行、空白页小结和不可操作/误导性设计文案。iOS Shared/App 包级源码构建通过；Android `testDebugUnitTest`、`lintDebug`、`assembleDebug` 通过，lint 无阻断项。
- 第八/九/十批双端产品完善：两端均已支持不污染真实数据的演示模式、开始/结束日期范围搜索、待核验/计划状态 Revision 更新和结构化 JSON 导出；iOS 增加现代日历/麦克风授权反馈、失败不自动关闭记录入口、存储恢复重试、照片引用不复制原图和音频恢复文件加密，并将日历导入收敛为稳定引用、批量事务和重复幂等；Android 演示模式可从设置切换 Fake Repository，并补齐系统录音 Intent 的 package-visibility 声明和搜索失败重试。照片、音频、日历主动来源在双端统一为 confidential，Android 照片状态与 iOS 统一为已记录。Android 已在 API 36 16 KB AVD 完成 APK/UI/连接回归与截图语义审查；iOS 共享核心 smoke 另已验证测试密钥下的加密重载、媒体密文、照片引用、范围查询、存储失败恢复、批量导入回滚和演示隔离。完整 Xcode/iOS SDK、iOS 设备与 Android 物理设备仍待补，TalkBack/VoiceOver 设备级无障碍证据未闭合。详见 `docs/quality/Android-16KB-UI验收-20260718.md` 与 `docs/quality/iOS-Shared-Core验证-20260718.md`。
- 当前里程碑：第十/十一/十二/十三/十四/十五/十六/十七/十八/十九/二十/二十一/二十二批双端产品完善、隐私和设备交付里程碑均已立项并进行中；本轮新增双端 AccessGrant policy、Android 配对本地 scope gate、endpoint 拒绝扩权、两端授权范围展示、iOS Local Node 协议/真实客户端边界，并通过 Swift→Android API 36 16 KB AVD 的真实 TLS/HMAC/Grant-bound Today 子门；继续保持 Android NSD、iOS Bonjour、双端状态/恢复矩阵与证据边界。当前仍缺完整 Xcode/simctl、物理 Android 设备、真实 QR/账户 Grant registry、iOS 真机传输与设备级无障碍证据。

## 进行中

- M23 配对与连接生命周期：iOS pairing material 已先行拒绝 duplicate JSON key；Android/iOS connector 均有显式 disconnect，清理展示状态前会通知连接实现。当前工具链回归通过；QR envelope、账户 Grant registry、重启 reconnect、物理设备和设备级无障碍仍待补。

| 工作 | 状态 | 下一步 |
|---|---|---|
| 项目工作区与上线工作流 | done | Git 基线 `115690b` 已建立 |
| 总体框架评审 | in_progress | 作为 MVP 讨论基线；体验闭环候选并入本轮设计原则评审 |
| MVP 范围与体验原则 | accepted | 品牌、单悬浮记录入口、双端页面、统一 Skill、六条旅程和指标已接受 |
| MVP 三线并行设计与技术方案 | accepted | 产品、交互、契约、架构、安全、质量和工程计划完成全链审计 |
| MVP 研发前准备 Goal | done | owner/local gap 清零；13 项正式决策已登记（ADR-003 被替代）；Skill/Schema/治理/链接/编译门禁通过，只保留 Spike/外部证据 |
| MVP 工程实现 | in_progress | 第八批双端源码已完成；第九批 Android 16 KB AVD/APK/UI/连接回归子门通过，第二十批已对齐 iOS 真实/演示连接入口，第二十一批 AccessGrant policy 与 Android 本地 scope gate 已完成 Android 构建/AVD复验，仍需 iOS Xcode/真机、Android 物理设备、完整无障碍和真实 QR/账户、共享 Grant、后台、真实模型 |
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
- iOS Journaling Suggestions 尚无 Mac/真机证据；Android Picker/Share/Calendar/Voice/Agent/AI 小结当前已有 API 36 x86_64 既有回归与 API 36 16 KB arm64 AVD UI/连接回归，但无物理设备、OEM、后台或真实 LAN 证据。
- Windows 截屏 API 探测仍为 `RuntimeException`。
- instrumentation-only Codex seed 仍保留为测试辅助；新的 paired Host smoke 已不依赖它写 Event，但当前只使用合成事件和 ADB 端口转发，不证明真实 Codex 会话采集、后台运行或物理跨设备传输。
- 产品负责人决策已关闭：LAN/P2P 无用户数据云、账户归属无用户密钥 UX、Health 公开 MVP、结构化导出 P1、M1–M6 全面研发、系统版本、Agent 自动读写、Raw+SourceLocator、单悬浮入口和 Pilot/真机投入均已 accepted。
- 仍待不可替代证据：真实用户、双端真机、SQLCipher 16 KB 与 UI/真机性能、真实 LAN/分布式删除、真实宿主 Skill、真实模型 AI/成本、共享 Grant/QR/账户授权与数据传输、目标市场法律与商店/供应商审核。

## 下一执行门

第六批 Android 合成完整体验与普通用户统一连接体验均为 `conditional_pass`：Share/Picker/Calendar/Voice 正式入口可测，配对 Host 经 TLS/HMAC 写入 SQLCipher，用户同意后可生成结构化今日小结；普通用户 Debug 可用同网发现、扫码、账户设备三入口完成统一授权、体验成功和断开。Android 最新 AVD 回归为 57 项可执行通过、7 个显式 Gate 跳过、0 失败，Release 已接入真实 NSD 但发现后授权仍 fail closed；iOS 设置页同样只将 Bonjour 发现展示为候选，演示连接必须显式选择。下一步优先实现二维码/账户设备证明、共享 Grant、证书/会话绑定和真实双端数据传输，再做物理设备/16 KB/OEM、后台、真实模型；Gate 1 继续 `hold`，不能将 Mock 或发现体验写成真实联网、数据传输、用户价值或公开发布已验证。
