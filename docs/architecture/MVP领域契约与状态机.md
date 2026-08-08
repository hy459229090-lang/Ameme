# Ameme MVP 领域契约与状态机 v0.1

> 文档状态：已接受；领域不变量与状态机为研发前正本，机器契约已校验\
> 更新日期：2026-07-13\
> 机器正本：`../../packages/contracts/schemas/ameme-domain.schema.json`\
> 示例：`../../packages/contracts/examples/synthetic-day.json`\
> 边界：本文冻结领域语义、不变量和状态转换；物理存储、同步与删除执行见 `MVP本地存储同步与删除协议.md`。

## 1. 领域链与真相层级

```text
AcquisitionContract
  -> SourceObject
  -> Observation
  -> EventCandidate
  -> EventRevision -> Event current view
  -> EpisodeRevision -> Episode current grouping
  -> DayLedger
  -> Summary / RecallPage / ContextPack

UserAddendum ------^               |
FeedbackEvent ---------------------+--> future policy/eval
LineageEdge connects every derivation
```

真相层级固定如下：

1. `SourceObject` 是系统实际获得的来源信封，不证明用户做了什么。
2. `Observation` 是解析器或用户可直接观察/陈述的局部事实。
3. `EventRevision` 是不可变变更记录，`Event` 是其当前投影视图。
4. `Episode` 是同一 space 内多个阶段/相关 Event 的可版本化组合；它不合并或覆盖成员 Event 的事实。
5. `DayLedger` 是某 `space + local_date + timezone` 下的 Event/Episode 有序投影，不是另一份正文。
6. `Summary`、搜索索引、向量、RecallPage 和 ContextPack 都是可失效、可重算派生物。
7. MVP 不自动形成长期人格结论；Memory 仅保留为后续版本能力，不进入当前机器契约主链。
8. `Artifact` 是 Agent/分享入口带回的可引用产物元数据；正文仍由 SourceObject/Raw policy 管理，Artifact 不能绕过 Event 与空间权限直接暴露。

## 2. 标识、时间和版本

| 规则 | 冻结内容 |
|---|---|
| ID | 客户端生成、全局唯一、不可从内容推导；前缀只帮助诊断，不携带用户信息 |
| 版本 | 持久化对象均携带 `schema_version=1`；Event/DayLedger 另有单调 `revision` |
| 时间 | 业务事件保存带偏移的 UTC 时刻、原始 `timezone` 和 `precision`；未知结束时间不补零时长 |
| 日期归属 | DayLedger 用事件发生地/用户当时确认的时区；跨午夜事件可在主日显示并被相邻日引用，不复制 Event |
| 因果 | `device_sequence + base_revision` 优先于墙上时钟；时间戳只作显示和辅助排序 |
| 幂等 | 写命令必须有 idempotency key；同 key 同内容返回原结果，同 key 异内容拒绝 |
| 敏感度 | `public / personal / confidential / restricted`；下游只能保持或提高，不能自行降低 |

## 3. 核心不变量

| ID | 不变量 | 违反后的处理 |
|---|---|---|
| INV-001 | Adapter 只能写 Contract/SourceObject，不能直写已确认 Event | 拒绝输入并记录非内容错误 |
| INV-002 | Event 的每个事实字段至少有一条 FieldEvidence；未知字段省略 | Candidate 保持待核验，不补写事实 |
| INV-003 | 用户原话和历史 Revision 不被模型覆盖 | 追加 Revision；保留旧版本 |
| INV-004 | `planned` 不自动升级为 `confirmed` | 需要现实来源或用户确认 |
| INV-005 | 情绪/关系/重要性与事实证据、授权解耦 | 不提高任何事实字段置信度，不触发或绕过扩权 |
| INV-006 | 不同 space 的内容不得合并成同一 Event | 拒绝合并；仅在获准视图层并列显示 |
| INV-007 | Restricted 默认不能送往外部模型 | 本地/确定性降级或 `SENSITIVE_MODEL_BLOCKED` |
| INV-008 | 保存成功以本地事务提交为准，不以 AI/同步完成为准 | 后台失败进入可恢复状态，不撤销成功文案 |
| INV-009 | DayLedger、Summary、索引不能成为反向覆盖 Event 的来源 | 只允许从 Revision 重建投影 |
| INV-010 | Recall 返回是请求、Grant、空间策略、数据可用范围的交集 | 越界返回拒绝，不返回“空结果”掩盖策略失败 |
| INV-011 | Tombstone/撤权先于普通内容同步应用 | 旧内容进入隔离，不能复活 |
| INV-012 | 删除完成必须有目标副本和派生物的完成/例外证明 | 证明不全保持 `partial_failed` |
| INV-013 | 遥测、审计和错误不得携带正文、搜索词、位置、健康值或模型输入 | 丢弃敏感字段并触发安全测试失败 |
| INV-014 | 不支持的 Schema major 不得猜测解析 | 返回 `SCHEMA_UNSUPPORTED`，保留原信封待升级 |
| INV-015 | UserAddendum 必须有 target_id；新事件补充指向目标 DayLedger/日期占位，修订指向 Event | 拒绝写入并保留本地草稿 |
| INV-016 | LineageEdge 方向固定为 derived/current object `from` -> dependency/parent `to` | 反向边拒绝写入，避免删除影响图方向歧义 |
| INV-017 | Episode 的全部 Event 必须同 owner/space；Episode Revision 不改变成员 Event 事实 | 跨空间组合拒绝，成员变化只追加 EpisodeRevision |

## 4. 状态机

### 4.1 AcquisitionContract

```text
not_requested -> limited / foreground / background_limited / granted
not_requested -> denied / unsupported
active -> paused -> active
active / paused -> revoked
active -> expired
```

- 系统权限、Ameme Contract 和 Agent Grant 是三个不同对象；任一收窄都会收窄最终能力。
- 撤权冻结新获取和新处理，不等同于删除历史；界面必须让用户单独选择删除。

### 4.2 SourceObject

```text
acquired -> queued -> processing -> processed
                         |             |
                         v             v
                       failed ------> queued
acquired/queued/processing -> quarantined
any non-deleted -> deleted (only through DeletionJob)
```

- `failed` 保留安全可恢复的原始对象或引用；到期清理由 DeletionJob/retention worker 处理。
- `quarantined` 用于 Schema、策略、恶意输入或空间不明；不得进入 Event 主链。

### 4.3 EventCandidate 和 Event

```text
candidate -> accepted -> EventRevision(initial)
candidate -> needs_review / conflict -> accepted / rejected
candidate -> superseded

Event active -> recomputing -> active / conflict
Event active/conflict -> deleted
```

Event current view 只由 Revision 投影。Merge/Split 产生新 Revision 和 lineage；旧 ID 通过 superseded/redirect 元数据保持可解释，不静默复用到不同事件。

Episode 与 Event 使用相同的 append-only 原则：成员增删、顺序、标题和 merge/split 都追加 EpisodeRevision；Event 修改只使 Episode `recomputing`，不能被 Episode 反向覆盖。

### 4.4 DayLedger 和 Summary

```text
DayLedger coverage:
empty / sparse / ready_local / processing / partial / syncing /
permission_limited / offline

Summary:
absent -> processing -> ready
ready -> stale -> processing -> ready
absent/processing -> insufficient
ready/stale -> deleted
```

- `ready_local` 只表示本机可用，不表示跨设备完整。
- 任一参与 Event Revision、删除、空间迁移或证据撤回都会使对应 Summary `stale`。
- `insufficient` 不生成空泛正文。

### 4.5 AccessGrant 和 ContextPack

```text
Grant active -> revoked
Grant active -> expired

ContextPack ready / partial / denied -> expired
```

每次调用实时检查 Grant；已生成 ContextPack 还要有短有效期和 caller 绑定。撤销后的缓存不得被新任务继续使用。

### 4.6 SyncEnvelope

```text
local_pending -> uploaded -> acknowledged -> applied
      |             |             |
      v             v             v
   retryable      rejected      quarantined
```

SyncEnvelope 不原地更新。拒绝原因必须区分 schema、grant、space、base revision、sequence gap、payload policy 和 tombstone precedence。

### 4.7 DeletionJob 与 ExportJob

删除状态以机器 Schema 为准：

```text
queued -> local_deleting -> sync_propagating -> recomputing -> completed
                         \-> partial_failed <-/
```

部分失败修复后从失败步骤继续，不重新暴露已删除对象。导出采用 `queued -> snapshotting -> rendering -> completed/failed -> expired`，快照失败不修改原数据。

## 5. 事件字段与权威

| 字段 | 优先证据 | 冲突规则 |
|---|---|---|
| 时间 | 来源原始时间、用户确认、系统到访区间 | 时区/精度归一后仍冲突则保留多值并待核验 |
| 地点 | 用户确认、照片/系统位置、日历地点 | 计划地点不能覆盖现实位置；近似不能显示精确地址 |
| 人物/关系 | 用户表达、获准联系人/会议参与信息 | 不能从人脸/聊天猜关系；冲突需用户确认 |
| 动作 | 用户原话、可观察来源组合 | App 打开/文件修改本身不足以证明高层动作完成 |
| 结果 | 执行回执、版本/任务状态、用户确认 | 文件名或计划词不能证明“已完成/已上线” |
| 意图/情绪 | 用户明确表达 | 模型只能候选，不替用户定性 |

权威按字段判断，不给整个来源一个总分。用户对自身感受/意图权威，对外部系统结果不自动高于系统回执。

## 6. 并发与冲突矩阵

| A | B | 结果 |
|---|---|---|
| 用户字段修改 | 模型/解析器重算 | 用户字段保留；系统可提交带证据的冲突候选 |
| 用户修改 | 另一设备用户修改同字段同 base | 创建 conflict，不用 last-write-wins |
| 不同字段 Revision | 不同字段 Revision | 可交换合并，产生新 revision 并保留双亲 lineage |
| Source 更新 | 已确认用户断言 | 不覆盖；降低/提高相关证据状态并提示 |
| 计划 Observation | 现实 Observation | 保留计划与现实状态；确认 Event 使用现实证据 |
| Tombstone | 普通更新/旧设备重放 | Tombstone 优先；更新隔离并记录 ack |
| Grant revoke | 新 Recall/ContextPack | 拒绝调用；不得返回缓存内容 |
| 跨 space 合并 | 任意证据 | 永久拒绝，除非用户先显式迁移对象 |

## 7. Recall 与 ContextPack 语义

执行顺序固定：

```text
authenticate channel/device
 -> append content-free STARTED access audit
 -> validate Grant/status/expiry
 -> intersect purpose + spaces + data types + time
 -> query structured current revisions
 -> filter tombstone/restricted/source availability
 -> optionally rank candidates
 -> build day-grouped RecallPage or minimal ContextPack
 -> append content-free COMPLETED result/object-count bucket
 -> return range_state and partial reasons
```

- `STARTED` 必须在 repository 访问前持久化；失败时拒绝执行。`COMPLETED` 记录稳定结果码和数量桶，
  不保存正文、query、payload/digest、对象 ID、locator、路径、密钥或自由异常文本。中断或完成记录
  失败时保留未完成 `STARTED`，不得伪装成成功。
- Cursor 绑定 query hash、授权、空间集合、排序版本和快照上界；不能拿去翻另一个查询。
- `complete_for_requested_scope` 只表示请求范围内参与设备/索引状态完整，不表示人生完整。
- 向量只做候选召回，结构化权限、当前 Revision 和 tombstone 在最终返回前再次校验。
- ContextPack item 固定 `object_id + revision`，过期后必须重新查询，避免 Agent 长期持有隐式权限。

## 8. 兼容与迁移不变量

1. V1 writer 可增加可选字段，但不能改变已有字段含义或降低安全默认值。
2. 新 enum 对旧客户端未知时，旧客户端可展示通用状态但不得执行高风险动作。
3. 破坏性对象变化必须新 major、提供前向迁移、备份和失败恢复；同步协商最低共同 major。
4. 搜索/向量索引有独立 `index_version`，可删后重建；索引迁移失败不能破坏 Catalog/Event Store。
5. 降级客户端不得写入它无法完整理解的安全/删除对象。

## 9. 契约验收

当前离线校验命令：

```powershell
python scripts/validation/validate_contracts.py
```

研发切片 P0 还必须增加：

- 每个定义的有效/无效 fixture；特别覆盖未知字段、缺安全字段、越界枚举和错误 Schema major。
- 同 idempotency key 同/异 payload、Revision 冲突、跨空间、Grant 过期和 Tombstone 优先。
- 任意 Event 字段可回溯 Observation/SourceObject；删除影响图能包含 Summary、索引和副本。
- Swift/Kotlin/服务端生成或校验结果与同一 synthetic bundle 一致。

## 10. 待 Spike 而非待写作

- ID 具体编码（UUIDv7/ULID 等）和共享代码生成工具，以排序、隐私和生态支持实验决定。
- 双端最低版本、时区边界和系统回收后的后台恢复。
- 同步是否需要 HLC/向量时钟；V1 契约先保留 device sequence/base revision。
- 精确原始 TTL、tombstone 保留和 ContextPack/Grant 默认有效期。
