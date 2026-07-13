# Windows 信息源实测

`windows-capability-probe.ps1` 是低风险能力探针，只验证能力是否存在和基本事件是否可用。

`windows-uia-coverage-probe.ps1` 只统计当前顶层应用的 UI Automation 节点、Pattern 和遍历耗时，不保存任何元素名称或文本。

实测结论见 `windows-feasibility-report.md`。

它不会：

- 保存窗口标题或 UI 文本；
- 读取浏览器历史或配置文件；
- 读取用户文档、Git 仓库内容或终端历史；
- 截屏、录屏或录音。

运行方式：

```powershell
powershell -ExecutionPolicy Bypass -File .\research\windows\windows-capability-probe.ps1
```

需要落盘结果时，显式提供 `-OutputPath`。结果只包含脱敏后的能力和数量信息。
