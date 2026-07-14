# Chrome activeTab 一键 Capture Spike 报告

> 结论状态：低权限闭环已验证\
> 实测日期：2026-07-13\
> 隐私边界：只访问本机临时 HTTP 服务的合成页面；使用临时浏览器配置，结束后删除；未读取真实 Chrome 配置、历史或用户网页。

## 1. 目标

验证 Chrome 扩展能否在不声明任何 `host_permissions`、不读取浏览历史的情况下：

1. 用户触发前看不到当前页 URL 和标题。
2. 用户按快捷键后临时读取当前页的 URL、标题、语言和文本长度。
3. 跨 origin 导航后，旧的页面权限自动撤回。

## 2. 环境

| 项 | 实际值 |
|---|---|
| OS | Windows 11 Enterprise，Build 26200 |
| Chromium | 145.0.7632.6，Playwright 隔离安装 |
| Node.js | 24.14.0 |
| Playwright / playwright-core | 1.61.1 / 1.61.1 |
| Extension | Manifest V3，`activeTab + scripting + storage` |
| host permissions | 未声明 |

系统安装的 Google Chrome 在自动化命令行下没有加载 Manifest V3 service worker，因此正式可复跑验证使用隔离的 Playwright Chromium。这个差异意味着后续仍需补一次用户开发者模式加载或打包扩展测试，不能把本次结果直接等同于 Chrome Web Store 发布验证。

## 3. 可复跑命令

```powershell
$env:AMEME_CHROMIUM_PATH='C:\Users\N33131\AppData\Local\ms-playwright\chromium-1208\chrome-win64\chrome.exe'
$env:NODE_PATH='C:\Users\N33131\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\node_modules;C:\Users\N33131\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\node_modules\.pnpm\playwright-core@1.61.1\node_modules'
& 'C:\Users\N33131\.cache\codex-runtimes\codex-primary-runtime\dependencies\node\bin\node.exe' .\research\browser\run-active-tab-spike.js
```

测试为了模拟浏览器级真实快捷键，会短暂激活隔离 Chromium 窗口并发送 `Alt+Shift+M`。测试页标题唯一，运行前不应打开同名窗口。

## 4. 实际结果

```json
{
  "ok": true,
  "manifestPermissions": ["activeTab", "scripting", "storage"],
  "hostPermissionsDeclared": false,
  "beforeGesture": {
    "urlVisible": false,
    "titleVisible": false
  },
  "capture": {
    "ok": true,
    "titleMatched": true,
    "urlMatched": true,
    "language": "zh-CN",
    "textLength": 45,
    "selectedTextLength": 0
  },
  "afterNavigation": {
    "blocked": true,
    "errorClass": "cannot_access_after_navigation"
  }
}
```

七项断言全部通过：最小权限、无 host permission、触发前 URL/标题不可见、触发后合成页读取成功、标题/URL 匹配、跨 origin 后访问被阻止。

## 5. 关键发现

1. `activeTab` 足以支持用户明确触发的当前页 Capture，不需要安装时申请全站访问。
2. 同 origin 内导航仍保留临时授权；跨 origin 导航才撤回。本次第一次测试误用了同 origin 页面并失败，修正测试后通过。这一规则必须写入产品权限说明。
3. `storage` 仅用于保存测试结果；正式产品可以将结果立即发送给本地 Desktop Agent，并评估是否移除此扩展权限。
4. 当前只验证普通 HTML。PDF、浏览器内部页、Chrome Web Store、扩展页、受 CSP/登录限制页面和 iframe 仍未验证。
5. 本次读取了页面文本长度用于证明脚本执行，不代表正式版应默认上传正文。正式版需要在触发后展示预览、空间和处理位置。

## 6. 对 Prototype 的结论

Browser Basic 可进入 Workday ContextPack Prototype：

- 用户按扩展按钮/快捷键。
- 扩展在当前页临时形成 SourceObject。
- 默认展示标题、来源域、选择文本/正文范围和目标空间的预览。
- 用户确认后交给本地 Agent；跨 origin 自动失去旧权限。

Browser Continuous 仍保持待验证，不能从本次 Spike 推导出持续标签页、历史或全站正文权限可接受。
