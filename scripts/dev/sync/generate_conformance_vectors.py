# Purpose: Generate or verify canonical SYNC-01 cross-language conformance vectors.
# Input: --write to materialize the JSON fixture, or --check to verify committed goldens.
# Output: Stable JSON summary and, for --write, the canonical synthetic vector file.

from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[3]
PACKAGE_SRC = ROOT / "packages" / "sync-protocol" / "src"
VECTOR_PATH = ROOT / "tests" / "fixtures" / "sync" / "canonical-conformance-vectors.json"
sys.path.insert(0, str(PACKAGE_SRC))

from ameme_sync_protocol.conformance import materialize_document, validate_document  # noqa: E402


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Generate or verify transport-free SYNC-01 conformance vectors."
    )
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write", action="store_true", help="rewrite the canonical JSON fixture")
    mode.add_argument("--check", action="store_true", help="verify the committed fixture")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    expected = materialize_document()
    if args.write:
        VECTOR_PATH.parent.mkdir(parents=True, exist_ok=True)
        VECTOR_PATH.write_text(
            json.dumps(expected, ensure_ascii=False, sort_keys=True, indent=2) + "\n",
            encoding="utf-8",
        )
        print(
            json.dumps(
                {"ok": True, "mode": "write", "path": str(VECTOR_PATH), "vectors": expected["vector_count"]},
                ensure_ascii=False,
                sort_keys=True,
            )
        )
        return 0

    if not VECTOR_PATH.is_file():
        print(json.dumps({"ok": False, "error": "vector_file_missing", "path": str(VECTOR_PATH)}))
        return 1
    actual = json.loads(VECTOR_PATH.read_text(encoding="utf-8"))
    errors = validate_document(actual)
    if actual != expected:
        errors.append("committed vector document differs from deterministic materialization")
    errors = sorted(set(errors))
    print(
        json.dumps(
            {"ok": not errors, "mode": "check", "vectors": len(actual.get("vectors", [])), "errors": errors},
            ensure_ascii=False,
            sort_keys=True,
        )
    )
    return 0 if not errors else 1


if __name__ == "__main__":
    raise SystemExit(main())
