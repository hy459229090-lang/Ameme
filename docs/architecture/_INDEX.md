# architecture 架构文档索引

| 主题 | 当前材料 | 状态 |
|---|---|---|
| 能力、体验和数据平面 | `../02-product-system-framework.md` | v0.3，双主采集职责已确认 |
| 非结构化处理与存储 | `../06-data-processing-storage-options.md` | v0.4，框架已确认，待验证 |
| Event 与 DayLedger 最小模型 | `Event与DayLedger最小模型.md` | v0.4，显著度与事实置信度/授权解耦 |
| 事件反馈与记忆类型 | `记忆类型与反馈事件模型.md` | v0.2，等待 DayLedger 实验验证 |
| MVP 研发架构技术方案 | `MVP研发架构技术方案.md` | v0.6，技术栈、LAN/账户、SourceLocator、Skill 与 Spike 边界已接受 |
| MVP 领域契约与状态机 | `MVP领域契约与状态机.md` | v0.1，领域不变量、状态、冲突、Recall 和兼容候选 |
| MVP 本地存储、同步与删除 | `MVP本地存储同步与删除协议.md` | v0.23，Android SQLCipher v14/iOS envelope v8；双端设置页提供真实候选健康、最近成功和 exact-confirmation 的同安装恢复，QR Bootstrap v2 以一次性短 envelope + P-256 持有证明换取独立 credential，本机删除收敛 runtime/pairing/pending payload。Android 扫码、账户/共享 Grant registry、peer/provider、跨设备 key、真实用户/物理设备仍待验证 |
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
| 2026-07-14 | 容量/来源 | Android 升级 v5 来源实例幂等，接入 Calendar Provider/系统语音引用，完成异步 I/O、34 项普通 AVD 测试及 10k/100k 性能报告 |
| 2026-07-14 | Agent 应用协议 | 冻结 `ameme.agent-local-node.v1`，Android capture-only 端点可写入 SQLCipher；不包含 LAN、认证、加密、发现或生产持久幂等 |
| 2026-07-26 | Agent Revision 写入 | Android/Host 在冻结 v1 上实现 write-only `append_revision`、SQLCipher 原子幂等与 sensitivity 隐藏；该切片当时 undo 关闭，后续由独立撤销切片更新 |
| 2026-07-26 | Agent Event/Revision 撤销 | Android/Host 在冻结 v1 上实现 exact `undo_capture`、Event tombstone、Revision compensation/head conflict、10 分钟首次时窗与重开重放；read/ContextPack/Recall、跨端传播、真实宿主和设备门保持关闭 |
| 2026-07-26 | Agent 最小读取 | Android/Host 在冻结 v1 上实现 bounded `visible_events`、SQLCipher current projection、scope/sensitivity/time/query/delete 边界与 Host Recall/Context budget；`get_event`/策略/长期 Memory、真实宿主和设备门保持关闭 |
| 2026-07-29 | 用户确认 provenance | Android schema v13 / iOS envelope v8 新增 content-free exact-revision 用户确认记录；完整显式 Candidate 确认可在删源后保留 Event 但去除来源声明，partial/stale/legacy fail closed，迁移不猜测旧动作；真实用户和设备 Gate 保持 hold |
| 2026-07-29 | 恢复激活回滚 | 双端新增同安装候选 exact-confirmation 激活内核、HMAC `prepared/committed` crash journal、失败回旧 live、启动收敛与伪造 journal fail closed；该历史执行点尚未接 UI，后续由同日“同安装恢复用户入口”补齐；`productionRecoveryClaim=false`、跨设备 key 与物理恢复继续 hold |
| 2026-07-29 | iOS Agent 客户端对齐 | iOS 在普通用户 QR Grant 仍为 event-only 的前提下，补齐四项 Android 生产 Local Node v1 operation 的 canonical builder、最小 scope、握手 capability 与 typed response/result-digest/error-shape fail-closed，并在 API 36 / 16 KB AVD 完成 Swift→Android 四操作纵向闭环；物理设备、共享 Grant 与真实 Host 继续 hold |
| 2026-07-29 | Android Agent 访问审计 | Android SQLCipher schema v14 增加 Local Node STARTED/COMPLETED content-free access audit、180 天单点保留、append-only/提前删除保护、失败关闭和设置页最近记录；同安装恢复进一步按未过期记录单调 union 并对冲突/容量失败关闭。相关 instrumentation 后续已在 API 36 / 16 KB AVD 执行；iOS Host、账户汇聚、真实用户/物理设备继续 hold |
| 2026-07-29 | 本机删除访问面收敛 | 双端产品删除入口在 Personal space root freeze 后，停止当前安装 Agent、清除本机 pairing/连接元数据与 pending action/export/App Group handoff，并以 content-free marker 防复活；Android AVD 100/93/7/0、iOS 生产 Smoke、静态 Gate 206/206 通过，账户/共享 Grant registry、peer/provider 与物理设备 hold |
| 2026-07-29 | QR Bootstrap v2 | Release 二维码改为短时一次性 bootstrap envelope；Android 原子消费并绑定首次 P-256 key、签发独立随机 credential，同 key 可重取；iOS device-only Keychain 支持 pending/active 重启恢复。冻结应用通道仍为 bearer，Android 扫码、共享 Grant、物理 LAN/设备与真实用户继续 hold |
| 2026-07-29 | 同安装恢复用户入口 | 双端设置页接通有界恢复点、实际隔离恢复健康、创建/验证/最近成功状态和逐字 `恢复`；Android 关闭/重开 SQLCipher 并重载 Today，iOS 将恢复根放在 live 根同级。该入口仍依赖当前安装设备 key，跨设备与物理灾备 hold |
| 2026-07-14 | Android 完整体验 | SQLCipher 升级 v6；加入 DayLedger/Summary 与持久 Agent 幂等，配对 TLS/HMAC Host→Android→Today 模拟器闭环通过；NSD/物理 LAN/后台与账户 Grant 仍保留 |
