# Ameme SYNC-01 确定性协议模拟器

本包用于在没有真实网络、账户服务和生产密码学的条件下，验证 2–3 个 peer 对不可变同步信封的确定性处理语义。它只回答“给定相同有效 envelope 集合，节点是否得到相同投影，以及缺口是否被如实表达”，不证明 Bonjour/NSD、Socket、LAN 权限、加密会话或真机后台能力。

## 语言与生产实现边界

本目录中的 Python 是 **test-only executable specification / deterministic simulator**：只在开发、回归和跨语言一致性测试中执行，不进入 iOS/Android 生产包，不承担真实网络、发现、加密、设备身份、后台调度、持久化、密钥或恢复运行时。

生产 LAN Peer Sync 的实现边界固定为：

- iOS：Swift + Network.framework，平台发现、连接、权限、后台和密钥行为由原生实现负责。
- Android：Kotlin + NSD/平台网络 API，平台发现、连接、权限、后台和密钥行为由原生实现负责。
- 两端共同消费 `packages/contracts/` 与 `tests/fixtures/sync/canonical-conformance-vectors.json`；未来 Swift/Kotlin conformance tests 必须复用同一批输入 envelope、peer 初态和 golden expectation，不能各自复制一套语义夹具。

当前不选择 TypeScript/JavaScript、Rust 或 Kotlin Multiplatform（KMP）作为共享生产同步运行时：

- TypeScript/JavaScript：MVP 没有共享 JS 移动运行时基线；引入引擎仍不能替代 Network.framework/NSD、系统权限、后台和密钥的原生集成，反而增加包体与生命周期边界。
- Rust：当前先验证协议正确性，尚无证据表明投影算法是性能/电量瓶颈；FFI、异步取消、崩溃诊断、包体和双端构建成本还未验证。
- KMP：当前架构只共享 contracts/vectors、不共享 P0–P2 运行时 Core；LAN 发现、后台与安全存储仍是平台专属，过早引入共享运行时会改变已接受的原生边界。

仅在出现可复现证据时重新评估共享生产运行时：Swift/Kotlin 算法重复导致持续缺陷；真机 profiling 证明性能或电量瓶颈位于共享算法；团队维护数据证明双实现不可持续；并且 Rust/KMP/其他 FFI 的构建、包体、崩溃、异步、调试和升级成本已被实测。重新评估本身不等于选型变更，仍需架构/安全 Review。

## 已覆盖

- 深度不可变的 `SyncEnvelope`、每设备 `device_sequence`、接收 cursor 和幂等键。
- duplicate、out-of-order、sequence gap、offline replay、unsupported/old schema quarantine。
- 基于 `base_revision` 的并发 Revision：不同字段确定性合并，同字段保留 conflict，不使用墙上时钟或 last-write-wins。
- tombstone 和 grant/device revoke 的控制面优先级；tombstone 到达后旧更新不能复活对象。
- 设备撤销、授权过期消息、删除传播缺口、sequence/idempotency 重放攻击的负向结果。
- `convergence`、`partial`、`conflict`、`proof_incomplete` 四类稳定结果，以及不含正文的可序列化 evidence。

## 运行

从仓库根目录执行：

```powershell
python -m unittest discover -s tests/sync -p "test_*.py" -v
python scripts/dev/sync/run_sync01_demo.py --scenario all
python scripts/dev/sync/generate_conformance_vectors.py --check
```

demo 的 JSON 输出排序稳定；`--output <path>` 可保存一次本地证据。demo、基础夹具和 canonical vectors 只包含合成标识与合成字段值；`tests/fixtures/sync/sync01-fixture.json` 记录基础范围，跨语言输入以 canonical vector 文件为准。

当前 canonical 文件含 12 条向量。维护者在协议语义获批变更后运行 `python scripts/dev/sync/generate_conformance_vectors.py --write` 生成 golden 文件；日常测试和 CI 只运行 `--check`，防止实现漂移时静默改写期望。每条向量包含完整输入 envelope、peer 初态、投递步骤、完整期望投影及其 canonical SHA-256、result class、关键 evidence code、partial reason 和 deletion proof。

## 契约边界与已知缺口

实现保持 `packages/contracts/**` 只读，并沿用现有 `SyncEnvelope` operation 枚举。当前机器契约没有单独的 device-revocation envelope，也没有把 peer session 的授权到期写入 envelope：

- 设备撤销在模拟器中使用 `grant_revocation + object_type=device_grant`，目标放在 payload；生产实现前应决定是否将其正式固化为契约字段。
- 授权到期属于接收 peer 的确定性会话状态，测试通过 `set_authorization_expiry` 注入；这不是新增线上字段。
- 模拟器把 priority control envelope 在 sequence gap 存在时“先应用控制、暂不推进 cursor”，用于证明删除/撤权不会因普通消息缺口而失效；真实传输需要确认控制水位、签名覆盖和 durable ack 的正式编码。

协议细节与状态机见 [PROTOCOL.md](PROTOCOL.md)。
