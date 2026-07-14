# Purpose: run the complete no-network Ameme Python and workspace validation baseline.
# Input: committed contracts, synthetic fixtures, reference packages, Skill package, docs, and workspace metadata.
# Output: streamed gate results plus a final JSON verdict; exits non-zero on the first failed gate.

from __future__ import annotations

import json
import os
from pathlib import Path
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[2]

PYTHON_PATHS = [
    ROOT,
    ROOT / "packages" / "core-reference",
    ROOT / "packages" / "sync-protocol" / "src",
    ROOT / "packages" / "ai-processing-reference",
    ROOT / "packages" / "agent-local-node-protocol",
    ROOT / "services" / "ameme-mcp-mock",
    ROOT / "tests" / "agent",
]

COMMANDS = [
    ("contract_quality", [sys.executable, "scripts/validation/run_contract_quality.py"]),
    (
        "core_reference",
        [sys.executable, "-m", "unittest", "discover", "-s", "tests/core", "-p", "test_*.py", "-v"],
    ),
    (
        "sync_reference",
        [sys.executable, "-m", "unittest", "discover", "-s", "tests/sync", "-p", "test_*.py", "-v"],
    ),
    (
        "agent_mock",
        [sys.executable, "-m", "unittest", "discover", "-s", "tests/agent", "-p", "test_*.py", "-v"],
    ),
    (
        "agent_android_adapter",
        [
            sys.executable,
            "-m",
            "unittest",
            "discover",
            "-s",
            "services/ameme-mcp-mock/tests",
            "-p",
            "test_*.py",
            "-v",
        ],
    ),
    (
        "ai_reference",
        [sys.executable, "-m", "unittest", "discover", "-s", "tests/ai", "-p", "test_*.py", "-v"],
    ),
    ("ai_fixed_eval", [sys.executable, "scripts/validation/run_ai_reference_eval.py"]),
    ("skill_package", [sys.executable, "scripts/validation/validate_ameme_skill.py"]),
    ("markdown_links", [sys.executable, "scripts/governance/check_markdown_links.py"]),
    ("workspace_governance", [sys.executable, "scripts/governance/check_workspace.py"]),
]


def validation_environment() -> dict[str, str]:
    environment = os.environ.copy()
    inherited = environment.get("PYTHONPATH", "")
    entries = [str(path) for path in PYTHON_PATHS]
    if inherited:
        entries.append(inherited)
    environment["PYTHONPATH"] = os.pathsep.join(entries)
    environment["PYTHONDONTWRITEBYTECODE"] = "1"
    return environment


def main() -> int:
    environment = validation_environment()
    completed: list[str] = []
    for gate, command in COMMANDS:
        print(f"[workspace-validation] {gate}: {' '.join(command)}", flush=True)
        result = subprocess.run(
            command,
            cwd=ROOT,
            env=environment,
            check=False,
            stderr=subprocess.STDOUT,
        )
        if result.returncode != 0:
            print(
                json.dumps(
                    {
                        "ok": False,
                        "completed_gates": completed,
                        "failed_gate": gate,
                        "failed_command": command,
                    },
                    indent=2,
                )
            )
            return result.returncode
        completed.append(gate)

    print(json.dumps({"ok": True, "completed_gates": completed}, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
