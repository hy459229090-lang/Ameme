# Purpose: prepare or remove a synthetic Android source experience pack and start a formal ACTION_SEND capture.
# Input: a connected API 36 AVD, Android SDK, JDK 17, and built or buildable Ameme debug APKs.
# Output: verified system photo/audio/calendar sources, a formal synthetic text share, and a content-free JSON summary.

[CmdletBinding()]
param(
    [string]$Serial = "emulator-5554",
    [string]$AndroidSdk = $env:ANDROID_HOME,
    [string]$JavaHome = $env:JAVA_HOME,
    [switch]$ResetAppData,
    [switch]$SkipBuild,
    [switch]$Clean
)

$ErrorActionPreference = "Stop"
$RepositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\..\.."))
$AndroidProject = Join-Path $RepositoryRoot "apps\android"

if ([string]::IsNullOrWhiteSpace($AndroidSdk)) {
    throw "AndroidSdk or ANDROID_HOME is required."
}
if (-not $SkipBuild -and [string]::IsNullOrWhiteSpace($JavaHome)) {
    throw "JavaHome or JAVA_HOME is required when building."
}

$Adb = Join-Path $AndroidSdk "platform-tools\adb.exe"
$Gradle = Join-Path $AndroidProject "gradlew.bat"
$AppApk = Join-Path $AndroidProject "app\build\outputs\apk\debug\app-debug.apk"
$TestApk = Join-Path $AndroidProject "app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"
if (-not (Test-Path -LiteralPath $Adb -PathType Leaf)) {
    throw "adb.exe was not found under the selected Android SDK."
}

$OnlinePattern = "^" + [regex]::Escape($Serial) + "\s+device$"
if (-not (& $Adb devices | Select-String -Pattern $OnlinePattern)) {
    throw "Requested Android serial is not online."
}
$ApiLevel = ((& $Adb -s $Serial shell getprop ro.build.version.sdk) -join "").Trim()
$IsEmulator = ((& $Adb -s $Serial shell getprop ro.kernel.qemu) -join "").Trim()
if ($ApiLevel -ne "36" -or $IsEmulator -ne "1") {
    throw "Synthetic source preparation is restricted to an API 36 Android emulator."
}

if (-not $SkipBuild) {
    $PreviousJavaHome = $env:JAVA_HOME
    $PreviousAndroidHome = $env:ANDROID_HOME
    try {
        $env:JAVA_HOME = $JavaHome
        $env:ANDROID_HOME = $AndroidSdk
        Push-Location $AndroidProject
        try {
            & $Gradle :app:assembleDebug :app:assembleDebugAndroidTest
            if ($LASTEXITCODE -ne 0) { throw "Android experience APK build failed." }
        } finally {
            Pop-Location
        }
    } finally {
        $env:JAVA_HOME = $PreviousJavaHome
        $env:ANDROID_HOME = $PreviousAndroidHome
    }
}

foreach ($RequiredFile in @($AppApk, $TestApk)) {
    if (-not (Test-Path -LiteralPath $RequiredFile -PathType Leaf)) {
        throw "A required Android artifact is missing. Build without -SkipBuild first."
    }
}

& $Adb -s $Serial install -r $AppApk 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw "App APK install failed." }
& $Adb -s $Serial install -r -t $TestApk 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Test APK install failed." }

if ($ResetAppData -and -not $Clean) {
    & $Adb -s $Serial shell pm clear com.ameme.android 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "App data reset failed." }
}

if ($Clean) {
    $Instrumentation = & $Adb -s $Serial shell am instrument -w -r `
        -e class "com.ameme.android.experience.SourceExperiencePackInstrumentedTest#removeSyntheticSystemSources" `
        -e amemeSourceExperienceCleanup true `
        "com.ameme.android.test/androidx.test.runner.AndroidJUnitRunner" 2>&1
    if ($LASTEXITCODE -ne 0 -or ($Instrumentation -join "`n") -notmatch "OK \(1 test\)") {
        throw "Synthetic source cleanup failed."
    }
    [ordered]@{
        ok = $true
        serial = $Serial
        action = "clean"
        system_sources_remaining = 0
        ameme_events_deleted = $false
    } | ConvertTo-Json -Compress
    exit 0
}

$Instrumentation = & $Adb -s $Serial shell am instrument -w -r `
    -e class "com.ameme.android.experience.SourceExperiencePackInstrumentedTest#prepareSyntheticPhotoAudioAndCalendarSources" `
    -e amemeSourceExperiencePack true `
    "com.ameme.android.test/androidx.test.runner.AndroidJUnitRunner" 2>&1
if ($LASTEXITCODE -ne 0 -or ($Instrumentation -join "`n") -notmatch "OK \(1 test\)") {
    throw "Synthetic source experience preparation failed."
}

& $Adb -s $Serial shell am force-stop com.ameme.android 2>&1 | Out-Null
$ShareResult = & $Adb -s $Serial shell am start -W `
    -a android.intent.action.SEND `
    -c android.intent.category.DEFAULT `
    -t text/plain `
    --es android.intent.extra.TEXT "AMEME_SYNTHETIC_SHARED_NOTE_20260714" `
    -n "com.ameme.android/.MainActivity" 2>&1
if ($LASTEXITCODE -ne 0 -or ($ShareResult -join "`n") -notmatch "Status:\s+ok") {
    throw "Formal ACTION_SEND launch failed."
}

$Checklist = Join-Path $PSScriptRoot "README.md"
[ordered]@{
    ok = $true
    serial = $Serial
    api = $ApiLevel
    reset_app_data = [bool]$ResetAppData
    prepared_system_sources = @("photo", "audio", "calendar")
    formal_action_send_started = $true
    direct_event_seed = $false
    location = "not_implemented"
    health = "not_implemented"
    manual_checklist = $Checklist
} | ConvertTo-Json -Compress
