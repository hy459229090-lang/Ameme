# tests

跨组件测试目录。真实个人数据不得作为测试夹具提交。

## 统一验证入口

从仓库根目录运行：

```powershell
python scripts/validation/run_workspace_validation.py
```

该入口按各套件需要的导入路径依次执行契约质量、Core、Sync、Agent、AI、Skill、Markdown 链接和工作区治理。不要用根目录单次 `unittest discover -s tests` 替代它；不同参考包和 Agent harness 有各自明确的模块边界。Android JVM/构建与设备测试仍按 `apps/android/README.md` 单独执行。

建议结构：

- `fixtures/synthetic/`：合成的网页、截图、音频、账单、事件和记忆样本。
- `integration/`：组件与 Connector 集成测试。
- `e2e/`：用户核心旅程。
- `performance/`：CPU、内存、磁盘、网络和模型成本。
- `security/`：权限、越权、密钥、删除和审计。
