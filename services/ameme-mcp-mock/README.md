# Ameme MCP local mock

本目录提供 Agent 入口第一版的无云 MCP 服务。它只使用本地 JSON 状态和合成夹具，不包含登录、生产鉴权、记忆云、外部模型或第三方 Agent 市场能力。

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
python scripts/validation/validate_ameme_skill.py
```

本地状态默认写入 `.tmp/ameme-mcp-mock/state.json`，该目录已被 `.gitignore` 排除。测试使用临时目录并只加载 `tests/fixtures/agent/` 中的合成数据。

## 已知契约缺口

`packages/contracts/` 当前已有 AccessGrant、Event、Revision、ContextPack 等领域 Schema 和 HTTP OpenAPI，但没有六个 MCP tool 的机器可读输入/输出 Schema。由于本任务冻结 `packages/contracts/**`，本服务暂时在 `server.py` 的 `TOOLS` manifest 内维护实现级 JSON Schema。建议契约任务把这六个 tool schema 纳入共享契约并增加 breaking-change diff；在此之前，Mock manifest 不是新的跨端正本。
