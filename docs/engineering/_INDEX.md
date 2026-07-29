# engineering 工程文档索引

## 当前状态

MVP 工程实现进行中。已集成契约质量基线、非生产 Core/Raw/队列 Oracle、独立 CoreOracle 参考宿主进程、同步模拟器、R0 AI/12-case 固定评测与 Android SQLCipher Local Event Node。`ameme.agent-local-node.v1` 的 bounded `visible_events` + `create_event`/`append_revision`/exact `undo_capture` MCP 适配、Android 配对 TLS/HMAC listener、SQLCipher 持久边界和 Today 展示已形成仓库闭环；历史 API 36 AVD 纵向证据只覆盖 `create_event`，读取/Revision/undo 仍需设备与真实宿主执行。生产 Raw、NSD/物理 LAN、后台 Agent、共享账户 Grant、真实 ContextPack/宿主和物理设备仍待实现或验证。

## 当前正本

| 主题 | 路径 | 状态 |
|---|---|---|
| 接口、错误与 Agent 工具 | `MVP接口与错误契约.md` | v0.5，OpenAPI/MCP/幂等/分页/错误与 Android bounded read/write/undo 边界 |
| 机器契约包 | `../../packages/contracts/` | v0.1，Schema/OpenAPI/合成夹具，离线校验通过 |
| 研发任务书与里程碑 | `MVP研发任务书与里程碑.md` | v0.3，Epic/依赖/DoD/周期场景/M1–M6 研发授权 |
| Agent Skill 运行时契约 | `Ameme-Skill运行时契约.md` | v0.4，六模式、宿主/MCP/安全/兼容与 Android bounded Event read/write/undo 门已接受 |
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
| 2026-07-14 | Android 基线 | 完成异步 I/O、Calendar/Voice、v5 幂等、批量快路径和 10k/100k AVD 报告；物理设备/16 KB/UI 性能仍保留 |
| 2026-07-14 | Agent→Android 应用层 | 冻结 Local Node v1 canonical JSON 协议，接通 capture-only MCP 适配边界与 Android SQLCipher `create_event`；生产通道、持久幂等和真实宿主仍保留 |
| 2026-07-14 | Android MVP 闭环 | 接入配对 TLS/HMAC listener、Keystore 凭据、SQLCipher v6 持久幂等、结构化 DayLedger 小结与无状态网关；模拟器纵向闭环通过，真机/NSD/后台/真实模型仍保留 |
| 2026-07-26 | Agent Revision 写入 | 在冻结 v1 协议上接通 Android/Host `append_revision`：exact revision ID、SQLCipher 原子幂等、敏感目标隐藏、MCP/TLS 回归；该切片当时 undo 关闭，后续由独立撤销切片更新 |
| 2026-07-26 | Agent Event/Revision 撤销 | 在冻结 v1 协议上接通 Android/Host exact `undo_capture`：10 分钟首次时窗、Event tombstone、Revision compensation/head conflict、SQLCipher 持久幂等与恶意结果拒绝；read、跨端传播、设备与真实宿主 Gate 保留 |
| 2026-07-26 | Agent 最小读取 | 接通 Android/Host bounded `visible_events`：单 Personal space/Event/structured、query/time/limit/sensitivity、Restricted 不泄漏、正文截断和 Host Recall/Context injection/budget；`get_event`/策略/长期 Memory 保持关闭，真实 Host/设备 Gate 保留 |
| 2026-07-15 | Android 连接体验 | 增加 Debug 三入口统一候选/授权/成功/断开适配层和非敏感状态存储；Release provider 为空，真实 TLS 手工路径下沉开发者选项 |
