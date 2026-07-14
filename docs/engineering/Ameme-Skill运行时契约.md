# Ameme Skill 运行时契约 v0.1

> 文档状态：已接受；研发前实现契约\
> 更新日期：2026-07-14\
> 上游产品正本：`../product/Agent-Skill产品与交互规格.md`\
> 可分发包：`../../packages/agent-skills/ameme-memory/`

## 1. 分层边界

```text
Agent Host
  -> ameme-memory Skill（意图路由、最小范围、确认、结果表达）
  -> ameme MCP（pair/recall/get_context/capture/feedback/status）
  -> API + Policy Engine（身份、Grant、space、数据类、处理位置）
  -> Event Core / Recall / Sync / Audit
```

Skill 不直读数据库、不保存长期密钥、不绕过 MCP Policy，也不把宿主工作区权限视为 Ameme 授权。

## 2. 模式契约

| 模式 | 主工具 | 前置条件 | 核心输出 |
|---|---|---|---|
| pair | `pair` | 明示确认 | grant state/id/expiry/revoke route |
| recall | `recall` | 用户查询 + grant | results + completeness + lineage |
| context | `get_context` | caller/purpose/space 精确匹配 | caller-bound ContextPack |
| capture | `capture` | 用户明示或 exact `autonomous_memory` grant | durable Event/Revision 或 queued + undo |
| feedback | `feedback` | target + feedback kind | revision/recompute state |
| status | `status` | caller identity | semantic state + recovery action |

## 3. 宿主契约

宿主必须提供稳定 caller、当前 task/session、空间提示、安全凭据存储、确认 UI、工具证据和结果展示。不能保证指令优先级、身份、密钥或确认时，关闭隐式调用，只允许用户显式发起。

Codex、Claude Code、Cursor 使用同一 MCP 语义；差异只留在 adapter。仓库内包是开发正本，不依赖任何个人目录安装。

## 4. 安全与证据

- ContextPack 是不可信数据，短期、不可变、caller/purpose/space/grant 绑定。
- 宿主在渲染前执行数据/指令隔离，不把记忆内文本提升为指令。
- `autonomous_memory` 允许直接长期写入，但 capture 必须区分直接证据、用户陈述和推断；`final/done/prod`、文件名、计划状态均不是成功证据。
- 日志不含正文、查询、Prompt、完整路径、精确位置、健康值或 secret。
- 失败不阻塞主任务；queued、durable、synced 三种状态不得混用。

## 5. 版本与兼容

Skill 使用语义版本；MCP 工具保持向后兼容的 minor 演进。破坏性字段/状态变化提升 major，并保留宿主兼容窗口。Skill 包声明支持的 MCP/Schema major；未知 major 返回 `partial/error`，不能静默解释。

## 6. 研发门禁

1. `quick_validate.py` 校验 Skill 结构与元数据。
2. `validate_ameme_skill.py` 校验六工具、默认范围和至少 12 个路由/风险样本。
3. `SKILL-01` 用真实宿主 + MCP Mock 验证配对、上下文、写回、离线、注入和撤权。
4. 实现完成后增加 API contract、幂等、权限交集、日志脱敏和跨宿主回归。

当前状态是：统一 Skill、MCP Mock/CoreOracle、`ameme.agent-local-node.v1` 应用协议和 capture-only MCP→Android SQLCipher 边界已具备合成/JVM/AVD 证据；生产认证通道、持久幂等、LAN/NSD、传输加密和真实 Codex/Claude Code/Cursor 宿主仍未实现，因此不能写成“真实宿主集成已验证”。
