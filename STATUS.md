# Ameme 状态看板

> 最后更新：2026-07-15
> 事实来源：`JOBS/JOBS-Ameme.md` 与各 Gate 验证材料。

## 当前阶段

- 生命周期：Discovery Gate 仍在补证，MVP 工程实现进行中；M1–M6 全面研发获批，当前已集成契约、参考 Core/Raw/队列、同步、R0 AI/固定评测，以及 Android 加密 Local Event Node、显式来源、配对 Agent 写入和结构化今日小结。
- 当前 Gate：Gate 1 `hold`。产品主链已改为 DayLedger 事件覆盖优先；桌面 Spike 和 FORMALdoc 只能证明专业来源，通用来源与正式用户证据仍不足。
- 产品 MVP：`今天` 为唯一默认主页，单悬浮记录按钮展开文字/语音/照片/导入，右上搜索进入统一历史日流；数据稀疏是默认状态。品牌为简洁、高效、安静、可信、流畅；双端使用原生控件。
- Agent：统一 `ameme-memory` Skill 与本地 MCP Host 已形成六模式、授权内自动读写、撤销、预算控制和 14-case 风险评测。Android 普通用户连接已提供同网发现、扫码、账户设备三种统一入口；当前仅 Debug 模拟候选/成功并明确不联网，Release 不含模拟 provider。真实 TLS 1.3/certificate pin/HMAC listener、Keystore 凭据和 SQLCipher 持久幂等仍由 Debug 开发者路径验证；API 36 AVD 的真实纵向 smoke 证明 Host 写入 Today、重启保留且 ADB 不注入 Event。真实 NSD/QR/账户发现、共享 Grant registry、后台、append/undo/recall 与真实第三方宿主仍未实现。
- 同步/账户：同账户已批准设备在 LAN 内点对点同步；身份服务不保存记忆内容；无用户密钥 UX，也无全设备丢失后的旧数据恢复。
- 工程实现：Android API 36 AVD 普通回归发现 56 项：49 项通过、7 项显式 Gate 跳过；新增三入口选择、统一授权/成功/断开、非敏感状态 fail closed 和 Release provider 为空的测试。另有 DB-01 有效性能报告、移动来源体验包、Android→本地网关 1/1 和配对 Agent E2E。覆盖 SQLCipher/Keystore/WAL/v1→v6/space/Revision、Photo/Share/Calendar/Voice、FTS/LIKE/keyset、DayLedger/Summary、持久 Agent 幂等、TLS 配对和 10k/100k 容量。真实 OpenAI live 因无密钥/显式开关按设计跳过；这些证据仍不等于双端真机、真实三种连接、生产 Raw/LAN/后台或发布通过。

## 进行中

| 工作 | 状态 | 下一步 |
|---|---|---|
| 项目工作区与上线工作流 | done | Git 基线 `115690b` 已建立 |
| 总体框架评审 | in_progress | 作为 MVP 讨论基线；体验闭环候选并入本轮设计原则评审 |
| MVP 范围与体验原则 | accepted | 品牌、单悬浮记录入口、双端页面、统一 Skill、六条旅程和指标已接受 |
| MVP 三线并行设计与技术方案 | accepted | 产品、交互、契约、架构、安全、质量和工程计划完成全链审计 |
| MVP 研发前准备 Goal | done | owner/local gap 清零；13 项正式决策已登记（ADR-003 被替代）；Skill/Schema/治理/链接/编译门禁通过，只保留 Spike/外部证据 |
| MVP 工程实现 | in_progress | 第六批闭环后完成 Android 普通用户三入口统一连接体验与 Release 隔离；下一批转向真实 NSD/QR/账户、物理设备、后台、共享 Grant、真实模型和 iOS Mac |
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
- iOS Journaling Suggestions 尚无 Mac/真机证据；Android Picker/Share/Calendar/Voice/Agent/AI 小结当前仅 API 36 x86_64 AVD，无物理设备、OEM、后台或真实 LAN 证据。
- Windows 截屏 API 探测仍为 `RuntimeException`。
- instrumentation-only Codex seed 仍保留为测试辅助；新的 paired Host smoke 已不依赖它写 Event，但当前只使用合成事件和 ADB 端口转发，不证明真实 Codex 会话采集、后台运行或物理跨设备传输。
- 产品负责人决策已关闭：LAN/P2P 无用户数据云、账户归属无用户密钥 UX、Health 公开 MVP、结构化导出 P1、M1–M6 全面研发、系统版本、Agent 自动读写、Raw+SourceLocator、单悬浮入口和 Pilot/真机投入均已 accepted。
- 仍待不可替代证据：真实用户、双端真机、SQLCipher 16 KB 与 UI/真机性能、真实 LAN/分布式删除、真实宿主 Skill、真实模型 AI/成本、目标市场法律与商店/供应商审核。

## 下一执行门

第六批 Android 合成完整体验与普通用户统一连接体验均为 `conditional_pass`：Share/Picker/Calendar/Voice 正式入口可测，配对 Host 经 TLS/HMAC 写入 SQLCipher，用户同意后可生成结构化今日小结；普通用户 Debug 可用同网发现、扫码、账户设备三入口完成统一授权、体验成功和断开。Android 普通设备回归为 49 通过、7 个显式 Gate 跳过、0 失败，Release 单测确认无模拟 provider。下一步优先实现真实 NSD/QR/账户设备证明和共享 Grant，再做物理设备/16 KB/OEM、后台、真实模型；iOS 等待 Mac。Gate 1 继续 `hold`，不能将 Debug 体验写成真实联网、数据传输、用户价值或公开发布已验证。
