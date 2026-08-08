Verdict: `conditional_pass`
Scope / Build / Commit: 冻结 `ameme.agent-local-node.v1` 上的 Android/Host `append_revision`、exact Grant、SQLCipher 原子 Revision 与持久幂等、敏感目标隐藏、MCP/TLS adapter；工作区未提交状态，未形成发布提交
Gate / Spike ID: `P0-ANDROID-AGENT-REVISION-10`
Owner / Reviewer: Codex 主执行 Agent / Agent 写入、授权、持久化与存在性边界复核
Date / Environment: 2026-07-26；macOS、临时 JDK 17、Android SDK 36、Python 3.12 + 隔离依赖

> 后续能力说明：本报告保留 Revision 切片完成当时的真实边界与计数；随后 exact Event/Revision
> `undo_capture` 已由独立报告 `P0-Android生产Agent-EventRevision撤销验证-20260726.md`
> 收敛。下文“undo 关闭”是历史切片结论，不应再当作当前能力声明。

# P0 Android 生产 Agent Revision 写入验证

## 结论

Android 生产 Local Node 原先“共享协议已冻结 `append_revision`，但端点只实现 `create_event`”的仓库缺口已收敛，Verdict 为 `conditional_pass`。

现在已形成 write-only `create_event` + `append_revision`：

- `append_revision` 只接受 frozen v1 的 `event_id/space/revision/content/evidence_state/fact_status/now`；
- 请求必须有最小 `revision` scope，且同时落在 pairing policy、verified session 与 repository space 内；
- SQLCipher 同一 transaction 追加 immutable revision、更新 current projection、持久 idempotency、终结旧字段证据、使 Event-bound 长期 Memory 失效、刷新搜索和 DayLedger；
- 同 slot/同 digest 在 repository 关闭重开后返回原 exact revision ID，不产生第二次 mutation；异 digest 冲突；
- 目标不存在、已删除或 sensitivity 不可见统一返回 `NOT_VISIBLE`，不回显 ID、正文或存在性差异；
- Host adapter/MCP capture/TLS 1.3 channel 接受严格的 revision result，target ID 必须与请求一致，额外/恶意字段会毒化会话；
- `get_event`、`visible_events`、`set_policy_blocked`、`undo_capture`、ContextPack/Recall 与长期 Memory confirmation 均未开放。

这不是完整 Agent Beta 通过。真实 Codex/Claude Code/Cursor 生产宿主、共享账户 Grant registry、物理 LAN/NSD、后台生命周期、撤权跨端传播、设备纵向 Revision、物理设备和真实用户仍是独立 Gate。

## 授权与兼容边界

新 pairing 的用户授权卡片和本地 policy 明示 `Personal · autonomous_memory · structured event/revision · 30 天`。Runtime 不按全局常量宣告能力，而是从持久 policy 派生：

- policy 有 `event` 才宣告 `create_event`；
- policy 有 `revision` 才宣告 `append_revision`；
- 旧 pairing 已持久化的 event-only policy 继续只宣告 `create_event`，不会因 App 升级静默扩权；
- append-only Host channel 可只声明 Revision，不被迫取得 Event create authority。

Android 的 current pairing 仍以本地 pairing policy 为 MVP 信任根，不是共享账户 Grant registry。Host MCP 的 autonomous Grant 仍按单用途 exact type 发放；测试分别批准 Event Grant 和 Revision Grant，没有把一个广 Grant 冒充两个 exact consent。

## 数据与恢复语义

Agent Revision 不调用 UI 的普通 `updateEvent`，而使用专用 repository transaction：

1. 在当前 repository space 内读取 active Event；已删除与缺失均不可见。
2. 在事务内校验当前 Event sensitivity 属于 verified session 允许集合。
3. 只更新 `detail/factStatus/evidenceState/revision`，保留 Event ID、title/type/source/sensitivity、日期与原始来源关系。
4. 向 append-only `event_revisions` 写 `agent_revision`，再更新 current projection。
5. 终结旧 exact-revision 字段证据；Agent 不伪造新的 provider/source-object 字段证据。
6. 使 exact Event-revision-bound 长期 Memory 失效，但不创建或确认 Memory。
7. 更新 FTS/LIKE 派生索引与 DayLedger。
8. 在同一外层 transaction 写 `agent_idempotency`；失败整体回滚。

幂等表继续只保存 caller/grant/purpose/space/type/operation/slot、payload digest、Event ID、revision 和时间，不保存正文。重放时从不可变 `event_revisions` 按 Event + revision 找回真实 revision ID；找不到会失败关闭，不制造替代 ID。

## 自动验证

### Android

```text
env JAVA_HOME=/private/tmp/ameme-toolchains-20260726/jdk17/Contents/Home \
  ANDROID_HOME=/private/tmp/ameme-toolchains-20260726/android-sdk \
  ANDROID_SDK_ROOT=/private/tmp/ameme-toolchains-20260726/android-sdk \
  ./gradlew :app:testDebugUnitTest :app:lintDebug \
    :app:compileDebugAndroidTestKotlin :app:assembleDebug --no-daemon
```

结果：`BUILD SUCCESSFUL`；Debug JVM 88/88、0 failure、0 skipped，Lint 无阻断项，41,084,137-byte Debug APK 构建完成，全部 androidTest 源码编译通过。新增 SQLCipher instrumentation 覆盖 create→append→关闭→重开→同 revision ID 重放，但本轮没有启动 AVD 或物理设备，因此不能写成 SQLCipher 设备测试通过。

### Host adapter 与 TLS

```text
env PYTHONPATH=/private/tmp/ameme-python312-workspace-deps \
  python3.12 -m unittest discover \
    -s services/ameme-mcp-mock/tests -p "test_*.py" -v
```

结果：23/23 通过。覆盖：

- MCP `capture(event)` 与独立 exact Revision Grant 的 `capture(revision)` 都进入注入的 Android channel，无 Core/JSON fallback；
- request scope 只含单 space + `revision`，原 idempotency key 不进入 wire control；
- append-only capability 可用，read/undo 即使被 channel 多报也不执行；
- extra field、错误 object type、非法 revision ID、错误 target、bool/过小 revision 均拒绝并毒化 channel；
- TLS 1.3/certificate pin/HMAC/session/sequence 的 `append_revision` 端到端合成 channel 通过，密钥、原 key 与正文不进入对象诊断。

这些测试使用本机合成 TLS peer，不是第三方真实宿主或物理 Android。

### 静态最小能力 Gate

```text
python3.12 scripts/validation/validate_android_agent_revision_contract.py
```

结果：85/85，通过范围为 `static_android_agent_append_revision_only`；输出明确：

- `read_context_or_recall_claim=false`
- `undo_claim=false`
- `long_term_memory_confirmation_claim=false`
- `real_host_execution_claim=false`
- `physical_device_execution_claim=false`
- `shared_account_grant_claim=false`
- `release_claim=false`

统一 workspace Gate 23/23 通过；其中 Android Agent adapter 23 项、治理 164 项，其余既有 Gate 同轮回归均为 0 error。

## 失败记录

| 首次失败 | 根因 | 处理 | 未解决影响 |
|---|---|---|---|
| 直接运行 Gradle 报找不到 Java Runtime | 当前 shell 没有 `JAVA_HOME` | 使用仓库既有临时 JDK 17 | 临时工具链不是发布签名/真机环境 |
| 仅设置 JDK 后 Gradle 报 Android SDK location missing | 当前 shell 没有 `ANDROID_HOME` | 使用仓库既有隔离 Android SDK 36 | 不影响仓库编译；不关闭设备 Gate |
| 首次 Host 全量 discovery 缺少 `cryptography` | 直接 Python 3.12 未注入隔离 workspace deps | 以 `/private/tmp/ameme-python312-workspace-deps` 作为 `PYTHONPATH` 重跑 | 依赖环境需由正式 CI/发布机复现 |
| MCP Event 使用 event+revision 广 Grant 返回 `CONSENT_REQUIRED` | MCP autonomous control 面要求单用途 exact type，而 Local Node transport 允许 verified Grant 为最小请求 scope 的超集 | 测试按产品边界分别取得 Event Grant 与 Revision Grant；Android 本地 pairing policy 仍作为最大 scope | 共享账户 Grant registry 与双控制面统一仍是外部门 |

失败证据没有被删除或改写为通过。

## Gate 边界

| Gate | 当前结论 |
|---|---|
| frozen schema 与 canonical codec | pass |
| Android JVM application/repository invariants | pass |
| Host MCP/TLS adapter | pass（合成 peer） |
| Android SQLCipher instrumentation source | compiled only |
| 历史 AVD paired smoke | 只覆盖 `create_event`，不覆盖 Revision |
| 真实第三方 Host | hold |
| Android 物理设备 / OEM / 后台 / 物理 LAN | hold |
| 共享账户 Grant 与撤权传播 | hold |
| read/ContextPack/Recall/undo | unsupported |
| 长期 Memory confirmation | unsupported |
| 签名 / 商店 / 封闭 Beta 发布 | hold |

## Verdict

`conditional_pass`：仓库内 Android Agent exact-Grant Revision 写入、持久幂等、敏感目标隐藏和 Host adapter 已实现并有自动化证据；设备、真实宿主、共享账户授权、读取/撤销、后台与发布 Gate 没有被合成或静态结果替代。
