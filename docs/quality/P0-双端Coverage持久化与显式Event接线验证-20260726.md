Verdict: `conditional_pass`
Scope / Build / Commit: Android SQLCipher schema v7 与 iOS AES-GCM local-store envelope v2 的 Coverage 持久化、显式 Candidate→Event 接受、Event 删除后 link detach/不复活；工作区未提交状态，未形成发布提交
Gate / Spike ID: `P0-COVERAGE-PERSISTENCE-EVENT-02`
Owner / Reviewer: Codex 主执行 Agent / Android 与 iOS 非视觉实现独立复核
Date / Environment: 2026-07-26；macOS Command Line Tools、Swift Package、临时 JDK 17、Android SDK 36、隔离 Python 3.12 venv

# P0 双端 Coverage 持久化与显式 Event 接线验证

## 结论

Android 与 iOS 生产存储现已能保存版本化 `CoverageCompilation`，且保存 Coverage 本身不会创建 Event、Revision 或 DayLedger。只有显式接受一个仍为 `open` 的 Candidate，才会在同一原子写入中创建 Event、消费 Candidate 并保存可审计 link；删除该 Event 会把 link 标为 `detached`，但 Candidate 保持终态，重新编译或重启后不能复活或再次接受。

iOS 已由生产 `AmemeSharedSmoke` 实际执行加密保存、重载、接受、重编译、删除、再次重载与不复活。Android 已通过 JVM 契约、schema v7 源码、完整 Debug 单测/lint/APK 和 androidTest 编译；SQLCipher v7 迁移与运行时 instrumentation 尚未在 AVD 或物理设备执行，因此本报告不声明 Android 数据库运行时或设备门通过。

该切片不输出“全天覆盖率”，也不把 observation、gap 或 status-only 状态隐式升级为 Event。它尚未关闭 Today 来源状态、长期 Memory、来源级 cascade delete、生产备份恢复、物理设备、真实用户或发布 Gate。

## 实现证据

| 平台/层 | 生产实现 | 已建立的不变量 |
|---|---|---|
| Android | `data/CoverageRepository.kt`、`data/local/LocalCoveragePersistence.kt`、`LocalEventDatabase.kt` schema v7、`LocalMemoryRepository.kt` | Coverage snapshot 与 Candidate 生命周期保存在 SQLCipher；显式接受与 Event revision/current projection/DayLedger/link 同事务；Event 删除 detach link；重编译保留终态 |
| iOS | `Shared/CoveragePersistence.swift`、`LocalMemoryStore.swift` envelope v2 | Event、Coverage day、link 同一 AES-GCM 密文 envelope 原子写入；兼容旧 `[MemoryEvent]` 密文；未知 schema/损坏 fail closed |
| 跨端接受语义 | `preserve_evidence` / `user_confirmed` | 计划候选默认仍是 `planned`；只有用户确认才升级对应事实语义；调用方不能注入任意 Event |
| 生命周期 | `open`、`consumed`、`dismissed`、`deleted`；link `active` / `detached` | 只有 `open` 可接受；终态不因重编译或删除 Event 回到 `open` |
| 状态边界 | `source_status_only` 与无 EventHint gap | 可持久化来源状态，但不创建 Candidate/Event；不计算或展示伪造覆盖百分比 |

## 可复现验证

```bash
env JAVA_HOME=/tmp/ameme-jdk17-runtime/Contents/Home \
  ANDROID_HOME=/tmp/ameme-android-sdk-runtime \
  ./gradlew :app:testDebugUnitTest \
    --tests 'com.ameme.android.coverage.*'

env JAVA_HOME=/tmp/ameme-jdk17-runtime/Contents/Home \
  ANDROID_HOME=/tmp/ameme-android-sdk-runtime \
  ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug

env JAVA_HOME=/tmp/ameme-jdk17-runtime/Contents/Home \
  ANDROID_HOME=/tmp/ameme-android-sdk-runtime \
  ./gradlew :app:compileDebugAndroidTestKotlin
```

结果：Coverage 相关 JVM tests 11/11 通过；Debug 全量 JVM tests 72/72，0 failed / 0 errors / 0 skipped；lint 与 Debug APK 构建通过。`LocalCoveragePersistenceInstrumentedTest` 已编译，覆盖持久化不创建 Event、显式接受、重编译终态、删除不复活、重启与 v6→v7 迁移，但未在设备上执行。

```bash
swift build --package-path apps/ios --target AmemeShared
swift run --package-path apps/ios AmemeSharedSmoke
/tmp/ameme-project-py312/bin/python scripts/validation/validate_ios_project.py
```

结果：`AmemeShared` 构建通过；生产 smoke 实际通过 Coverage 密文保存/重载、显式接受、删除 link detach 与重载后不复活；iOS project 46 项通过。`CoveragePersistenceTests.swift` 已进入 Xcode test target，但本机无完整 XCTest runtime，本报告不声明 XCTest 通过。

```bash
/tmp/ameme-project-py312/bin/python \
  scripts/validation/validate_mobile_coverage_contract.py
/tmp/ameme-project-py312/bin/python \
  scripts/validation/run_workspace_validation.py
git diff --check
```

结果：跨端静态契约 279 项、91 个 wire value 通过并明确标记 `static_cross_platform_contract_only`；统一工作区 15/15 Gate 与 diff 检查通过。静态契约只检查模型、接线和不变量 marker，不替代 SQLCipher、XCTest 或设备运行。

## 首次失败、根因与修复

| 首次失败 | 根因 | 修复与重跑 | 剩余影响 |
|---|---|---|---|
| iOS 新持久化类型首次编译因 `Sendable` 报错 | 现有 `CaptureKind`/`Sensitivity` 未声明 `Sendable`，而新接受输入不需要跨并发域承诺 | 移除不必要的 `Sendable`；Shared build 与生产 smoke 重跑通过 | 无 |
| Android 全量编译一次在 Compose `Icons.*` 报错 | 并行视觉 Session 正在把旧图标迁移到本地图标，构建恰好读取到中间状态 | 不修改或回退视觉文件；等待对方完成后重跑，Debug 单测/lint/APK 全绿 | 该失败与 Coverage 代码无关，保留记录以解释工作树并发 |
| Android Coverage day 最初使用 replace upsert | SQLite `REPLACE` 会先删除 parent，可能触发 Candidate/link 外键级联 | 改为 update-then-insert；JVM、完整构建和 androidTest 编译重跑通过 | SQLCipher 运行时仍需设备执行 |
| `swift test` 无法导入 `XCTest` | 当前机器只有 Command Line Tools | 生成 Xcode project 并保留 XCTest 源码；本地用 Shared build/smoke 验证生产路径 | 完整 XCTest 仍为 Xcode/设备 Gate |

## 未关闭 Gate

- Android SQLCipher v6→v7 迁移、加密重开、接受事务与删除不复活的 instrumentation 尚未在 AVD/物理设备执行；物理设备是最终证据。
- iOS XCTest、Simulator/真机进程终止、Keychain 丢失、磁盘故障与物理恢复仍待完整 Xcode 和设备环境。
- 本报告对应的 Coverage/Event 切片未覆盖长期 Memory；后续 `P0-双端生产长期Memory边界验证-20260726.md` 已覆盖双端 evidence revision、显式 confirmation、validity、supersession 与上游变化失效。Today/ContextPack 与真实复用仍未关闭。
- 来源级 cascade delete、ContextPack/摘要/索引不复活、生产备份健康、损坏拒绝、非空目标恢复拒绝和 tombstone/deletion watermark 优先尚未关闭。
- T0 8 人 × 7 天真实 Pilot、真实 Agent 宿主、签名/Provisioning、商店、生产账号与市场规模继续 `hold`。

## 判定

`P0-COVERAGE-PERSISTENCE-EVENT-02` 关闭了仓库内“Coverage 只能存在于纯编译器、无法安全持久化或显式落 Event”的缺口，当前结论为 `conditional_pass`。不得将本报告扩写为 Android 数据库设备运行、生产恢复、长期 Memory、真实一天覆盖、物理设备或封闭 Beta 已通过。
