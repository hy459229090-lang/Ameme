# release 发布索引

## 当前状态

M24/M25 双端仓库工程候选已通过并保有可复跑构建/自动化证据；尚未产生可供真实分发或公开发布的签名候选版本。发布与回滚框架及外部门执行包已就绪，真实账户、物理设备、演练与签收仍待执行。

| 主题 | 文件 | 状态 |
|---|---|---|
| MVP 封闭发布与回滚 | `MVP封闭发布与回滚计划.md` | v0.2，LAN/Health、5→10–15 人 rings、stop/rollback 基线 |
| 封闭 Beta 外部门执行包 | `封闭Beta外部门执行包.md` | v0.1 ready_to_execute / blocked_external；已纳入双端同安装恢复 UI 的物理验收步骤，`production_recovery_claim`/`cross_device_recovery_claim`、物理设备/读屏、真实 Agent/LAN、签名/商店、真实成本与事故/回滚仍 hold |

模板：`../governance/_template-发布计划.md`。

## 变更日志

| 日期 | 操作 | 说明 |
|---|---|---|
| 2026-07-13 | 建立 | 创建发布正式文档域 |
| 2026-07-13 | 新增 | 形成封闭 MVP 发布、feature flag、停止条件和分层回滚候选 |
| 2026-07-14 | 决策 | 切换为 LAN Peer Sync，Health 为公开发布硬门，Pilot 采用 5 人后扩 10–15 人双端矩阵 |
| 2026-07-26 | 新增 | 建立封闭 Beta 外部门执行包与 fail-closed 空白 Gate ledger；明确 4 台物理设备、真实读屏/Agent/LAN/恢复、签名商店/成本和事故回滚步骤，所有外部门仍 hold |
| 2026-07-29 | 更新 | 将双端同安装恢复点、真实候选健康、最近成功状态和逐字确认加入物理设备验收；该 UI 不改变用户自有/E2EE、跨设备 key、全设备丢失和生产支持 Gate |
| 2026-08-08 | 更新 | 复核并同步仓库基线与外部门执行包状态：隔离 Python 3.12 + `scripts/requirements-dev.txt` 运行基线 28/28 为 conditional_pass；首次缺 PyYAML 与系统 Python 3.9 环境失败已透明保留。外部参与者/物理设备/签名/真实模型与 `production_recovery_claim`/`cross_device_recovery_claim` 仍 hold；执行包更新日期同步到 2026-08-08 |
