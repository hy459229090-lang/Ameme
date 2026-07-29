# Ameme 项目 Job

> 文档状态：M24 双端仓库工程候选已完成；真实/Mock 核心闭环和模拟器/AVD 设备门已收口，物理设备与公开发布 Gate 仍未关闭。
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

- 状态：Goal `active`；Job `in_progress`；仓库内候选 `conditional_pass`，真实用户与生产 Gate `hold`
- 目标：按 M1–M6 依赖把研发前正本变成可构建、可复跑、可恢复的真实实现；Gate 1/2、真实数据和公开发布边界保持不变。
- 当前环境：macOS Command Line Tools 可运行 Swift Shared/App/Smoke；Android JDK 17 + SDK 36 + API 36 16 KB arm64 AVD 可运行；完整 iOS App/Extension/Unit/UI Test 由 GitHub macOS 15 + Xcode 16.4 CI 执行，物理 iOS/Android 仍待发布负责人提供。

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
- [x] MCP→Android 适配边界（第五批历史状态）：默认后端仍为 JSON Mock；`android-local-node` 只能显式启用，必须注入已认证 channel provider 和凭据引用。该批次只实现 write-only `create_event`/`append_revision`；后续 exact undo 能力见当前生产切片，read/policy 操作仍稳定返回 `OPERATION_UNSUPPORTED`。超时、绑定错误和畸形响应会关闭并毒化 channel；没有 Python socket/LAN fallback。
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

- [ ] CORE/AG 后续：用共享账户 Grant registry 替代 pairing-scoped root claim，在真实 Codex/Claude Code/Cursor + 物理设备纵向执行 bounded read/ContextPack/Recall、`append_revision`/exact undo，补跨端撤销传播和后台生命周期；Python CoreStore 继续只作 Oracle/Host 适配，不进入 App Core。
- [ ] SYNC-002/003：短时 QR Bootstrap v2 已在 Android host/iOS client 的 AVD + ADB-forward 路径落地；继续补 Android scanner client、账户设备列表、共享 Grant registry 与设备证明。不得把发现、Debug 模拟、ADB 转发或 Host 通道通过写成物理 LAN/账户配对通过。
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
- [ ] 真实连接门禁：QR Bootstrap v2 的 Android host/iOS client 仓库路径、证书/会话绑定与 AVD 纵向传输已形成；Android scanner、账户设备、共享 Grant registry、后台、真实 LAN/用户和物理设备仍待实现与验证。本轮不把发现、Mock 或 ADB forward 升级为这些 Gate 通过。

- 退出条件：两端真实发现/交换/授权和数据传输使用共享 Grant 语义在真实设备上通过，同时保留 Mock 双路径、断开/过期/撤销和无障碍证据后，才能将本里程碑改为 `done`。

### 第十九批双端真实发现适配与授权边界里程碑（2026-07-18，in_progress）

- 目标：把普通用户连接从仅 Debug Mock 推进到可运行的真实局域网发现边界；发现、授权、会话和数据写入必须分层，任何失败都保持未连接且不修改本机事件。
- [x] Android Release 发现：`NsdPairingExperienceConnector` 浏览 `_ameme-agent._tcp.`，解析设备/Agent/能力等受限 TXT 元数据；超时、无设备、账户未授权和发现后未授权均使用稳定失败状态。后续 QR v2 已接 Android host 输出，但 Android scanner/client 仍未接入。
- [x] iOS Bonjour 发现：`BonjourAgentExperienceDiscovery` 使用 Network.framework，添加 `NSLocalNetworkUsageDescription` 与 `_ameme-agent._tcp` 声明；候选能力默认标为“能力待授权确认”，不写入连接状态。
- [x] 跨端诚实边界：发现候选 `simulated == false` 也不代表已授权；连接状态只有在后续认证/Grant 完成后才能持久化，模拟连接仍保留独立 Mock 路径。
- [x] 验证：Android Debug/Release 单测、lint、APK、API 36 16 KB AVD 全量 64/57/7/0；iOS Shared/App build、Shared Smoke 和 iOS 目标源码解析通过。
- [ ] 授权与传输：二维码短时交换与 Android host/iOS client 的 TLS/Grant-bound AVD 传输已落地；继续实现 Android scanner、账户设备服务、共享 Grant registry、真实 Agent advertiser/client、LAN 恶意 peer、后台恢复和物理设备证据。

- 退出条件：Android/iOS 在真实设备上完成发现→明确授权→Grant 绑定→受限数据操作→撤销/过期，并通过无障碍、后台和删除收敛门禁后，才能将本里程碑改为 `done`。

### 第二十批双端连接入口与真实/演示体验对齐里程碑（2026-07-18，in_progress）

- 目标：让 iOS 与 Android 的普通用户连接页都把真实发现、授权前候选、失败反馈和显式演示路径分开；任何只有发现没有授权的结果都不得写成已连接。
- [x] iOS 真实入口接入：设置页的同一局域网入口调用 Bonjour/Network.framework 发现；账户设备在服务未接入时给出明确错误，不再静默落成模拟连接。后续二维码入口已接 QR v2 Android host/iOS client production transport。
- [x] iOS 授权前体验：候选展示设备、Agent、方式、能力和授权边界；点击允许后由 connector 决定是否已完成认证，未完成时保留页面并显示 fail-closed 反馈。
- [x] iOS 演示路径隔离：增加明确的“试用演示连接（不联网）”入口；只有显式演示动作才保存 `simulated == true` 状态，真实候选不写入 UserDefaults。
- [x] 双端契约证据：iOS Shared/App build、目标源码解析与 Smoke 覆盖模拟连接往返和发现候选不得晋升为连接；Android Release NSD、Debug Mock、单测/lint/APK 与 AVD 基线保持通过。
- [ ] 真实授权闭环：QR Bootstrap v2、iOS production client 与 Android host 的证书/会话绑定及 AVD 纵向传输已落地；Android scanner/client、账户设备服务、共享 Grant registry、真实 LAN/用户、后台和物理设备仍待实现。在此之前不宣称设备级真实传输通过。

- 退出条件：两端真实入口均能完成发现→明确授权→Grant 绑定→受限数据操作→撤销/过期，且真实/演示路径在无障碍、后台和设备回归中均可区分后，才能将本里程碑改为 `done`。

### 第二十一批双端 AccessGrant 授权契约与本地策略门禁里程碑（2026-07-18，in_progress）

- 目标：让 iOS 与 Android 在真实连接尚未完成时先共享同一份最小授权语义；用户确认的配对范围必须能约束每次 Agent 请求，过期、撤销、身份不匹配或扩权都 fail closed，且 Mock 与真实发现路径继续清楚分离。
- [x] 跨端契约模型：iOS `AgentAccessGrant`/`AgentAccessGrantPolicy` 与 Android 对齐 canonical AccessGrant 的 schema version、caller/Grant 绑定、purpose、space、data type、有效期、撤销和有界 scope；新增 iOS XCTest 源码与 Shared Smoke、Android JVM 测试。
- [x] Android 本地门禁：显式创建开发者配对时记录 Personal/`autonomous_memory`/structured `event` 的 30 天本地批准策略；恢复时缺少或损坏策略即撤销配对；运行时将 Host claim 绑定为 request-time Grant，并在写入前拒绝身份、期限、purpose、space 和 data type 扩权。
- [x] 双端授权展示：iOS/Android 授权确认卡片显示“拟授权范围：Personal 空间 · autonomous_memory · 结构化 event · 30 天”，不把发现候选或演示连接冒充真实传输；Android 连接页与 iOS 设置页继续保留显式演示标识。
- [ ] 真实授权闭环：QR Bootstrap v2 已补齐 Android host/iOS client 的短时交换、签发时 P-256 key binding、独立 credential 与重启恢复；iOS 也已通过 Network.framework 在 API 36 / 16 KB AVD 经 ADB forward 完成四操作纵向 Smoke。共享外部 Grant registry、Android scanner/client、账户设备服务、撤销传播、真实扫码/物理 LAN/设备或第三方宿主仍待实现；普通用户入口继续只授予 event-only scope。
- [x] 验证门禁：使用 `/tmp/ameme-jdk17`、Android SDK 36 和 API 36 16 KB arm64 AVD 重跑 Android Debug/Release JVM、lint、assemble、全量连接回归（64 总计 / 57 可执行通过 / 7 显式跳过 / 0 失败）及 M21 授权 UI 定向 smoke（1/1）；配对 Host→TLS/HMAC→Android→SQLCipher→Today smoke 通过，重启后事件仍可见且未用 ADB 注入；TalkBack AVD 语义探针完成 Today→设置→三入口→授权卡片→演示成功路径；iOS Shared/App build、Smoke、目标解析、Share 输入 25 项和 accessibility 静态契约 14 项通过。
- [ ] 设备级门禁：完整 Xcode/XCTest/Simulator、iOS/Android 物理设备、VoiceOver/TalkBack、后台/旋转和真实 QR/账户/共享 Grant/数据传输仍待补。

- 退出条件：两端真实连接完成发现→明确授权→Grant 绑定→受限数据操作→撤销/过期，并以设备级无障碍、后台、旋转和恢复证据复验；在此之前 M21 保持 `in_progress`。

### 第二十二批 iOS Local Node 协议与真实连接边界里程碑（2026-07-18，in_progress）

- 目标：让 iOS 与 Android 使用同一套 Local Node v1 通道语义；协议实现必须可由 Android/Python golden 验证，真实连接必须经过 TLS 1.3、证书 pin、HMAC session、序列/nonce/replay 检查和 active Grant，失败时清理密钥并关闭连接。
- [x] 跨端协议编解码：新增 iOS `AgentLocalNodeChannelCodec`，覆盖 canonical JSON、pairing parser、client/server hello、session key、request/response frame、digest、HMAC 和 bounded replay ledger；Shared Smoke 与 Android/Python golden 向量逐字一致。
- [x] Grant-bound 应用请求：首批新增 iOS `AgentLocalNodeCreateEventDraft` 和 `buildCreateEventRequest`；后续 P0 客户端切片补齐 `append_revision`、exact `undo_capture`、bounded `visible_events` builder 与 typed response parser。四操作均在序列化前校验 caller/Grant、purpose、space、data type、期限和 exact scope；普通用户连接入口仍保持 event-only，过期或扩权 Grant 不产生请求。
- [x] 真实网络边界：新增 Network.framework TLS 1.3 `AgentLocalNodeNetworkClient`，校验证书 SHA-256 pin，按序发送/接收 canonical frame；`BonjourAgentExperienceConnector` 只接受注入的 pairing/credential/active Grant factory，默认发现候选仍授权 fail closed。
- [x] 通道生命周期：Bonjour connector 持有已认证 client registry，AppModel 断开时显式关闭底层 actor/channel；不会出现仅保存“已连接”展示状态、实际 session 已释放的假连接。
- [x] 当前可复现证据：`swift build --target AmemeShared`、`swift run AmemeSharedSmoke`、`swift build --target AmemeApp`、iOS 目标源码 parse、Share 输入验证、accessibility 静态契约和 workspace validation 均通过；Smoke 新增 Android/Python channel golden 与 Grant-bound write request。
- [x] 跨端真实传输子门：`scripts/dev/agent/smoke_ios_network_to_android.py` 在 API 36 / 16 KB AVD 上由 Swift Network.framework client 通过 ADB forward 完成 TLS 1.3/certificate pin/HMAC、Grant-bound `create_event`，事件在 Android Today 可见；pairing secret、forward 和临时构建产物均在 finally 清理，输出不含正文。该历史证据只签收 `create_event`，不覆盖后来加入 Smoke 的读取、Revision 和 exact undo 序列。
- [ ] 真实端到端门禁：Android host 与 iOS client 的 QR v2 + TLS/Grant-bound AVD 实连已完成；完整 Xcode/XCTest、Android scanner、iOS/Android 物理设备、真实 LAN、账户/共享 Grant registry、撤销传播、后台/旋转、VoiceOver/TalkBack 仍待补；未把 AVD/ADB-forward 写成设备级传输通过。

- 退出条件：iOS/Android 在真实设备完成发现→明确授权→Grant 绑定→受限 create_event→撤销/过期，并完成设备级无障碍、后台、旋转与恢复证据后，才能将 M22 改为 `done`。

### 第二十三批双端配对解析与连接生命周期收口里程碑（2026-07-18，in_progress）

- 目标：在二维码/账户 registry 和物理设备接入前，先把配对输入的严格解析、断开语义和“展示状态不等于活跃 session”的边界固定下来；任何 malformed pairing、重复 JSON key 或断开失败都不得静默晋升为已连接。
- [x] iOS 严格配对解析：`AgentLocalNodePairingMaterial.parse` 复用重复 key/JSON grammar scanner，拒绝 duplicate key、非法 framing 和未知字段，不让 Foundation 的 last-value 语义越过网络边界。
- [x] 双端断开契约：Android `PairingExperienceConnector` 与 iOS `AgentExperienceConnector` 都提供显式 `disconnect`；Android 设置页清理非敏感连接状态前通知 connector，iOS Bonjour connector 从 active client registry 移除并关闭 Network.framework actor。
- [x] 回归证据：iOS App build、Shared Smoke、Android Debug/Release JVM 与 lint 通过；Shared Smoke 新增重复配对 key 拒绝，Android 连接 UI smoke 继续覆盖允许/成功/断开恢复。
- [x] 仓库配对入口：跨端 QR 已升级为 `ameme.agent-pairing-bootstrap.v2`；Android server 持久包裹并原子消费 5 分钟 bootstrap，在 TLS pin 下验证 P-256 client-key possession 后签发独立 30 天 channel credential，同 key 仅在有界 receipt 窗重取，Release 无 developer bearer。iOS 相机/粘贴 parser 保存 device-only Keychain pending/active 状态，启动后实际重连成功才恢复 UI；冻结应用 v1 通道不接受 QR secret。
- [ ] 真实设备配对门：Android 当前仍是 Local Node host/QR 输出端，相机扫描→production client transport 未实现；双端物理相机、真实 LAN、后台/进程终止、并发首扫、撤销传播、共享 Grant 与每连接 P-256 proof 均未证明。不得把 AVD/ADB-forward、手动复制或签发时 key binding 写成这些 Gate 通过。
- [x] 状态恢复真相：真实 client/credential 可安全恢复前，进程重启会清理非模拟连接展示元数据；演示连接仍可恢复，真实状态不会被标为活跃传输。
- [ ] 设备级门禁：完整 Xcode/XCTest/Simulator、物理设备、后台/旋转、VoiceOver/TalkBack 和撤销传播仍与 M22 共用未闭合门禁。

- 退出条件：配对 envelope 在 iOS/Android/Python 之间逐字一致，二维码扫描→授权→Grant-bound 传输→断开/重连可在真实设备通过，且重启、过期、撤销和无障碍证据闭合后，才能将 M23 改为 `done`。

### 第二十四批双端真实构建与设备交付里程碑（2026-07-26，done）

- 目标：把 iOS/Android 的真实本机与固定 Mock 核心闭环、跨端功能/设计差异、可装配工程、自动化、无障碍、设备证据和仓库交付门统一收口。
- [x] iOS 可装配工程：XcodeGen 2.46.0 冻结 App、静态 Shared Core、嵌入式 Share Extension、Unit/UI Tests 和共享 scheme；静态 framework 只链接、不错误嵌入，App/Extension bundle 元数据受门禁。
- [x] Android 构建与设备：Debug/Release JVM、Lint、APK、测试 APK 同轮通过；API 36 16 KB arm64 AVD、`font_scale=1.3` 下 67 discovered / 60 passed / 7 显式 Gate skipped / 0 failed。
- [x] 真实/Mock UX：双端均提供隔离的固定演示数据；Android 大字号 Onboarding CTA 固定可见，真实 Today 和 Mock Today 当前截图/语义树无 P0/P1；iOS XCUITest 覆盖 Mock→Today→Settings→Search、深色、无障碍超大字体与旋转。
- [x] 跨端真连接：QR envelope 的 HMAC secret 表示统一，Swift Network.framework 经 TLS 1.3/pin/HMAC/Grant 写入 Android SQLCipher/Today；Host→Android 路径重启仍可见且 ADB 未注入事件。
- [x] 仓库门禁：14/14 workspace gates、iOS project 44、Share inputs 30、iOS accessibility 25、QR contract 32、governance 143 和 diff 检查通过；实现证据以 PR #1 最新实现提交为准，状态文档提交不改变实现范围。
- [x] 最新 PR head CI 签收：workspace、iOS、Android 全部绿色；iOS 22 Unit + 1 XCUITest 及 4 张当前运行截图已下载并视觉复核；运行链接以 PR #1 Checks 和三份 workflow 历史为准。
- [ ] 发布保留门：至少一台 iPhone/iPad 和一台 Android 14+ OEM 物理设备、真实 VoiceOver/TalkBack、签名/App Group/Share Sheet、来源权限撤销、后台/旋转、物理 LAN、商店隐私申报和回滚演练。
- 验证报告：`docs/quality/M24-双端真实构建与设备交付验证-20260726.md`。
- 退出条件：仓库门可在 CI 全绿后关闭；物理设备 Beta 与公开发布维持独立 `hold`，不得由 Simulator/AVD/Mock 代替。

### 第二十五批双端平台视觉升级与设备复验里程碑（2026-07-26，done）

- 目标：在不改变真实本机与固定 Mock 闭环的前提下，把 iOS “今天”页升级为更简洁的 iOS 26 Liquid Glass 控制层，并把 Android 从早期基础 Material 3 视觉升级到当前稳定 Material 3 平台表达；以设备截图、字体缩放、语义树和真实构建复验跨端差异。
- [x] 视觉目标：iOS 以已选第一版方向为基础进一步减法，保留暖白内容画布、单列时间流、一个顶部玻璃控制面和一个右下记录控制；内容行不玻璃化，不新增装饰性卡片、标签轨道或自定义玻璃绘制。
- [x] iOS 平台实现：iOS 26+ 使用 SwiftUI 原生 `glassEffect` / prominent glass button；iOS 18–25 使用系统 Material 降级。Today 搜索/设置保持独立 44 pt 语义按钮，记录入口保留禁用状态和减少动态效果兼容。
- [x] Android 平台实现：保持 stable Material 3 / Compose BOM 生产基线，不采用仍为 alpha/internal 的 Expressive API；补齐类型、形状、明暗色和受控动态色，改为成组顶部操作、安静文字状态、原生 FAB，并在大字体下切换纵向事件布局。
- [x] 本机构建门：Android Debug 单测、Lint、Debug APK 和测试 APK 通过；iOS Shared/App 包级构建、目标源码解析、Project/Share/Accessibility 静态契约与工作流 YAML 通过。
- [x] Android 设备复验：API 36 / 16 KB AVD 的 Debug/Release、设备回归与截图 artifact 全绿；本地 130%/200% 字号同屏和 UIAutomator 语义树无 P0/P1。
- [x] iOS 渲染复验：Xcode 26.6 / iOS 26.5 Simulator 的 App/Share Extension、22 Unit、默认浅色与深色 XXXL XCUITest 全绿；设计参考、默认实现和无障碍实现已在同一输入中签收。
- [x] 发布保留门已登记：物理 iPhone/iPad、Android 14+ OEM、真实 VoiceOver/TalkBack、签名/App Group/Share Sheet、权限撤销、后台/旋转、物理 LAN 和商店流程继续 `hold`。
- 最终 CI：workspace `30193059061`、iOS `30193059096`、Android `30193059066`；实现证据提交 `229d12a`。
- 验证报告：`docs/quality/M25-双端平台视觉升级与设备复验-20260726.md`；双端细项见 `apps/ios/design-qa.md` 与 `apps/android/design-qa.md`。
- 退出条件：同一 Mock 状态的双端当前截图无 P0/P1 视觉或可用性问题，核心真实/Mock 闭环回归不退化，字体缩放和语义证据可复跑；物理设备与发布门必须继续单独登记，不能由模拟器替代。

### 当前门禁

1. SQLCipher 若不能在 API 34+/16KB 页面约束下工作，保留失败证据并走 ADR 的安全回退评审，不允许静默改成明文 SQLite。
2. Raw、日志、测试和 Git 只使用合成数据；密钥不得硬编码到 release 路径或日志。
3. iOS 不因当前缺少 Mac 被标记为已完成；Android 通过也不能替代双端 M3 证据。

## 一天上下文覆盖与长期记忆 P0

- 状态：`in_progress`
- Goal：完整执行 `docs/product/目标用户一天上下文覆盖与来源优先级.md`，先形成可复跑覆盖基线，再关闭当前仓库能够验证的 P0 编译、治理、恢复与复用闭环；真实用户、物理设备、市场规模和发布权限继续作为不可替代 Gate。
- 当前切口：T0 AI 使用者中的知识工作者；Mobile 普通用户主路径继续独立成立。
- 并行保护：另一 Codex session 正在调整设计样式。本 Job 不修改颜色、字体、圆角、间距、图标、动效和纯视觉 token；开始每批实现前检查工作树与最近提交。若必须触及同一页面，只提交领域/状态/接口语义，不覆盖并行 session 的视觉实现。

### 执行步骤

- [x] 建立目标用户一天上下文分类、覆盖价值公式、T0–T2 和 P0–P3 优先级。
- [x] 明确六项长期承诺以及备份恢复、Agent 长期事实写入边界的必要调整。
- [x] 建立 `目标用户 × 上下文 × 来源 × 获取方式 × 覆盖增益 × 成本/风险` 合成覆盖矩阵和可复跑校验。
- [x] 建立 `SourceCapability`、`CoverageObservation`、`ContextGap` 机器契约、兼容规则和正负夹具。
- [x] 在 Core reference 实现来源能力登记、覆盖观测、缺口识别和保守事件编译，证明稀疏日不制造事件。
- [x] 证明来源/事件/长期记忆 lineage、两类删除语义、索引重建和删除后摘要失效。
- [x] 形成可验证备份健康、完整性检查和恢复演练；不把导出文件存在等同于恢复成功。
- [x] 加固 Agent：exact Grant 内可自动写 Event/EventCandidate/Revision，推断不得自动升级为确认的长期偏好、关系、健康、财务或重大决定。
- [x] 建立一次搜索或 Agent 任务真实复用的合成可复跑基线，并为后续真实用户 outcome 保留入口。
- [x] 更新产品、架构、隐私、质量、STATUS、当前正本和验证报告；运行全链门禁。

### 本地证据

- Coverage：5 分群、10 上下文、10 来源能力、3 合成 user-day；4,262 项检查，8 observations、5 candidates、6 gaps，市场规模状态 `not_verified`。
- 全链：统一工作区 27/27 Gate 通过；Core 47、Sync 22、Agent 35、Agent Android adapter 29、AI 38、Skill 16 case / 15 risk、iOS Agent client 82、治理 175、Markdown 43 均通过。
- 跨端 Agent：API 36 / 16 KB arm64 AVD 上的 Swift Network.framework→Android SQLCipher→Today 纵向 Smoke 已实际执行 create→bounded read→Revision→Revision exact undo→Event exact undo→final read；普通用户 QR Grant 仍为 event-only，执行包使用显式 expanded Grant。
- 质量报告：`docs/quality/P0-上下文覆盖与长期记忆闭环验证-20260726.md`。
- 并行保护：未修改或回退另一 session 的 Android/iOS 视觉文件；在来源稳定后串行使用既有 AVD 完成独立 Local Node 网络 Smoke，未与 instrumentation 并发。

### 外部待办

- [ ] T0 8 人 × 7 天 Pilot，回填真实覆盖、操作成本、重要遗漏和复用结果。
- [ ] 确定首发地区、平台比例和渠道后，按有分母的来源漏斗估算可服务规模。
- [ ] 选择用户自有存储或 E2EE 云备份路线，并在双端物理设备完成生产恢复/删除演练。
- [ ] 用真实 Agent 宿主、真实来源权限和旁观者场景验证低成本获取。

### 当前文件边界

- 本 Job 优先修改：`packages/contracts/`、`packages/core-reference/`、`tests/fixtures/coverage/`、`tests/contracts/`、`tests/core/`、`research/coverage/`、非视觉正本和验证脚本。
- 默认不修改：`apps/ios/`、`apps/android/` 中的 View/Compose/SwiftUI 样式实现，以及产品视觉、低保真和原生 UI 风格文件。
- 若并行 session 修改本 Job 计划触及的契约或 Core 文件，先复核差异并合并语义，不回退对方改动。

### 退出条件

1. 合成覆盖矩阵、契约、Core reference、删除/恢复/复用测试和验证报告可从干净环境复跑。
2. P0 六项承诺均有实现证据或明确外部 Gate；不能只靠文档标记完成。
3. 工作区、契约、Python、Agent 和相关平台非视觉回归通过，失败记录不删除。
4. Gate 1、正式市场规模、真实用户、物理设备和公开发布未满足时继续保持 `hold`。

## 封闭 Beta 候选持续执行 Goal

- 状态：`in_progress`
- 建立日期：2026-07-26
- 目标：完成所有仓库内仍可执行的生产 P0、恢复、审计、复用与 Beta 准备工作，把产品推进到“真实用户可用、可恢复、可审计的封闭 Beta 候选”。
- 证据边界：真实参与者、物理设备、真实读屏、签名/Provisioning、商店、付费服务、生产外部账号和目标市场法律审查是不可替代 Gate；合成、Mock、Simulator、AVD、静态检查和 reference 结果不能关闭这些 Gate。
- 当前切口：把已经通过的 coverage/reference 语义接入 Mobile/Local Node 生产运行时；优先非视觉领域模型、Repository、状态机、测试和失败恢复，不重复已经有效的 reference 工作。

### 执行计划

- [x] 读取协作规则、状态、Job、当前正本及 P0 产品/研究/契约/Core/架构/隐私/Skill 基线。
- [x] 审计并解释现有未提交变更，确认并行视觉文件、设备任务和文件所有权；未回退未知改动，未触碰受保护视觉文件。
- [x] 在隔离 Python 3.12 环境复跑统一门禁，记录首次失败、根因、修复和重跑结果；恢复、复用、本机来源删除与外部 Gate 包均已进入统一 20/20 基线。
- [x] 选择并实现首个未完成的非视觉生产 P0 运行时切片，覆盖拒权、失败、不可用和稀疏日降级；双端六项 Mobile 来源、编译器和状态工厂已进入生产模块。
- [x] 接通 Coverage Candidate 的显式生产事件编译、Revision/DayLedger 与 Event link；保存 Coverage 不创建 Event，删除 Event 后 link detach 且 Candidate 保持终态不复活。单来源 source cascade 已在 v11/v6 收敛，多来源 exact-revision 字段证据与保守重算/字段失证删除已在 v12/v7 收敛；ContextPack 不复活仍在后续步骤。
- [x] 接通双端生产长期 Memory 的 exact Event revision evidence、显式 confirmation、validity、supersession 与上游 Event revision/delete 失效边界；普通与敏感/推断候选均不自动 active。Android Agent 可写 Event/Revision，但不能调用长期 Memory confirmation，Revision 会使旧 Event-bound Memory 失效。
- [x] 完成仓库内可验证的双端本机恢复安全底座：Event 删除事务同写持久 watermark/tombstone，认证同安装完整快照，损坏/错 key/旧水位/额外文件/非空目标 fail closed；默认只生成隔离候选，不自动切换 live store。
- [x] 完成同安装恢复候选的双端可回滚激活内核：授权精确绑定 backup ID 且最长 15 分钟，激活前后二次验证候选与权威删除水位，原候选不被消费；HMAC journal 以 `PREPARED`/`COMMITTED` 区分失败回旧 live 与保留已验证新 live，启动可继续收敛，伪造/损坏 journal fail closed。iOS 生产 Smoke 已实际执行成功激活和过期授权零变更；Android SQLCipher instrumentation 的 exact authorization、候选保留、中途失败回滚、过期授权零变更、伪造 journal、Agent audit union/merge failure 与 orphan sidecar 已在 API 36 / 16 KB AVD 恢复类 5/5 和全量 96/89/7/0 中执行。本内核不接普通用户 UI，仍硬编码 `productionRecoveryClaim=false`。
- [ ] 在用户自有存储或 E2EE 路线、recovery secret 与物理恢复演练获批后，接入用户可见备份健康、恢复确认/切换和支持状态；当前 artifact 明确 `productionRecoveryClaim=false`，不能冒充全设备丢失恢复。
- [x] 完成历史搜索、显式关键词项目续接、用户选择范围会前上下文、active 决定/承诺找回四类生产 repository 复用及无正文遥测；exact reference 15 分钟到期并二次验证，Restricted 排除。该批次当时 Android Agent read 关闭；后续最小 `visible_events` 只开放独立 Event projection，不开放本机 Memory/telemetry。
- [x] 完成双端本机 source identity 与最小删除语义：Android schema v11 落 source graph/单来源 cascade/source watermark 并明确 Raw 外部所有权；iOS envelope v6 落 app-owned 密文 Raw-only/pending retry、外部原件边界与单来源 cascade。
- [x] 完成双端 content-free exact-revision 字段→来源证据与多来源删除动作：Android schema v12 / iOS envelope v7 只在每个字段仍有 active source 时追加保守重算 revision，字段失去最后来源时删除 Event，legacy/用户 revision stale 则 `lineage_unavailable` 且零 mutation。
- [x] 完成双端用户确认专属 provenance：Android schema v13 / iOS envelope v8 只保存确认 ID、exact Event/revision、确认类型、字段名、完整/partial 标志、时间和 terminal state；完整显式 Candidate 确认可在删源后保留 Event 但必须终结来源 claim，partial/stale/legacy 不得保留失去支持的正文，Agent revision/undo 不伪造确认，v12/v7 迁移不猜测旧动作。iOS 生产 Smoke 已实际覆盖完整/partial 与加密重载；Android v13 SQLCipher 已进入本轮 API 36 / 16 KB AVD 全量设备回归。真实用户、iOS XCTest/物理设备及外部删除证明继续 hold。
- [x] 在冻结 `ameme.agent-local-node.v1` 上接通 Android 生产 `append_revision`：新 pairing 明示 `event/revision`，旧 event-only policy 不扩权；同一 SQLCipher transaction 追加不可变 Revision/current projection、幂等记录、搜索/DayLedger 更新和长期 Memory 失效。缺失/删除/sensitivity 不可见目标统一 `NOT_VISIBLE`。该切片当时 read/undo 关闭，后续由独立撤销/读取切片更新；设备/真实第三方宿主 Gate 未关闭。
- [x] 在冻结 `ameme.agent-local-node.v1` 上接通 Android 生产 exact Event/Revision `undo_capture`：原 capture token 与 caller/grant/purpose/space/type 精确绑定，首次限 10 分钟；Event 追加 tombstone，Revision 仅在原 revision 仍为 current head 时追加补偿 revision，后续 head 返回 conflict。撤销 mutation/幂等同一 SQLCipher transaction，重开后重放同一终态，幂等表不保存正文/snapshot。Host control token 与 Android 域分离原 slot 精确映射，严格绑定结果并拒绝恶意响应；缺少 `grant_operations` 的旧 pairing fail closed 重授权。该切片当时 read 关闭，后续最小读取切片已更新；跨端传播、设备与真实宿主 Gate 未关闭。
- [x] 在冻结 `ameme.agent-local-node.v1` 上接通 Android 生产最小 `visible_events`：只读当前 Personal space 的 active Event/structured projection，query≤1,000、limit≤100、时区化时间边界与 session sensitivity 交集；production 不授予 Restricted，未获 Restricted 的 session 不通过 `risk_filtered` 泄漏存在性。响应只含 bounded title/description 和必要 Event 元数据，不含 user words/source/locator/Raw。Host 结果做 exact scope/type/数量/长度绑定，Recall/Context 保留 injection 和 item/token budget；`get_event`、策略写入和长期 Memory 关闭。JVM、Host MCP/TLS 与 androidTest 编译通过，真实 Host/ContextPack、设备与共享账户 Grant Gate 未关闭。
- [x] 补齐 iOS 生产 Local Node 四操作客户端：`create_event`、`append_revision`、exact `undo_capture`、bounded `visible_events` 均具备 canonical request builder、exact Grant 子集校验、typed response/result digest/错误形状与冻结 retryability 校验；malformed 响应关闭通道，合法远端应用错误不毒化会话。原始 application exchange 已私有化，四个生产 exchange 只按当前时间授权。普通用户二维码默认 Grant 和展示文案继续为 event-only，只有显式扩展 Grant 才可构造 Revision/read/undo 请求；完整 Swift→Android 四操作序列已在 API 36 / 16 KB AVD 实际执行并复核 Today，物理 LAN/设备、真实 Host/ContextPack 与共享 Grant 仍未证明。
- [x] 完成 QR 一次性 bootstrap 与安全重连仓库切片：v2 envelope 将 5 分钟 bootstrap 与 30 天 pairing 分离；Android 原子记录 consumed、client public key/thumbprint 与独立 QR credential，只允许同 key 有界响应恢复，Release 不生成开发 bearer；iOS 在 device-only Keychain 保存同一 P-256 key 的 pending/active 状态，启动仅在真实通道认证后恢复。应用 channel v1 保持冻结，Debug Host credential 独立。该切片不宣称每次重连 P-256 proof、Android scanner client、共享 Grant 或物理设备。
- [x] 完成 Android 生产 Local Node 访问审计：SQLCipher schema v14 为四项 operation 在 repository 前追加 content-free `STARTED`、完成后追加同 trace `COMPLETED`；只保存 caller/purpose/canonical scope/operation/稳定结果码/对象数量桶/时间，180 天单点保留且表 append-only、未到期禁止删除。STARTED 失败零 repository 执行，COMPLETED 失败保留未完成证据并由持久幂等安全重试；设置页只读最近 20 条且不生成合成记录。migration/runtime/UI instrumentation 已在 API 36 / 16 KB AVD 执行；iOS Host、账户/多设备审计、真实用户/物理设备仍 hold。
- [x] 关闭 Android 同安装恢复回退 Agent 安全账本的空白：激活时先原子清理候选过期 audit，再把旧 live 未过期 audit 与候选做 monotonic union；exact duplicate 去重，同 audit ID/trace-phase 内容冲突、非法记录或 50,000 行未过期容量溢出 fail closed；retained count/digest 与合并后 SQLCipher size/SHA-256 在 staging/new live 双重验证。认证 PREPARED journal 前移到 staging 前并在恢复时清理专用 WAL/SHM/journal，覆盖复制/merge 崩溃窗口；新增 JVM 与 SQLCipher instrumentation 均已执行。
- [x] 完成当前安装 Personal space 的双端本机 root freeze：依赖投影收敛后最后写 SPACE 水位，重载后冻结普通读写、拒绝旧备份，并允许 app-owned Raw/provider locator 后续清理；结果硬拒账号/peer 过度宣称。account/Grant/Contract、provider 原件、peer/云副本、物理擦除和最终用户权利请求流程仍为真实 Gate。
- [x] Android Search 以一个扁平“把记忆用起来”入口接入历史找回、项目续接、会前上下文、决定/承诺四种用户意图和 useful/not-useful/wrong/miss/outdated 五类无正文反馈；解析与 revision 复核在同一 SQLCipher transaction 完成。Search 已改为单一 `LazyColumn`，在 API 36 / 16 KB、320dp、130% 字号下的全量设备回归、定向 UI smoke 与最终截图通过。
- [x] 将 iOS `ReuseJourneyController` 薄接入 SwiftUI Search：单一扁平入口、原生 `confirmationDialog`、结果 Sheet、exact Event 跳转和五类反馈均已接通，演示模式不写生产复用记录；本地 App/Shared build、生产 Smoke、parse 与静态无障碍契约通过。
- [x] 由完整 Xcode CI 签收本轮 iOS 新 UI、恢复激活和 Local Node 四操作 response/Grant 负向 XCTest：run `30438185984` 使用 Xcode 26.6 / iOS 26.5 完成 App + Share Extension、63 Shared Unit、默认浅色 Mock/真实本机 UI 2/2 与深色 XXXL UI 1/1。T0 helpful/wrong/miss/revision/prompt burden、物理设备和真实读屏仍未关闭；Simulator、Shared smoke 和固定数据不能冒充双端真实用户可用。
- [x] 完成 T0 8 人 × 7 天 Pilot、真实宿主、双端物理设备、签名/商店、安全/事故/回滚外部 Gate 执行包；私有原始模板、角色、设备、账号、耗时、阈值和证据规则已可直接执行，所有真实 Gate 初始为 `hold`。
- [ ] 由真实参与者与负责人执行 T0 8×7、真实宿主、双端物理设备/读屏、签名/商店、生产恢复、成本、安全/事故/回滚 Gate；不得以执行包完成冒充外部 Gate 通过。
- [x] 完成本轮全量复验并同步 Job、状态、索引和独立验证报告；隔离 Python 3.12 workspace 27/27、Android JVM 97/97、Debug/Release、Lint、41,926,985-byte Debug APK、API 36 / 16 KB AVD 91 discovered / 84 passed / 7 外部门 skipped / 0 failed、恢复激活专项 3/3、完整 UI 12/12、Host adapter/TLS 29/29、Agent 35/35、Android Agent read 108/108、undo 117/117、Revision 85/85、iOS Agent client 82/82、iOS Shared/App/LocalNodeSmoke build、生产 Shared smoke 与 Swift→Android 四操作纵向 Smoke、字段 provenance 138 项、用户确认 provenance 94 项、来源删除 134 项、治理 175 项及既有回归通过；完整 Xcode CI 与外部 Gate 未关闭，Goal 继续 active、Job 保持 in_progress。
- [x] 完成访问审计切片非设备复验并同步正本、索引和独立报告：Android Debug JVM 101/101、Debug/Release assemble、Lint 0 error、androidTest 编译、41,126,525-byte Debug APK、33,461,214-byte unsigned Release APK、Agent audit 静态门 126 项和隔离 Python 3.12 workspace 28/28（Markdown 43、治理 177）通过。首次 workspace 因旧静态门硬编码当前 v13 失败，更新为 v14 且保留功能引入版本断言后全绿。并行 AVD 未被占用，v14 instrumentation、物理设备和外部 Gate 未关闭。
- [x] 完成恢复审计单调保全的非设备复验与正本同步：Android Debug JVM 103/103、Debug/Release assemble、Lint 0 error、androidTest 编译、41,571,569-byte Debug APK、33,493,982-byte unsigned Release APK、Agent audit 静态门 143、recovery 138 和 workspace 28/28（Markdown 43、治理 177）通过。首轮 Kotlin 因 `MessageDigest.put` extension/member reference 歧义失败，改为显式 lambda 后同命令及全量重跑通过；并行 AVD 未被占用，新增 union/sidecar instrumentation、物理设备和外部 Gate 未关闭。
- [x] 完成访问审计与恢复账本的设备级收口：修正过期候选审计误占恢复容量/冲突的边界，Debug JVM 104/104、Debug/Release、Lint 0 error（31 warning / 1 hint）、41,571,585-byte Debug APK、33,493,982-byte unsigned Release APK、Agent audit 静态门 146、recovery 138、隔离 Python 3.12 workspace 28/28 通过；API 36 / 16 KB、320dp、130% 字号 AVD XML 精确为 96 discovered / 89 passed / 7 外部门 skipped / 0 failed，其中恢复 5/5、审计持久化 2/2、端点 3/3、UI 13/13。
- [x] 完成双端本机 Space 删除协调与防复活收口：Android 产品入口在 root freeze 后停止 Local Node、撤销本机 pairing、关闭体验连接、清除连接元数据并持久冻结 Keystore 待处理快照；iOS 同步断开 Agent、清除展示元数据、持久冻结待导出与 Share Extension handoff，并清理原子写遗留的隐藏载荷。双端启动时会对已删除根重放本机收敛，确认 UI 要求精确输入“删除”，但不冒充账号、共享 Grant、provider/peer 删除或物理擦除。最终 Android 107/107 JVM、Lint 0 error（29 warning / 1 hint）、41,182,589-byte Debug APK、33,559,518-byte unsigned Release APK、API 36 / 16 KB AVD 100 discovered / 93 passed / 7 外部门 skipped / 0 failed；iOS Shared/App/Smoke、跨端 Space 静态门 206 和 workspace 28/28 通过，最终 head Xcode CI 仍待签收。
- [x] 完成 QR v2 实现、Android Material 3 连接设置减法、动态子门与独立报告收口：设置页把单一大卡拆为存储/同步、当前 Agent、接收其他设备三段，普通 QR 默认收起 Debug 开发者材料；Android Debug/Release JVM 单 variant 110/110、Debug/Release/androidTest/Lint、41,190,345-byte Debug APK、33,592,286-byte unsigned Release APK、2,874,573-byte androidTest APK、manager instrumentation 7/7 和全量 API 36 / 16 KB AVD 103/96/7/0 通过，Today/设置/QR 均在 130% 字号下人工复核；iOS Shared/App/SharedSmoke/LocalNodeSmoke build、device-only Keychain Smoke、Test 源码 parse、Swift→Android v2 bootstrap→四操作 Smoke 与 Debug Host 独立凭据 Smoke 通过；QR 静态门 54/54、workspace 28/28。最终 head XCTest、Android scanner client、共享 Grant、物理扫码/LAN/后台仍 hold。

### 文件所有权与并行保护

- 已观察到的 P0 reference 未提交范围：`packages/contracts/`、`packages/core-reference/`、`services/ameme-mcp-mock/`、`tests/core/`、`tests/agent/`、`research/coverage/`、验证脚本和相应非视觉正本。继续把无法确认归属的改动视为用户或其他 Session 工作，只做语义兼容的最小补丁。
- 并行视觉保护：不修改或回退 `CommonComponents.kt`、`TodayScreen.kt`、`Theme.kt`、`AmemeApp.swift` 的视觉样式；不改颜色、字体、圆角、间距、图标、动效和视觉 token。
- 每个实现批次前复查 `git status --short`、`git diff --name-only` 和设备进程；不得共享 serial 并行跑 Android instrumentation，也不得争用 Simulator/AVD 构建输出。

### 风险与外部 Gate

| Gate | 当前状态 | 仓库内准备要求 | 关闭证据 |
|---|---|---|---|
| T0 真实 Pilot | `hold` | 问卷、练习日、每日回顾、D3/D5/D7 找回、D8 删除/恢复、脱敏聚合模板 | 8 人 × 7 天真实结果且无 P0/P1 信任问题 |
| 双端物理设备与真实读屏 | `hold` | 设备矩阵、权限撤销、后台/进程终止、旋转、动态字体、恢复步骤 | iOS 与 Android OEM 真机记录和负责人签收 |
| 生产恢复路线 | `hold` | 用户自有存储或 E2EE 云备份决策包、密钥恢复/退出迁移/损坏测试 | 生产 SQLCipher + Keychain/Keystore 物理恢复且删除不复活 |
| 真实 Agent 宿主 | `hold` | adapter、exact Grant、注入/撤权/过期/离线人工验收包 | 至少一个真实宿主与真实 Local Node 的受限闭环 |
| 签名、商店与发布 | `hold` | 隐私清单、数据安全表、签名/App Group、监控、事故和回滚步骤 | 负责人、账号与商店/分发流程真实完成 |
| 市场规模 | `hold` | 地区、平台比例、可触达渠道、抽样框和分母模板 | 输入明确并以真实来源漏斗计算 |

### 失败日志

本 Job 不删除历史验证报告中的首次失败。新一轮已记录 Android 缺失 `JAVA_HOME`、Android CI Search 用例未等待异步生产 repository 就绪而提前断言复用入口、iOS 本机缺失 XCTest、iOS CI 的 `CoverageCompilerTests` 手工重建 `CoverageSignal` 漏传 `importance`、gap scope 语义过严、跨端静态验证器、Swift `Sendable`、SQLite replace-upsert 外键风险、并行视觉中间态导致的构建失败、长期 Memory 类型策略表达与静态门注释误报、recovery SQL bind 数组误替换、复用 helper 缺失显式删除参数、TTL 边界断言偏移、source tombstone helper 缺失显式 return、动态 trigger 静态门误匹配、删除后恢复入口错误分层/调用方水位降级旁路、字段证据 v6→v7 装载缺口、旧多来源静态断言、Agent operation 字段误加到 canonical Grant、撤销测试 token 长度少一位、撤销 Schema `$ref` 误判、多行静态 marker 误判、Host unittest module 路径误用、读取切片的 Android 工具链环境错误、系统 Python 缺少 TLS 1.3、instrumentation helper 漏标 `suspend`、Context budget sentinel 回归、workspace Python 缺 PyYAML、隐藏 `sourceLabel/userWords` query oracle、索引先引用未落盘报告导致的治理失败，以及“Android 扫码是简单 UI 缺口”的初始判断在追踪生产 secret 生命周期后被否定；仓库根既存未跟踪 `outputs/` 敏感文件失败已通过完整移至仓库外同工作区 `ameme-untracked-evidence-20260726-191150/` 收敛，未删除证据。QR v1 审计确认 5 分钟 parser window 不等于服务端一次性；后续 v2 实施又保留了 Swift fixture 迁移编译错误、bootstrap 后 listener 重建竞态、一次 AVD 离线、系统 Python TLS 1.3 不可用、Host UI 固定等待失败、全量 instrumentation signal 9、定向测试遗留 `UiAutomation` 服务、并发 Gradle instrumentation 重装同包强杀全量 runner，以及静态 validator 旧 marker 失败。后两项均以设备原始日志归因为测试基础设施污染，独占 AVD 重跑由 Gradle 成功结束且 0 assertion failure。v2 已通过服务端消费、独立签发与同 key 恢复修复仓库缺口；命令、根因、重跑和仍未解决的物理/共享 Gate 见两份 QR P0 报告。

### 当前生产切片证据

- Android Coverage JVM tests 11/11、长期 Memory contract 3/3、复用 contract 3/3、source deletion contract 3/3、local-space deletion contract 3/3、全量 Debug JVM 110/110、Release JVM 110/110、lint 0 error（29 warning / 1 hint）、41,190,345-byte Debug APK、33,592,286-byte unsigned Release APK、2,874,573-byte androidTest APK 和 androidTest 编译通过。API 36 / 16 KB arm64 AVD 全量 XML 精确为 103 discovered / 96 passed / 7 显式外部门 skipped / 0 failed；QR manager 7/7，既有恢复激活、schema v14 Agent audit、生产端点、UI、删除确认和待处理动作回归继续通过。AVD 是虚拟设备，物理设备仍未执行。
- iOS `AmemeShared`/`AmemeApp`/`AmemeLocalNodeSmoke` 构建与生产 Shared smoke 通过；smoke 实际覆盖 Coverage 密文保存/重载/显式接受/删除不复活、exact-revision 多来源字段证据/重算/重载、完整用户确认删源保留/来源 claim 终结、partial 确认整 Event 删除和加密重载、长期 Memory 候选/显式确认/Event revision 失效/重载不复活、四类短时复用/无正文 telemetry/旧引用失效、app-owned 密文 Raw-only、外部原件 `external_not_owned`/`completed_local_only` 边界、单来源 cascade/source watermark、认证同安装备份、过期激活授权零变更、exact backup 确认激活、删除水位保留与原候选不消费、当前安装 Personal space 根冻结，以及 Local Node 四操作 request/typed response、result digest、冻结错误码/retryability 和默认 event-only Grant 拒绝 Revision 扩权。字段失证整 Event 删除、用户 revision stale、v6→v7 field evidence、v7→v8 user confirmation 无猜测 migration、SourceObject 权威备份、激活中途失败回滚/伪造 journal、finalize persist 失败 reload 收敛、Space 根失败回滚和 Agent response/Grant 负向 XCTest 已补充；本机 `swift test` 真实失败于缺少 XCTest。扩展网络 Smoke 已在 API 36 / 16 KB arm64 AVD 实际执行 create/read/revision/exact undo/Today 纵向链，仍需完整 Xcode 与物理设备链路。
- 跨端 Coverage 静态契约 280 项、长期 Memory 92 项、本机恢复 138 项、生产复用 186 项、本机来源删除 134 项、字段 provenance 138 项、用户确认 provenance 94 项、本机 Space 删除与本机授权收敛 206 项、外部 Gate 包 80 项、Android Agent Revision 87 项、Android Agent undo 117 项、Android Agent read 108 项、Android Agent access audit 146 项、iOS Agent client 82 项通过；隔离 Python 3.12 统一工作区 28/28 Gate 通过，Core 47、Agent 35、Android Agent adapter 29、AI 38、iOS project 52、Share inputs 30、iOS accessibility 33、QR 54、Markdown 43、治理 180。QR Gate 的 server-enforced one-time、credential rotation 与 Release developer-bearer absence 静态实现 claim 为 true；Android camera scanner、共享 Grant registry 与物理设备执行 claim 明确为 false。
- Verdict：`conditional_pass`。Coverage、显式 Event 接线、长期 Memory、本机删除水位/恢复候选、同安装候选可回滚激活内核、四类复用原子解析、双端 Search 单入口、无正文 telemetry、source identity/Raw-only/单来源 cascade、多来源 exact-revision 保守重算/字段失证删除、完整用户确认删源保留/partial fail-closed、Android SQLCipher AVD 执行、当前安装 Personal space root freeze、Android Local Node content-free 访问审计与恢复账本，以及 QR v2 服务端一次性 bootstrap→签发时 key-bound 独立 credential→device-only 重连路径已形成。冻结应用 channel 仍为 bearer，Android scanner client、iOS 最终 head bootstrap XCTest、iOS Host/账户审计、真实 outcome/用户确认动作、account/Grant/peer/分布式删除、跨设备 key recovery、用户可见生产恢复、物理设备和真实用户仍为 `hold`。

### 退出条件

1. 所有可在仓库和当前本地环境内完成的生产代码、迁移、测试、脚本、文档和执行包已经实现并复验。
2. 六项产品承诺在生产路径均有真实实现证据或精确外部 Gate，不以 reference/Mock 冒充。
3. 外部门执行者可以直接按人员、设备、账号、步骤、耗时、数据处理和判定清单完成验证。
4. 只有全部完成定义和外部 Gate 都由真实证据关闭后，才可把本 Goal 标记 `complete`。
