# Ameme MVP 成本、容量、SLO 与可观测性基线 v0.2

> 文档状态：已接受；计算模型、规划负载、性能预算和无内容观测为研发基线，价格/真机达成待 Spike\
> 更新日期：2026-07-14\
> 指标正本：`../product/MVP指标与埋点字典.md`\
> 说明：表中负载和预算是研发压测档位/设计约束，不是已观测用户行为或供应商承诺。

## 1. 规划负载档位

| Profile | Events/day | SourceObjects/day | Photo refs/day | Audio min/day | Agent recalls/day | Retention use |
|---|---:|---:|---:|---:|---:|---|
| L | 20 | 40 | 5 | 1 | 2 | 新用户/稀疏日 |
| M | 80 | 160 | 20 | 5 | 10 | 首批知识工作者规划档 |
| H | 300 | 600 | 60 | 20 | 40 | 压力档，不代表目标平均 |
| X | 1,000 | 2,000 | 200 | 60 | 100 | 防失控/降级测试，不承诺完整功能 |

数据库/Recall 基准另使用 1k、10k、100k、1M Event 合成库。MVP 最低设备的通过档位由真机 Spike 批准；H/X 用于验证分页、限流和不会数据丢失。

## 2. 单位成本变量

| Variable | 单位 | 来源 |
|---|---|---|
| `P_struct_gb_month` | 结构化 GB/月 | MVP 为设备存储资源量，供应商账单价固定为 0；未来数据云另立决策 |
| `P_blob_gb_month` | Raw GB/月 | MVP 为设备存储资源量，供应商账单价固定为 0 |
| `P_egress_gb` | LAN 传输 GB | 无云 egress 账单，价格固定为 0；仍测网络、电量和同步耗时 |
| `P_stt_min` | 音频分钟 | 转写提供方 |
| `P_ocr_image` | 图片/页 | OCR 提供方 |
| `P_model_in/out` | 百万 token | 各模型档 |
| `P_embed_token` | 百万 token | 可选 embedding |
| `P_job_million` | 百万本地任务 | 无云 worker 账单，价格固定为 0；仍测 CPU/电量/队列积压 |
| `P_identity_user_month` | 活跃账户/月 | 账户身份服务；只处理身份/设备元数据，不保存记忆内容 |
| `P_support_hour` | 支持小时 | 内部完全成本 |

LAN/本地项目的结构化存储、Raw、传输和任务价格在 MVP 输入中为 0，但对应 GB、传输量、CPU、电量和磁盘容量必须继续输出，不能把“无云账单”写成“无成本”。身份和模型供应商未选前不填虚构单价；选型报告记录日期、区域、免费额度、税费、最低消费和退出成本。

## 3. 可计算公式

```text
StructuredGB_user_month =
  (events * avg_event_bytes
   + revisions * avg_revision_bytes
   + observations * avg_observation_bytes
   + audit_rows * avg_audit_bytes
   + index_overhead_bytes) / 1e9

SelectedRawGB_user_month = selected_raw_bytes_retained / 1e9

ModelCost_user_month =
  stt_minutes * P_stt_min
  + ocr_images * P_ocr_image
  + input_tokens_million * P_model_in
  + output_tokens_million * P_model_out
  + embedding_tokens_million * P_embed_token

InfraCost_user_month =
  StructuredGB * P_struct_gb_month
  + SelectedRawGB * P_blob_gb_month
  + EgressGB * P_egress_gb
  + JobsMillion * P_job_million
  + P_identity_user_month

FullyLoadedCost = ModelCost + InfraCost + allocated_support + optional monitoring
GrossMargin = (net_revenue - FullyLoadedCost) / net_revenue
```

成本输出必须同时给 L/M/H、P50/P95 和来源/任务拆分；平均值不能掩盖音频/Raw/大模型重用户。

## 4. 预算控制点

| 控制 | 默认行为 | 触发降级 |
|---|---|---|
| 原始数据 | 本地短恢复；选定对象才同步 | 空间/网络超预算时暂停新 Raw sync，不删已保存事实 |
| OCR/STT | 用户显式内容优先，系统/端侧优先 | 配额不足保留原始并排队/让用户补文字 |
| 模型 | R0/R1 优先，R2/R3 只处理高价值或难例 | 日/月预算或 provider 限流时保持 Candidate/无 Summary |
| Embedding | 默认关闭 | 词法/结构化 Recall 未达到预登记任务才 Spike |
| Summary | 一天最多按 Revision 变化触发合并重算 | 高频修改去抖；数据不足不生成 |
| Agent | page size、ContextPack item/token/expiry 限制 | 超范围收窄/分页，不返回整个数据库 |

## 5. 研发性能预算

以下是 Prototype 的暂定设计预算，不是已通过证据；最低设备和数据档位确定后可以收紧/调整，但必须保留“本地先可用”。

| SLI | 暂定预算 | 负载/测法 |
|---|---:|---|
| Today 首个可滚动帧 cold P95 | <= 1,200 ms | M 档本地库、最低候选设备、无网络依赖 |
| Today 首个可滚动帧 warm P95 | <= 500 ms | 同上 |
| 主动补充本地 durable ack P95 | <= 300 ms | 文字/manifest；大媒体复制单独量 |
| DayLedger 30 天向上分页 P95 | <= 300 ms | 100k Event 本地库 |
| 本地关键词首屏 P95 | <= 700 ms | 100k Event，中文基准集 |
| Event Revision commit P95 | <= 300 ms | 含 DayLedger stale/outbox 事务 |
| 长列表慢帧比例 | < 5% | 两端平台 frame metric，M/H 日流 |
| LAN peer 单次同步收敛 P95 | <= 5,000 ms 候选 | 两台已配对设备同网、M 档增量；后台不可用单独标记 |
| 账户身份 API availability | >= 99.5% 候选 | 只影响登录/加设备，不阻塞已登录设备本地 Today |
| 保存后数据丢失 | 0 confirmed case | 崩溃/进程杀/磁盘/断网故障注入 |

电量、后台唤醒、内存和安装体积不预填“看起来合理”的数字；真机先测 baseline，再由最低版本/设备档位登记上限。持续位置若超过预算，优先降低 A3 获取频率而非隐藏资源影响。

## 6. SLO 与错误预算

封闭 MVP 先建立 SLI，不把文档数字当生产承诺：

| Journey | SLI | Good event | Hard exclusion |
|---|---|---|---|
| 保存 | durable capture | ack 后对象可恢复 | 任一 ack 后丢失阻断 |
| Today | local ready | 本地内容在预算内可滚动 | 网络/AI 等待不计为 good |
| Recall | authorized result | 预算内返回 + range_state | 越权结果永不进入 good |
| Sync | convergence | 允许时间内 cursor/Revision 收敛 | tombstone 复活阻断 |
| Delete | proof completion | 所有可达副本/派生 ack | 假完成阻断 |
| Model | valid grounded output | Schema+evidence+policy 全过 | 无证据事实/泄漏阻断 |

安全、数据丢失和假删除没有可消费错误预算。普通可用性错误预算用来决定暂停放量、回滚或降级；不能用更高吞吐抵消信任事故。

## 7. Trace、Metric、Log

### 7.1 Trace

每个 SourceObject/命令生成随机 `trace_id`，贯穿：

```text
acquire -> persist -> parse -> candidate -> revision -> ledger
        -> sync -> recall/context -> feedback
        -> delete/recompute/proof
```

Span 只含匿名 ID、source/task type、space class、版本、状态码、大小/数量/耗时桶、重试和 processing location。Raw/正文/query/精确位置/健康值禁止进入 attributes。

### 7.2 Metrics

- 产品遥测使用 `MVP指标与埋点字典.md`，可关闭且与内容库身份分离。
- 运行指标包括 queue age/depth、job success/retry、DB latency/size、sync gap、index freshness、model route/valid/upgrade、delete pending/proof、API latency/error。
- Restricted space 默认只保留客户端本地诊断；获准上传的粗聚合不含对象级 ID 或记忆内容。

### 7.3 Logs

使用结构化 allow-list logger；禁止 dump request/response、provider exception 原文、SQL bind、文件路径、系统联系人、URL/title 和 token。Error details 在本地先 redaction，上传支持包前用户预览范围。

## 8. 告警与降级

| Signal | Severity | 自动动作 | 人工动作 |
|---|---|---|---|
| unauthorized disclosure / wrong space | SEV0 | 关闭相关调用/Grant 路由 | 安全响应、范围与删除 |
| saved data loss / tombstone resurrection | SEV0 | 停止放量和相关写路径 | 数据恢复、根因、回归 |
| deletion false completion | SEV0 | UI 降为 proof incomplete | 扫描副本/索引，修复证明 |
| model policy/grounding failure spike | SEV1 | 降级 R0/R1、回滚 prompt/model | Eval 差分和原因分类 |
| sync/index backlog | SEV1/2 | 限流后台、保持 local/partial | 容量/版本诊断 |
| cost per active user 超预算 | SEV2 | 关闭非关键 R3/embedding、限 Raw sync | 调整路由/定价，不删用户数据 |

## 9. 容量与成本 Gate 输出

Gate 3 前必须填：最低设备/版本、L/M/H 本地基准、LAN 同步资源量、模型/身份提供方变量和封闭用户上限。Gate 4/5 必须提供可重算 CSV/JSON、负载脚本、Trace/Metric 查询和单用户/重用户成本分布。没有这些数据时结论只能是 `needs_spike`，不能写“成本可控”。

当前已提供可执行的相对成本夹具，用于验证公式和替换真实报价：

```powershell
python scripts/analysis/calculate_mvp_cost.py
python scripts/analysis/calculate_mvp_cost.py --input <approved-pricing.json>
```

`tests/fixtures/cost/mvp-cost-planning.json` 明确标记为 relative planning units；任何对外价格/毛利结论必须使用带日期和供应商证据的独立输入，不能引用该夹具数值。
