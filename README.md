# Ameme

Ameme 是一个由用户控制的个人记忆基础设施：从电脑、手机、通信工具和生活服务获取用户授权的信息，处理为可追溯、可纠正、可隔离、可迁移并可供 AI 使用的长期上下文。

当前阶段：Discovery / MVP 研发准备完成。D1–D10 与技术基线已接受，M1–M6 研发获批；Gate 1 仍为 `hold`、Gate 2 未执行，真实用户、真机、安全、Health 与发布证据待实施产生。

## 快速入口

- 当前状态：[STATUS.md](STATUS.md)
- 当前正本：[docs/_CURRENT.md](docs/_CURRENT.md)
- 工作队列：[JOBS/README.md](JOBS/README.md)
- 工作区地图：[WORKSPACE_MAP.md](WORKSPACE_MAP.md)
- 协作规则：[AGENTS.md](AGENTS.md)
- 生命周期与上线 Gate：[docs/governance/产品生命周期与上线门禁.md](docs/governance/产品生命周期与上线门禁.md)
- 标准工作流：[docs/governance/标准工作流.md](docs/governance/标准工作流.md)

## 工作区组成

```text
产品与治理：docs/ + JOBS/
技术调研：research/
用户端：apps/
身份/可选外部服务适配：services/（MVP 不承载用户记忆数据）
共享能力：packages/
信息源：connectors/
基础设施：infra/
自动化：scripts/
质量验证：tests/ + docs/quality/
发布运行：docs/release/ + docs/operations/
```

目录职责和任务路由以 `WORKSPACE_MAP.md` 为准。

## 当前产品原则

1. 记忆数据本地优先，同账户已批准设备只在 LAN 内点对点同步；MVP 不建设用户记忆数据云，外部模型处理单独授权。
2. “无感”表示不需要重新书写，不表示无授权或不可见。
3. 原始证据、解析观察、AI 推断和用户确认分层保存。
4. 工作、个人、家庭、健康和财务支持统一查看与底层隔离。
5. 输入不要求结构化；系统负责获取、分类、处理、存储和使用。
6. 删除、lineage、导出和访问审计从第一版进入架构。
7. 每个阶段通过证据 Gate 和用户确认后再升级。
8. Agent 只在 exact `autonomous_memory` Grant 内自动读写 Event/Revision；Restricted、Raw、扩权和删除仍受独立门禁。

## 工作区健康检查

```powershell
python scripts/governance/check_workspace.py
```

检查内容包括必需入口、根目录边界、文档域索引、正本引用、脚本注释头、大文档头、敏感数据和生成目录风险。GitHub Actions 会在推送到 `main` 或提交 Pull Request 时运行同一检查。

## 数据安全

不要向仓库提交真实浏览历史、聊天、照片、音频、位置、账单、健康数据、数据库、密钥或访问令牌。测试数据必须使用 `tests/fixtures/synthetic/` 下的合成样本。
