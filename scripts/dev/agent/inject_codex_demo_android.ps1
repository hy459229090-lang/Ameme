# Purpose: inject a one-time local Codex demo seed into the Android debug app through the gated instrumentation seam.
# Input: a bounded local seed file, a connected debug device/emulator, built app/test APKs, and an Android SDK path.
# Output: a content-free pass/fail summary and a foreground Ameme debug app backed by the production SQLCipher repository.

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$SeedFile,

    [string]$Serial = "emulator-5554",

    [string]$AndroidSdk = $env:ANDROID_HOME,

    [switch]$ResetAppData
)

$ErrorActionPreference = "Stop"
$RepositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\..\.."))
$ResolvedSeed = [System.IO.Path]::GetFullPath($SeedFile)
$RepositoryPrefix = $RepositoryRoot.TrimEnd([System.IO.Path]::DirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar

if ($ResolvedSeed.StartsWith($RepositoryPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "SeedFile must stay outside the Git workspace. Use an OS-local private directory."
}
if (-not (Test-Path -LiteralPath $ResolvedSeed -PathType Leaf)) {
    throw "SeedFile does not exist."
}
$SeedLength = (Get-Item -LiteralPath $ResolvedSeed).Length
if ($SeedLength -lt 1 -or $SeedLength -gt 65536) {
    throw "SeedFile must be between 1 byte and 64 KiB."
}
if ([string]::IsNullOrWhiteSpace($AndroidSdk)) {
    throw "AndroidSdk or ANDROID_HOME is required."
}

$Adb = Join-Path $AndroidSdk "platform-tools\adb.exe"
$AppApk = Join-Path $RepositoryRoot "apps\android\app\build\outputs\apk\debug\app-debug.apk"
$TestApk = Join-Path $RepositoryRoot "apps\android\app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"
foreach ($RequiredFile in @($Adb, $AppApk, $TestApk)) {
    if (-not (Test-Path -LiteralPath $RequiredFile -PathType Leaf)) {
        throw "A required Android artifact is missing. Build the debug app and test APK first."
    }
}

$OnlinePattern = "^" + [regex]::Escape($Serial) + "\s+device$"
if (-not (& $Adb devices | Select-String -Pattern $OnlinePattern)) {
    throw "Requested Android serial is not online."
}

& $Adb -s $Serial install -r $AppApk 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw "App APK install failed." }
& $Adb -s $Serial install -r -t $TestApk 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Test APK install failed." }
if ($ResetAppData) {
    & $Adb -s $Serial shell pm clear com.ameme.android 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "App data reset failed." }
}

$RemoteStaging = "/data/local/tmp/ameme-codex-demo-seed.json"
try {
    & $Adb -s $Serial push $ResolvedSeed $RemoteStaging 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "Seed staging failed." }
    & $Adb -s $Serial shell run-as com.ameme.android mkdir -p files
    if ($LASTEXITCODE -ne 0) { throw "Private files directory creation failed." }
    & $Adb -s $Serial shell run-as com.ameme.android cp $RemoteStaging files/ameme-codex-demo-seed.json
    if ($LASTEXITCODE -ne 0) { throw "Private seed copy failed." }

    $Instrumentation = & $Adb -s $Serial shell am instrument -w -r `
        -e class "com.ameme.android.data.transport.CodexSkillDemoSeedInstrumentedTest#localCodexSkillSeedIntoProductionSqlCipher" `
        -e amemeCodexDemoSeed true `
        "com.ameme.android.test/androidx.test.runner.AndroidJUnitRunner" 2>&1
    if ($LASTEXITCODE -ne 0 -or ($Instrumentation -join "`n") -notmatch "OK \(1 test\)") {
        throw "Codex demo seed instrumentation failed."
    }
} finally {
    & $Adb -s $Serial shell rm -f $RemoteStaging 2>&1 | Out-Null
    & $Adb -s $Serial shell run-as com.ameme.android rm -f files/ameme-codex-demo-seed.json 2>&1 | Out-Null
}

& $Adb -s $Serial shell am force-stop com.ameme.android 2>&1 | Out-Null
& $Adb -s $Serial shell monkey -p com.ameme.android -c android.intent.category.LAUNCHER 1 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Ameme launch failed." }

[ordered]@{
    ok = $true
    serial = $Serial
    reset_app_data = [bool]$ResetAppData
    production_import_endpoint = $false
    storage = "android_sqlcipher"
} | ConvertTo-Json -Compress
