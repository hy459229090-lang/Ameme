# SYNC-01 模拟协议与状态机

> 结论口径：本文描述的是确定性内存模拟协议，不是 LAN-SYNC 或安全验收报告。

## 1. 输入与真相

每个 envelope 包含契约已有字段：`schema_version`、`space_id`、`device_id`、`device_sequence`、`operation`、`object_type/object_id`、`base_revision`、`payload`、`idempotency_key` 和 `created_at`。构造后 dataclass 与 payload 都不可变，证据 hash 取稳定 canonical JSON 的 SHA-256。

投影不使用 `created_at` 选胜者。因果只使用：

1. origin device 的单调 sequence；
2. 每个 peer 对 origin 的完成 cursor；
3. Event Revision 的 `base_revision`；
4. tombstone/revocation 控制面优先级。

## 2. 接收状态机

```text
receive
  -> duplicate same sequence+digest       -> return prior-safe duplicate ack
  -> same sequence, different digest      -> reject REPLAY_SEQUENCE_COLLISION
  -> unsupported schema                   -> quarantine; cursor does not advance
  -> sequence > expected, ordinary        -> pending_gap; cursor does not advance
  -> sequence > expected, control         -> preapply control; pending cursor gap
  -> sequence == expected                 -> validate auth/idempotency; apply/reject
                                            -> advance cursor; drain contiguous pending
```

控制面是 `grant_revocation`、`tombstone`、`deletion_ack`。控制面预应用不等于完成 cursor：证据同时保留“安全控制已生效”和“普通序列仍有缺口”。

不支持的 schema major 隔离并阻止该 origin 完成 cursor，结果只能是 `partial`。接收端不猜字段、不丢 envelope 后假装完整。

## 3. Revision 投影

同一 Event 的 envelope 按 `base_revision` 分代。每一代内：

- 不同字段变化可交换合并；
- 同字段相同值折叠为同一结果；
- 同字段不同值保存排序稳定的 alternatives，Event 进入 `conflict`；
- tombstone 存在时 Event 必为 `deleted`，历史 Revision 只能作为不可读的最小模拟历史，不得复活投影；
- base 祖先缺失时保留 `revision_gap`，结果为 `partial`。

该算法刻意不实现“最新时间获胜”。

## 4. 删除与证明

tombstone payload 的 `required_peer_ids` 是本次合成测试的证明范围。模拟器记录每个 peer 实际应用 tombstone 的 content-free receipt：

- 全部 required peer 已确认：proof `complete`；
- 任一 peer 离线、遗失或未应用：结果 `proof_incomplete`，列出 `pending_peer_ids`；
- proof 不包含被删字段值。

这只证明模拟收件与投影语义，不证明物理存储、索引、备份、Raw Vault 已清除。

## 5. 结果判定

结果优先级固定，避免把风险降级成普通成功：

```text
missing deletion proof -> proof_incomplete
preserved field conflict -> conflict
digest mismatch / gap / quarantine / security rejection -> partial
otherwise equal state digests -> convergence
```

`conflict` 可以同时 `converged=true`：含义是所有 peer 对“存在未决冲突”达成一致，而不是冲突已解决。

## 6. 非目标

本模拟器不实现：Bonjour/NSD、真实 Socket、热点/AP isolation、账户登录、设备密钥、签名、TLS、加密 Raw、Keychain/Keystore、真机后台、吞吐/电量。上述内容仍分别受 LAN-SYNC-01、SEC-01、DEL-01 和真机 Gate 约束。
