# Ameme MCP local mock

本目录提供 Agent 入口第一版的无云 MCP 服务。默认运行方式使用本地 JSON 合成夹具，不包含登录、生产鉴权、记忆云、外部模型或第三方 Agent 市场能力。

## 存储边界

- `EventNodeStore` 是 Mock 业务层与 Event/Revision 持久化之间的明确接口。`AmemeMock` 仍负责 pairing、Grant、exact scope、撤权/过期、风险策略、反馈、ContextPack、活动和 MCP 幂等；Store 只在授权成功后接收请求。
- 每次 Store 调用携带已授权请求范围形成的 `EventNodeScope`，适配器再次核对 owner、space 和 memory type。Scope 不写入 Event/Revision 正文，也不能代替 Mock 的授权判断。
- `JsonStore` 仅是保留现有行为的 **非生产合成夹具后端**，不是移动端数据库、服务端数据库或发布候选实现。
- `CoreEventNodeStore` 仅用于合成集成测试。它只调用 `packages/core-reference` 的公开命令，把 capture/revision/undo 映射为 Source → Observation → Event/Revision → Tombstone/Lineage，并让离线交付进入 Core durable sync queue；它不直写 SQLite。
- `CoreOracleHostReferenceStore` 提供首个可运行的进程边界：MCP 进程保留 Grant/Policy/ContextPack/Activity 控制面，显式启动 `core_oracle_host.py` 子进程后，用 `ameme.core-oracle-host.v1` JSONL stdio 访问同一个 `CoreEventNodeStore`。这是公开代码的 developer preview / 集成骨架，不是生产 Native Core。
- `AndroidLocalNodeStore` 是面向 Android Local Node 的发布候选 **adapter 边界**。`TlsAndroidLocalNodeChannel` 提供 Host 侧主动发起的 TLS 1.3 客户端：先校验配对时冻结的证书 SHA-256 pin，再以配对密钥完成 HMAC 握手、设备/会话绑定和递增 sequence/nonce 帧校验。Android App 已有对应 Kotlin listener、配对 UI/凭据生命周期和 SQLCipher 持久幂等；它不提供明文 TCP，也不会回退到 JSON fixture 或 Python CoreOracle。NSD/LAN 自动发现、后台生命周期、物理设备和共享账户 Grant registry 仍未实现。
- Core revision 的 control undo 记录只保存 event/space/revision/source lineage 等最小标识，不复制旧 title/description；只有 `JsonStore` 合成夹具为了兼容原有内存补偿行为保留旧快照。
- Python `CoreOracle` 仍是非生产语义参考和测试 Oracle，不能作为 Android、iOS、桌面或云端生产 runtime，也不证明 SQLCipher、Keychain/Keystore、真实同步或宿主集成完成。

宿主 adapter 对调用方 idempotency key 再做一次 `core-oracle-host-wire` 域隔离后才跨进程；控制面和 Oracle 操作日志均不保存正文或原始 key。子进程 stdout 只允许协议响应，stderr 被丢弃以避免日志正文和无人消费管道反压。客户端使用后台 reader 与默认 5 秒响应 deadline；挂起时终止并回收子进程，畸形响应、进程崩溃和 request-id/protocol 不匹配均 fail closed。MCP 将此类失败收敛为不含异常正文、可重试的 `LOCAL_NODE_UNAVAILABLE`，不会让单次 tool call 直接击穿 stdio server。

Core SQLite 与 Mock control JSON 不是同一事务。测试适配器不伪造跨库原子性，而是在 control 中保存不含正文的操作日志：`prepared/retry_pending/failed → core_committed`。日志只含语义哈希、对象 ID、revision/source lineage、尝试次数和对账状态。重启后使用同一 idempotency key 重放 Core 公共命令；Core 成功前不会写最终 MCP 幂等成功记录。永久领域错误标记 `failed`，可重试的 SQLite 运行错误标记 `retry_pending`，且每个 key 独立，不阻塞同一 scope 的其他操作。

调用方传入的 idempotency key 也视为潜在敏感输入：进入 control key、Core 命令键、操作日志或 SourceLocator 前，统一转换为带用途域隔离的 SHA-256 slot，不持久化原值。该摘要只用于稳定重放和冲突检测，**不是身份、授权、签名或防伪凭据**；所有访问仍必须先通过 Grant 和 exact-scope 校验。

## 能力和安全边界

- stdio MCP：`pair`、`capture`、`recall`、`get_context`、`feedback`、`status`。
- Grant 每次按 caller、purpose、space、memory type、有效期取交集；不支持通配 Grant。
- 隐式读取、自动 capture 和直接 Event/Revision 仅接受与请求精确匹配的单用途 `autonomous_memory` Grant。
- 用户陈述写为 `user_asserted`，模型推断写为 `inferred`，直接工具证据写为 `observed`；文件名或 `done/final` 文本不会自动升级证据。
- Raw、Restricted、跨空间、范围扩张和删除不会静默执行。自动模式直接阻断 Raw/Restricted；显式模式要求独立高风险确认。删除只返回专用删除流程错误。
- capture 本地原子持久化，明确区分 `persistence_state=durable`、`delivery_state=queued|local_only` 和 `synced=false`；提供活动记录与 10 分钟 undo。
- ContextPack 15 分钟过期并绑定 caller/purpose/space/grant；正文标记为不可信数据，疑似提示词注入从上下文中过滤。
- revoke 立即阻断新访问并使已有 ContextPack 失效；重放配对 challenge 被拒绝，写入通过 idempotency key 去重。
- 活动记录只保存调用方、用途、空间、类型、结果码、数量桶和时间，不保存正文、查询或 Prompt。

## 启动

从仓库根目录执行：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/dev/agent/start_mcp_mock.ps1
```

也可以直接启动并指定忽略目录及合成夹具：

```powershell
python services/ameme-mcp-mock/server.py `
  --data-dir .tmp/ameme-mcp-mock `
  --seed-file tests/fixtures/agent/synthetic-memories.json
```

显式启用跨进程 CoreOracle 参考宿主（不接受 seed 文件）：

```powershell
python services/ameme-mcp-mock/server.py `
  --data-dir .tmp/ameme-mcp-core-host-preview `
  --store-backend core-oracle-host-reference
```

此模式的 MCP 控制面在 `mcp-control.json`，Oracle 参考数据在 `core-oracle-host/`。它适合验证 Agent/MCP → policy → IPC → Core 公共命令的纵向闭环；不得载入真实个人数据，也不得作为公开发布的移动端存储实现。

Android adapter 的显式 CLI 形态如下。四个值都是交给宿主 channel provider 解析的引用或身份期望，不是端点地址、凭据或会话密钥本身：

```powershell
python services/ameme-mcp-mock/server.py `
  --data-dir .tmp/ameme-mcp-android `
  --store-backend android-local-node `
  --android-endpoint-ref endpoint-ref:android-local-node `
  --android-credential-ref credential-ref:android-local-node `
  --android-expected-device-id device_expected `
  --android-session-binding-ref session-binding-ref:android-local-node
```

直接执行上面的命令会按设计失败关闭，因为普通 CLI 没有可注入的认证 channel provider。嵌入式宿主必须通过 `server.main(..., android_channel_factory=...)` 注入 provider；provider 返回的会话绑定必须同时匹配 expected device 和 session binding。引用不会写入 MCP control JSON，错误信息和对象诊断也不回显其值。control 中的 caller、Grant、purpose 和 scope 只是已授权请求的声明，不能代替 channel 认证。

真实 Host 客户端入口只接受一个被 Git 忽略的配对材料文件，不接受命令行 host、pin 或明文密钥：

```powershell
python scripts/dev/agent/start_android_local_node_host.py `
  --pairing-file .tmp/ameme-pairing/android-local-node.json `
  --data-dir .tmp/ameme-mcp-android
```

配对文件必须是 exact-field JSON；它不含密钥，`credential_ref` 当前只允许 `credential-ref:env/NAME`，由启动进程环境解析至少 32 字节的配对密钥：

```json
{
  "channel_protocol": "ameme.agent-local-node.channel.v1",
  "endpoint_ref": "endpoint-ref:paired/android-device",
  "credential_ref": "credential-ref:env/AMEME_ANDROID_PAIRING_SECRET",
  "expected_device_id": "device_expected",
  "session_binding_ref": "session-binding-ref:paired/session",
  "pairing_id": "pair_expected",
  "host": "192.0.2.10",
  "port": 44321,
  "tls_certificate_sha256": "sha256_<64 lowercase hex>"
}
```

证书 pin、pairing/device/session binding 任一不匹配，或 TLS 低于 1.3，Host 都会在发送应用请求前关闭连接。凭据值、证书、endpoint 和记忆正文均不进入异常文本、对象 repr 或 MCP control JSON。

共享协议允许六种 EventNode operation，但具体 adapter 和 channel 都必须声明实际 capability。当前 MCP Android adapter 只实现 `create_event`、`append_revision`、`undo_capture` 和 `visible_events`；`get_event` 与 `set_policy_blocked` 即使被 channel 多报也稳定返回 `OPERATION_UNSUPPORTED`。`visible_events` 只发送 payload 派生的 sorted space/type、query/time、high-risk flag 与 1–100 limit；成功结果必须只有 `events/risk_filtered`，每个 Event 精确绑定请求 space/Event/structured，title/description≤240/1,000，Restricted 不能越过本次请求。重复 ID、未知字段、额外内容、越界数量或 scope 均毒化会话。Host `recall/get_context` 通过该操作读取，并继续执行 injection、item/token budget；不使用任意目标 `get_event`。写入/撤销结果仍精确绑定原请求，Host 不为写入或撤销暗中发起 read。超时、畸形响应、request-id/protocol 不匹配、会话绑定错误和非法成功响应均在五秒内失败关闭并毒化当前 channel。

离线队列语义可使用 `--offline` 或 `AMEME_MCP_MOCK_OFFLINE=true`。服务按一行一个 JSON-RPC 消息读写 stdio，stdout 不输出诊断文本。

## 配对 Host 到 Android 的真实本地体验

API 36 AVD 可用一条 smoke 命令完成：安装 APK、通过显式 debug seam 只签发通道凭据、启动 Android TLS listener、运行真实 MCP Host、写入一条合成 Event、验证 Today 可见与进程重启后仍存在，最后撤销配对。ADB 只负责安装、端口转发和 UI 取证，不写 Event：

```powershell
python scripts/dev/agent/smoke_paired_android.py `
  --serial emulator-5556 `
  --android-sdk C:\Users\N33131\AppData\Local\Android\Sdk
```

成功 JSON 必须同时包含 `capture_persistence_state=durable`、`visible_in_today=true`、`persistent_after_restart=true` 和 `adb_used_for_event_injection=false`。这份既有 smoke 只证明 `create_event` 的 Host→Android 配对加密传输与本地持久化；`append_revision`、exact Event/Revision undo 与 `visible_events` 当前由 JVM/adapter/MCP/TLS 合成回归和编译后的 Android SQLCipher instrumentation source 覆盖，尚无本轮 AVD/物理设备实跑证据。它们都不证明 NSD、物理 LAN、后台常驻、真实 ContextPack、跨端撤销传播或第三方真实 Codex/Claude Code/Cursor 宿主已经发布。

MCP 宿主的最小配置语义如下；具体配置键由宿主 adapter 决定：

```json
{
  "command": "python",
  "args": [
    "services/ameme-mcp-mock/server.py",
    "--data-dir",
    ".tmp/ameme-mcp-mock",
    "--seed-file",
    "tests/fixtures/agent/synthetic-memories.json"
  ]
}
```

## 本地 Codex 任务的一次性 Android 体验

开发者可以把一个**明确授权、人工筛选、仅含结构化事件**的本地 Codex 任务导出先经过 `ameme-memory` Skill/MCP Mock，再用 Android instrumentation-only seam 写入 Debug APK 的正式 SQLCipher 仓库。该路径用于本机体验和纵向验证，不是生产 Agent 传输、导入 API 或后台采集能力。

输入 JSON 只允许顶层 `thread_id` 与 `events`。每条事件必须提供 `source_turn_id`、`content`、带时区的 `event_time`、`event_type`、`evidence_kind`、`sensitivity` 和 `data_class`；其中 `data_class` 必须为 `structured`，敏感度仅允许 `public|personal`。直接工具证据、用户陈述和推断分别保持为 `direct_evidence`、`user_statement`、`inference`，不得互相升级。

真实源文件、Skill 状态和 Android seed 必须放在 Git 工作区之外的本机私有目录，例如：

```powershell
$demoRoot = Join-Path $env:TEMP "ameme-codex-demo"
python scripts/dev/agent/build_codex_demo_seed.py `
  --source "$demoRoot/source.json" `
  --output "$demoRoot/android-seed.json" `
  --state-dir "$demoRoot/skill-state" `
  --confirm-local-private-data

powershell -ExecutionPolicy Bypass -File scripts/dev/agent/inject_codex_demo_android.ps1 `
  -SeedFile "$demoRoot/android-seed.json" `
  -AndroidSdk $env:ANDROID_HOME `
  -Serial emulator-5554 `
  -ResetAppData
```

构建器限定最多 12 条、64 KiB、`structured` 且 `public|personal` 的事件，并输出不含正文的计数摘要。注入脚本要求工作区外 seed，使用测试 APK 启动显式 instrumentation，完成后删除设备上的一次性明文副本并打开 Ameme。生产 APK 不暴露 seed/import endpoint；本机源文件和生成物仍需由操作者按授权保留期删除。

## 调用与验证

完整 stdio 调用示例由 smoke 脚本实际执行：

```powershell
python scripts/dev/agent/smoke_mcp_mock.py
python scripts/dev/agent/smoke_mcp_mock.py --store-backend core-oracle-host-reference
python -m unittest discover -s services/ameme-mcp-mock/tests -p "test_*.py" -v
python -m unittest discover -s tests/agent -p "test_*.py" -v
python -m unittest discover -s tests/agent -p "test_core_store_integration.py" -v
python scripts/validation/validate_ameme_skill.py
```

本地默认 Mock 状态写入 `.tmp/ameme-mcp-mock/state.json`，该目录已被 `.gitignore` 排除。CoreStore 集成测试在临时目录中分别创建 control JSON 和 Core SQLite，只通过测试生成合成数据，不读取或提交真实个人数据。

专项宿主测试实际跨进程覆盖 exact scope、幂等重放/冲突、Event 删除式 undo、Revision 补偿式 undo、重启恢复、离线队列、双控制面无正文/无原始 key，以及挂起/畸形/崩溃 fail-closed。

## 已知契约缺口

`packages/contracts/` 当前已有 AccessGrant、Event、Revision、ContextPack 等领域 Schema 和 HTTP OpenAPI，但没有六个 MCP tool 的机器可读输入/输出 Schema。由于本任务冻结 `packages/contracts/**`，本服务暂时在 `server.py` 的 `TOOLS` manifest 内维护实现级 JSON Schema。建议契约任务把这六个 tool schema 纳入共享契约并增加 breaking-change diff；在此之前，Mock manifest 不是新的跨端正本。

ADR-001 与同步协议同时阻止把 Python 或新增的共享 runtime 当成 P0–P2 生产 Core。Host 侧 Python 只实现 MCP/Skill 与通道适配，Android Kotlin Local Node 承担 TLS listener、凭据、SQLCipher 和持久幂等；API 36 AVD 已取得真实纵向闭环证据。仍缺设备发现、物理 LAN、后台生命周期、共享账户 Grant registry、真实第三方宿主和 iOS 对等实现。不能把 Python Host 打包进 App，也不能把模拟器端口转发写成物理 LAN 已通过。
