# Ameme Event 与 DayLedger 最小模型 v0.4

> 文档状态：第 4 步架构候选已确认，等待后续样本与 Prototype 验证\
> 目标：定义“一天发生了什么”如何被识别、清晰描述、可靠存储，并支持后续总结和长期记忆。\
> 非目标：现在穷举全部记忆类型或确定最终数据库技术栈。

术语说明：`DayLedger` 仅作为内部对象名，指“某一天按时间组织的事件记录”。用户侧统一使用“我的一天”或“每日事件记录”。

## 1. 核心对象顺序

```text
SourceObject / Signal
 -> Observation 从来源中解析出的观察
 -> EventCandidate 可能发生的事
 -> Event 已核验或高置信事实
 -> Episode 围绕同一活动的一段经历
 -> DayLedger 某一天的事件账本
 -> Summary / Memory / Recall 派生使用
```

### EventCandidate

系统根据照片、日历、地点、文件、网页、游戏记录或用户一句话推测的事件草稿。允许不完整、冲突、被拒绝或与其他草稿合并。

### Observation

解析器从 SourceObject 中读取出的局部观察，例如“照片拍摄于 19:10”“日历标题包含项目评审”“游戏 API 返回 2 胜 1 负”。Observation 只陈述来源中可观察到的内容，不直接等于“用户做了什么”。

### Event

带时间、来源和事实状态的最小事件。Event 不等于一条 App 行为；连续打开多个页面、修改多个文件或连拍多张照片，通常应合并成一个有意义事件。

### Episode

多个相关 Event 构成的一段经历，例如“去公司并参加项目评审”“和家人晚餐”“晚上玩了两局游戏”。

### DayLedger

某个自然日内按时间组织的 Event/Episode、已知缺口和修订记录。它是当天记录的核心真相源；日报和日记文案只是它的派生视图。

### UserAddendum（用户后补描述）

用户在事件发生时或事后，通过文字、语音、照片/文件补充“发生了什么、为什么重要、结果如何”。后补描述是正式来源，不直接覆盖旧记录：它可以创建新的 EventCandidate，或形成 EventRevision 并保留补充时间、所指事件时间和原始内容引用。

## 2. 什么算一天中值得记录的事件

目标是覆盖“对还原这一天有意义的事件”，不是记录每一次点击、每一步移动或每条消息。

一个候选至少满足一项：

- 活动发生变化：从通勤转为工作、从工作转为吃饭或娱乐。
- 有明确意图：研究问题、购买物品、锻炼、旅行、联系某人。
- 产生结果或状态变化：完成文件、做出决定、赢得比赛、到达地点、支付账单。
- 与人发生有意义的互动：会议、聚餐、通话、共同活动。
- 用户主动标记：一句话、语音、分享或“这件事值得记”。
- 能解释后续事件：身体不适、设备故障、临时任务或情绪变化。

以下默认只作为信号，不单独成为 Event：

- 短暂打开/切换 App。
- 同一意图下的重复文件修改或网页访问。
- 没有上下文的单个通知。
- 无法与本人活动可靠关联的后台任务。

## 3. Event 最小 Schema

```json
{
  "event_id": "evt_20260713_001",
  "owner_id": "local_owner",
  "space_id": "personal",
  "occurred_at": {
    "start": "2026-07-13T12:10:00+08:00",
    "end": "2026-07-13T13:05:00+08:00",
    "precision": "approximate",
    "timezone": "Asia/Shanghai"
  },
  "facts": {
    "actor": [{"entity_id": "self", "role": "participant"}],
    "action": "dine",
    "object": "lunch",
    "location": {"label": "公司附近餐厅", "precision": "place_label"},
    "result": null
  },
  "classification": {
    "primary_scene": "life",
    "event_form": "activity",
    "tags": ["meal", "social"]
  },
  "salience": {
    "general_importance": 0.58,
    "emotional": {
      "direction": "unknown",
      "intensity": null,
      "source": null,
      "confidence": null
    },
    "relationships": [
      {
        "entity_id": "friend_1",
        "event_significance": 0.72,
        "source_ref": "addendum_1"
      }
    ]
  },
  "user_assertions": [
    {
      "scope": "participants_and_meaning",
      "content_ref": "addendum_1",
      "recorded_at": "2026-07-13T21:20:00+08:00"
    }
  ],
  "description": {
    "title": "中午在公司附近吃饭",
    "summary": "中午外出吃饭，约一小时后返回。同行人尚未确认。"
  },
  "fact_status": "inferred",
  "field_confidence": {
    "time": 0.86,
    "action": 0.73,
    "location": 0.62,
    "participants": 0.20
  },
  "source_refs": ["src_calendar_1", "src_photo_2"],
  "evidence_by_field": {
    "time": ["obs_photo_exif_1"],
    "location": ["obs_location_1"],
    "participants": ["addendum_1"]
  },
  "merge_provenance": {
    "candidate_ids": ["ec_1", "ec_2"],
    "decision": "auto_merge",
    "decision_revision_id": "evtr_1"
  },
  "sensitivity": "personal",
  "retention_policy": "structured_long_raw_30d",
  "revision": 1
}
```

## 4. 字段边界

| 字段组 | 必须回答什么 | 规则 |
|---|---|---|
| Identity | 这是谁、哪个空间的事件 | Work/Personal/Family/Health/Finance 隔离 |
| Time | 何时发生、精度多高 | 支持精确、近似、仅日期和未知结束时间 |
| Actor | 本人、他人还是系统 | 他人身份默认最小化，允许匿名角色 |
| Action/Object | 做了什么、围绕什么 | 使用稳定动作词和可扩展对象，不把 App 名当动作 |
| Location | 在哪里 | 支持城市/区域/地点标签，默认不长期保存精确轨迹 |
| Result | 产生了什么结果或状态变化 | 不知道就留空，不从文件名猜“已上线” |
| Description | 用户如何快速看懂 | 由事实字段生成，可被用户改写但保留 Revision |
| Classification | 这件事属于哪些场景、形态和标签 | 多标签；分类变化不改变事实真假 |
| Salience | 这件事的一般重要性、情绪显著度和关系显著度分别如何 | 三者分开；只影响表达、排序和回顾，不改变任何事实字段置信度或授权 |
| User assertion | 用户补充了什么意图、感受、意义或结果说明 | 保留原话、补充时间和所指时间，不混入系统观察 |
| Evidence | 依据是什么 | 每个推断字段可回到一个或多个 SourceObject |
| Confidence | 哪些确定、哪些只是推测 | 字段级置信度，不只给整条事件一个分数 |
| Governance | 谁能看、保存多久、是否同步 | 随空间、来源和敏感度决定 |

### 用户后补描述字段

| 字段 | 含义 |
|---|---|
| `recorded_at` | 用户实际补充这段描述的时间 |
| `refers_to_time` | 描述所指向的事件时间或时间段 |
| `target_id` | 可选；关联已有 EventCandidate/Event/Episode/DayLedger |
| `content` | 文字、转写文本或附件引用 |
| `assertion_scope` | 用户补充的是事实、意图、感受、结果还是重要性 |
| `space_id` | 后补描述所属隔离空间 |
| `source_ref` | 原始语音、图片、文件或输入记录的位置 |

用户对自己的意图、感受和事件意义具有最高来源权重；对支付结果、他人承诺、系统状态等外部事实，仍可与其他来源并列或等待核验。

情绪显著度主要来自用户文字、语音或主动标记；人脸、声纹和健康波动只能生成待核验候选。关系显著度记录某个人在当前事件中的意义；参与人等事实字段必须由独立来源证据或用户明确陈述支持。情绪、关系和重要性只影响表达、排序与回顾，不能提高任何事实字段置信度，也不能触发空间、来源或 Agent 扩权。

用户标记“这件事很重要”可以触发一次显式的跨空间或第三方 Agent 授权。用户确认对象、用途、接收方和有效期后，形成可撤销的授权策略；重要性本身不改写历史授权，也不允许静默访问。

## 5. 清晰描述规范

每个事件面向用户提供三层表达：

1. `title`：一句话说明“发生了什么”，适合时间线扫读。
2. `summary`：补时间、人物、结果和不确定性，通常 1–2 句。
3. `evidence`：展开后显示来源、原对象位置、字段置信度和修订历史。

底层内容同时分成三类：事实字段、用户陈述和展示文本。展示文本可以重算；用户原话和事实修订不能因重新生成摘要而消失。

写作规则：

- 先写人的活动，不写“Chrome 打开 12 次”“修改 8 个文件”。
- 有结果时优先表达结果；没有结果时只写活动，不猜完成状态。
- 事实和推断分开：“参加了会议”与“可能决定延期”不能混成一个确定句。
- 不知道人物、地点或原因时允许省略，不用模型补齐。
- 多个连续信号围绕同一意图时合并描述；不同意图或长时间中断时拆分。
- 用户改写自然语言不必修改事实字段；若改写包含新事实，生成 EventRevision 并注明来自用户。

示例：

| 信号流水 | 不合格描述 | 合格 Event |
|---|---|---|
| 10 个网页 + 3 个 PDF | 浏览了很多网页 | 上午研究旅行目的地，重点比较交通和住宿；尚未确认最终选择 |
| 照片 + 地点 + 支付截图 | 去了餐厅 | 晚上在某餐厅和朋友聚餐；参与人由用户补充，支付金额留在 Finance 空间 |
| 游戏 API 3 局战绩 | 玩了游戏 | 晚上进行了 3 局排位赛，2 胜 1 负；赛季变化由游戏来源提供 |
| 日历 + 文件修改 | 开会并做了文档 | 下午参加项目评审，并根据讨论更新方案；是否形成最终决策待确认 |

## 6. Episode 合并规则

先用规则生成可解释基线，再使用模型补充：

硬边界优先于相似度：不同用户、未授权的不同空间、明确冲突时间、用户已拆分或无法证明本人参与的活动，默认不自动合并。

- 时间连续：相邻信号间隔在场景阈值内。
- 意图一致：相同项目、地点、人物、活动或用户表达。
- 来源互补：照片、日历和地点共同描述一件事时合并，不重复展示。
- 状态变化：开始、进行、结果和离开可组成同一 Episode。
- 冲突保留：来源时间或结论冲突时不强行合并，生成待核验关系。

阈值按场景配置，不能用一个“30 分钟”规则处理通勤、会议、游戏、旅行和工作。

系统只做三类决定：高可信自动合并；可能相关则保持独立并建立关系；冲突或不同意图则分开。每次自动合并必须保存候选 ID、字段来源、模型/规则版本和可撤销的 Revision。

归并是字段级的：照片可以提供时间，日历可以提供计划，Agent 可以提供工作过程，用户可以提供真实意图和感受。任何一个来源都不能仅凭自己覆盖其他字段。

## 7. DayLedger Schema

```json
{
  "day_ledger_id": "day_20260713_personal",
  "local_date": "2026-07-13",
  "timezone": "Asia/Shanghai",
  "space_ids": ["personal"],
  "entries": [
    {"kind": "episode", "id": "ep_1", "order": 1},
    {"kind": "event", "id": "evt_2", "order": 2}
  ],
  "coverage": {
    "reviewed_ranges": ["08:00/14:00"],
    "known_gaps": ["14:00/18:00"],
    "active_sources": ["calendar", "photo_picker", "user_text"],
    "source_failures": []
  },
  "revision": 3,
  "summary_ids": ["sum_daily_3"]
}
```

`known_gaps` 不代表这段时间一定有事件，只表示系统证据不足或用户尚未复盘。产品不能用“100% 覆盖”描述未复盘的一天。

## 8. 本地存储

Prototype 先用 SQLite + 加密 Raw Vault：

| 表/存储 | 保存内容 |
|---|---|
| `source_objects` | 来源、权限、原始对象指针、hash、处理状态 |
| `observations` | 从来源解析出的局部观察、解析器版本、字段置信度和 lineage |
| `event_candidates` | 草稿事实、置信度、候选关系和状态 |
| `candidate_relations` | 同一事件可能性、相关/冲突关系、合并理由和模型/规则版本 |
| `events` | 当前有效 Event 快照，便于查询 |
| `event_revisions` | 字段/描述/空间/保留策略的追加修订 |
| `user_addenda` | 用户后补描述、所指时间、目标对象、断言范围和原始引用 |
| `episodes` / `episode_events` | 事件分组和排序 |
| `day_ledgers` / `day_ledger_entries` | 每日账本、覆盖缺口、来源状态和条目顺序 |
| `summaries` | 基于哪个 DayLedger revision、模型/模板版本、生成时间 |
| `memories` / `memory_revisions` | 从可信事件形成的长期记忆 |
| Raw Vault | 原始照片、音频、网页、文件副本；与数据库分离 |

Summary 必须记录 `day_ledger_revision`。底层事件变化后，旧 Summary 标记 `stale`，重新生成新版本，不能静默覆盖。

## 9. 普通用户最小样本

总体框架确认后，不依赖工作区的首个样本建议包含：

- 一条日历事件。
- 两张由用户选择的照片或截图。
- 一个分享链接/文件。
- 一句文字或语音补充。
- 至少一个没有数字证据、需要用户补录的真实事件。

验收不是总结是否好看，而是：事件是否按时间排列、描述是否忠实、重复是否合并、缺口是否可见、来源是否可展开、修改后 Summary 是否正确重算。

## 10. 待验证问题

1. “有意义事件”的合并粒度是否因用户而差异过大。
2. 普通用户一天至少需要几种来源，才能产生第一价值。
3. 用户更愿意发生时一键补充，还是晚间按空白时段补充。
4. DayLedger 长期保存全部 Event，还是对低价值 Event 采用分级保留。
5. 跨时区、跨午夜、旅行、夜班和长时间游戏如何归属自然日。
6. 多人共同事件如何避免把他人隐私写入个人长期记忆。
