# Ameme MVP 本地存储、同步与删除协议 v0.6

> 文档状态：已接受；MVP 存储/同步/删除实现正本，达成情况待 Spike\
> 更新日期：2026-07-14\
> 上游：`MVP领域契约与状态机.md`、`../../packages/contracts/schemas/ameme-domain.schema.json`、`../decisions/ADR-005-MVP技术实现默认栈.md`\
> 实现基线：Android 固定官方 `net.zetetic:sqlcipher-android:4.15.0` + SQLite WAL；schema v6 的 SourceLocator、DayLedger/Summary、Agent 持久幂等、可回退 FTS5、异步 I/O、Calendar/Voice 显式来源和 10k/100k 已形成 API 36 x86_64 AVD 证据。应用私有 Raw Vault AES-256-GCM、iOS、LAN append-only peer sync、真机、16 KB 与 UI 性能仍待验证。官方来源：<https://github.com/sqlcipher/sqlcipher-android>、<https://central.sonatype.com/artifact/net.zetetic/sqlcipher-android/4.15.0>。

## 1. 设备内逻辑分区

| 分区 | 内容 | 推荐持久化 | 关键边界 |
|---|---|---|---|
| Secure Config | 设备身份私钥、空间 key 引用、Connector/会话凭据 | iOS Keychain / Android Keystore | 不进入业务 SQLite、日志或导出 |
| Catalog | Contract、SourceObject 元数据、Observation、lineage、source health | SQLCipher/SQLite WAL | Raw 指针与正文分离 |
| Source Index | 系统对象 locator/bookmark/content URI、fingerprint、可用状态 | SQLCipher；locator 设备本地 | Raw 缓存过期后仍能回跳原对象；不保证原对象永远存在 |
| Event Store | EventCandidate、EventRevision、Event current projection、Feedback | SQLCipher append + projection | Revision 为真相，projection 可重建 |
| Day Store | DayLedger entry、coverage、Summary manifest | SQLCipher | Summary 文本是派生物 |
| Recall Index | FTS5/日期索引；embedding 默认关闭 | 同库可删除重建索引 | 不作为权限或删除真相 |
| Raw Vault | 音频、选定照片副本、中间缓存、manifest | 应用私有文件 + 每对象 AES-256-GCM | 独立 nonce/DEK/retention/hash |
| Job/Outbox | processing、sync、deletion、export、recompute | SQLCipher durable queue | App 重启后继续；幂等 |
| Audit | 授权、访问、高风险动作和结果码 | SQLCipher 受限表/安全导出 | 不含正文和原始错误文本 |

## 2. 逻辑表与索引

物理命名可调整，但以下关系必须存在：

| Table | 主键/唯一约束 | 必要索引 |
|---|---|---|
| `acquisition_contracts` | contract_id | space/source/revocation、expires_at |
| `source_objects` | source_object_id；idempotency_key unique/owner | space+acquired_at、contract、processing、expires_at |
| `source_locators` | source_object_id+device_id；opaque locator unique/owner | state+last_verified、fingerprint；设备本地 |
| `observations` | observation_id | source_object、space+kind+time |
| `user_addenda` | addendum_id | target、space+submitted_at |
| `event_candidates` | candidate_id | status、space+time |
| `event_revisions` | event_revision_id；event_id+revision unique | event+revision、base revision |
| `events_current` | event_id | space+start time、state、fact status |
| `field_evidence` | event/revision/field/observation | observation、source lineage |
| `day_ledgers` | space+local_date+timezone | owner+date、coverage state |
| `day_ledger_entries` | ledger+event unique | ledger+sort_key |
| `summaries` | summary_id；ledger+based_on_revision unique | state、ledger |
| `feedback_events` | feedback_id | target、space+created_at |
| `access_grants` | grant_id | caller+status、expires_at、space relation |
| `lineage_edges` | edge_id；from/to/relation unique | from、to |
| `sync_outbox` / `sync_inbox` | envelope_id；device+sequence unique | state+retry_at、space+sequence |
| `deletion_jobs` / `deletion_steps` | job_id / job+step | state+updated_at、target |
| `export_jobs` | job_id | state+updated_at、expires_at |
| `audit_events` | audit_id | actor+time、action+result、space+time |
| `schema_migrations` | migration_id | applied_at、app_version |

所有内容表必须带 `space_id` 或通过不可绕过的外键关联到 space。查询层不得依赖调用方记得手写 space filter；Repository/DAO 接口把 space 作为必填构造参数。

## 3. 事务边界

### 3.1 主动补充

同一设备事务内完成：

1. 校验有效 Contract/space。
2. 写 SourceObject/UserAddendum、本地 Raw manifest；外部/系统 Raw 同时写 SourceLocator 和 fingerprint。
3. 写 processing job 与 sync outbox（若允许）。
4. commit 后才向 UI 返回 `capture_saved_local`。

Raw 文件写入采用临时文件、fsync/平台等价操作、hash 校验和原子 rename；数据库只引用已完成 manifest。磁盘不足不能返回保存成功。

### 3.2 Event Revision

同一事务内校验 `If-Match-Revision`、追加 Revision、更新 current projection、更新 DayLedger revision、把 Summary 标为 stale、写 index/recompute outbox。索引更新在事务外可重试，但旧索引结果必须经过 current/tombstone 校验。

### 3.3 删除请求

同一事务内创建 DeletionJob、把目标设为不可新读取/处理、写 tombstone outbox 和步骤清单。物理清理由后台执行；UI 的“请求已受理”与“全部完成”使用不同状态。

## 4. Raw Vault

每个 manifest 至少保存：object id、space、source id、key id、cipher/hash、MIME、大小、chunk、created/expires、retention class、deletion state 和 selected-for-sync。日志不记录明文绝对路径。

- 默认原始只在来源设备；结构化同步不自动复制 Raw。
- `selected_raw_sync` 逐对象向获准 LAN peer 发送加密副本，不因空间模式改变而批量回溯传输旧 Raw，除非用户明确选择。
- 系统相册引用失效与 Ameme 副本删除分开建模。
- 初始 TTL 采用 ADR-005 集中配置：位置点 24 小时、主动音频 7 天、照片预览 7 天、模型临时缓存 24 小时。每类仍必须通过最大重试窗口、空间预算、清理行为和删除证明 Spike；Spike 可以收紧，延长 Raw/Restricted 需新 SDR。
- Raw 缓存到期只清除 Ameme 副本/临时内容；SourceLocator、fingerprint、来源/权限状态按结构化生命周期保留，使用户仍可尝试打开系统原对象。
- Locator 只能保存平台授予的 opaque token：Photo asset id、security-scoped bookmark、persisted content URI 或 app-private object id。不得把明文绝对路径写入日志、遥测、Agent ContextPack 或其他设备。
- 回跳前重新验证权限、fingerprint 和状态；对象移动、删除或撤权时显示 `moved/missing/permission_revoked/deleted`，不能声称“保证可找回原文件”。

## 5. 同步协议

### 5.1 角色与最小职责

- Peer Device：发现同网设备，生成 device sequence/outbox、认证/加密会话、交换 cursor/envelope/ack，应用 inbox 并维护本地投影。
- Initiator/Responder：仅是本次会话角色；双方都保存 durable outbox/inbox/known-peer state，不存在中心数据协调者。
- Account Identity：只验证账户/设备归属和会话，不保存、路由或处理记忆内容。
- Processor（可选）：端侧优先；外部模型只在独立处理授权下接收最小输入并留下 lineage。

LAN 被视为不可信网络。发现广播不含账户/space/内容 ID；连接后验证同账户和设备身份，建立加密会话，再交换最小 capability/cursor。Raw 与 Restricted 不因同网自动扩大范围；selected Raw 仍需逐对象/空间策略。

### 5.2 Push

1. Device 从 outbox 按优先级取 `grant_revocation/tombstone/deletion_ack`，再取普通 envelope。
2. 接收 peer 验证账户/设备身份、space membership、Schema、device sequence、幂等和 payload 策略。
3. 接收 peer 本地持久化后返回 durable ack/cursor；发送端只在 ack 后标记 delivered-to-peer。
4. Sequence gap 返回期望序号，不丢弃本地 envelope；重复 envelope 返回原 ack。

### 5.3 Pull

Cursor 绑定 peer pair、space、授权和发送设备顺序。每批返回 envelope、next cursor 和 `complete/partial`；设备先应用撤权/tombstone，再应用内容。未知 major 进入 quarantine 并停止推进该 peer/space 的完成 cursor，防止静默漏对象。

### 5.4 冲突

- Append-only revision 不使用整行 last-write-wins。
- 同 base 修改不同字段可自动合并；同字段用户修改进入 conflict。
- 删除/撤权优先，普通更新隔离。
- DayLedger/Summary/index 是本地重建投影，不进行全文互相覆盖。
- 设备时间不能决定最终胜者；V1 以 sequence/base revision 表达因果，HLC/向量时钟由 Spike 决定。

### 5.5 完整性表达

只有以下均满足，查询才可标 `complete_for_requested_scope`：

1. 请求空间/设备范围明确；
2. 参与设备 cursor 已追平当前已知上界；
3. Grant/Contract 未过期；
4. current projection 和索引已消费对应 Revision/tombstone；
5. 没有 quarantined major 或待处理 gap。

否则返回 `partial` 和稳定 reason，不能用空数组替代。

## 6. 删除传播

### 6.1 影响图

Deletion planner 从 target 沿 lineage 计算：Raw、SourceObject、Observation、Candidate、FieldEvidence、Event/Revision、DayLedger entry、Summary、Recall/向量索引、ContextPack manifest、缓存、同步副本和可删除审计字段。

### 6.2 单来源与多来源

- 事件只依赖被删来源：删除/隐藏 Event current，保留最小 tombstone 和证明。
- 事件有其他来源：移除证据，追加 `deletion_recompute` Revision，重算字段状态；不得保留被删来源的独特文本。
- 用户后来独立确认的字段：删除前让用户选择“仅移除来源并保留我的确认”或“连同事件删除”，结果进入 job scope。

### 6.3 完成条件

`completed` 需要：本机 Raw/结构化/派生/索引清理完成；所有当前可达同步副本 ack；投影重算完成；proof manifest 生成。离线或已遗失设备无法确认时保持 `partial_failed`/pending replica，并向用户显示例外。

证明只包含对象 ID 的不可逆摘要、步骤、时间、设备/副本状态和错误码，不包含已删正文。

## 7. 撤权与设备移除

- 撤来源权限：停止新读和新处理，历史按原策略保留，用户可另发删除。
- 撤 Agent Grant：新调用和刷新立即失败，本机及已知 peer 销毁相应会话；已发 ContextPack 按 expiry 到期并禁止刷新。
- 移除设备：吊销设备身份和新同步权限；已持有本地数据不能被远程“假装已物理删除”，若设备仍在线则派发删除，离线则记录证明缺口。
- 删除 space/account：先冻结写入、撤所有 Grant/Contract，再执行全图删除；失败不可恢复到普通可用状态。

## 8. 备份、迁移和回滚

1. 数据库迁移前做本地加密快照和可用空间检查；快照保留不超过迁移/回滚所需窗口。
2. 迁移脚本单调、可重复检测，记录 checksum/app version；失败回滚到旧库，不在半迁移库继续写。
3. Event Store 迁移与 Recall index 重建分开；索引可删后重建。
4. App 降级若不能理解当前 major，只读导出/升级提示；禁止写旧格式破坏安全对象。
5. Peer 升级使用 tolerant reader → writer 顺序；破坏性变化通过新 major、能力协商和双读窗口，未知 major 隔离但不污染现有空间。

## 9. Android 本地 Event 最小切片达成边界

2026-07-14 的 Android 切片已经实现并在 API 36 x86_64 AVD 验证：注入式 `DatabaseKeyProvider`、Keystore AES-256-GCM 包裹随机数据库 key、SQLCipher WAL、数据库 trigger 强制 `event_revisions` 禁止 UPDATE/DELETE、`events_current` 投影、Repository 显式绑定 `space_id`、commit 后再展示、tombstone 后重建仍不可见，以及 v1→v2 Revision backfill、v2→v3 `space_legacy` 隔离、v3→v4 `source_locators`、v4→v5 来源实例和 v5→v6 Event policy/DayLedger/Summary/Agent 幂等迁移。跨空间相同 Event ID 可共存且不可互读/互删。错误密钥被拒绝，主库文件头不是明文 SQLite header；SQLCipher/应用日志不输出正文或 key。Compose 的 open/read/write/search/来源访问和授权清理由统一 I/O 边界执行，取消后的 open 不泄漏 repository，重组也不会提前关闭当前实例。

`source_locators` 只在 SQLCipher 内保存 `content://` URI、MIME、来源、访问模式、生命周期和可选来源实例键，不复制照片/PDF/音频原始内容。Event revision、current projection 和 locator 在同一事务写入；用户取消 Photo Picker/系统录音/音频选择、空输入、无读取授权、非 `content://`、多项或非白名单 ACTION_SEND 不产生 Event。语音只接受系统返回的音频引用，不申请麦克风权限，也不生成转写或占位正文。Calendar 只在用户显式选择可见 calendar IDs 和 1/7/31 日窗口后读取 Provider；物理游标行数有硬限，全天日期不受本地时区漂移，同一 active instance 跨批次/重启幂等，且始终写为 `Planned` 而不是已发生事实。

DB-01 在 `c8fc128` 上形成有效 [`10k/100k API 36 AVD 报告`](../../tests/results/performance/android-db01-api36.json)：100k FTS 关键词页 P95 177.72 ms、单次提交 P95 215.67 ms，分别通过 700 ms 和 300 ms 的预注册数据库门；加密数据库 95,227,904 bytes，v3→v5 10k 迁移 P95 892.51 ms。日期筛选 P95 734.56 ms 仍是观察项；所有数字都不是物理设备或 UI 首帧证据。

持久 URI 授权删除使用可恢复两阶段状态：Event tombstone 事务只把同空间 `PersistedRead` locator 改为 `RELEASE_PENDING`，普通 `sourceLocator` 立即不可见；`SessionRead` 可直接进入 `DELETED`。事务外、按 repository space 注入的 cleanup coordinator 在生产 App 打开 repository 后和 UI 删除后运行；OS release 成功或确认授权已不存在后写 `RELEASED`，返回 false、抛异常或无法确认时保持 pending。即使删除由 Agent/API 直接调用、commit 后进程崩溃或首次 release 失败，重启仍可枚举并重试；其他 space 不能读取或完成该任务。数据库事务内禁止调用 `ContentResolver`。

Recall 已增加不透明 keyset cursor 和日期分页。被测 SQLCipher 运行时成功创建 FTS5 虚表；FTS5 与强制 LIKE fallback 已验证共同的“最多 16 个空白分词、每个词都须在 Event 字段中命中”契约，以及一致的参数化查询、`space_id`、日期、ACTIVE state、删除过滤和稳定倒序。该证据不声称 FTS tokenizer 的所有边界与 substring LIKE 完全等价。FTS 表是派生索引，创建或重建失败时数据库仍可打开并使用 LIKE。结论只覆盖 API 36 AVD 的 SQLCipher 4.15.0 运行时，不外推所有设备或 16 KB page size。

Android 清单同时保持 `allowBackup=false`，`data-extraction-rules` 对 cloud backup 和 device transfer 显式排除 root/file/database/sharedpref/external 及四个 device-protected data domain；编译后 XML 资源由设备测试核对。该配置用于避免 SQLCipher DB 和 wrapped-key blob 被系统备份或 D2D 搬迁，仍需后续厂商/真机矩阵验证：<https://developer.android.com/identity/data/autobackup>。

Agent 本地应用层已接入同一个 SQLCipher repository：`ameme.agent-local-node.v1` 的 `create_event` 只创建初始 Revision `1`；配对 TLS 1.3/certificate pin/HMAC/session/sequence 通道验证后，Android 以 pairing 为当前 MVP 信任根，继续约束单一 Personal space、Event 类型、结构化数据和受支持 operation。幂等槽与 Event 在同一 SQLCipher transaction 中提交，关闭重建后同内容重放原结果、异内容冲突。API 36 AVD 的真实 Host smoke 证明 Event 不是由 ADB 注入，Today 可见且 App 重启后仍存在。共享账户 Grant registry、append/undo/recall、LAN/NSD、后台与物理设备仍不在这条证据内。

该证据仅关闭“最小本地 Event 闭环可运行”的实现问题，没有关闭完整协议或 DB-01：

- 重建测试是关闭数据库并重新构造 repository，不等于操作系统杀进程/崩溃恢复；
- 未验证真机、iOS、16 KB page size、真实 Today 首帧/搜索端到端、后台锁、电量或峰值内存；10k/100k 仅形成单台 API 36 x86_64 AVD 数据库基线；
- 当前删除是追加 tombstone 并更新本机投影，尚未实现物理清除、影响图、peer ack 和删除证明；
- SourceLocator 目前覆盖 Photo Picker/ACTION_SEND/Calendar/Voice 元数据、实例幂等和授权生命周期，未覆盖可用性复核、fingerprint、Raw Vault 或跨设备行为；
- Raw Vault、durable job/outbox、完整 lineage/审计和 LAN sync 尚未进入该切片；
- Agent `create_event` 已完成配对 Host→TLS/HMAC→Android→SQLCipher 的模拟器纵向闭环；共享账户 Grant、NSD/物理 LAN、后台、append/undo/recall 和真实第三方宿主仍待实现；
- v1→v2→v3→v4→v5→v6 已验证成功迁移与 legacy space 回填，但故障注入、加密快照和失败回滚仍属于 MIG-01。

## 10. 必须执行的 Spike

| Spike | 环境/输入 | 指标 | 通过/否决 | 证据 |
|---|---|---|---|---|
| DB-01 SQLite/加密 | 双端最低候选设备、10k/100k Event 合成库 | 首帧、分页、事务、迁移、体积 | AVD 数据库门已过；仍需物理设备/16 KB/UI | `tests/results/performance/android-db01-api36.json` |
| SYNC-01 离线冲突 | 两设备模拟器、乱序/重复/断网 envelopes | 丢失、重复、冲突、收敛 | 无静默丢失；tombstone 不复活 | `tests/results/sync/` |
| LAN-SYNC-01 发现/权限/会话 | 双端真机、同网/热点/隔离 Wi-Fi/恶意 peer | 发现率、授权、连接、后台窗口、吞吐、资源 | 无明文广播/越权；拒权可单机；可恢复同步 | `tests/results/lan-sync/` |
| DEL-01 删除证明 | 单/多来源、Summary、索引、离线设备夹具 | 残留、ack、重算、时间 | 不假完成；恢复后收敛 | `tests/results/deletion/` |
| RAW-01 TTL/清理 | 音频、缩略、位置、模型缓存失败矩阵 | 恢复率、空间、电量、删除 | 预算内且不丢已确认保存 | `tests/results/retention/` |
| MIG-01 迁移回滚 | V1 合成库、故障注入 | 可恢复、耗时、数据 hash | 失败回旧库；主链 hash 一致 | `tests/results/migration/` |

这些 Spike 是研发/真机证据，当前文档只把执行条件和否决门槛补齐。
