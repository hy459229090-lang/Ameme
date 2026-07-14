# PDR-001：MVP 以事件化“今天”为核心，Mobile 与 Agent 双主端

- 状态：accepted
- 日期：2026-07-13
- 决策人：产品负责人
- 影响 Gate：Gate 1–5
- 替代/被替代：替代早期“Desktop Agent 优先”和“AI 日记文章优先”假设

## 背景与问题

产品终局覆盖更多用户和记忆类型，但首版必须同时证明日常事件覆盖和 AI 任务上下文价值，且不能假设所有用户拥有工作区或 Coding Agent。

## 约束

- iOS/Android 拥有桌面难以获得的照片、语音、位置、健康和分享入口。
- AI 使用者的工作任务主要发生在既有 Agent 产品中，不应先造 Native Desktop 管理端。
- 用户基础单位是一天中的事件；总结和长期记忆后置。

## 候选方案

| 方案 | 价值 | 风险 | 成本 | 证据 |
|---|---|---|---|---|
| Desktop Agent 单端 | 工作上下文密度高 | 日常覆盖差、普通用户不可用 | 中 | 已否决为首版主端 |
| Mobile 单端 | 日常来源独特 | AI 用户任务链断开 | 高 | 不满足首批用户 |
| Mobile 双原生 + Agent MCP/Skill/API | 生活与工作互补 | 双端与同步复杂 | 高 | 产品负责人逐项确认 |

## 决策

MVP 使用 iOS/Android Native Mobile + Agent MCP/Skill/API + Personal Event Core。默认输出为 `今天` 事件流；历史通过搜索按钮进入统一日流；Agent 使用最小 Grant 获取 ContextPack，并在 exact `autonomous_memory` Grant 内自动写入带证据状态的 Event/Revision。

## 后果与回滚

- 不建设 Native Desktop、完整 Web App、小程序和浏览器插件主产品。
- 若双端资源不足，收缩来源/阶段而非改用跨平台自定义 UI 壳；范围变化需新 PDR。
- Gate 1/2 外部证据未通过前只能条件式研发准备，不能公开宣称价值已验证。

## 待验证项

真实用户是否理解事件状态与范围；双端真机来源能力；Agent Recall Utility。
