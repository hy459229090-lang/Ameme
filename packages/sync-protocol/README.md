# Ameme SYNC-01 确定性协议模拟器

本包用于在没有真实网络、账户服务和生产密码学的条件下，验证 2–3 个 peer 对不可变同步信封的确定性处理语义。它只回答“给定相同有效 envelope 集合，节点是否得到相同投影，以及缺口是否被如实表达”，不证明 Bonjour/NSD、Socket、LAN 权限、加密会话或真机后台能力。

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
```

demo 的 JSON 输出排序稳定；`--output <path>` 可保存一次本地证据。输入只使用 `tests/fixtures/sync/sync01-fixture.json` 中的合成标识与合成字段值。

## 契约边界与已知缺口

实现保持 `packages/contracts/**` 只读，并沿用现有 `SyncEnvelope` operation 枚举。当前机器契约没有单独的 device-revocation envelope，也没有把 peer session 的授权到期写入 envelope：

- 设备撤销在模拟器中使用 `grant_revocation + object_type=device_grant`，目标放在 payload；生产实现前应决定是否将其正式固化为契约字段。
- 授权到期属于接收 peer 的确定性会话状态，测试通过 `set_authorization_expiry` 注入；这不是新增线上字段。
- 模拟器把 priority control envelope 在 sequence gap 存在时“先应用控制、暂不推进 cursor”，用于证明删除/撤权不会因普通消息缺口而失效；真实传输需要确认控制水位、签名覆盖和 durable ack 的正式编码。

协议细节与状态机见 [PROTOCOL.md](PROTOCOL.md)。
