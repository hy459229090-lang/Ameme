# Ameme 当前正本

> 文档状态：当前有效\
> 适合读者：产品、研发、设计、安全、测试、AI\
> 人类快速阅读：先看“当前阶段”和“按问题查正本”\
> AI 阅读提示：先按任务读取对应正本，不要默认全文读取全部研究材料。

## 当前阶段

Ameme 已完成 MVP 研发前本地可完善准备与 D1–D10 最终拍板；差距矩阵 `needs_work = 0`、`needs_owner_review = 0`，全链结论为 `pass_with_external_evidence`。M1–M6 全面研发获批，Gate 1 仍为 `hold`、Gate 2 未执行，不能用开工授权或规格替代真实用户、真机和实现证据。

## 按问题查正本

| 问题 | 当前正本 | 状态 |
|---|---|---|
| 产品愿景与立项假设 | `00-project-charter.md` | 当前有效 |
| 产品能力、多端、体验与版本框架 | `02-product-system-framework.md` | v0.3，双主采集职责已确认 |
| R0/R1 调研计划 | `03-r0-r1-research-plan.md` | plan_ready，按产品决策有意后置 |
| 用户与场景矩阵 | `04-user-scenario-matrix.md` | v0.2，待真实访谈验证 |
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
| MVP 本地存储、同步与删除 | `architecture/MVP本地存储同步与删除协议.md` | v0.2，SQLCipher/Raw Vault/SourceLocator/LAN sync/删除基线已接受 |
| MVP 接口、错误与 Agent 工具 | `engineering/MVP接口与错误契约.md` | v0.1，OpenAPI/MCP/幂等/分页/错误候选 |
| MVP 机器契约 | `../packages/contracts/` | v0.1，JSON Schema/OpenAPI/合成夹具可复跑 |
| MVP AI 路由与 Prompt | `architecture/MVP-AI任务路由与Prompt契约.md` | v0.1，任务分层、隐私门、回退与 Eval 候选 |
| MVP 成本容量 SLO 与观测 | `architecture/MVP成本容量SLO与可观测性.md` | v0.2，LAN/local-first 成本、容量、SLO 与观测基线 |
| 正式产品/架构/安全决策 | `decisions/_INDEX.md` | 13 项登记（12 accepted + 1 superseded_for_mvp），0 项 needs_owner_review |
| MVP 隐私影响评估 | `privacy-security/MVP隐私影响评估.md` | v0.2，账户/LAN 数据流、用途、风险和发布前置已接受 |
| MVP 数据分类、保留与权利 | `privacy-security/MVP数据分类保留与用户权利.md` | v0.2，生命周期、集中 TTL、删除/导出基线已接受 |
| MVP 安全需求与威胁模型 | `privacy-security/MVP安全需求与威胁模型.md` | v0.2，LAN/账户密钥模型、20 类威胁、控制和测试门已接受 |
| MVP 第三方与商店申报 | `privacy-security/MVP第三方处理与商店申报清单.md` | v0.2，无数据云、release allow-list 为空，准入门已定义 |
| MVP 测试与 AI 评测 | `quality/MVP测试与AI评测策略.md` | v0.1，层级、夹具、旅程、环境和 Gate 候选 |
| Agent Skill 运行时契约 | `engineering/Ameme-Skill运行时契约.md` | v0.1，宿主/MCP/兼容/安全门已接受 |
| MVP 工程任务与里程碑 | `engineering/MVP研发任务书与里程碑.md` | v0.3，M1–M6 全面研发获批、发布仍受 Gate 约束 |
| MVP 技术 Spike | `engineering/MVP技术Spike任务书.md` | v0.3，15 项验证/否决任务 ready_to_execute |
| MVP 用户/真机验证 | `research/MVP用户与真机验证执行包.md` | ready_to_execute / blocked_external |
| MVP 封闭发布与回滚 | `release/MVP封闭发布与回滚计划.md` | v0.2，LAN/Health、5→10–15 人 rings、stop/rollback 基线 |
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
