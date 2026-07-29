# Ameme MVP 本地存储、同步与删除协议 v0.20

> 文档状态：已接受；MVP 存储/同步/删除实现正本，达成情况待 Spike\
> 更新日期：2026-07-29\
> 适合读者：移动端、存储、同步、安全、恢复、Agent 与测试负责人\
> 人类快速阅读：先看“实现基线”、第 5 节删除语义、第 8 节迁移策略和第 9 节当前达成边界\
> AI 阅读提示：版本与通过范围以实现基线和第 9 节为准；不得把静态、Smoke、Simulator/AVD 证据扩写成真实用户、物理设备或分布式删除通过\
> 上游：`MVP领域契约与状态机.md`、`../../packages/contracts/schemas/ameme-domain.schema.json`、`../decisions/ADR-005-MVP技术实现默认栈.md`\
> 实现基线：Android 固定官方 `net.zetetic:sqlcipher-android:4.15.0` + SQLite WAL；schema v6 的 SourceLocator、DayLedger/Summary、Agent 持久幂等、可回退 FTS5、异步 I/O、Calendar/Voice 显式来源和 10k/100k 已形成 API 36 x86_64 AVD 证据；schema v7 加入 Coverage，v8 加入长期 Memory，v9 加入不可回退删除水位、备份 checkpoint 和认证同安装恢复候选，v10 加入四类复用的无正文 attempt/outcome，v11 加入本机 SourceObject/Event link/deletion job、单来源 cascade、source watermark 与当前安装 Personal space root freeze，v12 加入 exact-revision `event_field_evidence` 与多来源保守重算/删除计数，v13 加入 content-free exact-revision `event_user_confirmations`。v7–v13 SQLCipher instrumentation 已在 API 36、16 KB arm64 AVD 的本轮全量回归中执行；Android 仍没有 app-owned Raw Vault。iOS AES-GCM local-store envelope v8 已由生产 smoke 走通 Coverage、长期 Memory、删除水位、认证备份、四类短时复用、无正文遥测、app-owned media Raw-only、外部原件边界、单/多来源字段证据重算、完整用户确认删源保留、partial 确认 fail-closed 与当前安装 Personal space 根水位/旧备份拒绝。2026-07-29 进一步加入不改变 schema/envelope 的同安装候选激活内核：exact backup 短时确认、权威水位二次校验、同卷 staging 与 HMAC crash journal，失败回旧 live，commit 后保留已验证新 live；Android 已在 API 36 / 16 KB AVD 恢复类 3/3 与全量 91/84/7/0 执行，iOS 成功路径已由生产 smoke 执行。同日 iOS Local Node 客户端在普通用户 QR Grant 仍为 event-only 的前提下，补齐四项 Android 生产 v1 operation 的 canonical builder、最小 Grant scope、握手 capability 检查和 typed response/result-digest/error-shape fail-closed；原始 exchange 私有化且生产 exchange 只按当前时间授权。四项 operation 已在 API 36 / 16 KB arm64 AVD 完成 Swift Network.framework→Android SQLCipher→Today 纵向执行。两端设备密钥均未进入 artifact，因此不构成跨设备/全设备丢失恢复；本机 root 也不构成 Grant 撤销、peer/provider 删除或物理擦除证明。真实用户确认动作、account/Grant/peer proof、完整 Raw retention、LAN append-only peer sync、最终用户可见 ContextPack、物理设备与 UI 性能仍待验证。官方来源：<https://github.com/sqlcipher/sqlcipher-android>、<https://central.sonatype.com/artifact/net.zetetic/sqlcipher-android/4.15.0>。

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

发现与授权分层：NSD/Network.framework、短时二维码和 Account Identity 设备列表都只能产生有界候选；三种入口必须进入同一个 Grant 确认与设备证明流程。候选和连接状态可保存设备/Agent 展示名、方式、能力、时间与是否模拟等非敏感元数据，不保存一次性秘密、配对 JSON、IP 或端口。Android Debug 的确定性体验 Connector 只模拟候选和成功状态，不建立 socket、不创建 Grant/Event/审计；Release provider 必须为空。现有 TLS 1.3/certificate pin/HMAC 手工通道保留为 Debug 工程验证路径，不等于普通用户发现协议或 LAN sync 已实现。

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

产品层必须把两种删除意图区分为不同命令和影响预览：

- `delete_raw_evidence`：只清 Ameme Raw 密文/manifest，保留 SourceObject/Observation/Event/Revision/DayLedger/索引的结构化历史，并把原始证据标为不可回看；
- `delete_source_cascade`：从 SourceObject 沿 lineage 清理依赖的结构化、派生和索引对象；用户独立确认字段只有在影响预览中明确选择后才保留。

Core reference 已用 synthetic 故障矩阵证明这两个语义不会互换，以及 source cascade 后投影重建不复活对象；移动生产命令、peer ack 和物理删除仍需 DEL-01。

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

Core reference 提供一个非生产恢复 Oracle：SQLite online backup 生成一致结构化快照，已加密 Raw ciphertext 原样复制，manifest 记录 hash/size，恢复前后执行数据库 `quick_check` 和 Raw 完整性校验，备份不含密钥且只允许恢复到不存在的新目录。

2026-07-26 的移动生产切片另加入“同安装恢复候选”：Android 对 WAL checkpoint 后的完整 SQLCipher 文件做 hash/HMAC、`cipher_integrity_check`、`integrity_check`、schema 与删除水位校验；iOS 对完整 AES-GCM envelope 和被引用的 app-owned media 密文做精确清单、hash/HMAC、AES-GCM 与关系一致性校验。两端都只恢复到不存在的新候选目录，拒绝损坏、错 key、额外文件、非空目标和比权威删除水位更旧的快照，不 merge/覆盖 live store，不自动切换候选。

2026-07-29 增加独立“同安装候选激活内核”，但不改变上述候选恢复的非覆盖语义：调用方先获得绑定 exact `backup_id`、最长 15 分钟的明确确认；内核再次验证 candidate、manifest MAC、schema、密文/媒体完整性和调用时权威删除水位，再把候选复制到 live 同卷 staging。Android 要求 SQLCipher repository 已关闭且无 WAL/SHM sidecar；iOS 在 `MainActor` 同步冻结 store，并把 app-owned media locator 从候选根重写到 live 根。原候选不被移动或删除。

激活 crash journal 不含正文，只含版本、`prepared|committed`、确认 ID、backup ID 和当前设备密钥 HMAC。`PREPARED` 阶段只要旧 live rollback 存在，启动或调用内失败就无条件移除未提交新 live 并恢复旧根；新 live 完成密文、关系、水位和媒体复验后才把 journal 写为 `COMMITTED`，启动随后保留新 live 并完成旧 rollback 清理。伪造/损坏 journal、符号链接、保留文件冲突和候选漂移全部 fail closed；cleanup 未完成只返回 `cleanupPending`，不把 commit 误报为已回滚。

artifact、authorization 和 receipt 都明确 `productionRecoveryClaim=false` 与 `external_same_install_required`，不包含 SQLCipher raw key、Keychain/Keystore key、Agent pairing secret 或 pending-action/export snapshot。内核关闭仓库内 live-store 文件切换和失败回旧库的空白，但不关闭全设备丢失；它尚未接普通用户 UI。生产 SQLCipher/Keychain/Keystore 跨设备 key recovery、E2EE/用户自有存储路线、OS 调度、账户恢复、用户可见备份健康/支持状态与物理设备演练仍是独立 Gate。

1. 数据库迁移前做本地加密快照和可用空间检查；快照保留不超过迁移/回滚所需窗口。
2. 迁移脚本单调、可重复检测，记录 checksum/app version；失败回滚到旧库，不在半迁移库继续写。
3. Event Store 迁移与 Recall index 重建分开；索引可删后重建。
4. App 降级若不能理解当前 major，只读导出/升级提示；禁止写旧格式破坏安全对象。
5. Peer 升级使用 tolerant reader → writer 顺序；破坏性变化通过新 major、能力协商和双读窗口，未知 major 隔离但不污染现有空间。

## 9. Android 本地 Event 最小切片达成边界

### 9.1 Coverage 持久化与 Event 接线

2026-07-26 的双端生产切片新增版本化 Coverage 持久化。Android schema v7 把 day snapshot、Candidate 生命周期、Candidate→Event link 和 source-object index 保存在 SQLCipher；iOS local-store envelope v6（Coverage 初始切片为 v2）把 Event、Coverage day、link、长期 Memory、删除水位、无正文复用 telemetry 和本机 source lineage 保存在同一 AES-GCM 密文。保存或重新编译 Coverage 不创建 Event/Revision/DayLedger；只有显式接受仍为 `open` 的 Candidate，才能在一个原子写入中创建 Event、消费 Candidate 并保存 link。删除 Event 只把 link 改为 `detached`，Candidate 保持终态，防止重新编译或重启复活。

Coverage→Event 边界不自动形成长期 Memory。后续 schema v8/envelope v3 切片新增 9 类长期 Memory：proposal 必须绑定 exact active Event revision，evidence/sensitivity 由存储层读取，普通候选与敏感/推断候选都只有显式用户确认后才 active；有效期只控制默认可见性，replacement 使旧项 superseded，Event revision/delete 使关联项 invalidated。Agent Local Node 可写 Event/Revision并有界读取获准的 Event current projection，但不得调用长期 Memory confirmation repository；Agent Revision 会使旧的 Event-bound Memory 失效。

schema v9/envelope v4 在同一 Event 删除事务中增加不含正文的 terminal watermark/tombstone。Android trigger 禁止删除或回退水位；iOS load 验证摘要、唯一键、Event ID 与当前投影互斥。恢复候选必须至少包含调用方给出的权威水位，否则拒绝旧 snapshot，Coverage active link 与 Event-bound Memory 也不能绕过该 Event 水位复活。

schema v10/envelope v5 增加四类本机复用：历史搜索、显式关键词项目续接、用户选择范围的会前上下文和 active 决定/承诺找回。`ReuseContext` 的 exact Event/Memory ID、revision 与 lineage 只保留在内存 15 分钟，正文解析前重新验证 revision、Memory validity、删除和 Restricted 策略；不把旧 context 静默重建为新内容。持久 attempt/outcome 只保存 intent、complete/partial/empty 本机范围、数量桶、按随机 attempt ID 加盐的 SHA-256 Event/Memory/lineage 摘要、策略排除码、结果、用户动作和时间，不保存 query、Prompt、正文、用户原话、locator、路径或 raw ID。该 telemetry 不参与 Recall/Memory 编译，不构成删除后内容的第二索引。当前没有 Project 实体或 Meeting/Contact 推断。Agent Local Node 的独立 `visible_events` 只读获准的结构化 Event current projection，可供 Host `recall/get_context` 使用；它不开放长期 Memory、通用 `get_event`、source lineage 或本机复用 telemetry。

schema v11/envelope v6 引入本机 source identity 与删除执行边界，schema v12/envelope v7 加入 content-free exact-revision 字段证据；当前 schema v13/envelope v8 进一步加入独立的用户确认 provenance。Android 将新捕获和 Coverage source IDs 注册为 `source_objects` 并以 `event_source_links` 关联 Event；iOS 使用同构的 SourceObject/EventSourceLink。完整单来源 cascade 默认终结 Event/Coverage/Memory/source 水位。多来源 Candidate 只有显式字段→来源映射完整覆盖 accepted fields 与 linked sources 才能创建字段证据；删除一个来源后每个字段仍有 active 支持时追加保守重算 revision，字段失去最后来源时删除整个 Event，mapping 缺失或因用户 revision 过期时返回 `lineage_unavailable` 且零结构化 mutation。

`EventUserConfirmation` / `LocalEventUserConfirmation` 只保存确认 ID、Event ID、精确 revision、确认类型、字段名集合、完整性标志、时间与 terminal state，不保存字段值、正文、用户 ID 或来源内容。只有显式接受完整 Candidate 字段集形成的 `completeFieldSet=true` 记录可以在删源后独立支持 Event；此时来源 link/claim 仍被终结，Event 追加新 revision 并标记“用户确认（来源已删除）”，旧确认记录终结后以相同确认 ID 携带到新 revision。事实状态或用户补充在缺少完整字段集合时只产生 `partial` 审计记录，绝不能保留失去唯一来源的 Event。Agent revision/undo 不会伪造用户确认；v12/v7 迁移只建立空确认集合，禁止从旧 fact status、source link 或字段证据猜测用户动作。Android 只有 provider/content locator，没有 app-owned Raw bytes，因此 Raw-only 必须返回 `external_not_owned` 或 `no_raw`。iOS 对 app-owned AES-GCM media 先持久化 `pending_cleanup`，移除 Event locator 并追加 revision，再物理删除 ciphertext、持久化 `deleted`；重启可重试，pending 期间拒绝备份。Photos 等外部原件不删除。

同一 schema/envelope 的通用删除水位还支持当前安装 Personal space 的本机 root freeze。Android 在一个 SQLCipher transaction 内先收敛 Event/Coverage/Memory/source，再最后写 `SPACE/space_personal`；根水位后的普通 Repository 读写 fail closed，但 locator cleanup 可继续。iOS 先构造完整终态 envelope、最后写 SPACE tombstone、提交后删除 app-owned ciphertext 并允许重启重试；根水位后的普通持久化和新备份被冻结。两端恢复都把 live root 作为不可被调用方削弱的权威水位，旧 snapshot 缺少 root 时拒绝候选。为防复活和审计而保留的 tombstone/删除记录/旧 revision 不是物理擦除证明；外部 provider 原件、Grant/Contract、账户服务和 peer 副本没有被这条命令删除。

Android v7–v13 instrumentation 已在 API 36、16 KB arm64 AVD 全量执行，XML 精确结果为 91 discovered / 84 passed / 7 外部门 skipped / 0 failed；恢复激活专项 3/3、完整 UI 套件 12/12。这仍不等于物理设备或 OEM 证明。iOS 已由生产 smoke 走过确认、上游 revision 失效、四类短时复用、无正文 telemetry、app-owned Raw-only、外部原件边界、单来源 cascade/source watermark、多来源 exact-revision 字段证据重算、完整用户确认删源保留、partial 确认 fail-closed、删除水位、认证备份、exact-confirmation 激活、当前安装 Personal space root freeze、旧快照拒绝和重载不复活，但新 UI/激活的 XCTest、真实用户确认触发面、真实 helpful 结果、进程终止与跨设备物理恢复仍待完整 Xcode/真机。account/Grant/Contract delete、peer ack、分布式权威水位与生产删除证明仍是后续 Gate。

2026-07-14 的 Android 切片已经实现并在 API 36 x86_64 AVD 验证：注入式 `DatabaseKeyProvider`、Keystore AES-256-GCM 包裹随机数据库 key、SQLCipher WAL、数据库 trigger 强制 `event_revisions` 禁止 UPDATE/DELETE、`events_current` 投影、Repository 显式绑定 `space_id`、commit 后再展示、tombstone 后重建仍不可见，以及 v1→v2 Revision backfill、v2→v3 `space_legacy` 隔离、v3→v4 `source_locators`、v4→v5 来源实例和 v5→v6 Event policy/DayLedger/Summary/Agent 幂等迁移。跨空间相同 Event ID 可共存且不可互读/互删。错误密钥被拒绝，主库文件头不是明文 SQLite header；SQLCipher/应用日志不输出正文或 key。Compose 的 open/read/write/search/来源访问和授权清理由统一 I/O 边界执行，取消后的 open 不泄漏 repository，重组也不会提前关闭当前实例。

`source_locators` 只在 SQLCipher 内保存 `content://` URI、MIME、来源、访问模式、生命周期和可选来源实例键，不复制照片/PDF/音频原始内容。Event revision、current projection 和 locator 在同一事务写入；用户取消 Photo Picker/系统录音/音频选择、空输入、无读取授权、非 `content://`、多项或非白名单 ACTION_SEND 不产生 Event。语音只接受系统返回的音频引用，不申请麦克风权限，也不生成转写或占位正文。Calendar 只在用户显式选择可见 calendar IDs 和 1/7/31 日窗口后读取 Provider；物理游标行数有硬限，全天日期不受本地时区漂移，同一 active instance 跨批次/重启幂等，且始终写为 `Planned` 而不是已发生事实。

DB-01 在 `c8fc128` 上形成有效 [`10k/100k API 36 AVD 报告`](../../tests/results/performance/android-db01-api36.json)：100k FTS 关键词页 P95 177.72 ms、单次提交 P95 215.67 ms，分别通过 700 ms 和 300 ms 的预注册数据库门；加密数据库 95,227,904 bytes，v3→v5 10k 迁移 P95 892.51 ms。日期筛选 P95 734.56 ms 仍是观察项；所有数字都不是物理设备或 UI 首帧证据。

持久 URI 授权删除使用可恢复两阶段状态：Event tombstone 事务只把同空间 `PersistedRead` locator 改为 `RELEASE_PENDING`，普通 `sourceLocator` 立即不可见；`SessionRead` 可直接进入 `DELETED`。事务外、按 repository space 注入的 cleanup coordinator 在生产 App 打开 repository 后和 UI 删除后运行；OS release 成功或确认授权已不存在后写 `RELEASED`，返回 false、抛异常或无法确认时保持 pending。即使删除由 Agent/API 直接调用、commit 后进程崩溃或首次 release 失败，重启仍可枚举并重试；其他 space 不能读取或完成该任务。数据库事务内禁止调用 `ContentResolver`。

Recall 已增加不透明 keyset cursor 和日期分页。被测 SQLCipher 运行时成功创建 FTS5 虚表；FTS5 与强制 LIKE fallback 已验证共同的“最多 16 个空白分词、每个词都须在 Event 字段中命中”契约，以及一致的参数化查询、`space_id`、日期、ACTIVE state、删除过滤和稳定倒序。该证据不声称 FTS tokenizer 的所有边界与 substring LIKE 完全等价。FTS 表是派生索引，创建或重建失败时数据库仍可打开并使用 LIKE。结论只覆盖 API 36 AVD 的 SQLCipher 4.15.0 运行时，不外推所有设备或 16 KB page size。

Android 清单同时保持 `allowBackup=false`，`data-extraction-rules` 对 cloud backup 和 device transfer 显式排除 root/file/database/sharedpref/external 及四个 device-protected data domain；编译后 XML 资源由设备测试核对。该配置用于避免 SQLCipher DB 和 wrapped-key blob 被系统备份或 D2D 搬迁，仍需后续厂商/真机矩阵验证：<https://developer.android.com/identity/data/autobackup>。

Agent 本地应用层已接入同一个 SQLCipher repository：`ameme.agent-local-node.v1` 的 `create_event` 创建初始 Revision `1`，`append_revision` 在相同 Event 上追加不可变 revision 并更新 current projection，exact `undo_capture` 只撤销原 capture token 对应的 Event 或 Revision。Event 撤销追加 tombstone；Revision 撤销要求目标仍为 current head，并追加恢复前一 immutable revision 内容的补偿 Revision，不覆盖或删除历史。Revision/撤销事务同时终结旧字段证据、使 Event-bound 长期 Memory 失效、刷新搜索和 DayLedger；不存在、已删除、过期、类型不匹配或 sensitivity 不可见目标统一为 `NOT_VISIBLE`，后续 head 冲突为 `REVISION_CONFLICT`。原写入和撤销都把幂等槽与 mutation 放在同一 SQLCipher transaction，撤销首次调用限原写入后 10 分钟；已成功撤销的相同请求在关闭重建与时窗后仍重放已持久终态。幂等表不保存正文或恢复 snapshot。

`visible_events` 在同一库的 active current projection 上执行单 Personal space、Event-only、structured-only、最多 16 个 AND query term、精确时区时间边界、session sensitivity 交集和 1–100 条上限；删除/space root freeze 后不可见。返回映射把 title/description 限为 240/1,000 code points，并显式给出 truncation，不返回 user words、source label/ID、locator、路径或 Raw。`allow_high_risk` 不能扩大 session sensitivity；生产 runtime 根本不授予 Restricted。配对 TLS 1.3/certificate pin/HMAC/session/sequence 通道验证后，Android 按持久 local policy 只宣告获准的 operation；缺少 `grant_operations` 的旧 pairing fail closed 并要求重新授权，不能因升级静默获得读取、写入或撤销能力。本轮 API 36 / 16 KB AVD 已由 Swift 客户端实际执行四操作纵向链；共享账户 Grant registry、真实 Host/ContextPack、物理 LAN/NSD、后台、跨端撤销传播与物理设备仍不在通过证据内。

iOS `AgentLocalNodeNetworkClient` 现在保存 TLS/HMAC hello 中经过认证的 operation 集，并只在调用方
提供包含目标 space/type 的显式 Grant 且 operation 已协商时构造
`create_event`/`append_revision`/`undo_capture`/`visible_events`。成功响应必须是 canonical 六字段
envelope，request ID、`result_digest` 和 operation-specific exact result shape 全部匹配；畸形结果
关闭 session，合法远端 error 只暴露冻结 code/retryability。普通用户 QR 默认 policy 继续只有
event，连接展示仍只声称结构化 Event 写入；读取、Revision 与撤销需要后续独立用户确认，不能由
新增 API 静默扩权。原始 application exchange 已收为私有，生产 exchange 只能用当前时间做 Grant
授权。本轮 Swift→Android Smoke 在 API 36 / 16 KB arm64 AVD 实际执行
create→bounded read→Revision→Revision exact undo→Event exact undo→final read，并由 Today
可见性复核保留的原始 Event。

该证据仅关闭“最小本地 Event 闭环可运行”的实现问题，没有关闭完整协议或 DB-01：

- 重建测试是关闭数据库并重新构造 repository，不等于操作系统杀进程/崩溃恢复；
- 未验证真机、iOS、16 KB page size、真实 Today 首帧/搜索端到端、后台锁、电量或峰值内存；10k/100k 仅形成单台 API 36 x86_64 AVD 数据库基线；
- 当前删除是追加 tombstone/watermark 并更新本机投影；同安装恢复候选会拒绝旧水位，但尚未实现物理清除、完整影响图、peer ack、分布式权威水位和删除证明；
- SourceLocator 目前覆盖 Photo Picker/ACTION_SEND/Calendar/Voice 元数据、实例幂等和授权生命周期，未覆盖可用性复核、fingerprint、Raw Vault 或跨设备行为；
- Raw Vault、durable job/outbox、完整 lineage/审计和 LAN sync 尚未进入该切片；
- Agent 四项生产 operation 已完成配对 Swift→TLS/HMAC→Android→SQLCipher→Today 的 API 36 / 16 KB AVD 纵向闭环；该执行使用 ADB forward 与显式 expanded Grant，不等于普通用户已授权读取/撤销，也不替代共享账户 Grant、NSD/物理 LAN、后台、跨端撤销传播、真实第三方宿主与真实 ContextPack；
- v1→v2→v3→v4→v5→v6 已验证成功迁移与 legacy space 回填；v6→v7 Coverage、v7→v8 长期 Memory、v8→v9 恢复安全、v9→v10 无正文复用 telemetry、v10→v11 来源删除、v11→v12 字段证据和 v12→v13 用户确认 provenance 已在 API 36 / 16 KB AVD 的当前全量 SQLCipher instrumentation 中执行。同安装加密快照和 HMAC journal live-store 激活/失败回滚内核已有 fail-closed Oracle，并由 Android AVD 恢复类 3/3 执行；物理设备、跨设备 key recovery、真实进程 kill、断电/空间不足与 OEM 文件系统故障注入仍属于 MIG-01。

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
