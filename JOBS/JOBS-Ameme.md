# Ameme 项目 Job

> 文档状态：当前执行中；MVP 工程实现与第九/十/十一/十二/十三/十四/十五/十六/十七/十八/十九/二十批双端产品完善并行推进，真实设备与发布 Gate 仍未关闭。
> 适合读者：产品、研发、设计、安全、测试、AI、发布协作人。
> 人类快速阅读：先看各批次标题、当前门禁和最后一条验证结果，再按任务域展开。
> AI 阅读提示：以本文件和 `STATUS.md` 为状态正本；不得把 AVD、Mock、合成评测或跳过 Gate 写成真机、生产或公开发布通过。

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
- [x] 本机 Codex 体验辅助：当前任务 7 条人工筛选结构化事件经 `ameme-memory` Skill/MCP Mock 形成一次性 seed，再由显式 androidTest seam 经 Agent Local Node 写入正式 SQLCipher；API 36 AVD 目标测试 1/1，Today 显示 7 条。真实内容未进入 Git，设备临时明文由测试/runner 删除；这不是生产传输或后台采集证据。
- [x] 体验辅助回归：Agent Python 回归由 28 增至 30，全仓 Python 由 167 增至 169；workspace validation、Android JVM/lint/debug APK/test APK 通过。普通设备基线未重跑，仍沿用 35 项基线并单列本次目标设备测试。

### 已集成第六批切片

- [x] Android DayLedger/Summary：SQLCipher 升级 v6，Event policy、DayLedger revision、Summary `insufficient/processing/ready/stale`、compare-and-set completion 和删除失效落地；用户明确同意后只向无状态网关发送合格结构化 Event，不包含照片/音频原文件、SourceLocator、搜索记录或 Restricted。
- [x] 推理网关：Node/TypeScript 服务实现严格 `ameme.day-event-projection.v1 → ameme.day-summary.v1`、事件/字节/token 边界、`store:false` OpenAI provider、deterministic fake、无正文 metadata log、Android `GMT`/固定时区支持。8 项通过，真实 OpenAI live 因无显式密钥/开关 1 项跳过；不写成真实模型质量证据。
- [x] Android 配对通道：设置页创建/撤销 30 天配对，Keystore 包裹一次性秘密，非导出 TLS identity，TLS 1.3/certificate pin/双向 HMAC/session/sequence/nonce listener 与 SQLCipher durable idempotency 接入生产 App 进程。paired Host smoke 证明 durable/local-only、Today 可见、重启保留、ADB Event 注入为 false。
- [x] 移动来源完整体验：合成照片/音频/日历只准备系统来源，正式 Picker/OpenDocument/Calendar/ACTION_SEND 路径负责写 Event；用户明确分享文字纠正为 `UserAsserted`，媒体在无 OCR/STT 时继续 `Processing`。
- [x] Review 修复：AVD `adb reverse` 无响应后把 Debug 网关限定为 `10.0.2.2`，Release 仍无内置地址；放宽合法 Android 时区但拒绝越界；更新 UI smoke 的正式文案与 onboarding test isolation。最终普通设备回归 52 项发现：45 通过、7 项显式 gate 跳过，0 失败；目标 UI 3/3、来源 7/7、网关 1/1。
- [x] 验证报告：`docs/quality/Android-MVP完整体验闭环验证-20260714.md` 判定 `conditional_pass`；只关闭 Android API 36 AVD 合成完整体验，不关闭真实用户、真实模型、iOS、真机、物理 LAN 或发布 Gate。

### 已集成第七批体验切片

- [x] Android 普通用户设备连接统一为同网自动发现、扫描二维码、账户设备三种入口；三者共享候选、授权、成功、持久状态和断开流程，不向普通用户暴露密钥、JSON、IP 或端口。
- [x] Debug deterministic connector 只模拟连接成功并在授权/成功/连接卡片中标注不建立真实网络连接；不创建 Event、Grant 或访问审计。Release provider 返回空，独立 Release 单测通过。
- [x] 现有真实 TLS 1.3/certificate pin/HMAC 手工配对保留，但下沉至 Debug 开发者选项；它继续承担 Host→Android 工程验证，不冒充普通用户发现协议。
- [x] 自动化与视觉验证：三入口单元测试、5/5 UI 主路径、2/2 状态存储、Release 隔离、Debug/Release build/lint，以及 API 36 AVD 56 项普通回归（49 通过、7 Gate 跳过、0 失败）。验证报告：`docs/quality/Android-Agent统一连接体验验证-20260715.md`，判定 `conditional_pass`。

### 本轮跨端体验收口（2026-07-17）

- [x] iOS：补齐 SwiftUI 原生 App 目标与导航壳，覆盖首次引导、今天日流、历史搜索/单日筛选、统一记录 Sheet、文字/照片/语音/文件/日历主动导入、事件详情补充、设置、Agent 三入口体验状态和删除进度。
- [x] iOS：本机事件与媒体引用使用 Keychain AES-GCM 加密；Summary 仅使用至少两条非受限、可用事件；未接入的 LAN、真实 Agent 网络和 AI 网关在 UI 中明确标注，不伪造成功。
- [x] Android：搜索页补回稳定设置入口；事件详情的补充写入真实 Revision/FTS/DayLedger；删除从“手动模拟步骤”改为一次确认后执行本机删除，失败保留可重试状态。
- [x] Android：移除无动作的状态 Chip/核验按钮和误导性的 mock/合成用户文案；空白今天页仍显示小结区；设置只对真正可操作的入口显示导航暗示。
- [x] 当前可复跑验证：`python3 scripts/governance/check_workspace.py` 通过（137 checks，0 errors，0 warnings）；iOS `swift build --target AmemeShared`、`swift build --target AmemeApp` 与 `swift run --package-path apps/ios AmemeSharedSmoke` 在当前 macOS Command Line Tools 环境通过；`git diff --check` 通过。完整 `swift test` 仍需 Xcode 提供 XCTest。
- [x] 当前环境限制已部分解除：使用临时 JDK 17、Android SDK 36 和 API 36 `google_apis_ps16k` arm64 AVD 完成 Android APK、JVM/lint、16 KB 安装启动、连接回归和截图/语义树审查；完整 Xcode/iOS SDK、iOS Simulator/真机与 Android 物理设备仍不可用，不写成已验证。

### 下一执行批次

- [ ] CORE/AG 后续：用共享账户 Grant registry 替代 pairing-scoped root claim，补 append/undo/recall、后台生命周期和真实 Codex/Claude Code/Cursor host；Python CoreStore 继续只作 Oracle/Host 适配，不进入 App Core。
- [ ] SYNC-002/003：在已落地的 Android NSD 与 iOS Bonjour/Network.framework 发现适配器上继续补短时二维码交换、账户设备列表与设备证明，统一进入共享 Grant；不得把发现、Debug 模拟、ADB 转发或 Host 通道通过写成物理 LAN/账户配对通过。
- [ ] AI live：在隐私/供应商评审和显式测试密钥后运行真实模型固定 Eval、延迟/token/成本与中文小结质量对比；未通过前 fake 只用于本地体验。
- [ ] Android 设备矩阵：物理设备、16 KB page-size、OEM Calendar、系统录音结果授权、权限撤销、进程死亡、真实 Today/Search UI 性能与日期筛选优化。

### 第八批双端产品完善（2026-07-18）

- [x] iOS：修复事件数量、搜索词、日期范围、来源、Revision 和连接方式被显示为字面量的问题；补齐开始/结束日期范围搜索。
- [x] iOS：增加不写入真实加密库的固定演示数据模式，覆盖正常、计划、待核验、整理中、受限、详情、删除和小结状态。
- [x] Android：增加同一日流的开始/结束日期范围协议、SQLCipher/Fake Repository 分页实现和 Compose 范围选择器；保留旧单日 API 兼容。
- [x] 双端：待核验/计划事件可选择“确认已发生”或“仍是计划”，真实追加 Revision，失败时保留当前状态并提示重试。
- [x] 双端：结构化 JSON 导出接入系统保存/分享入口；导出固定 Personal 空间，排除 Restricted、SourceLocator 和原始照片/音频文件。
- [x] 双端：补齐演示数据入口和可见边界提示；Android 演示切换使用内存 Fake Repository，退出后重新打开真实 SQLCipher。
- [x] iOS：使用现代 EventKit 全日历访问请求、显式麦克风授权反馈，并在本机加密写入失败时回滚事件/媒体状态。
- [x] 当前验证：iOS `swift build --target AmemeShared` 与 `swift build --target AmemeApp` 在当前 Command Line Tools 环境通过；Android 使用临时 JDK 17 与 Android SDK 36 完成 `:app:testDebugUnitTest`、`:app:lintDebug`、`:app:assembleDebug`，构建成功，lint 无阻断项；新增语音 Intent 的 `<queries>` 声明后，原有 package-visibility 警告已消除。
- [ ] 设备级验收（当时记录）：第八批写入时设备环境尚未提供；第九批已补齐 Android 16 KB AVD 子门，但 iOS Simulator/真机、Android 物理设备和完整无障碍证据仍待补。

### 第九批双端真实验收里程碑（2026-07-18，in_progress）

- 目标：把本轮已经完成的双端源码闭环推进到可审计的设备级体验证据，并关闭剩余的跨端视觉、无障碍和发布前回归门槛。
- [ ] iOS：在完整 Xcode/iOS SDK 环境打开 `apps/ios/Package.swift`，完成 iOS Simulator/真机 Debug 构建、单测、权限拒绝/撤销、演示模式、范围搜索、状态 Revision、导出和删除路径验收。
- [x] Android AVD：API 36 `google_apis_ps16k` arm64 AVD 页大小为 16,384，完成 APK 安装启动；`connectedDebugAndroidTest` 52 项可执行测试通过、7 项显式 Gate 跳过、0 失败。
- [ ] Android 物理设备：至少一台 Android 14+ 设备仍待补，覆盖权限撤销/进程重建/系统录音与日历导入、OEM、后台和真实 LAN。
- [x] Android 视觉证据：已保存并检查 Onboarding/Today/Capture/Text Entry/Event Detail/Delete Impact/Search/Date Picker/Settings 截图及 UIAutomator 树；修复日期选择器英文默认文案、记录保存成功不关 Sheet 和启动加载竞态。详细记录见 `docs/quality/Android-16KB-UI验收-20260718.md`。
- [ ] 完整无障碍：当前完成截图与 Compose/UIAutomator 语义检查；TalkBack、动态字体、旋转、焦点恢复、VoiceOver 仍待设备级验收。
- [ ] 双端闭环：Android 已完成真实本机文字记录→Today→Search/日期范围→详情→删除与演示模式边界检查；iOS 真实来源和固定 Mock 两套路径仍待完整 Xcode 环境复验。
- 退出条件：两端设备回归均无 P0/P1；截图/无障碍检查有证据；Android/iOS 构建与自动化测试通过；STATUS、JOBS 和验证报告同步到同一结论。当前仅 Android AVD 子门通过，里程碑保持 `in_progress`。

### 第十批双端产品完善里程碑（2026-07-18，in_progress）

- 目标：在第九批设备证据补齐的基础上，把 iOS 与 Android 的核心页面、状态语义、数据边界和故障反馈收敛为同一套可体验、可回归、可交付标准；真实本机路径与固定 Mock 路径都必须可完成体验闭环。
- [ ] 跨端体验矩阵：Today、记录、搜索/日期范围、详情/Revision、删除影响、设置/导出、演示模式逐项对齐正常、空白、加载、失败、受限和恢复状态；差异必须有明确平台理由并写入验证记录。
- [ ] iOS 真实验收：完整 Xcode/iOS SDK 下完成 Simulator/真机 Debug 构建、XCTest/XCUITest、权限拒绝与撤销、演示/真实本机往返、VoiceOver、Dynamic Type、旋转和恢复。
- [ ] Android 真实验收：物理 Android 14+ 设备补测 16 KB/SQLCipher、权限撤销、进程死亡、系统日历/录音、后台恢复、OEM 差异、TalkBack、Dynamic Font、旋转和真实 LAN；API 36 16 KB AVD 继续作为回归基线。
- [ ] 架构与交付门禁：共享契约/Mock fixture、双端 Repository/IO 边界、导出与删除策略保持 fail closed；构建、单测、UI 回归、语义树、截图、治理和文档结果必须可由干净环境复跑，不能把跳过项写成通过。
- [x] 当前可用环境补证：iOS `AmemeSharedSmoke` 已覆盖测试密钥注入下的加密写入/重载、日期范围、Revision、摘要、导出、删除和演示隔离；iOS App 源码通过 iOS 目标 parse；Android Debug 构建/单测/lint/APK 继续通过。以上不替代 iOS Keychain/权限/真机与 Android 物理设备证据。
- [x] 第十批本机可关闭差异：iOS 照片改为仅保存 Photos 不透明引用、音频恢复文件改为 AES-GCM 密文，并为本机存储错误提供重试与不可用时的记录入口禁用；Android 搜索失败提供可重试操作、记录方式节点声明 Button 语义。Smoke 已验证媒体密文、删除清理和照片引用；Android API 36 16 KB AVD 连接回归 52 项可执行测试通过、7 项显式跳过、0 失败。
- [x] 第十批来源边界与恢复证据：iOS/Android 的照片、音频、日历主动来源统一标记为 confidential；Android 照片事件与 iOS 对齐为用户已选择/已记录；iOS Smoke 已验证 KeyMaterial 失败后 `retryLoad()` 可恢复到 ready。复跑结果：iOS Shared/App 构建与 Smoke 通过，Android 单测/Lint/APK 通过，16 KB AVD 52 项可执行测试通过、7 项显式跳过、0 失败。
- [x] 第十批导入一致性：iOS 日历改为带 `eventkit://` 稳定引用的批量事务，重复导入幂等跳过，持久化失败整批回滚；Smoke 已执行覆盖成功、重复和失败不留部分事件，XCTest 测试源码同步覆盖同一矩阵。Android Calendar adapter 已有同等批量提交/取消/失败不暴露部分事件证据。
- 退出条件：双端真实与 Mock 核心闭环均可复跑；无 P0/P1 体验或数据边界问题；关键控件具备可读名称、状态和操作反馈；iOS/Android 构建与自动化测试通过；剩余跳过项、设备限制和发布风险均登记并获得明确处理结论。该里程碑与第九批设备补证并行，保持 `in_progress`。

### 第十一批隐私与设备交付里程碑（2026-07-18，in_progress）

- 目标：继续收口两个 Native Mobile 产品的隐私清理、真实设备准备和可复跑证据；任何没有真实设备支持的门禁都保持透明，不以本机替代证据。
- [x] iOS 录音生命周期：结束、取消和录音页离开统一删除临时音频文件，并释放音频会话；避免未加密临时录音残留或麦克风会话悬挂。
- [x] iOS 来源可靠性：只有 `AVAudioRecorder.record()` 真正启动才进入录音状态；空录音不会写入事件，并会给出重试/选择已有音频反馈；日历导入跳过没有稳定 `eventIdentifier` 的条目，维持 `eventkit://` 幂等引用。
- [x] 双端语义收口：Android 异常状态卡片的“查看”入口声明 Button 角色；iOS 事件行包含“仅本机”状态，删除进度向 VoiceOver 暴露当前步骤/跳过步骤。真实 TalkBack/VoiceOver 手势和动态字体仍待设备门禁。
- [x] 双端搜索语义：iOS 搜索加入来源标签字段，与 Android 一样支持按“日历/照片/主动输入”等来源词检索；Shared Smoke 与 XCTest 测试源码覆盖来源检索。
- [x] 双端导出语义：iOS/Android 结构化 JSON 的事实状态、事件类型、证据状态统一输出契约 wire value（如 `user_asserted`、`state_change`），不再暴露平台枚举命名差异；两端测试覆盖导出边界。
- [x] iOS 本机复验：App 目标编译、iOS 目标源码解析和 Shared Smoke 通过；Smoke 继续覆盖加密媒体、照片引用、日历批量提交/幂等/回滚、删除与演示隔离。
- [x] 设备事实登记：当前仅有 Command Line Tools，`xcodebuild`/`simctl` 不可用；Android 连接回归须使用 `/tmp/ameme-android-sdk` 固定 SDK 路径，不能写成物理设备通过。
- [ ] iOS 完整 Xcode：补 Simulator/真机 Debug、XCTest/XCUITest、Keychain、权限拒绝/撤销、VoiceOver、Dynamic Type、旋转和录音真实生命周期验证。
- [ ] Android 物理设备：补 Android 14+、OEM、进程重建、后台、真实录音/日历、TalkBack、动态字体、旋转和真实 LAN 验收；API 36 16 KB AVD 继续作为回归基线。
- 退出条件：录音临时数据无残留、双端真实与 Mock 闭环可复跑、设备级和无障碍证据完整、构建/测试/治理均通过；在此之前保持 `in_progress`。

### 第十二批双端体验收口里程碑（2026-07-18，in_progress）

- 目标：把两个 Native Mobile 产品从“核心能力已实现”推进到“主要页面和关键状态可持续体验、可复跑、可交付”，继续收口跨端设计差异、真实本机/固定 Mock 两条路径和最终设备门禁。
- [x] Android 深层体验复核：在 API 36 16 KB AVD 重新走通 Onboarding、Today、文字记录、真实事件、详情、删除影响/确认删除、Search、开始/结束日期选择和 Settings 底部；稳定帧未发现 P0/P1 设计问题。
- [x] Android 语义复核：本轮 UIAutomator 树确认记录入口、事件行、搜索/设置、日期选择器、删除流程和设置操作节点具备可读文本或 content description；截图审查仅作为辅助证据，不宣称完整无障碍通过。
- [x] 双端搜索/日历收口：iOS 多词搜索按最多 16 个空白分隔词逐词 AND 匹配；日历导入改为用户选择可读日历和今天/7 天/31 天范围后再查询，避免默认读取所有日历的固定窗口；App 目标构建、iOS 目标解析和 Shared Smoke 通过。
- [x] Android 真实/Mock 搜索一致性：Fake Repository 与 SQLCipher/LIKE 后端统一最多 16 个空白分隔词逐词 AND 规则，补充跨字段命中与不完整查询不命中单测，并通过 Debug 单测、lint、APK 和 16 KB AVD 回归。
- [x] Android 空白/稀疏状态对齐：普通本机路径按活动事件数自动解析 `空白`、`稀疏`、`正常`，加载/错误/离线/部分范围/权限受限状态优先保留；体验状态覆盖仍保持测试确定性，并通过单测、lint、APK 和 16 KB AVD 回归。
- [x] 双端来源状态可见：Android 设置展示照片按次选择、日历只读权限和系统录音入口状态；iOS 设置读取 Photos/AVAudioSession/EventKit 原生状态并保留拒绝后的手动降级路径；Android UI smoke、Debug 构建和 iOS App target build/目标 parse 通过。
- [ ] 双端体验矩阵：逐页关闭 Today、记录、搜索/日期范围、详情/Revision、删除、设置/导出、演示模式在正常、空白、加载、失败、受限和恢复状态上的差异；平台差异必须有理由和测试证据。
- [ ] iOS 真实设备门禁：完整 Xcode/iOS SDK 下完成 Simulator/真机、XCTest/XCUITest、VoiceOver、Dynamic Type、旋转、权限拒绝/撤销和真实来源生命周期。
- [ ] Android 物理设备门禁：至少一台 Android 14+ OEM 设备完成权限撤销、进程重建、后台、录音/日历、TalkBack、动态字体、旋转和真实 LAN。
- [ ] 交付同步：构建、单测、连接回归、截图/语义、治理和质量报告保持同一结论；所有缺失证据继续显式登记，不把 AVD、Mock 或跳过项升级为真机/生产通过。
- 退出条件：双端主要闭环真实本机与固定 Mock 均可复跑；无 P0/P1 体验和数据边界问题；关键控件具备可读名称、状态和反馈；iOS/Android 设备级、构建、自动化、无障碍和治理门禁完成。当前保持 `in_progress`。

### 第十三批主动来源与恢复里程碑（2026-07-18，in_progress）

- 目标：把两个产品的主动来源入口统一为“先确认、后落库”，让取消、权限不足和本机写入失败都保留用户控制权；同时明确真实 iOS Share Extension 与设备级门禁不能用文件选择器、Mock 或 AVD 代替。
- [x] Android 外部分享确认：`ACTION_SEND` 解析后先进入“保存分享内容？”确认页；确认才写入 Personal 本机节点，取消不创建 Event，落库失败保留待确认内容；UI smoke 覆盖确认前无事件、确认后 Today 可见。
- [x] iOS 文件导入确认：文本/音频文件选择后先在记录页展示文件名、文本预览或音频来源边界；确认才调用本机保存，取消不创建 Event；App target build、iOS 目标 parse 和 Shared Smoke 通过。
- [x] 双端导出恢复：Android 导出快照在系统保存取消/写入失败后保留并显示“重试保存导出”；iOS 设置在已有导出文件后将入口明确为“重新生成结构化导出”，两端均不修改源记录。
- [x] iOS incoming-share 桥接：Shared Core 提供受限文本/图片/PDF payload、App Group 文件 handoff 和只含 UUID 的 `ameme://incoming-share/<id>` URL；App 侧显示确认页并在确认后落库，Shared Smoke/XCTest 源码覆盖回读、拒绝和清理。
- [x] 双端边界说明：Android README、iOS README、质量报告和状态看板均登记“确认后落库”的新语义，以及 iOS 真实 Share Extension 尚未接入的限制。
- [ ] iOS 真实 Share Extension：在完整 Xcode target、App Group/受控共享文件和 Share Sheet 返回路径下接入同一确认/保存逻辑，并补权限拒绝、取消、重复回调和大内容边界测试；当前环境缺少 Xcode，不标记为完成。
- [ ] 设备与无障碍回归：补 iOS Simulator/真机、Android 物理设备、VoiceOver/TalkBack、Dynamic Type、旋转和进程恢复；第九至十二批门禁仍保持原状态。
- 退出条件：两端主动来源确认/保存/取消/失败语义一致且有自动化证据；真实 iOS Share Extension 完成；设备级、完整无障碍、构建和治理门禁仍全部登记并通过后，才可将本里程碑改为 `done`。

### 第十四批跨端状态与恢复矩阵里程碑（2026-07-18，in_progress）

- 目标：把 Today、记录、搜索/日期范围、详情/Revision、删除、设置/导出和演示模式的共同状态语义固化为可复跑矩阵；优先关闭本机与固定 Mock 可证明的设计差异，再保留真实设备门禁。
- [x] 共同状态规则：iOS `ExperienceMode.resolvedFor` 与 Android 使用同一 `0 → 空白、1 → 稀疏、2+ → 正常` 规则，加载、错误、离线、部分范围和权限受限显式状态优先。
- [x] iOS 状态落地：`LocalMemoryStore.mode` 复用共同规则，Shared XCTest 源码覆盖空白/稀疏/正常、显式错误优先和 Mock 保持确定性。
- [x] 双端页面矩阵：新增 `docs/quality/双端体验状态矩阵-20260718.md`，逐页登记状态、恢复动作、平台差异理由和当前证据，未把静态源码/AVD/Mock升级为真机通过。
- [x] 现有证据复核：Android 单测/lint/APK/16 KB AVD 与 iOS Shared/App target build/目标 parse/Smoke 仍保持上一批结论。
- [ ] iOS/Android 设备级状态回归：完整 Xcode、Simulator/真机、物理 Android、VoiceOver/TalkBack、Dynamic Type、旋转、进程恢复和真实来源生命周期仍待环境补齐。
- [ ] 矩阵最终退出：设备门禁、完整无障碍、真实 iOS Share Extension 和发布前构建/性能证据闭合后，才能把跨端体验矩阵和本里程碑改为 `done`。

- 退出条件：共同状态和恢复动作在本机/Mock 双路径可复跑，关键差异有平台理由和证据；剩余设备与发布门禁必须逐项通过，不能用条件通过替代。

### 第十五批真实 iOS 分享扩展输入里程碑（2026-07-18，in_progress）

- 目标：把真实 iOS Share Extension 接入所需的共享代码、App Group、扩展声明和失败恢复路径准备成可直接装配的 target 输入；在没有完整 Xcode 时仍只关闭静态契约和本机可复跑边界，不宣称系统分享页或签名通过。
- [x] 共享目录统一：`IncomingShareHandoffStore` 统一拥有 `group.com.ameme.ios` 标识和 `IncomingShares` 子目录，主 App 与 Extension 使用同一解析逻辑。
- [x] Extension target 输入：新增 `ShareViewController.swift`、Share Extension `Info.plist`、App/Extension entitlements 和接入说明；声明文字、URL、图片和单文件激活规则。
- [x] 分享安全边界：扩展只接受一项内容，支持文字/URL、图片、PDF；正文/文件不进入 URL，空内容、不支持类型、App Group 缺失、超过 16,384 字符/32 MB 和读取失败均 fail closed。
- [x] 生命周期恢复：扩展提供取消/重试，使用 `didStart`/`didFinish` 防重复回调；App 对同一 handoff UUID 幂等，不重复弹出确认页。
- [x] 静态与本机证据：Share Extension 源码与 App 目标解析通过，Info.plist/entitlements 校验通过，Shared/App build、bounded Shared Smoke 和 25 项静态输入治理检查通过。
- [ ] 完整 Xcode target：补 `.xcodeproj`/真实 extension target、签名、App Group provisioning、Share Sheet 返回主 App、重复 scene 激活和真实系统文件提供者测试；当前工具链缺少 Xcode。
- [ ] 设备与无障碍回归：iOS Simulator/真机、Android 物理设备、VoiceOver/TalkBack、Dynamic Type、旋转和进程恢复仍待设备环境。

- 退出条件：完整 Xcode target 能构建并签名，Share Sheet 的确认/取消/失败/重试/大内容边界在 iOS 设备上通过，且双端设备与无障碍门禁同时闭合后，才可将本里程碑改为 `done`。

### 第十六批双端待处理动作恢复与交付复验里程碑（2026-07-18，in_progress）

- 目标：让用户在系统分享、导出或 Activity/进程中断后，仍能安全恢复待处理动作；恢复阶段不提前写入事件，平台差异保留在原生 UI 和系统能力边界内，并用最新 Android AVD 与 iOS Shared Smoke 证据更新交付门槛。
- [x] Android 待处理动作存储：新增独立于 SQLCipher Event Node 的 `noBackupFilesDir/pending-actions` 加密快照，使用 Android Keystore AES-256-GCM、AAD、版本化 envelope、临时文件 + 原子替换和损坏 fail-closed；分享与导出可独立清理。
- [x] Android 生命周期恢复：Activity 重建后恢复分享确认和导出快照；本机仓库未就绪时确认按钮禁用并说明恢复状态，仓库就绪后才允许落库；取消、写入失败和清理失败均保留可恢复边界。
- [x] Android 自动化证据：加密快照 round-trip/明文不可见/损坏保留测试通过；新增 Activity recreation UI smoke；API 36 16 KB AVD 全量 62 项总测试中 55 项可执行通过、7 项显式 Gate 跳过、0 失败。
- [x] iOS handoff 启动恢复：`IncomingShareHandoffStore.pendingIDs()` 扫描共享目录中的受限 opaque handoff；App 启动时自动回读首个有效 handoff 并展示同一确认页，损坏或截断 handoff fail closed 且不触碰本机事件库；Shared Smoke 增加重启可发现断言。
- [x] 双端安全与设计复核：恢复前无 Event、确认后才写入、恢复中有明确不可操作反馈；日志不记录正文、URL、密钥或文件内容，文档同步最新证据与未关闭门禁。
- [x] iOS 静态无障碍契约：核心搜索、设置、日期、记录、编辑、删除、录音和恢复控件均有可读标签；错误状态保留“状态说明”和“重试”两个独立 VoiceOver 子元素；静态契约门通过，但不替代 VoiceOver 真机读屏。
- [ ] 完整设备门禁：iOS 完整 Xcode/真实 Share Sheet/App Group/签名/真机，Android 物理 Android 14+ OEM 的进程死亡、后台、权限撤销、TalkBack、动态字体、旋转和真实来源生命周期仍待环境补齐。
- [ ] 测试门禁：当前 Command Line Tools 下 `swift build --target AmemeApp`、目标源码 parse、Shared Smoke 通过；XCTest/XCUITest 需完整 Xcode/iOS SDK，不把 `swift test` 不可用写成测试通过。

- 退出条件：Android AVD、iOS Shared/静态边界和本机/Mock 闭环保持可复跑；完整 iOS target/设备、Android 物理设备和无障碍证据闭合后，才能将本里程碑改为 `done`。

### 第十七批双端结构化导出恢复与隐私清理里程碑（2026-07-18，in_progress）

- 目标：让 Android 与 iOS 的结构化导出在系统文档/分享流程中断后都能恢复同一份快照，并允许用户主动清除待导出内容；快照使用平台安全密钥加密，不提前改写事件源，不把演示数据快照带回真实模式。
- [x] iOS 加密待导出快照：新增 `PendingExportStore`，使用 Keychain 密钥、AES-GCM AAD、版本化 envelope、原子替换、大小上限和损坏 fail-closed；磁盘不保存导出正文。
- [x] iOS 恢复与清理体验：App 启动识别待导出快照，设置页提供“恢复上次结构化导出”和“清除导出恢复快照”；切换演示/真实数据时清理旧快照，避免模式串数据。
- [x] Android 清理体验：设置页为已有 Keystore 加密导出快照增加“清除未完成导出”；清理只删除待处理快照，不触碰本机事件；新增 UI smoke 覆盖 Activity 重建后清理。
- [x] 本机/Mock 证据：iOS App target build、Shared Smoke（导出密文往返/清理）和目标源码解析通过；Android Debug 单测/lint/APK、定向 UI smoke 和 API 36 16 KB AVD 全量 64 项（57 项可执行通过、7 项显式跳过、0 失败）通过。
- [ ] 设备级导出门禁：完整 Xcode/iOS Share Sheet、系统文件提供者取消/写入失败、进程终止恢复，以及 Android 物理设备/OEM 文档提供者、TalkBack、后台和旋转仍待补。

- 退出条件：双端导出快照的加密、恢复、清理和源数据不变在完整 Xcode/iOS 设备与 Android 物理设备上复验，且无障碍和发布前门禁同时闭合后，才能将本里程碑改为 `done`。

### 第十八批双端设备连接体验状态收口里程碑（2026-07-18，in_progress）

- 目标：让 iOS 与 Android 的三种普通用户连接入口共享同一可解释状态模型；Mock 连接只能证明授权/展示/断开体验，真实发现、二维码交换、账户设备和数据传输仍必须由平台实现与设备证据单独证明。
- [x] iOS 结构化状态：新增 `AgentConnectionMethod` 与 `AgentExperienceConnection`，统一自动发现、二维码、账户设备三种入口，记录设备、Agent、能力、连接时间、30 天期限和 `simulated` 标记。
- [x] iOS 安全边界：`AgentExperienceStore` 只持久化非敏感展示元数据；密钥、端点、证书 pin 和正文不进入 UserDefaults；损坏/未知版本/过期状态 fail closed 并自动清理。
- [x] iOS 体验闭环：设置页显示实际连接方式、能力、有效期和明确的“体验模式 · 不建立真实网络连接”，支持显式断开；三种入口都进入同一授权确认卡片。
- [x] 双端证据：iOS App build、Shared Smoke 和 Shared XCTest 源码覆盖三种方式往返、过期清理和损坏清理；Android 既有 `PairingExperienceStore`、三入口授权/成功/断开 UI smoke 与 Release 无模拟 provider 门禁保持通过。
- [x] Android 视觉/语义复核：API 36 16 KB AVD 当前运行截图覆盖启动、Today 空白态、设置、连接入口、授权、体验连接成功、期限展示和断开恢复；未发现需追加的 Android 设计修复。
- [x] 真实发现适配器：Android Release 使用 `NsdManager` 浏览 `_ameme-agent._tcp.`，iOS Shared 新增 Bonjour/Network.framework 浏览会话；两者仅产生非敏感候选，不把发现当作授权或连接成功。
- [ ] 真实连接门禁：Android/iOS 二维码与账户设备、共享 Grant registry、证书/会话绑定、真实数据传输、后台和物理设备仍待实现与验证；本轮不把发现或 Mock 状态升级为网络成功。

- 退出条件：两端真实发现/交换/授权和数据传输使用共享 Grant 语义在真实设备上通过，同时保留 Mock 双路径、断开/过期/撤销和无障碍证据后，才能将本里程碑改为 `done`。

### 第十九批双端真实发现适配与授权边界里程碑（2026-07-18，in_progress）

- 目标：把普通用户连接从仅 Debug Mock 推进到可运行的真实局域网发现边界；发现、授权、会话和数据写入必须分层，任何失败都保持未连接且不修改本机事件。
- [x] Android Release 发现：`NsdPairingExperienceConnector` 浏览 `_ameme-agent._tcp.`，解析设备/Agent/能力等受限 TXT 元数据；超时、无设备、二维码未接入、账户未授权和发现后未授权均使用稳定失败状态。
- [x] iOS Bonjour 发现：`BonjourAgentExperienceDiscovery` 使用 Network.framework，添加 `NSLocalNetworkUsageDescription` 与 `_ameme-agent._tcp` 声明；候选能力默认标为“能力待授权确认”，不写入连接状态。
- [x] 跨端诚实边界：发现候选 `simulated == false` 也不代表已授权；连接状态只有在后续认证/Grant 完成后才能持久化，模拟连接仍保留独立 Mock 路径。
- [x] 验证：Android Debug/Release 单测、lint、APK、API 36 16 KB AVD 全量 64/57/7/0；iOS Shared/App build、Shared Smoke 和 iOS 目标源码解析通过。
- [ ] 授权与传输：实现二维码短时交换、账户设备服务、共享 Grant registry、证书/会话绑定和双端真实数据传输；补真实 Agent advertiser/host、LAN 恶意 peer、后台恢复和物理设备证据。

- 退出条件：Android/iOS 在真实设备上完成发现→明确授权→Grant 绑定→受限数据操作→撤销/过期，并通过无障碍、后台和删除收敛门禁后，才能将本里程碑改为 `done`。

### 第二十批双端连接入口与真实/演示体验对齐里程碑（2026-07-18，in_progress）

- 目标：让 iOS 与 Android 的普通用户连接页都把真实发现、授权前候选、失败反馈和显式演示路径分开；任何只有发现没有授权的结果都不得写成已连接。
- [x] iOS 真实入口接入：设置页的同一局域网入口调用 Bonjour/Network.framework 发现；二维码和账户设备在服务未接入时给出明确错误，不再静默落成模拟连接。
- [x] iOS 授权前体验：候选展示设备、Agent、方式、能力和授权边界；点击允许后由 connector 决定是否已完成认证，未完成时保留页面并显示 fail-closed 反馈。
- [x] iOS 演示路径隔离：增加明确的“试用演示连接（不联网）”入口；只有显式演示动作才保存 `simulated == true` 状态，真实候选不写入 UserDefaults。
- [x] 双端契约证据：iOS Shared/App build、目标源码解析与 Smoke 覆盖模拟连接往返和发现候选不得晋升为连接；Android Release NSD、Debug Mock、单测/lint/APK 与 AVD 基线保持通过。
- [ ] 真实授权闭环：二维码短时交换、账户设备服务、共享 Grant registry、证书/会话绑定、iOS/Android 真实 Local Node client 与 Agent advertiser/host 仍待实现；在此之前不宣称真实数据传输通过。

- 退出条件：两端真实入口均能完成发现→明确授权→Grant 绑定→受限数据操作→撤销/过期，且真实/演示路径在无障碍、后台和设备回归中均可区分后，才能将本里程碑改为 `done`。

### 第二十一批双端 AccessGrant 授权契约与本地策略门禁里程碑（2026-07-18，in_progress）

- 目标：让 iOS 与 Android 在真实连接尚未完成时先共享同一份最小授权语义；用户确认的配对范围必须能约束每次 Agent 请求，过期、撤销、身份不匹配或扩权都 fail closed，且 Mock 与真实发现路径继续清楚分离。
- [x] 跨端契约模型：iOS `AgentAccessGrant`/`AgentAccessGrantPolicy` 与 Android 对齐 canonical AccessGrant 的 schema version、caller/Grant 绑定、purpose、space、data type、有效期、撤销和有界 scope；新增 iOS XCTest 源码与 Shared Smoke、Android JVM 测试。
- [x] Android 本地门禁：显式创建开发者配对时记录 Personal/`autonomous_memory`/structured `event` 的 30 天本地批准策略；恢复时缺少或损坏策略即撤销配对；运行时将 Host claim 绑定为 request-time Grant，并在写入前拒绝身份、期限、purpose、space 和 data type 扩权。
- [x] 双端授权展示：iOS/Android 授权确认卡片显示“拟授权范围：Personal 空间 · autonomous_memory · 结构化 event · 30 天”，不把发现候选或演示连接冒充真实传输；Android 连接页与 iOS 设置页继续保留显式演示标识。
- [ ] 真实授权闭环：共享外部 Grant registry、二维码短时交换、账户设备服务、Android/iOS 真实 Agent advertiser/host、撤销传播和物理设备证据仍待实现；iOS 已补齐与 Android/Python 对齐的 Local Node TLS/HMAC 编解码、Grant-bound create_event builder 和 Network.framework TLS 1.3 client，但当前环境未提供可运行的 iOS target/真机，因此不宣称真实数据传输已通过。
- [x] 验证门禁：使用 `/tmp/ameme-jdk17`、Android SDK 36 和 API 36 16 KB arm64 AVD 重跑 Android Debug/Release JVM、lint、assemble、全量连接回归（64 总计 / 57 可执行通过 / 7 显式跳过 / 0 失败）及 M21 授权 UI 定向 smoke（1/1）；配对 Host→TLS/HMAC→Android→SQLCipher→Today smoke 通过，重启后事件仍可见且未用 ADB 注入；TalkBack AVD 语义探针完成 Today→设置→三入口→授权卡片→演示成功路径；iOS Shared/App build、Smoke、目标解析、Share 输入 25 项和 accessibility 静态契约 14 项通过。
- [ ] 设备级门禁：完整 Xcode/XCTest/Simulator、iOS/Android 物理设备、VoiceOver/TalkBack、后台/旋转和真实 QR/账户/共享 Grant/数据传输仍待补。

- 退出条件：两端真实连接完成发现→明确授权→Grant 绑定→受限数据操作→撤销/过期，并以设备级无障碍、后台、旋转和恢复证据复验；在此之前 M21 保持 `in_progress`。

### 第二十二批 iOS Local Node 协议与真实连接边界里程碑（2026-07-18，in_progress）

- 目标：让 iOS 与 Android 使用同一套 Local Node v1 通道语义；协议实现必须可由 Android/Python golden 验证，真实连接必须经过 TLS 1.3、证书 pin、HMAC session、序列/nonce/replay 检查和 active Grant，失败时清理密钥并关闭连接。
- [x] 跨端协议编解码：新增 iOS `AgentLocalNodeChannelCodec`，覆盖 canonical JSON、pairing parser、client/server hello、session key、request/response frame、digest、HMAC 和 bounded replay ledger；Shared Smoke 与 Android/Python golden 向量逐字一致。
- [x] Grant-bound 应用请求：新增 iOS `AgentLocalNodeCreateEventDraft` 和 `buildCreateEventRequest`，在序列化前校验 caller/Grant、purpose、space、data type、期限和 write scope；过期 Grant 不产生写请求。
- [x] 真实网络边界：新增 Network.framework TLS 1.3 `AgentLocalNodeNetworkClient`，校验证书 SHA-256 pin，按序发送/接收 canonical frame；`BonjourAgentExperienceConnector` 只接受注入的 pairing/credential/active Grant factory，默认发现候选仍授权 fail closed。
- [x] 通道生命周期：Bonjour connector 持有已认证 client registry，AppModel 断开时显式关闭底层 actor/channel；不会出现仅保存“已连接”展示状态、实际 session 已释放的假连接。
- [x] 当前可复现证据：`swift build --target AmemeShared`、`swift run AmemeSharedSmoke`、`swift build --target AmemeApp`、iOS 目标源码 parse、Share 输入验证、accessibility 静态契约和 workspace validation 均通过；Smoke 新增 Android/Python channel golden 与 Grant-bound write request。
- [x] 跨端真实传输子门：`scripts/dev/agent/smoke_ios_network_to_android.py` 在 API 36 / 16 KB AVD 上由 Swift Network.framework client 通过 ADB forward 完成 TLS 1.3/certificate pin/HMAC、Grant-bound `create_event`，事件在 Android Today 可见；pairing secret、forward 和临时构建产物均在 finally 清理，输出不含正文。
- [ ] 真实端到端门禁：完整 Xcode/XCTest/Simulator、iOS/Android 物理设备、Android advertiser 与 iOS client 的 TLS/Grant 实连、二维码/账户设备 registry、撤销传播、后台/旋转、VoiceOver/TalkBack 仍待补；未把编解码通过写成设备传输通过。

- 退出条件：iOS/Android 在真实设备完成发现→明确授权→Grant 绑定→受限 create_event→撤销/过期，并完成设备级无障碍、后台、旋转与恢复证据后，才能将 M22 改为 `done`。

### 第二十三批双端配对解析与连接生命周期收口里程碑（2026-07-18，in_progress）

- 目标：在二维码/账户 registry 和物理设备接入前，先把配对输入的严格解析、断开语义和“展示状态不等于活跃 session”的边界固定下来；任何 malformed pairing、重复 JSON key 或断开失败都不得静默晋升为已连接。
- [x] iOS 严格配对解析：`AgentLocalNodePairingMaterial.parse` 复用重复 key/JSON grammar scanner，拒绝 duplicate key、非法 framing 和未知字段，不让 Foundation 的 last-value 语义越过网络边界。
- [x] 双端断开契约：Android `PairingExperienceConnector` 与 iOS `AgentExperienceConnector` 都提供显式 `disconnect`；Android 设置页清理非敏感连接状态前通知 connector，iOS Bonjour connector 从 active client registry 移除并关闭 Network.framework actor。
- [x] 回归证据：iOS App build、Shared Smoke、Android Debug/Release JVM 与 lint 通过；Shared Smoke 新增重复配对 key 拒绝，Android 连接 UI smoke 继续覆盖允许/成功/断开恢复。
- [ ] 真实配对入口：定义并实现带短时 one-time secret 的跨端 QR envelope、iOS/Android 相机扫描与导入校验；不把手动复制 pairing JSON/secret 误标为普通用户扫码体验。
- [x] 状态恢复真相：真实 client/credential 可安全恢复前，进程重启会清理非模拟连接展示元数据；演示连接仍可恢复，真实状态不会被标为活跃传输。
- [ ] 设备级门禁：完整 Xcode/XCTest/Simulator、物理设备、后台/旋转、VoiceOver/TalkBack 和撤销传播仍与 M22 共用未闭合门禁。

- 退出条件：配对 envelope 在 iOS/Android/Python 之间逐字一致，二维码扫描→授权→Grant-bound 传输→断开/重连可在真实设备通过，且重启、过期、撤销和无障碍证据闭合后，才能将 M23 改为 `done`。

### 当前门禁

1. SQLCipher 若不能在 API 34+/16KB 页面约束下工作，保留失败证据并走 ADR 的安全回退评审，不允许静默改成明文 SQLite。
2. Raw、日志、测试和 Git 只使用合成数据；密钥不得硬编码到 release 路径或日志。
3. iOS 不因当前缺少 Mac 被标记为已完成；Android 通过也不能替代双端 M3 证据。
