# Ameme MVP 用户与真机验证执行包 v0.2

> 文档状态：ready_to_execute / blocked_external；T0 8 人 × 7 天流程、角色、设备/账号、空白模板、否决和数据处理已齐，等待真实人员、设备、账户和负责人签收\
> 更新日期：2026-07-26\
> 目的：补 Gate 1/2 的不可替代证据，不把 FORMALdoc 或合成样本当通用用户证明。

## 0. Gate、人员与启动条件

当前 verdict 为 `hold`。启动前必须在私有运行目录登记真实姓名/联系方式，但仓库只登记角色：

| 角色 | 人数 | 职责 | 不可兼任边界 |
|---|---:|---|---|
| Product owner | 1 | 批准预注册阈值、首发范围和继续/停止 | 不能由实现 Agent代签 |
| Research lead/moderator | 1 | 招募、同意、D0–D8 执行与偏差记录 | 不接触非必要正文 |
| Privacy/safety owner | 1 | 同意书、旁观者、TTL、撤回/删除、事故判断 | P0/P1 信任问题有独立停止权 |
| Device QA lead | 1 | 签名物理设备 build、来源/恢复证据 | 设备自动化不能代签真人体验 |
| Data reviewer | 1 | 分子/分母、缺失数据、去标识和 Gate 报告 | 不查看不必要原始内容 |
| Participants | 8 | T0 AI 使用者中的知识工作者，完成练习日、D1–D8 | 不能用团队成员的合成演练替代 |

最少需要 4 台真实测试设备覆盖 iOS 18+、当前 iOS、Android API 34+ Pixel 类和 Android API 34+ 非 Pixel OEM；目标为 4 名 iOS / 4 名 Android 参与者。若实际平台配额不足，照实报告分母且 Gate 保持 `hold`，不得用 Simulator/AVD 补齐。每位参与者使用独立测试账户和最小测试内容；至少一个获批真实 Agent 宿主账号用于愿意启用 Agent 的参与者。签名、TestFlight/Play internal、身份/Agent/模型账号均由对应 owner 提供，账号 ID、邮箱、token 和证书不得进入仓库。

启动清单：

- [ ] 角色实名、stop authority、设备、账号和支持渠道已在私有目录登记；
- [ ] 8 名参与者筛选、同意和补偿获批，无未成年人或强制公司机密导入；
- [ ] 4/4 平台目标或经 Product/Research 书面批准的偏差已预登记；
- [ ] 候选 build 绑定 commit/build ID，双端物理 smoke、签名和本机加密先通过；
- [ ] 恢复路线对参与者的真实能力与限制已说明；当前同安装 artifact 不得称为全设备恢复；
- [ ] `data/private/user-studies/<study-id>/` 已建立访问控制、TTL、撤回和删除日志；
- [ ] 下述阈值在看到任何正式结果前由 Product/Research/Privacy 三方签收。

## 1. 研究问题

1. 用户是否认为“事件化的一天”比日记文章/原始流水更有用？
2. Mobile 日常来源与 Agent 工作来源是否互补，还是只制造噪声？
3. 用户能否理解 planned/inferred/partial/local-only/permission-limited/deletion proof？
4. 用户愿意给哪些权限、LAN 同步、外部模型处理和 Agent 访问，哪些信任条件不可妥协？
5. Recall 是否帮助真实任务，补充/纠错负担是否可持续？

## 2. 样本与招募

| Segment | 计划 | 筛选 |
|---|---:|---|
| T0 AI-native knowledge worker | 8 | 每周使用 Coding/general Agent，有多项目/历史召回痛点；必须也能独立完成 Mobile 无 Agent 路径 |
| Privacy-sensitive quota | 至少 2（包含于 8） | 对权限、云/Agent、删除或恢复有明确边界；拒权是有效结果 |

本轮固定为 8 人 × 7 个有效使用日，共 56 个 participant-days，另有 D0 练习和 D8 退出/删除恢复。不是统计代表性；报告分子/分母、缺失和失败模式，不外推总体百分比。缺日不得用另一个人的天数补齐；不足 56 个有效 user-days 时 Gate 保持 `hold`，可另开补充 run。

排除：未成年人、不能有效同意、要求导入第三方/公司机密全量内容、医疗诊断场景。参与者必须能使用测试账户/自行选择最小内容。

## 3. 执行任务

| Task | 输入 | 观察 | 成功/否决 |
|---|---|---|---|
| U1 回忆昨天 | 不看 Ameme，列 5–10 个认为重要的事件/缺口 | 自然事件粒度、来源 | 形成 reference，不评价记忆力 |
| U2 来源/权限选择 | Onboarding/来源概念稿 | 选择/拒绝、原因、云/模型理解 | 若多数无法说清后果，重做授权 |
| U3 看 sparse Today | 合成/Prototype 稀疏日 | 是否误以为“完整”、状态理解 | 把 partial 当完整为硬问题 |
| U4 补漏/纠错 | 文字/语音/照片之一 | 动作/时间/信任、原话 | ack/恢复/Revision 可理解 |
| U5 历史任务 | 日历/关键词/上滑，或 Agent ContextPack | 找到并用于一个具体任务 | useful/missing/outdated/irrelevant |
| U6 删除/撤权 | Event/Agent/source 删除影响 | 是否理解范围/例外 | 误以为系统相册/外部副本删除为硬问题 |
| U7 一天复盘 | 与 U1 reference 比较 | 重要遗漏、错误事实、负担、价值 | Day Reconstruction Utility |

### 3.1 D−2 至 D8 时间表

| 时间 | 每位参与者 | 工作人员 | 预计耗时 |
|---|---|---|---:|
| D−2–D0 | 筛选、同意、权限说明、练习日、删除演示 | Moderator + Privacy + Device QA | 45–60 分钟 |
| D1–D7 白天 | 自愿使用主动记录/Share/日历/Agent；可拒绝任一来源 | 异常只做非内容状态支持 | 每日主动操作目标 ≤5 分钟 |
| D1–D7 晚间 | 人工时间线回顾、重要上下文与遗漏标记 | Moderator | 每日 10–15 分钟 |
| 次日早晨 | 确认/修订/删除前一日候选 | 远程值守 | 每日 2–5 分钟 |
| D3/D5/D7 | 固定复用任务 | 记录 helpful/wrong/miss/outdated/action | 每次 5–8 分钟 |
| D8 | 退出访谈、Event/source 删除、恢复演练、研究数据删除 | Moderator + Privacy + Device QA | 45–60 分钟 |

预计参与者总投入约 3.5–4.5 小时；工作人员约 32–40 小时，不含招募与设备故障。不得为了满足耗时而跳过同意、删除或恢复。

四类复用任务在 24 个 D3/D5/D7 slot 中平衡分配，每类 6 次：历史搜索、显式关键词项目续接、用户选定范围会前上下文、决定/承诺找回。任务必须来自参与者当天确认可用于研究的真实目标，但记录表只保存 intent、计数、结果和加盐 attempt hash，不保存 query 或正文。

## 4. 预登记继续/否决

### 继续

- 至少在一个首批 segment 中多位参与者完成真实历史任务并报告具体价值；
- 重要错误/遗漏可被发现和纠正，补充负担可接受；
- 用户能说明主要范围、账户与 LAN 同步、全设备丢失、Agent 和删除边界，或问题能通过具体交互修复。

### 收缩/否决

- Event 数量增加但用户无法用它完成复盘/任务；
- 用户反复把 planned/partial 当 confirmed/complete；
- 为获得价值必须持续录音/全相册/后台精确位置等不可接受权限；
- 关键事实错误/错空间/删除误解在迭代后仍持续；
- Agent ContextPack 无实际任务价值或授权负担高于收益。

### 4.1 本轮预注册判定

以下阈值必须在正式 D1 前签收，不能看完结果再改：

- 完整性：8/8 完成 D0、56/56 有效 user-days、24/24 固定复用任务和 8/8 D8；
- 复用价值：24 次固定任务至少 16 次 `useful`，且每个 intent 的 6 次任务至少 3 次 `useful`；
- 记得准：任何导致错误外部行动、跨空间/Restricted 外发或删除误导的 wrong memory 为立即停止；其他 wrong/outdated/miss 必须给出分子/分母，wrong memory 不得超过 2/24；
- 找得到：人工回顾确认的重要上下文中，最小来源组合总体召回至少 70%，每位参与者均须报告分子/分母，不用平均值隐藏失败个案；
- 少操作：每日主动输入中位数 ≤5 分钟、主动提示中位数 ≤2 次；拒绝权限者仍可完成记录/查看/搜索/修订/删除/导出；
- 删除/恢复：8/8 D8 的影响预览符合参与者预期，删除后重启/恢复不复活；任一复活、假完成或不可解释范围为停止；
- 信任：无 P0/P1 隐私、安全、数据完整性或旁观者问题。出现时立即停对应来源/Ring，不用总体价值抵消。

这些是封闭 Pilot 的产品继续门，不是市场统计阈值。若样本/平台/任务不足、数据缺失或 owner 未预签，verdict 为 `hold`，不是自动 `reject` 或 `pass`。

## 5. 数据处理

- 原始录音/屏幕/真实内容只进 `data/private/user-studies/<study-id>/`（Git ignored），访问限定研究人员。
- 默认记录任务结果、时间、状态理解和匿名引用；不复制参与者完整相册/健康/工作区。
- 参与者可跳过来源、撤回、要求删除；原始 TTL/删除时间写入 consent。
- 仓库只保存脱敏 aggregate、失败模式和不可逆 evidence hash；小样本引语需再次核对去标识。
- 公司/客户/第三人内容不得进入模型或研究附件，除非另有明确授权。

## 6. 研究物料

- Screener：Agent 使用频率、记录方式、近期召回失败、设备、权限敏感、是否可用测试数据。
- Consent：目的、收集内容、录制、处理位置、保留、撤回、奖励、联系渠道。
- Moderator guide：按 U1–U7，不诱导“AI 应该懂我”。
- Observer sheet：task success、误解、重要遗漏/错误、纠正动作、权限/信任原话、severity。
- Post-task：useful/irrelevant/missing/outdated、负担、愿意继续/付费只作探索。

### 6.1 可直接执行的空白模板

从 `tests/manual/external-gates/` 复制以下空白文件到
`data/private/user-studies/<study-id>/`：

- `t0-participant-day-template.csv`
- `t0-reuse-outcome-template.csv`
- `t0-d8-delete-recovery-template.csv`
- `external-gate-summary-template.json`

participant ID 与 attempt ID 使用每个 study 独立 salt 的不可逆 hash；禁止写联系人、正文、Prompt、精确位置、健康值、完整路径、key、设备 serial、账户 ID 或第三方身份。截图/录音默认关闭；确需录制时单独同意并只保存在私有目录，到期删除。仓库只允许提交聚合分子/分母、失败类别、evidence bundle hash 和结论。

## 7. 报告与 Gate

每位 participant 有匿名 ID 和 task matrix；D0 练习不混入正式分母。最终输出：segment、平台、56 user-days 完整性、来源漏斗、重要上下文分子/分母、24 次复用结果、失败严重度、删除/恢复、隐私反馈、产品变化和 Gate `pass/hold/reject` 建议。Data reviewer 生成只含聚合与 hash 的报告，Research/Privacy/Product 三方签收。真机 Prototype 证据另由技术 Gate 提供；研究不能替代安全、性能、签名、商店或生产恢复。
