# architecture 架构文档索引

| 主题 | 当前材料 | 状态 |
|---|---|---|
| 能力、体验和数据平面 | `../02-product-system-framework.md` | v0.3，双主采集职责已确认 |
| 非结构化处理与存储 | `../06-data-processing-storage-options.md` | v0.4，框架已确认，待验证 |
| Event 与 DayLedger 最小模型 | `Event与DayLedger最小模型.md` | v0.4，显著度与事实置信度/授权解耦 |
| 事件反馈与记忆类型 | `记忆类型与反馈事件模型.md` | v0.2，等待 DayLedger 实验验证 |
| MVP 研发架构技术方案 | `MVP研发架构技术方案.md` | v0.6，技术栈、LAN/账户、SourceLocator、Skill 与 Spike 边界已接受 |
| MVP 领域契约与状态机 | `MVP领域契约与状态机.md` | v0.1，领域不变量、状态、冲突、Recall 和兼容候选 |
| MVP 本地存储、同步与删除 | `MVP本地存储同步与删除协议.md` | v0.4，Android SQLCipher v4 SourceLocator 两阶段授权清理、FTS/LIKE 分页、space 隔离和 append-only 部分验证；完整 DB-01/Raw/LAN/删除仍待验证 |
| MVP AI 路由与 Prompt | `MVP-AI任务路由与Prompt契约.md` | v0.1，任务目录、隐私门、回退和 Eval 候选 |
| MVP 成本容量 SLO 与观测 | `MVP成本容量SLO与可观测性.md` | v0.2，LAN/local-first 规划档、公式、暂定预算与观测基线 |

## Gate 3 准备状态

- 系统上下文、组件边界、领域/机器契约、同步/删除/迁移、模型路由和容量/成本正本已形成。
- Gate 3 决策已关闭；仍需实现/Spike、真实价格和安全/性能证据。

## 变更日志

| 日期 | 操作 | 说明 |
|---|---|---|
| 2026-07-13 | 建立 | 创建架构正式文档域 |
| 2026-07-13 | 新增 | 定义 MemoryTypeDefinition、FeedbackEvent、个人规则与覆盖快照 |
| 2026-07-13 | 修订 | 新增 EventCandidate、EventRevision、DayLedger，记忆形成后置 |
| 2026-07-13 | 新增 | 定义事件边界、描述规范、DayLedger Schema、存储表和普通用户样本 |
| 2026-07-13 | 修订 | 增加 Observation、字段级归并、双主入口跨端存储与同步边界 |
| 2026-07-13 | 修订 | Event 分开保存一般重要性、情绪显著度和关系显著度 |
| 2026-07-14 | 纠偏 | 按产品负责人原始决策，禁止显著度提高事实置信度或触发扩权 |
| 2026-07-13 | 新增 | 三线并行形成 MVP 研发架构技术方案 v0.1 |
| 2026-07-13 | 修订 | RecallQuery 增加历史日流分页/锚点，增加四类原始与中间数据保留原则 |
| 2026-07-13 | 修订 | 用户侧历史入口改为搜索按钮，底层 Recall/RecallQuery 名称与契约保留 |
| 2026-07-13 | 确认 | iOS SwiftUI 与 Android Jetpack Compose Material 3 原生 UI 优先；共享代码不拥有 UI 渲染 |
| 2026-07-13 | 新增 | 形成机器契约对应的领域状态机、本地存储、同步、删除和迁移正本候选 |
| 2026-07-13 | 修订 | 架构升级 v0.5，冻结可逆默认基线，补齐 AI、成本、容量、SLO 与无内容可观测性 |
| 2026-07-14 | 决策 | 接受 SQLCipher/FTS5/SourceLocator、LAN Peer Sync、账户归属/透明设备密钥和统一 Skill 自动记忆边界 |
| 2026-07-14 | 实现证据 | Android 固定官方 SQLCipher 4.15.0；API 36 AVD 验证 WAL、错误密钥、非明文 header、重建、追加删除与 v1→v2→v3，未外推真机/16 KB/性能 |
| 2026-07-14 | 边界加固 | 本地库升级 v3，显式 space 绑定和复合主键；Revision trigger 禁止改删；系统备份/D2D 显式排除全部数据域；空文字不生成占位原话 |
| 2026-07-14 | Android 证据 | API 36 AVD 验证 v4 SourceLocator、Photo Picker/ACTION_SEND 边界、Calendar planned 适配器、FTS5/LIKE 等价和 keyset 日期分页；不外推真机/16 KB/性能 |
| 2026-07-14 | P1 修复 | 持久 URI 删除改为 `RELEASE_PENDING → RELEASED` 两阶段清理；启动/删除后重试，失败保留且按 space 隔离 |
