# Windows 信息源实测报告 v0.1

实测日期：2026-07-13\
设备：Windows 11 企业版，Build 26200，64 位，16 逻辑处理器，31.9 GiB 内存\
结论状态：低风险能力验证完成；正文采集、持续性能和隐私体验尚未验证

## 1. 实测边界

本轮没有保存窗口标题、UI 文本、浏览器历史或用户文件内容，没有截屏、录屏或录音。测试只输出应用进程名、元素数量、能力是否存在、事件类型和耗时。

使用脚本：

- `windows-capability-probe.ps1`
- `windows-uia-coverage-probe.ps1`

## 2. 结果

| 能力 | 结果 | 实测证据 | 当前判断 |
|---|---|---|---|
| 前台窗口识别 | 通过 | 能获得前台窗口、进程和标题长度；可见顶层窗口 14 个、进程类型 13 个 | 可作为低风险 Activity 信号 |
| UI Automation 顶层结构 | 通过 | 顶层元素 20 个，其中 17 个具有可访问名称 | 可用，但不能推断所有应用正文都可读 |
| UI Automation 应用覆盖 | 部分通过 | Explorer、Terminal、Chrome、Excel 有较多结构；部分 Electron/自绘应用结构很薄或为零 | 必须按应用分层，不能作为万能内容采集器 |
| 文件变更事件 | 通过 | 探针 v0.2 修复事件回调竞态后连续 3 次捕获 create 2、change 3、rename 1、delete 1 | 适合选定目录的事件驱动采集；仍需真实目录噪声与恢复测试 |
| Windows Graphics Capture | 探测失败 | 最终回归返回 `RuntimeException`，且 `capture_attempted=false` | 不能据此宣称可用；应先定位 API/运行时兼容性，并继续保持默认关闭 |
| 浏览器环境 | 通过 | 本机安装 Chrome 和 Edge | 适合验证 Chromium Extension 路线 |
| Git 环境 | 通过 | 本机 `git` 可用 | 可做仓库 opt-in 和 commit/diff 元数据实验 |
| 终端历史 | 未验证 | 本轮明确未读取 | 需要独立授权和密钥脱敏设计后再测 |

## 3. UI Automation 覆盖详情

采用每个窗口最多 300 个节点的广度遍历，不保存元素名称和文本值。

| 应用类型/样本 | 扫描节点 | 命名节点比例 | Text/Value Pattern | 耗时 | 判断 |
|---|---:|---:|---:|---:|---|
| Explorer | 31-37 | 77%-81% | Text 7 | 48-94 ms | 结构较好 |
| Windows Terminal | 38 | 82% | Text 6 | 46 ms | 可获取结构，但不能直接等同于安全读取历史 |
| Chrome | 47-66 | 55%-65% | Text 1、Value 1 | 68-100 ms | 适合窗口/控件信号；网页正文应由 Extension 获取 |
| Excel | 达到 300 上限 | 94% | Text 1、Value 26 | 549 ms | 数据丰富但遍历昂贵，应事件触发或专用解析 |
| Electron/自绘类应用样本 | 0-16 | 0%-14% | 多数为 0 | 0-69 ms | UIA 可能只能看到壳层，需要应用适配或 OCR 降级 |

关键结论：

> “Accessibility-first，OCR fallback”可作为策略，但必须增加“Connector/文件解析优先”。对于浏览器、Office、IDE 和 Electron 应用，最可靠的内容来源往往不是统一 UIA 遍历。

建议内容获取优先级：

1. 官方 API、应用 Connector 或文件原格式解析。
2. Browser Extension / Git / Calendar 等专用适配器。
3. UI Automation 的事件和局部结构。
4. 用户显式触发的截图/OCR。
5. 持续屏幕捕获仅作为可选高级模式，不作为默认基础。

## 4. 浏览器路线判断

Chrome 官方文档表明：

- `activeTab` 可以在用户点击扩展、快捷键或上下文菜单后，临时获得当前页的 URL、标题及脚本执行能力。
- 持续读取标签页的 URL、标题等敏感属性需要 `tabs` 或相应 host permissions。
- Chrome 扩展可以较低成本移植到 Chromium Edge，但仍需单独验证商店和权限声明。

因此建议拆成两档：

### Browser Basic

- 只使用 `activeTab + scripting`。
- 用户点击/快捷键后捕获当前页面。
- 权限更易解释，适合一键 Capture 原型。

### Browser Continuous

- 使用 `tabs/webNavigation` 和用户选择的站点权限。
- 记录 URL、标题、切换和停留时间等元数据。
- 页面正文不默认全站读取；按域名或单页授权。

官方资料：

- [Chrome activeTab](https://developer.chrome.com/docs/extensions/develop/concepts/activeTab)
- [Chrome Tabs API 与权限](https://developer.chrome.com/docs/extensions/reference/api/tabs)
- [Chrome History API](https://developer.chrome.com/docs/extensions/reference/api/history)
- [Chrome Extension 移植到 Edge](https://learn.microsoft.com/en-us/microsoft-edge/extensions/developer-guide/port-chrome-extension)

## 5. Windows Prototype 可进入与暂缓的数据源

### 建议进入下一轮技术样机

1. 前台进程、窗口句柄、开始/结束时间；标题只在端侧分类后决定是否保存。
2. 用户选择目录的文件 create/modify/rename/delete 元数据。
3. Browser Basic 一键捕获；随后比较 Continuous 元数据模式。
4. 用户选择 Git 仓库的 branch、commit 和变更文件元数据。
5. 全局快捷键主动补充一句话，并绑定当前 Episode。

### 需要先做隐私与性能设计

1. UI Automation 正文读取。
2. 终端命令和输出。
3. 文件正文与 diff 内容。
4. 浏览器持续正文读取。

### 暂不进入默认路径

1. 全屏连续截图或录像。
2. 麦克风和系统音频持续录制。
3. 键盘输入和剪贴板持续记录。
4. 未经用户选择的全部磁盘、浏览历史和 IM 正文。

## 6. 下一轮 Windows 实验

建议构建一个只处理元数据的 30-60 分钟采集样机，验证：

- 事件量、重复率和噪声类型。
- 前台窗口切换能否与文件/Git/浏览器事件合并为 Episode。
- CPU、内存、磁盘和电池开销。
- 排除规则和暂停是否可靠。
- 不读取正文时能否还原主要活动。

这一实验仍不等于产品 MVP，只用于验证 Windows 端的最小充分数据组合。

## 7. 回归修复记录

最终回归出现的 `InvalidOperationException` 来自探针实现：异步 FileSystemWatcher 回调写入集合的同时，主线程枚举集合并清理订阅。v0.2 改为先进入 PowerShell 事件队列，再在操作完成后统一读取和清理；使用独立文件验证 delete，避免 rename 后立即删除被系统合并。

修复后连续 3 次结果一致：create 2、change 3、rename 1、delete 1。这个结论只证明合成临时目录中 watcher 可工作，不证明真实项目目录不会丢事件、重复或产生大量临时文件噪声。
