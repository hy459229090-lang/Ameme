# engineering 工程文档索引

## 当前状态

MVP 工程实现进行中。已集成契约质量基线、非生产 Core/Raw/队列 Oracle、同步协议模拟器、Agent MCP Mock、R0 AI 参考实现与 Android SQLCipher 加密 Local Event Node；Android 后续切片在 API 36 AVD 通过 25 项设备测试，覆盖 v4 SourceLocator、持久 URI 两阶段清理、Photo Picker/ACTION_SEND 边界、Calendar planned 适配器、FTS5/LIKE 多词共同语义和 keyset 日期分页。生产 Raw/LAN、Calendar Provider/语音、真实模型/宿主、双端真机、16 KB 和性能容量仍待实现或验证。

## 当前正本

| 主题 | 路径 | 状态 |
|---|---|---|
| 接口、错误与 Agent 工具 | `MVP接口与错误契约.md` | v0.1，OpenAPI/MCP/幂等/分页/错误候选 |
| 机器契约包 | `../../packages/contracts/` | v0.1，Schema/OpenAPI/合成夹具，离线校验通过 |
| 研发任务书与里程碑 | `MVP研发任务书与里程碑.md` | v0.3，Epic/依赖/DoD/周期场景/M1–M6 研发授权 |
| Agent Skill 运行时契约 | `Ameme-Skill运行时契约.md` | v0.1，六模式、宿主/MCP/安全/兼容门已接受 |
| 技术 Spike 任务书 | `MVP技术Spike任务书.md` | v0.3，15 项 ready_to_execute / 等待环境 |

## Gate 3/4 实现证据

- API/Schema、错误/兼容、迁移协议和研发里程碑正本已形成。
- 仓库构建、生成代码、数据库迁移运行、Connector SDK 和端到端证据只能在研发切片中产生，按任务书与 Spike 进入 Gate 3/4。

## 变更日志

| 日期 | 操作 | 说明 |
|---|---|---|
| 2026-07-13 | 建立 | 创建工程正式文档域 |
| 2026-07-13 | 路由 | 增加 MVP 研发架构候选与 P0–P7 工程切片入口 |
| 2026-07-13 | 新增 | 建立 MVP 接口/错误正本和可复跑机器契约包 |
| 2026-07-13 | 新增 | 建立可估算工程任务、依赖里程碑和所有未验证技术 Spike 任务书 |
| 2026-07-14 | 决策 | 接受 LAN/账户/SourceLocator/Agent 自动记忆技术基线与 M1–M6 全面研发；新增 Skill 运行契约、SKILL-01 和仓库内分发包 |
| 2026-07-14 | 开工 | 集成 C-001、M2 Core Oracle、SYNC-01、Agent Mock、AND-001 与 Review 修复；启动 Android Local Node、Raw Vault/队列和 R0 AI 批次 |
| 2026-07-14 | Android 后续 | AND-003/004 与 DB-01 子切片形成 API 36 AVD 证据；真实设备、容量和性能仍未关闭 |
