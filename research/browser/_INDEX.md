# Browser 信息源研究索引

| 文件/目录 | 状态 | 用途 |
|---|---|---|
| `active-tab-extension/` | Spike | 只使用 `activeTab + scripting + storage` 的一键 Capture 扩展 |
| `run-active-tab-spike.js` | 可复跑 | 在隔离 Chrome 配置和合成网页中验证权限授予与导航撤回 |
| `active-tab-spike-report.md` | 已验证 | 真实运行结果、权限撤回和限制 |

## 变更日志

| 日期 | 操作 | 说明 |
|---|---|---|
| 2026-07-13 | 建立并实测 | 使用隔离 Chromium 与合成网页验证 `activeTab` 一键 Capture |
