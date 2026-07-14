# Ameme 状态看板

> 最后更新：2026-07-14
> 事实来源：`JOBS/JOBS-Ameme.md` 与各 Gate 验证材料。

## 当前阶段

- 生命周期：Discovery Gate 仍在补证，MVP 工程实现进行中；M1–M6 全面研发获批，当前已集成契约、参考 Core/Raw/队列、同步、Agent↔Core 测试适配、R0 AI/固定评测与 Android 加密 Local Event Node/显式来源切片。
- 当前 Gate：Gate 1 `hold`。产品主链已改为 DayLedger 事件覆盖优先；桌面 Spike 和 FORMALdoc 只能证明专业来源，通用来源与正式用户证据仍不足。
- 产品 MVP：`今天` 为唯一默认主页，单悬浮记录按钮展开文字/语音/照片/导入，右上搜索进入统一历史日流；数据稀疏是默认状态。品牌为简洁、高效、安静、可信、流畅；双端使用原生控件。
- Agent：统一 `ameme-memory` Skill 与本地 MCP Mock 已形成六模式、授权内自动读写、撤销、预算控制和 14-case 风险评测；新增 EventNodeStore/Core Oracle 测试适配、独立 JSONL stdio 参考宿主进程、双存储崩溃对账与 28 项回归。Python Core 仍非生产节点，真实 Codex/Claude Code/Cursor 宿主和 Android Local Node 接合仍待实现。
- 同步/账户：同账户已批准设备在 LAN 内点对点同步；身份服务不保存记忆内容；无用户密钥 UX，也无全设备丢失后的旧数据恢复。
- 工程实现：截至工程提交 `c8fc128`，Android API 36 AVD 已通过 34 项普通设备测试和 1 项独立 DB-01 性能探针，覆盖 SQLCipher/Keystore/WAL/v1→v5/space/Revision、Photo/Share、真实 Calendar Provider、系统语音引用、FTS/LIKE/keyset、来源实例幂等、持久 URI 清理、异步 I/O 与 10k/100k 容量。100k FTS P95 177.72 ms、单次提交 P95 215.67 ms，两项预注册 DB 门通过；它们仍不等于双端真机、16 KB、UI 首帧、生产 Raw/LAN、真实模型或真实 Agent 宿主通过。

## 进行中

| 工作 | 状态 | 下一步 |
|---|---|---|
| 项目工作区与上线工作流 | done | Git 基线 `115690b` 已建立 |
| 总体框架评审 | in_progress | 作为 MVP 讨论基线；体验闭环候选并入本轮设计原则评审 |
| MVP 范围与体验原则 | accepted | 品牌、单悬浮记录入口、双端页面、统一 Skill、六条旅程和指标已接受 |
| MVP 三线并行设计与技术方案 | accepted | 产品、交互、契约、架构、安全、质量和工程计划完成全链审计 |
| MVP 研发前准备 Goal | done | owner/local gap 清零；13 项正式决策已登记（ADR-003 被替代）；Skill/Schema/治理/链接/编译门禁通过，只保留 Spike/外部证据 |
| MVP 工程实现 | in_progress | 第四批已关闭 Android 异步 I/O、Calendar/Voice 与 10k/100k AVD 基线；下一批推进真实 Agent→Android Local Node、LAN 和物理设备；iOS 构建等待 Mac |
| R0 用户与场景研究 | plan_ready | 按产品决策有意后置 |
| R1 多端信息源调研 | plan_ready | 按产品决策有意后置，后续只做 MVP 定向补证 |
| Event/每日事件记录模型 | plan_ready | v0.4 保留情绪/关系/重要性独立字段，但禁止其提高事实置信度或触发扩权 |
| Prototype 候选评审 | plan_ready | D1–D10 已回填；等待 Gate 1 输入与研发实施证据 |

## 已验证

- Windows 可获得前台窗口元数据和 UI Automation 部分结构；文件事件探针修复竞态后连续 3 次通过 create/change/rename/delete。
- Chrome/Edge Extension、Git 和显式 Capture 是可继续实验的来源。
- UI Automation 覆盖不一致，不能作为统一正文采集方案。
- Chrome `activeTab` 在隔离 Chromium 合成页中完成低权限闭环，跨 origin 后旧授权撤回。
- FORMALdoc 只读快照证明 Git、状态看板、Job 和文件元数据的新鲜度/权威不同；它被定位为专业桌面来源对照，不代表普通用户。
- 全屏/全音频持续采集不进入首版默认路径；Windows Capture 探针仍阻塞。

## 阻塞/待确认

- 首批目标用户已初步选择 AI 使用者，但尚未完成真实访谈验证。
- Prototype 不设用户最小来源门槛；工程夹具需覆盖单来源和数据稀疏日。
- 不含 Git/工作区的通用一天样本尚未建立；正式参与者来源和访谈安排未确认。
- iOS Journaling Suggestions 尚无 Mac/真机证据；Android Picker/Share/Calendar/Voice/性能当前仅 API 36 x86_64 AVD，无物理设备与 OEM 证据。
- Windows 截屏 API 探测仍为 `RuntimeException`。
- 产品负责人决策已关闭：LAN/P2P 无用户数据云、账户归属无用户密钥 UX、Health 公开 MVP、结构化导出 P1、M1–M6 全面研发、系统版本、Agent 自动读写、Raw+SourceLocator、单悬浮入口和 Pilot/真机投入均已 accepted。
- 仍待不可替代证据：真实用户、双端真机、SQLCipher 16 KB 与 UI/真机性能、真实 LAN/分布式删除、真实宿主 Skill、真实模型 AI/成本、目标市场法律与商店/供应商审核。

## 下一执行门

第四批研发切片已集成并通过 147 项 Python 测试、AI 固定 Eval 12/12、2,715/2,709 项契约检查、14-case/14-risk Skill、35 个 Markdown 链接、130 项治理、Android 28 项 JVM、lint/assemble、API 36 AVD 34 项普通设备测试和 1 项独立性能测试。下一步推进真实 Agent→Android Local Node、LAN 认证/加密/重放保护，以及 Android 物理设备/16 KB/OEM 来源验证；iOS 源码、构建和真机证据等待 Mac。Gate 1 继续 `hold`，不能将合成/模拟器进度写成真实用户价值已验证。
