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

- `validation/run_workspace_validation.py`：完整无网络 Python/契约/Skill/文档/治理基线；要求 Python 3.10+，低版本解释器在启动前以机器可读结果失败，推荐隔离 Python 3.12 + `scripts/requirements-dev.txt`；
- `validation/run_ai_reference_eval.py`：固定合成 AI Eval、攻击/删除集与差分报告校验；
- `governance/check_workspace.py`：仅工作区结构与治理规则；
- `validation/run_contract_quality.py`：仅机器契约质量基线。
- `validation/validate_coverage_contracts.py`：复跑目标用户/上下文/来源合成矩阵、字段证据边界、保守事件编译、显式缺口和禁止虚假覆盖率/市场规模规则。
- `validation/validate_mobile_coverage_contract.py`：校验双端 production coverage 模型与共享 v1 枚举、安全规则和 Git 可追踪性一致；静态门不替代平台构建或设备测试。
- `validation/validate_mobile_long_term_memory_contract.py`：校验双端 production 长期 Memory 的类型、显式确认、Event revision 绑定、有效期、替代、失效、迁移、Agent 隔离和 Git 可追踪性一致；静态门不替代 SQLCipher、XCTest 或设备测试。
- `validation/validate_mobile_recovery_contract.py`：校验双端持久删除水位、认证本机恢复 artifact、损坏/错密钥/旧水位/非空目标 fail-closed、恢复候选隔离和 Git 可追踪性一致；明确不声称跨设备或生产灾难恢复，静态门不替代物理设备恢复演练。
- `validation/validate_mobile_reuse_contract.py`：校验历史搜索、显式关键词项目续接、会前上下文、决定/承诺回忆四类本机复用，精确 revision 二次验证、Restricted 排除、周有帮助复用计数，以及不落查询/正文/路径/原始对象 ID 的摘要遥测；明确不声称真实用户觉得有帮助、设备执行或 Agent 读取协议已开放。
- `validation/validate_mobile_source_deletion_contract.py`：校验当前 Android schema v13 / iOS envelope v8 的本机 SourceObject→Event lineage、外部 Raw 所有权边界、iOS app-owned ciphertext Raw-only/pending retry、单来源 cascade、多来源 conservative action、source watermark 与 legacy/stale fail-closed；明确不声称 account/space、provider 原件、peer proof 或设备执行通过。
- `validation/validate_mobile_field_provenance_contract.py`：校验双端 content-free exact-revision 字段→来源证据、完整映射约束、多来源保守重算、字段失证整 Event 删除、显式完整用户确认保留、partial/stale fail-closed、迁移和重载；明确不声称真实用户、设备或外部删除通过。
- `validation/validate_mobile_user_confirmation_provenance_contract.py`：校验 Android schema v13 / iOS envelope v8 的 content-free exact-revision 用户确认记录、完整与 partial 边界、删源后的保守保留、revision 终结/携带、v12/v7 不猜测迁移、认证备份保留和可执行 iOS Smoke；明确不声称真实用户、物理设备、account/provider/peer proof 或 XCTest 已执行。
- `validation/validate_android_agent_revision_contract.py`：校验 Android 生产 Local Node 的 exact Grant `append_revision`、SQLCipher 原子 Revision/幂等重放、敏感目标存在性隐藏和 Host TLS adapter，并把 undo 交给独立 Gate；明确不声称 read/ContextPack、真实宿主、物理设备、共享账户 Grant 或发布通过。
- `validation/validate_android_agent_undo_contract.py`：校验 Android/Host exact Event/Revision `undo_capture`、10 分钟首次时窗、Event tombstone、Revision 补偿 revision/head conflict、SQLCipher 原子撤销幂等、旧 pairing fail-closed 与 read/长期 Memory 隔离；明确不声称真实宿主、设备纵向执行、跨端撤销传播、共享账户 Grant 或发布通过。
- `validation/validate_android_agent_read_contract.py`：校验 Android/Host 最小 `visible_events` 读取、Event-only/单 Space/结构化/敏感度/时间/查询/数量边界、Restricted 不泄漏、正文截断、Recall/ContextPack 预算与 `get_event`/策略写入保持关闭；明确不声称真实 Host、物理设备、共享账户 Grant 或发布通过。
- `validation/validate_ios_agent_local_node_client_contract.py`：校验 iOS Local Node 客户端对 `create_event`、`append_revision`、exact `undo_capture` 与 bounded `visible_events` 的最小 Grant scope、握手 operation 协商、canonical request、typed response/result digest、冻结 error/retryability 和恶意结果 fail-closed；普通用户 QR Grant 仍为 event-only，静态门不声称新 operation 已网络/设备执行或已接共享账户 Grant。
- `validation/validate_mobile_local_space_deletion_contract.py`：校验双端当前安装内 Personal space 的根删除水位、写入冻结、Event/Coverage/长期 Memory/复用投影收敛、旧备份拒绝、失败回滚与本机 Raw/locator 后续清理；明确不声称账号删除、Grant 撤销、provider 原件删除、peer proof、物理擦除或设备执行通过。
- `validation/prepare_external_gate_run.py`：为一个已冻结 40-hex commit 在 Git-ignored `data/private` 下创建空白 T0/物理设备/签名/商店/成本/恢复 Gate 模板和内容无关 manifest；不采集证据、不覆盖既有 run，也不把 Gate 标为通过。
- `validation/validate_external_gate_packs.py`：校验 T0 8×7、双端物理设备/读屏、真实 Agent、生产恢复、签名/商店、付费成本和事故回滚执行包的角色、步骤、空白数据模板、私有路径与 fail-closed `hold` 初始状态；输出只证明执行包准备度。
- `validation/run_android_db01_performance.ps1`：显式运行 Android SQLCipher 10k/100k 合成容量、搜索、分页、内存、迁移和 FTS/LIKE 对照探针，并导出无正文 JSON 指标；脚本按 serial 获取进程互斥，锁已占用时直接失败。

Android connected test 会重装同一 application ID。同一设备或 AVD serial 上严禁并行运行 `connectedDebugAndroidTest`、性能 probe 或其他 instrumentation；否则进程可能被另一任务重装/强停，证据一律无效。不同 serial 可并行。
