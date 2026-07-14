# Purpose: Run the non-production Ameme local-core reference/oracle demo.
# Input: The committed synthetic core-day fixture and an optional SQLite path.
# Output: Deterministic JSON showing capture, Today, Recall, deletion, and rebuild.

from __future__ import annotations

import argparse
from datetime import datetime, timedelta
import json
from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "packages" / "core-reference"))

from ameme_core_reference import CoreOracle, load_synthetic_day  # noqa: E402


class StepClock:
    def __init__(self) -> None:
        self.current = datetime.fromisoformat("2026-07-14T12:00:00+00:00")

    def __call__(self) -> str:
        value = self.current.isoformat(timespec="seconds")
        self.current += timedelta(seconds=1)
        return value


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run the Ameme non-production local-core reference/oracle demo."
    )
    parser.add_argument(
        "--database",
        default=":memory:",
        help="SQLite path for inspection; defaults to an in-memory database.",
    )
    parser.add_argument(
        "--fixture",
        type=Path,
        default=ROOT / "tests" / "fixtures" / "core" / "synthetic_core_day.json",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    fixture = json.loads(args.fixture.read_text(encoding="utf-8"))
    database = args.database
    if database != ":memory:":
        path = Path(database)
        path.parent.mkdir(parents=True, exist_ok=True)
        if path.exists():
            path.unlink()
    with CoreOracle(database, now=StepClock()) as core:
        handles = load_synthetic_day(core, fixture)
        today_before = core.today(
            owner_id=fixture["owner_id"],
            space_id=fixture["space_id"],
            local_date=fixture["day"]["local_date"],
            timezone_name=fixture["day"]["timezone"],
        )
        recall_before = core.recall(
            owner_id=fixture["owner_id"],
            space_id=fixture["space_id"],
            date_from=fixture["day"]["local_date"],
            date_to=fixture["day"]["local_date"],
            keyword="river",
        )
        walk_source = handles["records"]["river_walk"]["capture"]["source_object_id"]
        deletion = core.delete_source(
            walk_source,
            idempotency_key="demo-delete-river-walk-001",
        )
        after_delete_ids = [
            event["event_id"]
            for event in core.today(
                owner_id=fixture["owner_id"],
                space_id=fixture["space_id"],
                local_date=fixture["day"]["local_date"],
                timezone_name=fixture["day"]["timezone"],
            )["events"]
        ]
        rebuild = core.rebuild()
        today_after_rebuild = core.today(
            owner_id=fixture["owner_id"],
            space_id=fixture["space_id"],
            local_date=fixture["day"]["local_date"],
            timezone_name=fixture["day"]["timezone"],
        )
        after_rebuild_ids = [event["event_id"] for event in today_after_rebuild["events"]]
        output = {
            "oracle": "non-production-reference-only",
            "fixture": fixture["description"],
            "captured_source_count": core.table_count("source_objects"),
            "event_statuses_before_delete": sorted(
                {event["fact_status"] for event in today_before["events"]}
            ),
            "evidence_statuses_before_delete": sorted(
                {
                    evidence["status"]
                    for event in today_before["events"]
                    for evidence in event["evidence_detail"]
                }
            ),
            "day_range_state": today_before["range_state"],
            "day_partial_reasons": today_before["ledger"]["partial_reasons"],
            "recall_result_ids": [item["object_id"] for item in recall_before["results"]],
            "recall_has_day_and_event_evidence": bool(
                recall_before["results"]
                and recall_before["results"][0]["day_evidence"]
                and recall_before["results"][0]["event"]["evidence_detail"]
            ),
            "deletion": deletion,
            "rebuild": rebuild,
            "active_event_ids_stable_after_rebuild": after_delete_ids == after_rebuild_ids,
            "deleted_keyword_recall_state": core.recall(
                owner_id=fixture["owner_id"],
                space_id=fixture["space_id"],
                keyword="river",
            )["range_state"],
        }
        print(json.dumps(output, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
