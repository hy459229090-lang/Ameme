Verdict: `conditional_pass`
Scope / Build / Commit: 冻结 `ameme.agent-local-node.v1` 上的 Android/Host exact Event/Revision `undo_capture`、10 分钟首次时窗、SQLCipher 持久幂等与 Host control token 映射；工作区未提交状态，未形成发布提交
Gate / Spike ID: `P0-ANDROID-AGENT-UNDO-11`
Owner / Reviewer: Codex 主执行 Agent / Agent 撤销、授权、持久化、恢复与存在性边界复核
Date / Environment: 2026-07-26；macOS、临时 JDK 17、Android SDK 36、Python 3.12 + 隔离依赖

# P0 Android 生产 Agent Event/Revision 撤销验证

## 结论

Android 生产 Local Node 与 Host adapter 已在冻结 v1 payload/result 上形成 exact Event/Revision
`undo_capture` 仓库闭环，Verdict 为 `conditional_pass`：

- 原 capture 返回的域分离幂等 slot 同时作为 Host control undo token；撤销严格绑定相同
  caller/grant/purpose/space/type，Host 内部 undo record、正文和 snapshot 不跨 TLS channel；
- 首次撤销必须早于原写入持久幂等记录创建后 10 分钟；已成功撤销的完全相同请求即使在时窗后
  或 repository 关闭重开后仍重放相同终态；
- Event 撤销只追加 tombstone，不删除历史 Revision；
- Revision 撤销只在被撤销 Revision 仍为 current head 时成功，并追加恢复前一 active immutable
  revision 内容的 compensation revision；后续已有新 head 返回 `REVISION_CONFLICT`，不覆盖历史；
- 撤销 mutation、搜索/DayLedger 刷新、Event-bound 长期 Memory 失效和撤销幂等记录处于同一
  SQLCipher transaction；幂等表不保存正文或恢复 snapshot；
- token 不存在、过期、type 不匹配、目标已删除或 sensitivity 不可见均折叠为 `NOT_VISIBLE`；
- 新 pairing 明示 operation policy；旧持久 pairing 缺少 `grant_operations` 时 fail closed 并撤销，
  必须重新授权，不能因 App 升级静默获得撤销能力；
- Host 对 Event/Revision 撤销结果做 exact object binding；Revision 必须返回不同于原 revision 的
  compensation ID，恶意/畸形结果会毒化 channel；
- read/ContextPack/Recall、policy mutation 与长期 Memory confirmation 没有随撤销开放。

这不是完整 Agent Beta 通过。真实 Codex/Claude Code/Cursor 生产宿主、共享账户 Grant registry、
物理 LAN/NSD、Android 后台生命周期、跨端撤销传播、设备纵向执行、物理设备、真实用户、签名和
发布仍是独立 Gate。

## 数据、恢复与审计语义

### Event

首次 exact undo 在同一外层 transaction 内定位原 `create_event` 幂等行、校验 10 分钟时窗与当前
active revision `1`，再调用 repository tombstone。普通读取、Search 和 DayLedger 不再显示目标，
Event-bound 长期 Memory 失效；不可变历史与删除水位保留。相同 undo slot/digest 重放读取持久终态，
不会追加第二个 tombstone。

### Revision

首次 exact undo 从原 `append_revision` 幂等行重建 immutable revision ID，并要求它仍是 current
head。Repository 读取前一 active revision，追加 reason=`agent_revision_undo` 的补偿 revision，
更新 current projection、搜索和 DayLedger，终结当前字段证据，但不伪造恢复后的 provider evidence。
相同 undo 重放返回同一 compensation revision ID；目标之后已有新 head 时零 mutation 并返回
`REVISION_CONFLICT`。

### 持久控制面

`agent_idempotency` 继续只保存 caller/grant/purpose/space/type/operation/slot、payload digest、
Event ID、revision 和时间。撤销不会保存正文、previous-event snapshot、SourceLocator、secret 或
Host control record。首次时窗使用原 SQLCipher 行的创建时间与 endpoint 注入时钟；相同撤销的持久
重放先验证 digest，再返回已保存终态，不被时钟变化误判成第二次 mutation。

## 可复跑证据

### Android

```text
JAVA_HOME=/private/tmp/ameme-jdk17-20260725/Contents/Home
ANDROID_HOME=/private/tmp/ameme-toolchains-20260726/android-sdk
./gradlew :app:testDebugUnitTest :app:lintDebug \
  :app:compileDebugAndroidTestKotlin :app:assembleDebug --no-daemon
```

- 结果：`BUILD SUCCESSFUL`；Debug JVM `92/92`，0 failed / 0 error / 0 skipped；
- Lint：0 error、30 warning、1 hint；其中两处 `ApplySharedPref` 是配对元数据同步持久化边界，
  其余为版本/资源建议，均无阻断项；
- `compileDebugAndroidTestKotlin` 通过；
- Debug APK：`41,084,265` bytes；
- instrumentation source 覆盖 Revision compensation/恢复前内容与证据边界/关闭重开同 ID 重放，
  以及 Event tombstone/关闭重开/不复活；本轮未启动 AVD 或物理设备，不能写成设备执行通过。

### Host 与 Core control

```text
python3.12 -m unittest -v \
  services.ameme-mcp-mock.tests.test_android_local_node_host_command \
  services.ameme-mcp-mock.tests.test_android_local_node_store \
  services.ameme-mcp-mock.tests.test_server_android_backend \
  services.ameme-mcp-mock.tests.test_tls_android_local_node_channel
python3.12 tests/agent/test_core_store_integration.py
```

- Android Local Node Host adapter/TLS：`26/26`；
- Core store integration：`8/8`；
- 覆盖 Event/Revision capture→undo、undo-only channel、内部 control record 不跨 channel、恶意结果
  拒绝、TLS 1.3 Event undo、read capability 保持关闭。

### 静态契约

```text
python3.12 scripts/validation/validate_android_agent_revision_contract.py
python3.12 scripts/validation/validate_android_agent_undo_contract.py
```

- Revision 回归：`85/85`；
- exact Event/Revision undo：`117/117`；
- 输出明确 `read_context_or_recall_claim=false`、`physical_device_execution_claim=false`、
  `real_host_execution_claim=false`、`shared_account_grant_claim=false`、`release_claim=false`。

统一工作区最终 `24/24` Gate 通过；其中 Android Agent adapter `26/26`、Markdown links `43`、
workspace governance `166`，均 0 error。

## 首次失败与修复

1. Android 首次编译发现 operation 字段误加到 canonical `AgentAccessGrant`，而非 pairing-local
   `AgentAccessGrantPolicy`。修复为只扩展本地 policy，冻结 canonical Grant 不变；重跑通过。
2. JVM 首次运行发现测试常量 `SECOND_UNDO_SLOT` 只有 63 位十六进制。修正为 v1 要求的
   64 位 token；重跑 `92/92`。
3. undo 静态门首次把 JSON Schema 的 `$ref` 误当作直接 enum。修正验证器按引用结构核对；
   重跑通过。
4. undo 静态门第二次依赖脆弱的单行 marker，而实现为等价多行 Kotlin。改为结构化的多个
   独立 marker；重跑 `117/117`。
5. Host 聚合命令首次把非 package 的 `tests.agent` 当作 unittest module；已执行的 20 个 Host
   case 均通过但命令以 import error 退出。改为四个真实 Host module 后 `26/26`，并单独执行
   Core integration `8/8`；无代码失败或证据冒充。
6. 统一工作区首次在第 24 个治理 Gate 发现仓库根既存未跟踪 `outputs/`，其中两份 Xcode
   `.xcresult/database.sqlite3` 命中敏感文件规则。确认 19 MB 目录无进程占用后，完整移至仓库外
   同工作区 `ameme-untracked-evidence-20260726-191150/`，未删除证据；复跑 workspace
   `24/24`、治理 `166` 通过。

## 外部 Gate

保持 `hold`：

- 至少一个真实第三方宿主 + 物理 Android，通过无 ADB forward 的真实 LAN/TLS 通道执行独立
  Event 撤销与 Revision 补偿撤销；
- 验证 10 分钟过期、后续 head conflict、进程终止/后台、断网、Grant 过期/撤销、重配对；
- 共享账户 Grant registry 与跨设备撤销传播；
- 物理设备 SQLCipher instrumentation、OEM/16 KB/低存储/锁屏/进程死亡；
- read/ContextPack/Recall 的独立最小授权协议；
- 真实用户可理解的 undo 展示、签名内部发行、商店与发布负责人签收。
