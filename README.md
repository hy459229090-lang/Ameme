<div align="center">

# Ameme

**让散落在设备与服务里的生活片段，成为由你控制、可追溯、可纠正的长期上下文。**

[![Workspace Health](https://github.com/hy459229090-lang/Ameme/actions/workflows/workspace-health.yml/badge.svg)](https://github.com/hy459229090-lang/Ameme/actions/workflows/workspace-health.yml)
[![Android CI](https://github.com/hy459229090-lang/Ameme/actions/workflows/android.yml/badge.svg)](https://github.com/hy459229090-lang/Ameme/actions/workflows/android.yml)
![Status](https://img.shields.io/badge/status-active%20development-6C63FF)
![Privacy](https://img.shields.io/badge/privacy-local--first-0F9D8A)

</div>

Ameme 是一个正在开发中的、本地优先的个人记忆基础设施。它把用户主动记录或明确授权的信息组织成连续的 `DayLedger`（每日事件账本），保留来源、修改和删除链路，再向 AI 提供边界清晰的长期上下文。

> [!IMPORTANT]
> Ameme 目前处于 Discovery / MVP 工程实现阶段，尚未公开发布。仓库中的 Android Debug 流程、合成数据和模拟连接用于验证产品与技术闭环，不代表真实联网、跨设备同步或生产可用性。

## 为什么做 Ameme

人与 AI 的对话越来越多，但真正有价值的上下文仍散落在笔记、日历、照片、语音、聊天和不同设备中。传统做法通常要求用户反复整理，或者把大量原始数据交给不可见的云端系统。

Ameme 探索另一条路径：

- **本地优先**：记忆数据默认留在用户设备；MVP 不建设用户记忆数据云。
- **用户发起或明确授权**：无感不等于无授权，采集入口、范围与用途始终可见。
- **证据分层**：原始证据、解析观察、AI 推断和用户确认不会混为一谈。
- **可追溯、可纠正、可删除**：每条记忆保留来源与 Revision，删除、导出和访问审计从第一版进入架构。
- **面向 Agent，但不交出控制权**：Agent 只能在精确 Grant 范围内工作；敏感数据、扩权和删除有独立门禁。

![Ameme 的本地优先、分区与显式授权设计](docs/product/assets/ameme-feature-local-first.png)

## 核心闭环

```text
主动记录 / 授权导入
          ↓
Event + SourceLocator（来源与证据）
          ↓
DayLedger（连续的一天）
          ↓
用户确认的结构化小结
          ↓
可撤销、可审计的 AI / Agent 上下文
```

![从日常片段到可追溯 DayLedger 的功能闭环](docs/product/assets/ameme-feature-dayledger.png)

当前 Android 原型以“今天”为唯一默认主页，通过一个记录按钮接收文字、照片、语音和日历导入；统一历史日流负责找回。数据稀疏是正常状态，产品不会用虚构内容填满界面。

![今天、单一记录入口与统一历史日流的产品设计](docs/product/assets/ameme-feature-mobile-design.png)

## 已有实现

| 领域 | 当前仓库中的实现 | 边界 |
|---|---|---|
| Android | 原生 Kotlin / Jetpack Compose 客户端，`minSdk 34` | 主要证据来自 API 36 x86_64 AVD，仍需真机验证 |
| 本地存储 | SQLCipher 加密 Event、Revision、DayLedger、Summary 与搜索索引 | 完整物理清除和分布式删除仍待验证 |
| 信息输入 | 文字、Photo Picker、显式 Share、系统语音结果、范围化日历导入 | 不做后台全量采集，不申请媒体库或麦克风权限 |
| Agent | `ameme-memory` Skill、本地 MCP Host、授权与风险评测 | 真实第三方宿主与共享账户 Grant 尚未完成 |
| AI | 可替换推理网关、固定合成评测、用户确认后生成每日小结 | 真实模型、成本和生产安全证据仍待补齐 |
| 同步 | 协议、确定性模拟器与一致性向量 | 真实 LAN 发现、真机 P2P 与后台同步尚未完成 |

更完整的当前状态与证据边界见 [STATUS.md](STATUS.md)。

## 仓库导航

```text
apps/          用户端应用（当前包含 Android MVP）
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

完整、无网络的 Python / 契约 / Skill / 文档治理验证：

```powershell
python scripts/validation/run_workspace_validation.py
```

Android 构建与单元测试（JDK 17、Android SDK Platform 36）：

```powershell
cd apps/android
./gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

连接 API 34+ 模拟器或设备后：

```powershell
./gradlew.bat connectedDebugAndroidTest
```

同一设备序列号上不要并行运行 instrumentation 测试；这些任务会重装同一 application ID。详细环境与已验证范围见 [Android README](apps/android/README.md)。

## 数据与隐私

本仓库只允许合成测试数据。请勿提交真实浏览历史、聊天正文、照片、音频、精确位置、账单、健康数据、数据库、密钥或访问令牌。测试夹具位于 `tests/fixtures/synthetic/` 及其他明确标记的 synthetic 目录。

## 项目状态

当前 Gate 1 为 `hold`：工程实现可以继续，但真实用户、双端真机、真实 LAN / Agent 宿主、生产模型、安全合规与商店审核证据尚未闭环。Ameme 会把失败、跳过和阻塞保留为正式证据，不用“文档写完”替代验证通过。

欢迎通过 Issue 讨论产品边界、架构、安全模型和可复跑验证。当前仓库尚未声明开源许可证；除非另有说明，代码与文档不自动授予再分发或商用许可。
