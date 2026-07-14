# Ameme MVP 研发前准备清单与差距矩阵 v0.3

> 文档状态：已接受；`needs_work` 与 `needs_owner_review` 均已清零，只保留 Spike/外部证据\
> 更新日期：2026-07-14\
> 对应 Goal：完成 Ameme MVP 研发前全部可完善准备，仅保留产品负责人决策和不可替代的外部实证\
> 适合读者：产品负责人、产品、交互、客户端、服务端、Agent、数据、安全、测试\
> 人类快速阅读：先看第 2 节关闭规则、第 3 节差距矩阵和第 6 节明早 Review 边界\
> AI 阅读提示：不得把“文档已补齐”写成 Gate 1/2/3 已通过；只有矩阵中 `needs_work` 归零、检查通过且 Review 完成，才可提交研发前材料。

## 1. 目标与非目标

本轮目标是把 MVP 从“方向和候选规格”推进到“研发团队可以估算、拆分、实现和验收的准备状态”：

- 产品、交互、架构、数据、安全、AI、质量、成本、计划使用同一范围和术语。
- 所有进入 MVP 的能力都有输入、输出、状态、错误、权限、删除、指标和测试落点。
- 所有可在当前工作区通过分析、写作、交叉审计和合成验证关闭的缺口全部关闭。
- 需要产品负责人选择的事项压缩成少量互斥选项，说明影响和推荐。
- 需要真机、真实用户、团队能力或外部账户的事项形成可直接执行的 Spike/研究任务，不伪造证据。

本轮不编写生产功能，不创建云资源，不上传真实个人数据，不宣称 Gate 1/2/3 通过。

## 2. 研发前 Definition of Ready

研发前材料提交 Review 必须同时满足：

1. 下表不存在 `needs_work`。
2. `needs_spike` 每项都有输入、环境、步骤、指标、通过/否决条件、证据路径和负责人角色。
3. `needs_owner_review` 只包含会改变产品范围、成本/隐私边界、里程碑或不可逆工程方向的事项。
4. `blocked_external` 明确缺少的设备、参与者、账户、凭据或组织确认，且不能由本地合成结果代替。
5. 产品能力可追踪到交互、领域对象、API/Schema、存储/同步、隐私、安全、指标和测试。
6. 研发任务有依赖顺序、完成定义、回滚/迁移要求和验收证据。
7. 正本、索引、`STATUS.md`、`JOBS/`、`WAL.md` 与决策记录一致。
8. `git diff --check` 与 `python scripts/governance/check_workspace.py` 通过；专项校验脚本和文档链接检查无错误。

### 2.1 状态定义

| 状态 | 含义 | 关闭方式 |
|---|---|---|
| `ready` | 内容、交叉映射和验证已完成 | Review 发现问题时重新打开 |
| `needs_work` | 当前工作区可继续完善 | 本 Goal 必须关闭 |
| `needs_spike` | 需要实现/真机/容量实验 | 先补可执行任务与门槛；证据在对应 Gate 回填 |
| `needs_owner_review` | 只有产品负责人能决定 | 进入明早决策包，不混入普通待办 |
| `blocked_external` | 缺不可替代外部条件 | 明确条件与解阻动作，不伪造替代证据 |

## 3. 当前差距矩阵

| 域 | 初始 | 当前 | 关闭证据/下一证据 |
|---|---|---|---|
| 产品 | `needs_work` | `ready` | PRD、J1–J6、统一 Agent Skill、品牌/单悬浮记录入口与冻结边界 |
| 交互 | `needs_work` | `ready` | Mobile 交互 v0.6、10 Page ID、单悬浮按钮、双端组件/状态/恢复 |
| 用户证据 | `blocked_external` | `blocked_external` | 用户/真机执行包 ready，等待参与者/设备 |
| 来源可行性 | `needs_spike` | `needs_spike` | MOB-IOS/AND、LOC、HEALTH 任务书 |
| 领域模型 | `needs_work` | `ready` | 领域不变量/状态/冲突、Event/Episode Revision |
| Schema | `needs_work` | `ready` | 21 类对象+request/view defs、正负 fixture、校验/CI |
| API | `needs_work` | `ready` | OpenAPI、MCP、错误码、幂等/分页/权限/命令与视图 |
| 本地存储 | `needs_work` | `needs_spike` | 逻辑表/事务/迁移正本；DB/RAW/MIG 任务 |
| 同步 | `needs_work` | `needs_spike` | envelope/冲突/tombstone/proof；SYNC/DEL 任务 |
| 系统架构 | `needs_work` | `ready` | v0.6、ADR-001–005、SDR-001/002、Skill 运行时契约 |
| AI | `needs_work` | `needs_spike` | T01–T12/Prompt/Eval/回退；AI-01 |
| 隐私 | `needs_work` | `needs_spike` | PIA/分类/TTL/权利/商店边界已决；地区/法律/Health/供应商待证据 |
| 安全 | `needs_work` | `needs_spike` | 20 threats、LAN Peer Sync、账户归属/透明设备密钥已决；SEC/SKILL 验证待执行 |
| 指标 | `needs_work` | `ready` | 主指标/driver/guardrail/事件字典/禁采 |
| 成本容量 | `needs_work` | `needs_spike` | L/M/H/X、公式/脚本/SLO；COST-01 真实价格 |
| 质量 | `needs_work` | `ready` | 测试/Eval/fixture/Gate/报告模板 |
| 工程计划 | `needs_work` | `ready` | E0–E8、M0–M6、依赖/DoD/周期场景 |
| 发布准备 | `needs_work` | `ready` | environments/flags/rings/stop/rollback/operations |
| 决策治理 | `needs_work` | `ready` | PDR-001–005、ADR-001–006、SDR-001–002 共 13 项登记；12 accepted、ADR-003 superseded_for_mvp |

## 4. 执行工作包

| Work package | 主要产物 | Review 重点 | 退出条件 |
|---|---|---|---|
| W1 范围与体验冻结 | MVP PRD、双端页面/状态、权限/删除旅程 | 价值、范围、平台原生、可访问性、数据可支撑 | 产品/交互非产品决策问题关闭 |
| W2 契约冻结 | 领域术语、Schema、API、错误码、状态机、夹具 | 一致性、兼容、幂等、分页、权限、删除 | 契约可校验且可生成实现任务 |
| W3 架构与数据 | 系统架构、存储、同步、迁移、Agent、AI | 边界、故障、离线、成本、可观测性 | 关键方向有 ADR 或明确 Spike |
| W4 隐私安全 | PIA、威胁模型、保留、密钥、第三方 | 最小化、隔离、删除、越权、恢复 | 高风险有控制和验证任务 |
| W5 指标成本质量 | KPI/埋点、成本、测试、Eval、性能 | 可计算、可重放、无敏感日志、否决标准 | Gate 证据可由计划产物重算 |
| W6 工程与发布 | 任务书、里程碑、环境、发布/回滚前置 | 依赖、DoD、风险、可回滚、负责人角色 | 研发可直接估算和分工 |
| W7 全链 Review | 追踪矩阵、审计报告、问题修订 | 必须修复/建议/限制分级 | `needs_work` 清零 |
| W8 负责人 Review | 决策摘要、推荐、差异、研发启动建议 | 产品负责人确认 D1–D10 与品牌方向 | 13 项正式决策登记，M1–M6 全面研发获批 |

## 5. Review 标准

每个 Work package 至少执行五类 Review：

1. **来源与范围 Review**：事实、假设、候选和已验证状态是否混淆。
2. **产品—数据 Review**：页面承诺是否有来源、对象、状态和失败降级。
3. **数据—安全 Review**：每次读取、同步、AI 处理、删除是否经过权限、lineage 和审计。
4. **实现—质量 Review**：每条需求是否有接口、错误、迁移、测试、指标和恢复路径。
5. **跨正本 Review**：术语、版本、状态、索引和 Gate 结论是否一致。

问题等级：

- `P0 必须修复`：会导致越权、数据丢失、错误事实、无法删除、核心闭环不可实现或研发无法开工。
- `P1 必须在本 Goal 修复`：规格冲突、状态缺失、不可验收、成本/性能无边界、索引或决策不一致。
- `P2 建议`：不阻断研发但影响可维护性或体验；能本地完成则一并关闭。
- `External evidence`：必须由真实用户、真机、团队或外部系统提供；不能用文档替代。

## 6. 产品负责人 Review 关闭结果

产品负责人已最终回填 D1–D10：LAN/P2P 无用户数据云、账户归属无用户密钥 UX、Health 进入公开 MVP、结构化导出 P1、M1–M6 全面研发、iOS 18+/Android 14+、Agent 授权内自动读写、Raw A+SourceLocator、单悬浮记录入口、5 人 Pilot 后扩 10–15 人真机矩阵。后续不再就可由 Spike 验证的实现细节重复要求非技术负责人选型。

## 7. 当前明确限制

- 当前 Gate 1 仍为 `hold`，缺正式用户行为证据和通用移动场景实证。
- 当前没有可复跑的 Mobile + Event Core Prototype，Gate 2 不能通过。
- 当前工作区没有 iOS/macOS 构建环境、Android 真机和商店/云账户，不能生成对应实测证据。
- 本 Goal 可以完成所有研发前规格、合成契约、验证计划和审计，但不能把上述外部缺口改写为已完成。

## 8. 关闭审计

- `needs_work`：0。
- `needs_spike`：全部有 `MVP技术Spike任务书.md` 中的环境、步骤、指标、通过/否决、证据路径和角色。
- `needs_owner_review`：0；原 5 项已按授权接受推荐方案。
- `blocked_external`：用户、设备、账户、团队、供应商/法律输入，均不能由本地文档替代。
- Skill 缺口曾在 Review 后重新打开，现已由仓库内 `ameme-memory` 包、产品/运行契约、12 个风险样本和 CI 校验关闭。
- 全链映射与修订记录：`MVP研发前全链追踪与审计.md`。
