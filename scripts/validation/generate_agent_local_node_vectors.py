# Purpose: materialize or check deterministic synthetic Agent Local Node v1 conformance vectors.
# Input: packages/agent-local-node-protocol executable specification; no network or real user data.
# Output: tests/fixtures/agent/agent-local-node-conformance-v1.json or a drift verdict.

from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[2]
PACKAGE_ROOT = ROOT / "packages" / "agent-local-node-protocol"
FIXTURE = ROOT / "tests" / "fixtures" / "agent" / "agent-local-node-conformance-v1.json"
if str(PACKAGE_ROOT) not in sys.path:
    sys.path.insert(0, str(PACKAGE_ROOT))

from ameme_agent_local_node_protocol import build_conformance_document  # noqa: E402


def _arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Generate/check Agent Local Node vectors")
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write", action="store_true")
    mode.add_argument("--check", action="store_true")
    return parser.parse_args()


def main() -> int:
    arguments = _arguments()
    materialized = json.dumps(
        build_conformance_document(), ensure_ascii=False, sort_keys=True, indent=2
    ) + "\n"
    if arguments.write:
        FIXTURE.parent.mkdir(parents=True, exist_ok=True)
        FIXTURE.write_text(materialized, encoding="utf-8", newline="\n")
        print(json.dumps({"ok": True, "written": str(FIXTURE.relative_to(ROOT))}))
        return 0
    committed = FIXTURE.read_text(encoding="utf-8") if FIXTURE.exists() else ""
    ok = committed == materialized
    print(
        json.dumps(
            {"ok": ok, "fixture": str(FIXTURE.relative_to(ROOT)), "drift": not ok},
            indent=2,
        )
    )
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
