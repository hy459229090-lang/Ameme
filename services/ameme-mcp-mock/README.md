# Ameme MCP local mock

本目录提供 Agent 入口第一版的无云 MCP 服务。默认运行方式使用本地 JSON 合成夹具，不包含登录、生产鉴权、记忆云、外部模型或第三方 Agent 市场能力。

## 存储边界

- `EventNodeStore` 是 Mock 业务层与 Event/Revision 持久化之间的明确接口。`AmemeMock` 仍负责 pairing、Grant、exact scope、撤权/过期、风险策略、反馈、ContextPack、活动和 MCP 幂等；Store 只在授权成功后接收请求。
- 每次 Store 调用携带已授权请求范围形成的 `EventNodeScope`，适配器再次核对 owner、space 和 memory type。Scope 不写入 Event/Revision 正文，也不能代替 Mock 的授权判断。
- `JsonStore` 仅是保留现有行为的 **非生产合成夹具后端**，不是移动端数据库、服务端数据库或发布候选实现。
- `CoreEventNodeStore` 仅用于合成集成测试。它只调用 `packages/core-reference` 的公开命令，把 capture/revision/undo 映射为 Source → Observation → Event/Revision → Tombstone/Lineage，并让离线交付进入 Core durable sync queue；它不直写 SQLite。
- Core revision 的 control undo 记录只保存 event/space/revision/source lineage 等最小标识，不复制旧 title/description；只有 `JsonStore` 合成夹具为了兼容原有内存补偿行为保留旧快照。
- Python `CoreOracle` 仍是非生产语义参考和测试 Oracle，不能作为 Android、iOS、桌面或云端生产 runtime，也不证明 SQLCipher、Keychain/Keystore、真实同步或宿主集成完成。

Core SQLite 与 Mock control JSON 不是同一事务。测试适配器不伪造跨库原子性，而是在 control 中保存不含正文的操作日志：`prepared/retry_pending/failed → core_committed`。日志只含语义哈希、对象 ID、revision/source lineage、尝试次数和对账状态。重启后使用同一 idempotency key 重放 Core 公共命令；Core 成功前不会写最终 MCP 幂等成功记录。永久领域错误标记 `failed`，可重试的 SQLite 运行错误标记 `retry_pending`，且每个 key 独立，不阻塞同一 scope 的其他操作。

调用方传入的 idempotency key 也视为潜在敏感输入：进入 control key、Core 命令键、操作日志或 SourceLocator 前，统一转换为带用途域隔离的 SHA-256 slot，不持久化原值。该摘要只用于稳定重放和冲突检测，**不是身份、授权、签名或防伪凭据**；所有访问仍必须先通过 Grant 和 exact-scope 校验。

## 能力和安全边界

- stdio MCP：`pair`、`capture`、`recall`、`get_context`、`feedback`、`status`。
- Grant 每次按 caller、purpose、space、memory type、有效期取交集；不支持通配 Grant。
- 隐式读取、自动 capture 和直接 Event/Revision 仅接受与请求精确匹配的单用途 `autonomous_memory` Grant。
- 用户陈述写为 `user_asserted`，模型推断写为 `inferred`，直接工具证据写为 `observed`；文件名或 `done/final` 文本不会自动升级证据。
- Raw、Restricted、跨空间、范围扩张和删除不会静默执行。自动模式直接阻断 Raw/Restricted；显式模式要求独立高风险确认。删除只返回专用删除流程错误。
- capture 本地原子持久化，明确区分 `persistence_state=durable`、`delivery_state=queued|local_only` 和 `synced=false`；提供活动记录与 10 分钟 undo。
- ContextPack 15 分钟过期并绑定 caller/purpose/space/grant；正文标记为不可信数据，疑似提示词注入从上下文中过滤。
- revoke 立即阻断新访问并使已有 ContextPack 失效；重放配对 challenge 被拒绝，写入通过 idempotency key 去重。
- 活动记录只保存调用方、用途、空间、类型、结果码、数量桶和时间，不保存正文、查询或 Prompt。

## 启动

从仓库根目录执行：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/dev/agent/start_mcp_mock.ps1
```

也可以直接启动并指定忽略目录及合成夹具：

```powershell
python services/ameme-mcp-mock/server.py `
  --data-dir .tmp/ameme-mcp-mock `
  --seed-file tests/fixtures/agent/synthetic-memories.json
```

离线队列语义可使用 `--offline` 或 `AMEME_MCP_MOCK_OFFLINE=true`。服务按一行一个 JSON-RPC 消息读写 stdio，stdout 不输出诊断文本。

MCP 宿主的最小配置语义如下；具体配置键由宿主 adapter 决定：

```json
{
  "command": "python",
  "args": [
    "services/ameme-mcp-mock/server.py",
    "--data-dir",
    ".tmp/ameme-mcp-mock",
    "--seed-file",
    "tests/fixtures/agent/synthetic-memories.json"
  ]
}
```

## 调用与验证

完整 stdio 调用示例由 smoke 脚本实际执行：

```powershell
python scripts/dev/agent/smoke_mcp_mock.py
python -m unittest discover -s tests/agent -p "test_*.py" -v
python -m unittest discover -s tests/agent -p "test_core_store_integration.py" -v
python scripts/validation/validate_ameme_skill.py
```

本地默认 Mock 状态写入 `.tmp/ameme-mcp-mock/state.json`，该目录已被 `.gitignore` 排除。CoreStore 集成测试在临时目录中分别创建 control JSON 和 Core SQLite，只通过测试生成合成数据，不读取或提交真实个人数据。

## 已知契约缺口

`packages/contracts/` 当前已有 AccessGrant、Event、Revision、ContextPack 等领域 Schema 和 HTTP OpenAPI，但没有六个 MCP tool 的机器可读输入/输出 Schema。由于本任务冻结 `packages/contracts/**`，本服务暂时在 `server.py` 的 `TOOLS` manifest 内维护实现级 JSON Schema。建议契约任务把这六个 tool schema 纳入共享契约并增加 breaking-change diff；在此之前，Mock manifest 不是新的跨端正本。
