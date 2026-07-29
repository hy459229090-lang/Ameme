# Ameme MVP 数据分类、保留、删除与用户权利 v0.3

> 文档状态：已接受；分类、初始 TTL、生命周期和用户权利为研发基线，法律例外待目标市场审查\
> 更新日期：2026-07-29\
> 机器字段：`sensitivity`、`sync_mode`、`retention_class`、`expires_at`、`deletion_state`\
> 上游：`../architecture/MVP本地存储同步与删除协议.md`

## 1. 分类规则

| Class | 例子 | 默认处理 |
|---|---|---|
| `public` | 产品公开配置、无用户内容的静态模板 | 可随包/公开；仍做完整性校验 |
| `personal` | 普通用户文字、低敏事件描述、偏好 | 本地/选择性同步；不用于广告/通用训练 |
| `confidential` | 照片/音频、日历、Agent Artifact、关系/人物、精细工作内容 | 加密、最小访问、内容禁日志 |
| `restricted` | 精确/轨迹位置、健康、凭据附近内容、高敏情绪/身份/财务、用户标记 | 默认设备本地；外部模型/遥测阻断；显式策略才移动 |
| `secret`（治理类） | 私钥、token、恢复材料、供应商密钥 | 仅 Keychain/Keystore/服务密钥系统；永不进业务对象 |

派生对象采用依赖数据的最高敏感度，除非有经审计的不可逆聚合规则。模型不得自行把 confidential/restricted 降级。

## 2. 保留类别

| retention_class | 用途 | 删除触发 | 限制 |
|---|---|---|---|
| `ephemeral_recovery` | Raw/模型重试/临时导出 | expires_at、用户删除、撤权策略 | 精确 TTL 必填；到期不可用于新模型 |
| `structured_active` | Event/Revision/DayLedger/Artifact | 用户删除、space/account 删除 | 可长期直到用户删除；必须可导出/迁移 |
| `user_retained` | 用户明确保留的选定 Raw/导出 | 用户删除/导出到期 | 明确对象和副本，不继承为整库同步 |
| `derived_rebuildable` | Summary/index/cache/ContextPack | 上游变化、expiry、删除 | 不作为唯一真相；优先清理 |
| `security_audit` | Grant/访问/删除证明元数据 | 安全窗口到期/合法例外 | 无正文；访问严格；不能无限保存“以防万一” |
| `source_locator` | 指向仍由本地来源系统持有的原对象 | 用户删除、来源失效、指纹变化 | 只保存 opaque locator/reference 和校验状态，不复制完整路径或把索引当 Raw |

## 3. 数据生命周期矩阵

| 对象 | 创建 | 默认保留/同步 | 失效 | 删除/证明 |
|---|---|---|---|---|
| Contract/Grant | 用户分项授权 | 元数据在相关设备；账户服务仅保存账户/设备会话 | 暂停、撤销、过期 | 停止新用；安全审计限时保留 |
| SourceObject 元数据 | 每次实际获取 | 本地；最小 structured sync | 来源失效/Contract 撤回 | 沿 lineage 重算 |
| SourceLocator | 原对象由本地来源系统持有时创建 | 本地；随获准 SourceObject 同步至 LAN peer | 原文件移动/删除、权限撤回、指纹不符 | 标记 `missing/stale/revoked`；不声称仍可找回 |
| 原始位置点 | A3 机会式采样 | 本地滚动恢复窗，不同步 | 事件化/重试完成或到期 | 清缓冲+派生地点按用户选择 |
| 原始音频 | 主动录音 | 本地恢复窗；用户选定才长期/同步 | 转写/确认/重试完成或到期 | 音频与确认文字分别选择 |
| 照片 | Picker 原对象引用 | 不复制原图；选定 Raw 才复制 | 系统引用失效/撤权 | 删除 Ameme 副本不等于删系统照片 |
| 模型输入/响应 | 获准处理 | 最小短窗；优先只保 hash/version | 解析/质量/重试完成或到期 | provider/local cache 均进入 job |
| Observation/Candidate | 解析/候选 | 本地/按策略 sync | superseded/rejected/重算 | 删除依赖或保留最小冲突历史 |
| Event/Episode Revision | 接受/修改/归并 | structured_active | 新 Revision 不删除旧历史 | 用户删除/账户删除沿图传播 |
| DayLedger/Summary/index | 投影/派生 | ledger active；summary/index rebuildable | revision/tombstone/policy 变化 | 删除或重建，不保残留 |
| ContextPack | Agent 使用 | 短 expiry、caller-bound | 过期/Grant 撤销 | 禁刷新、清缓存/manifest |
| Export | 用户请求 | 本机短期文件 | 到期/分享完成 | 自动清理；外部副本由用户控制 |
| Telemetry | 可选产品动作 | 聚合/限时 | 用户关闭/窗口到期 | 匿名 ID 重置；内容不应存在 |

## 4. 初始 TTL 与调整规则

MVP 采用以下集中配置默认值；它们是研发假设与最大窗口，不是必须保留到期的最低时长。事件化/分享/迁移稳定后可提前清理，用户删除与撤权策略优先执行。

| 类别 | 初始默认值 |
|---|---|
| location raw | 24 小时，事件化成功可提前清理 |
| active audio raw | 7 天恢复窗；明确保留后转 `user_retained` |
| photo preview | 7 天；不影响系统相册原图 |
| model request/response | 24 小时；之后只留版本、hash、状态 |
| ContextPack | 15 分钟；撤权立即失效 |
| export | 24 小时或成功分享后立即清理 |
| idempotency record | 7 天 |
| migration snapshot | 7 天，稳定后清理 |
| security audit | 180 天，无正文 |
| tombstone/deletion proof | 所有已知副本 ack 且至少 90 天；未确认副本不得宣称完成 |

每个 TTL Spike 必须输出：

```text
max(source retry p99, offline recovery window, deletion propagation window)
 + bounded safety margin
 <= user-visible maximum and storage/privacy budget
```

Gate 3/4 要为上述类别写入单一配置、迁移和到期测试；代码不得散落魔法数字。RAW/DEL/SEC Spike 可收紧数值；延长 Raw、Restricted 或安全审计窗口必须新增安全决策记录。

## 5. 删除语义

1. **仅删除 Ameme Raw 证据**：删除 Raw Vault 密文/manifest 并将证据标记不可用；用户明确选择时可保留 Event/Revision、SourceObject lineage 和可重建索引，但界面必须说明记录不再能回看原始证据。
2. **移除来源并级联**：删 SourceObject/Observation/证据并重算；仅在用户明确选择时保留独立确认字段，否则删除只依赖该来源的 Event 与派生物。
3. **删除事件**：Event/Episode、DayLedger entry、Summary/index/ContextPack manifest 和副本传播。
4. **删除来源合同**：停止来源、删除获准范围历史或仅撤权由用户明确选择。
5. **删除 space/account**：先冻结、撤 Grant/Contract，再全图删除。
6. **系统原对象**：Ameme 不能声称删除系统相册、HealthKit/Health Connect、外部 Agent 或用户已分享的外部副本；只说明自己的引用/副本和可验证接收方。

2026-07-29 达成边界：Android schema v13 与 iOS envelope v8 已在既有 SourceObject→Event lineage 和 content-free exact-revision 字段→来源证据之外，加入独立的用户确认 provenance。记录只含确认 ID、Event/revision、确认类型、字段名集合、是否覆盖完整 accepted field set、时间与 terminal state，不含字段值、正文、用户 ID 或来源内容。只有明确绑定完整 Candidate 字段集的 `completeFieldSet=true` 确认可以在唯一来源删除后独立支持 Event；来源 link/claim 仍被终结，Event 追加 revision 并显示“用户确认（来源已删除）”。缺少完整字段集合的 fact-status/用户修订只形成 `partial` 审计，不能保留失去来源的 Event；Agent revision/undo 不会创建确认，v12/v7 迁移也不会从旧状态猜测用户动作。多来源仍只有在每个 accepted field 获得剩余来源或完整用户确认支持时才重算；字段失去最后支持则删除 Event，legacy/stale/partial 必须 fail closed。Android 当前只有 provider/content locator，没有 app-owned Raw Vault，因此 Raw-only 只能返回 `external_not_owned/no_raw`；iOS 仅对 app-owned AES-GCM media 执行 pending→物理删除→完成并支持重启重试，Photos 等外部原件不变。这是仓库内实现与生产 Smoke 证据，不是真实用户、物理设备、source contract/account、peer ack 或合规删除证明；这些 Gate 继续 `hold`。

同日 Android schema v14 增加生产 Local Node 的 content-free `STARTED`/`COMPLETED` 访问记录：
只保存调用方声明、purpose、canonical space/data-type scope、operation、稳定结果码、对象数量桶、
时间与 180 天 `retention_until`，明确不记录正文、搜索词、payload/digest、request/grant/object ID、
source/locator/path、配对密钥、模型输入或自由异常。STARTED 在 repository 访问前持久化，失败则
拒绝执行；COMPLETED 失败保留诚实的未完成 STARTED，并依靠写操作持久幂等安全重试。SQLCipher
表禁止 UPDATE 和未到期 DELETE，最近读取/总容量有界；Android 设置页只读最近 20 条且不生成
合成记录。新增 v13→v14 migration/runtime/UI instrumentation 本轮只编译，未占用并行 AVD。
同安装恢复激活现把旧 live 在激活时仍未过期的审计与候选账本做单调 union：精确重复去重，
同 ID 或同 trace/phase 的不同内容、非法记录和 50,000 行容量溢出均在换库前失败关闭；retained
ledger digest 与合并后 SQLCipher 文件 digest 在换库前后复核，PREPARED 崩溃回旧 live 并清理
专用 sidecar。该合并不复活已过期审计，也不改变 180 天窗口。iOS 当前仍是客户端而非数据拥有型
Host；账户/多设备 owner audit、安全导出、真实用户理解、物理设备和发布均继续 `hold`。

同日补齐的产品级本机删除入口只处理当前安装 Personal space：先终结可读 Event/Coverage/长期 Memory/复用/source 投影并写不可被旧备份绕过的 SPACE 水位，再停止并等待当前安装 Agent runtime，验证清除本机 pairing/secret 和连接元数据，并清空/冻结 Android pending action、iOS pending export 与 App Group incoming-share handoff。Share Extension 和旧任务在 content-free marker 后写入 fail closed；iOS 删除 app-owned Raw ciphertext，Android 继续处理 persisted locator release。这里撤销的是当前安装 pairing，不是账户或共享 Grant registry；结果硬编码不宣称账号删除、peer proof、Photos/provider 原件、对端/云副本、外部分享或物理介质擦除。只有账户/共享 Grant、远端 ack、例外与用户可见权利请求流程完成后，才可把第 5 项“删除 space/account”报告为全局完成。

审计证明保留不可逆对象摘要、步骤、时间、接收方/副本状态和错误码，不保正文。离线/丢失设备未 ack 时显示 proof incomplete。

## 6. 备份与卸载

- Raw Vault、密钥和高敏数据库默认排除 OS 普通云备份。结构化数据按 SDR-001 只在已批准 LAN peer 间选择性同步；selected Raw 也只能在用户逐项授权后同步到已批准设备。
- App 卸载可能移除 app-specific storage；用户需要独立保存的数据通过显式导出/系统媒体库完成，不能让“卸载即丢失”成为隐藏设计。
- 迁移快照只在升级/回滚窗口存在，到期进入 DeletionJob。
- MVP 不提供记忆数据云备份/灾备。peer 恢复必须先应用 tombstone/删除水位再接收对象，恢复演练验证不会复活已删对象。
- Python Core reference 已用 synthetic 数据证明一致 SQLite 快照、已加密 Raw 密文、hash/`quick_check`、损坏拒绝、无密钥备份和恢复到新目录。这不是生产移动备份：其结构化 SQLite 为明文，密钥需外部提供，也未证明 OS 调度、Keychain/Keystore 恢复、E2EE 云传输、账户恢复或物理设备灾难恢复。
- 双端同安装候选现在可在 exact backup 短时确认后进入可回滚 live-store 激活内核：候选与权威 tombstone 在切换前后复核，HMAC journal 只保留版本、状态、确认 ID、backup ID 与 MAC，不含正文、路径或 key；`PREPARED` 失败回旧 live，`COMMITTED` 只保留已验证新 live，原候选不消费。该内核没有普通用户入口，receipt 仍为 `productionRecoveryClaim=false`；没有 recovery secret、跨设备 key、用户自有/E2EE 路线和物理设备演练时，不得向用户宣称卸载、换机或全设备丢失后可恢复。
- Android 激活时将 content-free 安全审计视为不可回退账本：只把旧 live 中激活时仍未过期的记录并入候选，精确重复去重，冲突/容量异常拒绝整个激活，并在原子换库前后复核账本与 SQLCipher 文件摘要。该仓库实现只完成 JVM、androidTest 编译和静态门，真实进程终止、磁盘满、断电、OEM 文件系统和物理设备仍待独立演练。

## 7. 权利请求与身份校验

访问/导出/删除/注销前使用与风险相称的重新认证；不通过邮件/客服直接发送内容。若用户失去所有已批准设备，身份服务只能处理登录、会话和账户元数据，不能恢复、解密或导出旧记忆内容。

权利处理状态可查询、可重试、可审计；法定响应时限和保留例外必须在目标辖区确定后由法律审查补充。

## 8. 禁止日志字段

正文、转写、搜索词、Prompt/response、文件/照片标题、URL、精确坐标、健康值、联系人/人物、完整路径、token/key、系统 provider 原始错误。日志只允许匿名 ID、类型、版本、结果码、耗时/大小/数量桶和处理位置。
