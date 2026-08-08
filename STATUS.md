# Ameme 状态看板

> 最后更新：2026-08-08
> 事实来源：`JOBS/JOBS-Ameme.md` 与各 Gate 验证材料。

## 当前阶段

- 生命周期：Discovery Gate 仍在补证，M24 双端仓库工程候选已完成；M1–M6 全面研发获批，iOS/Android 的真实本机与固定 Mock 核心闭环、一次性 QR v2 bootstrap→独立凭据→Local Node 受限通道、可复现构建/测试和 Simulator/AVD 设备门已形成。
- 当前 Gate：仓库工程候选 `pass`，Gate 1 / 物理设备 Beta / 公开商店发布继续 `hold`。不得把 Simulator/AVD、合成数据、Mock、ADB forward 或静态无障碍检查写成物理真机、真实用户或公开发布通过；2026-08-08 已补充完成仓库级基线复核：在 Python 3.12 下复跑 `run_workspace_validation.py`，合同、运行时、AI fixed eval 与治理门全部通过；外部真实参与者、物理设备、签名发布、真实模型与跨设备恢复仍为 `hold`。本轮外部门执行包初始化已复跑完成（`run-id=demo-20260808e`，`data/private/external-gates/demo-20260808e`，`overall_verdict=hold`，claim 均 `false`）；本轮尝试补跑 Android 本机 JVM/测试命令失败于环境缺少 Java Runtime，按透明失败日志进入待补外部依赖项清单。
- 产品 MVP：`今天` 为唯一默认主页，单悬浮记录按钮展开文字/语音/照片/导入，右上搜索进入统一历史日流；数据稀疏是默认状态。品牌为简洁、高效、安静、可信、流畅；双端使用原生控件。
- Agent：统一 `ameme-memory` Skill 与本地 MCP Host 已形成六模式、授权内自动读写、撤销、预算控制和 16-case / 15-risk 评测；Event/Revision 自动写入不再等同于确认长期 Memory。Android 生产 Local Node 已在冻结 v1 上实现 `create_event`、`append_revision`、exact `undo_capture` 与 bounded `visible_events`：新配对明示 Personal/structured Event 读取和 Event/Revision 写入/撤销，缺少 operation policy 的旧 pairing fail closed；读取只走 active Event current projection，query≤1,000、limit≤100、title/description≤240/1,000，生产 runtime 排除 Restricted，且不返回 user words/source/locator/Raw。Host `recall/get_context` 继续执行 injection 和 item/token budget；`get_event`、策略写入与长期 Memory 确认关闭。Event/Revision 撤销仍保持 tombstone/compensation、10 分钟首次时窗和 SQLCipher 幂等重放。iOS 生产客户端现已补齐四项 operation 的 canonical builder、最小 Grant scope、握手 capability 检查、typed result 与 result-digest/error-shape fail-closed；原始 application exchange 已收为私有，生产 exchange 只能按当前时间授权，避免绕过 typed Grant/operation 检查。普通用户 QR 默认仍为 event-only，不静默增加读取/Revision/撤销。本轮在 API 36 / 16 KB arm64 AVD 上完成 Swift Network.framework→Android SQLCipher→Today 四操作纵向 Smoke：create→bounded read→Revision→Revision exact undo→Event exact undo→final read 全部通过；真实 ContextPack、共享账户 Grant、后台、物理 LAN/设备与第三方生产宿主仍未证明。
- QR 配对安全 Gate：Release 已切换到 `ameme.agent-pairing-bootstrap.v2`。Android 持久包裹 5 分钟 bootstrap，校验 HMAC + P-256 client-key possession 后原子标记 consumed，并签发与 QR secret 分离的 30 天应用 channel credential；响应丢失只允许同 key 在有界 receipt 窗内重取同一凭据。Android Release 现以用户主动触发的 Google Play 系统 QR-only scanner 接收有界 payload，不声明 App camera permission；无系统扫码服务时也可由用户显式粘贴完整配对码，App 不读取剪贴板、限制 16,384 字符且取消/提交即清除。两条入口都经过同一严格 QR v2 parser 和 event-only 确认；确认后才以 Keystore 非导出 P-256 key、TLS 1.3 certificate pin 签发独立 credential，并在应用 HMAC 认证和 `create_event` capability 成功后显示连接。pending/active 由独立 Keystore AES-GCM key 包裹在 `noBackupFilesDir`，启动只在实际重连后恢复；本机删除、断开、过期和损坏均 fail closed。实现中同时把 Local Node TLS server KeyManager 钉死到专用 RSA alias，避免与共存的客户端 P-256 key 跨域误选。iOS 继续以 `WhenUnlockedThisDeviceOnly` Keychain 保存 pending/active。冻结 v1 应用通道仍使用 bearer，并未在每次重连重复 P-256 proof。QR 静态门 62/62、API 36 / 16 KB AVD 完整 115/108/7/0 和真实 Keystore→TLS bootstrap→应用认证 loopback 通过；物理光学扫码、无 Play 真机显式粘贴、真实 LAN/后台、共享 Grant registry 与真实用户仍为 `hold`。
- Agent 访问审计：Android 当前 SQLCipher schema v14 已把生产 Local Node 的全部启用操作接入 content-free `STARTED/COMPLETED` 审计，记录 caller、purpose、operation、Space/数据类型范围、结果、对象数分桶与时间，不保存正文、query、payload、对象 ID、路径、secret、Grant 或异常文本；审计写失败时操作 fail closed，保留期为 180 天，设置页只读展示最近 20 条且没有合成回退。同安装恢复现把旧 live 在激活时未过期的审计与候选账本单调 union，先原子清理候选过期行，再做精确重复去重；同 ID/trace-phase 冲突、非法记录或 50,000 行未过期容量溢出均在换库前失败关闭，并以账本/合并 SQLCipher 双摘要绑定 staging 与新 live。当前 Debug JVM 112/112、Lint、Debug/unsigned Release APK、androidTest 编译、审计静态契约 146 与恢复 210 通过；API 36 / 16 KB AVD 已实际执行 v13→v14、append-only/TTL、端点、设置页、恢复 union/sidecar 和普通用户恢复切换场景。iOS Host、账户级汇总、安全导出、真实用户与物理设备仍未完成。
- 同步/账户：同账户已批准设备在 LAN 内点对点同步；身份服务不保存记忆内容；无用户密钥 UX，也无全设备丢失后的旧数据恢复。
- 工程实现：Android 已在 API 36、16 KB、320dp、130% 字号 AVD 上完成当前全量 `connectedDebugAndroidTest`：XML 精确结果为 115 discovered / 108 passed / 7 显式外部门 skipped / 0 failed；系统扫码、显式粘贴、Manifest/权限、Keystore/TLS、恢复、Agent 审计、生产端点、删除与完整真实/Mock UI 继续回归。Debug/Release JVM 均为 122/122，Lint、Debug/Release 与 androidTest 编译通过；10 张指定视觉证据由 CI 精确校验并逐张复核，记录面板与事件详情截图在窗口稳定后抓取。隔离 Python 3.12 workspace 28/28、Markdown 45 条链接、治理 185 项和 QR 62/62 通过。实现/证据提交收口到 `8291ced`；最终 runs 为 workspace `31246698198`、iOS `31246698219`、Android `31246698217`，三门全绿。iOS 的 App + Share Extension 构建、71 项 Unit、普通字号 Mock/真实本机 UI 2/2、深色 XXXL UI 1/1 与 15 张截图通过。本机仍只有 Command Line Tools，因此本地 `swift test` 缺 `XCTest`；完整 XCTest/XCUITest 证据来自上述 Xcode 26.6 / iOS 26.5 CI。这些结果证明仓库、Simulator/AVD 与本机路径，不替代物理设备、真实 VoiceOver/TalkBack、签名、生产账户/模型或商店发布。
- P0 生产 Coverage、长期 Memory、本机恢复、复用与删除：双端已有 Coverage v1 编译/状态/密文持久化和显式 Candidate→Event 接受；保存 Coverage 不创建 Event，Event 删除后 link detach 且 Candidate 不复活。进一步加入 9 类 Event-revision-bound 长期 Memory、删除水位、四类复用、SourceObject→Event lineage、exact-revision 字段证据、独立用户确认 provenance 与当前安装 Personal space 根冻结；完整确认可在删源后保留 Event 但终结来源 claim，partial/stale/legacy fail closed。本轮把同安装恢复接到双端设置页，并补齐双端真实/Mock 复用入口、结果、content-free helpful feedback 与删除闭环。Android 全量 115/108/7/0 与 iOS 最终 head Xcode/XCUITest 已签收；该 UI 仍依赖当前安装设备 key，artifact/receipt 为 `production_recovery_claim=false`。真实参与者 helpful outcome、account/Grant/peer/分布式删除、跨设备 key recovery、卸载/设备丢失、物理设备和真实读屏仍为独立 Gate，Verdict 保持 `conditional_pass`。
- Android 当前存储版本：上段的 schema v13 是用户确认 provenance 的引入版本；Android 当前 schema 已随 Agent 访问审计升级为 v14，iOS 当前 envelope 仍为 v8。v13→v14 迁移只新建 content-free 审计结构，不推断或补造历史访问记录。
- 本轮同安装恢复：双端授权精确绑定 candidate backup ID、有效期不超过 10 分钟，并在 live-store 切换前后复核密文、关系、媒体和调用时权威删除水位；原候选不被消费。Android 的认证 `PREPARED` journal 覆盖 staging/审计合并崩溃窗口，并单调保全旧 live 未过期 Agent audit；iOS 同步冻结 store 并重写 app-owned media locator。双端新增有界 `current/previous/candidate` 恢复点管理器，发布新点前保留旧点，只有实际隔离恢复成功才显示健康；设置页展示创建/验证/最近成功并要求逐字 `恢复`。Android AVD 全量 105/98/7/0、恢复 6/6、UI 14/14；iOS Debug/Release build 与生产 Smoke 通过，本机 XCTest 缺失。artifact/receipt 继续硬编码 `production_recovery_claim=false`；用户自有存储或 E2EE 路线、recovery secret、跨设备 key、卸载/设备丢失、真实进程/断电故障和物理恢复继续 `hold`。
- 本轮跨端收口：iOS Search 在“第一张图”的内容优先方向上进一步减法，只保留一个扁平复用入口，使用原生 `confirmationDialog` 与结果 Sheet；iOS 26 Liquid Glass 只用于系统控制面，内容不被装饰性玻璃包裹。Android Search 复盘后确认采用当前稳定 Material 3，不复制 iOS 玻璃；同时把固定顶部 + 独立结果滚动改为单一 `LazyColumn`，解决 320dp/130% 字号空态下复用入口不可达的问题，并用等宽日期控件、短标签和原生 `AlertDialog` 完成设备复验。Mock Repository 现同样实现四类复用、15 分钟引用复核、结果解析和无正文 helpful feedback，真实本机与 Mock 均可完成闭环。最终 head 的 iOS Xcode/XCUITest/截图与 Android API 36/16 KB/130% 设备回归、10 张逐图证据均已签收。
- 第八至二十四批双端产品完善：两端均支持不污染真实数据的演示模式、日期范围搜索、Revision、结构化导出、恢复/清理、严格配对与显式断开；完整 Xcode/iOS SDK 已由 CI 接管真实 App/Share Extension build、Unit/UI Tests 与截图附件。本机仍只有 Command Line Tools；iOS/Android 物理设备、真实 TalkBack/VoiceOver、签名/Provisioning 和商店流程未闭合。
- 当前里程碑：M24 状态为 `done`；PR #1 最新实现提交与最终 PR head 的 workspace、iOS、Android CI 已签收，状态文档提交不改变实现范围，PR #1 可人工 review。详见 `docs/quality/M24-双端真实构建与设备交付验证-20260726.md`。
- M25 平台视觉复验：状态为 `done`，仓库工程候选 `pass`。iOS Today 已按选定方向收敛为内容优先单列时间流，iOS 26+ 使用原生 Liquid Glass 控制面、iOS 18–25 使用系统 Material 降级；Android 保持 Compose BOM `2026.06.00` / Material 3 `1.4.0` 稳定生产基线，并迁移官方 Material Symbols。Android Debug/Release、Lint、APK、API 36 / 16 KB 设备、130%/200% 字号和语义证据通过；Xcode 26.6 / iOS 26.5 的 App/Share Extension、22 Unit、默认浅色与深色 XXXL XCUITest 通过。最终 runs：workspace `30193059061`、iOS `30193059096`、Android `30193059066`；物理设备与发布门仍 `hold`。

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
| M25 双端平台视觉与设备复验 | done | 仓库工程候选 pass；双端当前平台视觉、字号、语义、真实构建与 Simulator/16 KB AVD 通过，物理设备和发布门继续 hold |
| R0 用户与场景研究 | plan_ready | 按产品决策有意后置 |
| R1 多端信息源调研 | plan_ready | 按产品决策有意后置，后续只做 MVP 定向补证 |
| 一天上下文覆盖与长期记忆 P0 | in_progress | reference、双端生产 Coverage、加密持久化/显式 Event、Android exact-Grant Agent bounded Event read + Event/Revision 写入/精确撤销、iOS 四操作 canonical/typed 客户端及 Swift→Android AVD 纵向执行、长期 Memory、本机删除水位/恢复候选与普通用户同安装恢复健康/exact 切换、真实/Mock 四类复用原子解析与 helpful feedback、双端 Search 单入口、无正文 telemetry、source lineage/Raw-only/多来源字段证据、完整用户确认与当前安装 Personal space root freeze 已获最终 head CI；继续完成真实 outcome/Host/ContextPack、跨端撤销、account/Grant/peer/分布式删除和跨设备生产恢复，T0 Pilot、真实规模与物理恢复保持 hold |
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
- 合成覆盖工具已覆盖 5 分群、10 类上下文和 10 种来源能力，但它不证明真实一天覆盖、授权率或市场规模；T0 Pilot 和首发地区/平台/渠道仍待确认。
- iOS Journaling Suggestions 尚无物理真机证据；Android Picker/Share/Calendar/Voice/Agent/AI 小结当前已有 API 36 16 KB arm64 AVD UI/连接回归，但无物理设备、OEM、后台或物理 LAN 证据。
- Windows 截屏 API 探测仍为 `RuntimeException`。
- instrumentation-only Codex seed 仍保留为测试辅助；paired Swift client 四操作 smoke 已不依赖它写 Event，并已执行 `create_event`、`visible_events`、`append_revision` 与 Event/Revision `undo_capture`，但仍只使用合成事件、显式扩展 Grant 和 ADB 端口转发。这不证明普通用户真实扫码、真实 Codex ContextPack、后台运行或物理跨设备传输。
- 产品负责人决策已关闭：LAN/P2P 无用户数据云、账户归属无用户密钥 UX、Health 公开 MVP、结构化导出 P1、M1–M6 全面研发、系统版本、Agent 自动读写、Raw+SourceLocator、单悬浮入口和 Pilot/真机投入均已 accepted。
- 仍待不可替代证据：真实用户、双端真机、SQLCipher 16 KB 与 UI/真机性能、真实 LAN/分布式删除、真实宿主 Skill、真实模型 AI/成本、共享 Grant/QR/账户授权与数据传输、目标市场法律与商店/供应商审核。

## 下一执行门

M24/M25 仓库工程候选均为 `pass`。下一执行门由发布负责人完成双端物理设备、真实 VoiceOver/TalkBack、签名/Provisioning、OEM/来源/后台、物理 LAN、商店申报和回滚清单；产品侧继续执行 T0 真实 Pilot。Simulator/AVD、固定 Mock、合成事件与自动化无障碍证据不能关闭这些门，Gate 1 与公开发布继续 `hold`。
