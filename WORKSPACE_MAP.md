# Ameme 工作区地图

> 用途：快速路由，不记录普通文件流水。当前正本入口见 `docs/_CURRENT.md`。

## 任务快速路由

| 要做什么 | 先读哪里 |
|---|---|
| 看当前阶段、阻塞和下一确认门 | `STATUS.md`、`JOBS/README.md` |
| 执行或更新正式任务 | `JOBS/JOBS-Ameme.md` |
| 查当前有效文档 | `docs/_CURRENT.md` |
| 看产品定位、用户、PRD、指标 | `docs/product/_INDEX.md` |
| 看调研、来源和实验结论 | `docs/research/_INDEX.md`、`research/` |
| 看系统、数据和模型架构 | `docs/architecture/_INDEX.md` |
| 看隐私、安全和合规边界 | `docs/privacy-security/_INDEX.md` |
| 看工程 RFC、接口和迁移 | `docs/engineering/_INDEX.md` |
| 看测试、评测和验证证据 | `docs/quality/_INDEX.md` |
| 看发布门禁、版本和回滚 | `docs/release/_INDEX.md` |
| 看监控、事故和客服运行 | `docs/operations/_INDEX.md` |
| 看正式决策与替代关系 | `docs/decisions/_INDEX.md` |
| 看工作区规则、流程和模板 | `docs/governance/_INDEX.md` |
| 运行工作区健康检查 | `python scripts/governance/check_workspace.py` |

## 目录总览

| 目录 | 内容 |
|---|---|
| `docs/` | 正式产品、研究、架构、安全、工程、质量、发布与运行文档 |
| `research/` | 可复跑的技术探针和实验 |
| `apps/` | Desktop、Mobile、Web、Browser Extension |
| `services/` | API、任务处理、同步和账户服务 |
| `packages/` | 共享领域模型、存储、加密、UI 和 SDK |
| `connectors/` | 各信息源适配器 |
| `infra/` | 环境、部署、可观测性和基础设施 |
| `scripts/` | 治理、构建、迁移、验证和发布脚本 |
| `tests/` | 合成夹具、集成、端到端、性能与安全测试 |
| `JOBS/` | 工作队列与确认门 |

## 维护规则

只有一级目录、快速路由或跨目录正本入口变化时更新本文件；普通文件变化只更新局部 `_INDEX.md`。
