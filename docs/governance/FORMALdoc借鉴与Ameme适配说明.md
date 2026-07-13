# FORMALdoc 借鉴与 Ameme 适配说明

> 文档状态：review_ready  
> 适合读者：产品负责人、项目协作者、AI  
> 人类快速阅读：看“继承机制”和“主动修正”  
> AI 阅读提示：本文件解释治理设计来源，不替代 `AGENTS.md` 和生命周期 Gate 正本。

## 1. 为什么不直接复制 FORMALdoc

FORMALdoc 是成熟的产品文档协作库，长处在正式文档、索引、Job 和多角色 Review；Ameme 需要从研究一直走到多端软件上线，还必须承载代码、数据 Schema、基础设施、测试、发布、监控和事故响应。因此 Ameme 采用同一治理思想，但调整为软件单仓。

## 2. 继承的机制

| FORMALdoc 机制 | Ameme 采用方式 |
|---|---|
| `_CURRENT.md` 当前正本 | `docs/_CURRENT.md` 作为全局正本入口；重大主题再建立局部 `_CURRENT.md` |
| `_INDEX.md` 目录索引 | 正式文档域维护局部索引，避免全仓扫描 |
| `WORKSPACE_MAP.md` 快速路由 | 保持在 100 行内，只记录任务入口和一级目录 |
| `JOBS/README + 项目 Job` | 总览只做状态路由，细节集中在 `JOBS/JOBS-Ameme.md` |
| Plan First 和 PM Gate | 重大任务先有计划；用户只在计划、阻塞和 Gate/发布时介入 |
| 独立 validation 与 reviewer | 验证证据放 `docs/quality/`，Gate 结论不能由产出者的文字自证 |
| 脚本生成、审计、验证、索引同步 | 固化为标准 Review 顺序并进入 CI |
| 规则空白与决策流水 | 口头决定先写 `WAL.md`，长期决定进入 `docs/decisions/` |

## 3. FORMALdoc 暴露的问题

### 3.1 规则重复和漂移

FORMALdoc 曾同时存在 AGENTS、CLAUDE、Cursor rules 和目录规则，例如旧规则要求每次普通文档变化都更新 WORKSPACE_MAP，而新规则已改成只在全局路由变化时更新。多份正本会让执行者选到旧规则。

Ameme 修正：

- `AGENTS.md` 是跨工具唯一规范正本。
- `WORKSPACE_MAP.md` 只负责路由。
- 目录 `_INDEX.md` 只负责局部规则和文件状态。

### 3.2 状态和真实产物不一致

FORMALdoc 的工具链 Job 曾记录脚本已完成、Smoke test 已通过，复核时发现脚本并不存在，实测数字也不同。这说明人工状态表不能替代机器证据。

Ameme 修正：

- 只有产物存在且验证命令实际通过，Job 才能进入 `review_ready`。
- `scripts/governance/check_workspace.py` 在本地和 CI 使用同一检查。
- 验证报告必须记录命令、输入、环境、输出和限制。

### 3.3 索引和总览膨胀

FORMALdoc 曾出现近 1MB 的 `_INDEX.md` 和极长的 JOBS 总览，导致索引本身成为新负担。

Ameme 修正：

- `_INDEX` 只做路由，不嵌入大表和正文。
- Job 总览一项一行，详细过程只在项目 Job。
- 生成的大规模资产有独立 manifest、数据文件和验证报告。

### 3.4 文档工作流没有完整覆盖软件上线

FORMALdoc 有 PRD、计划、验证和交付，但没有统一覆盖多端代码、数据库迁移、密钥、CI/CD、商店发布、灰度、监控、回滚和事故。

Ameme 修正：

- 代码域：apps、services、packages、connectors。
- 运行域：infra、release、operations。
- Gate 4–7 明确工程完成、Beta、生产发布和上线后决策。

## 4. Ameme 的最小治理原则

1. 正本唯一，但历史和失败证据可追溯。
2. 状态来自真实验证，不来自计划文字。
3. 用户只在重要决策点介入，过程自动闭环。
4. 隐私和删除是产品能力，也是发布 Gate。
5. 文档、代码、测试、发布和运行属于同一个生命周期。
6. 先让健康检查小而真实，再按实际失败增加规则，避免复制 FORMALdoc 的全部复杂度。
