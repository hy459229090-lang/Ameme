# Android DB-01 性能探针说明

文档状态：API 36 AVD 基线已验证

适合读者：Android、数据架构、质量与产品负责人

当前结论：`c8fc128` 上已形成可复跑、内容安全的 10k/100k AVD 基线；两项预注册数据库延迟门通过，但它不替代物理设备、16 KB、UI 首帧或电量证据

## 1. 目的

使用完全合成的数据，在同一套 SQLCipher EventNode 上复跑 10k/100k 容量、搜索、分页、单次提交、内存、磁盘和迁移探针。探针输出 `ameme.android.db01.performance.v2` JSON，不输出事件正文、查询结果 ID、URI、密钥或用户数据。

## 2. 覆盖与判定

每个 10k/100k 数据集记录：

- 分批导入耗时、吞吐、数据库体积和单事件体积；
- 已有 FTS 索引下的冷数据库打开耗时；它不是 Today UI 首帧，也不能用于判定 1200ms 首帧目标；
- 首页、FTS 双词 AND 查询、跨字段查询、日期筛选和最多 1000 页 keyset 遍历；
- 在同一加密数据库上强制 FTS 与 LIKE 两条路径，比较共同查询语义和耗时；JSON 只写相等布尔值和数量，不写结果 ID；
- 打底后的独立唯一事件单次提交 p50/p95/max，设计门为 p95 ≤ 300ms；
- `android.os.Debug`/Runtime 的粗粒度进程快照。该数值不是峰值、组件独占内存或真机证据；
- 10k 行可复跑 v3 fixture 到当前 v5 的 3 次迁移打开耗时。迁移计时明确不含 FTS 回填。

100k FTS 关键词第一页数据库查询设计门为 p95 ≤ 700ms。它只评价单台 API 36 AVD 上的数据库 query，不等于 UI 搜索端到端或物理设备门。性能超预算仍生成带 `p95_le_target=false` 的失败证据；只有 schema、隐私标记、10k/100k 数据完整性、迁移字段或 FTS/LIKE 语义缺失才使 runner 直接失败。

## 3. 运行与产物

同一 Android serial 只能运行一个 instrumentation。connected test 会重装相同 application ID；并发会强停另一测试进程，使全部结果失效。runner 使用 per-serial 进程互斥，但直接执行 Gradle 的其他任务仍需由调度方串行化。runner 先构建 APK，再通过 ADB 安装和手动启动单个 instrumentation，并在卸载前立即从 app 私有目录提取报告；不能在 Gradle connected test 完成并卸载 app 后再调用 `run-as`。

```powershell
scripts/validation/run_android_db01_performance.ps1 `
  -AndroidHome C:\Users\N33131\AppData\Local\Android\Sdk `
  -JavaHome C:\Users\N33131\AppData\Local\Programs\Microsoft\jdk-17-ameme `
  -Serial emulator-5554
```

有效运行已生成 [`tests/results/performance/android-db01-api36.json`](../../tests/results/performance/android-db01-api36.json)。第一次尝试受同一 serial 的另一条 instrumentation 重装污染；第二次暴露逐条重复查询/FTS 删除热点并使 AVD 失去响应。这两次均判为无效。批量路径改为分块存在性预检和新记录快速插入后，最终手动 instrumentation 在 137.4 秒完成 1/1 测试，runner 的 schema、隐私标记、数据集、迁移、语义一致性和延迟字段校验全部通过。

## 4. 有效基线

| 指标 | 10k | 100k | 判定 |
|---|---:|---:|---|
| 批量导入 | 2,302 ms / 4,344 events/s | 49,225 ms / 2,032 events/s | 有效基线，无预注册硬门 |
| 加密数据库体积 | 9,502,720 bytes | 95,227,904 bytes | 约 950–952 bytes/event |
| 已有 FTS 冷打开 | 943 ms | 1,150 ms | 仅 DB open，不是 Today 首帧 |
| FTS 关键词页 P95 | 9.24 ms | 177.72 ms | 100k ≤ 700 ms，通过 |
| 单次提交 P95 | 8.26 ms | 215.67 ms | ≤ 300 ms，通过 |
| 日期筛选页 P95 | 75.61 ms | 734.56 ms | 无硬门，列为下一轮优化观察项 |
| 进程 PSS（查询后粗快照） | 91,816 KB | 92,112 KB | 非峰值、非组件独占、非真机 |
| v3→v5 迁移 P95 | — | 892.51 ms（10k fixture） | 不含 FTS 回填 |

两种查询路径在测试的关键词和跨字段查询上结果 ID 完全一致，报告未输出这些 ID。100k keyset walk 按探针上限只遍历 1,000 页/50,000 条，不宣称遍历了完整 100k。

## 5. 待验证边界

- 需要另补物理设备、最低候选设备、16KB page-size、进程死亡和真实 UI 搜索/Today 首帧证据；
- AVD memory 只能作为趋势快照，不支持电量、峰值内存或真机容量结论。
- 日期筛选 100k P95 734.56 ms、完整 100k 历史遍历、长期增量写入和大批量首次导入仍需后续性能设计；当前两项硬门通过不代表 DB-01 全部性能工作永久关闭。
