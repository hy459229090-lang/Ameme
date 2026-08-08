<div align="center">

# Ameme

**让散落在设备与服务里的生活片段，成为由你控制、可追溯、可纠正的长期上下文。**

[![Workspace Health](https://github.com/hy459229090-lang/Ameme/actions/workflows/workspace-health.yml/badge.svg)](https://github.com/hy459229090-lang/Ameme/actions/workflows/workspace-health.yml)
[![iOS CI](https://github.com/hy459229090-lang/Ameme/actions/workflows/ios.yml/badge.svg)](https://github.com/hy459229090-lang/Ameme/actions/workflows/ios.yml)
[![Android CI](https://github.com/hy459229090-lang/Ameme/actions/workflows/android.yml/badge.svg)](https://github.com/hy459229090-lang/Ameme/actions/workflows/android.yml)
![Status](https://img.shields.io/badge/status-active%20development-6C63FF)
![Privacy](https://img.shields.io/badge/privacy-local--first-0F9D8A)

</div>

Ameme 是一个正在开发中的、本地优先个人记忆基础设施。它把用户主动记录或明确授权的信息组织成连续的 `DayLedger`（每日事件账本），保留来源、修订与删除链路，再向 AI 和 Agent 提供边界清晰、可以撤销的长期上下文。

它不是把所有原始数据上传给模型的“云端记忆箱”，也不会为了显得聪明而在后台无差别采集。Ameme 更关心一件事：**当 AI 记得你时，你仍然知道它记得什么、为什么记得，以及如何改正或删除。**

![Ameme 将日常片段整理成由用户控制的长期上下文](docs/product/assets/ameme-overview-user-controlled-context.png)

> [!IMPORTANT]
> 截至 2026-07-30，仓库工程候选已通过现有自动化门禁，包含可构建的 iOS 与 Android 原生产品、固定演示数据、加密本机存储和受限 Local Node 通道。这些证据来自 CI、Simulator、AVD、合成数据和 Mock；Ameme 尚未公开发布，物理设备 Beta、真实用户、签名分发、生产账户/模型与商店审核仍是独立发布门。

## 30 秒理解 Ameme

| 你做什么 | Ameme 做什么 | AI / Agent 得到什么 | 你始终保留什么 |
|---|---|---|---|
| 主动记录文字、照片、语音，或明确授权日历与分享导入 | 在本机把片段整理成带来源和 Revision 的 Event | 经过选择、结构化且受 Grant 限制的上下文 | 查看、修订、删除、导出和撤销能力 |
| 在“今天”查看真实发生的内容 | 把同一天的 Event 组成连续 `DayLedger` | 用户确认后的每日小结或精确事件范围 | 原始证据、AI 推断和用户确认之间的清晰边界 |
| 允许某个 Agent 完成具体任务 | 只打开指定 Space、数据类型、操作和期限 | 完成任务所需的最小上下文 | 访问记录、授权撤销和失败关闭 |

## 从日常片段到 DayLedger

```text
主动记录 / 授权导入
          ↓
Event + SourceLocator（来源与证据）
          ↓
DayLedger（连续的一天）
          ↓
用户确认的结构化小结与长期记忆
          ↓
可撤销、可审计的 AI / Agent 上下文
```

文字、照片、语音、日历与显式分享会进入同一条时间线。每条 Event 都保留来源、证据、修改记录和当前投影；AI 生成的观察不会自动冒充用户确认的事实。

![从日常片段到可追溯 DayLedger 的功能闭环](docs/product/assets/ameme-feature-dayledger.png)

## 为什么坚持本地优先

- **默认留在设备**：MVP 不建设承载用户记忆正文的数据云，本机数据库使用 SQLCipher 加密。
- **用户发起或明确授权**：权限只在实际使用来源时请求；无感体验不等于无授权采集。
- **证据分层**：原始证据、解析观察、AI 推断与用户确认不会混为一谈。
- **可追溯、可纠正、可删除**：Event、Revision、来源、删除水位和访问审计从第一版进入数据模型。
- **分区与最小授权**：工作、个人、家庭、健康与财务等范围可隔离；Agent 只能在精确 Grant 内工作。

![Ameme 的本地优先、分区与显式授权设计](docs/product/assets/ameme-feature-local-first.png)

## 产品体验

iOS 与 Android 都把“今天”作为唯一默认主页：一个记录入口接收文字、照片、语音、文件/分享和范围化日历导入；右上搜索进入统一历史日流。真实本机库与固定演示数据相互隔离，数据稀疏是正常状态，真实路径不会用虚构内容填满界面。

两端优先采用平台原生交互而不是追求像素一致：iOS 26+ 使用系统 Liquid Glass 控制面并为 iOS 18–25 提供 Material 降级；Android 使用稳定版 Material 3 与官方 Material Symbols。共同目标是简洁、高效、安静、可信。

![今天、单一记录入口与统一历史日流的产品设计](docs/product/assets/ameme-feature-mobile-design.png)

## Agent 可以使用记忆，但不能接管记忆

Ameme 把 Agent 访问设计成一个可见、可撤销的闭环：

1. 用户选择用途、Space、数据类型、操作范围和有效期。
2. Local Node 只暴露该 Grant 允许的结构化 Event；敏感正文、Raw 和越界字段默认关闭。
3. 每次启用的读写都会留下不含正文的访问审计；审计写入失败时操作失败关闭。
4. Revision 和捕获支持精确撤销，删除继续保留 tombstone / compensation 证据，避免内容悄悄复活。

![用户确认、精确授权、访问审计与撤销组成可信 Agent 闭环](docs/product/assets/ameme-feature-trusted-agent-loop.png)

## 当前已有实现

| 领域 | 当前仓库中的实现 | 仍未越过的边界 |
|---|---|---|
| iOS | SwiftUI 客户端、静态 Shared Core、嵌入式 Share Extension、XcodeGen 工程、Unit/UI Test targets；iOS 26 原生视觉与 iOS 18+ 降级 | 签名、物理设备、真实 VoiceOver、App Store 与生产权限仍需发布验收 |
| Android | Kotlin / Jetpack Compose 原生客户端，Material 3，`minSdk 34`、`targetSdk 36` | OEM、物理设备、真实 TalkBack、后台与商店流程仍需发布验收 |
| 本地存储 | SQLCipher 加密 Event、Revision、DayLedger、Summary、搜索索引、删除水位与 content-free Agent 审计 | 物理擦除、账户级删除和分布式删除仍待验证 |
| 同安装恢复 | 双端可创建有界恢复点，只有隔离恢复成功后才显示健康，并要求逐字确认切换 | 依赖当前安装的设备密钥；不支持卸载、换机、设备丢失或跨设备生产恢复 |
| 信息输入 | 双端文字、按次照片、显式 Share、语音结果/录音、范围化日历导入 | 不做后台全量采集；真实来源仍需物理设备与用户验收 |
| Agent | `ameme-memory` Skill、本地 MCP Host、精确授权、风险评测、Event/Revision 读写与撤销 | 真实第三方生产宿主、共享账户 Grant、ContextPack 与跨端撤销传播尚未完成 |
| AI | 可替换推理网关、固定合成评测、用户确认后生成每日小结 | 真实生产模型、成本、内容安全和供应商审查仍待补齐 |
| 连接 | QR v2 一次性 bootstrap、独立应用凭据、TLS 1.3 pin、HMAC event-only 通道；Android 支持系统扫码或显式粘贴 | 物理光学扫码、真实 LAN / P2P、后台同步、账户设备 registry 尚未完成 |

更完整的状态、证据与保留门见 [STATUS.md](STATUS.md)。

## 已验证与保留门

当前已验证的仓库工程基线包括：

- Workspace、契约、Skill、Markdown 链接与治理校验。
- Xcode 26.6 / iOS 26.5 Simulator 上的 App、Share Extension、Unit/UI Tests 与大字体视觉复验。
- Android API 36、16 KB arm64 AVD 上的 Debug/Release、Lint、SQLCipher、恢复、系统扫码/显式粘贴和设备回归。
- Swift → Android Local Node 的 QR v2、TLS、最小 Grant、Event / Revision 写入、读取与精确撤销纵向 Smoke。

以下结论仍保持 `hold`：

- 真实用户价值、长期留存与目标市场证据。
- iOS / Android 物理设备、真实 VoiceOver / TalkBack、OEM 来源与后台行为。
- 签名、Provisioning、Beta、商店审核、生产监控和回滚。
- 真实账户与共享 Grant、物理 LAN / P2P、跨设备恢复与分布式删除。
- 生产模型、真实 Agent 宿主、成本、安全合规与供应商审查。

Simulator、AVD、Mock 和合成数据不会被写成真机、真实用户或公开发布通过。

## 仓库导航

```text
apps/          iOS 与 Android 原生用户端应用
packages/      共享契约、Core、同步协议、AI 与 Agent Skill
services/      本地 Host、MCP mock 与可替换推理网关
connectors/    信息源适配器入口
research/      可复跑的技术探针与脱敏实验
docs/          产品、架构、安全、质量、发布与运行正本
tests/         合成夹具、契约、集成、安全与评测
scripts/       治理、验证、构建和发布辅助脚本
JOBS/          工作队列、证据、阻塞与确认门
```

- [当前状态](STATUS.md)
- [当前正本与阅读路由](docs/_CURRENT.md)
- [工作区地图](WORKSPACE_MAP.md)
- [产品设计规格](docs/product/MVP产品设计规格.md)
- [架构技术方案](docs/architecture/MVP研发架构技术方案.md)
- [隐私影响评估](docs/privacy-security/MVP隐私影响评估.md)
- [测试与 AI 评测策略](docs/quality/MVP测试与AI评测策略.md)

## 本地验证

完整、无网络的 Workspace / 契约 / Skill / 文档验证：

```powershell
python3.12 -m venv /tmp/ameme-project-py312
/tmp/ameme-project-py312/bin/python -m pip install -r scripts/requirements-dev.txt
/tmp/ameme-project-py312/bin/python scripts/validation/run_workspace_validation.py
```

该基线要求 Python 3.10+；macOS Command Line Tools 自带的 Python 3.9 会被入口脚本明确拒绝，避免在中途 Sync Gate 才因语法不兼容失败。

Android 构建与单元测试（JDK 17、Android SDK Platform 36）：

```powershell
cd apps/android
./gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

连接 API 34+ 模拟器或设备后：

```powershell
./gradlew.bat connectedDebugAndroidTest
```

同一设备序列号上不要并行运行 instrumentation 测试；这些任务会重装同一 application ID。详细环境与证据边界见 [Android README](apps/android/README.md)。

iOS 工程构建（完整 Xcode 26.6 / iOS 26.5 SDK）：

```bash
xcodebuild \
  -project apps/ios/Ameme.xcodeproj \
  -scheme Ameme \
  -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  CODE_SIGNING_ALLOWED=NO \
  build
```

工程由固定版本的 XcodeGen 从 `apps/ios/project.yml` 生成。完整测试、Shared Smoke、当前机器限制与跨端 Smoke 见 [iOS README](apps/ios/README.md)。

## 数据与隐私

本仓库只允许合成测试数据。请勿提交真实浏览历史、聊天正文、照片、音频、精确位置、账单、健康数据、数据库、密钥或访问令牌。测试夹具位于 `tests/fixtures/synthetic/` 及其他明确标记的 synthetic 目录。

## 项目状态与许可

仓库工程候选当前为 `pass`，Gate 1、物理设备 Beta 与公开商店发布继续 `hold`。Ameme 会把失败、跳过和阻塞保留为正式证据，不用“文档写完”替代验证通过。

欢迎通过 Issue 讨论产品边界、架构、安全模型和可复跑验证。当前仓库尚未声明开源许可证；除非另有说明，代码与文档不自动授予再分发或商用许可。
