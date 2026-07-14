# Ameme MVP 用户与真机验证执行包 v0.1

> 文档状态：ready_to_execute / blocked_external；脚本、招募、任务、否决和数据处理已齐，等待参与者/设备/负责人批准\
> 更新日期：2026-07-13\
> 目的：补 Gate 1/2 的不可替代证据，不把 FORMALdoc 或合成样本当通用用户证明。

## 1. 研究问题

1. 用户是否认为“事件化的一天”比日记文章/原始流水更有用？
2. Mobile 日常来源与 Agent 工作来源是否互补，还是只制造噪声？
3. 用户能否理解 planned/inferred/partial/local-only/permission-limited/deletion proof？
4. 用户愿意给哪些权限、LAN 同步、外部模型处理和 Agent 访问，哪些信任条件不可妥协？
5. Recall 是否帮助真实任务，补充/纠错负担是否可持续？

## 2. 样本与招募

| Segment | 计划 | 筛选 |
|---|---:|---|
| AI-native knowledge worker | 5–8 | 每周使用 Coding/general Agent，有多项目/历史召回痛点 |
| Mobile-first daily recorder | 5–8 | 不要求 Agent，愿意用照片/语音/日历回顾一天 |
| Privacy-sensitive/health-interest | 3–5（可与上面重叠） | 对位置/健康/云共享有明确边界 |

总样本计划 10–15，先 5 人 pilot 再决定是否调整。不是统计代表性；报告分子/分母和失败模式，不用虚假百分比。

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

研究开始前由产品负责人把具体定量阈值写入 protocol；不能看完结果再改。

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

## 7. 报告与 Gate

每位 participant 有匿名 ID 和 task matrix；pilot 后先修协议，不混入正式分母。最终输出：segment、分子/分母、任务证据、失败严重度、隐私反馈、产品变化和 Gate 1 `pass/hold/reject` 建议。真机 Prototype 证据另由技术 Spike 提供；研究不能替代安全/性能测试。
