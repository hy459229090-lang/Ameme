# Ameme 当前正本

> 文档状态：当前有效\
> 适合读者：产品、研发、设计、安全、测试、AI\
> 人类快速阅读：先看“当前阶段”和“按问题查正本”\
> AI 阅读提示：先按任务读取对应正本，不要默认全文读取全部研究材料。

## 当前阶段

Ameme 已完成 MVP 研发前本地可完善准备、D1–D10 最终拍板和 M24/M25 双端仓库工程候选；当前 Goal 继续收敛 P0 生产运行时、跨端复用体验与外部门执行包。当前已集成契约、参考 Core/Raw/队列与同步、独立 CoreOracle、AI/固定评测、Android SQLCipher Local Event Node、双端本机核心与 Share/Agent Local Node 路径。Android/iOS 生产模块已加入 Coverage v1、九类 Event-revision-bound 长期 Memory、删除水位、认证同安装恢复候选、同安装 exact-confirmation/HMAC journal 可回滚激活内核、四类短时复用、SourceObject→Event lineage、exact-revision 字段证据、独立用户确认 provenance、当前安装 Personal space 根冻结与本机 Agent/连接/待处理载荷收敛。恢复默认只生成隔离候选；显式激活内核要求 exact backup ID、最长 15 分钟确认，在切换前后复核候选与调用时权威删除水位，`PREPARED` 失败回旧 live、`COMMITTED` 保留已验证新 live，原候选不消费且 `productionRecoveryClaim=false`。四类复用 API 的 exact references 15 分钟到期，并在 Android SQLCipher transaction / iOS MainActor 同步调用内完成 revision/删除/Restricted 复核和正文解析；持久 telemetry 只含加盐摘要、结果和动作。双端 Search 现均只展示一个“把记忆用起来”入口：iOS 使用原生 `confirmationDialog` + 结果 Sheet，iOS 26 Liquid Glass 只用于系统控制面；Android 使用当前稳定 Material 3 的扁平行项目 + `AlertDialog`，并以单一 `LazyColumn` 解决小屏/大字号空态下入口不可达的问题。Android schema v14 现为四项生产 Local Node operation 增加 content-free `STARTED/COMPLETED` SQLCipher 访问审计、180 天单点保留、失败关闭和设置页最近 20 条只读投影；同安装恢复会先清理候选过期审计，再把旧 live 在激活时未过期的审计与候选账本单调 union，精确重复去重，冲突/未过期容量异常 fail closed，并以账本和合并 SQLCipher 双摘要绑定换库。v13→v14、append-only/TTL、端点重载、设置页和恢复 union/sidecar instrumentation 已在 API 36 / 16 KB arm64 AVD 全量执行，XML 精确结果为 100 discovered / 93 passed / 7 外部门 skipped / 0 failed；恢复激活类 5/5、Agent 审计持久化 2/2、生产端点 3/3、完整 Ameme UI 套件 13/13、本机 Space 删除确认 UI 2/2、待处理动作存储 3/3。iOS Local Node 客户端已在普通用户 QR Grant 仍为 event-only 的边界下补齐四项 Android 生产 v1 operation 的 canonical builder、握手 capability、typed response/result-digest/error-shape 校验；原始 exchange 已收为私有，生产 exchange 只按当前时间授权。本地 Shared/App/Smoke build、生产 Shared Smoke、82 项静态门与统一 workspace 28/28 通过，并在 API 36 / 16 KB arm64 AVD 实际完成 Swift→Android create→bounded read→Revision→Revision exact undo→Event exact undo→final read 纵向网络闭环。当前 iOS CI `30438185984` 已用 Xcode 26.6 / iOS 26.5 完成 App + Share Extension、63 Unit、默认 Mock/真实本机 UI 2/2 和深色 XXXL UI 1/1。Android 本机恢复审计合并不代表 iOS Host、账户/多设备审计或安全导出已完成；本机仍没有完整 Xcode。真实 Host/ContextPack、真实用户确认动作与 helpful outcome、account/Grant/peer/分布式删除、跨端撤销传播、跨设备 key recovery、用户可见生产恢复、T0 Pilot、物理设备、真实读屏、签名和发布仍为 `hold`，不能用合成评测、Mock、Simulator/AVD 或静态检查替代。

2026-07-29 的本机删除入口进一步把 root freeze 与当前安装收敛串联：设置页要求逐字输入 `删除`，随后停止并等待本机 Agent runtime、验证移除当前安装 pairing/secret 与连接元数据，并清空 Android pending action、iOS pending export/App Group incoming-share handoff，写 content-free marker 阻止重启复活。Android JVM 107/107、API 36 / 16 KB AVD 100/93/7/0、iOS 生产 Smoke 与跨端静态契约 206 项通过；iOS XCTest 尚未在最终 head 执行。该结论只覆盖当前安装，不代表账号或共享 Grant registry、peer/云副本、provider 原件、外部分享、物理擦除或物理设备执行。

## 按问题查正本

| 问题 | 当前正本 | 状态 |
|---|---|---|
| 产品愿景与立项假设 | `00-project-charter.md` | 当前有效 |
| 产品能力、多端、体验与版本框架 | `02-product-system-framework.md` | v0.3，双主采集职责已确认 |
| R0/R1 调研计划 | `03-r0-r1-research-plan.md` | plan_ready，按产品决策有意后置 |
| 用户与场景矩阵 | `04-user-scenario-matrix.md` | v0.2，待真实访谈验证 |
| 目标用户一天上下文覆盖与来源优先级 | `product/目标用户一天上下文覆盖与来源优先级.md` | v0.2，本地 reference conditional_pass；市场规模与真实覆盖待 7 天 Pilot |
| 一天上下文覆盖研究执行基线 | `../research/coverage/目标用户一天上下文覆盖研究执行基线.md` | plan_ready；T0 8 人 × 7 天，尚无真实参与者 |
| P0 上下文覆盖与长期记忆验证 | `quality/P0-上下文覆盖与长期记忆闭环验证-20260726.md` | conditional_pass；真实用户/生产恢复/物理设备 hold |
| P0 双端生产 Coverage 运行时验证 | `quality/P0-双端生产Coverage运行时验证-20260726.md` | conditional_pass；生产领域底座通过，持久化/Event/Memory/真机待接入 |
| P0 双端 Coverage 持久化与显式 Event 接线 | `quality/P0-双端Coverage持久化与显式Event接线验证-20260726.md` | conditional_pass；iOS 生产密文 smoke 与双端原子接受边界通过；Android SQLCipher 后续已由 2026-07-29 API 36 / 16 KB 全量回归补证，iOS XCTest/真机待补 |
| P0 双端生产长期 Memory 边界 | `quality/P0-双端生产长期Memory边界验证-20260726.md` | conditional_pass；双端 evidence revision、显式确认、有效期、替代、上游失效与不复活通过；Android SQLCipher 后续已由 2026-07-29 API 36 / 16 KB 全量回归补证，iOS XCTest、ContextPack/恢复/真机待补 |
| P0 双端本机恢复候选与删除水位 | `quality/P0-双端本机恢复候选与删除水位验证-20260726.md` | conditional_pass；持久删除水位、认证同安装备份、隔离候选和损坏/错 key/旧水位/非空目标拒绝通过；同安装候选可由独立 exact-confirmation API 原子激活并在失败时回旧 live，跨设备 key、用户流程与物理恢复 hold |
| P0 双端本机恢复候选可回滚激活内核 | `quality/P0-双端本机恢复候选可回滚激活内核验证-20260729.md` | conditional_pass；exact backup 短时确认、PREPARED-before-staging、HMAC crash journal、失败回旧 live、候选不消费和 Android 未过期 Agent audit 单调 union 已实现；恢复类 AVD 5/5 已通过，用户 UI、跨设备 key、iOS fault-injection XCTest 与物理恢复 hold |
| P0 双端本机 Space 冻结与删除水位 | `quality/P0-双端本机Space冻结与删除水位验证-20260726.md` | conditional_pass；当前安装 Personal space 原子收敛、根水位、写入冻结、本机 cleanup 与旧备份拒绝通过，账号/Grant/peer/provider 原件/物理擦除/设备执行 hold |
| P0 双端本机 Space 删除授权与待处理快照收敛 | `quality/P0-双端本机Space删除授权与待处理快照收敛-20260729.md` | conditional_pass；当前安装 Agent runtime/pairing/连接与 pending action/export/share 快照已随产品删除入口收敛并防复活，Android JVM/AVD、iOS Smoke、静态门通过；共享 Grant registry、peer/provider、iOS XCTest/物理设备 hold |
| P0 双端用户确认字段证据与删源保留 | `quality/P0-双端用户确认字段证据与来源删除保留验证-20260729.md` | conditional_pass；Android schema v13 / iOS envelope v8 的 content-free exact-revision 完整确认保留、partial/stale/legacy fail-closed、迁移与加密重载已实现；Android SQLCipher 已在 API 36 / 16 KB AVD 执行，真实用户、iOS XCTest/物理设备及外部删除 proof hold |
| P0 Android 生产 Agent Revision 写入 | `quality/P0-Android生产Agent-Revision写入验证-20260726.md` | conditional_pass；Revision 切片的 exact-Grant/SQLCipher 幂等/敏感目标隐藏/Host MCP+TLS 通过；当时的 undo 关闭结论已由后续独立撤销报告更新 |
| P0 Android 生产 Agent Event/Revision 撤销 | `quality/P0-Android生产Agent-EventRevision撤销验证-20260726.md` | conditional_pass；撤销切片的 exact token、10 分钟首次时窗、Event tombstone、Revision compensation/head conflict、SQLCipher 持久幂等与 Host MCP+TLS 通过；当时的 read 关闭结论已由后续最小读取报告更新 |
| P0 Android 生产 Agent 最小读取 | `quality/P0-Android生产Agent-最小读取验证-20260726.md` | conditional_pass；bounded `visible_events`、SQLCipher scope/sensitivity/time/query/delete、Host Recall/Context/injection/budget 与 TLS 通过，真实 Host/设备/共享 Grant/发布 hold |
| P0 iOS 生产 Agent 四操作客户端与响应校验 | `quality/P0-iOS生产Agent-四操作客户端与响应校验-20260729.md` | conditional_pass；四项生产 v1 operation 的 canonical/minimal-Grant/typed-response 边界与 Swift→Android API 36 / 16 KB AVD 纵向闭环通过，普通用户默认仍 event-only；物理设备、共享 Grant、真实 Host 与发布 hold |
| P0 Android 生产 Agent 访问审计与只读投影 | `quality/P0-Android生产Agent-访问审计与只读投影-20260729.md` | conditional_pass；schema v14 的 STARTED/COMPLETED content-free audit、180 天保留、失败关闭、幂等重试、设置页投影与同安装恢复未过期账本单调 union 已实现；API 36 / 16 KB AVD 全量 96/89/7/0，iOS Host/账户审计、真实用户与物理设备 hold |
| R0 用户任务与竞品证据 | `research/R0用户任务与竞品证据_20260713.md` | 桌面研究完成 |
| R0 访谈与行为验证 | `research/R0用户访谈与行为验证计划.md` | 等待招募确认 |
| R0 FORMALdoc 真实样本 | `research/R0-FORMALdoc真实工作样本实验.md` | 专业桌面来源快照完成，不代表通用产品 |
| 多端信息源地图 | `05-multi-end-source-map.md` | v0.2，部分实测 |
| R1 官方能力证据 | `research/R1官方能力证据矩阵_20260713.md` | 官方矩阵完成，部分实测 |
| 非结构化处理与存储 | `06-data-processing-storage-options.md` | v0.4，框架已确认，待验证 |
| Event 与 DayLedger 最小模型 | `architecture/Event与DayLedger最小模型.md` | v0.4，当前核心架构候选 |
| 反馈与记忆类型覆盖 | `product/记忆反馈与类型覆盖框架.md` | 辅助机制，事件覆盖后使用 |
| 多端使用与体验闭环 | `product/多端使用与体验闭环.md` | v0.1，第 5 步评审候选 |
| MVP 范围与体验设计原则 | `product/MVP范围与体验设计原则.md` | v0.5，当前产品评审候选 |
| MVP 产品设计规格 | `product/MVP产品设计规格.md` | v0.6，D1–D10、范围/六条旅程/验收与冻结边界已接受 |
| 移动端来源与轻量位置记录 | `product/移动端来源与轻量位置记录.md` | v0.3，当前 Mobile 来源候选 |
| 产品体验风格与演进策略 | `product/产品体验风格与演进策略.md` | v0.4，效率风格与双端原生 UI 边界已确认 |
| Mobile 信息架构与数据获取联动 | `product/Mobile信息架构与数据获取联动.md` | v0.5，今天/搜索/平台原生设置入口已确认 |
| Mobile 交互设计规格 | `product/Mobile交互设计规格.md` | v0.6，单悬浮记录入口、页面/状态/权限已接受 |
| Mobile 低保真设计基线 | `product/Mobile低保真设计基线.md` | v0.3，页面与状态语义已确认 |
| Mobile 原生 UI 与流畅性基线 | `product/Mobile原生UI与流畅性基线.md` | v0.2，简洁高效品牌、原生 UI 与初始预算已接受 |
| Mobile 双端原生页面规格 | `product/Mobile双端原生页面规格.md` | v0.2，逐页组件、原生记录弹层、恢复与验收已接受 |
| Agent Skill 产品规格 | `product/Agent-Skill产品与交互规格.md` | v0.1，统一 Skill 与授权/失败/安全边界已接受 |
| MVP 指标与埋点字典 | `product/MVP指标与埋点字典.md` | v0.1，主指标、护栏和隐私安全埋点候选 |
| MVP 研发架构技术方案 | `architecture/MVP研发架构技术方案.md` | v0.6，技术实现默认栈与 Spike 边界已接受 |
| MVP 领域契约与状态机 | `architecture/MVP领域契约与状态机.md` | v0.1，不变量、状态、冲突、Recall 与兼容候选 |
| MVP 本地存储、同步与删除 | `architecture/MVP本地存储同步与删除协议.md` | v0.22，Android SQLCipher 4.15.0/v14、iOS envelope v8；Android Agent content-free 审计/180 天保留/设置投影在同安装恢复时按未过期记录单调 union；iOS Host/账户审计、用户恢复 UI、真实用户/ContextPack、跨设备 key、完整 Raw/LAN 仍待验证 |
| MVP 接口、错误与 Agent 工具 | `engineering/MVP接口与错误契约.md` | v0.5，Agent Local Node v1 bounded `visible_events` + `create_event`/`append_revision`/exact `undo_capture`，`get_event`/策略/长期 Memory 关闭 |
| MVP 机器契约 | `../packages/contracts/` | v0.1，JSON Schema/OpenAPI/合成夹具可复跑 |
| MVP AI 路由与 Prompt | `architecture/MVP-AI任务路由与Prompt契约.md` | v0.1，任务分层、隐私门、回退与 Eval 候选 |
| MVP 成本容量 SLO 与观测 | `architecture/MVP成本容量SLO与可观测性.md` | v0.2，LAN/local-first 成本、容量、SLO 与观测基线 |
| 正式产品/架构/安全决策 | `decisions/_INDEX.md` | 13 项登记（12 accepted + 1 superseded_for_mvp），0 项 needs_owner_review |
| MVP 隐私影响评估 | `privacy-security/MVP隐私影响评估.md` | v0.2，账户/LAN 数据流、用途、风险和发布前置已接受 |
| MVP 数据分类、保留与权利 | `privacy-security/MVP数据分类保留与用户权利.md` | v0.2，生命周期、集中 TTL、删除/导出基线已接受 |
| MVP 安全需求与威胁模型 | `privacy-security/MVP安全需求与威胁模型.md` | v0.2，LAN/账户密钥模型、20 类威胁、控制和测试门已接受 |
| MVP 第三方与商店申报 | `privacy-security/MVP第三方处理与商店申报清单.md` | v0.2，无数据云、release allow-list 为空，准入门已定义 |
| MVP 测试与 AI 评测 | `quality/MVP测试与AI评测策略.md` | v0.1，层级、夹具、旅程、环境和 Gate 候选 |
| Agent Skill 运行时契约 | `engineering/Ameme-Skill运行时契约.md` | v0.4，宿主/MCP/兼容/安全门与 Android bounded Event read/write/exact undo 边界已接受 |
| MVP 工程任务与里程碑 | `engineering/MVP研发任务书与里程碑.md` | v0.3，M1–M6 全面研发获批、发布仍受 Gate 约束 |
| MVP 技术 Spike | `engineering/MVP技术Spike任务书.md` | v0.3，15 项验证/否决任务 ready_to_execute |
| MVP 用户/真机验证 | `research/MVP用户与真机验证执行包.md` | v0.2 ready_to_execute / blocked_external；T0 8×7、4/4 平台目标、56 user-days、24 复用任务、8 次 D8、角色/耗时/私有模板/阈值齐，等待真实人员设备账号 |
| MVP 封闭发布与回滚 | `release/MVP封闭发布与回滚计划.md` | v0.2，LAN/Health、5→10–15 人 rings、stop/rollback 基线 |
| 封闭 Beta 外部门执行包 | `release/封闭Beta外部门执行包.md` | v0.1 ready_to_execute / blocked_external；双端物理设备/读屏、真实 Agent/LAN、生产恢复、签名/商店、真实成本、事故/回滚的人员设备账号步骤耗时和判定齐，当前 hold |
| MVP 运行与事故响应 | `operations/MVP运行监控与事故响应.md` | v0.1，dashboard、SEV、Runbook、支持与演练候选 |
| 事件反馈与记忆类型模型 | `architecture/记忆类型与反馈事件模型.md` | v0.2，架构草案 |
| Prototype 候选 | `07-prototype-candidate-review.md` | v0.2，DayLedger Core，Gate 1 hold |
| Windows 信息源实测 | `../research/windows/windows-feasibility-report.md` | 已完成低风险验证 |
| Chrome activeTab 实测 | `../research/browser/active-tab-spike-report.md` | 低权限闭环已验证 |
| 工作区和上线流程 | `governance/产品生命周期与上线门禁.md`、`governance/标准工作流.md` | 已确认 |
| MVP 三线并行计划 | `governance/MVP三线并行设计与技术方案计划.md` | v0.5，双端平台映射、流畅性与 P0/P1 并行阶段 |
| MVP 三线 C1 审计 | `governance/MVP三线C1交叉审计.md` | v0.4，pass_with_validation_items |
| MVP 研发前准备与差距 | `governance/MVP研发前准备清单与差距矩阵.md` | v0.3，owner/local gap 清零，只剩 external/spike |
| MVP 研发前全链追踪与审计 | `governance/MVP研发前全链追踪与审计.md` | v0.2，pass_with_external_evidence |
| MVP 产品负责人 Review | `governance/MVP明早Review包_20260714.md` | accepted，D1–D10 最终拍板已回填 |
| FORMALdoc 适配依据 | `governance/FORMALdoc借鉴与Ameme适配说明.md` | 已确认 |

## 历史输入

- `00-phase-0-product-discovery.md`：第一轮竞品和桌面 MVP 假设，已降级为历史输入。
- `01-discovery-brief.md`：早期 Discovery 摘要，结论需以当前正本为准。
