# coverage 一天上下文覆盖研究索引

| 文档/夹具 | 用途 | 当前状态 |
|---|---|---|
| `目标用户一天上下文覆盖研究执行基线.md` | 7 天日记研究、证据口径、规模估算和隐私处理 | `plan_ready`；尚无真实参与者 |
| `../../tests/fixtures/coverage/target-user-context-source-matrix-v1.json` | 目标用户、上下文、来源能力与 3 个合成用户日 | synthetic；不可作为市场证据 |
| `../../scripts/validation/validate_coverage_contracts.py` | 复跑矩阵、契约、保守编译和禁止虚假精度规则 | 可执行 |

## 当前结论

- 已建立 5 个目标用户分群、10 类上下文和 10 种来源能力的合成决策基线。
- 合成结果只证明规则可复跑，不证明真实授权率、用户规模、一天覆盖率或产品价值。
- 首批真实研究优先运行 T0 知识工作者 7 天 Pilot；扩展分群在 Pilot 工具和隐私流程通过后进入。
