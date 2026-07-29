# Ameme 封闭 Beta 外部门执行包 v0.1

> 状态：`ready_to_execute / blocked_external`；当前总 verdict 为 `hold`
> 更新日期：2026-07-26
> 适用范围：真实参与者、物理设备、真实读屏、签名/Provisioning、内部商店分发、真实 Agent 宿主、生产恢复、付费服务/成本、安全事故和回滚
> 证据边界：Simulator、AVD、Mock、合成夹具、Swift smoke、静态门和计划只能证明准备度，不能关闭本文件的 Gate

## 1. 进入条件、人员与停止权

候选冻结输入：reviewed commit、双端 build ID、schema/protocol/policy version、feature flag
snapshot、SBOM/SDK allow-list、恢复格式、隐私声明版本。任一输入在执行中变化，受影响 Gate 必须重跑。

| 角色 | 最少人数 | 负责 Gate | 权限/独立性 |
|---|---:|---|---|
| Release owner | 1 | 候选冻结、Ring、签名、stop/rollback | 不能由单一实现 Agent 自批 |
| iOS device QA | 1 | iOS 物理设备、VoiceOver、Keychain、TestFlight | 有真实设备与签名 build |
| Android device QA | 1 | Android OEM、TalkBack、SQLCipher、Play internal | 有两类 OEM 与签名 build |
| Security/privacy owner | 1 | 日志、Restricted、密钥、商店/供应商、事故 | 可独立停止 |
| Recovery reviewer | 1 | 备份、密钥恢复、损坏、切换、删除不复活 | 不由备份实现者单独签 |
| Agent host owner | 1 | 真实宿主、Grant、撤权、过期、LAN | 有真实宿主账号/设备 |
| Product/research owner | 1 | T0、用户可见边界、继续/停止 | 绑定 8×7 Pilot |
| Support/on-call | 1 | 支持、SEV、用户沟通、数据请求 | Ring 开始前可联系 |

SEV0 停止权：Release、Security/privacy、Product 任一可立即停止。出现未授权内容、
wrong-space、Restricted 外发、secret/content telemetry、ack 后丢失、tombstone 复活、
假删除、恢复覆盖 live store 或签名/商店声明与行为不一致时，无需等待比例阈值。

## 2. 设备、账户、网络与预计耗时

### 2.1 最小物理矩阵

| 槽位 | 真实设备 | OS | 必跑 |
|---|---|---|---|
| I1 | 支持最低版本的 iPhone | iOS 18.x | Keychain/File Protection、VoiceOver、Share/Photo/Calendar/Voice、进程终止、恢复 |
| I2 | 当前 iPhone | 当前 iOS | 当前视觉/动态字体、TestFlight、后台、恢复与升级 |
| A1 | Pixel 类 arm64 | Android API 34+ | SQLCipher、16 KB 能力记录、TalkBack、Picker/Share/Calendar/Voice、Keystore |
| A2 | 非 Pixel OEM arm64 | Android API 34+ | OEM 权限/后台/文件系统、TalkBack、Play internal、恢复 |

每个槽位至少 1 台物理设备；设备型号、serial、账户和屏幕录像只进私有 evidence
bundle。仓库只记录 `device_model_bucket`、OS、build、结果和 bundle hash。iOS 与 Android
各至少一次真实低电量/低存储观察，但不得故意危及参与者主设备；故障注入使用专用测试设备。

网络：普通 Wi-Fi、隔离客户端 Wi-Fi、热点/局域网、离线四种。真实 LAN/QR/Grant 需两台
物理设备或一台物理 Mobile + 一个真实宿主设备；ADB forward、loopback 和模拟发现不算。

### 2.2 必需外部账户

- Apple Developer + App Store Connect/TestFlight 内部测试权限；
- Google Play Console internal testing + Android app-signing/upload authority；
- 测试身份/设备归属服务账户；
- 至少一个真实 Agent 宿主账户与可撤销 exact Grant；
- 若启用外部模型/OCR/STT：获批生产/封闭测试 provider 账户、区域、retention/DPA、真实报价；
- 支持/事故渠道和目标市场 privacy/security/legal owner。

凭据、邮箱、team/store/account ID、证书、profile、token、recovery code 不进入 Git。若任一
必需账号未提供，对应 Gate 保持 `hold`；关闭 feature 不得绕过已批准的公开范围，但可缩小
封闭 Ring 并重新签收范围。

预计执行：候选冻结 2 小时；每个设备槽位核心旅程 2.5–3 小时；每平台恢复/升级 1.5 小时；
真实 LAN/Agent 2–3 小时；双端真实读屏 2 小时；签名/商店/隐私 4–6 小时；事故/回滚 3 小时。
总计约 24–32 人时，不含 T0 8×7 Pilot。

## 3. 私有运行目录与启动命令

从仓库根目录运行：

```bash
python3.12 scripts/validation/prepare_external_gate_run.py \
  --run-id <YYYYMMDD-build-id> \
  --build-commit <40-hex-reviewed-commit>
```

脚本只在 Git-ignored `data/private/external-gates/<run-id>/` 创建空白模板与 manifest，不采集
设备/账户数据，也不把 Gate 标为通过。执行人员填写：

- `physical-device-run-template.csv`
- `provider-cost-input-template.csv`
- `signing-store-checklist-template.md`
- `external-gate-summary-template.json`

真实截图、录像、系统报告、签名/商店页面、数据库/备份、日志和支持记录放同一私有目录的
受控子目录；最终只提交内容无关的文件 hash、分子/分母和 verdict。每个 bundle 生成 hash
前先移除凭据和不必要正文。

## 4. 每台设备的顺序化执行

不得并行复用同一设备 serial、测试账户或恢复目标。每步记录开始/结束、build、操作者角色、
结果、错误码/issue ID 和 bundle hash。

1. **真实性与安装**：拍摄/记录物理设备证明；安装签名内部测试 build；核对 commit/build、
   包名、签名、entitlement/permission、真实/Mock 标识。
2. **空白与拒权**：新测试账户/空库；拒绝每项来源权限；普通用户仍完成文字记录、Today、
   Search、修订、删除、导出，不出现假数据或死路。
3. **主动来源**：真实文字、系统 Share、Photo Picker、日历、系统语音/音频选择；取消、撤权、
   重复回调、来源删除、不可用均不制造 Event 或越权。
4. **Event/Memory**：确认 planned、修订追加 Revision；敏感/推断长期 Memory 不自动 active；
   上游修订/删除后旧 Memory 和复用 context 失效。
5. **四类复用**：用户可见历史搜索、项目续接、会前上下文、决定/承诺找回；记录 helpful、
   wrong/miss/outdated/revised/hidden/deleted。若最终触发面尚未接入，Gate 直接 `hold`，不得用
   repository smoke 代替。
6. **生命周期**：前后台、锁屏、进程终止、重启、旋转/横竖屏、日期/时区变化、权限撤销、
   动态字体；无丢失、假完成或正文日志。
7. **真实读屏**：VoiceOver/TalkBack 从启动完成记录、搜索、修订、删除/取消、导出/恢复状态；
   焦点、名称、角色、顺序、错误与恢复动作可理解。静态 semantics 不计。
8. **存储/删除**：核对 SQLCipher/AES-GCM、Keystore/Keychain、错误 key fail closed；Event
   删除后 Today/Search/Memory/复用不可见；Source/Raw/space/account 仅在真实实现存在时执行，
   未实现直接 `hold`。
9. **备份/恢复**：创建一致生产候选；显示备份健康/最近验证；损坏、错 key、旧 watermark、
   额外文件、非空目标拒绝；恢复到空目标，经明确确认后切换；Today/Search/Revision 可读，
   已删数据不复活。当前同安装 artifact 无 key recovery/user switch，故生产恢复 Gate 仍
   `hold`，直到批准路线完成。
10. **导出/退出**：结构化导出可重试/清理、Restricted/locator/media 不泄漏；账号/space
    退出的保留与删除边界可解释。
11. **性能/资源/日志**：记录冷启动、搜索、保存、恢复、存储、电量、crash/ANR；运行正文、
    query、Prompt、path、key/secret canary 扫描。真实阈值在候选冻结前预登记。

## 5. 真实 Agent、LAN 与撤权

1. 真实宿主生成短时 QR/候选；Mobile 显示 caller、purpose、space、data type、operation 和期限。
2. 用户确认 exact Grant，物理 LAN 建立 TLS/pin/HMAC/session；无 ADB forward。
3. 宿主写一个参与者批准的测试 Event A，保留返回的 exact undo token；在 10 分钟内撤销，
   Mobile Today/Search 不再可见，重放同一撤销不产生第二次 tombstone，进程重启后仍不复活。
4. 宿主写独立测试 Event B，再用独立 exact Revision Grant 追加一次 Revision；在该 Revision
   仍为 current head 时用其 exact token 撤销，Mobile 显示补偿 Revision 恢复前一内容，历史
   Revision 未被覆盖，重放和重启返回同一 compensation revision ID。
5. 分别测试超 scope、跨 space、Restricted、过期 token、错误 type、不同 payload 同
   idempotency、离线，以及 Revision 后续已有新 head 时撤销返回 conflict 且零 mutation。
6. 撤销 Grant/断开/移除设备；新调用立即失败，已有短时 context 不能刷新；旧 pairing 若没有
   明示 operation policy，升级后必须重新授权，不能静默得到 undo。
7. 用当前 `visible_events` exact operation policy 验证单 Personal space、Event/structured、query/time/limit、
   Public/Personal/Confidential 交集、正文截断与 `get_event`/策略写入关闭；ContextPack 中的注入文本必须
   作为不可信数据过滤。仓库内 adapter/JVM/TLS 结果不能替代这一步真实宿主与物理设备执行，未执行时
   read/复用 Gate 保持 `hold`。
8. 仓库内 `AmemeLocalNodeSmoke` 可作为 iOS 侧预检，依次执行 create→bounded read→append→
   exact revision undo→独立 Event create/undo→final read；执行人必须改用本次真实 pairing 与明确
   扩展 Grant。普通用户默认 event-only Grant 不得被复用或静默扩权，且预检/AVD 结果不能替代本节
   的真实宿主、无 ADB forward 与物理 Mobile 证据。

通过需要至少一个真实宿主 + 物理 Mobile 的批准范围闭环，全部负向 fail closed，无正文/secret
日志。第三方生产宿主若未提供，状态为 `hold`。

## 6. 生产恢复路线 Gate

进入执行前必须有已批准 ADR：用户自有存储或 E2EE 云备份；包含 key/recovery secret 建立、
轮换、遗失、换机、退出迁移、旧设备撤销、支持与删除传播。

必须在双端物理设备执行：

- 一致快照、DB/Raw/media hash 与关系完整性；
- 真实 key recovery；错 key/损坏/截断/额外文件 fail closed；
- 目标非空不覆盖；候选验证后由用户明确切换，失败可回旧 live store；
- 恢复后 Today/Search/Revision/Memory 可读；
- 权威 tombstone 优先，已删 Event/Raw/Source/ContextPack/peer 数据不复活；
- 最近成功备份/验证/恢复状态与限制对普通用户可见；
- 全设备丢失、旧设备离线、恢复 secret 丢失分别有诚实结果。

当前仓库已完成同安装、设备 key 尚在时的隔离候选，以及不接 UI 的 exact-confirmation/HMAC
journal 可回滚激活内核；artifact 与 receipt 仍为 `productionRecoveryClaim=false`。这不提供
recovery secret、跨设备 key、用户可见备份健康或支持流程。在上述路线和物理演练前，本 Gate
必须保持 `hold`。

## 7. 签名、商店、隐私、付费服务与成本

使用私有 `signing-store-checklist-template.md`，由 Release + Security/privacy 双签。核对：

- 签名 build 与 reviewed commit、App Group、Keychain/Keystore、backup exclusion；
- TestFlight/Play internal allow-list、最小权限、privacy manifest/Data safety；
- SDK/network allow-list、provider region/retention/deletion、无未声明 endpoint；
- 目标市场组织身份、政策、支持/删除联系渠道；
- 真实 provider 账单/报价的生效日期、region、currency、unit 和 free-tier 排除。

单位有效复用成本：

```text
有效复用成本
= Ring 内实际模型 + 存储 + 同步 + 支持可变成本
  / 同期被真实用户确认 useful 的复用次数
```

分母为 0 时报告“不可计算”，不得写 0；免费额度不能替代 steady-state 报价。无付费账户或真实
报价时 `paid_provider_and_cost=hold`，不以 fixture token/cost 关闭。

## 8. 事故与回滚演练

在签名候选与专用测试数据上依次演练：saved data missing、wrong-space/unauthorized、
tombstone/restore、provider outage、migration failure、cost/resource runaway。记录检测、
contain、stop authority、RTO/RPO、恢复、用户沟通草稿与后续 owner。

回滚后必须验证：

- capture/Today/Search/delete 的安全最小路径仍可用；
- 旧客户端不写不理解的 schema；
- tombstone/revoke 优先于普通同步；
- restore 不覆盖 live store，已删内容不复活；
- 已外发到 Agent/provider/export 的内容不会被“回滚 App”假装撤回。

任一演练未真实执行，`incident_and_rollback=hold`。

## 9. 判定与证据签收

每个 Gate 只能是 `pass`、`conditional_pass`、`hold`、`reject`。`external-gate-summary` 中从
`hold` 改状态必须同时满足：

1. 真实人员、设备、账号和 signed build 已绑定；
2. 规定步骤完整，失败与偏差未删除；
3. evidence bundle hash 非空；
4. 对应 reviewer/approver 角色签收；
5. 无 P0/P1 信任问题，stop conditions 未触发；
6. 结论不把 Simulator/AVD/Mock/静态/smoke 作为真实证据。

封闭 Beta 总 verdict 只有在 T0 8×7、双端物理设备/读屏、生产恢复、真实 Agent（批准范围）、
签名/internal distribution、商店隐私安全、真实成本和事故回滚全部关闭后才可离开 `hold`。
当前这些外部输入均未提供，因此本执行包的正确结论是 `hold`，不是 release-ready。
