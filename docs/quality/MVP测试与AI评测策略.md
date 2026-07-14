# Ameme MVP 测试与 AI 评测策略 v0.1

> 文档状态：研发前候选；测试层级、夹具、环境、质量门和证据格式已冻结，运行结果待实现/真机/用户验证\
> 更新日期：2026-07-13\
> 契约入口：`../../packages/contracts/`、`../../scripts/validation/validate_contracts.py`\
> AI 细则：`../architecture/MVP-AI任务路由与Prompt契约.md`

## 1. 质量原则

1. 先证明不会越权、丢失、错误确认和假删除，再优化摘要“看起来聪明”。
2. 正常、空、稀疏、拒权、有限授权、离线、冲突、过期、部分同步和进程回收均为主测试路径。
3. EventRevision/lineage 为可重放真相；Summary/index/ContextPack 的正确性必须从真相重建。
4. 合成夹具用于确定性回归，真实用户/真机证据单独标识，二者不能互相冒充。
5. 所有测试输出不提交真实个人数据、token、Raw、正文日志或可逆路径。

## 2. 测试层级

| Level | 范围 | 主要工具/产物 | 每次 PR | Gate 运行 |
|---|---|---|---:|---:|
| L0 静态/治理 | 格式、索引、secret、SBOM、lint/compile | workspace/contract/secret scan | 是 | 是 |
| L1 Contract/Unit | Schema、enum、状态、不变量、规则/归并 | JSON fixtures、Swift/Kotlin/server tests | 是 | 是 |
| L2 Local Integration | SQLite/Raw/queue/Revision/ledger/delete | 合成库、故障注入 | 核心变更 | 是 |
| L3 Adapter | Picker/Share/Calendar/Location/Health/Agent | simulator + real device evidence | 适配器变更 | 是 |
| L4 Sync/API | auth、grant、envelope、conflict、cursor、tombstone | local/staging multi-device harness | API 变更 | 是 |
| L5 Native UI/E2E | J1–J6、页面状态、恢复、a11y | XCTest/XCUITest、Compose/UIAutomator 等 | 主路径 | 是 |
| L6 AI Eval | extraction/merge/summary/recall/policy | fixed eval set + differential report | prompt/model | 是 |
| L7 Non-functional | 性能、资源、安全、迁移、恢复、隐私网络 | benchmark/security/restore drills | 相关变更 | 是 |
| L8 User evidence | 任务价值、理解、负担、信任 | consented study | 否 | Gate 1/2/5 |

具体框架由工程栈决定，层级和证据不能因工具变化被取消。

## 3. 夹具目录

| ID | 场景 | 必须断言 |
|---|---|---|
| FX-01 sparse_text_day | 只有一句后补描述 | 能形成 Event；不显示覆盖率/虚假全天 |
| FX-02 offline_audio | 离线音频、处理失败/恢复 | 本地 ack 后不丢；状态可恢复 |
| FX-03 photo_limited | 用户选两张照片、后撤一张 | 只处理选择范围；lineage/删除正确 |
| FX-04 planned_calendar | 日历计划无现实证据 | fact_status=planned，不写已发生 |
| FX-05 location_precision | approximate/foreground/A3 点 | 精度不升级；无连续轨迹默认 |
| FX-06 health_states | denied/no_data/locked/not_synced | 四态不合并；公开 build 五类逐项授权/撤权一致 |
| FX-07 same_event | 多源同事件/相关/冲突 | 字段权威、merge/split、Revision |
| FX-08 cross_midnight | 时区变化/夜班/旅行 | DayLedger 归属可解释、无重复 Event |
| FX-09 two_device_conflict | 同/异字段并发 Revision | 同字段 conflict，异字段合并 |
| FX-10 tombstone_offline | 删除后旧设备重放 | 不复活、pending proof 清楚 |
| FX-11 agent_grant | purpose/space/type/expiry 组合 | 只返回交集；撤权后缓存失效 |
| FX-12 prompt_injection | 来源含工具/越权指令 | 当数据处理，不改变 Grant/Schema |
| FX-13 delete_graph | 单/多来源+Summary/index/ContextPack | 完整影响图/重算/证明 |
| FX-14 migration_fault | 迁移中磁盘/进程故障 | 回旧库或安全只读；hash 一致 |
| FX-15 long_day | 1k Event/日、100k/1M 库 | 分页/首帧/内存/降级 |

当前仓库只有 machine contract 正/负示例；其余 fixture 是 P0/P1 实现任务，未完成前不能声称测试覆盖。

## 4. 核心旅程验收矩阵

| Journey | E2E | 负向 | 数据/安全 | 产品指标 |
|---|---|---|---|---|
| J1 第一份今天 | 拒绝全部后台权限后文字保存 | 磁盘满/进程杀/解析失败 | Contract、local ack、无内容日志 | capture_saved_local、day review |
| J2 低负担补充/纠错 | 语音/照片/分享、Revision | 离线/撤权限/冲突 | 原话保留、lineage、stale Summary | burden/correction |
| J3 历史搜索 | 上滑/日历/关键词/返回恢复 | 未同步/index stale/cursor expired | space/grant/tombstone filter | recall utility/partial |
| J4 Agent | pair/capture/recall/context/feedback | MITM/replay/过期/跨 space/injection | caller key、Grant、audit | context_pack_outcome |
| J5 来源/权限/同步 | 开启/有限/暂停/修复 | OS 不支持/锁屏/限流 | processing location、Contract | permission/result_state |
| J6 删除/导出 | delete Job/proof/export snapshot | 离线副本/失败/过期/跨 space | tombstone、keys、no source mutation | delete_job_result |

## 5. AI Eval

### 5.1 必须分集

- `contract`: 结构、枚举、证据引用、安全字段。
- `grounding`: 用户原话、照片/日历/位置/健康 observation 到 Event field。
- `merge`: same/related/conflict/split、跨 space 禁止。
- `summary`: 句子到 Event revision，可识别 stale/insufficient。
- `recall`: 任务有用/缺失/过时/越权，范围 partial 不丢。
- `attack`: prompt injection、恶意 JSON、长输入、隐私 canary。
- `delete`: 模型缓存/embedding/output 不残留。

### 5.2 评测报告

每次 prompt/model/route 变更输出：版本、数据集 commit/hash、任务数、关键事实错误、unsupported fact、Schema valid、policy violation、merge precision/recall、summary faithfulness、recall utility、延迟、token/成本和差分失败案例。

硬阻断：任一越权内容、confirmed unsupported fact、计划当现实的关键错误、错 space、删除后召回。其他阈值在第一个固定集跑完后预登记，不能看结果后调低。

## 6. 性能与资源

按 `MVP成本容量SLO与可观测性.md` 的 L/M/H/X 与 1k–1M 库测试；iOS/Android 分开记录最低候选设备：

- cold/warm Today 首帧、日流慢帧、输入/键盘、分页、搜索、返回/进程恢复；
- SQLite/Raw/索引/迁移体积与事务；
- 位置/健康/同步/索引后台唤醒、电量、CPU、内存和网络；
- API p50/p95/p99、queue age、sync convergence、delete proof；
- AI route/upgrade/valid/latency/cost。

暂定预算是设计目标，不是 pass 证据；真机报告必须给设备/OS/build/样本/重复次数/分布。

## 7. 安全与隐私测试

- MASVS 对照：Storage/Crypto/Auth/Network/Platform/Code/Privacy。
- API：BOLA/IDOR、Grant、purpose/space/data/time、rate/size、error redaction、cursor binding。
- Mobile：backup、deep link/share URI、App switcher/notification、clipboard、root/jailbreak 降级、key store。
- Sync/Delete：replay/order/gap、tombstone、device revoke、restore、partial proof。
- Supply chain：锁文件、SBOM、SCA、签名、secret、未知域名/SDK 阻断。
- Privacy：manifest/Data safety/网络抓包/SDK allow-list/遥测 canary/用户权利任务。

## 8. 环境矩阵

| Environment | 数据 | 目的 | 禁止 |
|---|---|---|---|
| local/unit | synthetic only | 快速回归 | 外部网络/真实凭据 |
| simulator/emulator | synthetic/system fake | UI/状态/故障 | 充当真机权限/电量证据 |
| real device lab | 合成+专用测试账户 | 平台/后台/性能/资源 | 个人主账户/相册/健康库 |
| staging | synthetic/consented test accounts | sync/API/security/release rehearsal | 生产密钥/真实全量数据 |
| closed user study | 明确同意的最小数据 | 价值/理解/真实缺口 | 原始数据进 Git/通用日志 |
| production | approved only | 发布后运行 | debug bypass/未登记 SDK |

## 9. Gate 质量规则

| Gate | 必须通过 |
|---|---|
| P0 | contracts、negative fixtures、invariants、delete graph、error redaction |
| P1 | local end-to-end、crash/disk/migration、stale/rebuild、FTS 基准 |
| P2/P3 | 双端 explicit sources、权限状态、真实设备、公开健康五类/位置资源与商店证据 |
| P4 | multi-device conflict/tombstone/keys/recovery/proof |
| P5 | Agent auth/injection/utility/writeback |
| Gate 4 | 全 J1–J6、a11y、性能、安全、迁移、删除、观测，固定 commit/build |
| Gate 5 | 预登记价值/负担/信任/稳定/成本；真实用户证据 |

## 10. 缺陷等级与关闭

- P0：越权、错 space、ack 后丢失、错误关键事实、删除假完成、secret/内容日志；停止放量。
- P1：主路径不可用、迁移/同步不收敛、平台严重卡顿、用户无法理解范围；Gate 前清零。
- P2：有安全降级/替代、不影响核心闭环；有 owner/date 并可 conditional。
- P3：细节/增强；进入 backlog，不修改 Gate 结论。

关闭必须有失败测试先复现、修复 commit、相关层回归和已知限制；“手工看起来好了”不是关闭证据。
