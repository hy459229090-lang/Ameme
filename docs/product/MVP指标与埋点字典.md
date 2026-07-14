# Ameme MVP 指标与埋点字典 v0.1

> 文档状态：in_progress，目标阈值待 Prototype/Beta 基准\
> 更新日期：2026-07-13\
> 适用范围：MVP 产品决策、质量 Gate、成本与隐私 Review\
> 原则：指标服务“是否准确、低负担、可信地记录并使用经历”的决策，不以采集量、Event 数或 Token 消耗作为成功指标。

## 1. 决策与复盘节奏

| 决策 | 复盘节奏 | 主要负责人角色 | 指标作用 |
|---|---|---|---|
| Prototype 是否继续 | 每次实验 | 产品/研发/安全 | 判断闭环、质量、性能、隐私和成本是否可行 |
| 封闭 MVP 是否有第一价值 | 每周 | 产品/设计/数据 | 判断今天是否可用、补充是否低负担、搜索是否成功 |
| 是否扩大来源/用户 | 双周/月 | 产品/安全/运营 | 判断新增覆盖是否超过噪声、权限和成本 |
| 是否进入 Beta/发布 | Gate | 产品/研发/质量/安全 | 对照预登记阈值与硬守门项 |

## 2. 推荐主指标

MVP 使用三个互补结果指标，不把单一代理包装成北极星：

| 指标 | 定义 | 计算 | 决策价值 | 主要局限 |
|---|---|---|---|---|
| `Day Reconstruction Utility Rate`（一天还原有用率） | 被抽样复盘的一天中，用户认为重要事件基本被覆盖、描述可用且没有严重错误的比例 | `useful_reviewed_days / eligible_reviewed_days` | 直接验证“我的一天”是否有价值 | 需要抽样复盘，不能每天强迫用户确认 |
| `Recall Utility Rate`（搜索/上下文有用率） | 有明确任务的搜索或 ContextPack 使用中，结果被标记有用且无越权/严重过时的比例 | `useful_recall_outcomes / evaluated_recall_attempts` | 验证历史是否真正被使用 | 早期使用次数少，需结合定性证据 |
| `Low-burden Value Days`（低负担价值日） | 形成可用今天或产生有用搜索，并且用户主动整理负担未超出预登记门槛的天数 | 满足价值条件与负担条件的 user-day 数/率 | 防止通过频繁询问换取表面准确率 | 门槛需由 Prototype 基准确定 |

目标暂不填写。先在 Prototype 记录分布、失败模式和样本量，再在封闭 MVP 前预登记目标区间；不能看完 Beta 结果后倒调门槛。

## 3. 诊断驱动指标

| 主指标 | 驱动指标 | 定义 |
|---|---|---|
| 一天还原有用率 | `First Value Success` | 新用户在首次会话或次日形成至少一条可追溯事件并理解其来源 |
| 一天还原有用率 | `Important Misses per Reviewed Day` | 复盘后用户补充的“本应出现的重要事件”数量 |
| 一天还原有用率 | `Material Correction Rate` | 影响时间、人物、动作、结果、空间或合并边界的事件修订数/被复盘事件数 |
| 低负担价值日 | `Active Review Burden` | 用户为补充、核验和纠错实际花费的前台时间与动作数 |
| 低负担价值日 | `Prompt Dismissal Rate` | 高价值追问被跳过/关闭的比例，按问题类型分组 |
| 搜索有用率 | `Search Result Open Rate` | 有结果搜索中打开事件/来源的比例，只作诊断不作价值结论 |
| 搜索有用率 | `Zero/Partial Result Rate` | 零结果和部分范围查询占比，按未同步/未授权/无命中区分 |
| 搜索有用率 | `Context Missing Rate` | Agent/搜索使用后报告缺少关键上下文的比例 |

## 4. 硬守门与护栏

| Guardrail | 口径 | Gate 含义 |
|---|---|---|
| Unauthorized disclosure | 未获 purpose/space/time/data type 授权仍返回内容 | 任何确认案例均为 P0，阻断测试扩量 |
| Wrong-space event | 事件或反馈进入错误隔离空间且被其他调用可见 | 阻断；修复和回归后才能继续 |
| Data loss after saved | UI 已确认“已保存”但 SourceObject/UserAddendum 无法恢复 | 阻断；成功语义必须收紧或修复事务 |
| Deletion false completion | 页面显示完成但仍有可访问副本/索引/派生物且未说明 | 阻断 |
| Critical fact error | 将未发生事件、他人承诺或计划写成确认事实并产生实际影响 | 阻断对应自动化规则 |
| Crash/hang/ANR | 双端崩溃、卡死、ANR 和恢复失败 | 门槛在真机基准后预登记 |
| Resource impact | 电量、后台、存储、网络、模型/转写成本 | 超预算则收缩来源、频率或模型路由 |

隐私与安全硬守门不使用“平均率很好”抵消单个严重事件。

## 5. 埋点事件字典

所有遥测只记录动作、状态、耗时、匿名对象 ID、大小桶和版本；不记录用户文字、搜索词、照片/音频、精确位置、联系人、健康值、文件名、完整 URL 或 Agent 提示词。

### 5.1 公共字段

| 字段 | 含义 | 约束 |
|---|---|---|
| `event_name` | 稳定事件名 | 小写 snake_case，语义版本化 |
| `event_version` | 埋点 Schema 版本 | 破坏性变化升版本 |
| `occurred_at` | 设备端事件时间 | peer/身份遥测可另记 received_at；不上传业务事件时间 |
| `anonymous_user_id` | 遥测匿名主体 | 与内容库 owner_id 分离，可重置 |
| `device_session_id` | 一次 App/Agent 会话 | 短期、不可反查设备标识 |
| `platform/app_version` | 平台和版本 | 不包含设备名称 |
| `space_class` | personal/work/health 等类别 | 可聚合；Restricted 默认不上报 |
| `result_state` | success/partial/empty/error/cancel | 必须使用稳定枚举 |
| `duration_bucket` | 耗时桶 | 默认不上报精确毫秒，性能专用通道例外 |
| `object_count_bucket` | 数量桶 | 不上传对象标题或内容 |
| `error_code` | 稳定非内容错误码 | 禁止拼接系统原始敏感信息 |

### 5.2 核心事件

| 事件 | 触发时机 | 必要属性 | 对应指标 |
|---|---|---|---|
| `today_view_ready` | 本地今天可滚动 | local_state、event_count_bucket、summary_state | 启动、价值日 |
| `source_explainer_viewed` | 用户查看来源价值/权限说明 | source_type、entry_point | 首次价值/信任 |
| `system_permission_result` | 系统权限返回 | source_type、permission_state、request_stage | 授权旅程 |
| `capture_started` | 选择记录方式 | modality、entry_point | 补充漏斗 |
| `capture_saved_local` | 本地事务提交 | modality、offline、duration_bucket | 数据丢失守门、负担 |
| `capture_processing_result` | 异步处理结束 | step、result_state、error_code | 管线质量 |
| `event_feedback_submitted` | 接受/否认/纠错/遗漏 | action、field_class、source_count_bucket | 准确、遗漏、负担 |
| `search_submitted` | 关键词/日期查询提交 | has_text、date_mode、scope_state | 搜索漏斗；不传 query |
| `search_results_ready` | 首屏结果可用 | result_state、count_bucket、partial_reason_code | 搜索质量/性能 |
| `recall_result_opened` | 打开事件/来源 | result_rank_bucket、target_type | 搜索诊断 |
| `context_pack_outcome` | Agent 报告使用结果 | useful/irrelevant/missing/outdated/policy_violation | Recall Utility |
| `delete_job_started` | 确认删除 | target_type、scope_class、replica_count_bucket | 删除漏斗 |
| `delete_job_result` | 删除阶段结束 | completed/partial_failed、failed_stage_code | 删除守门 |
| `sync_state_changed` | 设备/空间同步状态变化 | state、reason_code、queue_bucket | 部分范围/可靠性 |
| `day_review_outcome` | 抽样复盘完成 | useful、important_miss_bucket、correction_bucket、burden_bucket | 一天还原有用率 |

## 6. 指标计算口径

### 6.1 Eligible reviewed day

只有满足以下条件才进入一天还原有用率分母：

- 用户明确进入抽样复盘或在真实使用后提交结果；
- 当前范围、未同步设备和未授权来源已向用户说明；
- 复盘覆盖至少一个已经形成的 Event/DayLedger；
- 同一 user-day 多次复盘只使用预定义合并规则，不能挑最好结果。

### 6.2 Useful recall attempt

一次搜索或 ContextPack 只有在具有明确任务、返回结果且用户/调用方提交 outcome 时进入评估分母。零结果和部分结果另作诊断，不能从分母中静默删除。

### 6.3 Low-burden value day

价值条件与负担条件必须同时满足。负担包含主动输入、追问、纠错和权限重复提示；系统后台耗时不计入用户负担，但进入性能和成本护栏。

## 7. 数据质量与反作弊

1. 客户端事件使用稳定 `event_id` 幂等去重；离线重放不能重复计数。
2. 指标按 `app_version + event_version` 可拆分；Schema 变更不能拼接不可比口径。
3. `today_view_ready` 不等于一天有价值；不能用打开率代替还原有用率。
4. Event 数、来源数、Token 数只作成本/诊断，不能作为北极星。
5. 用户关闭遥测后不影响产品核心功能；本地质量诊断与云遥测分离。
6. 小样本只报告分子/分母和区间，不给虚假精确百分比。

## 8. 基线与目标计划

| 阶段 | 需要的数据 | 输出 |
|---|---|---|
| 合成 Prototype | 任务成功、失败路径、性能、删除/权限负向样本 | 验证可测性和事件完整性，不设置价值目标 |
| 5–10 人可用性测试 | 还原结果、遗漏、纠错、负担、搜索任务 | 初步分布与失败模式 |
| 封闭 MVP 前 | 更稳定样本、设备/来源分层、单位成本 | 预登记主指标、护栏和停止条件 |
| Beta | 周期性留存、价值、信任、性能、成本 | Gate 5 结论，禁止事后改低门槛 |

当前没有真实产品基线，因此本文不提供数值目标；这不是缺失，而是避免把主观数字伪装成证据。真机性能门槛、单位成本预算和 Beta 价值阈值将分别由对应 Spike/研究回填。
