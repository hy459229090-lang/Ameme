# research 可复跑实验索引

| 子目录 | 用途 | 当前入口 |
|---|---|---|
| `windows/` | Windows 信息源低风险能力验证 | `windows/_INDEX.md` |
| `browser/` | Browser Extension 与当前页低权限 Capture | `browser/_INDEX.md` |
| `coverage/` | 目标用户一天上下文、来源增量、规模口径与 7 天研究 | `coverage/_INDEX.md` |

## 规则

- 只提交脱敏脚本、合成样本和可公开的结果摘要。
- 原始个人数据、捕获内容、数据库和日志放在 `.gitignore` 排除位置。
- 脚本必须有用途/输入/输出三行头，并记录真实运行命令。
