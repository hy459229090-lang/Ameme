# Android DB-01 性能探针说明

文档状态：待验证

适合读者：Android、数据架构、质量与产品负责人

当前结论：探针实现和 JVM/构建/lint 已通过；没有可引用的设备性能基线

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
- 10k 行可复跑 v3 fixture 到当前 v4 的 3 次迁移打开耗时。迁移计时明确不含 FTS 回填。

100k FTS 关键词第一页数据库查询设计门为 p95 ≤ 700ms。它只评价单台 API 36 AVD 上的数据库 query，不等于 UI 搜索端到端或物理设备门。性能超预算仍生成带 `p95_le_target=false` 的失败证据；只有 schema、隐私标记、10k/100k 数据完整性、迁移字段或 FTS/LIKE 语义缺失才使 runner 直接失败。

## 3. 运行与产物

同一 Android serial 只能运行一个 instrumentation。connected test 会重装相同 application ID；并发会强停另一测试进程，使全部结果失效。runner 使用 per-serial 进程互斥，但直接执行 Gradle 的其他任务仍需由调度方串行化。

```powershell
scripts/validation/run_android_db01_performance.ps1 `
  -AndroidHome C:\Users\N33131\AppData\Local\Android\Sdk `
  -JavaHome C:\Users\N33131\AppData\Local\Programs\Microsoft\jdk-17-ameme `
  -Serial emulator-5554
```

有效运行应生成 `tests/results/performance/android-db01-api36.json`。首次尝试受到同一 serial 上另一条 instrumentation 重装污染，测试进程被外部强停，因此本工作树不保留该次部分结果，也不声明任何性能通过。

## 4. 待验证边界

- 需要在合并后的固定 commit 上独占 API 36 AVD 串行重跑普通 connected regression 和性能 probe；
- 需要另补物理设备、最低候选设备、16KB page-size、进程死亡和真实 UI 搜索/Today 首帧证据；
- AVD memory 只能作为趋势快照，不支持电量、峰值内存或真机容量结论。
