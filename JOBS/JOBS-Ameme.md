# Ameme 项目 Job

## 工作区与上线工作流

- 状态：`done`
- 目标：建立从产品调研、决策、Prototype、MVP、测试、Beta 到生产发布和运营复盘的可执行工作区。
- 确认人：产品负责人。

### 步骤

- [x] 审查 FORMALdoc 的目录、规则、Job、索引、验证和已知失败。
- [x] 建立 Ameme 根级规则、路由、状态和决策流水。
- [x] 建立正式文档域、工程代码域和数据安全边界。
- [x] 定义生命周期 Gate、标准工作流和完成标准。
- [x] 建立机器可检查的工作区健康脚本和 CI。
- [x] 将现有调研纳入当前正本与索引。
- [x] 运行健康检查并保留真实结果。

### 交付

- `AGENTS.md`、`WORKSPACE_MAP.md`、`STATUS.md`、`WAL.md`
- `docs/governance/`
- `docs/_CURRENT.md`、各文档域 `_INDEX.md`
- `scripts/governance/check_workspace.py`
- `.github/workflows/workspace-health.yml`

### 确认结果

1. 用户于 2026-07-13 确认工作区规范与生命周期 Gate。
2. 工作、个人、家庭、健康、财务等空间的密钥隔离策略仍需在 Gate 3 前确定。
3. 已创建首个正式 Git 基线提交 `115690b`，作者仅在本仓库配置为 `Codex <codex@local>`。

### 真实验证证据

| 验证 | 实际结果 |
|---|---|
| `python scripts/governance/check_workspace.py` | `ok=true`，73 checks，0 errors，0 warnings |
| Python 脚本编译检查 | 通过 |
| Windows capability probe v0.2 | 文件事件竞态修复后连续 3 次捕获 create 2、change 3、rename 1、delete 1；截屏 API 探测仍为 `RuntimeException` |
| Windows UIA coverage probe | 完成；覆盖差异仍存在，且未保存元素名称、文本值或窗口标题 |
| Chrome activeTab Spike | 7 项断言全部通过：触发前 URL/标题不可见、触发后合成页成功、跨 origin 撤回；未声明 host permissions |
| Git 基线 | `115690b chore: establish Ameme project governance baseline` |

## 用户与场景研究

- 状态：`plan_ready`
- 当前结论：产品核心不是要求用户拥有工作区，也不是先生成记忆候选；应先覆盖一天中有意义的事件，形成清晰、可追溯的 DayLedger，再生成总结和长期记忆。FORMALdoc 只作为 `R0-WORK-001` 专业桌面来源对照，不计入 15 人市场验证。
- 本轮新增：项目章程、系统框架、数据模型和 Prototype 已切换为 `EventCandidate -> Event/Episode -> DayLedger -> Summary/Memory/Recall`；反馈降为事件补漏、纠错和使用结果机制。
- 框架评审进度：第 1 层“产品最终解决什么问题”已确认；基础单位为一天中的事件，价值顺序为保留经历 -> 总结/洞察/AI 使用。
- 框架评审进度：第 2 层能力分层无明显问题；新增用户后补描述作为正式来源，可补事件或修订描述，且不覆盖历史。
- 框架评审进度：第 3 层再次修订：端优先级先看独特数据/事件覆盖，再看用户规模。Agent MCP/Skill/API 与 Native Mobile 为主采集入口；Web App/小程序/Browser Extension 负责扩大触达、查看和补充；Native Desktop 后置。
- 框架评审进度：第 3 层已由产品负责人确认。第 4 层候选采用“来源证据 -> 解析观察 -> 事件记录 -> 派生使用”四层记录，事件按字段级证据归并，Mobile 与 Agent 只同步用户允许的数据。
- 框架评审进度：第 4 层已由产品负责人确认。第 5 层候选取消强制每日审核：高置信事件自动保存，低置信只在高价值时询问；Mobile、Agent、Web 使用不同自然入口。
- 下一步：按产品负责人决定有意后置；不启动样本和试访，等待 MVP 范围与体验原则确认后重排。
- Gate：Gate 1。

## 多端信息源研究

- 状态：`plan_ready`
- 当前结论：官方能力证据矩阵完成；Windows 文件事件和 Chrome activeTab 低风险闭环实测通过。来源重新分为通用基础、系统增强和专业增强三层，专业工具不再是产品前提。
- 下一步：按产品负责人决定有意后置；当前不继续扩展来源调研，后续只为已确认 MVP 范围做定向补证。
- Gate：Gate 1。

## MVP 范围与体验原则

- 状态：`accepted`
- 已确认：首批服务 AI 使用者；iOS 和 Android 两个 Native Mobile 客户端均进入 MVP，且 Mobile 单独服务日常用户也能形成“我的一天”；Agent 侧为 MCP/Skill/API 完整集成层。
- 主闭环：Mobile 以 `今天` 形成可追溯“我的一天”，用户低负担补充/纠错，并可通过右上搜索按钮进入历史日流；Agent 在任务中完成一次有用的历史调用。
- 当前范围：Mobile 的文字/语音、分享、照片选择、日历；Agent 的明确记录、任务上下文、选定产物和结果写回；结构化同步、来源解释、Revision 和删除传播为底座。
- 本轮新增：信息按来源级、字段级、事件级和用户级四层区分可信度、重要性、独特性、未来用途、新鲜度、敏感度和个人优先级，不使用单一总分。
- 已确认：“我的一天”为 Mobile 主输出结构；照片第一、语音第二，位置为必选来源，健康/日历等按用户授权；情绪显著度和关系显著度独立建模。
- 本轮新增：轻量位置采用照片/日历/系统建议、前台一次位置、后台到访/显著变化/地理围栏三层来源，不默认保存连续 GPS 轨迹。
- 本轮确认：主导航现阶段使用 `今天`；情绪/关系/重要性与事实置信度、授权完全解耦，标记重要不得触发或绕过扩权。
- 本轮新增：轻量位置进一步改为 A0-A3 机会式级联；健康在同一条“身体与活动”路径中分为日常活动、锻炼、睡眠、恢复和正念/主观状态五个授权包。
- 本轮确认：A0-A3 机会式位置级联作为默认策略；健康同页展示五类授权包，默认推荐日常活动、锻炼和睡眠。
- 本轮确认：MVP 采用效率风格，优先满足首批 AI 使用者快速看清、补充、核验和使用一天；情绪风格作为后续表达层保留，共用 Event Core 和权限底座。
- 本轮修订：Mobile 信息架构与数据获取必须并行设计；每个页面模块都要映射正式数据对象、来源、授权、获取时机、处理状态和失败降级。
- 本轮确认：`今天` 是唯一默认首页；右上放大镜按钮进入历史搜索页；设置不占主导航并按平台使用原生二级入口；`今天` 保持当日记忆浏览和上传/补充主路径。
- 本轮确认：用户界面不使用“找回”作为功能名称；历史搜索采用日历定位 + 搜索词检索，后续 Agent 复用 RecallQuery、空间和权限边界。
- 本轮确认：搜索页只使用一条按日分组的历史日流；向上滑加载更早日期，日历负责跳转，关键词/日期范围负责筛选同一日流。
- 本轮确认：今日小结在事件流末尾显示 2–3 行简版并可展开，数据不足时不生成；不设面向用户的最小来源组合。
- 已确认保留原则：原始位置 24 小时、音频/照片预览 7 天、模型临时缓存 24 小时等集中 TTL；结构化结果独立保留，用户选定原始才同步，删除需要传播；Spike 可收紧，延长需安全决策。
- 本轮确认：效率版 `今天`、默认搜索页以及空白、数据稀疏、索引更新中、部分失败只锁定共同语义，不作为跨平台像素稿。
- 本轮确认：流畅性优先；iOS 使用 SwiftUI/系统组件，Android 使用 Jetpack Compose Material 3/系统能力，不建设跨平台自定义 UI 壳。
- 本轮确认：品牌为简洁、高效、安静、可信、流畅；`今天` 只采用一个悬浮“记录”按钮，点击后展开文字/语音/照片/导入，不设常驻输入框或底部 Tab Bar。
- 当前交付：Mobile 交互 v0.6、双端页面 v0.2、原生 UI/性能 v0.2、统一 Agent Skill 产品/运行契约和仓库内分发包已接受。
- 下一步：按依赖启动 M1–M6，优先实现契约 Runtime、本地 Core、LAN/Skill 风险切片；真机/外部证据按 Spike 补齐。
- Gate：产品范围确认，不代表 Gate 1 已通过。

## MVP 三线并行设计与技术方案

- 状态：`accepted`
- 目标：并行完成产品设计、交互设计和研发架构技术方案，并用统一追踪矩阵完成 C1 交叉审计。
- 执行计划：`docs/governance/MVP三线并行设计与技术方案计划.md`

### 三线产物

- 产品线：`docs/product/MVP产品设计规格.md`
- 交互线：`docs/product/Mobile交互设计规格.md`
- 研发架构线：`docs/architecture/MVP研发架构技术方案.md`
- 现有联动基线：`docs/product/Mobile信息架构与数据获取联动.md`

### 当前执行

1. 产品、交互和研发架构三份 v0.4 已回填。
2. C1 交叉审计已更新为 v0.4，结论为 `pass_with_validation_items`。
3. 15 项 MVP 能力已映射产品规则、交互入口和架构落点；“已闭环候选”不等于已验证。
4. 页面/状态语义、原生 UI、单悬浮记录入口、历史日流、小结、无最小来源、LAN/账户/SourceLocator 与统一 Skill 已确认；真机流畅性和实现达成待验证。

### 当前执行门

- 产品负责人已确认三线并行工作方式。
- C1 审计：`docs/governance/MVP三线C1交叉审计.md`。
- M1–M6 全面研发获批；里程碑验收和公开发布仍按 Gate 1/2、真机、Health、安全/同步/Skill Spike 关闭。

## Prototype 候选评审

- 状态：`plan_ready`
- 当前核心：一个 DayLedger Core；通用/移动、专业桌面、沟通与服务是三个可替换来源包，时间线、总结、找回和 ContextPack 是上层输出。
- 当前建议：Event/每日事件记录模型 v0.2 已建立，但先不跑样本和闭环；当前评审信息分类、事件归并、描述和跨端存储。这不是 MVP 决策。
- Gate：Gate 1 仍为 `hold`，缺正式用户、通用来源样本和移动端证据。

## MVP 研发前准备 Goal

- 状态：`done`
- 目标：按计划、执行、Review、修订和门禁复核循环，关闭全部当前工作区可完善的研发前缺口；只把产品范围、重大隐私/成本/架构取舍和不可替代外部实证留给产品负责人。
- 执行基线：`docs/governance/MVP研发前准备清单与差距矩阵.md`
- 结束条件：差距矩阵 `needs_work` 归零；产品/交互/架构/隐私/质量/工程/发布正本和正式决策齐全；验证与索引一致；形成明早 Review 包；不得用文档冒充 Gate 1/2 实证。

### 执行步骤

- [x] 建立 Goal、八阶段执行计划和研发前 Definition of Ready。
- [x] 完成初始差距审计，识别隐私、安全、Schema/API、质量、成本、工程和决策空域。
- [x] 形成产品、双端交互、旅程、状态、指标和验收的研发前候选，并完成全链 Review 与修订。
- [x] 形成领域、Schema、API、存储、同步、冲突、删除和迁移的研发前候选；机器契约离线校验及全链 Review 通过。
- [x] 补齐系统架构、AI 路由、成本容量、可观测性和性能研发前候选；13 项 PDR/ADR/SDR 正式登记（12 accepted、ADR-003 superseded_for_mvp）。
- [x] 补齐 PIA、数据分类/保留/权利、威胁模型、条件密钥模型、第三方处理和商店申报研发前候选。
- [x] 形成测试/Eval、研发任务书、里程碑、15 项技术 Spike、用户研究执行包、发布/回滚和事故响应研发前候选。
- [x] 完成全链 Review、修复、机器门禁和明早产品负责人 Review 包。
- [x] 补齐统一 `ameme-memory` Skill、宿主契约、14-case 风险评测与 CI 校验；修正显著度/重要性错误增信与扩权表述。

### 当前结论

1. W1–W6 的产品、交互、领域、Schema/API、架构、AI、隐私安全、质量、成本、工程、发布和运行正本已形成并完成全链 Review。
2. 差距矩阵 `needs_work = 0`；14 项跨线发现已修订，所有 P0 能力均可追踪到页面、契约、状态/存储、安全、指标、测试和工程任务。
3. 机器契约 2,473 项离线校验通过；118 项工作区治理与 30 个 Markdown 链接均 0 error，Python compileall 和 diff 门禁通过。成本脚本已切换 LAN/local-first relative fixture，不冒充真实供应商报价。
4. D1–D10 已最终拍板并回填：LAN/账户/Health/导出/全面研发/OS/Agent 自动记忆/Raw+SourceLocator/单悬浮入口/Pilot 矩阵。
5. 正式用户证据、Mobile 真机能力、端到端实现、供应商/商店/法律证据均已有可执行任务和否决门槛，但未被文档伪造为通过。
6. Gate 1 仍为 `hold`，Gate 2 未执行；M1–M6 全面研发获批，但完成/发布继续受对应 Gate/Spike 约束。
7. 终检通过：Skill 官方结构、7 文件/14-case/14-risk、2,473 项机器契约、118 项工作区治理、30 个 Markdown 链接、Python compileall、成本脚本和 `git diff --check`；仅保留已有 LF→CRLF 工作区提示。

## MVP 工程实现

- 状态：`in_progress`
- 目标：按 M1–M6 依赖把研发前正本变成可构建、可复跑、可恢复的真实实现；Gate 1/2、真实数据和公开发布边界保持不变。
- 当前环境：Windows + Android SDK 可执行；iOS 源码、构建和真机证据等待产品负责人后续提供 Mac 环境。

### 已集成首批切片

- [x] C-001：冻结 v0.1 契约快照、breaking diff、正负 fixture、CI 和安全日志 canary。
- [x] C-003/AND-001 局部：Kotlin contract round-trip/validator 与 Android Compose/Material 3 合成状态骨架。
- [x] M2 参考证据：非生产 SQLite Core Oracle 跑通 Source→Event/Episode→DayLedger→Recall→删除/重建；不冒充 Mobile 生产库。
- [x] SYNC-01：确定性同步协议、12 条冻结向量、冲突/删除/跨空间负向测试；不冒充真实 LAN。
- [x] Agent 风险切片：本地 MCP Mock、统一 Skill、撤销/撤权/预算/injection 测试；不冒充真实宿主集成。
- [x] 总控 Review：修复多来源删除、evidence undo、跨空间接收、Revision undo、Context 预算和 Android 数值边界后，按依赖集成到 main `852270f`。

### 已集成第二批切片

- [x] DB-01 + AND-002 第一段：Android 固定官方 SQLCipher 4.15.0，接入 Keystore 包裹随机库密钥、WAL、错误密钥拒绝、v1→v2→v3 非破坏迁移、space 隔离、数据库级 Revision 不可改删、重建/日期关键词读取和 Tombstone 删除；API 36 AVD 13/13，通过不代表真机、16 KB 或容量性能完成。
- [x] CORE-002/007/008 参考段：非生产 Core Oracle 增加 Raw Vault manifest/AES-GCM、耐久 processing/sync/delete queue、两阶段可恢复删除、AAD v2 元数据绑定、TTL 与 deletion proof 故障恢复；未落入移动端生产节点。
- [x] AI-001/002/004 第一段：增加 Task registry、PromptEnvelope、显式 Provider allow-list、R0 时间/计划/去重规则、EventCandidate 结构化/evidence validator 和安全回退；无真实模型、完整 Grant 或 Summary 质量结论。
- [x] 合并后全链 Review：125 项 Python 测试、2,715 项兼容检查、2,709 项契约检查、5 个契约质量门、14-case/14-risk Skill、32 个 Markdown 链接、128 项治理、Android JVM/lint/assemble 与 API 36 AVD 13 项设备测试通过。

### 已集成第三批切片

- [x] AND-003/004 第一段：Android Photo Picker、受限 ACTION_SEND、文字入口、语音明确 Unsupported、用户触发且有界的 Calendar 合成 adapter；Calendar 只写 `Planned`，无媒体/麦克风/日历/位置敏感 Manifest 权限。
- [x] DB-01 后续第一段：SQLCipher v4 SourceLocator、API 36 FTS5 + 参数化 LIKE 共同多词语义、keyset 日期分页、space/delete 过滤，以及 Persisted URI `RELEASE_PENDING → RELEASED` 两阶段恢复；25/25 AVD，通过不代表 16 KB、容量或真机性能。
- [x] AI-005/006 第一段：12-case 固定合成 Eval、攻击/删除集、差异报告，以及 scope-bound budget/cache/batch/route observability；真实模型质量、成本和供应商行为仍未证明。
- [x] SKILL-01/AG-003 Windows 测试段：MCP Mock 接 EventNodeStore/Core Oracle 测试适配，覆盖授权/撤权/离线/injection/undo、双存储三崩溃窗口和内容安全控制面；Python Core 与 Mock 均非真实宿主或生产 Local Node。
- [x] 合并后全链 Review：143 项 Python 测试、AI Eval 12/12、2,715/2,709 契约检查、14-case/14-risk Skill、32 个链接、129 项治理、Android JVM/lint/assemble 与 API 36 AVD 25 项设备测试通过。

### 已集成第四批切片

- [x] DB-01 性能段：Compose 数据库打开、写入、检索、来源读取和授权清理统一移到 `MemoryIoExecutor`；Search 以 request generation 隔离旧分页结果，修复 repository 在重组时被提前关闭的生命周期缺陷。
- [x] DB-01 10k/100k：批量路径改为分块存在性预检和新记录快速插入；API 36 AVD 有效报告覆盖容量、FTS/LIKE、keyset、单次提交、磁盘、粗内存和 v3→v5 迁移。100k FTS P95 177.72 ms、单次提交 P95 215.67 ms，两项预注册 DB 门通过。
- [x] AND-003/004 第二段：用户触发的 Calendar Provider 只读导入和系统录音/音频选择引用落地；READ_CALENDAR 仅在显式操作请求，无 WRITE_CALENDAR/RECORD_AUDIO。物理游标限流、全天日期、提交终态和来源实例幂等已覆盖。
- [x] CORE/AG 参考宿主边界：MCP 可显式启动独立 `ameme.core-oracle-host.v1` JSONL stdio 参考进程，挂起/畸形/崩溃有界失败；Android 仅冻结 transport port，不冒充真实 LAN Local Node。
- [x] 合并后全链 Review：147 项 Python、28 项 Android JVM、34 项普通 AVD 设备测试、1 项 DB-01 性能测试、AI Eval 12/12、2,715/2,709 契约、14-case/14-risk Skill、35 个链接和 130 项治理检查通过。

### 已集成第五批切片

- [x] AG/CORE 应用协议：冻结 `ameme.agent-local-node.v1` 严格 JSONL envelope、canonical JSON、payload/result digest、最小请求 scope、Grant 超集授权、稳定错误与幂等槽；6 项协议单测、68 项校验和跨语言 golden vector 通过。该协议不包含发现、网络、认证或传输加密。
- [x] MCP→Android 适配边界：默认后端仍为 JSON Mock；`android-local-node` 只能显式启用，必须注入已认证 channel provider 和凭据引用。当前仅实现 `create_event`，其余操作稳定返回 `OPERATION_UNSUPPORTED`，超时、绑定错误和畸形响应会关闭并毒化 channel；没有 Python socket/LAN fallback。
- [x] Android `create_event`：经 separately verified session、Grant/space/type/sensitivity/data-class 检查和注入式原子幂等 registry 后写入真实 `MemoryRepository`；SQLCipher 关闭重开后仍可读。生产工厂默认关闭，当前 registry 仅测试进程内实现，不能声明跨重启幂等。
- [x] 合并后全链 Review：167 项 Python、Android 37 项 JVM、lint/assemble、API 36 AVD 普通回归 35 项（34 通过、1 项 DB-01 性能用例按设计跳过）、AI Eval 12/12、2,715/2,709 契约、Agent Local Node 68 项校验、14-case/14-risk Skill、37 个链接和 132 项治理检查通过。

### 下一执行批次

- [ ] CORE/AG 生产通道：实现设备认证、会话绑定、加密、重放保护、Android 生命周期和持久幂等 registry，再接真实 Codex/Claude Code/Cursor host；Python CoreStore 与注入 channel 只保留 Oracle/测试边界。
- [ ] SYNC-002/003：Android NSD/未来 iOS Network.framework 的发现、同账户设备认证、加密会话和冻结向量 conformance；不得把应用层协议通过当作 LAN 通过，iOS 部分等待 Mac。
- [ ] Android 设备矩阵：物理设备、16 KB page-size、OEM Calendar、系统录音结果授权、权限撤销、进程死亡、真实 Today/Search UI 性能与日期筛选优化。

### 当前门禁

1. SQLCipher 若不能在 API 34+/16KB 页面约束下工作，保留失败证据并走 ADR 的安全回退评审，不允许静默改成明文 SQLite。
2. Raw、日志、测试和 Git 只使用合成数据；密钥不得硬编码到 release 路径或日志。
3. iOS 不因当前缺少 Mac 被标记为已完成；Android 通过也不能替代双端 M3 证据。
