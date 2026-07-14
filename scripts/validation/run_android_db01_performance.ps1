# Purpose: Run the explicit Android DB-01 synthetic 10k/100k performance probe.
# Input: Android SDK/JDK paths, one connected API 34+ device or emulator, optional output path.
# Output: Validated content-free JSON metrics under tests/results/performance by default.
param(
    [string]$AndroidHome = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$Serial = $env:ANDROID_SERIAL,
    [string]$OutputPath = ""
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$androidProject = Join-Path $repoRoot "apps\android"
if ([string]::IsNullOrWhiteSpace($AndroidHome)) { throw "ANDROID_HOME or -AndroidHome is required" }
if ([string]::IsNullOrWhiteSpace($JavaHome)) { throw "JAVA_HOME or -JavaHome is required" }
$adb = Join-Path $AndroidHome "platform-tools\adb.exe"
if (-not (Test-Path -LiteralPath $adb)) { throw "adb.exe not found under AndroidHome" }

if ([string]::IsNullOrWhiteSpace($Serial)) {
    $devices = @(& $adb devices | Select-Object -Skip 1 | Where-Object { $_ -match "\sdevice$" } | ForEach-Object { ($_ -split "\s+")[0] })
    if ($devices.Count -ne 1) { throw "Exactly one connected device is required unless -Serial is provided" }
    $Serial = $devices[0]
}

$mutexName = "AmemeAndroidConnectedTest_$($Serial -replace '[^A-Za-z0-9]', '_')"
$mutex = New-Object System.Threading.Mutex($false, $mutexName)
$lockAcquired = $false
try {
    try {
        $lockAcquired = $mutex.WaitOne(0)
    } catch [System.Threading.AbandonedMutexException] {
        $lockAcquired = $true
    }
    if (-not $lockAcquired) {
        throw "Android serial $Serial is already reserved by another Ameme connected-test process"
    }

$env:ANDROID_HOME = $AndroidHome
$env:JAVA_HOME = $JavaHome
$env:ANDROID_SERIAL = $Serial
Push-Location $androidProject
try {
    & .\gradlew.bat assembleDebug assembleDebugAndroidTest --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "Android DB-01 APK build failed" }
} finally {
    Pop-Location
}

$appApk = Join-Path $androidProject "app\build\outputs\apk\debug\app-debug.apk"
$testApk = Join-Path $androidProject "app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"
if (-not (Test-Path -LiteralPath $appApk)) { throw "Android DB-01 app APK is missing" }
if (-not (Test-Path -LiteralPath $testApk)) { throw "Android DB-01 test APK is missing" }

& $adb -s $Serial install -r -t $appApk
if ($LASTEXITCODE -ne 0) { throw "Android DB-01 app APK install failed" }
& $adb -s $Serial install -r -t $testApk
if ($LASTEXITCODE -ne 0) { throw "Android DB-01 test APK install failed" }
$clearResult = (& $adb -s $Serial shell pm clear com.ameme.android 2>&1) -join ""
if ($LASTEXITCODE -ne 0 -or $clearResult.Trim() -ne "Success") {
    throw "Android DB-01 app data reset failed"
}

$instrumentationLines = @(& $adb -s $Serial shell am instrument -w -r `
    -e class com.ameme.android.performance.EventNodePerformanceInstrumentedTest `
    -e amemePerformance true `
    com.ameme.android.test/androidx.test.runner.AndroidJUnitRunner 2>&1)
$instrumentationLines | Write-Output
$instrumentationText = $instrumentationLines -join [Environment]::NewLine
if (
    $LASTEXITCODE -ne 0 -or
    $instrumentationText -match "FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed" -or
    $instrumentationText -notmatch "OK \(1 test\)"
) {
    throw "Android DB-01 instrumented probe failed"
}

if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $OutputPath = Join-Path $repoRoot "tests\results\performance\android-db01-api36.json"
} elseif (-not [System.IO.Path]::IsPathRooted($OutputPath)) {
    $OutputPath = Join-Path $repoRoot $OutputPath
}
$outputDirectory = Split-Path -Parent $OutputPath
New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
$jsonLines = @(& $adb -s $Serial exec-out run-as com.ameme.android cat files/db01-performance.json)
if ($LASTEXITCODE -ne 0 -or $jsonLines.Count -eq 0 -or $jsonLines[0] -notmatch "^\s*\{") {
    throw "Could not read DB-01 metrics from the target app"
}
$json = $jsonLines -join [Environment]::NewLine
$parsed = $json | ConvertFrom-Json
if (
    $parsed.schema -ne "ameme.android.db01.performance.v2" -or
    -not $parsed.synthetic_data_only -or
    $parsed.contains_user_content
) {
    throw "DB-01 report schema/privacy marker is invalid"
}
$datasetCounts = @($parsed.datasets | ForEach-Object { [int]$_.event_count } | Sort-Object)
if ($datasetCounts.Count -ne 2 -or $datasetCounts[0] -ne 10000 -or $datasetCounts[1] -ne 100000) {
    throw "DB-01 report does not contain both required 10k and 100k datasets"
}
foreach ($dataset in $parsed.datasets) {
    if (
        $null -eq $dataset.single_capture_commit_ms.p95 -or
        $null -eq $dataset.forced_like_comparison.keyword_result_ids_equal -or
        $null -eq $dataset.coarse_process_memory.after_queries_and_single_commits.pss_kb
    ) {
        throw "DB-01 dataset is missing required latency, LIKE/FTS, or memory evidence"
    }
}
$dataset100k = @($parsed.datasets | Where-Object { $_.event_count -eq 100000 })[0]
if (
    $null -eq $dataset100k.fts_keyword_page_ms.target_p95_ms -or
    $null -eq $dataset100k.fts_keyword_page_ms.p95_le_target -or
    $null -eq $parsed.migration_v3_to_current.open_and_migrate_ms.p95
) {
    throw "DB-01 report is missing the 100k query budget or migration evidence"
}
$utf8 = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($OutputPath, $json + [Environment]::NewLine, $utf8)
Write-Output $OutputPath
} finally {
    if ($lockAcquired) { $mutex.ReleaseMutex() }
    $mutex.Dispose()
}
