# Purpose: Run deterministic SYNC-01 in-memory scenarios without network or cryptography.
# Input: Optional scenario name, seed, and output JSON path; synthetic fixture values only.
# Output: Stable JSON evidence for convergence, conflict, or incomplete deletion proof.

from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "packages" / "sync-protocol" / "src"))

from ameme_sync_protocol.scenarios import SCENARIOS  # noqa: E402


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run transport-free deterministic Ameme SYNC-01 scenarios."
    )
    parser.add_argument(
        "--scenario",
        choices=["all", *sorted(SCENARIOS)],
        default="all",
        help="scenario to execute",
    )
    parser.add_argument("--seed", type=int, default=20260714)
    parser.add_argument("--output", type=Path, help="optional UTF-8 JSON output path")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    names = sorted(SCENARIOS) if args.scenario == "all" else [args.scenario]
    payload = {
        "scope": "SYNC-01 deterministic protocol simulation only",
        "non_claims": [
            "no Bonjour or NSD",
            "no real socket or LAN",
            "no production cryptography",
            "no account service",
        ],
        "seed": args.seed,
        "results": {name: SCENARIOS[name](seed=args.seed).to_dict() for name in names},
    }
    rendered = json.dumps(payload, ensure_ascii=False, sort_keys=True, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered, encoding="utf-8")
    sys.stdout.write(rendered)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
