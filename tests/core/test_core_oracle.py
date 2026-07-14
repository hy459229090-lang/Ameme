from __future__ import annotations

from datetime import datetime, timedelta
import json
from pathlib import Path
import sqlite3
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "packages" / "core-reference"))

from ameme_core_reference import (  # noqa: E402
    CoreOracle,
    IdempotencyConflict,
    InvariantViolation,
    NotFound,
    RevisionConflict,
    load_synthetic_day,
)


class StepClock:
    def __init__(self) -> None:
        self.current = datetime.fromisoformat("2026-07-14T12:00:00+00:00")

    def __call__(self) -> str:
        value = self.current.isoformat(timespec="seconds")
        self.current += timedelta(seconds=1)
        return value


class CoreOracleTest(unittest.TestCase):
    def setUp(self) -> None:
        self.fixture = json.loads(
            (ROOT / "tests" / "fixtures" / "core" / "synthetic_core_day.json").read_text(
                encoding="utf-8"
            )
        )
        self.tempdir = tempfile.TemporaryDirectory()
        self.database = Path(self.tempdir.name) / "oracle.sqlite3"
        self.core = CoreOracle(self.database, now=StepClock())

    def tearDown(self) -> None:
        self.core.close()
        self.tempdir.cleanup()

    def load_day(self) -> dict:
        return load_synthetic_day(self.core, self.fixture)

    def today(self) -> dict:
        return self.core.today(
            owner_id=self.fixture["owner_id"],
            space_id=self.fixture["space_id"],
            local_date=self.fixture["day"]["local_date"],
            timezone_name=self.fixture["day"]["timezone"],
        )

    def test_full_chain_preserves_states_evidence_and_partial_range(self) -> None:
        self.load_day()
        today = self.today()
        self.assertEqual(today["range_state"], "partial")
        self.assertEqual(today["ledger"]["partial_reasons"], ["device_not_synced"])
        self.assertEqual(
            {event["fact_status"] for event in today["events"]},
            {"planned", "high_confidence_inference", "user_asserted"},
        )
        self.assertIn(
            "inferred",
            {
                evidence["status"]
                for event in today["events"]
                for evidence in event["evidence_detail"]
            },
        )
        self.assertEqual(len(today["episodes"]), 1)
        for event in today["events"]:
            self.assertTrue(event["evidence_detail"])
            self.assertTrue(event["lineage"])

    def test_keyword_and_date_recall_return_day_and_event_evidence(self) -> None:
        handles = self.load_day()
        recall = self.core.recall(
            owner_id=self.fixture["owner_id"],
            space_id=self.fixture["space_id"],
            date_from="2026-07-14",
            date_to="2026-07-14",
            keyword="hiking",
        )
        self.assertEqual(recall["range_state"], "partial")
        self.assertEqual(
            recall["results"][0]["object_id"],
            handles["records"]["inferred_research"]["event"]["event_id"],
        )
        self.assertEqual(recall["results"][0]["day_evidence"]["coverage_state"], "partial")
        self.assertTrue(recall["results"][0]["event"]["evidence_detail"])

    def test_duplicate_write_replays_and_changed_payload_conflicts(self) -> None:
        self.core.create_contract(
            self.fixture["contract"], idempotency_key="duplicate-contract-001"
        )
        source = dict(self.fixture["records"][0]["source"])
        source.update(
            {
                "contract_id": self.fixture["contract"]["contract_id"],
                "owner_id": self.fixture["owner_id"],
                "space_id": self.fixture["space_id"],
                "device_id": self.fixture["contract"]["device_id"],
                "sync_mode": self.fixture["contract"]["sync_mode"],
                "retention": {
                    "retention_class": "structured_active",
                    "deletion_state": "active",
                },
            }
        )
        first = self.core.capture_source(source, idempotency_key="duplicate-capture-001")
        second = self.core.capture_source(source, idempotency_key="duplicate-capture-001")
        self.assertEqual(first, second)
        self.assertEqual(self.core.table_count("source_objects"), 1)
        changed = dict(source)
        changed["content_hash"] = "sha256_synthetic_changed_000001"
        with self.assertRaises(IdempotencyConflict):
            self.core.capture_source(changed, idempotency_key="duplicate-capture-001")

    def test_source_type_must_match_acquisition_contract(self) -> None:
        self.core.create_contract(
            self.fixture["contract"], idempotency_key="source-contract-001"
        )
        source = dict(self.fixture["records"][0]["source"])
        source.update(
            {
                "contract_id": self.fixture["contract"]["contract_id"],
                "owner_id": self.fixture["owner_id"],
                "space_id": self.fixture["space_id"],
                "device_id": self.fixture["contract"]["device_id"],
                "source_type": "text",
                "sync_mode": self.fixture["contract"]["sync_mode"],
                "retention": {
                    "retention_class": "structured_active",
                    "deletion_state": "active",
                },
            }
        )
        with self.assertRaises(InvariantViolation):
            self.core.capture_source(source, idempotency_key="wrong-source-type-001")

    def test_out_of_order_revision_is_rejected(self) -> None:
        handles = self.load_day()
        event_id = handles["records"]["river_walk"]["event"]["event_id"]
        self.core.append_event_revision(
            event_id,
            base_revision=1,
            changes={"title": "Updated synthetic title"},
            actor="user",
            reason="user_edit",
            idempotency_key="revision-update-001",
        )
        with self.assertRaises(RevisionConflict):
            self.core.append_event_revision(
                event_id,
                base_revision=1,
                changes={"title": "Stale synthetic title"},
                actor="user",
                reason="user_edit",
                idempotency_key="revision-stale-001",
            )
        self.assertEqual(self.core.table_count("event_revisions"), 4)

    def test_conflict_and_undo_are_new_revisions(self) -> None:
        handles = self.load_day()
        event_id = handles["records"]["river_walk"]["event"]["event_id"]
        updated = self.core.append_event_revision(
            event_id,
            base_revision=1,
            changes={"title": "Edited river walk"},
            actor="user",
            reason="user_edit",
            idempotency_key="conflict-base-update-001",
        )
        conflict = self.core.record_conflict(
            event_id,
            competing_base_revision=1,
            competing_changes={"title": "Competing stale edit"},
            idempotency_key="conflict-record-001",
        )
        self.assertEqual(conflict["fact_status"], "conflict")
        restored = self.core.undo_event(
            event_id,
            base_revision=conflict["revision"],
            restore_revision=updated["revision"],
            idempotency_key="undo-event-001",
        )
        self.assertEqual(restored["revision"], 4)
        snapshots = self.core.event_revision_snapshots(event_id)
        self.assertEqual([item["revision"] for item in snapshots], [1, 2, 3, 4])
        self.assertEqual(snapshots[2]["fact_status"], "conflict")
        self.assertEqual(snapshots[3]["title"], "Edited river walk")

    def test_undo_restores_target_revision_evidence_snapshot(self) -> None:
        handles = self.load_day()
        event_id = handles["records"]["river_walk"]["event"]["event_id"]
        original = self.core.event_revision_snapshots(event_id)[0]
        replacement_observation_id = handles["records"]["inferred_research"][
            "observation"
        ]["observation_id"]
        replacement_source_id = handles["records"]["inferred_research"]["capture"][
            "source_object_id"
        ]
        changed = self.core.append_event_revision(
            event_id,
            base_revision=1,
            changes={
                "title": "Temporary evidence replacement",
                "fact_status": "high_confidence_inference",
            },
            actor="user",
            reason="user_edit",
            evidences=[
                {
                    "field": "action",
                    "observation_ids": [replacement_observation_id],
                    "confidence": 0.72,
                    "status": "inferred",
                }
            ],
            idempotency_key="undo-evidence-change-001",
        )
        changed_snapshot = self.core.event_revision_snapshots(event_id)[-1]
        self.assertEqual(changed_snapshot["source_object_ids"], [replacement_source_id])

        restored = self.core.undo_event(
            event_id,
            base_revision=changed["revision"],
            restore_revision=1,
            idempotency_key="undo-evidence-restore-001",
        )

        self.assertEqual(restored["revision"], 3)
        snapshots = self.core.event_revision_snapshots(event_id)
        self.assertEqual([item["revision"] for item in snapshots], [1, 2, 3])
        self.assertEqual(snapshots[-1]["field_evidence"], original["field_evidence"])
        self.assertEqual(snapshots[-1]["source_object_ids"], original["source_object_ids"])
        compensation = self.core.connection.execute(
            "SELECT actor, reason, changes_json FROM event_revisions "
            "WHERE event_id = ? AND revision = 3",
            (event_id,),
        ).fetchone()
        self.assertEqual(compensation["actor"], "user")
        self.assertEqual(compensation["reason"], "user_edit")
        self.assertEqual(json.loads(compensation["changes_json"])["undo_of_revision"], 1)
        current = next(
            event for event in self.today()["events"] if event["event_id"] == event_id
        )
        self.assertEqual(
            {item["source_object_id"] for item in current["evidence_detail"]},
            set(original["source_object_ids"]),
        )

    def test_locator_degradation_is_traceable(self) -> None:
        handles = self.load_day()
        source_id = handles["records"]["inferred_research"]["capture"]["source_object_id"]
        self.core.degrade_source_locator(
            source_id,
            state="missing",
            reason="synthetic_reference_removed",
            idempotency_key="locator-missing-001",
        )
        history = self.core.locator_history(source_id)
        self.assertEqual([item["state"] for item in history], ["available", "missing"])
        self.assertEqual(history[-1]["reason"], "synthetic_reference_removed")

    def test_delete_tombstone_and_rebuild_do_not_resurrect(self) -> None:
        handles = self.load_day()
        source_id = handles["records"]["river_walk"]["capture"]["source_object_id"]
        event_id = handles["records"]["river_walk"]["event"]["event_id"]
        deletion = self.core.delete_source(
            source_id, idempotency_key="delete-river-source-001"
        )
        self.assertEqual(deletion["state"], "completed")
        self.assertNotIn(event_id, [event["event_id"] for event in self.today()["events"]])
        self.assertEqual(
            self.core.recall(
                owner_id=self.fixture["owner_id"],
                space_id=self.fixture["space_id"],
                keyword="river",
            )["range_state"],
            "empty",
        )
        active_before = [event["event_id"] for event in self.today()["events"]]
        rebuilt = self.core.rebuild()
        active_after = [event["event_id"] for event in self.today()["events"]]
        self.assertEqual(active_before, active_after)
        self.assertGreaterEqual(rebuilt["tombstones_applied"], 3)
        with self.assertRaises(sqlite3.IntegrityError):
            self.core.connection.execute(
                "UPDATE event_revisions SET reason = 'illegal' WHERE event_id = ?", (event_id,)
            )

    def test_delete_one_source_recomputes_and_preserves_multi_source_event(self) -> None:
        handles = self.load_day()
        event_id = handles["records"]["river_walk"]["event"]["event_id"]
        deleted_source_id = handles["records"]["river_walk"]["capture"][
            "source_object_id"
        ]
        deleted_observation_id = handles["records"]["river_walk"]["observation"][
            "observation_id"
        ]
        remaining_source_id = handles["records"]["inferred_research"]["capture"][
            "source_object_id"
        ]
        remaining_observation_id = handles["records"]["inferred_research"][
            "observation"
        ]["observation_id"]
        self.core.append_event_revision(
            event_id,
            base_revision=1,
            changes={"title": "Synthetic event with two independent sources"},
            actor="system",
            reason="source_update",
            evidences=[
                {
                    "field": "action",
                    "observation_ids": [
                        deleted_observation_id,
                        remaining_observation_id,
                    ],
                    "confidence": 1.0,
                    "status": "user_asserted",
                },
                {
                    "field": "emotion",
                    "observation_ids": [deleted_observation_id],
                    "confidence": 1.0,
                    "status": "user_asserted",
                },
            ],
            idempotency_key="multi-source-event-001",
        )

        deletion = self.core.delete_source(
            deleted_source_id, idempotency_key="delete-one-of-multiple-sources-001"
        )

        self.assertEqual(deletion["state"], "completed")
        self.assertEqual(deletion["affected"]["retained_event_ids"], [event_id])
        self.assertEqual(deletion["affected"]["deleted_event_ids"], [])
        snapshots = self.core.event_revision_snapshots(event_id)
        self.assertEqual([item["revision"] for item in snapshots], [1, 2, 3])
        current = snapshots[-1]
        self.assertEqual(current["state"], "active")
        self.assertEqual(current["fact_status"], "high_confidence_inference")
        self.assertEqual(current["source_object_ids"], [remaining_source_id])
        self.assertEqual(
            current["field_evidence"],
            [
                {
                    "field": "action",
                    "observation_ids": [remaining_observation_id],
                    "confidence": 0.72,
                    "status": "inferred",
                }
            ],
        )
        self.assertNotEqual(
            current["title"], "Synthetic event with two independent sources"
        )
        revision_reason = self.core.connection.execute(
            "SELECT reason FROM event_revisions WHERE event_id = ? AND revision = 3",
            (event_id,),
        ).fetchone()["reason"]
        self.assertEqual(revision_reason, "deletion_recompute")
        self.assertIsNone(
            self.core.connection.execute(
                "SELECT 1 FROM tombstones WHERE target_type = 'event' AND target_id = ?",
                (event_id,),
            ).fetchone()
        )
        self.assertIn(event_id, [event["event_id"] for event in self.today()["events"]])
        self.assertEqual(self.today()["episodes"][0]["state"], "active")
        self.core.rebuild()
        rebuilt = next(
            event for event in self.today()["events"] if event["event_id"] == event_id
        )
        self.assertEqual(rebuilt["source_object_ids"], [remaining_source_id])

    def test_delete_is_idempotent_only_with_same_key(self) -> None:
        handles = self.load_day()
        source_id = handles["records"]["river_walk"]["capture"]["source_object_id"]
        first = self.core.delete_source(source_id, idempotency_key="delete-idempotent-001")
        second = self.core.delete_source(source_id, idempotency_key="delete-idempotent-001")
        self.assertEqual(first, second)
        self.assertEqual(self.core.table_count("deletion_jobs"), 1)
        with self.assertRaises(InvariantViolation):
            self.core.delete_source(source_id, idempotency_key="delete-another-key-001")

    def test_missing_evidence_and_unknown_observation_are_rejected(self) -> None:
        self.load_day()
        event = {
            "owner_id": self.fixture["owner_id"],
            "space_id": self.fixture["space_id"],
            "event_type": "activity",
            "time_range": {
                "start": "2026-07-14T21:00:00+08:00",
                "timezone": "Asia/Shanghai",
                "precision": "minute",
            },
            "title": "Unsupported event",
            "fact_status": "confirmed",
        }
        with self.assertRaises(InvariantViolation):
            self.core.accept_event(event, [], idempotency_key="no-evidence-event-001")
        with self.assertRaises(NotFound):
            self.core.accept_event(
                event,
                [
                    {
                        "field": "action",
                        "observation_ids": ["obs_missing_synthetic"],
                        "confidence": 1.0,
                        "status": "observed",
                    }
                ],
                idempotency_key="missing-observation-event-001",
            )

    def test_empty_day_and_no_match_express_insufficient_data(self) -> None:
        empty = self.core.today(
            owner_id=self.fixture["owner_id"],
            space_id=self.fixture["space_id"],
            local_date="2026-07-15",
            timezone_name="Asia/Shanghai",
        )
        self.assertEqual(empty["ledger"]["coverage_state"], "empty")
        self.assertEqual(empty["ledger"]["summary_state"], "insufficient")
        recall = self.core.recall(
            owner_id=self.fixture["owner_id"],
            space_id=self.fixture["space_id"],
            keyword="doesnotexist",
        )
        self.assertEqual(recall["range_state"], "empty")
        self.assertEqual(recall["results"], [])


if __name__ == "__main__":
    unittest.main()
