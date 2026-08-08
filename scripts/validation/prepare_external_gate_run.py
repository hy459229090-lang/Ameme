# Purpose: create a private, blank external-gate run directory without claiming evidence.
# Input: a safe run ID and the exact reviewed 40-hex commit.
# Output: copied content-free templates plus a template manifest under data/private by default.

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import shutil
import sys


ROOT = Path(__file__).resolve().parents[2]
TEMPLATE_ROOT = ROOT / "tests/manual/external-gates"
DEFAULT_OUTPUT_ROOT = ROOT / "data/private/external-gates"
RUN_ID_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{2,63}$")
COMMIT_PATTERN = re.compile(r"^[0-9a-f]{40}$")
COPIED_TEMPLATES = (
    "t0-participant-day-template.csv",
    "t0-reuse-outcome-template.csv",
    "t0-d8-delete-recovery-template.csv",
    "physical-device-run-template.csv",
    "provider-cost-input-template.csv",
    "signing-store-checklist-template.md",
    "external-gate-summary-template.json",
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Prepare blank private templates for a real external Gate run.",
    )
    parser.add_argument("--run-id", required=True)
    parser.add_argument("--build-commit", required=True)
    parser.add_argument(
        "--output-root",
        type=Path,
        default=DEFAULT_OUTPUT_ROOT,
        help="Defaults to Git-ignored data/private/external-gates.",
    )
    return parser.parse_args()


def ensure_safe_output_root(output_root: Path) -> Path:
    resolved = output_root.expanduser().resolve()
    root = ROOT.resolve()
    private = (ROOT / "data/private").resolve()
    if resolved == root or resolved == Path(resolved.anchor):
        raise ValueError("output root is too broad")
    if resolved.is_relative_to(root) and not resolved.is_relative_to(private):
        raise ValueError("workspace output must remain under Git-ignored data/private")
    return resolved


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(64 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    args = parse_args()
    if not RUN_ID_PATTERN.fullmatch(args.run_id):
        raise ValueError("run ID must be 3-64 safe filename characters")
    if not COMMIT_PATTERN.fullmatch(args.build_commit):
        raise ValueError("build commit must be an exact lowercase 40-hex reviewed commit")
    output_root = ensure_safe_output_root(args.output_root)
    destination = output_root / args.run_id
    if destination.exists():
        raise FileExistsError(f"external Gate run already exists: {destination}")
    destination.mkdir(parents=True)

    created: list[Path] = []
    for name in COPIED_TEMPLATES:
        source = TEMPLATE_ROOT / name
        if not source.is_file():
            raise FileNotFoundError(f"missing external Gate template: {source}")
        target = destination / name
        shutil.copy2(source, target)
        created.append(target)

    summary_path = destination / "external-gate-summary-template.json"
    summary = json.loads(summary_path.read_text(encoding="utf-8"))
    summary["run_id"] = args.run_id
    summary["build_commit"] = args.build_commit
    summary["generated_at"] = datetime.now(timezone.utc).isoformat()
    summary_path.write_text(
        json.dumps(summary, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )

    manifest = {
        "schema_version": 1,
        "run_id": args.run_id,
        "build_commit": args.build_commit,
        "evidence_status": "blank_templates_only",
        "gate_claim": False,
        "files": {
            path.name: {
                "sha256": sha256(path),
                "size_bytes": path.stat().st_size,
            }
            for path in sorted(created)
        },
    }
    manifest_path = destination / "template-manifest.json"
    manifest_path.write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(
        json.dumps(
            {
                "ok": True,
                "destination": str(destination),
                "templates": len(created),
                "gate_claim": False,
                "next": "Assign real owners and execute docs/release/封闭Beta外部门执行包.md",
            },
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (FileExistsError, FileNotFoundError, ValueError) as error:
        print(json.dumps({"ok": False, "error": str(error)}, indent=2))
        sys.exit(1)
