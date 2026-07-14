# packages

跨端共享包目录，例如领域对象、Schema、存储、加密、Connector SDK、ContextPack 和 UI 组件。

共享定义应保持单一正本，并有兼容与版本策略。

## 当前共享包

| 包 | 用途 | 状态 |
|---|---|---|
| `contracts/` | MVP JSON Schema、OpenAPI、合成夹具与兼容规则 | v0.1 冻结基线；breaking diff/运行时/安全测试已接入 CI |
| `core-reference/` | SQLite 本地事件链、Revision、Recall、Raw Vault、耐久队列、删除/重建语义 Oracle | 非生产参考实现；两阶段删除、AAD v2 和合成故障恢复已通过 |
| `sync-protocol/` | LAN 同步 envelope/sequence/conflict/tombstone 的确定性模拟与冻结向量 | SYNC-01 参考实现；未接真实网络 |
| `ai-processing-reference/` | R0 时间/计划/去重、Provider/Policy 门与 EventCandidate 结构化验证 | 非生产、无网络参考实现；未接真实模型或完整 Grant |
| `agent-skills/ameme-memory/` | 面向 Codex/Claude Code/Cursor 的统一记忆 Skill、宿主策略和工具契约 | v0.1 运行时正本；结构/路由校验已接入 CI |
