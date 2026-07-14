# ADR-001：双端原生 UI，MVP 共享 Schema 而不共享 UI/Core 运行时代码

- 状态：accepted
- 日期：2026-07-13
- 决策人：产品负责人确认 UI 原则；研发前架构基线确认共享边界
- 影响 Gate：Gate 2–4
- 替代/被替代：无

## 背景与问题

iOS/Android 的导航、权限、Picker、后台、可访问性和系统恢复不同。过早引入跨平台 UI 或 FFI Core 会把未知平台问题与领域实现绑定。

## 约束

- iOS 使用 SwiftUI/系统组件，Android 使用 Jetpack Compose Material 3/系统能力。
- 两端必须消费同一领域语义、错误码和 API。
- 团队能力、最低系统版本和 FFI 调试成本尚无证据。

## 候选方案

| 方案 | 价值 | 风险 | 成本 | 证据 |
|---|---|---|---|---|
| Flutter/React Native UI | 代码复用 | 系统行为和权限差异被自定义壳掩盖 | 中 | 与已确认原则冲突 |
| Rust/KMP 共享 UI/Core | 领域复用高 | FFI/并发/迁移/调试前置 | 高 | 当前无团队证据 |
| 原生 UI + JSON Schema/OpenAPI 共享 | 平台行为真实、契约一致 | 领域实现会重复一部分 | 中 | 可立即校验 |

## 决策

MVP 两端原生实现 UI、权限适配和 Local Event Node；共享 `packages/contracts` 的 JSON Schema/OpenAPI/fixtures，并通过生成/校验保证一致。P0–P2 不引入共享运行时 Core；后续只有在重复成本和性能有实证时再评估 KMP/Rust。

## 后果与回滚

- 领域算法必须有跨语言 fixtures，不能靠复制文档。
- 将来共享 Core 可逐模块替换，不改变外部契约。
- 品牌层在系统控件之上渐进增加，不改变导航语义。

## 待验证项

Swift/Kotlin DTO 生成工具、两端 fixture round-trip、团队维护成本。
