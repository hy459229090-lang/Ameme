Verdict: `conditional_pass`
Scope / Build / Commit: 冻结 `ameme.agent-local-node.v1` 上的 Android/Host bounded `visible_events`、SQLCipher current Event projection、exact Grant/scope/sensitivity/query/time/limit、Host Recall/Context budget 与 TLS adapter；工作区未提交状态，未形成发布提交
Gate / Spike ID: `P0-ANDROID-AGENT-READ-12`
Owner / Reviewer: Codex 主执行 Agent / Agent 最小读取、存在性、内容披露、注入与预算边界复核
Date / Environment: 2026-07-26；macOS、临时 JDK 17、Android SDK 36、Python 3.12 + 隔离依赖

# P0 Android 生产 Agent 最小读取验证

## 结论

Android 生产 Local Node 与 Host adapter 已在冻结 v1 上形成 bounded `visible_events` 最小读取闭环，
Verdict 为 `conditional_pass`：

- 读取只允许一个已绑定 Personal space、`memory_types=["event"]`、`data_classes=["structured"]`；
  `get_event` 与 `set_policy_blocked` 继续关闭；
- query 最多 1,000 个 Unicode code point，limit 为 1–100；可选绝对时间范围按 repository
  时区映射为本地 Event 边界；
- 结果只来自未删除 Event 的 current active projection，不读取不可变历史、长期 Memory、Raw 或
  reuse telemetry；
- production runtime 只声明 Public/Personal/Confidential，不授予 Restricted。可见敏感度必须同时
  落在 pairing policy 与 verified session 交集；没有 Restricted authority 的 session 也不会从
  `risk_filtered` 得知 Restricted Event 是否存在；
- query 只匹配将要披露的 title/description，不匹配 `userWords`、`sourceLabel`、source object、
  locator/path 或其他隐藏字段，避免把搜索命中变成隐藏内容 oracle；
- 每项响应只含 Event ID、space ID、`memory_type`、revision、event type、title、description、
  fact/evidence 状态、sensitivity、data class 与 `content_truncated`；title/description 分别截断到
  240/1,000 code point；
- Android Runtime 成功读取不触发写入刷新 callback；旧 pairing 缺少持久 operation policy 时仍
  fail closed 并要求重新授权，App 升级不会静默扩权；
- Host 对结果做 exact object、scope/type/数量/长度和枚举绑定，畸形或夹带字段结果会毒化
  channel；Recall/Context 继续执行 provider-injection 过滤及 item/token budget；
- Context 只多取一个有界 sentinel（最多 13 项）判断 item budget 是否耗尽，不进行无界读取。

这不是完整 Agent Beta 通过。当前证据使用本机 repository/JVM、编译后的 instrumentation source、
合成 Host/TLS peer 和静态契约；没有执行真实 Codex/Claude Code/Cursor 生产宿主、物理 Android、
真实 LAN/NSD、后台生命周期、共享账户 Grant registry、跨端撤权传播、签名或发布。

## 授权、内容与存在性边界

新 pairing 明示 Personal/structured Event 读取以及 Event/Revision 写入与撤销。Runtime 只从持久
`grant_operations` 派生宣告能力；持久策略没有 `visible_events` 时即使 Host 请求也拒绝。请求中的
`allow_high_risk` 不能扩展 verified session sensitivity，只能在 session 已经具有 Restricted authority
时允许返回 Restricted；当前 production runtime 根本不创建该 authority。

Repository 查询的 sensitivity predicate 与 Restricted existence probe 使用相同的 space、active、
query 和 time 条件。只有 verified session 自身包含 Restricted 时才计算并返回 `risk_filtered=true`；
普通 production session 固定看不到 Restricted 内容，也看不到其存在性差异。删除后的 Event 不再
进入结果；不可变 revision 与 tombstone 仍按既有审计/恢复语义保存。

返回正文只来自 title/detail 的受限 current projection。`sourceLabel` 和 `userWords` 不进入 SELECT、
FTS/LIKE query predicate 或 wire result；SourceLocator、外部 Raw、app-owned media、长期 Memory
候选、confirmation 状态和无正文 telemetry 均不属于该 capability。

## Host Recall 与 Context

Host `recall` 把 frozen read payload 映射为单次 bounded `visible_events`，结果再经过已有的内容注入
过滤。`get_context` 使用相同读取，不会绕过 Local Node policy；远端候选最多取默认 item budget
12 加一个 sentinel。过滤后输出仍执行 item/token budget，并保留 `item_budget_exhausted`、
`token_budget_exhausted` 与 provider-injection partial reason。读取成功不会创建 Event、Revision
或长期 Memory，也不会把 Local Node 内容留在 Host control record。

## 可复跑证据

### Android

```text
JAVA_HOME=/private/tmp/ameme-jdk17-20260725/Contents/Home
ANDROID_HOME=/private/tmp/ameme-toolchains-20260726/android-sdk
./gradlew :app:testDebugUnitTest :app:lintDebug \
  :app:compileDebugAndroidTestKotlin :app:assembleDebug --no-daemon
```

- 结果：`BUILD SUCCESSFUL`；Debug JVM `96/96`，0 failed / 0 error / 0 skipped；
- Lint：0 error、31 warning、1 hint；均为版本、资源或既有同步持久化建议，无阻断项；
- `compileDebugAndroidTestKotlin` 通过；
- Debug APK：`41,085,221` bytes；
- JVM 覆盖 canonical envelope/result、scope/operation、current/delete、sensitivity/Risk 无泄漏、
  query/time/limit、截断和隐藏字段不可搜索；
- SQLCipher instrumentation source 覆盖 Personal/Confidential/Restricted、query/time/limit、
  sensitivity、删除、关闭重开与隐藏 source label 不可搜索；本轮只编译，未在 AVD 或物理设备执行。

### Host、Core 与 TLS

```text
PYTHONPATH=/private/tmp/ameme-python312-workspace-deps \
python3.12 -m unittest discover \
  -s services/ameme-mcp-mock/tests -p "test_*.py" -v
PYTHONPATH=/private/tmp/ameme-python312-workspace-deps \
python3.12 -m unittest discover -s tests/agent -p "test_*.py" -v
```

- Android Local Node Host adapter/TLS：`29/29`；
- Agent lifecycle/policy/Core store integration：`35/35`；
- 覆盖 read-only channel、strict payload/result、恶意结果 poison、MCP Recall/Context、injection 与
  item/token budget、目标读取保持关闭，以及 TLS 1.3/pin/HMAC/session/sequence 下的
  `visible_events`；
- TLS peer 为本机合成服务，不是第三方生产宿主或物理 Android。

### 静态契约与统一工作区

```text
PYTHONPATH=/private/tmp/ameme-python312-workspace-deps \
python3.12 scripts/validation/validate_android_agent_read_contract.py
PYTHONPATH=/private/tmp/ameme-python312-workspace-deps \
python3.12 scripts/validation/run_workspace_validation.py
```

- bounded `visible_events`：`108/108`；
- Revision 回归：`85/85`；exact Event/Revision undo 回归：`117/117`；
- 统一工作区：`25/25` Gate；其中 Core `47/47`、Agent `35/35`、Android Agent adapter
  `29/29`、Markdown links `43`、workspace governance `168`，均 0 error；
- 静态输出明确 `get_event_claim=false`、`set_policy_blocked_claim=false`、
  `raw_or_locator_disclosure_claim=false`、`restricted_runtime_claim=false`、
  `long_term_memory_confirmation_claim=false`、`real_host_execution_claim=false`、
  `physical_device_execution_claim=false`、`shared_account_grant_claim=false`、
  `release_claim=false`。

## 首次失败与修复

| 首次失败 | 根因 | 处理 | 未解决影响 |
|---|---|---|---|
| 从 Android 子目录运行根目录相对路径且当前 shell 缺少 Java | 命令工作目录与工具链环境错误 | 回到仓库根并显式使用临时 JDK 17 | 临时工具链不是发布签名/真机环境 |
| 设置 JDK 后 Gradle 报 Android SDK location missing | 当前 shell 没有 `ANDROID_HOME` | 使用仓库既有隔离 Android SDK 36 | 不影响仓库编译；不关闭设备 Gate |
| 系统 Python 3.9 的 OpenSSL 不支持测试要求的 TLS 1.3 API | 本机系统解释器能力不足，既有 TLS case 同样在握手前失败 | 使用 workspace Python 3.12 / OpenSSL 3.5 与隔离依赖 | 正式 CI/Host 仍需固定可复现 runtime |
| instrumentation 新 helper 首次编译报 suspend 调用错误 | 本地辅助函数漏标 `suspend` | 将 helper 标为 `suspend` 后重编译通过 | instrumentation 仍只编译、未设备执行 |
| 首次把远端 Context limit 收紧为 exact item budget 后既有 partial-reason case 回归 | exact limit 无法区分“恰好填满”与“仍有候选”，且 injection 过滤会消耗候选 | 改为固定最多 item budget + 1 的 bounded sentinel，生命周期回归通过 | 不产生无界读取；真实 Host 体验仍待验 |
| 首次以 workspace Python 3.12 跑统一门禁时缺少 PyYAML | 隔离依赖路径未注入 | 设置 `/private/tmp/ameme-python312-workspace-deps` 后重跑 | 正式 CI 需声明依赖 |
| 静态复核发现 query 会匹配未披露的 `sourceLabel/userWords` | 搜索 predicate 比 wire disclosure 范围更宽，构成命中 oracle | SQL/FTS/LIKE 只匹配 title/detail，并新增 JVM、instrumentation 与 SQLite FTS5 回归 | 真实设备 SQLite/输入法边界仍待验 |
| 首次统一工作区在治理 Gate 报当前最小读取报告不存在 | 索引先引用报告、报告尚未落盘 | 补齐本独立报告后重跑统一工作区 | 无产品代码影响 |

失败证据没有被删除或改写为通过。

## 外部 Gate

保持 `hold`：

- 至少一个真实第三方 Agent 宿主 + 物理 Android，通过无 ADB forward 的真实 LAN/TLS 通道执行
  bounded Recall/Context；
- 验证真实 query/timezone/large dataset、Restricted/Confidential、删除、进程终止、后台、断网、
  Grant 过期/撤销与重新配对；
- 共享账户 Grant registry、跨设备撤权传播与 Host control record 删除/审计；
- 物理设备 SQLCipher instrumentation、OEM/16 KB/低存储/锁屏/进程死亡；
- 真实用户对读取授权、截断、风险过滤、上下文来源和撤销的理解；
- `get_event` 或策略写入如需开放，必须建立独立最小授权与审计协议；
- 签名内部发行、商店/供应商申报、事故演练、回滚与发布负责人签收。
