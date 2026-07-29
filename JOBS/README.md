# Ameme 工作队列

> 本文件只做状态路由。详细计划、证据和确认项见 `JOBS-Ameme.md`。

| 工作主题 | 当前状态 | 当前 Gate | 详情 |
|---|---|---|---|
| 工作区与上线工作流 | done | 已确认 | `JOBS-Ameme.md#工作区与上线工作流` |
| 用户与场景研究 | plan_ready | Gate 1 | 按产品决策有意后置；`JOBS-Ameme.md#用户与场景研究` |
| 多端信息源研究 | plan_ready | Gate 1 | 按产品决策有意后置；`JOBS-Ameme.md#多端信息源研究` |
| 一天上下文覆盖与长期记忆 P0 | in_progress | Goal active / repository candidate conditional_pass / Gate 1 hold | 本地覆盖、编译、删除、恢复、复用、Android Agent 四操作、iOS 四操作 fail-closed 客户端与 Swift→Android AVD 纵向闭环已完成；等待完整 Xcode、真实 Pilot、规模与生产恢复；`JOBS-Ameme.md#一天上下文覆盖与长期记忆-p0` |
| MVP 范围与体验原则 | accepted | Gate 1 hold | 品牌、单悬浮记录入口、统一 Skill、旅程和指标已接受；`JOBS-Ameme.md#mvp-范围与体验原则` |
| MVP 三线并行设计与技术方案 | accepted | M1–M6 authorized / Gate 1 hold | 产品、交互、契约、架构、安全、质量和工程计划完成全链审计；`JOBS-Ameme.md#mvp-三线并行设计与技术方案` |
| MVP 研发前准备 Goal | done | M1–M6 authorized / Gate 1 hold | owner/local gap 清零且终检通过，只保留 Spike 与外部证据；`JOBS-Ameme.md#mvp-研发前准备-goal` |
| MVP 工程实现 | done | M24 engineering pass / Gate 1 and release hold | 双端真实本机/固定 Mock 核心闭环、可装配工程、QR→Android TLS/HMAC/Grant、Android 16 KB 大字号设备回归、iOS Xcode/XCUITest 截图和仓库门禁已收口；物理设备、真实读屏、签名/商店、账户共享 Grant、后台、正式用户与生产模型继续作为发布保留门；`JOBS-Ameme.md#mvp-工程实现` |
| 双端平台视觉与设备复验 | done | M25 engineering pass / release hold | iOS 26 原生 Liquid Glass 与 Android 当前稳定 Material 3 已完成同屏视觉、字号、语义、真实构建和 Simulator/16 KB AVD 复验；物理设备/真实读屏/签名与商店仍 hold；`JOBS-Ameme.md#第二十五批双端平台视觉升级与设备复验里程碑2026-07-26done` |
| Prototype 候选评审 | plan_ready | Gate 1 hold | D1–D10 已回填，等待 Gate 1 输入与实施证据；`JOBS-Ameme.md#prototype-候选评审` |
| 封闭 Beta 候选持续执行 | in_progress | production P0 / external gates hold | Coverage、事件、长期 Memory、恢复候选及同安装可回滚激活内核、复用、来源/space 删除、字段证据、用户确认 provenance、Android Agent bounded read/write/undo、iOS 四操作客户端与 AVD 纵向闭环已接生产；iOS 普通入口仍为 event-only，真实用户/设备/跨设备恢复/签名/商店 Gate 继续 `hold`；`JOBS-Ameme.md#封闭-beta-候选持续执行-goal` |

## 状态定义

`proposed -> plan_ready -> approved -> in_progress -> review_ready -> accepted -> done`

旁路状态：`blocked`、`cancelled`。
