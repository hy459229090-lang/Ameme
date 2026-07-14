# Ameme MVP 研发前全链追踪与审计 v0.2

> 审计结论：`pass_with_external_evidence`\
> 更新日期：2026-07-14\
> 含义：当前工作区可完善的规格/契约/计划和负责人决策已关闭；不代表 Gate 1/2/3 通过，外部证据见第 5 节。

## 1. 全链追踪矩阵

| Capability | Journey/Page | Contract/API | State/Storage | Privacy/Security | Metric | Test/Engineering |
|---|---|---|---|---|---|---|
| ONBOARD-001 | J1 / M-ONB | ContractRequest、`/source-contracts` | not_requested→grant/deny | 按需系统权限、无权限墙 | source_explainer/permission | U2、IOS/AND-001、MOB spikes |
| TODAY-001 | J1/J2 / M-TOD | TodayView、DayLedger、`GET /today` | empty/sparse/ready_local/partial/offline | space-bound、本地首帧 | today_view_ready/day review | FX-01/15、CORE-005、UX-001 |
| CAPTURE-001 | J2 / M-CAP | SourceObjectEnvelope/UserAddendum、`/captures` `/addenda` | acquired→processed/failed；durable ack | 内容禁遥测、Contract location | capture_started/saved/result | FX-02/03、CORE-002/003、IOS/AND-003 |
| SOURCE-001 Photo | J1/J2 / M-SRC/CAP | photo Contract/SourceObject | Picker/limited/revoked/raw ref | 不全相册、不人脸关系推断 | permission/result | FX-03、MOB-IOS/AND |
| SOURCE-002 Location | J5 / M-SRC | location Contract/Observation | A0–A3、precision、TTL job | Restricted、不连续 GPS | source/result/resource | FX-05、LOC-01、IOS/AND-005 |
| SOURCE-003 Calendar | J1/J5 | calendar Contract/Observation | planned/no-data/revoked | 选定日历/时间窗 | source/result | FX-04、IOS/AND-004 |
| SOURCE-004 Health | J5 / M-SRC | health Contract/Observation | denied/no-data/locked/not_synced | Restricted、PDR-002/store release gate | source/result | FX-06、HEALTH-01、IOS/AND-006 |
| RECALL-001/002 | J3 / M-SEA/CAL | RecallQuery/Page、`/recall` `/events` | cursor/range partial/index stale | Grant/space/tombstone final filter | search/results/utility | FX-08/11/15、SEARCH-01、CORE-006 |
| CONTROL-001 | J5 / M-SET/SRC/AGT | contracts/grants/devices/sync status | pause/revoke/expired/partial | 系统权限≠Contract≠Grant | permission/sync_state | U2/U6、UX-002/003 |
| TRUST-001 | J2/J5 / M-EVT | FieldEvidence/Lineage、`/events/{id}/lineage` | observed/inferred/planned/conflict | 不返回 Raw；字段级解释 | correction/trust | FX-04/07、CORE-004、UX-002 |
| TRUST-002 | J2 / M-EVT | Event/EpisodeRevision、If-Match | append/conflict/stale/rebuild | 原话/历史不覆盖 | feedback/correction | FX-07/09、CORE-004/005 |
| TRUST-003 | J6 / M-DEL | DeletionRequest/Job、`/deletion-jobs` | queued→partial_failed/completed | tombstone/ack/proof/backup | delete_job_* hard gate | FX-10/13、DEL-01、CORE/SYNC-008/006 |
| SYNC-001/DEVICE-001 | J3/J5 | SyncEnvelope、`/sync/*` `/devices` | sequence/gap/conflict/tombstone | SDR-001/002、space key | sync_state_changed | FX-09/10、SYNC-01、SYNC-001–007 |
| AGENT-001 | J4 / M-AGT | Grant/ContextPack/Feedback、MCP/API + `ameme-memory` Skill | active/revoked/expired/partial/offline | caller key、purpose/space/type/expiry、exact autonomous grant | context_pack_outcome | FX-11/12、Skill 14-case eval、SEC-01、SKILL-01、AG-001–005 |
| AUDIT-001 | J4/J6 / M-AGT/SET | `/access-audit`、audit metadata | security_audit TTL | 无正文/query，owner-visible | guardrails | C-006、T12、UX-003 |
| SUMMARY-001 | J2 / Today 末尾 | Summary/TodayView | absent/processing/ready/stale/insufficient | 只读 current Event、可重建 | value/grounding | summary eval、AI-004/005 |
| EXPORT-001 | J6 / Settings | ExportRequest/Job、`/export-jobs` | queued→completed/failed/expired | re-auth、scope snapshot、no key | job result/support | U6、security test、UX-004（若批准） |

矩阵中每个 P0 都有页面/命令/对象/状态/权限/指标/测试/工程落点；具体文件以 `docs/_CURRENT.md` 路由。

## 2. Review 发现与修订

| ID | Severity | 发现 | 修订/状态 |
|---|---|---|---|
| R-001 | P1 | PRD 只有三条旅程，Agent/权限/删除缺端到端 | 增至 J1–J6，产品验收 14 项 |
| R-002 | P1 | 双端只有共同低保真，无逐页查询/命令/状态/恢复 | 新增 10 个 Page ID 与双端组件规格 |
| R-003 | P1 | Event 数量容易被误当价值 | 指标改为 Day Reconstruction/Recall/Low-burden，Event 只作诊断 |
| R-004 | P0 | Markdown 对象无机器正本，客户端可各自解释 | 21 类持久对象 + request/view defs、OpenAPI、正负 fixture/validator/CI |
| R-005 | P1 | Episode 在旧正本存在但机器契约缺失 | 增加 Episode/EpisodeRevision、DayLedger/Recall/ContextPack 支持 |
| R-006 | P1 | API 只有底层资源，无法支撑 Today/来源/Grant/访问/导出页面 | 新增 TodayView、Contract/Grant list、lineage、addendum、device/sync/audit/export status 和命令 DTO |
| R-007 | P0 | 删除、冲突、迁移、同步只写原则，无事务/证明协议 | 新增本地存储同步删除协议、状态机、错误码、负向测试/Spike |
| R-008 | P1 | 架构保持十项无边界候选 | 形成 13 项正式 PDR/ADR/SDR 与 15 个验证/否决 Spike；ADR-003 在 MVP 被 ADR-006 替代 |
| R-009 | P1 | Health P0 未结合平台用途/商店门 | PDR-002：进入首个公开版本，资格/申报/真机不通过即阻断发布 |
| R-010 | P0 | 隐私/安全/第三方/商店为空 | PIA、分类保留、20 threats、安全需求、SDK/store allow-list 门 |
| R-011 | P1 | AI/成本/性能没有可验证预算和回退 | Task/Prompt/Eval、L/M/H/X、成本脚本、SLO/观测/降级 |
| R-012 | P1 | 质量/工程/发布/运行目录基本为空 | 测试/Eval、E0–E8、M0–M6、release/rollback、runbooks |
| R-013 | P1 | 外部用户/真机缺口只有“待验证” | 形成用户执行包和 15 个技术 Spike，均含环境/步骤/否决/证据 |
| R-015 | P1 | MCP 存在但缺用户可用 Skill、宿主边界和风险评测 | 新增统一 `ameme-memory` 包、产品/运行契约、14-case fixture、SKILL-01 与 CI 校验 |
| R-016 | P0 | 旧正本误把情绪/关系显著度用于事实增信并让重要性触发扩权 | 统一改为显著度只影响表达/排序/回顾，与事实置信度和授权完全解耦 |
| R-014 | P1 | 根依赖文件违反工作区 allow-list | 移到 `scripts/requirements-dev.txt`，工作区检查恢复 |

## 3. 五类 Review 结论

| Review | Verdict | 说明 |
|---|---|---|
| 来源与范围 | pass_with_external_evidence | 事实/假设/规划/真实证据已分层；Gate 1/2 仍 hold |
| 产品—数据 | pass | 所有 Page/Journey 有 contract/state/recovery；Health 有 release gate |
| 数据—安全 | pass_with_spikes | space/purpose/lineage/delete、LAN Peer Sync、账户归属和透明设备密钥已落；实现证据待 SEC/SYNC/DEL |
| 实现—质量 | pass_with_spikes | API/error/migration/test/metric/rollback 已落；真机/运行证据待实现 |
| 跨正本治理 | pass | 当前索引/Status/Job/决策/CI 可检查；最终命令见第 4 节 |

## 4. 机器验证

当前需持续执行：

```powershell
python scripts/validation/validate_contracts.py
python scripts/validation/validate_ameme_skill.py
python scripts/analysis/calculate_mvp_cost.py
python -m compileall -q scripts research
git diff --check
python scripts/governance/check_workspace.py
python scripts/governance/check_markdown_links.py
```

成本脚本成功只证明公式/输入可运行，relative fixture 不证明真实成本。机器契约通过不证明客户端/服务端已实现。

## 5. 仅剩非本地关闭项

### needs_owner_review

无。D1–D10 已由产品负责人最终拍板，见 `MVP明早Review包_20260714.md` 和 `../decisions/_INDEX.md`。

### blocked_external / needs_spike

- 10–15 人用户/任务/信任研究；iOS/Android 真机权限/后台/性能/Health/Location。
- 本地 DB/加密/FTS、同步/删除/迁移、Agent/Skill security、AI provider、真实成本。
- 目标市场/主体/未成年人/隐私联系渠道/法律审查、身份/模型/商店/供应商账户。

这些均已有 ready-to-execute 文档；没有设备、参与者、团队、账户或批准时不能由文字替代。

## 6. 研发启动建议

- 产品负责人已批准 M1–M6 全面研发；实现可按依赖并行，验收仍逐 Gate 关闭。
- M3–M6 在缺 Gate 1/2、真机、Health、SEC/SYNC/SKILL 证据时不得宣称完成或进入公开发布。
- P4 LAN sync/Agent 自动记忆已有决策基线，但 SEC/SYNC/SKILL-01 通过前不做公开承诺。
- 本审计不改变 Gate 1 `hold`、Gate 2 未执行和 Gate 3 未批准的事实。
