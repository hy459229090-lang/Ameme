# Android Mobile 来源体验包

这套本地工具只准备全合成的 Android 系统来源：一张图片、一段 2 秒 WAV 音频、一个本地日历和两条日历计划。它不会直接创建 Ameme Event；最终写入仍经过正式 Photo Picker、OpenDocument 音频选择器、只读 Calendar 导入和 ACTION_SEND，再进入 SQLCipher。

## 一键准备

在仓库根目录运行：

```powershell
.\scripts\dev\android\prepare_source_experience_pack.ps1 `
  -Serial emulator-5554 `
  -AndroidSdk C:\Users\N33131\AppData\Local\Android\Sdk `
  -JavaHome C:\Users\N33131\AppData\Local\Programs\Microsoft\jdk-17-ameme `
  -ResetAppData
```

脚本构建并安装 Debug App/Test APK，通过测试包向系统 Provider 准备来源，然后用正式 ACTION_SEND 启动一条合成文本分享。目标 Ameme APK 仍只声明 `READ_CALENDAR`；`WRITE_CALENDAR` 只属于测试包。

## 人工体验清单

1. 首次进入：点击“查看今天”。确认今天页出现 `AMEME_SYNTHETIC_SHARED_NOTE_20260714`，这条记录由正式 ACTION_SEND 写入并显示为“用户陈述”。
2. 照片：点击“记录一件事” → “照片”，在系统 Photo Picker 选择带有 `AMEME / SYNTHETIC PHOTO` 字样的图片。返回后确认今天页出现“选择了一张照片”。
3. 语音：点击“记录一件事” → “语音” → “选择已有音频”，在系统文件选择器从“音频” → “Unknown” → “Ameme Experience”选择 `Ameme Synthetic Tone.wav`。点击卡片正文，不要点击右上角的试听图标。返回后确认出现“选择了一段语音”；产品不会生成转写文本。
4. 日历：点击“记录一件事” → “导入”，同意只读日历权限，勾选 `Ameme Synthetic Calendar`，选择“7 天”，点击“确认导入”。今天页确认“Ameme 合成项目复盘”保持“计划，未确认发生”；搜索页确认次日的“Ameme 合成散步计划”同样保持计划状态。
5. 重启 App：确认以上已完成入口的记录仍可在今天页或搜索中读取，证明最终结果来自 SQLCipher，而不是体验包直接 seed。
6. 权限核对：Ameme 只请求日历读取权限；图片、音频仍使用单次系统选择授权。位置和 Health 尚未实现，不应出现相应数据或权限。
7. AI 小结：先按 `services/ameme-inference-gateway/README.md` 启动显式 fake 网关。确保今天至少有两条可用结构化事件（分享文字、Agent Event 或日历计划），点击“生成 AI 小结”并确认发送范围。API 36 AVD Debug APK 通过 `10.0.2.2:8787` 访问宿主机；照片/音频原文件和 SourceLocator 不会进入请求。

系统选择器受 AVD 媒体索引和 DocumentsUI 展示排序影响；Audio Provider 在 API 36 AVD 中会按“Unknown”分组。Photo Picker 中图片以缩略图识别。

## 清理与回滚

```powershell
.\scripts\dev\android\prepare_source_experience_pack.ps1 `
  -Serial emulator-5554 `
  -AndroidSdk C:\Users\N33131\AppData\Local\Android\Sdk `
  -SkipBuild `
  -Clean
```

清理只删除测试包创建的系统图片、音频和日历；不会静默删除已经通过正式入口写入的 Ameme Event。如需清空这些 Event，使用产品删除入口或显式执行 `adb shell pm clear com.ameme.android`。
