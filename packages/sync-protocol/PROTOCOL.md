# SYNC-01 模拟协议与状态机

> 结论口径：本文描述的是确定性内存模拟协议，不是 LAN-SYNC 或安全验收报告。

## 1. 语言与生产实现边界

Python 实现是 test-only executable specification / deterministic simulator。它的职责是把已接受的 contracts 与同步不变量变成可复跑状态机和 golden vectors；它不打包进移动端，不实现生产网络、密码学、持久化或后台运行时。

生产实现分别为 iOS Swift/Network.framework 与 Android Kotlin/NSD。两端必须读取同一 `packages/contracts/` 和 `tests/fixtures/sync/canonical-conformance-vectors.json`，用各自原生实现重放步骤并比较完整投影、canonical digest、result class、evidence code 和 reason。生产 App 不启动 Python，也不把 Python 模拟器作为 fallback。

当前不采用共享 TypeScript/JavaScript、Rust 或 KMP runtime，因为尚无跨端重复缺陷、算法性能/电量瓶颈或维护成本证据足以抵消 JS 引擎/生命周期、Rust FFI/异步/构建、KMP 平台桥接/架构迁移成本。重新评估需要同时具备：重复缺陷统计、真机 profiling、团队维护证据，以及候选 FFI/包体/崩溃/调试/升级成本实测；结论仍需新的架构和安全 Review。

## 2. Canonical conformance vectors

canonical 文件当前包含 12 条合成向量。每条向量固定：

1. 完整 contract-shaped input envelopes；
2. 2–3 个 peer 的空投影、支持 schema 和授权到期初态；
3. 确定性 deliver/offline/replay 步骤；
4. 完整期望 projection 与 `sha256(ameme-canonical-json-v1)` digest；
5. `convergence / partial / conflict / proof_incomplete` result class；
6. 关键 evidence code、partial reason、event state 和 deletion proof。

生成与校验：

```powershell
python scripts/dev/sync/generate_conformance_vectors.py --write
python scripts/dev/sync/generate_conformance_vectors.py --check
```

`--write` 只用于获批的协议语义变更；正常回归使用 `--check`。这些向量证明的是模型一致性，不是真机或生产安全证据。

`ameme-canonical-json-v1` 固定为：UTF-8 无 BOM、对象 key 按 Unicode code point 字典序递归排序、数组保持声明顺序、无额外空白、非 ASCII 字符按 UTF-8 JSON string 输出、v1 projection vector 只使用十进制整数而不使用浮点数，最后输出小写十六进制 SHA-256。完整待摘要 projection 同时保存在每条向量的 `expected.peer_projections`，Swift/Kotlin 不能只复用预计算 digest 而跳过投影比较。

## 3. 输入与真相

每个 envelope 包含契约已有字段：`schema_version`、`space_id`、`device_id`、`device_sequence`、`operation`、`object_type/object_id`、`base_revision`、`payload`、`idempotency_key` 和 `created_at`。构造后 dataclass 与 payload 都不可变，证据 hash 取稳定 canonical JSON 的 SHA-256。

投影不使用 `created_at` 选胜者。因果只使用：

1. origin device 的单调 sequence；
2. 每个 peer 对 origin 的完成 cursor；
3. Event Revision 的 `base_revision`；
4. tombstone/revocation 控制面优先级。

## 4. 接收状态机

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

## 5. Revision 投影

同一 Event 的 envelope 按 `base_revision` 分代。每一代内：

- 不同字段变化可交换合并；
- 同字段相同值折叠为同一结果；
- 同字段不同值保存排序稳定的 alternatives，Event 进入 `conflict`；
- tombstone 存在时 Event 必为 `deleted`，历史 Revision 只能作为不可读的最小模拟历史，不得复活投影；
- base 祖先缺失时保留 `revision_gap`，结果为 `partial`。

该算法刻意不实现“最新时间获胜”。

## 6. 删除与证明

tombstone payload 的 `required_peer_ids` 是本次合成测试的证明范围。模拟器记录每个 peer 实际应用 tombstone 的 content-free receipt：

- 全部 required peer 已确认：proof `complete`；
- 任一 peer 离线、遗失或未应用：结果 `proof_incomplete`，列出 `pending_peer_ids`；
- proof 不包含被删字段值。

这只证明模拟收件与投影语义，不证明物理存储、索引、备份、Raw Vault 已清除。

## 7. 结果判定

结果优先级固定，避免把风险降级成普通成功：

```text
missing deletion proof -> proof_incomplete
preserved field conflict -> conflict
digest mismatch / gap / quarantine / security rejection -> partial
otherwise equal state digests -> convergence
```

`conflict` 可以同时 `converged=true`：含义是所有 peer 对“存在未决冲突”达成一致，而不是冲突已解决。

## 8. 非目标

本模拟器不实现：Bonjour/NSD、真实 Socket、热点/AP isolation、账户登录、设备密钥、签名、TLS、加密 Raw、Keychain/Keystore、真机后台、吞吐/电量。上述内容仍分别受 LAN-SYNC-01、SEC-01、DEL-01 和真机 Gate 约束。
