# services

MVP 服务与本地服务边界目录。当前产品基线是设备本地 Event Core + 已批准设备之间的 LAN 点对点同步；MVP 不建设用户记忆数据云。身份服务未来只处理账户归属、设备会话和目录，不保存或解密记忆内容。

当前实现：

- `ameme-mcp-mock/`：Agent MCP/Skill 的本地、无云、合成数据 Mock；用于配对、授权交集、自动记录/召回、反馈、活动记录、撤销、撤权、过期和安全负向验证。目录内还提供显式选择的 CoreOracle 参考宿主子进程，用于验证进程/IPC 纵向闭环；它仍是 Python executable spec，不是生产 Native Core，也不连接 Android SQLCipher Local Node。

任何未来服务仍必须写清职责、API、数据范围、鉴权、可观测性、成本、部署、迁移和回滚。创建云资源、接入真实数据、加入外部模型/SDK 或发布生产配置均不属于当前 Mock 范围，并需要对应隐私、安全和发布确认。
