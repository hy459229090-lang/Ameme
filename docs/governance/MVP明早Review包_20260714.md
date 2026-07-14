# Ameme MVP 明早产品负责人 Review 包

> Review 日期：2026-07-14\
> 准备状态：`accepted`\
> 总结论：`pass_with_external_evidence`\
> 边界：`needs_work = 0`、`needs_owner_review = 0`；Gate 1 仍为 `hold`，Gate 2 未执行，工程实现尚未开始。

## 1. 先看结论

产品负责人已完成 D1–D10 最终拍板：LAN 点对点同步、账户归属且无用户密钥门槛、健康进入首个公开版本、结构化导出 P1、M1–M6 全面研发、iOS 18+/Android 14+、Agent 授权内自动读写、Raw A 保留并建立 SourceLocator、Today 单悬浮记录按钮、5 人 Pilot 后扩至 10–15 人真机矩阵。研发授权不等于 Gate 1/2/3 或公开发布已通过。

## 2. 已冻结的 MVP 基线

| 域 | 当前基线 | 明早无需重开的问题 |
|---|---|---|
| 产品 | 一天中的可追溯 Event → `今天` → 补充/纠错 → 历史搜索 → Agent 最小上下文 | 不做 AI 日记文章、持续录屏/录音、完整 Web/桌面端、全量 IM |
| 用户 | 首批为 AI 使用者/高信息密度知识工作者；Mobile 同时支持不懂 MCP 的独立用户 | Mobile 不能以代码工作区为前提 |
| 形态 | iOS Native + Android Native + Agent MCP/Skill/API + Personal Event Core | 不做跨平台像素壳；Web/小程序/桌面采集后置 |
| 交互 | `今天` 唯一默认主页；单悬浮记录按钮展开文字/语音/照片/导入；右上搜索进入同一历史日流；设置为平台原生二级入口 | 不使用“找回”作为用户术语；数据稀疏是正常状态 |
| 来源 | 照片、语音/文字主动补充、机会式位置、选定日历、首个公开版本健康五类、Agent | 不要求用户达到最小来源组合；不默认连续 GPS/全相册/健康原始流 |
| 信任 | Raw 按集中 TTL 保留并建立不暴露完整路径的 SourceLocator；Revision 不覆盖原话；删除传播 | 源索引不保证源仍存在；权重不替代事实，重要性不自动越权 |
| 架构 | 双端原生、Schema 共享；SQLite + Raw Vault + FTS5；同账户已批准设备 LAN Peer Sync；身份服务无记忆内容 | 不建设 MVP 用户数据云；模型/身份提供方由 Spike 与采购证据决定 |
| 质量 | 主指标为一天还原效用、历史召回效用、低负担价值日；隐私/删除/越权为硬护栏 | Event 数量不是北极星；遥测不收正文/搜索词/位置/健康值 |

## 3. 已接受的 D1–D10 最终决策

| 决策 | 最终选择 | 必须接受的影响 |
|---|---|---|
| D1 | LAN/P2P 同步，无用户数据云 | 仅设备同网且在线时同步；后台持续实时不作承诺 |
| D2 | 账户即数据归属，无用户密钥 UX | 登录不等于云恢复；所有设备丢失时旧数据不可恢复 |
| D3 | 健康进入首个公开版本 | 资格、申报、真机语义与删除不通过即阻断公开发布 |
| D4 | 结构化导出 P1 | M5 完成，不阻塞 P0 本地闭环 |
| D5 | M1–M6 全面研发 | 可并行实现，但每个 Gate 和 release stop condition 仍有效 |
| D6 | iOS 18+、Android 14+ | 更低版本不进入 MVP 兼容承诺；发布前覆盖 Android 17 权限变化 |
| D7 | Agent 授权内自动读写 | 仅 exact `autonomous_memory` Grant；Restricted/Raw/扩权/删除继续受门禁 |
| D8 | Raw A 保留 + SourceLocator | 原始按 TTL；本地源索引提高可找回性但不保证源仍存在 |
| D9 | Today 单悬浮记录按钮 | 一个入口打开原生 Sheet/BottomSheet，主页不常驻多输入控件 |
| D10 | 5 人 Pilot → 10–15 人/双端矩阵 | Pilot 先修任务与信任问题，再扩样本与设备覆盖 |

正式决策见 [决策索引](../decisions/_INDEX.md)。

## 4. 不能由今晚文档替代的证据

| 证据包 | 已准备 | 仍需外部条件 | 未完成时挡住 |
|---|---|---|---|
| 用户任务/信任研究 | 任务、脚本、样本、继续/否决条件 | 10–15 名目标用户，先 5 名 pilot | Gate 1 范围确信、M3 |
| iOS/Android 真机 | MOB-IOS/AND、LOC、HEALTH 步骤与指标 | 设备、开发者账号、候选 OS | M3 验收、健康/位置公开发布 |
| 本地 Core | DB/RAW/MIG/SEARCH/AI/COST Spike | 研发人员、候选库/模型与真实供应商价格 | M2 完成、M5 性能/成本 |
| 同步/删除/安全 | SYNC/DEL/SEC Spike 与负向夹具 | 测试服务、两设备、密钥/备份环境 | M4、Gate 3/4 |
| 发布/合规 | PIA、SDK/store allow-list 门、回滚/事故计划 | 目标市场/主体/隐私联系渠道、法律/商店/供应商 Review | Gate 3–6 |

这些项都已经是可执行任务，不再是“以后调研”；但没有真实参与者、设备、账户或实现时不能标记通过。执行入口见 [用户与真机验证执行包](../research/MVP用户与真机验证执行包.md) 和 [技术 Spike 任务书](../engineering/MVP技术Spike任务书.md)。

## 5. 已批准的研发与 Gate 边界

```text
产品研发授权：M1–M6 可按依赖并行准备和实施
Gate 1：仍 hold，必须补真实用户/场景证据
Gate 2：未执行，必须跑通真实 Prototype
M3/M4 验收：需要双端设备/账号和 MOB/SYNC/DEL/SEC/SKILL-01 证据
M5/M6 发布：需要质量、Health、隐私、成本、商店与对应 Gate 证据
```

研发可以全面展开，但在批准真实数据/供应商/发布环境前仍使用合成数据、可替换 provider 和本地/LAN 协议环境，不创建用户记忆数据云或引入未批准 SDK。

## 6. Review 证据地图

| 想检查什么 | 正本 |
|---|---|
| 产品价值、范围、旅程和验收 | [MVP 产品设计规格](../product/MVP产品设计规格.md) |
| iOS/Android 逐页组件、状态和恢复 | [Mobile 双端原生页面规格](../product/Mobile双端原生页面规格.md) |
| 领域、不变量、Schema/API | [领域契约与状态机](../architecture/MVP领域契约与状态机.md)、[机器契约](../../packages/contracts/README.md) |
| 系统架构、AI、成本和 SLO | [研发架构技术方案](../architecture/MVP研发架构技术方案.md)、[成本容量 SLO](../architecture/MVP成本容量SLO与可观测性.md) |
| 隐私、安全、保留、删除与第三方 | [隐私影响评估](../privacy-security/MVP隐私影响评估.md)、[安全需求与威胁模型](../privacy-security/MVP安全需求与威胁模型.md) |
| 研发拆分、测试、发布和运行 | [研发任务书](../engineering/MVP研发任务书与里程碑.md)、[测试与 AI 评测](../quality/MVP测试与AI评测策略.md)、[发布与回滚](../release/MVP封闭发布与回滚计划.md) |
| 缺口是否真正归零 | [全链追踪与审计](MVP研发前全链追踪与审计.md)、[差距矩阵](MVP研发前准备清单与差距矩阵.md) |

## 7. 最终自检结果

| 检查 | 结果 | 证明边界 |
|---|---|---|
| JSON Schema/OpenAPI/正负 fixture | 2,473 checks，0 error | 证明契约与 SourceLocator 一致性，不证明实现完成 |
| Ameme Skill | 官方 `quick_validate` 通过；自定义 7 文件/14 case/14 risk 通过 | 证明 Skill 包和自动记忆/风险夹具完整，不证明真实宿主集成 |
| 工作区治理 | 118 checks，0 error / 0 warning | 证明正本/目录/治理约束一致 |
| Markdown 链接 | 30 links，0 error | 证明仓内引用可达 |
| Python compileall | 通过 | 证明脚本可解析 |
| `git diff --check` | 通过 | 仅有 Windows 探针 LF→CRLF 工作区提示，无 whitespace error |
| 成本计算 | 可复跑 | 当前为 relative planning fixture，不是真实供应商报价 |

最终审计结论不变：研发前本地可完善项已关闭，但真实 Gate 证据必须在后续执行中产生。
