# quality 质量与验证索引

## 当前证据

| 证据 | 路径 | Verdict |
|---|---|---|
| Windows 信息源低风险实测 | `../../research/windows/windows-feasibility-report.md` | 部分通过 |
| Android DB-01 10k/100k AVD 报告 | `../../tests/results/performance/android-db01-api36.json` | 有效基线；两项预注册 DB 延迟门通过 |
| Android MVP 完整体验闭环 | `Android-MVP完整体验闭环验证-20260714.md` | conditional_pass；移动来源/Agent/AI 小结在 API 36 AVD 闭环 |
| Android Agent 统一连接体验 | `Android-Agent统一连接体验验证-20260715.md` | conditional_pass；三入口、统一授权、模拟标识、断开和 Release 隔离通过 |
| Android 16 KB 与 UI 验收 | `Android-16KB-UI验收-20260718.md` | conditional_pass；16 KB AVD、64 项总测试中 57 项可执行回归、外部分享/导出恢复与清理、当前深层页面稳定帧和语义树复核通过，物理设备/完整无障碍待补 |
| iOS Shared Core 验证 | `iOS-Shared-Core验证-20260718.md` | conditional_pass；加密本机闭环、媒体边界、录音启动/清理、SwiftUI accessibility contract、来源搜索、跨端导出 wire value、加密导出恢复/清理、Bonjour 发现与真实/演示连接边界、AccessGrant policy、批量导入回滚/幂等、Share Extension target 输入和 Smoke 通过；完整 Xcode/iOS UI/真机/无障碍待补 |
| 双端体验状态矩阵 | `双端体验状态矩阵-20260718.md` | conditional_pass；共同状态规则、逐页恢复动作、本机/Mock 证据和 AccessGrant 本地 scope 门禁已登记；设备级状态、真实来源、完整无障碍待补 |
| M23 配对与连接生命周期验证 | `M23-配对与连接生命周期验证-20260718.md` | conditional_pass；iOS duplicate-key pairing 拒绝、双端 disconnect 契约、既有 Swift→Android AVD 真实传输子门通过；QR/account/reconnect/设备级门禁待补 |
| M24 双端真实构建与设备交付验证 | `M24-双端真实构建与设备交付验证-20260726.md` | 仓库工程候选 pass；双端真实/Mock 核心闭环、QR→Android TLS/HMAC/Grant、Android 16 KB 大字号设备回归、iOS Xcode/XCUITest 截图和仓库门禁通过；物理设备/读屏/签名与商店发布仍为保留门 |
| M25 双端平台视觉升级与设备复验 | `M25-双端平台视觉升级与设备复验-20260726.md` | 仓库工程候选 pass；iOS 26 Liquid Glass、Android 当前稳定 Material 3、双端字号/语义/同屏视觉与 Simulator/16 KB AVD 全绿；物理设备/真实读屏/签名与商店继续 hold |
| P0 上下文覆盖与长期记忆闭环 | `P0-上下文覆盖与长期记忆闭环验证-20260726.md` | conditional_pass；覆盖/编译/删除/恢复/Agent 边界/复用的合成本地闭环通过，真实用户、规模、生产恢复与物理设备 hold |
| P0 双端生产 Coverage 运行时 | `P0-双端生产Coverage运行时验证-20260726.md` | conditional_pass；Android/iOS 生产领域模型、六项来源注册表、保守编译器与状态降级通过，持久化/UI/Event/Memory/真机仍待接入 |
| P0 双端 Coverage 持久化与显式 Event 接线 | `P0-双端Coverage持久化与显式Event接线验证-20260726.md` | conditional_pass；双端版本化密文、显式接受、link detach 与删除不复活边界通过；Android SQLCipher 后续已由 2026-07-29 API 36 / 16 KB 全量回归补证，iOS XCTest/真机待补 |
| P0 双端生产长期 Memory 边界 | `P0-双端生产长期Memory边界验证-20260726.md` | conditional_pass；9 类 Memory、exact Event revision、显式确认、有效期、替代、上游失效与不复活通过；Android SQLCipher 后续已由 2026-07-29 API 36 / 16 KB 全量回归补证，iOS XCTest、ContextPack/恢复/真机待补 |
| P0 双端本机恢复候选与删除水位 | `P0-双端本机恢复候选与删除水位验证-20260726.md` | conditional_pass；双端持久删除水位、认证同安装备份、损坏/错 key/旧水位/非空目标 fail-closed 与隔离候选通过，跨设备密钥、用户流程和物理恢复 hold |
| P0 双端本机恢复候选可回滚激活内核 | `P0-双端本机恢复候选可回滚激活内核验证-20260729.md` | conditional_pass；exact backup 短时确认、PREPARED-before-staging、HMAC crash journal、失败回旧 live与候选不消费已实现；Android v14 另对未过期 Agent 审计做单调 union/冲突容量 fail-closed，恢复类 5/5 已在 API 36 / 16 KB AVD 执行；用户 UI、跨设备 key、iOS fault-injection XCTest 与物理恢复 hold |
| P0 双端生产复用运行时与无正文遥测 | `P0-双端生产复用运行时与无正文遥测验证-20260726.md` | conditional_pass；四类本机复用、事务内 exact revision 解析、双端 Search 单入口/五类反馈、Restricted 排除与无正文遥测通过；Android API 36 / 16 KB AVD 已执行，iOS 新 Xcode/XCUITest、真实 helpful 与物理设备 hold |
| P0 双端本机来源谱系与删除语义 | `P0-双端本机来源谱系与删除语义验证-20260726.md` | conditional_pass；v11/v6 引入 terminal identity guard/单来源 cascade/外部 Raw 边界、app-owned Raw-only/pending retry/SourceObject 权威备份/source watermark；Android SQLCipher 后续已由 2026-07-29 API 36 / 16 KB 全量回归补证，多来源由字段证据报告收敛，account/space、peer proof 与物理设备仍 hold |
| P0 双端本机 Space 冻结与删除水位 | `P0-双端本机Space冻结与删除水位验证-20260726.md` | conditional_pass；v11/v6 引入并在当前 Android v14/iOS v8 继续保留的 Personal space 根水位、投影收敛、写入冻结、app-owned Raw/locator cleanup、旧备份拒绝与重载防复活通过；Android v7–v13 SQLCipher 后续已由 2026-07-29 API 36 / 16 KB 全量回归补证，账号/Grant/peer/provider 原件/物理擦除与物理设备 hold |
| P0 双端本机 Space 删除授权与待处理快照收敛 | `P0-双端本机Space删除授权与待处理快照收敛-20260729.md` | conditional_pass；设置页逐字确认、当前安装 Agent runtime/pairing/连接元数据收敛、pending action/export/incoming-share 清理与 marker 防复活通过；Android JVM 107/107、API 36 / 16 KB AVD 100/93/7/0、iOS 生产 Smoke 与静态契约 206 项通过，iOS XCTest、账号/共享 Grant registry/peer/provider/物理设备 hold |
| P0 双端字段来源证据与多来源删除重算 | `P0-双端字段来源证据与多来源删除重算验证-20260726.md` | conditional_pass；Android schema v12 / iOS envelope v7 引入 content-free exact-revision 字段证据、完整映射、多来源保守重算、字段失证整 Event 删除、legacy/用户 revision fail-closed 与重载；Android SQLCipher 后续已由 2026-07-29 API 36 / 16 KB 全量回归补证，用户确认由 2026-07-29 独立报告更新，iOS XCTest/物理设备与外部删除 proof hold |
| P0 双端用户确认字段证据与来源删除保留 | `P0-双端用户确认字段证据与来源删除保留验证-20260729.md` | conditional_pass；Android schema v13 / iOS envelope v8 的 content-free exact-revision 完整确认删源保留、来源声明终结、partial/stale/legacy fail-closed、v12/v7 无猜测迁移、认证备份与 iOS 生产 Smoke 通过；Android SQLCipher 已在 API 36 / 16 KB AVD 执行，真实用户、iOS XCTest/物理设备和外部删除 proof hold |
| P0 Android 生产 Agent Revision 写入 | `P0-Android生产Agent-Revision写入验证-20260726.md` | conditional_pass；记录 Revision 切片当时的 exact-Grant `append_revision`、SQLCipher 原子 Revision/幂等、敏感目标隐藏及 Host MCP/TLS adapter；当时的 undo 关闭结论已由后续独立撤销报告更新 |
| P0 Android 生产 Agent Event/Revision 撤销 | `P0-Android生产Agent-EventRevision撤销验证-20260726.md` | conditional_pass；记录撤销切片的 exact token、10 分钟首次时窗、Event tombstone、Revision compensation/head conflict、SQLCipher 持久幂等及 Host MCP/TLS adapter；当时的 read 关闭结论已由后续最小读取报告更新 |
| P0 Android 生产 Agent 最小读取 | `P0-Android生产Agent-最小读取验证-20260726.md` | conditional_pass；bounded `visible_events`、SQLCipher current projection、scope/sensitivity/time/query/delete、正文截断、Host Recall/Context injection/budget 与 TLS 通过；真实 Host/设备/共享 Grant/发布 hold |
| P0 iOS 生产 Agent 四操作客户端与响应校验 | `P0-iOS生产Agent-四操作客户端与响应校验-20260729.md` | conditional_pass；iOS 四项 Android 生产 v1 operation 已有最小 Grant builder、握手 capability、typed result、canonical/result-digest/error-shape fail-closed，并在 API 36 / 16 KB AVD 完成 Swift→Android 四操作纵向 Smoke；普通用户默认仍 event-only，物理设备、共享 Grant 与发布 hold |
| P0 Android 生产 Agent 访问审计与只读投影 | `P0-Android生产Agent-访问审计与只读投影-20260729.md` | conditional_pass；SQLCipher schema v14 增加 STARTED/COMPLETED content-free access audit、180 天保留、append-only/提前删除保护、审计故障失败关闭、幂等重试和设置页最近 20 条；同安装恢复单调保全未过期账本，相关 instrumentation 已在 API 36 / 16 KB AVD 全量 100/93/7/0 执行；iOS Host/账户审计、真实用户/物理设备 hold |

## 当前正本

| 主题 | 路径 | 状态 |
|---|---|---|
| MVP 测试与 AI 评测 | `MVP测试与AI评测策略.md` | v0.1，层级/夹具/旅程/环境/Gate 候选 |
| 验证报告模板 | `MVP验证报告模板.md` | 当前有效，强制前五行 verdict 与可复现证据 |
| Android DB-01 性能探针 | `Android-DB01性能探针说明.md` | API 36 AVD 有效基线；物理设备/16 KB/UI 仍待验证 |

## 后续范围

- 自动化测试策略、AI 评测、性能、安全、同步、迁移、删除传播、Beta 指标和 Gate 验证报告。

## 变更日志

| 日期 | 操作 | 说明 |
|---|---|---|
| 2026-07-13 | 建立 | 创建质量与验证正式文档域 |
| 2026-07-13 | 新增 | 形成 MVP 测试/AI Eval/性能/安全/环境/Gate 正本和验证报告模板 |
| 2026-07-14 | 新增 | 登记 Android DB-01 10k/100k 性能探针、判定门与待验证边界 |
| 2026-07-14 | 验证 | 固化 API 36 AVD 10k/100k 报告；100k FTS P95 177.72 ms、单次提交 P95 215.67 ms，两项硬门通过 |
| 2026-07-14 | 验证 | 移动来源、配对 Agent TLS 写入、显式结构化 AI 小结和重启持久化形成完整合成体验；真实模型/真机/双端仍保留 |
| 2026-07-15 | 验证 | Android Debug 三种普通用户连接入口收敛到统一授权/成功/断开流程，56 项设备回归 49 通过、7 Gate 跳过、0 失败；真实 NSD/QR/账户仍保留 |
| 2026-07-18 | 新增/验证 | Android 16 KB AVD UI 验收报告；52 项可执行测试通过、7 Gate 跳过，补充截图与 UIAutomator 语义证据；物理设备与 TalkBack 仍待补 |
| 2026-07-18 | 新增/验证 | iOS Shared Core 条件通过报告；补充 Keychain/加密本机闭环、媒体密文、照片引用、批量导入回滚与幂等证据；完整 Xcode/iOS UI/真机/VoiceOver 仍待补 |
| 2026-07-18 | 更新/验证 | 双端产品完善继续收口：iOS 来源标签搜索、录音/日历边界和 VoiceOver 文案，Android 状态卡片 Button 语义；Android AVD 回归为 52 通过、7 跳过、0 失败 |
| 2026-07-18 | 更新/验证 | 双端结构化导出状态值统一为 wire value；iOS Smoke 与 Android 单测通过，完整设备级导出/分享验证仍待补 |
| 2026-07-18 | 更新/验证 | iOS 多词搜索与 Android 逐词 AND 契约对齐；日历导入新增用户选择日历及今天/7 天/31 天范围，App 构建、目标解析和 Shared Smoke 通过 |
| 2026-07-18 | 更新/验证 | Android Fake Repository 与 SQLCipher/LIKE 搜索契约对齐；新增跨字段多词单测，Debug 单测/lint/APK 和 16 KB AVD 回归通过 |
| 2026-07-18 | 更新/验证 | Android 普通本机路径按事件数量解析空白/稀疏/正常状态，显式系统状态优先；新增 ExperienceMode 单测，Debug 单测/lint/APK 和 16 KB AVD 回归通过 |
| 2026-07-18 | 更新/验证 | 双端设置来源状态收口：Android UI smoke 验证照片按次选择、日历权限和系统录音能力文案；iOS App target build/目标 parse 验证 Photos/AVAudioSession/EventKit 状态读取 |
| 2026-07-18 | 更新/验证 | 第十三批主动来源与恢复继续收口：Android ACTION_SEND 先确认再落库、导出失败可重试；iOS 文件导入确认、opaque incoming-share handoff 与重新生成导出入口完成；两端构建/Smoke 通过，真实 iOS Share Extension 与设备级回归仍待补 |
| 2026-07-18 | 新增/验证 | 第十四批跨端状态与恢复矩阵：iOS `ExperienceMode` 与 Android 内容状态规则对齐，新增逐页状态/恢复/证据矩阵；Shared XCTest 源码、Android 单测/AVD 作为设备前基线，真机与完整无障碍仍待补 |
| 2026-07-18 | 新增/验证 | 第十五批真实 iOS 分享扩展输入：Shared Core 统一 App Group 路径，新增 Share Extension 源码/Info.plist/entitlements、取消/重试/重复回调和大内容边界；源码解析、bounded Smoke、App build 与 25 项静态输入治理通过，完整 Xcode target/签名/真机 Share Sheet 仍待补 |
| 2026-07-18 | 新增/验证 | 第十六批双端待处理动作恢复：Android Keystore 加密 pending-action 快照、Activity recreation 确认门和损坏 fail-closed 通过；iOS App Group handoff 增加 pending-ID 启动扫描与 Shared Smoke 断言；Android AVD 全量 55 项可执行通过、7 项跳过，完整设备生命周期仍待补 |
| 2026-07-18 | 更新/验证 | 第十六批无障碍收口：修复 iOS 恢复状态卡片将“重试”与状态文本合并的 VoiceOver 风险，新增 14 项 SwiftUI 静态 accessibility contract 检查；VoiceOver/TalkBack 真机读屏仍待补 |
| 2026-07-18 | 新增/验证 | 第十七批双端结构化导出恢复与隐私清理：iOS 新增 Keychain/AES-GCM 版本化待导出快照、重启恢复与主动清理，Android 设置页补齐未完成导出清理；iOS Shared Smoke、Android 定向 UI smoke 和 16 KB AVD 全量 64/57/7/0 通过，系统 Share Sheet/物理设备仍待补 |
| 2026-07-18 | 新增/验证 | 第十八批双端设备连接体验状态：iOS 新增三入口统一的非敏感连接状态、30 天期限、过期/损坏 fail-closed 和显式断开，与 Android PairingExperience 状态/Mock 授权路径对齐；iOS App build/Shared Smoke 通过，Android 16 KB AVD 全量 64/57/7/0 通过，真实 NSD/二维码/账户设备/LAN/Grant 与物理设备仍待补 |
| 2026-07-18 | 新增/验证 | 第十九批双端真实发现适配：Android Release 接入 NSD `_ameme-agent._tcp.`，iOS Shared 接入 Bonjour/Network.framework 发现会话与本地网络声明；发现只产生非敏感候选，授权/Grant/二维码/账户设备/数据传输仍 fail closed；Android Debug/Release 构建与 AVD、iOS Shared/App build/Smoke/目标解析通过 |
| 2026-07-18 | 新增/验证 | 第二十批双端连接入口对齐：iOS 设置页接入 Bonjour 真实发现、授权前候选和显式演示连接；二维码/账户设备未接入时明确失败，真实候选不会保存为连接；iOS Shared/App build、Smoke/目标解析与 Android 既有 NSD/Mock/AVD 基线通过 |
| 2026-07-18 | 新增/验证 | 第二十一批双端 AccessGrant/policy：iOS Shared 增加 canonical Grant 与本地 policy 绑定，Android pairing 持久化 30 天 Personal/`autonomous_memory`/structured `event` scope 并在 endpoint 前置拒绝扩权；两端授权卡片显示拟授权范围。iOS Shared/App build、Smoke/目标解析、Share 输入 25 项和 accessibility 静态契约 14 项通过；Android Debug/Release JVM、lint、assemble、64/57/7/0 AVD、1/1 授权 UI smoke、TalkBack AVD 语义探针与配对 Host/Android/SQLCipher/Today smoke 通过 |
| 2026-07-18 | 新增/验证 | 第二十二/二十三批连接收口：iOS Local Node TLS 1.3/pin/HMAC client、Grant-bound request 与 Swift→Android AVD 真实传输子门通过；随后补齐 iOS duplicate-key pairing 拒绝和 Android/iOS connector disconnect 契约，iOS App build、Shared Smoke、Android Debug/Release JVM 与 lint 通过；QR/account registry、reconnect 和设备级门禁仍待补 |
| 2026-07-26 | 新增/验证 | M24 双端工程交付收口：补齐 installable Xcode 工程/Share Extension/Unit/UI CI、Android Debug/Release 与 API 36 16 KB 设备 CI、短时 QR 真连接、真实/Mock 双路径、大字号和截图证据；仓库工程候选 pass，物理设备/读屏/签名/商店继续 hold |
| 2026-07-26 | 新增/验证 | M25 双端平台视觉升级：iOS 按选定方向收敛为原生 Liquid Glass 控制层与平面内容，Android 对齐当前稳定 Material 3 并迁移 Material Symbols；默认/XXXL、130%/200%、深色、语义、真实构建和设备自动化全绿，物理设备/真实读屏/签名/商店继续 hold |
| 2026-07-26 | 新增/验证 | P0 上下文覆盖与长期记忆闭环：5 分群/10 上下文/10 来源能力、3 个合成 user-day 和 4,262 项 Coverage 校验通过；Core 46、Agent 35、统一工作区 14/14 Gate 通过，真实 Pilot/规模/生产恢复继续 hold |
| 2026-07-26 | 新增/验证 | P0 双端生产 Coverage 运行时：双端六项当前 Mobile 来源、Coverage 编译与拒权/失败/不可用降级进入生产模块；Android 9/9、Swift Shared build/smoke、跨端静态契约 247 项和统一工作区 15/15 Gate 通过，持久化/真机/真实用户/生产恢复继续 hold |
| 2026-07-26 | 新增/验证 | P0 双端 Coverage 持久化与显式 Event 接线：Android SQLCipher schema v7 与 iOS AES-GCM envelope v2 保存 Coverage 但不隐式创建 Event；显式接受原子写入 Event/Revision/link，删除 detach 且不复活。Android Coverage 11/11、全量 JVM 72/72、lint/APK/androidTest 编译、iOS 生产 smoke 与跨端静态契约 279 项通过；Android instrumentation、长期 Memory、恢复和真机继续 hold |
| 2026-07-26 | 新增/验证 | P0 双端生产长期 Memory：Android SQLCipher schema v8 与 iOS AES-GCM envelope v3 加入 9 类 Event-revision-bound 候选、显式确认、有效期、替代和上游失效；Agent 保持 Event-only。Android 全量 JVM 75/75、lint/APK/androidTest 编译、iOS 生产 smoke、跨端静态契约 92 项和统一工作区 16/16 通过；设备 SQLCipher/XCTest、ContextPack/恢复/真机继续 hold |
| 2026-07-26 | 新增/验证 | P0 双端本机恢复候选与删除水位：Android SQLCipher schema v9 与 iOS AES-GCM envelope v4 加入不含正文且不可回退的 Event 删除水位、认证同安装备份和隔离恢复候选；损坏、错 key、旧水位与非空目标 fail closed。当前 v13/v8 另有 exact-confirmation/HMAC journal 同安装激活内核；Android API 36 / 16 KB AVD 专项 3/3 与全量 91/84/7/0、iOS 生产 Smoke 通过，跨设备 key、用户 UI、iOS XCTest、物理恢复和分布式删除继续 hold |
| 2026-07-26 | 新增/验证 | P0 双端生产复用运行时与无正文遥测：Android SQLCipher schema v10 与 iOS AES-GCM envelope v5 加入历史搜索、显式关键词项目续接、会前上下文、决定/承诺找回的共同 API；短时引用按 exact revision 二次验证，Restricted 排除，持久层只保留加盐摘要、结果和动作。iOS 生产 smoke、Android JVM/androidTest 编译与跨端静态契约 132 项通过；最终 UI、真实用户 helpful、Agent read、设备执行继续 hold |
| 2026-07-26 | 新增/验证 | P0 双端本机来源谱系与删除语义：Android SQLCipher schema v11 增加 source objects/link/job、terminal identity guard、单来源 cascade、跨 space 隔离、晚期失败全事务回滚与 source watermark，明确无应用自有 Raw；iOS envelope v6 增加 app-owned ciphertext Raw-only、pending cleanup/retry、SourceObject 权威备份、外部原件 local-only completion 与单来源 cascade。Android JVM 81/81、lint/APK/androidTest 编译，iOS Shared build/生产 smoke、跨端静态契约 130 项通过；XCTest/设备、multi-source、account/space 与 peer proof hold |
| 2026-07-26 | 新增/验证 | P0 双端本机 Space 冻结与删除水位：复用 schema v11/envelope v6 的通用 root tombstone，原子收敛当前安装 Personal space、冻结重载后读写、完成本机 Raw/locator cleanup 并拒绝旧备份；Android JVM 85/85、lint/APK/androidTest 编译、iOS Shared/App build/生产 smoke、跨端静态契约 112 项通过，账号/Grant/peer/provider 原件/物理擦除/设备执行 hold |
| 2026-07-29 | 新增/验证 | P0 双端本机 Space 删除授权与待处理快照收敛：产品入口逐字确认后停止当前 Agent runtime、清除当前安装 pairing/连接状态与待处理 action/export/share 快照，并以 content-free marker 阻止重启复活；Android JVM 107/107、API 36 / 16 KB AVD 100/93/7/0、iOS 生产 Smoke、静态契约 206 项通过，iOS XCTest、账号/共享 Grant registry/peer/provider/物理设备继续 hold |
| 2026-07-26 | 新增/验证 | P0 双端字段来源证据与多来源删除重算：Android schema v12 / iOS envelope v7 加入 exact-revision `EventFieldEvidence`，仅在每个字段仍有来源时重算，字段失证则删除 Event，legacy/用户 revision stale fail closed；Android JVM 85/85、lint/APK/androidTest 编译、iOS build/生产 smoke、字段 Gate 111 与来源删除 133 项通过，XCTest/设备与外部删除 proof hold |
| 2026-07-26 | 新增/验证 | P0 Android Agent Revision：冻结 v1 上接通 Android/Host `append_revision`、SQLCipher 原子 revision/current/idempotency、sensitivity 不可见目标折叠与 MCP/TLS 恶意结果拒绝；该切片当时 read/undo/长期 Memory 不扩权，后续 undo 由独立报告更新 |
| 2026-07-26 | 新增/验证 | P0 Android Agent Event/Revision 撤销：冻结 v1 上接通 Android/Host exact `undo_capture`、10 分钟首次时窗、Event tombstone、Revision compensation/head conflict、SQLCipher 重开重放和恶意结果拒绝；read/跨端传播/设备/真实宿主/共享 Grant/发布 hold |
| 2026-07-26 | 新增/验证 | P0 Android Agent 最小读取：冻结 v1 上接通 Android/Host bounded `visible_events`，限定 Personal/Event/structured、query/time/limit/session sensitivity，Restricted 不泄漏、正文截断，Host Recall/Context injection/budget 与 TLS 回归通过；`get_event`/策略/长期 Memory 关闭，真实 Host/设备/共享 Grant/发布 hold |
| 2026-07-29 | 新增/验证 | P0 双端用户确认字段证据与来源删除保留：Android schema v13 / iOS envelope v8 新增 content-free exact-revision `EventUserConfirmation`；完整显式 Candidate 确认删源后保留 Event 但终结 source claim，partial/stale/legacy 删除或 fail closed，迁移不猜测旧动作；iOS 生产 Smoke 与 Android API 36 / 16 KB SQLCipher instrumentation 已实际通过，iOS XCTest/真实用户/物理设备 hold |
| 2026-07-29 | 更新/验证 | 双端复用与当前平台 UI 收敛：iOS Search 改为单一扁平入口、原生 `confirmationDialog` 与结果 Sheet；Android 保持当前 Material 3，把 Search 改为单一 `LazyColumn` 并在 API 36 / 16 KB、320dp、130% 字号下完成 91/84/7/0 全量回归、完整 UI 12/12 和截图复核。iOS 新 Xcode/XCUITest/截图、物理设备与真实读屏/用户仍 hold |
| 2026-07-29 | 更新/验证 | 双端本机 Space 删除入口补齐本机授权与待处理载荷收敛：Android 停止 Local Node、撤 pairing、冻结 Keystore pending actions；iOS 断开 Agent、冻结 Pending Export 与 App Group handoff，并清理隐藏原子临时载荷。Android 107/107 JVM、API 36 / 16 KB AVD 100/93/7/0、iOS Shared/App/Smoke、跨端静态 Gate 206 和 workspace 28/28 通过；账号/共享 Grant/provider/peer/物理擦除、最终 Xcode head 与物理设备继续 hold |
| 2026-07-29 | 新增/验证 | P0 双端本机恢复候选可回滚激活：exact backup 15 分钟确认、切换前后水位/完整性复核、HMAC `prepared/committed` journal、中途失败回旧 live、成功后候选不消费和伪造 journal fail closed；iOS 生产 Smoke 与 Android API 36 / 16 KB AVD 恢复类 3/3、全量 91/84/7/0 通过，用户 UI/跨设备 key/物理故障注入继续 hold |
| 2026-07-29 | 新增/验证 | P0 iOS Agent 四操作客户端：在普通用户 QR Grant 仍为 event-only 的前提下，为 `append_revision`、exact `undo_capture`、bounded `visible_events` 补齐 canonical builder、握手 operation 检查、typed result 与 result digest/严格 error-shape 校验；原始 exchange 私有化且生产 exchange 只按当前时间授权。Shared/App/Smoke build、生产 Shared Smoke、82 项静态门、workspace 27/27 与 API 36 / 16 KB AVD 四操作纵向 Smoke 通过，物理设备/共享 Grant/真实 Host hold |
| 2026-07-29 | 新增/验证 | P0 Android Agent 访问审计：schema v14 为四项生产 operation 增加 repository 前 STARTED 与完成态 COMPLETED、180 天单点保留、数量桶、append-only/提前删除保护、失败关闭和设置页只读投影；JVM 与 androidTest 编译通过，新增 instrumentation 未争用并行 AVD，iOS Host/账户审计、真实用户/物理设备 hold |
| 2026-07-29 | 更新/验证 | Android 同安装恢复不再回退安全账本：PREPARED journal 前移覆盖 staging/merge 崩溃窗口，未过期 Agent audit 按 exact ID/trace-phase 单调 union，冲突/50,000 容量溢出 fail closed，并以账本与合并 SQLCipher 双摘要绑定原子换库；JVM 103/103、非设备构建、audit 143、recovery 138 与 workspace 28/28 通过，新增 instrumentation 仅编译 |
| 2026-07-29 | 更新/验证 | Android Agent 审计与恢复账本设备复验：API 36 / 16 KB、320dp、130% 字号 AVD 全量 100/93/7/0，恢复 5/5、审计持久化 2/2、生产端点 3/3、完整 UI 13/13；这是后续设备补证，不把 AVD 扩写为物理设备 |
