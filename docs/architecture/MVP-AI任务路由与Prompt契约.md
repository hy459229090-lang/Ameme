# Ameme MVP AI 任务路由、Prompt 与评测契约 v0.1

> 文档状态：已接受；任务分层、隐私门、结构化输出、回退与 Eval 为研发基线，提供方待 Spike\
> 更新日期：2026-07-13\
> 决策：`../decisions/ADR-004-AI分层与提供方可替换.md`\
> 非目标：不在本文选择“最强模型”，不把模型输出升级为无证据事实，不在 MVP 训练长期人格。

## 1. 任务目录

| Task ID | 输入 | 输出 | 默认层级 | 是否允许云模型 |
|---|---|---|---|---|
| `T01_TIME_NORMALIZE` | 来源时间/时区/精度 | TimeRange | R0 规则 | 否 |
| `T02_HASH_DEDUP` | hash/来源/时间窗 | 重复候选 | R0 规则 | 否 |
| `T03_OCR` | 用户选择图片/局部区域 | Observation 文本块 | R1 系统/端侧优先 | Contract 允许时可选 |
| `T04_TRANSCRIBE` | 用户主动音频 | transcript segments + timestamps | R1 系统/端侧优先 | Contract 允许时可选 |
| `T05_CLASSIFY_OBSERVATION` | 结构化 Observation | event type/field mapping candidate | R0/R2 | 非 Restricted 且有必要 |
| `T06_EVENT_DRAFT` | 最小 Observation bundle | EventCandidate + FieldEvidence | R2 小型结构化模型 | 可选 |
| `T07_MERGE_CANDIDATES` | 同 space/time 的 candidates | merge/split/conflict proposal | R0 规则，难例 R3 | 高价值难例且获准 |
| `T08_DESCRIPTION` | 已有事实字段/用户原话 | 短 title/description proposal | R2 | 可选 |
| `T09_DAY_SUMMARY` | 当前 DayLedger Event revisions | 2–3 行 Summary | R2/R3 | 非 Restricted 且证据足够 |
| `T10_QUERY_REWRITE` | 用户搜索词、本地语言 | 结构化过滤/同义候选 | R0/R2 | 默认本地；query 禁止遥测 |
| `T11_RANK_CONTEXT` | 已授权候选 IDs/摘要 | ranked IDs + reason codes | R0/R2 | 只传最小、获准字段 |
| `T12_LONG_TERM_MEMORY` | 跨日历史 | Memory | 禁用 | MVP 不执行 |

“默认层级”是路由上限，不是每次都调用。规则足够、数据稀疏、用户刚保存、预算耗尽、离线或策略阻断时直接完成/降级。

## 2. 路由顺序

```text
validate Contract/Grant/space/sensitivity
 -> can deterministic rule finish?
 -> can platform/on-device capability finish?
 -> is task valuable and budget available?
 -> minimize/redact input and select provider adapter
 -> validate structured output + evidence references
 -> accept as Observation/Candidate/derived output or fallback
```

硬门：

1. Restricted 默认 `SENSITIVE_MODEL_BLOCKED`，除非未来有明确私有处理决策。
2. Contract 未包含 `model_provider` processing location 时不得调用云模型。
3. 无 Evidence ID 的事实字段不得写入 Candidate/Event。
4. Summary 只读 Event current revision，不能直接读 Raw/聊天/照片后生成“隐藏事实”。
5. 删除/撤权队列中的对象不能进入新调用；相关缓存按 lineage 删除。
6. AI 故障不能回滚 `capture_saved_local`，也不能阻塞已有 Today/Recall。

## 3. PromptEnvelope

Prompt 不散落在客户端字符串中。每次任务使用版本化信封：

```json
{
  "task_id": "T06_EVENT_DRAFT",
  "task_version": "1",
  "input_schema": "ObservationBundle/v1",
  "output_schema": "EventCandidate/v1",
  "prompt_template_version": "event-draft-zh-v1",
  "policy_version": "mvp-policy-v1",
  "model_adapter": "provider-neutral-small",
  "locale": "zh-CN",
  "space_id": "space_token_not_name",
  "evidence_manifest": ["obs_..."],
  "content_blocks": [{"evidence_id": "obs_...", "content": "authorized minimal block"}],
  "constraints": {"no_unreferenced_facts": true, "max_candidates": 3}
}
```

持久化 lineage 只保留 task/prompt/schema/model/policy version、evidence IDs、处理位置、结果状态和必要的不可逆 input hash。模型原始请求/响应只在获准的短恢复窗口内保留；错误日志不保存正文。

## 4. 输出契约

每个模型输出先经过：

1. JSON/Schema 解析；
2. 枚举、长度、敏感度和 space 校验；
3. Evidence 引用存在性和授权检查；
4. 未支持字段、注入式指令和模型自报“确信”的剥离；
5. 同 task 的 deterministic post-check；
6. 写 Candidate/derived store，不直写 confirmed Event。

失败分类：`invalid_json / schema_mismatch / missing_evidence / policy_violation / content_safety / timeout / provider_error / budget_exhausted`。重试最多由 task policy 控制；不能无限重试或换大模型绕过隐私门。

## 5. 回退矩阵

| 任务 | 首选失败 | 回退 | 用户可见 |
|---|---|---|---|
| OCR | 系统 OCR 不可用/低质量 | 保留图片引用、允许用户补文字；获准时云 OCR | “图片已保存，文字整理失败” |
| 转写 | 端侧失败 | 保留音频、重试；获准时云转写；改文字 | “语音已保存，等待整理” |
| Event draft | Schema/证据失败 | 规则字段或保持 SourceObject/UserAddendum | 不伪造 Event，可在 Today 显示已保存补充 |
| Merge | 模型不确定 | 保持独立 candidates / 提问 | 不静默合并 |
| Summary | 数据不足/预算/失败 | 不生成或继续显示 stale 旧版 | 不用空泛模板填充 |
| Recall ranking | 模型不可用 | 日期+FTS+结构化过滤 | 范围状态不变 |

## 6. 缓存与批处理

- Cache key = task + normalized input hash + schema/prompt/model/policy version + sensitivity/space policy version。
- Grant、Contract、Revision、tombstone 或 policy 变化时对应缓存失效。
- 低价值同日 candidates 可在充电/网络合适时批处理；用户主动补充和查询优先。
- 任何跨用户/space 缓存复用只允许非内容模型资产，禁止复用内容结果。

## 7. Eval 集规范

### 7.1 数据层

| 集 | 内容 | 用途 |
|---|---|---|
| `synthetic_contract` | 机器 Schema 正/负夹具 | 结构与策略回归 |
| `synthetic_day_sparse` | 单来源、空白时段、未同步、拒权 | 不把缺口写成事实 |
| `synthetic_conflict` | 计划/现实、双设备 Revision、时区冲突 | 冲突与合并 |
| `synthetic_delete` | 单/多来源、Summary/index/ContextPack | 删除残留 |
| `consented_closed_mvp` | 经同意、去标识的真实任务切片 | 价值和自然语言质量；Gate 后回填 |

### 7.2 核心指标

| 指标 | 定义 | 硬门 |
|---|---|---|
| Unsupported fact rate | 输出事实字段无有效 evidence / 总事实字段 | confirmed 输出必须为 0；candidate 也必须有引用 |
| Critical fact error | 未发生、计划当现实、他人承诺、错误空间等 | 任一确认案例阻断对应规则 |
| Schema valid rate | 首次输出通过 Schema/enum/length | 目标由模型 Spike 预登记 |
| Merge precision/recall | 与标注 merge/split 边界比较 | 精度优先，低置信保持独立 |
| Summary faithfulness | Summary 句子可回到 Event revision | 无引用句为失败 |
| Recall task utility | 明确任务后 useful/irrelevant/missing/outdated | 使用产品指标口径，不用点击率代替 |
| Policy violation | 越 space/purpose/data type/provider | 任一内容泄漏为 P0 |
| Upgrade rate | R0/R1 升到 R2/R3 的比例 | 成本驱动；不能靠扩大升级改善质量 |

### 7.3 Review 与放量

- Prompt/model 变更先跑固定集、差分报告和删除/权限负向集。
- 先 shadow/本地候选，不直接改变 confirmed Event。
- 质量、隐私、延迟或成本任一护栏变差即回滚 adapter/prompt version。
- 模型评测不得保存超出原 Contract 的内容；人工评审单独授权并限时。

## 8. 提供方 Spike

每个候选提供方用同一输入集记录：中文结构化质量、端到端延迟、失败/限流、区域、训练/保留政策、删除能力、单价变量和 SDK/退出成本。没有完整 PIA 与 Eval 报告，不把提供方写入 accepted ADR。
