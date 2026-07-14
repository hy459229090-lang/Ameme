# Purpose: Probe Windows window metadata, UI Automation, file events, and environment without saving user content.
# Input: Current Windows environment and optional OutputPath.
# Output: Redacted JSON capability summary, optionally written to OutputPath.
param(
    [string]$OutputPath = ""
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest

function Get-SafeError {
    param([System.Management.Automation.ErrorRecord]$Record)
    return $Record.Exception.GetType().Name
}

$result = [ordered]@{
    probe_version = "0.2"
    captured_at = (Get-Date).ToUniversalTime().ToString("o")
    privacy = [ordered]@{
        window_titles_recorded = $false
        ui_text_recorded = $false
        screenshots_taken = $false
        audio_recorded = $false
        browser_history_read = $false
    }
}

try {
    $os = Get-CimInstance Win32_OperatingSystem
    $computer = Get-CimInstance Win32_ComputerSystem
    $result.system = [ordered]@{
        os_caption = $os.Caption
        os_version = $os.Version
        os_build = $os.BuildNumber
        architecture = $os.OSArchitecture
        powershell_version = $PSVersionTable.PSVersion.ToString()
        logical_processors = [int]$computer.NumberOfLogicalProcessors
        memory_gib = [math]::Round([double]$computer.TotalPhysicalMemory / 1GB, 1)
    }
} catch {
    $result.system = [ordered]@{ error = Get-SafeError $_ }
}

try {
    Add-Type @"
using System;
using System.Text;
using System.Runtime.InteropServices;
public static class AmemeWindowProbe {
    [DllImport("user32.dll")]
    public static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll", SetLastError=true)]
    public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint processId);
    [DllImport("user32.dll", CharSet=CharSet.Unicode, SetLastError=true)]
    public static extern int GetWindowTextLength(IntPtr hWnd);
}
"@
    $foreground = [AmemeWindowProbe]::GetForegroundWindow()
    [uint32]$processId = 0
    [void][AmemeWindowProbe]::GetWindowThreadProcessId($foreground, [ref]$processId)
    $foregroundProcess = Get-Process -Id $processId -ErrorAction Stop
    $visibleProcesses = Get-Process | Where-Object { $_.MainWindowHandle -ne 0 }

    $result.window_metadata = [ordered]@{
        foreground_available = ($foreground -ne [IntPtr]::Zero)
        foreground_process = $foregroundProcess.ProcessName
        foreground_title_length = [AmemeWindowProbe]::GetWindowTextLength($foreground)
        visible_top_level_window_count = @($visibleProcesses).Count
        visible_process_type_count = @($visibleProcesses.ProcessName | Sort-Object -Unique).Count
    }
} catch {
    $result.window_metadata = [ordered]@{ error = Get-SafeError $_ }
}

try {
    Add-Type -AssemblyName UIAutomationClient
    Add-Type -AssemblyName UIAutomationTypes
    $root = [System.Windows.Automation.AutomationElement]::RootElement
    $children = $root.FindAll(
        [System.Windows.Automation.TreeScope]::Children,
        [System.Windows.Automation.Condition]::TrueCondition
    )
    $namedCount = 0
    $controlTypes = @{}
    foreach ($item in $children) {
        try {
            $name = $item.Current.Name
            if (-not [string]::IsNullOrWhiteSpace($name)) { $namedCount++ }
            $controlType = $item.Current.ControlType.ProgrammaticName
            if (-not $controlTypes.ContainsKey($controlType)) { $controlTypes[$controlType] = 0 }
            $controlTypes[$controlType]++
        } catch {
            # Some elevated or protected windows cannot be inspected.
        }
    }
    $focused = [System.Windows.Automation.AutomationElement]::FocusedElement
    $result.ui_automation = [ordered]@{
        root_available = ($null -ne $root)
        top_level_elements = $children.Count
        named_top_level_elements = $namedCount
        control_type_counts = $controlTypes
        focused_element_available = ($null -ne $focused)
        focused_element_name_length = if ($null -ne $focused) { $focused.Current.Name.Length } else { 0 }
        text_content_persisted = $false
    }
} catch {
    $result.ui_automation = [ordered]@{ error = Get-SafeError $_ }
}

$subscriptions = @()
$watcher = $null
$tempRoot = $null
$eventPrefix = "AmemeProbe." + [guid]::NewGuid().ToString("N")
try {
    $tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("ameme-probe-" + [guid]::NewGuid().ToString("N"))
    [System.IO.Directory]::CreateDirectory($tempRoot) | Out-Null
    $watcher = [System.IO.FileSystemWatcher]::new($tempRoot)
    $watcher.IncludeSubdirectories = $true
    $subscriptions = @(
        Register-ObjectEvent $watcher Created -SourceIdentifier ($eventPrefix + ".created")
        Register-ObjectEvent $watcher Changed -SourceIdentifier ($eventPrefix + ".changed")
        Register-ObjectEvent $watcher Renamed -SourceIdentifier ($eventPrefix + ".renamed")
        Register-ObjectEvent $watcher Deleted -SourceIdentifier ($eventPrefix + ".deleted")
    )
    $watcher.EnableRaisingEvents = $true
    $fileA = Join-Path $tempRoot "probe-a.txt"
    $fileB = Join-Path $tempRoot "probe-b.txt"
    $fileC = Join-Path $tempRoot "probe-delete.txt"
    [System.IO.File]::WriteAllText($fileA, "ameme capability probe")
    Start-Sleep -Milliseconds 150
    [System.IO.File]::AppendAllText($fileA, " updated")
    Start-Sleep -Milliseconds 150
    [System.IO.File]::Move($fileA, $fileB)
    Start-Sleep -Milliseconds 150
    # Use a separate, settled file for delete verification. Deleting a file
    # immediately after rename can be coalesced by FileSystemWatcher.
    [System.IO.File]::WriteAllText($fileC, "delete event probe")
    Start-Sleep -Milliseconds 300
    [System.IO.File]::Delete($fileC)
    Start-Sleep -Milliseconds 1500

    $eventSnapshot = @(
        Get-Event |
            Where-Object { $_.SourceIdentifier -like ($eventPrefix + ".*") } |
            ForEach-Object { $_.SourceIdentifier.Substring($eventPrefix.Length + 1) }
    )
    $eventCounts = @{}
    foreach ($eventName in $eventSnapshot) {
        if (-not $eventCounts.ContainsKey($eventName)) { $eventCounts[$eventName] = 0 }
        $eventCounts[$eventName]++
    }
    $result.file_events = [ordered]@{
        watcher_operational = ($eventSnapshot.Count -gt 0)
        event_counts = $eventCounts
        test_scope = "temporary synthetic directory"
        user_files_read = $false
    }
} catch {
    $result.file_events = [ordered]@{ error = Get-SafeError $_ }
} finally {
    foreach ($subscription in $subscriptions) {
        Unregister-Event -SubscriptionId $subscription.Id -ErrorAction SilentlyContinue
    }
    Remove-Event -SourceIdentifier ($eventPrefix + ".*") -ErrorAction SilentlyContinue
    if ($null -ne $watcher) { $watcher.Dispose() }
    if (-not [string]::IsNullOrWhiteSpace($tempRoot) -and [System.IO.Directory]::Exists($tempRoot)) {
        [System.IO.Directory]::Delete($tempRoot, $true)
    }
}

try {
    $apiInformation = [Windows.Foundation.Metadata.ApiInformation, Windows.Foundation, ContentType = WindowsRuntime]
    $capturePresent = $apiInformation::IsTypePresent("Windows.Graphics.Capture.GraphicsCaptureSession")
    $result.screen_capture = [ordered]@{
        graphics_capture_api_present = $capturePresent
        capture_attempted = $false
        requires_explicit_product_permission_and_visible_state = $true
    }
} catch {
    $result.screen_capture = [ordered]@{ error = Get-SafeError $_; capture_attempted = $false }
}

try {
    $browserCandidates = [ordered]@{
        edge = @(
            "$env:ProgramFiles (x86)\Microsoft\Edge\Application\msedge.exe",
            "$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe"
        )
        chrome = @(
            "$env:ProgramFiles\Google\Chrome\Application\chrome.exe",
            "$env:ProgramFiles (x86)\Google\Chrome\Application\chrome.exe",
            "$env:LOCALAPPDATA\Google\Chrome\Application\chrome.exe"
        )
        firefox = @(
            "$env:ProgramFiles\Mozilla Firefox\firefox.exe",
            "$env:ProgramFiles (x86)\Mozilla Firefox\firefox.exe"
        )
    }
    $installed = [ordered]@{}
    foreach ($browser in $browserCandidates.Keys) {
        $installed[$browser] = (@($browserCandidates[$browser] | Where-Object { Test-Path -LiteralPath $_ }).Count -gt 0)
    }
    $result.browser_environment = [ordered]@{
        installed = $installed
        profiles_inspected = $false
        history_read = $false
        recommended_path = "extension with per-site permission and one-click capture"
    }
} catch {
    $result.browser_environment = [ordered]@{ error = Get-SafeError $_ }
}

try {
    $git = Get-Command git -ErrorAction SilentlyContinue
    $result.developer_sources = [ordered]@{
        git_available = ($null -ne $git)
        repository_content_read = $false
        terminal_history_read = $false
        recommended_git_path = "repository opt-in plus git metadata and hooks"
        recommended_terminal_path = "explicit shell integration with secret redaction"
    }
} catch {
    $result.developer_sources = [ordered]@{ error = Get-SafeError $_ }
}

$json = $result | ConvertTo-Json -Depth 8
if (-not [string]::IsNullOrWhiteSpace($OutputPath)) {
    [System.IO.File]::WriteAllText($OutputPath, $json, [System.Text.UTF8Encoding]::new($false))
}
$json
