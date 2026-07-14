# Purpose: replay the fixed synthetic AI eval and verify its deterministic differential report.
# Input: committed synthetic AI fixtures and the offline AI processing reference package.
# Output: a content-free machine-checkable JSON verdict; optionally rewrites the committed report.

from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "packages" / "ai-processing-reference"))

from ameme_ai_reference.evaluation import run_synthetic_differential  # noqa: E402


BASE_FIXTURE = ROOT / "tests" / "fixtures" / "ai" / "synthetic_ai_eval.json"
DATASET_PATHS = (
    ROOT / "tests" / "fixtures" / "ai" / "synthetic_ai_fixed_eval_v1.json",
    ROOT / "tests" / "fixtures" / "ai" / "synthetic_ai_attack_delete_v1.json",
)
DEFAULT_REPORT = (
    ROOT / "tests" / "results" / "ai" / "AI-01" / "reference-differential-report.json"
)


def render(report: dict) -> str:
    return json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--write-report",
        action="store_true",
        help="rewrite the committed deterministic report before checking it",
    )
    parser.add_argument("--report", type=Path, default=DEFAULT_REPORT)
    args = parser.parse_args()

    report_path = args.report.resolve()
    report = run_synthetic_differential(
        base_fixture_path=BASE_FIXTURE,
        dataset_paths=DATASET_PATHS,
    )
    rendered = render(report)
    if args.write_report:
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(rendered, encoding="utf-8", newline="\n")

    report_matches = report_path.is_file() and report_path.read_text(
        encoding="utf-8"
    ) == rendered
    ok = (
        report["verdict"] == "pass_synthetic_reference_regression"
        and report_matches
    )
    summary = {
        "ok": ok,
        "claim": report["claim"],
        "verdict": report["verdict"],
        "real_model_quality_proven": report["real_model_quality_proven"],
        "case_count": report["case_count"],
        "matrix": report["matrix"],
        "changed_case_ids": report["differential"]["changed_case_ids"],
        "report_matches_committed": report_matches,
        "report_path": report_path.relative_to(ROOT).as_posix(),
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
