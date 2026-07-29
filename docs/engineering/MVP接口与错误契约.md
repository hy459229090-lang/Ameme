# Ameme MVP 接口、错误与 Agent 工具契约 v0.5

> 文档状态：已接受；HTTP/MCP 语义、幂等、分页和错误码为研发前实现契约\
> 更新日期：2026-07-26\
> OpenAPI：`../../packages/contracts/api/openapi.yaml`\
> JSON Schema：`../../packages/contracts/schemas/ameme-domain.schema.json`、`../../packages/contracts/schemas/ameme-agent-local-node.schema.json`

## 1. 接口边界

- Mobile UI 只通过 Local Event Node 的命令/查询接口读写，不直连系统来源表或拼接同步请求。
- Adapter 只创建 AcquisitionContract/SourceObject；Core 负责 Observation、Candidate、Revision、DayLedger 和派生物。
- Agent MCP/Skill 调用与 HTTPS API 共用 Policy/Grant/审计边界，不获得数据库、索引或 Raw Vault 直连。
- LAN Peer Sync 只接受 SyncEnvelope；不能把数据库表复制接口暴露给 peer 或 Agent。

## 2. 通用协议

| 项目 | 规则 |
|---|---|
| 鉴权 | Mobile 本地 capability、设备会话或 Agent bearer；每次仍校验 owner/space/purpose |
| 写入幂等 | `Idempotency-Key` 必填；同 key+hash 返回原结果，异内容 `IDEMPOTENCY_CONFLICT` |
| 并发修改 | Event Revision 使用 `If-Match-Revision`；不匹配 `REVISION_CONFLICT` 并返回当前 revision 元数据，不返回无权内容 |
| 分页 | Cursor 不透明并绑定 query/grant/space/snapshot；过期 `CURSOR_EXPIRED`，客户端从安全锚点重启 |
| 时间 | ISO 8601 带偏移；日期查询显式 timezone；服务端不猜客户端时区 |
| 错误 | `application/problem+json`；`code/message_key/retryable/trace_id` 必填，details 不含正文 |
| 限流 | 返回稳定 `SOURCE_RATE_LIMITED`/`RATE_LIMITED` 和 retry-after，不改写为“无数据” |
| 审计 | 记录 caller、purpose、spaces、data types、结果码、对象数量桶和 trace，不记录请求正文/搜索词 |

## 3. API 资源

| Resource | 主要操作 | 成功语义 |
|---|---|---|
| `/v1/today` | 读取日期+space 的 DayLedger | 返回本地/同步范围状态，不承诺人生完整 |
| `/v1/captures` | 保存 SourceObject/UserAddendum | 202 代表本地或可信接收端已持久化并排队，不代表 AI 完成 |
| `/v1/events/{id}` | 读取、追加 Revision、请求删除 | 修改必须带 base revision；删除返回 Job |
| `/v1/recall` | 日期/关键词/范围查询 | 返回 day-grouped RecallPage 和 partial reasons |
| `/v1/context-packs` | Agent 最小上下文 | 返回 grant-bound、短期、固定 revision manifest |
| `/v1/feedback` | 追加纠错/遗漏/使用结果 | 不覆盖历史对象 |
| `/v1/agent-grants` | 创建/查看/撤销 | Mobile 用户批准后生效；挑战本身不是 Grant |
| `/v1/deletion-jobs` | 创建/查看传播 | completed 与 partial_failed 严格区分 |
| `/v1/export-jobs` | 创建本机结构化导出 | 固定 snapshot，不改变源数据 |
| `/v1/sync/push|pull` | 交换不可变 envelope | 重复可安全重放，tombstone/撤权优先 |

## 4. Agent MCP 工具

| Tool | 输入 | 输出 | 限制 |
|---|---|---|---|
| `ameme.pair` | 一次性 challenge、caller 公钥/标识 | pending pairing，不返回数据 | 需 Mobile 审批；挑战短时一次 |
| `ameme.capture` | purpose、space、SourceObject/UserAddendum、evidence state、idempotency key、grant | Event/Revision ID 与 durable/queued/undo 状态 | `autonomous_memory` 可直接长期写入；推断不得伪装 confirmed |
| `ameme.recall` | RecallQuery | RecallPage | 必须有 grant；关键词不进入审计/遥测 |
| `ameme.context` | RecallQuery + task purpose | ContextPack | 最小对象、固定 revision、短 expiry |
| `ameme.feedback` | FeedbackEvent | accepted/queued | 用于 useful/irrelevant/missing/outdated/policy violation |
| `ameme.status` | grant id / job id | Grant、Deletion、sync 的非内容状态 | 不作为绕过数据权限的探针 |

Skill 文档负责“何时调用”和对用户的确认文案，不能放长期 token；MCP host 的本地配置只保存可撤销凭据引用。

### 4.1 Agent Local Node v1 当前实现边界

`ameme.agent-local-node.v1` 是 MCP adapter 与 Local Node 之间的应用层协议，不是 LAN、HTTP 或设备发现协议。正本由严格 JSON Schema、[`agent-local-node-protocol`](../../packages/agent-local-node-protocol/) executable spec 和跨语言 conformance vector 共同约束：canonical UTF-8 JSON、payload/result SHA-256 digest、最小且由 payload 派生的请求 scope、可为请求超集的 verified Grant、请求绑定响应、域分离幂等槽与稳定错误码。未知字段、重复键、浮点数、非法 UTF-8、NUL 和 lone surrogate 均 fail closed；v1 只有 `TEMPORARILY_UNAVAILABLE`、`INTERNAL_ERROR` 可重试。

当前 MCP `android-local-node` 后端与 Android 端点实现四种最小操作：`create_event`、`append_revision`、exact `undo_capture` 与有界 `visible_events`。适配器必须显式选择并注入已认证 channel provider、endpoint/credential/session binding 引用和 expected device id；Android 必须获得 separately verified session 以及获准的 operation/space/type/sensitivity/data class。Revision 写入对不存在、已删除或 sensitivity 不可见目标统一返回 `NOT_VISIBLE`，也不确认长期 Memory。撤销只接受原 capture 返回的域分离 token、同 caller/grant/purpose/space/type，且首次调用必须在原幂等记录创建后 10 分钟内：Event 追加 tombstone；Revision 仅在该 Revision 仍为 current head 时追加补偿 Revision，绝不覆盖历史。撤销 mutation 与撤销幂等记录处于同一 SQLCipher transaction，重开后同请求重放同一终态。

`visible_events` 不是任意 ID 读取：只接受当前绑定的 Personal space、`event`、`structured`，查询最多 1,000 code points、结果 1–100 条，并在 SQLCipher current active projection 上执行 AND 关键词、时区化时间范围和 session sensitivity 交集。生产 runtime 只授予 `public/personal/confidential`；Restricted 即使存在也不能越过 session，且只有 session 本身获准 Restricted 时，`risk_filtered` 才可反映策略过滤，避免向未授权 caller 泄漏存在性。返回仅含 bounded title（240）、description（1,000）、Event/revision/type/evidence/fact/sensitivity 与 `content_truncated`，不含 user words、source label/ID、locator、路径或 Raw。Host 对成功结果再次做 exact key/scope/type/sensitivity/长度/数量绑定，恶意结果会毒化会话；`get_context` 仍把正文视为不可信数据并执行 injection、item/token budget。`get_event` 与 `set_policy_blocked` 继续返回 `OPERATION_UNSUPPORTED`，因此 Revision 写入不会用目标读取把旧正文复制进 Host control state。

iOS 生产客户端也实现这四种操作的 canonical request builder、exact Grant scope 子集校验、typed response/result digest/错误形状和冻结 retryability 校验；握手未声明 operation 时不发送，malformed 响应关闭通道，合法远端应用错误只作为 content-free typed error 返回。原始 application exchange 不公开，生产 exchange 只按当前时间授权。普通用户二维码入口的默认 Grant 与展示仍为 event-only，Revision/read/undo 只能由另行明确授权的 Grant 构造，不能因客户端支持而静默扩权。

当前仓库证据覆盖 canonical 应用层、Host TLS 1.3 adapter、Android 配对 listener/凭据生命周期、SQLCipher 原子写入/读取、iOS Shared smoke/静态契约，以及 API 36 / 16 KB arm64 AVD 上 Swift Network.framework→Android SQLCipher→Today 的 create/read/revision/exact undo 纵向闭环。该 Smoke 使用 ADB forward 与显式 expanded Grant，不证明物理 LAN、普通用户 scope 扩张、共享账户 Grant registry、Android 后台生命周期、跨端撤销传播、真实 Codex/Claude Code/Cursor 生产宿主或真实设备 ContextPack/Recall。

## 5. 错误码目录

### 5.1 身份、权限与策略

| Code | Retry | UI/调用方动作 |
|---|---:|---|
| `AUTH_REQUIRED` | 否 | 重新登录/配对，不显示内容存在性 |
| `GRANT_MISSING` | 否 | Mobile 发起审批 |
| `GRANT_EXPIRED` | 否 | 重新审批有效期 |
| `GRANT_REVOKED` | 否 | 停止调用，不能自动再授权 |
| `PURPOSE_DENIED` | 否 | 收窄/重新解释用途 |
| `SPACE_DENIED` | 否 | 不提示目标空间是否有内容 |
| `DATA_TYPE_DENIED` | 否 | 收窄 data types |
| `CONTRACT_EXPIRED` | 否 | 用户重新连接来源 |
| `PERMISSION_DENIED` | 否 | 提供系统设置/替代输入 |
| `POLICY_BLOCKED` | 否 | 显示不含敏感规则细节的原因 |
| `SENSITIVE_MODEL_BLOCKED` | 可降级 | 改用本地/确定性处理 |

### 5.2 一致性与兼容

| Code | Retry | 动作 |
|---|---:|---|
| `REVISION_CONFLICT` | 条件 | 拉当前 revision，合并或请用户选择 |
| `IDEMPOTENCY_CONFLICT` | 否 | 生成新 key 或修复调用方 bug |
| `SCHEMA_UNSUPPORTED` | 升级后 | quarantine 并升级，不丢原 envelope |
| `SYNC_SEQUENCE_GAP` | 是 | 从期望 sequence 重发 |
| `CURSOR_EXPIRED` | 是 | 用原查询从安全锚点重启 |
| `TOMBSTONE_PRECEDENCE` | 否 | 丢弃/隔离旧更新，不复活对象 |

### 5.3 来源、处理与资源

| Code | Retry | 动作 |
|---|---:|---|
| `SOURCE_UNAVAILABLE` | 条件 | 保留其他来源和本地结果 |
| `SOURCE_RATE_LIMITED` | 是 | 按 retry-after 等待，不显示“无活动” |
| `SOURCE_NO_DATA` | 否 | 只表示请求范围无数据，不推断现实无活动 |
| `PROCESSING_FAILED` | 是/降级 | 保留原始补充，重试或改文字 |
| `STORAGE_FULL` | 清理后 | 不返回保存成功，提供清理/导出 |
| `TEMPORARILY_UNAVAILABLE` | 是 | 保留已有内容和离线队列 |

### 5.4 删除与导出

| Code | Retry | 动作 |
|---|---:|---|
| `DELETE_IN_PROGRESS` | 查询 | 打开现有 Job，不重复创建 |
| `DELETE_PROOF_INCOMPLETE` | 是/人工 | 显示未确认副本和下一步，不显示完成 |
| `EXPORT_SCOPE_CHANGED` | 重建 | 重新固定 snapshot，不静默缩小 |
| `EXPORT_EXPIRED` | 重建 | 重新导出，过期文件不可继续打开 |

HTTP status 只是传输层：400 输入、401 未认证、403 已认证但拒绝、404 无权时统一隐藏存在性、409 一致性、410 过期导出、422 语义、429 限流、5xx 可恢复故障。客户端逻辑以稳定 Code 为准。

## 6. 负向验收

1. 过期/撤销 Grant、跨 space、超 purpose 和超 data type 均返回不同 code，payload 为空。
2. 同 idempotency key 重放不产生第二个 SourceObject/Feedback/Job；异内容返回冲突。
3. 同 Event 同 base 的双设备同字段修改进入 conflict；不同字段可合并且历史均在。
4. Cursor 不能跨用户、Grant、space 或 query 使用。
5. 未同步/索引过期返回 partial reason；空结果不隐藏范围缺口。
6. Tombstone 到达后旧 update 被隔离；搜索、ContextPack 和 index 回读不能复活。
7. 所有错误 details、trace、audit 经过敏感内容扫描。

## 7. 代码生成与兼容任务

- 从单一 JSON Schema 生成或校验 Swift/Kotlin/服务端 DTO；生成结果不作为正本提交手改。
- OpenAPI 生成客户端只处理传输，领域构造必须经过平台/核心验证器。
- CI 运行 `python scripts/validation/validate_contracts.py`，并在后续加入 breaking-change diff、fixture round-trip 和 SDK smoke test。
- 任何破坏性接口变更先更新 PDR/ADR、迁移、旧客户端行为和回滚，再升 `/v2` 或 schema major。
