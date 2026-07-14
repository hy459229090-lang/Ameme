# Windows 信息源研究索引

| 文件 | 状态 | 用途 |
|---|---|---|
| `windows-capability-probe.ps1` | v0.2 已回归 | 检查窗口、UIA、文件事件、Capture API 和本机环境，不保存正文；文件事件竞态已修复 |
| `windows-uia-coverage-probe.ps1` | 已运行 | 统计可见应用 UIA 覆盖和耗时，不保存元素文本 |
| `windows-feasibility-report.md` | 当前结论 | Windows 低风险信息源实测报告 |
| `README.md` | 当前有效 | 运行方式和隐私边界 |

## 变更日志

| 日期 | 操作 | 说明 |
|---|---|---|
| 2026-07-13 | 建立 | 将 Windows 实测纳入正式研究路由 |
| 2026-07-13 | 修复 | FileSystemWatcher 改用 PowerShell 事件队列，连续 3 次通过 create/change/rename/delete 合成测试 |
