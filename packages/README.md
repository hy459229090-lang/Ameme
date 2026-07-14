# packages

跨端共享包目录，例如领域对象、Schema、存储、加密、Connector SDK、ContextPack 和 UI 组件。

共享定义应保持单一正本，并有兼容与版本策略。

## 当前共享包

| 包 | 用途 | 状态 |
|---|---|---|
| `contracts/` | MVP JSON Schema、OpenAPI、合成夹具与兼容规则 | v0.1 研发前候选；离线校验通过 |
| `agent-skills/ameme-memory/` | 面向 Codex/Claude Code/Cursor 的统一记忆 Skill、宿主策略和工具契约 | v0.1 研发前正本；结构/路由校验已接入 CI |
