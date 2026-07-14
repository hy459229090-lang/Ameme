# Purpose: Start the local-only Ameme MCP mock over stdio.
# Input: Optional AMEME_MCP_MOCK_DATA_DIR and AMEME_MCP_MOCK_OFFLINE environment variables.
# Output: MCP JSON-RPC messages on stdout; durable synthetic state under the ignored local data directory.
$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "..\..\..")
$server = Join-Path $root "services\ameme-mcp-mock\server.py"
$seed = Join-Path $root "tests\fixtures\agent\synthetic-memories.json"

python $server --seed-file $seed
