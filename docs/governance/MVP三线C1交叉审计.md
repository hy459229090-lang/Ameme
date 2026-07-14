# Ameme MVP 三线 C1 交叉审计 v0.4

> 文档状态：C1 产品取舍已全部回填，结论为 `pass_with_validation_items`\
> 更新日期：2026-07-13\
> 审计范围：产品设计 v0.4、Mobile 交互设计 v0.4、研发架构技术方案 v0.4、Mobile 低保真基线 v0.3、原生 UI 与流畅性基线 v0.1\
> 重要边界：C1 只证明三线逻辑可对齐，不证明用户价值、平台能力或工程实现已经通过 Gate。

## 1. 审计结论

三条线已经形成同一条 MVP 主链：

```text
获准来源 / 用户补充 / Agent
 -> SourceObject / Observation
 -> EventCandidate / Event / Episode
 -> DayLedger
 -> 今天浏览与补充
 -> Mobile 历史搜索 / 日历锚定 / 关键词筛选 / Agent 召回
 -> Revision / 删除传播 / 使用反馈
```

C1 结论为 `pass_with_validation_items`：

- 产品价值、核心页面、数据对象和组件边界基本对齐。
- 没有发现要求 Connector 直写事件、Summary 作为真相源或重要性静默越权等结构性冲突。
- D1 已选择“干净今天 + 右上搜索按钮”，D4 保留原则已确认，D5 双端原生 UI 优先已确认；当前只剩平台映射、可交互原型和技术/证据验证项。
- 当前不能据此解除 Gate 1 hold，也不能直接进入完整 MVP 开发。

## 2. 审计输入

- 产品：`docs/product/MVP产品设计规格.md`
- 交互：`docs/product/Mobile交互设计规格.md`
- 研发架构：`docs/architecture/MVP研发架构技术方案.md`
- 联动基线：`docs/product/Mobile信息架构与数据获取联动.md`
- 领域基线：`docs/architecture/Event与DayLedger最小模型.md`

## 3. MVP 能力追踪矩阵

| Capability | 产品规则 | 交互落点 | 架构落点 | C1 状态 | 下一证据 |
|---|---|---|---|---|---|
| TODAY-001 今天 | 事件优先、小结后置、不做覆盖率 | 本地首帧、时间线、状态、补充、小结 | Local Event Node、DayLedger、stale/rebuild | 已闭环候选 | 合成一天低保真 + P1 |
| CAPTURE-001 主动补充 | 语音/照片/文字/导入，先保存 | 常驻补充区、外部分享、失败保留 | SourceObject + UserAddendum + durable queue | 已闭环候选 | 离线补充 P1/P2 |
| SOURCE-001 照片 | 用户选择范围，原图默认本地 | Picker/相机/说明/媒体失效状态 | Photo Adapter、EXIF/hash、Raw Vault | 待 Spike | iOS/Android Picker 真机 |
| SOURCE-002 位置 | A0–A3 最低成本足够信号 | 分级授权、无位置降级 | Location Adapter、PlaceVisit/Trip、TTL 缓冲 | 待 Spike | P3 电量/后台/TTL |
| SOURCE-003 日历 | 计划不等于发生 | 来源包、计划状态、找回日期 | Calendar Adapter、planned Observation | 已闭环候选 | 双端时间窗/重复日程 Spike |
| SOURCE-004 健康 | 五类分项授权，无数据不等于无活动 | 身体与活动授权包、有限状态 | Health Adapter、日/会话聚合、Health space | 待 Spike | P3 HealthKit/Health Connect |
| RECALL-001 历史日流/日历 | 向上加载更早日期，日历只跳转锚点 | 统一按日分组日流、可展开月历 | RecallQuery anchor/day page、DayLedger 日期索引、Policy filter | 已闭环候选 | 长日流性能、锚点恢复和低保真任务 |
| RECALL-002 关键词/Agent 搜索 | 筛选同一历史日流，命中仍按日分组 | 搜索词、日期条件、部分结果和无结果 | RecallQuery、FTS、未来 semantic/agent | 已闭环候选 | 中文检索质量与删除回归 |
| CONTROL-001 设置 | 控制层不占内容导航 | 明确菜单入口；iOS push/sheet，Android 可用 drawer | Contract/Policy/Source health/Sync state | 已闭环候选 | 双端原生导航真机验证 |
| TRUST-001 来源解释 | 字段依据可理解 | 事件详情“为什么这样记录” | LineageEdge + field evidence | 已闭环候选 | 来源解释可用性验证 |
| TRUST-002 修订 | 不覆盖历史和原话 | 修改、确认/否认、拆分/合并 | EventRevision/DescriptionRevision | 已闭环候选 | 冲突与撤销 Prototype |
| TRUST-003 删除 | 删除传播到派生物和副本 | 影响范围、传播中、失败重试 | DeletionJob、tombstone、ack、recompute | 待 Spike | 单/多来源+离线设备删除 |
| SYNC-001 选择性同步 | Raw 默认本地，结构化按空间同步 | 仅本机、同步中、部分范围 | device_local/structured/selected_raw | 待决策+Spike | 威胁模型、P4 多设备 |
| AGENT-001 Agent 闭环 | purpose/space/time/type/expiry | 配对、使用范围、访问记录 | MCP/Skill/API、ContextPack、短合同 | 已闭环候选 | P5 身份/负向权限测试 |
| SUMMARY-001 今日小结 | 末尾 2–3 行简版、可展开、数据不足不生成 | 证据足够时显示、过期/重算状态 | Derived Output、stale flag | 已确认 | P1 小结阈值和语气验证 |

“已闭环候选”只表示产品、交互和架构都有对应落点；没有验证证据前仍不是已完成能力。

## 4. 三线已经一致的原则

1. 用户主动补充的成功条件是本地保存，不等待 AI 或网络。
2. `今天`读取本地 DayLedger 首帧，后台更新只做局部刷新。
3. Mobile 历史搜索的日流分页、日历锚定、关键词筛选和未来 Agent 搜索共用 RecallQuery 与权限边界。
4. 设置退出内容导航，承担来源、权限、同步、Agent 和删除控制。
5. Connector 只能提交 AcquisitionContract 约束下的 SourceObject，不能直接写 confirmed Event。
6. 原始照片、音频和位置默认留来源端；结构化对象选择性同步。
7. Summary、Memory、全文/向量索引和 ContextPack 都是可重建派生物。
8. 情绪、关系和重要性不改变无关事实，也不静默扩大权限。
9. 删除、撤权、离线、冲突和来源失败进入主链，不后置到上线后。
10. 不设置面向用户的最小来源组合；数据稀疏、未授权、未同步和处理失败是正式产品状态。
11. iOS 与 Android 统一任务语义和数据状态，但 UI 分别使用平台原生组件；本地首帧、输入和保存反馈不等待网络或 AI。

## 5. 产品反馈回填与剩余取舍

| ID | 取舍 | 当前结论 | 状态 | 影响 |
|---|---|---|---|---|
| D1 | 历史搜索入口位置和用户名称 | 选择“干净今天 + 右上放大镜按钮”；用户界面不显示“找回” | 已确认低保真 | 搜索可发现性和无障碍标签进入验证 |
| D2 | 今日小结默认态 | 当日事件流末尾显示 2–3 行简版，可展开；数据不足不生成 | 已确认 | 小结阈值与过期状态待验证 |
| D3 | 是否需要最小来源组合 | 取消产品门槛；工程夹具可选择若干来源，但不代表用户前提 | 已取消 | 必须专门验证数据稀疏日 |
| D4 | 原始位置、音频、照片缩略和模型输入缓存的保留 | 采用“本地短期恢复 + 结构化独立保留 + 选定原始才同步 + 删除传播”原则 | ADR-005 初始 TTL 已接受，Spike 可收紧/否决 | 隐私、存储、重算和删除 |
| D5 | 双端 UI 与流畅性方向 | iOS 使用 SwiftUI/系统组件，Android 使用 Jetpack Compose Material 3/系统能力；不追求像素一致，不建设跨平台自定义 UI 壳 | 已确认 | 组件映射、真机性能、团队能力与最低版本待验证 |

## 6. 必须进入定向验证的问题

1. iOS/Android 最低版本、原生组件稳定性、启动/滚动/输入/恢复性能、照片 Picker、位置后台和健康可用性矩阵。
2. A0–A3 位置的电量、延迟、系统限频和原始点 TTL。
3. LAN 结构化同步的威胁模型：发现隐私、设备认证、加密会话、删除传播与部分搜索如何验证。
4. 设备配对、空间密钥、账户恢复和设备撤销。
5. 多设备并发 Revision、tombstone 优先级和删除 ack。
6. 中文关键词检索、日期索引、未来语义检索的质量、体积和删除一致性。

## 7. C1 后续动作

1. 交互线以 `Mobile低保真设计基线.md` 的语义为骨架，分别完成 iOS/Android 原生组件映射、补充展开和事件详情状态，不再扩展通用像素稿。
2. 研发线先做 P0 契约与合成夹具，再做 P1 本地 DayLedger Core；夹具必须包含单来源和数据稀疏日。
3. 位置/健康、原始对象 TTL、同步/密钥和删除传播分别形成定向 Spike 计划。
4. 用统一任务跑通 C2：一份合成一天从获取到历史日流/筛选再到删除，并在双端真机记录流畅性指标。
