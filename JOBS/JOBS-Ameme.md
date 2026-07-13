# Ameme 项目 Job

## 工作区与上线工作流

- 状态：`review_ready`
- 目标：建立从产品调研、决策、Prototype、MVP、测试、Beta 到生产发布和运营复盘的可执行工作区。
- 确认人：产品负责人。

### 步骤

- [x] 审查 FORMALdoc 的目录、规则、Job、索引、验证和已知失败。
- [x] 建立 Ameme 根级规则、路由、状态和决策流水。
- [x] 建立正式文档域、工程代码域和数据安全边界。
- [x] 定义生命周期 Gate、标准工作流和完成标准。
- [x] 建立机器可检查的工作区健康脚本和 CI。
- [x] 将现有调研纳入当前正本与索引。
- [x] 运行健康检查并保留真实结果。

### 交付

- `AGENTS.md`、`WORKSPACE_MAP.md`、`STATUS.md`、`WAL.md`
- `docs/governance/`
- `docs/_CURRENT.md`、各文档域 `_INDEX.md`
- `scripts/governance/check_workspace.py`
- `.github/workflows/workspace-health.yml`

### Review 需要确认

1. 生命周期 Gate 是否符合“每一步在用户确认下推进”的协作方式。
2. 工作、个人、家庭、健康、财务等空间是否需要在 Gate 3 前确定密钥隔离策略。
3. 是否在工作区审核通过后初始化首个正式 Git 基线提交。

### 真实验证证据

| 验证 | 实际结果 |
|---|---|
| `python scripts/governance/check_workspace.py` | `ok=true`，68 checks，0 errors，0 warnings |
| Python 脚本编译检查 | 通过 |
| Windows capability probe | 脚本完成；窗口/UIA/浏览器/Git 环境可探测；最新回归中合成文件事件为 `InvalidOperationException`，截屏 API 探测为 `RuntimeException`，不得记作能力通过 |
| Windows UIA coverage probe | 完成；覆盖差异仍存在，且未保存元素名称、文本值或窗口标题 |
| Git 初始化 | 本地 `main` 分支已初始化，尚未创建基线 commit |

## 用户与场景研究

- 状态：`in_progress`
- 当前结论：候选用户和任务已形成桌面研究矩阵，缺少真实访谈和任务排序。
- 下一步：准备访谈样本、记录模板和证据标准。
- Gate：Gate 1。

## 多端信息源研究

- 状态：`in_progress`
- 当前结论：Windows 低风险能力探针完成；移动端、通信、账单和游戏需要继续验证。
- 下一步：完成官方权限研究和可复跑样本验证。
- Gate：Gate 1。

## Prototype 候选评审

- 状态：`proposed`
- 进入条件：R0/R1 证据完成，形成 2–3 个可比较闭环。
- 输出：候选用户、端、数据源、处理链、隐私边界、成本、验证指标和否决条件。
- Gate：Gate 1 通过后进入 Gate 2。
