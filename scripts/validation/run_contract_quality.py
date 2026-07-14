# Purpose: run the complete Ameme contract compatibility, runtime, harness and log-safety quality baseline.
# Input: current workspace contract artifacts and synthetic tests; no network or real user data.
# Output: streamed command results plus a final JSON verdict; exits non-zero on the first failed gate.

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
COMMANDS = [
    [sys.executable, "scripts/validation/check_contract_compatibility.py"],
    [sys.executable, "scripts/validation/validate_contracts.py"],
    [sys.executable, "scripts/validation/validate_agent_local_node_protocol.py"],
    [sys.executable, "-m", "unittest", "discover", "-s", "packages/agent-local-node-protocol/tests", "-p", "test_*.py", "-v"],
    [sys.executable, "-m", "unittest", "discover", "-s", "tests/contracts", "-p", "test_*.py", "-v"],
    [sys.executable, "-m", "unittest", "discover", "-s", "tests/harness", "-p", "test_*.py", "-v"],
    [sys.executable, "-m", "unittest", "discover", "-s", "tests/security/contract", "-p", "test_*.py", "-v"],
]


def main() -> int:
    completed = 0
    for command in COMMANDS:
        print(f"[contract-quality] running: {' '.join(command)}", flush=True)
        result = subprocess.run(command, cwd=ROOT, check=False, stderr=subprocess.STDOUT)
        if result.returncode != 0:
            print(json.dumps({"ok": False, "completed_gates": completed, "failed_command": command}, indent=2))
            return result.returncode
        completed += 1
    print(json.dumps({"ok": True, "completed_gates": completed}, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
