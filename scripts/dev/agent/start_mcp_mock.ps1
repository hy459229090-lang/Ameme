# Purpose: Start the local-only Ameme MCP mock over stdio.
# Input: Optional AMEME_MCP_MOCK_DATA_DIR, AMEME_MCP_MOCK_OFFLINE, and AMEME_MCP_MOCK_STORE_BACKEND variables.
# Output: MCP JSON-RPC messages on stdout; durable local integration state under the ignored data directory.
$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "..\..\..")
$server = Join-Path $root "services\ameme-mcp-mock\server.py"
$seed = Join-Path $root "tests\fixtures\agent\synthetic-memories.json"
$backend = if ($env:AMEME_MCP_MOCK_STORE_BACKEND) {
    $env:AMEME_MCP_MOCK_STORE_BACKEND
} else {
    "json"
}
$serverArgs = @("--store-backend", $backend)
if ($backend -eq "json") {
    $serverArgs += @("--seed-file", $seed)
}

& python $server @serverArgs
