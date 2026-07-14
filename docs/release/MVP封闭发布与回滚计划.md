# Ameme MVP 封闭发布与回滚计划 v0.2

> 文档状态：研发前候选；环境、feature flag、灰度、停止、迁移和回滚框架已冻结，具体 build/账户/日期待 Gate\
> 更新日期：2026-07-14\
> 当前事实：无发布候选、无商店/身份/模型生产账户、无真实构建；MVP 不建设用户记忆数据云；本文不能用于宣称 Gate 6 通过。

## 1. 发布目标与范围

首个执行 Ring 仍是可撤回的封闭测试，用于验证 `J1–J6`、Day Reconstruction/Recall Utility、信任、稳定和单位成本；随后进入首个公开 MVP。健康五类是公开 MVP 必备能力，HEALTH-01/商店证据未通过时阻断公开发布；后台位置、selected Raw peer sync、外部模型或向量仍按各自 Gate。

## 2. 环境与配置

| Env | 数据 | 访问 | 允许能力 |
|---|---|---|---|
| local | synthetic | developer | Contract/Core/fixtures，无外网默认 |
| dev | synthetic test accounts | team | API/sync/model adapter mock |
| staging | synthetic + approved test accounts | QA/security/research | 全链/迁移/安全/回滚 |
| ring0 internal | 专用最小测试数据 | 项目组 | 真机/运行观测 |
| ring1 closed | consented participants | allow-list | 预登记 MVP feature set |
| production closed | Gate 批准用户 | allow-list | 仅批准 flags/provider/region |

配置/secret 来自环境密钥系统，不入 Git/包。每次 build 固定 commit、Schema/API major、DB/index migration、prompt/model/policy version、third-party/SBOM 和 feature flag snapshot。

## 3. Feature flags

| Flag | Default | 开启门 |
|---|---:|---|
| `health_source` | off | PDR-002 + HEALTH-01 + store declaration |
| `location_a3_background` | off | LOC-01 资源/政策/真机 |
| `lan_peer_sync` | off | SDR-001/002 + ADR-006 + LAN-SYNC/SYNC/SEC-01 |
| `selected_raw_sync` | off | key model + one-object deletion proof |
| `cloud_ocr_stt` | off | provider PIA/Eval/cost |
| `cloud_event_model` | off | AI-01 + Restricted block |
| `semantic_vector_recall` | off | SEARCH-01 证明增益 |
| `agent_autonomous_memory` | off | AG security/utility/exact Grant/SKILL-01 tests |
| `daily_summary` | on for synthetic/local | grounded eval + insufficient/stale tests |
| `structured_export` | off | PDR-003 已批准；仍需 privacy/security tests |

Flag 关闭必须停止相应 API/SDK/后台任务和数据访问，不只是隐藏 UI。`health_source` 可以在内部构建默认 off，但首个公开版本必须在证据通过后 on；证据未通过时阻断公开发布，不能靠 flag off 绕过范围。

## 4. 发布 Rings

| Ring | 计划规模 | 最短观察 | 进入 | 退出/停止 |
|---|---:|---|---|---|
| R0 project team | 3–8 test users/devices | 3 个使用日 | Gate 4 核心证据 | 无 P0/P1、观测完整 |
| R1 pilot | 5 consented users | 7–14 日 | user protocol/支持就绪 | 修正任务、信任和支持问题 |
| R2 closed | 10–15 consented users + iOS/Android device matrix | 2–4 周 | Pilot 退出条件通过 | 任务价值/信任/成本可评；负责人决定 public next |

规模/天数是规划候选，明早/后续 Gate 批准。每 Ring 单独 feature flag、provider quota、stop authority 和 rollback owner。

## 5. 发布前清单

- [ ] Gate 1/2/3/4 结论与例外批准范围一致。
- [ ] commit/tag/build 可重复，签名/证书/secret/SBOM/third-party allow-list 校验。
- [ ] JSON Schema/OpenAPI、migration、unit/integration/E2E/AI/performance/security/delete 通过。
- [ ] 双端最低设备/OS、权限/后台/恢复/a11y 证据通过。
- [ ] SDR/PDR、privacy policy、App privacy/manifest、Data safety/Health/Location 申报一致。
- [ ] LAN peer restore/tombstone/delete/account close/rollback 演练通过；全设备丢失不可恢复的文案已验证。
- [ ] dashboard/alerts/on-call/support/data request/incident communication 就绪。
- [ ] cost input 使用真实报价/trace，不是 relative fixture。
- [ ] release notes 诚实列出数据范围、partial、关闭的来源和已知限制。

## 6. Stop conditions

立即停放量并关闭相关 flag：

- 任一 unauthorized disclosure、wrong-space、secret/content telemetry；
- ack 后数据丢失、tombstone resurrection、deletion false completion；
- 模型 confirmed critical fact error 产生实际影响；
- migration/restore 破坏主链或无法回旧库；
- crash/hang/ANR、资源或成本越过预登记 hard limit；
- 商店/隐私/供应商声明与实际网络行为不一致。

普通 p95/SLO/成本的数值停止线在 Ring 前用真实基线预登记，不在结果出来后调低。

## 7. 回滚层级

| 层 | 动作 | 数据注意 |
|---|---|---|
| Feature | remote/local kill switch、停后台/模型/来源 | 不删除用户已保存数据；状态说明 |
| Prompt/model | 回旧 version/route R0/R1 | 旧输出 stale/rebuild；缓存 lineage 删除 |
| API/worker | 回滚 compatible binary、暂停队列、限流 | tolerant reader；不丢 outbox/tombstone |
| Mobile | 停 rollout、回旧 store build/强制升级提示 | 旧客户端不得写不理解 major |
| DB schema | forward-fix 优先；使用加密 snapshot 回滚 | 新写入双读窗口；验证 hash/删除 |
| Sync protocol | 暂停 content，继续 revoke/tombstone priority | cursor/envelope 不重号 |
| Full service | read-only/export/delete-only safe mode | 保留用户控制和事故沟通 |

“回滚 App”不能撤回已经外发的模型/Agent/导出内容；需按 lineage/接收方执行删除/通知并显示例外。

## 8. Rollback rehearsal

1. staging 从 N-1 DB/index/prompt 升级到 N；注入中断。
2. 产生新 Revision、sync envelope、tombstone 和 DeletionJob。
3. 回旧 API/Mobile reader 或 forward-fix；验证旧客户端行为。
4. restore 旧 backup 后先应用 tombstone，扫描 Event/index/cache。
5. 记录 RTO/RPO、数据 hash、pending proof、命令/权限/负责人。

报告用 `../quality/MVP验证报告模板.md`，必须绑定 commit/build。未真实演练不得把回滚写为 ready。

## 9. 发布职责

| Decision | Approver |
|---|---|
| feature scope/Ring/用户 | Product owner |
| build/migration/rollback | Engineering lead |
| PIA/key/third-party/store | Security/privacy owner |
| test/eval evidence | QA/AI reviewer |
| production change | Release owner；不得由单个实现者自行批准 |
| SEV0 stop | on-call/security/product 任一可立即停止 |
