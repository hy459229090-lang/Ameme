# scripts

治理、构建、迁移、验证和发布辅助脚本。

脚本前三行必须说明用途、输入和输出。PowerShell 为兼容 Windows PowerShell 5.1 使用 ASCII：

```text
# Purpose: ...
# Input: ...
# Output: ...
```

Python 也可使用 `# 用途：` / `# 输入：` / `# 输出：`。

当前入口：

- `validation/run_workspace_validation.py`：完整无网络 Python/契约/Skill/文档/治理基线；
- `validation/run_ai_reference_eval.py`：固定合成 AI Eval、攻击/删除集与差分报告校验；
- `governance/check_workspace.py`：仅工作区结构与治理规则；
- `validation/run_contract_quality.py`：仅机器契约质量基线。
- `validation/run_android_db01_performance.ps1`：显式运行 Android SQLCipher 10k/100k 合成容量、搜索、分页、内存、迁移和 FTS/LIKE 对照探针，并导出无正文 JSON 指标；脚本按 serial 获取进程互斥，锁已占用时直接失败。

Android connected test 会重装同一 application ID。同一设备或 AVD serial 上严禁并行运行 `connectedDebugAndroidTest`、性能 probe 或其他 instrumentation；否则进程可能被另一任务重装/强停，证据一律无效。不同 serial 可并行。
