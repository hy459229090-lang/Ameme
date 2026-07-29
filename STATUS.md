# Ameme 状态看板

> 最后更新：2026-07-29
> 事实来源：`JOBS/JOBS-Ameme.md` 与各 Gate 验证材料。

## 当前阶段

- 生命周期：Discovery Gate 仍在补证，M24 双端仓库工程候选已完成；M1–M6 全面研发获批，iOS/Android 的真实本机与固定 Mock 核心闭环、一次性 QR v2 bootstrap→独立凭据→Local Node 受限通道、可复现构建/测试和 Simulator/AVD 设备门已形成。
- 当前 Gate：仓库工程候选 `pass`，Gate 1 / 物理设备 Beta / 公开商店发布继续 `hold`。不得把 Simulator/AVD、合成数据、Mock、ADB forward 或静态无障碍检查写成物理真机、真实用户或公开发布通过。
- 产品 MVP：`今天` 为唯一默认主页，单悬浮记录按钮展开文字/语音/照片/导入，右上搜索进入统一历史日流；数据稀疏是默认状态。品牌为简洁、高效、安静、可信、流畅；双端使用原生控件。
- Agent：统一 `ameme-memory` Skill 与本地 MCP Host 已形成六模式、授权内自动读写、撤销、预算控制和 16-case / 15-risk 评测；Event/Revision 自动写入不再等同于确认长期 Memory。Android 生产 Local Node 已在冻结 v1 上实现 `create_event`、`append_revision`、exact `undo_capture` 与 bounded `visible_events`：新配对明示 Personal/structured Event 读取和 Event/Revision 写入/撤销，缺少 operation policy 的旧 pairing fail closed；读取只走 active Event current projection，query≤1,000、limit≤100、title/description≤240/1,000，生产 runtime 排除 Restricted，且不返回 user words/source/locator/Raw。Host `recall/get_context` 继续执行 injection 和 item/token budget；`get_event`、策略写入与长期 Memory 确认关闭。Event/Revision 撤销仍保持 tombstone/compensation、10 分钟首次时窗和 SQLCipher 幂等重放。iOS 生产客户端现已补齐四项 operation 的 canonical builder、最小 Grant scope、握手 capability 检查、typed result 与 result-digest/error-shape fail-closed；原始 application exchange 已收为私有，生产 exchange 只能按当前时间授权，避免绕过 typed Grant/operation 检查。普通用户 QR 默认仍为 event-only，不静默增加读取/Revision/撤销。本轮在 API 36 / 16 KB arm64 AVD 上完成 Swift Network.framework→Android SQLCipher→Today 四操作纵向 Smoke：create→bounded read→Revision→Revision exact undo→Event exact undo→final read 全部通过；真实 ContextPack、共享账户 Grant、后台、物理 LAN/设备与第三方生产宿主仍未证明。
- QR 配对安全 Gate：Release 已切换到 `ameme.agent-pairing-bootstrap.v2`。Android 持久包裹 5 分钟 bootstrap，校验 HMAC + P-256 client-key possession 后原子标记 consumed，并签发与 QR secret 分离的 30 天应用 channel credential；响应丢失只允许同 key 在有界 receipt 窗内重取同一凭据。Release 不生成 developer bearer，iOS 以 `WhenUnlockedThisDeviceOnly` Keychain 保存 pending/active 状态并在实际重新认证后恢复连接。该 key binding 精确约束签发和恢复，冻结 v1 应用通道仍使用 bearer，并未在每次重连重复 P-256 proof。QR 静态门 54/54、Android manager AVD 7/7、Swift→Android v2 AVD smoke 与独立 Debug Host smoke 通过；Android 相机扫码生产 client、共享 Grant registry、最终 head iOS XCTest、物理扫码/LAN/后台仍为 `false/hold`。
- Agent 访问审计：Android 当前 SQLCipher schema v14 已把生产 Local Node 的全部启用操作接入 content-free `STARTED/COMPLETED` 审计，记录 caller、purpose、operation、Space/数据类型范围、结果、对象数分桶与时间，不保存正文、query、payload、对象 ID、路径、secret、Grant 或异常文本；审计写失败时操作 fail closed，保留期为 180 天，设置页只读展示最近 20 条且没有合成回退。同安装恢复现把旧 live 在激活时未过期的审计与候选账本单调 union，先原子清理候选过期行，再做精确重复去重；同 ID/trace-phase 冲突、非法记录或 50,000 行未过期容量溢出均在换库前失败关闭，并以账本/合并 SQLCipher 双摘要绑定 staging 与新 live。Debug JVM 110/110、Lint、Debug/unsigned Release APK、androidTest 编译、审计静态契约 146、恢复 138 与 workspace 28/28 通过；API 36 / 16 KB AVD 已实际执行 v13→v14、append-only/TTL、端点、设置页和恢复 union/sidecar 场景。iOS Host、账户级汇总、安全导出、真实用户与物理设备仍未完成。
- 同步/账户：同账户已批准设备在 LAN 内点对点同步；身份服务不保存记忆内容；无用户密钥 UX，也无全设备丢失后的旧数据恢复。
- 工程实现：Android 已在 API 36、16 KB arm64 AVD 上以 `PAGE_SIZE=16384`、320dp、`font_scale=1.3` 完成本轮全量 `connectedDebugAndroidTest`：XML 精确结果为 103 discovered / 96 passed / 7 显式外部门 skipped / 0 failed；其中 QR pairing manager 7/7，既有恢复激活、Agent 审计、生产端点、UI、删除确认与待处理动作回归继续通过。Debug/Release 构建、单 variant 110/110 JVM、Lint 0 error（29 warning / 1 hint）、APK 与 androidTest 编译通过，当前 Debug APK 为 41,190,345 bytes，unsigned Release APK 为 33,592,286 bytes，androidTest APK 为 2,874,573 bytes。最终 Today、Search、设置页三段式连接信息架构与默认收起开发者材料的 QR 弹层均已在 130% 字号下截图人工复核；这证明当前稳定 Material 3 的虚拟设备实现，不替代 TalkBack 或物理设备。iOS 已有 XcodeGen App、静态 Shared Core、嵌入式 Share Extension、Unit/UI Test targets 与共享 scheme；本地 Shared/App/SharedSmoke/LocalNodeSmoke build、生产 Shared Smoke、目标/Test 源码 parse 与 Swift→Android QR v2→四操作网络 Smoke 通过。当前 iOS CI `30438185984` 是本轮 QR v2 之前的最近完整 Xcode 基线；新增删除协调、防复活和 QR bootstrap/Keychain XCTest 仍待最终 head CI 签收，本机 `swift test` 继续真实失败于 Command Line Tools 缺少 `XCTest`。最新隔离 Python 3.12 workspace 28/28 Gate 通过：Coverage 4,262、Mobile Coverage 280、Mobile long-term Memory 92、Mobile recovery 138、Mobile reuse 186、Mobile source deletion 134、Mobile field provenance 138、Mobile user-confirmation provenance 94、Mobile local-space deletion 206、external gate pack 80、Android Agent Revision 87、Android Agent undo 117、Android Agent read 108、Android Agent audit 146、iOS Agent client 82、Core 47、Agent 35、Android Agent adapter 29、AI 38、iOS project 52、Share inputs 30、iOS accessibility 33、QR 54、Markdown 43、治理 180，均 0 error。QR Gate 的 server-enforced one-time、credential rotation 和 Release 无 developer bearer 静态实现 claim 为 true；Android 相机 scanner、共享 Grant registry 与物理设备执行 claim 保持 false。真实 OpenAI live 按设计跳过；AVD 不等于物理设备，真实 VoiceOver/TalkBack、签名、生产账户/模型与商店发布不在这些通过结论内。
- P0 生产 Coverage、长期 Memory、本机恢复、复用与删除：双端已有 Coverage v1 编译/状态/密文持久化和显式 Candidate→Event 接受；保存 Coverage 不创建 Event，Event 删除后 link detach 且 Candidate 不复活。进一步加入 9 类 Event-revision-bound 长期 Memory 候选、显式确认、有效期、替代和上游修订/删除失效；普通候选与敏感/推断候选都不会自动成为 active。Android Agent Revision 复用同一 Event revision/invalidation 边界，只写 Revision，不调用长期 Memory confirmation。Android schema v9 / iOS envelope v4 引入不含正文的持久 Event 删除水位、认证同安装备份和隔离恢复候选；损坏、错 key、旧水位与非空目标 fail closed，artifact 不含 key 且硬编码 `productionRecoveryClaim=false`。schema v10 / envelope v5 加入四类本机复用、15 分钟 exact reference、原子 revalidate+resolve 与只含加盐摘要/结果/动作的 telemetry；双端 Search 现均使用单一“把记忆用起来”入口，随后由平台原生选择器呈现四种意图并支持 exact Event 跳转和五类无正文反馈，演示模式不会污染生产复用记录。Android schema v11 / iOS envelope v6 引入本机 SourceObject→Event lineage与当前安装 Personal space 根水位，schema v12 / envelope v7 增加 content-free exact-revision 字段→来源证据；当前 schema v13 / envelope v8 新增独立用户确认 provenance，只保存确认 ID、精确 Event/revision、类型、字段名、完整/partial 标志、时间与 terminal state。完整显式 Candidate 确认可以在唯一来源删除后保留 Event，但来源 link/claim 仍终结并追加“用户确认（来源已删除）”revision；partial、legacy、stale 确认不能保留失去支持的正文，Agent revision/undo 不会伪造确认，v12/v7 迁移不猜测旧动作。Android 没有自有 Raw Vault，Raw-only 只返回外部所有权/无 Raw；iOS 可两阶段删除 app-owned AES-GCM media并重启重试。本机 `deleteLocalSpace` 会在依赖收敛后最后写 SPACE root，冻结重载后普通读写并拒绝旧备份；双端产品入口现进一步停止本机 Agent、清除连接元数据、冻结并清空待处理分享/导出，启动时重放未完成的本机收敛。它仍不撤账户/共享 Grant、不删除账号/provider/peer 副本且不证明物理擦除。Android v7–v14 SQLCipher instrumentation 已在本轮 API 36 / 16 KB AVD 全量回归中执行；iOS 生产 Shared Smoke 已覆盖完整确认保留、partial fail-closed、加密重载与删除后待处理载荷防复活。iOS 新 UI 的最终 head Xcode/XCUITest、真实用户确认动作与 helpful outcome、account/Grant/peer/分布式删除、跨设备 key recovery、用户可见生产恢复、物理设备和真实读屏仍为独立 Gate，Verdict 保持 `conditional_pass`。
- Android 当前存储版本：上段的 schema v13 是用户确认 provenance 的引入版本；Android 当前 schema 已随 Agent 访问审计升级为 v14，iOS 当前 envelope 仍为 v8。v13→v14 迁移只新建 content-free 审计结构，不推断或补造历史访问记录。
- 本轮同安装恢复激活内核：双端授权都精确绑定 candidate backup ID、有效期不超过 15 分钟，并在 live-store 切换前后复核密文、关系、媒体和调用时权威删除水位；原候选不被消费。Android 的认证 `PREPARED` journal 已前移到 staging 写入之前，覆盖复制/审计合并期间的崩溃窗口，并在恢复时收敛 staging/live 专用 sidecar；候选过期 audit 会先清理，旧 live 未过期 audit 再单调并入。iOS 同步冻结 store 并把 app-owned media locator 重写到 live 根。HMAC `PREPARED/COMMITTED` journal 使调用失败或重启能明确回旧 live 或保留已验证新 live，伪造 journal fail closed，commit 后清理未完成以 `cleanupPending` 诚实返回。iOS 生产 Smoke 已实际通过成功激活、过期授权零变更和删除不复活；Android exact authorization、候选保留、中途失败回滚、过期授权零变更、伪造 journal、audit union/merge failure 与 orphan sidecar 场景均已在本轮 API 36 / 16 KB AVD 全量 100/93/7/0 中签收。本内核仍没有用户可见的生产恢复入口，artifact/receipt 继续硬编码 `productionRecoveryClaim=false`；用户自有存储或 E2EE 路线、recovery secret、跨设备 key、真实进程/断电故障和物理恢复继续 `hold`。
- 本轮跨端收口：iOS Search 在“第一张图”的内容优先方向上进一步减法，只保留一个扁平复用入口，使用原生 `confirmationDialog` 与结果 Sheet；iOS 26 Liquid Glass 只用于系统控制面，内容不被装饰性玻璃包裹。Android Search 复盘后确认采用当前稳定 Material 3，不复制 iOS 玻璃；同时把固定顶部 + 独立结果滚动改为单一 `LazyColumn`，解决 320dp/130% 字号空态下复用入口不可达的问题，并用等宽日期控件、短标签和原生 `AlertDialog` 完成设备复验。iOS Shared/App 包级源码构建、Smoke 与 parse 通过；Android Debug/Release、全量设备回归与最终截图通过，iOS 新 Xcode/XCUITest/截图仍待 CI 签收。
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
| 一天上下文覆盖与长期记忆 P0 | in_progress | reference、双端生产 Coverage、加密持久化/显式 Event、Android exact-Grant Agent bounded Event read + Event/Revision 写入/精确撤销、iOS 四操作 canonical/typed 客户端及 Swift→Android AVD 纵向执行、长期 Memory、本机删除水位/恢复候选与同安装可回滚激活内核、四类复用原子解析、双端 Search 单入口、无正文 telemetry、source lineage/Raw-only/单来源 cascade、多来源 exact-revision 重算/字段失证删除、完整用户确认删源保留/partial fail-closed 与当前安装 Personal space root freeze conditional_pass；Android 激活 instrumentation 已设备签收，继续完成 iOS 新 UI/激活/Agent XCTest、真实 outcome/Host/ContextPack、跨端撤销传播、真实用户确认动作、account/Grant/peer/分布式删除和跨设备生产恢复，T0 Pilot、真实规模与物理恢复保持 hold |
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
