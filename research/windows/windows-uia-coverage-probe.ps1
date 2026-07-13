# Purpose: Measure UI Automation coverage and traversal time without saving element text.
# Input: Current desktop windows, MaxNodesPerWindow, and MaxWindows.
# Output: Redacted JSON summary with process names, counts, ratios, and elapsed time.
param(
    [int]$MaxNodesPerWindow = 500,
    [int]$MaxWindows = 20
)

$ErrorActionPreference = "Stop"
Set-StrictMode -Version Latest
Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$root = [System.Windows.Automation.AutomationElement]::RootElement
$windows = $root.FindAll(
    [System.Windows.Automation.TreeScope]::Children,
    [System.Windows.Automation.Condition]::TrueCondition
)
$walker = [System.Windows.Automation.TreeWalker]::ControlViewWalker
$rows = [System.Collections.Generic.List[object]]::new()

foreach ($window in @($windows | Select-Object -First $MaxWindows)) {
    try {
        $processId = $window.Current.ProcessId
        if ($processId -le 0) { continue }
        $process = Get-Process -Id $processId -ErrorAction Stop
        $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
        $queue = [System.Collections.Generic.Queue[object]]::new()
        $first = $walker.GetFirstChild($window)
        if ($null -ne $first) { $queue.Enqueue($first) }
        $nodeCount = 0
        $namedCount = 0
        $textPatternCount = 0
        $valuePatternCount = 0
        $protectedErrorCount = 0

        while ($queue.Count -gt 0 -and $nodeCount -lt $MaxNodesPerWindow) {
            $node = $queue.Dequeue()
            $nodeCount++
            try {
                if (-not [string]::IsNullOrWhiteSpace($node.Current.Name)) { $namedCount++ }
                if ($node.TryGetCurrentPattern([System.Windows.Automation.TextPattern]::Pattern, [ref]$null)) {
                    $textPatternCount++
                }
                if ($node.TryGetCurrentPattern([System.Windows.Automation.ValuePattern]::Pattern, [ref]$null)) {
                    $valuePatternCount++
                }
                $child = $walker.GetFirstChild($node)
                if ($null -ne $child) { $queue.Enqueue($child) }
                $sibling = $walker.GetNextSibling($node)
                if ($null -ne $sibling) { $queue.Enqueue($sibling) }
            } catch {
                $protectedErrorCount++
            }
        }
        $stopwatch.Stop()
        $rows.Add([ordered]@{
            process = $process.ProcessName
            nodes_scanned = $nodeCount
            scan_truncated = ($nodeCount -ge $MaxNodesPerWindow)
            named_node_ratio = if ($nodeCount -gt 0) { [math]::Round($namedCount / $nodeCount, 3) } else { 0 }
            text_pattern_nodes = $textPatternCount
            value_pattern_nodes = $valuePatternCount
            protected_errors = $protectedErrorCount
            elapsed_ms = $stopwatch.ElapsedMilliseconds
            content_persisted = $false
        })
    } catch {
        # Skip inaccessible system windows without exposing their details.
    }
}

$summary = [ordered]@{
    probe_version = "0.1"
    privacy = [ordered]@{
        element_names_persisted = $false
        text_values_persisted = $false
        window_titles_persisted = $false
    }
    window_count = $rows.Count
    windows = $rows
}

$summary | ConvertTo-Json -Depth 6
