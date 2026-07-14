# ADR-005：MVP 技术实现默认栈与保留参数

- 状态：accepted
- 日期：2026-07-14
- 决策人：产品负责人授权按专业建议代决 + 架构/安全基线
- 影响 Gate：Gate 2–6
- 替代/被替代：细化 ADR-002/003/004、SDR-001/002

## 决议

研发使用下列默认值开工；Spike 的职责是验证或否决，不再把选型原样退回产品负责人。

| 层 | MVP 默认实现 |
|---|---|
| iOS | SwiftUI + 原生导航/Picker/权限；deployment target iOS 18.0 |
| Android | Kotlin + Jetpack Compose Material 3；minSdk 34、targetSdk 36 |
| 结构化本地库 | 固定官方 `net.zetetic:sqlcipher-android:4.15.0`（Android）+ SQLite WAL；FTS5 仍由后续搜索 Spike 接入；iOS/Android 通过各自 repository adapter 接入 |
| 账户与密钥 | 账户 ID 作为数据归属；设备身份/透明包装密钥存 Keychain/Android Keystore，不向用户暴露 recovery key；每 Raw object 仍用内部 DEK |
| Raw Vault | 应用私有目录，每对象 AES-256-GCM、独立 nonce/DEK/hash；默认不进普通系统备份 |
| 源文件索引 | 对外部 Raw 保留系统授权范围内的 opaque locator/bookmark/content URI、fingerprint 和可用状态；不把明文绝对路径写入日志或跨端正文 |
| 本地搜索 | 日期/字段索引 + FTS5；embedding 默认关闭，只有 SEARCH-01 证明增益才开启 |
| 账户服务 | 只提供身份/会话/设备归属，不保存或路由用户记忆数据 |
| AI | R0/R1 确定性/端侧优先，云模型通过 provider adapter；Restricted 默认阻断外部模型 |
| 同步 | LAN Peer Sync：Bonjour/Network.framework + Android NSD；append-only envelope、revision、cursor、tombstone、ack/proof |

## 默认保留参数

| 对象 | 默认 TTL/保留 |
|---|---|
| 原始机会式位置点 | 24 小时；事件化成功后可提前清理 |
| 主动音频 Raw | 7 天恢复窗；用户明确保留则转 `user_retained` |
| 照片预览/缩略缓存 | 7 天；系统原图仍留相册 |
| 模型 request/response 临时缓存 | 24 小时；之后只留版本、hash 和状态 |
| ContextPack | 15 分钟，Grant 撤销立即失效 |
| 本机导出包 | 24 小时或成功分享后立即清理 |
| 幂等记录 | 7 天 |
| 迁移/回滚快照 | 7 天，成功稳定后清理 |
| 安全访问审计 | 180 天，无正文 |
| tombstone/删除证明 | 所有已知 peer 确认且至少 90 天；离线/遗失设备未确认时不得提前显示完整完成 |

所有 TTL 集中配置、可迁移、可测试；RAW/DEL/SEC Spike 可以收紧数值。延长涉及 Raw、Restricted 或安全审计时必须走 SDR，不允许散落魔法数字。

## 依据与约束

- Android 采用 SQLCipher 官方长期支持的新包 `net.zetetic:sqlcipher-android:4.15.0`，不使用已废弃的 `android-database-sqlcipher`：<https://github.com/sqlcipher/sqlcipher-android>、<https://central.sonatype.com/artifact/net.zetetic/sqlcipher-android/4.15.0>、<https://www.zetetic.net/blog/2026/04/28/sqlcipher-4.15.0-release/>
- SQLCipher 提供 SQLite 全库加密；当前只有 Android API 36 x86_64 AVD 的 WAL、错误密钥、文件头、重建和迁移证据，不能外推为 iOS、真机、16 KB page size、后台锁或性能通过：<https://www.zetetic.net/sqlcipher/documentation/>
- iOS 使用系统 Data Protection 与 Keychain；Android 密钥保持在 Keystore：<https://developer.apple.com/documentation/uikit/encrypting-your-app-s-files>、<https://developer.android.com/privacy-and-security/keystore>
- Health Connect 在 Android 14+ 为系统能力，降低封闭 MVP 的安装/迁移分支：<https://developer.android.com/health-and-fitness/health-connect/availability>

## 否决与回滚

- SQLCipher 若不能达到性能/许可证/后台兼容门，退回 OS 文件保护 + 字段级 envelope encryption 的替代 ADR，不得改用明文 SQLite。
- LAN discovery/transport 可替换，但同步 envelope、导出和删除证明不可依赖单一平台 API；未来增加云必须新建决策。
- provider、数据库和 SDK 版本必须锁文件/checksum；安全更新走兼容与迁移测试。
