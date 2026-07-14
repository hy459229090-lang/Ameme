# ADR-002：设备本地 Event Core 使用 SQLite + Raw Vault，索引和 Summary 可重建

- 状态：accepted
- 日期：2026-07-13
- 决策人：研发前架构基线
- 影响 Gate：Gate 2–4
- 替代/被替代：无

## 背景与问题

`今天`、补充、修订和删除必须离线可用，且 AI/网络不能阻塞保存。MVP 需要事务、关系、FTS、迁移和可删除重建能力，不需要先引入图数据库或端侧向量基础设施。

## 约束

- Raw 默认留在来源设备并有独立保留策略。
- EventRevision 是真相，DayLedger/Summary/index 是投影。
- iOS/Android 都有成熟 SQLite 能力；加密方案仍需真机 Spike。

## 候选方案

| 方案 | 价值 | 风险 | 成本 | 证据 |
|---|---|---|---|---|
| 纯文件/Markdown | 可读可导出 | 事务、冲突、索引、删除弱 | 低 | 不满足主链 |
| SQLite + private Raw Vault | 事务/迁移/FTS/队列成熟 | 加密与并发需验证 | 中 | 符合契约 |
| SQLite + 图/向量 DB | 召回扩展 | 体积、迁移、删除复杂 | 高 | MVP 无必要证据 |

## 决策

设备使用 SQLite 逻辑库 + 应用私有 Raw Vault；密钥在系统安全存储。EventRevision/outbox 为持久真相，current/DayLedger/FTS/Summary 可重建。MVP 不引入图数据库；向量索引仅在词法/结构化召回达不到预登记任务时进入 Spike。

## 后果与回滚

- SQLCipher 或字段/文件 envelope encryption 由 Spike 选择，不改变逻辑表。
- 索引故障降级到结构化/日期查询；不得影响 Event Store。
- 将来可把同步副本落 PostgreSQL，但不把云表作为设备写入的唯一真相。

## 待验证项

最低设备性能、加密、100k Event FTS、迁移和删除证明。
