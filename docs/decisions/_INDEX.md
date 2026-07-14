# decisions 决策记录索引

> 规则：影响用户、核心场景、数据范围、架构、隐私、商业模式或发布的决定写入本目录。被替代的决定保留并标记 superseded。

模板：`../governance/_template-决策记录.md`。口头原始决策仍保留在根目录 `WAL.md`，正式实施以本目录为准。

## 当前决策

| ID | 决策 | 状态 | 影响 |
|---|---|---|---|
| PDR-001 | `PDR-001-MVP范围与双主端.md` | accepted | 事件化今天、iOS/Android + Agent |
| PDR-002 | `PDR-002-健康来源发布边界.md` | accepted | 五类健康来源进入首个公开 MVP，成为发布硬门 |
| PDR-003 | `PDR-003-结构化导出进入封闭MVP.md` | accepted | 结构化导出作为 P1 在 M5 完成，不阻塞本地闭环 |
| PDR-004 | `PDR-004-研发条件启动与验证投入.md` | accepted | 批准 M1–M6 全面研发与 10–15 人/双端真机验证投入 |
| PDR-005 | `PDR-005-MVP品牌与交互品质方向.md` | accepted | 简洁高效、单一悬浮记录按钮、ChatGPT 移动端品质参考 |
| ADR-001 | `ADR-001-双端原生与Schema共享.md` | accepted | 原生 UI，MVP 共享机器契约而非运行时 Core |
| ADR-002 | `ADR-002-本地EventCore与可重建索引.md` | accepted | SQLite + Raw Vault，索引/Summary 可重建 |
| ADR-003 | `ADR-003-云协调模块化单体.md` | superseded_for_mvp | 未来云候选；MVP 被 ADR-006 替代 |
| ADR-004 | `ADR-004-AI分层与提供方可替换.md` | accepted | 确定性优先、provider adapter |
| ADR-005 | `ADR-005-MVP技术实现默认栈.md` | accepted | Android 固定官方 SQLCipher 4.15.0；FTS5、SourceLocator、OS 版本、透明设备密钥和 TTL 默认值 |
| ADR-006 | `ADR-006-LAN点对点同步.md` | accepted | Bonjour/NSD、同账户设备认证、LAN Peer Sync |
| SDR-001 | `SDR-001-结构化同步可见性.md` | accepted | 同局域网点对点同步，不建设用户数据云 |
| SDR-002 | `SDR-002-账户恢复与密钥模型.md` | accepted | 账户归属、系统透明密钥、无用户 recovery key UX |

## 变更日志

| 日期 | 操作 | 说明 |
|---|---|---|
| 2026-07-13 | 建立 | 创建正式决策域 |
| 2026-07-13 | 新增 | 将已确认产品边界和可逆架构基线转为 PDR/ADR，并登记 SDR-001 产品负责人决策 |
| 2026-07-13 | 新增 | 将结构化导出、条件启动与验证投入转为产品负责人可直接选择的 proposed 决策 |
| 2026-07-14 | 决议 | 产品负责人授权按专业建议代决：接受 PDR-002/003/004、SDR-001/002；新增品牌品质和技术默认栈决策 |
| 2026-07-14 | 最终拍板 | D1 改为 LAN/P2P 无数据云，D2 改为账户优先无用户密钥，健康进入公开 MVP，M1–M6 全面启动，Agent 自动读写，Raw 保留源索引，Today 改为单悬浮按钮 |
| 2026-07-14 | 依赖纠偏 | Android 加密库固定为官方 `sqlcipher-android:4.15.0`，纠正此前错误版本表述；真机、16 KB 与性能仍待验证 |
| 2026-07-14 | 实施证据 | Android API 36 AVD 已形成 SQLCipher v5、34 项普通设备测试和 10k/100k 数据库基线；原决策不变，物理设备/16 KB/UI 性能继续待验证 |
