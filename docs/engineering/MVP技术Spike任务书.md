# Ameme MVP 技术 Spike 任务书 v0.3

> 文档状态：ready_to_execute；15 项 Spike 用于验证/否决已接受基线，不再把选型退回产品负责人\
> 更新日期：2026-07-14\
> 报告模板：`../quality/MVP验证报告模板.md`

## 1. 通用规则

- Spike 先登记 build/commit、设备/OS、fixture、feature flags、重复次数和 pass/reject。
- simulator 不能替代真机后台/电量/安全证明；合成数据不能替代用户价值。
- 原始设备证据放 `artifacts/` 或 `data/private/`（Git ignored）；仓库只放脱敏报告、指标和 hash。
- `pass` 只覆盖该环境/范围；失败保留并形成 reject/narrow 决策。

## 2. Spike 清单

### MOB-IOS-01 iOS 来源/权限/恢复

- 环境：2 个最低/主流候选 iPhone、至少 2 个 iOS major，测试 Apple ID/相册/日历/健康库。
- 输入：FX-02/03/04/05/06、Page M-ONB/TOD/SRC/CAP。
- 步骤：Picker/Share/record、limited/denied/revoke、foreground/background/locked、process kill/relaunch、offline。
- 指标：权限状态准确、local ack/恢复、延迟/内存/电量/唤醒、无数据语义。
- Pass：拒权仍可文字补充；无越范围；保存不丢；状态可区分；达到/解释性能预算。
- Reject/narrow：平台/审核不支持核心用途、后台资源不可控、无法可靠恢复。
- 证据：`tests/results/mobile/ios/MOB-IOS-01/`；角色 iOS+QA+privacy。

### MOB-AND-01 Android 来源/厂商差异

- 环境：至少 Pixel/AOSP + 1 个高后台限制厂商，最低/主流 Android major，测试账户。
- 步骤/输入/指标同 iOS，另测 process death、Doze、battery optimization、Health Connect 缺失/权限取消两次，以及 Android 17 `ACCESS_LOCAL_NETWORK`/拒权/升级迁移。
- Pass：Photo Picker/Share/record/Calendar 与降级清楚；厂商差异由 capability snapshot 暴露。
- Reject/narrow：关键来源只能依赖广泛存储/后台权限或无法解释厂商行为。
- 证据：`tests/results/mobile/android/MOB-AND-01/`。

### LOC-01 A0–A3 位置与资源

- 环境：双端真机，步行/通勤/静止合成路线，不采个人日常轨迹。
- 步骤：A0 照片/日历、A1 前台一次、A2 低精度、A3 visit/significant/geofence 候选；权限降级/锁屏/重启。
- 指标：到访 precision/漏报/误报、后台延迟、唤醒、电量、原始点/TTL/删除。
- Pass：不连续 GPS；资源在批准预算内；近似不显示精确；无权限不写无活动。
- Reject/narrow：A3 资源/审核/厂商不稳定时退回 A0–A2。
- 证据：`tests/results/location/LOC-01/`。

### HEALTH-01 HealthKit/Health Connect 资格与语义

- 环境：双端真机、测试健康库、商店政策/申报版本快照。
- 步骤：五类逐项 read-only、拒绝/撤销/无数据/锁屏/未安装、后台、来源归因、feature flag off。
- 指标：状态可区分、数据最小化、用户理解、审核/申报可行性、价值增量。
- Pass：五类健康来源逐类用途/授权/无数据语义成立；无诊断/默认外部模型/错误无活动；申报与公开 build 行为一致。
- Reject：健康用途与产品定位/商店要求不相容时阻断公开 MVP，并回到产品负责人决定延期或正式缩范围；不得静默 flag off。
- 证据：`tests/results/health/HEALTH-01/`。

### DB-01 本地库/加密/FTS

- 环境：双端最低候选设备，1k/10k/100k/1M Event 合成库。
- 步骤：CRUD/Revision/DayLedger/FTS、并发读写、进程杀、磁盘满、锁屏、backup/restore、key rotate。
- 指标：首帧/分页/search/commit p50/p95、体积、内存、迁移时间、恢复。
- Pass：M/100k 达暂定预算或有批准调整；无 ack 后丢失；index 可重建。
- Reject/narrow：换加密方案/缩低端支持/关闭 FTS 大库。
- 证据：`tests/results/storage/DB-01/`。

### LAN-SYNC-01 双端局域网发现与安全会话

- 环境：iOS/Android 真机至少各 2 台，同一 Wi-Fi、手机热点、AP isolation、网络切换、恶意 peer；Android 16/17 权限差异。
- 步骤：Bonjour/NSD 注册/发现、拒权/撤权、同/异账户、设备加入、前后台/锁屏/进程杀、TLS 会话、Structured/selected Raw 断点续传。
- 指标：发现/连接成功率与时延、权限理解、后台窗口、吞吐/电量、广播字段、未授权连接、重放、恢复同步。
- Pass：发现不暴露账户/space/内容；只有同账户已授权设备可交换；拒权可单机；重连幂等；Raw 未选不传输。
- Reject/narrow：若跨平台自动发现不稳定，降级二维码/短码交换连接信息；若后台不可靠，限定打开 App 同步，不做保活规避。
- 证据：`tests/results/lan-sync/LAN-SYNC-01/`。

### SYNC-01 多设备收敛

- 环境：2–3 模拟 peer node，无中心 coordinator；固定随机种子。
- 步骤：断网/重复/乱序/gap、同/异字段 conflict、schema old/new、revoke/tombstone 优先。
- 指标：丢失/重复/冲突、收敛时间、queue、proof、old client。
- Pass：无静默丢失/复活；同字段 conflict；partial 范围准确。
- Reject：若协议不收敛，阻断 P4；评估 HLC/向量时钟。
- 证据：`tests/results/sync/SYNC-01/`。

### DEL-01 删除与恢复证明

- 环境：单/多来源、Summary/FTS/embedding/cache/ContextPack、在线/离线/丢失设备、backup restore。
- 步骤：remove source、delete event/space/account、故障/重试、恢复旧备份。
- 指标：残留扫描、ack、重算、proof、耗时。
- Pass：无可访问残留/复活；无法确认项不显示 completed。
- Reject：任何 false completion 阻断放量。
- 证据：`tests/results/deletion/DEL-01/`。

### RAW-01 TTL/配额/清理

- 环境：双端音频/照片预览/位置/model cache，离线和 provider failure。
- 步骤：不同配置窗口、重试、低磁盘、到期/用户删除/迁移。
- 指标：恢复成功、字节、电量、清理时延、残留。
- Pass：满足最大恢复需求且隐私/空间预算；expires_at/Job 可证明。
- Reject/narrow：缩短/禁用对应缓存或降低功能承诺。
- 证据：`tests/results/retention/RAW-01/`。

### MIG-01 Migration/rollback

- 环境：V1 1k/100k 库、多个旧 App reader、故障注入。
- 步骤：前向迁移、磁盘/进程中断、旧版打开、index rebuild、云 rolling compatibility。
- 指标：hash/row/invariant、时间/空间、恢复路径。
- Pass：失败回旧库/安全只读；无主链损坏；删除不复活。
- 证据：`tests/results/migration/MIG-01/`。

### SEARCH-01 中文 Recall 基线

- 环境：结构化+FTS、100k/1M 合成与 consented query set。
- 步骤：日期/关键词/同义/错别字/partial/tombstone；向量只做对照。
- 指标：task utility、missing/irrelevant、latency/size/delete consistency。
- Pass：结构化+FTS 达预登记任务且预算内；否则提出最小向量方案。
- Reject：向量增益不足或删除/体积不可控则保持关闭。
- 证据：`tests/results/recall/SEARCH-01/`。

### AI-01 Provider/route

- 环境：固定 eval/attack/delete set，候选端侧/云 provider adapters。
- 步骤：T03–T11、离线/限流/invalid JSON、Restricted block、prompt/model diff。
- 指标：grounding/critical fact/schema/policy/merge/summary/utility/latency/cost。
- Pass：硬门 0 违规；阈值预登记；可回退/删除/退出。
- Reject：不选 provider 或仅保留 R0/R1。
- 证据：`tests/results/ai/AI-01/`。

### SEC-01 Key/recovery/Agent

- 环境：SDR-001/ADR-006 LAN Peer Sync、SDR-002 账户归属/无用户密钥 UX，2 设备+Agent host+恶意 LAN peer。
- 步骤：pair/replay/MITM、账户接管、lost/all-lost device、new device login/peer sync、key rotate/revoke、backup exclusion、identity provider compromise simulation。
- 指标：可恢复范围、越权、secret 泄漏、账户/设备加入负担、全部设备丢失表达、支持流程。
- Pass：实现与用户承诺一致；无 silent escrow；revoke 有效。
- Reject：收缩 LAN selected Raw、自动 Agent 或新设备恢复承诺；不得退回含糊“账户可恢复全部数据”。
- 证据：`tests/results/security/SEC-01/`。

### SKILL-01 真实宿主与 MCP Mock

- 环境：至少 Codex 真实宿主 + `ameme` MCP Mock；Claude Code/Cursor adapter 做兼容检查。
- 输入：`packages/agent-skills/ameme-memory/`、12+ 路由/风险夹具、合成 Event/Grant/ContextPack。
- 步骤：首次配对、`autonomous_memory` 精确授权、自动上下文、自动直接长期写入、离线排队、纠正、越空间、Restricted 外发、提示词注入、撤权/过期。
- 指标：误触发/漏触发、授权扩大、注入执行、重复写入、ContextPack 过期、主任务阻塞、状态准确。
- Pass：无越权/注入执行/重复写；自动写入 evidence/fact state 正确、可见、可撤销；失败不阻塞主任务；撤权后自动读写失败；queued 不显示 synced。
- Reject/narrow：宿主无法保证 caller/指令优先级/密钥/确认时，关闭该宿主隐式调用，只保留显式深链或不支持。
- 证据：`tests/results/agent/SKILL-01/`。

### COST-01 真实单位经济

- 环境：批准模型/身份 provider 报价、M profile 本地/LAN 资源 trace；本地存储/LAN 传输供应商账单价为 0。
- 步骤：替换 cost JSON，跑 L/M/H、敏感性、P50/P95/重用户；核对账单。
- 指标：fully loaded/user、task/source breakdown、gross margin scenario、budget exceed。
- Pass：产品负责人批准价格/配额/降级；否则调路由/定价/范围。
- 证据：`tests/results/cost/COST-01/`，不提交合同/密钥。
