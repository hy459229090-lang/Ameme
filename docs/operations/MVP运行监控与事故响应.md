# Ameme MVP 运行监控、支持与事故响应 v0.1

> 文档状态：研发前候选；SLI/告警/SEV/Runbook/支持和数据请求框架已冻结，值班人员/工具待 Gate\
> 更新日期：2026-07-13\
> 观测边界：`../architecture/MVP成本容量SLO与可观测性.md`

## 1. 运行目标

- 保存、Today、本地搜索在身份服务、LAN peer 或模型故障时仍可用。
- 撤权、tombstone 和删除优先于普通处理/同步。
- 运维能定位对象状态但默认不能看到正文。
- 用户能查看 partial、错误、删除/导出状态，而不是依赖客服猜测。

## 2. Dashboard

| Board | 指标 | 禁止 |
|---|---|---|
| Journey | local ack、Today ready、Recall range/result、delete proof | Event/查询正文 |
| Client health | crash/hang/ANR、frame、memory/storage/battery bucket、OS/build | 设备名称/持久硬件 ID |
| Pipeline | acquire/parse/candidate/ledger queue age/result/retry | Source payload/provider raw error |
| Sync | outbox/inbox、gap、conflict、cursor lag、tombstone/revoke latency | object content |
| AI | task/route/valid/policy/upgrade/latency/token/cost | prompt/response |
| Privacy/security | denied cross-space/purpose、SDK domains、delete proof, secret canary | 精确敏感字段 |
| Cost/capacity | L/M/H distribution、storage/sync/model/support | 平均值掩盖重用户 |

## 3. Severity

| SEV | 例子 | 首次响应/动作 |
|---|---|---|
| SEV0 | unauthorized content、wrong space、ack 后丢失、tombstone 复活、假删除、secret 泄漏 | 立即 kill route/stop rollout，保全非内容证据，通知安全/产品/工程 |
| SEV1 | 大面积保存/同步/登录失败、迁移故障、模型 critical error spike | 降级/回滚，暂停相关 Ring |
| SEV2 | p95/queue/cost 超预算、单来源不可用、有替代路径 | 限流/降级/修复排期 |
| SEV3 | 局部 UI/文案/非核心诊断 | 正常 backlog |

具体值班响应时间由团队/支持时区批准；SEV0 不等待数值阈值。

## 4. 核心 Runbook

### R-01 Saved data missing

冻结受影响版本写入→用 anonymous object/trace 查 SourceObject/transaction/outbox→不从日志索取正文→尝试本地恢复/备份→评估范围→修复/回滚→回归 ack 语义与故障注入。

### R-02 Cross-space/unauthorized

撤相关 Grant/token/route→停止同步/Agent/模型→确定 caller/space/purpose/build/时间范围→扫描 access audit/ContextPack manifest→执行删除/通知评估→独立安全 Review 后恢复。

### R-03 Sync backlog/conflict

保持 local 可用/partial→优先 revoke/tombstone→检查 sequence/schema/old client/queue→限普通处理→修复后验证 convergence 和无复活。

### R-04 Deletion proof incomplete

绝不改 completed→列 pending replica/derived/index/backup→重试失败步骤→设备丢失时显示例外→restore scan→生成新 proof。

### R-05 Model/provider incident

关闭 provider/route→R0/R1 降级→隔离相关 output/cache→检查 policy/grounding/provider retention→回滚版本→固定 Eval/删除回归。

### R-06 Cost/resource runaway

关闭 R3/embedding/selected Raw/background A3→保留 capture/Today/delete→按 source/task/user profile 查驱动→调整配额/路由/价格，不静默删数据。

## 5. 用户支持与数据请求

Support UI/包由用户主动生成并预览，只包含 app/OS/version、feature flags、source/grant/sync/delete 非内容状态、错误码、trace IDs 和资源桶。客服不能要求上传完整数据库、相册、音频、Prompt 或 token。

| 请求 | 流程 |
|---|---|
| 我为什么看到此事件 | Event lineage/状态解释；无后台内容访问 |
| 为什么找不到 | 检查 range/space/device/source/index/permission，不说“从未发生” |
| 暂停/撤权 | 引导系统/Ameme/Agent 三层；说明历史保留 |
| 删除/注销 | re-auth、Job/proof、pending exception；不手工改库 |
| 导出 | scope/date/snapshot，过期/外部副本说明 |
| 安全/隐私投诉 | 升级 privacy/security owner，限制访问，保存非内容证据 |

适用法律的响应时限、联系渠道和组织身份在目标市场确定后补；未确定前不能对外发布隐私承诺。

## 6. 事故流程

```text
detect/report -> classify -> contain -> preserve minimal evidence
 -> investigate scope/root cause -> eradicate/recover
 -> decide user/regulator/store/provider notice
 -> verify deletion/restore -> postmortem -> controls/tests/Gate update
```

Postmortem 无责但必须具体：时间线、影响用户/空间/数据类型、发现缺口、为何控制未拦截、修复/owner/date、回归/监控、是否重开 PDR/ADR/Gate。正文样本不进入报告。

## 7. 演练

Gate 4 前：saved data loss、wrong-space、sync/tombstone、provider outage、deletion proof、migration restore 六个 tabletop/technical drill。Gate 5 前至少完成一次跨产品/工程/安全/支持的 SEV0 演练和用户沟通草稿 Review。
