# Ameme Discovery Brief v0.1

更新日期：2026-07-13

## 1. 首轮判断

市场并非空白，但仍缺少被验证的完整闭环：

- 自动捕获产品擅长“找回看过什么”，通常较弱于形成可维护的长期个人模型。
- 日记产品擅长“保存用户愿意表达的内容”，仍依赖主动输入或确认。
- Agent Memory 产品擅长“把输入过的对话转成可检索记忆”，不负责获得真实生活上下文。
- 操作系统拥有最强数据权限，但数据通常被锁在单一生态，且主动分析和跨 Agent 使用有限。

因此，Ameme 不应把差异化定义为“采集更多”，而应定义为：

> 在用户可控和可核验的前提下，把多源数字痕迹转化为能持续修正、能被人和 Agent 使用的个人记忆。

## 2. 竞品结构

| 类型 | 代表 | 采集方式 | 主要价值 | 可借鉴 | 明显空缺 |
|---|---|---|---|---|---|
| 全量桌面回忆 | Microsoft Recall、screenpipe、Rewind/Limitless 路线 | 定期截图、OCR、音频、应用上下文 | 搜索和回放过去 | 本地处理、排除列表、暂停和删除 | 长期记忆治理、跨生活来源、可信反思 |
| 主动日记 | Day One、Apple Journal | 用户输入，结合照片、位置、活动建议 | 表达和回顾生活 | 经用户选择后共享、端侧建议、端到端加密 | 仍有记录动作，工作上下文较弱 |
| 会议记忆 | Granola、Otter 等 | 日历、会议音频、转录 | 会议笔记和行动项 | 围绕单一高价值场景形成闭环 | 只覆盖会议，不是个人长期记忆 |
| Agent Memory | Mem0、Zep/Graphiti | 对话、业务数据、API 写入 | 为 Agent 组装长期上下文 | 事实抽取、时间关系、图检索、SDK | 不解决真实世界的无感采集和用户界面 |
| 个人知识库 | Notion、Obsidian、Capacities 等 | 用户主动保存或导入 | 组织知识和写作 | 可迁移文件、生态和展示 | 输入摩擦高，行为和生活事件覆盖弱 |

## 3. 已确认的设计信号

1. 本地优先已经成为桌面捕获产品的重要信任机制。
2. “无感”不等于“无授权”；成熟系统都需要暂停、应用/网站排除、删除和保留期控制。
3. iOS 的可行路径更接近“系统生成候选、用户点选分享”，而非第三方应用在后台任意读取全部生活数据。
4. Windows 的系统级全量截图能力依赖特定系统和硬件，不适合作为跨平台产品唯一基础。
5. Agent Memory 的技术生态已经能提供抽取、向量/图存储和检索，但 Ameme 的核心壁垒仍会在采集治理、记忆形成质量和用户纠错闭环。

## 4. 三个 MVP 候选

### A. AI Work Memory（当前推荐）

目标用户：使用 Coding Agent 的产品经理、程序员、研究员、创始人。

数据：前台应用/窗口元数据、浏览器活动、选定目录文件变更、Git、用户一键 Capture；内容读取按来源单独授权。

输出：可信工作日报、项目周回顾、可查询的项目时间线、MCP 上下文接口。

优势：用户痛点强、付费能力较高、桌面端可控、用户可参与 dogfood。

风险：与 Recall/screenpipe 的“搜索历史”容易同质化，必须以项目理解和 Agent 上下文复用建立差异。

### B. Meeting-to-Memory

目标用户：会议密集的管理者、产品经理、销售。

数据：日历、会议转录、用户补充。

输出：决策、承诺、人物关系和长期项目记忆。

优势：价值容易解释，数据边界较清晰。

风险：竞争拥挤，录音同意和平台接入复杂。

### C. Mobile Life Brief

目标用户：已有记录意愿但难以坚持的普通消费者。

数据：用户选中的照片、位置/活动建议、截图和账单导入。

输出：每日日记、消费/活动趋势、生活回顾。

优势：终局覆盖面大，情感价值强。

风险：平台权限、隐私、冷启动和付费意愿均更难，首版不建议。

## 5. 推荐的 Gate 1 决策

建议选择 A，但进一步收窄为：

> 为已经使用 Coding Agent 的高信息密度知识工作者，自动形成“今天推进了哪些项目、产生了哪些决策、下一步是什么”的可信工作记忆，并允许 Agent 在授权下调用。

首版不做：全量录音、读取私人 IM、手机账单、健康推断、情绪诊断、自动替用户形成稳定人格结论。

## 6. 下一轮调研任务

1. 用户访谈：验证用户当前如何写日报、周报、找历史决策和给 Agent 补上下文。
2. 任务排序：日报、项目回顾、历史问答、Agent 注入四者哪个最愿意付费。
3. 数据最小集实验：只用窗口/文件/Git 元数据能否得到可接受的项目时间线。
4. 隐私实验：比较默认关闭、分来源授权、全量捕获加排除三种信任模型。
5. 成本实验：分别测算纯本地、小模型 + 云端总结、全云端三种管线。

## 7. 本轮需要用户确认

只确认两件事：

1. 是否同意首批用户锁定“已经使用 Coding Agent 的高信息密度知识工作者”？
2. 是否同意以 A（AI Work Memory）作为首个 MVP 方向，并将手机生活记录放到后续阶段？

确认后进入 Gate 2，开始产出用户访谈提纲、MVP PRD、数据/隐私模型与可运行的采集验证程序。

## 8. 首轮来源

- Microsoft Recall privacy and controls: https://support.microsoft.com/en-us/windows/privacy/privacy-and-control-over-your-recall-experience
- Microsoft Recall developer overview: https://learn.microsoft.com/en-us/windows/apps/develop/windows-integration/recall/
- Apple Journaling Suggestions privacy: https://www.apple.com/legal/privacy/data/en/journaling-suggestions/
- Apple Journaling Suggestions API: https://developer.apple.com/documentation/JournalingSuggestions
- Day One AI and privacy: https://dayoneapp.com/guides/ai-features/ai-features/
- screenpipe product and repository: https://screenpipe.com/about and https://github.com/screenpipe/screenpipe
- Mem0 documentation: https://docs.mem0.ai/introduction and https://docs.mem0.ai/platform/faqs
- Zep documentation: https://help.getzep.com/ and https://help.getzep.com/mem0-to-zep

