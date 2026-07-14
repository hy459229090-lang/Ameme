# docs 文档索引

> 规则：当前正本先看 `_CURRENT.md`。正式子域使用各自 `_INDEX.md`；根目录现有 00–07 文档保留为 Discovery 基线，后续新正式文档按子域归档。

## 子域

| 子域 | 入口 | 用途 |
|---|---|---|
| 产品 | `product/_INDEX.md` | 用户、场景、PRD、指标和商业模式 |
| 研究 | `research/_INDEX.md` | 调研综合结论、访谈和实验结论 |
| 架构 | `architecture/_INDEX.md` | 系统、数据、模型和同步架构 |
| 隐私安全 | `privacy-security/_INDEX.md` | 权限、威胁、合规、保留和删除 |
| 工程 | `engineering/_INDEX.md` | RFC、Schema、接口、迁移和开发计划 |
| 质量 | `quality/_INDEX.md` | 测试、评测和验证证据 |
| 发布 | `release/_INDEX.md` | Gate、版本、发布和回滚 |
| 运营 | `operations/_INDEX.md` | 监控、事故、客服和运行手册 |
| 决策 | `decisions/_INDEX.md` | 正式决策与替代关系 |
| 治理 | `governance/_INDEX.md` | 项目规范、工作流和模板 |

## 早期基线文件

| 文件 | 状态 | 说明 |
|---|---|---|
| `00-project-charter.md` | v0.2 当前有效 | 事件覆盖优先的愿景和假设 |
| `00-phase-0-product-discovery.md` | 历史输入 | 首轮市场和 MVP 假设 |
| `01-discovery-brief.md` | 历史输入 | 首轮 Discovery 摘要 |
| `02-product-system-framework.md` | v0.3 当前有效 | DayLedger 优先；双主采集端职责已确认，进入信息处理评审 |
| `03-r0-r1-research-plan.md` | plan_ready，有意后置 | R0/R1 调研计划 |
| `04-user-scenario-matrix.md` | v0.2，待访谈 | 候选用户和场景矩阵 |
| `05-multi-end-source-map.md` | v0.2，部分实测 | 多端来源、权限和可行性 |
| `06-data-processing-storage-options.md` | v0.4，已确认待验证 | 四层记录、多源归并、三层描述与跨端存储边界 |
| `07-prototype-candidate-review.md` | v0.2，plan_ready | DayLedger Core 与三类来源包 |

## 当前专题

| 文件 | 状态 | 说明 |
|---|---|---|
| `product/记忆反馈与类型覆盖框架.md` | v0.1 | 反馈动作、基础类型、跨端入口和覆盖指标 |
| `product/多端使用与体验闭环.md` | v0.1 | Mobile、Agent、Web 的使用分工、低负担反馈与主动提示候选 |
| `product/MVP范围与体验设计原则.md` | v0.5 | `今天`、Mobile 来源、效率风格、显著度与事实/授权解耦 |
| `product/MVP产品设计规格.md` | v0.6 | D1–D10、六条旅程、产品范围、验收、LAN/账户/Agent/Raw 与双端原生 UI 原则 |
| `product/移动端来源与轻量位置记录.md` | v0.3 | 双端机会式位置、事件化、电量与五类健康授权包 |
| `product/产品体验风格与演进策略.md` | v0.4 | MVP 效率风格与双端原生 UI 优先边界 |
| `product/Mobile信息架构与数据获取联动.md` | v0.5 | 今天、搜索历史日流、平台原生设置与数据获取联动 |
| `product/Mobile交互设计规格.md` | v0.6 | 页面 ID、单悬浮记录入口、双端平台差异、Agent、删除/导出和状态验收 |
| `product/Mobile低保真设计基线.md` | v0.3 | 今天、搜索页和四类关键状态语义已确认 |
| `product/Mobile原生UI与流畅性基线.md` | v0.2 | 双端原生组件映射、单记录入口、流畅性原则与真机指标 |
| `architecture/记忆类型与反馈事件模型.md` | v0.2 | EventRevision、FeedbackEvent、类型注册、个人规则和存储 |
| `architecture/Event与DayLedger最小模型.md` | v0.4 | Observation、事件边界、归并、描述、条件显著度和 DayLedger Schema |
| `architecture/MVP研发架构技术方案.md` | v0.6 | Local Event Node、技术默认栈、双端原生、同步/AI/Skill/观测基线 |
| `research/R0-FORMALdoc真实工作样本实验.md` | 专业桌面来源对照 | FORMALdoc 只读快照与 EventCandidate 示例 |

## 变更日志

| 日期 | 操作 | 说明 |
|---|---|---|
| 2026-07-13 | 建立 | 增加正本入口和产品到上线的正式文档子域 |
| 2026-07-13 | 更新 | 纳入 R0/R1 证据、Windows/Chrome Spike 与 Prototype 候选 |
| 2026-07-13 | 更新 | 纳入记忆反馈/类型覆盖框架和 FORMALdoc 真实样本实验 |
| 2026-07-13 | 修订 | 产品主链改为 DayLedger 事件覆盖优先，反馈/记忆/总结后置 |
| 2026-07-13 | 修订 | 确认 Agent + Native Mobile 双主采集，形成第 4 步信息处理与跨端存储候选 |
| 2026-07-13 | 新增 | 第 4 步确认后进入多端使用与体验闭环评审 |
| 2026-07-13 | 新增 | 调研有意后置，进入 MVP 范围和体验设计原则讨论 |
| 2026-07-13 | 修订 | MVP 改为双移动端 + Agent 集成 + Event Core，并增加信息权重分层 |
| 2026-07-13 | 修订 | 确认 Mobile 核心来源和情绪/关系权重，形成轻量位置记录候选 |
| 2026-07-13 | 修订 | `今天` 暂定为主导航；增加机会式位置级联、健康授权包与条件显著度规则 |
| 2026-07-13 | 新增 | MVP 确认效率风格，情绪风格作为后续表达层保留 |
| 2026-07-13 | 新增 | Mobile 信息架构与数据获取进入并行联动设计 |
| 2026-07-13 | 修订 | Mobile 主内容改为今天+找回，设置进入侧滑抽屉 |
| 2026-07-13 | 新增 | 产品、交互和研发架构进入三线并行规格与交叉审计 |
| 2026-07-13 | 新增 | 三份 v0.1 已形成并完成 C1 pass_with_open_decisions 审计 |
| 2026-07-13 | 修订 | 三线 v0.2 回填历史日流、小结默认态、数据稀疏与原始保留原则 |
| 2026-07-13 | 确认 | 今天页低保真骨架与右上搜索按钮入口已选，视觉风格后置 |
| 2026-07-13 | 新增 | 默认搜索页与四类关键状态低保真进入产品评审 |
| 2026-07-13 | 确认 | 通用低保真转为共同语义图，iOS/Android 分别采用原生控件并以流畅性为验收目标 |
| 2026-07-14 | 决策 | D1–D10 最终回填：LAN/账户/Health/导出/全面研发/OS/Agent/Raw+SourceLocator/单悬浮入口/Pilot 矩阵 |
