# Android MVP 完整体验闭环验证（2026-07-14）
- Verdict：`conditional_pass`
- Scope：Android API 36 AVD 的移动来源、配对 Agent 写入、显式 AI 小结与 SQLCipher 持久化
- Build/Commit：本报告所在提交；Debug 0.1.0
- Date/Environment：2026-07-14，Windows 11，Android API 36 x86_64 AVD

## 目标与通过口径

| 口径 | 通过条件 | 结果 |
|---|---|---|
| 移动来源 | 正式 Share/Picker/Calendar/Voice 入口写入 SQLCipher；体验包不直写 Event | 通过 |
| Agent 真传输 | Host 经配对 TLS/HMAC 通道写入 Android；Today 可见且重启保留；ADB 不注入 Event | 通过 |
| AI 小结 | 至少两条合格结构化事件；用户确认后调用无状态网关；结果绑定 DayLedger revision 并持久化 | 通过（deterministic fake） |
| 失败安全 | 网关失败不删除 Event、不伪造模板；Restricted/Raw/原文件不进入请求 | 通过 |
| 回归 | Android JVM/lint/build/device、网关、Python/契约/Skill/治理门无未解释失败 | 通过；真实模型项按设计跳过 |

## 环境与数据

- 设备：`emulator-5556`，API 36，AVD `ameme_source_pack_api36`。
- Android：原生 Kotlin/Compose，SQLCipher schema v6，Debug 网关地址 `http://10.0.2.2:8787`。
- 推理：`DeterministicFakeProvider`，宿主 `127.0.0.1:8787`，无数据库、无正文日志。
- 数据：仅合成 Share、Agent、照片、音频和日历来源；没有真实照片、语音、位置、健康、聊天或工作区正文进入 Git。

## 可复现命令

```powershell
cd E:\Ameme\services\ameme-inference-gateway
npm.cmd run check

cd E:\Ameme\apps\android
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest connectedDebugAndroidTest

cd E:\Ameme
python scripts/dev/agent/smoke_paired_android.py --adb C:\Users\N33131\AppData\Local\Android\Sdk\platform-tools\adb.exe --serial emulator-5556
python scripts/validation/run_workspace_validation.py
```

显式网关设备测试另以 `amemeGatewayLive=true` 运行 `DaySummaryGatewayInstrumentedTest`；普通回归中该测试按设计跳过，避免把本地外部进程变成隐式依赖。

## 结果

| 断言 | 实际结果 | Verdict |
|---|---|---|
| Gateway TypeScript/Node | 9 项：8 通过，真实 OpenAI live 1 项因无显式密钥/开关跳过 | pass |
| Android 普通设备回归 | 52 项发现：45 通过，7 项显式 gate 跳过，0 失败 | pass |
| Android JVM/lint/build | `testDebugUnitTest`、`lintDebug`、Debug/Test APK 均通过 | pass |
| Android→Gateway 目标测试 | 1/1；Android `GMT` 时区、结构化请求和响应绑定通过 | pass |
| 移动 Share 目标测试 | 7/7；显式文字为 `UserAsserted`，图片/PDF仍保持待处理 | pass |
| Agent Host→Android | `durable`、`local_only`、Today 可见、重启保留、ADB Event 注入为 false | pass |
| 今日小结 UI | `2026-07-14 · 2 events` 生成成功，App 强停重启后仍存在 | pass |
| Python/契约/Skill | 169 项 Python 基线、2,715/2,709 契约、68 项 Agent 协议、12/12 AI Eval、14-case/14-risk Skill 通过 | pass |

## 失败记录与修复

| 失败 | 原因 | 修复与复验 |
|---|---|---|
| 分享文字显示“整理中”，不能进入小结 | Share 文字误沿用媒体待处理状态 | 改为 `UserAsserted` + `EvidenceState.UserAsserted`；目标测试 7/7 |
| AVD 无法通过 `adb reverse` 访问网关 | 当前 AVD reverse 通道无响应 | Debug 改用 Android 官方宿主别名 `10.0.2.2`；Release 仍无内置 URL/明文入口 |
| 网关拒绝 Android 请求 | AVD 系统时区 ID 为合法 `GMT`，旧校验只接受 `UTC`/IANA region | 支持受限固定时区并拒绝越界偏移；网关与设备目标测试通过 |
| 首轮普通设备回归 3 项失败 | UI 测试仍找旧引导文案且未隔离已持久化 onboarding 状态 | 使用稳定正式文案并在 Activity 启动前清理测试偏好；3/3 目标回归及全量回归通过 |

## 限制与判定

- `conditional_pass` 只表示 Android 模拟器上的完整合成体验已经可用，不代表 Gate 1 用户价值验证完成。
- 真实 OpenAI/其他模型调用没有密钥且未运行；当前只证明 provider contract、结构化边界和本地假模型闭环。
- `npm audit --omit=dev` 未取得审计结果：本机配置的网易 npm 镜像对 audit API 返回 HTTP 404；不能把本次依赖审计记为通过。
- iOS 等待 Mac；位置、Health、结构化导出、NSD/LAN 自动发现、后台 Agent、物理设备/16 KB/OEM 来源和共享账户 Grant registry 仍未完成。
- 本地截图和内容安全日志只作当次 Review 证据，不提交 Git；仓库报告只保留无正文断言和命令。

## Review 结论

继续进入 Android MVP 体验迭代与物理设备验证；不得把本报告升级成真实用户、真实模型、双端、物理 LAN 或公开发布证据。
