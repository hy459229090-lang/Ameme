Verdict: `conditional_pass`
Scope / Build / Commit: Android Local Node 四项生产 operation 的 SQLCipher 访问审计、180 天保留、失败关闭与设置页只读投影；工作区未提交状态，未形成发布提交
Gate / Spike ID: `P0-ANDROID-AGENT-AUDIT-14` / `AUDIT-001`
Owner / Reviewer: Codex 主执行 Agent / Android 安全审计、恢复与内容最小化独立复核待签
Date / Environment: 2026-07-29；macOS、临时 JDK 17、Android SDK 36、隔离 Python 3.12；API 36 / 16 KB arm64 AVD

# P0 Android 生产 Agent 访问审计与只读投影

## 结论

Android 生产 Local Node 已为冻结 v1 的 `create_event`、`append_revision`、exact
`undo_capture` 和 bounded `visible_events` 接入持久、内容无关的访问审计，Verdict 为
`conditional_pass`：

- 每个进入已启用生产端点的请求先向 SQLCipher 追加 `STARTED`，成功、拒权、校验失败或运行时
  错误再追加同一 trace 的 `COMPLETED`。授权拒绝也留下调用方声明、目的、空间、数据类型、操作、
  稳定结果码和时间，不返回或保存目标存在性细节。
- `STARTED` 写入失败时返回 `TEMPORARILY_UNAVAILABLE`，并且不执行 repository 读写；不能在
  审计不可用时静默继续。完成记录写入失败时保留诚实的未完成 `STARTED`，关闭原响应并返回可重试
  错误。若 mutation 已提交，既有 SQLCipher 幂等槽使同请求重试不会重复创建 Event/Revision。
- 记录模型和物理表都不含正文、搜索词、payload、payload/idempotency digest、request/grant ID、
  object ID、source/locator/raw path、配对密钥、模型输入或自由异常文本。对象数量只保存
  `0`、`1`、`2_10`、`11_100`、`101_plus` 或 `unknown` 区间。
- Android SQLCipher schema 从 v13 升至 v14；v13→v14 只建空审计表、索引和 append-only/提前
  删除保护 trigger，不从旧 Event、幂等记录或日志猜测历史访问。
- 保留期由 `AgentAccessAuditPolicy.retention = 180 days` 单点定义；读取最多 100 条，设置页当前
  只取最近 20 条；表最多保留 50,000 行，容量不足时 fail closed。过期行可清理，未到期行不能
  UPDATE 或直接 DELETE。
- Android 设置页新增“Agent 访问记录”，展示调用方、purpose、operation、space/data type、
  STARTED/COMPLETED、稳定结果码、对象数区间和本地时间；空库、读取失败与生产库不可用分别显示
  真实状态，不生成合成访问记录。
- 同安装恢复激活不再把安全账本回退到 backup 时点：候选过期行先在 staging transaction 中清理，
  再把旧 live 在激活时仍未过期的记录与候选账本做单调 union，精确重复去重；同
  ID/trace-phase 内容冲突、非法记录或 50,000 行未过期容量溢出都在换库前 fail closed。union
  的账本摘要、数量和合并后 SQLCipher 文件摘要在换库前后复核。

## 实现证据

| 层 | 生产实现 | 关键不变量 |
|---|---|---|
| 模型/策略 | `AgentAccessAuditRepository.kt` | canonical scope、phase/result 配对、数量桶、180 天和有界读写 |
| SQLCipher | `LocalAgentAccessAuditPersistence.kt`、`LocalEventDatabase.kt` | v14 迁移、STARTED/COMPLETED 唯一、matching start、append-only、未到期禁止删除 |
| Repository | `LocalMemoryRepository.kt` | 持久 sink 与最近记录/过期清理只读接口 |
| 端点 | `AgentAccessAudit.kt`、`MemoryRepositoryAgentLocalNodeEndpoint.kt` | STARTED 先于 repository；完成结果和 exact object-count bucket；审计故障失败关闭 |
| 生产 Runtime | `AgentLocalNodeRuntime.kt` | 每次已验证配对会话构造端点时强制注入 durable sink |
| 用户投影 | `AmemeApp.kt`、`SettingsScreen.kt` | 串行 I/O 读取、最近 20 条、无合成 fallback、内容最小展示 |
| 恢复保全 | `AgentAccessAuditRecovery.kt`、`LocalRecoveryAgentAccessAudit.kt`、`LocalRecoveryActivation.kt` | 过期候选清理、retained union、exact 去重、冲突/未过期容量拒绝、PREPARED-before-staging、digest-bound swap 与 sidecar 收敛 |
| 自动化 | JVM + androidTest source | success/拒权、begin/complete 故障、幂等重试、迁移、重载、TTL、SQL 负向和 UI 空态 |
| 静态门 | `validate_android_agent_access_audit_contract.py` | 生产接线、内容禁项、迁移/保留/失败关闭、UI 与证据边界 |

## 失败恢复语义

审计和业务 mutation 不能跨两个独立 SQLCipher transaction 原子提交，因此端点采用两阶段证据：

1. 业务访问前持久化 `STARTED`；失败则零 repository 执行。
2. 执行并得到 bounded response 后持久化 `COMPLETED`。
3. 第二步失败时不把成功响应交给调用方；保留 `STARTED` 作为“已开始但没有可信完成记录”的证据。
4. 写操作的重试由 caller/grant/purpose/space/type/operation/slot 与 payload digest 绑定的既有
   持久幂等记录解析，不重复 mutation。

这比只在返回后写一条 best-effort 记录更保守。`STARTED` 不是成功声明；设置页会明确显示“已开始”
和 `ATTEMPT_STARTED`。

## 可复现验证

```text
JAVA_HOME=/private/tmp/ameme-toolchains-20260726/jdk17/Contents/Home
ANDROID_HOME=/private/tmp/ameme-toolchains-20260726/android-sdk
./gradlew :app:testDebugUnitTest \
  --tests com.ameme.android.data.AgentAccessAuditRepositoryTest \
  --tests com.ameme.android.data.transport.MemoryRepositoryAgentLocalNodeEndpointTest \
  :app:compileDebugAndroidTestKotlin --no-daemon
```

结果：`BUILD SUCCESSFUL`。定向 JVM 实际执行数量桶/180 天/phase 不变量、允许与拒绝访问的
内容无关记录、STARTED 写失败零 mutation，以及 COMPLETED 写失败后的持久幂等重试；随后完整
Debug JVM 为 `107/107`、0 failed / 0 error / 0 skipped，其中新增 JVM 实际执行恢复 union、
精确去重、trace-phase 冲突、过期 live 输入拒绝，以及过期候选不占冲突/容量并集。Debug/Release
assemble、Lint 和 androidTest 编译通过；Lint 为 0 error、29 warning、1 hint。Debug APK 为
41,182,589 bytes，unsigned Release APK 为 33,559,518 bytes。

```text
python3 scripts/validation/validate_android_agent_access_audit_contract.py
python3 scripts/validation/run_workspace_validation.py
```

结果：access-audit 静态门 `146/146`、recovery 静态门 `138/138`；隔离 Python 3.12 统一工作区
`28/28` Gate 通过，
Markdown links 43、workspace governance 178，均 0 error。静态门显式保持
`ios_host_audit_claim=false`、`real_user_execution_claim=false`、
`physical_device_execution_claim=false`、`shared_account_audit_claim=false` 和
`release_claim=false`。

```text
ANDROID_SERIAL=emulator-5554 ./gradlew :app:connectedDebugAndroidTest --no-daemon
```

结果：Android 16、arm64、`PAGE_SIZE=16384`、320dp、`font_scale=1.3` AVD 的 XML 精确为
100 discovered / 93 passed / 7 个显式外部门 skipped / 0 failed。恢复激活类 5/5、Agent 审计
持久化 2/2、生产端点 3/3、完整 Ameme UI 13/13、本机 Space 删除确认 UI 2/2、待处理动作存储
3/3。控制台的“Finished 107”把 7 个 skipped 重复计入进度，交付计数以 100 个
`<testcase>` XML 节点为准。

## 首次失败与修复

| 首次失败 | 根因 | 处理 | 未解决影响 |
|---|---|---|---|
| 首次 Gradle 命令无法找到 Java | 当前 shell 没有 JDK 环境 | 使用既有隔离 JDK 17 | 临时工具链不等于签名/物理设备环境 |
| 补 JDK 后 Gradle 报 SDK location missing | 当前 shell 没有 `ANDROID_HOME` | 使用既有隔离 Android SDK 36 后重跑 | 不关闭设备 Gate |
| 代码审计发现成功响应前没有持久访问记录 | 既有 Local Node 只有持久幂等表，没有 security audit | 引入 v14、两阶段 sink、失败关闭与 UI 投影 | iOS Host 和共享账户审计仍未实现 |
| 恢复审计发现激活会用旧 backup 覆盖 backup 后 live 新增审计 | 业务快照与安全账本共用 SQLCipher 文件，既有激活只做文件替换 | 增加 retained union、冲突/容量 fail-closed、账本/文件双摘要和 PREPARED-before-staging；JVM 与 AVD 重跑通过 | 真实进程 kill/断电/磁盘满仍未执行 |
| 首轮恢复 union 编译报 `MessageDigest.put` extension/member reference 冲突 | Kotlin 不允许将同名成员/扩展重载直接作为 `forEach` 引用 | 改为显式 lambda并重跑定向及完整非设备 Gradle | 无运行时影响，失败记录保留 |
| 首次定向 AVD 编译同样停在上述 member reference 冲突 | 设备命令在并行增量尚未收稳时启动 | 等修复与非设备门稳定后重跑定向 11/11、全量 96/89/7/0 | 失败未计为设备通过 |
| 统一工作区首次停在 Mobile Coverage 静态门 | 既有六个跨端门把 Android 当前 schema 硬编码为 v13 | 改为断言当前 v14，并保留 v7–v13 各功能引入版本；重跑 28/28 | 无运行时影响 |
| 本轮直接用系统 Python 3.9 跑 workspace 在 Sync 类型语法导入失败 | 项目要求 Python 3.10+，固定验收环境为隔离 Python 3.12 | 使用 `uv --python 3.12 --with-requirements` 重跑 28/28 | 系统 Python 失败保留，不代表源码回归 |
| 新增删除确认 UI 首轮设备测试找不到 LazyColumn 尾部节点 | 测试直接对尚未组合的节点执行 `performScrollTo()` | 先对 `settings-list` 使用 `performScrollToNode(hasTestTag(...))`；定向 2/2 后全量 100/93/7/0 | 产品 UI 未缺失；真实 TalkBack 与物理设备仍是外部门 |

失败记录未删除或改写为通过。

## 未关闭 Gate

- 本切片只覆盖 Android 作为数据拥有者/Local Node Host 的生产端点。iOS 当前是四操作客户端，
  没有数据拥有型 Host 审计表或同等设置投影；不能声明“双端 Agent 审计完成”。
- `/v1/access-audit` 的账户/多设备 owner API、共享 Grant registry、跨设备 trace 汇聚、撤权传播和
  安全导出尚未接入；Android 页面只读当前本机 SQLCipher。
- Android 同安装激活的仓库内合并策略已关闭：backup 后未过期 live 审计会单调并入候选，过期
  候选不会占用 retained capacity；跨快照 STARTED/COMPLETED、merge failure 和 orphan sidecar
  已在 API 36 / 16 KB AVD 执行。仍需真实进程/断电/磁盘满演练。
- v13→v14 migration、append-only/TTL、端点重载和设置页 UI instrumentation 已在同一全量 AVD
  执行；仍需 Android 物理设备、低存储、锁屏、进程终止和 OEM SQLCipher 复验。
- 真实用户对 caller/purpose/scope/result/时间的理解、真实第三方 Host、真实 LAN、共享账户
  Grant、iOS Host、物理设备、真实读屏、签名、商店和事故/取证流程继续 `hold`。

## 判定

`P0-ANDROID-AGENT-AUDIT-14` 关闭了仓库内 Android Local Node “生产操作可读写但没有持久、
内容无关、用户可见访问记录”以及“同安装恢复会回退本机访问账本”的空白。它没有关闭双端/账户
审计、真实用户理解、物理设备、真实 Host 或封闭 Beta Gate；这些限制不得
由静态检查、Mock、Simulator 或 AVD 替代。
