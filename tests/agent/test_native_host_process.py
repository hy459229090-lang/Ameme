from __future__ import annotations

import json
import os
from pathlib import Path
import sqlite3
from tempfile import TemporaryDirectory
import time
import unittest

from support import Harness, capture_arguments

from ameme_mcp_mock import AmemeMock, MockError
from ameme_mcp_mock.event_store import EventNodeNotVisible, EventNodeScope
from ameme_mcp_mock.event_store import EventNodeStoreError
from ameme_mcp_mock.native_host_store import CoreOracleHostReferenceStore
from server import MCPServer


def _table_count(harness: Harness, table: str) -> int:
    database = harness.root / "core-oracle-host" / "core.sqlite3"
    connection = sqlite3.connect(database)
    try:
        row = connection.execute(f"SELECT COUNT(*) FROM {table}").fetchone()
    finally:
        connection.close()
    assert row is not None
    return int(row[0])


class NativeHostProcessTests(unittest.TestCase):
    def test_hung_malformed_and_crashed_hosts_fail_bounded_and_are_reaped(self) -> None:
        repository_fixtures = (
            Path(__file__).resolve().parents[1]
            / "fixtures"
            / "agent"
        )
        cases = (
            "host_malformed_response.py",
            "host_crashes.py",
        )
        with TemporaryDirectory() as directory:
            harness_root = Path(directory)
            for index, fixture in enumerate(cases, 1):
                with self.subTest(fixture=fixture):
                    started = time.monotonic()
                    with self.assertRaises(EventNodeStoreError):
                        CoreOracleHostReferenceStore(
                            harness_root / f"control-{index}.json",
                            harness_root / f"host-{index}",
                            host_script=repository_fixtures / fixture,
                            response_timeout_seconds=0.5,
                        )
                    self.assertLess(time.monotonic() - started, 3.0)

            store = CoreOracleHostReferenceStore(
                harness_root / "control-hung.json",
                harness_root / "host-hung",
                host_script=repository_fixtures / "host_hangs_after_ping.py",
                response_timeout_seconds=0.2,
            )
            child_pid = store.host_pid
            started = time.monotonic()
            with self.assertRaises(EventNodeStoreError):
                store.queued_count()
            self.assertLess(time.monotonic() - started, 3.0)
            self.assertFalse(store.host_running)
            self.assertEqual(child_pid, store.host_pid)
            store.close()

            mcp_store = CoreOracleHostReferenceStore(
                harness_root / "control-mcp-hung.json",
                harness_root / "host-mcp-hung",
                host_script=repository_fixtures / "host_hangs_after_ping.py",
                response_timeout_seconds=0.2,
            )
            try:
                result = MCPServer(AmemeMock(mcp_store)).handle(
                    {
                        "jsonrpc": "2.0",
                        "id": 1,
                        "method": "tools/call",
                        "params": {
                            "name": "status",
                            "arguments": {"caller_id": "agent_synthetic"},
                        },
                    }
                )
                assert result is not None
                problem = result["result"]["structuredContent"]
                self.assertEqual("LOCAL_NODE_UNAVAILABLE", problem["code"])
                self.assertTrue(problem["retryable"])
            finally:
                mcp_store.close()

    def test_real_process_boundary_exact_scope_idempotency_and_restart(self) -> None:
        harness = Harness(backend="core_host", seed=False, offline=True)
        try:
            self.assertNotEqual(os.getpid(), harness.store.host_pid)
            grant = harness.exact_grant()
            baseline = _table_count(harness, "source_objects")

            with self.assertRaises(MockError) as denied:
                harness.mock.call(
                    "capture",
                    capture_arguments(
                        grant["grant_id"],
                        space="space_personal",
                        idempotency_key="host-cross-space-01",
                    ),
                )
            self.assertEqual("SPACE_DENIED", denied.exception.code)
            self.assertEqual(baseline, _table_count(harness, "source_objects"))

            scope = EventNodeScope(
                owner_id="user_synthetic",
                caller_id="agent_codex_test",
                grant_id=grant["grant_id"],
                purpose="autonomous_memory",
                spaces=("space_work",),
                memory_types=("event",),
            )
            with self.assertRaises(EventNodeNotVisible):
                harness.store.visible_events(
                    scope=scope,
                    spaces=["space_personal"],
                    memory_types=["event"],
                    query=None,
                    allow_high_risk=False,
                    start_at=None,
                    end_at=None,
                )
            with self.assertRaises(EventNodeNotVisible):
                harness.store.visible_events(
                    scope=scope,
                    spaces=["space_work"],
                    memory_types=["revision"],
                    query=None,
                    allow_high_risk=False,
                    start_at=None,
                    end_at=None,
                )

            key = "RAW_HOST_IDEMPOTENCY_CANARY_91b8cafe"
            arguments = capture_arguments(
                grant["grant_id"],
                content="Synthetic host process event content canary.",
                idempotency_key=key,
            )
            first = harness.mock.call("capture", arguments)
            replay = harness.mock.call("capture", arguments)
            self.assertEqual(first["event_id"], replay["event_id"])
            self.assertTrue(replay["replayed"])
            self.assertEqual("queued", first["delivery_state"])
            self.assertEqual(1, _table_count(harness, "event_revisions"))

            with self.assertRaises(MockError) as changed:
                harness.mock.call(
                    "capture",
                    {**arguments, "content": "Changed synthetic host payload."},
                )
            self.assertEqual("IDEMPOTENCY_CONFLICT", changed.exception.code)
            self.assertEqual(1, _table_count(harness, "event_revisions"))

            first_pid = harness.store.host_pid
            harness.restart()
            self.assertNotEqual(first_pid, harness.store.host_pid)
            recalled = harness.mock.call(
                "recall",
                {
                    "caller_id": "agent_codex_test",
                    "grant_id": grant["grant_id"],
                    "purpose": "autonomous_memory",
                    "spaces": ["space_work"],
                    "memory_types": ["event"],
                    "invocation": "autonomous",
                },
            )
            self.assertEqual(
                [first["event_id"]],
                [item["object_id"] for item in recalled["results"]],
            )
            self.assertEqual(1, harness.store.queued_count())
        finally:
            harness.close()

    def test_event_delete_undo_and_revision_undo_survive_host_restart(self) -> None:
        harness = Harness(backend="core_host", seed=False)
        try:
            event_grant = harness.exact_grant()
            original = harness.mock.call(
                "capture",
                capture_arguments(
                    event_grant["grant_id"],
                    content="Synthetic original host description.",
                    idempotency_key="host-original-0001",
                ),
            )
            revision_grant = harness.exact_grant(memory_types=["revision"])
            revised = harness.mock.call(
                "capture",
                capture_arguments(
                    revision_grant["grant_id"],
                    memory_type="revision",
                    target_event_id=original["event_id"],
                    content="Synthetic revised host description.",
                    evidence_kind="user_statement",
                    idempotency_key="host-revision-0001",
                ),
            )
            harness.restart()
            undone_revision = harness.mock.call(
                "capture",
                {
                    "operation": "undo",
                    "caller_id": "agent_codex_test",
                    "grant_id": revision_grant["grant_id"],
                    "purpose": "autonomous_memory",
                    "space": "space_work",
                    "memory_type": "revision",
                    "undo_token": revised["undo_token"],
                },
            )
            self.assertEqual("undone", undone_revision["state"])
            harness.restart()
            original_scope = EventNodeScope(
                owner_id="user_synthetic",
                caller_id="agent_codex_test",
                grant_id=event_grant["grant_id"],
                purpose="autonomous_memory",
                spaces=("space_work",),
                memory_types=("event",),
            )
            restored = harness.store.get_event(
                original["event_id"],
                scope=original_scope,
                space="space_work",
                memory_type="event",
            )
            self.assertIsNotNone(restored)
            assert restored is not None
            self.assertEqual(
                "Synthetic original host description.", restored["description"]
            )

            removable = harness.mock.call(
                "capture",
                capture_arguments(
                    event_grant["grant_id"],
                    content="Synthetic host event removed through undo.",
                    idempotency_key="host-removable-0001",
                ),
            )
            undone_event = harness.mock.call(
                "capture",
                {
                    "operation": "undo",
                    "caller_id": "agent_codex_test",
                    "grant_id": event_grant["grant_id"],
                    "purpose": "autonomous_memory",
                    "space": "space_work",
                    "memory_type": "event",
                    "undo_token": removable["undo_token"],
                },
            )
            self.assertEqual("undone", undone_event["state"])
            harness.restart()
            self.assertIsNone(
                harness.store.get_event(
                    removable["event_id"],
                    scope=original_scope,
                    space="space_work",
                    memory_type="event",
                )
            )
        finally:
            harness.close()

    def test_both_control_planes_and_database_exclude_content_and_raw_keys(self) -> None:
        harness = Harness(backend="core_host", seed=False, offline=True)
        try:
            grant = harness.exact_grant()
            content = "SYNTHETIC_HOST_CONTENT_CANARY_47adfd71"
            capture_key = "RAW_HOST_CAPTURE_KEY_CANARY_47adfd71"
            feedback_key = "RAW_HOST_FEEDBACK_KEY_CANARY_47adfd71"
            captured = harness.mock.call(
                "capture",
                capture_arguments(
                    grant["grant_id"],
                    content=content,
                    idempotency_key=capture_key,
                ),
            )
            harness.mock.call(
                "feedback",
                {
                    "caller_id": "agent_codex_test",
                    "grant_id": grant["grant_id"],
                    "purpose": "autonomous_memory",
                    "space": "space_work",
                    "memory_type": "event",
                    "target_id": captured["event_id"],
                    "action": "context_useful",
                    "idempotency_key": feedback_key,
                },
            )
            harness.store.save()
            for control_path in (
                harness.root / "mcp-control.json",
                harness.root / "core-oracle-host" / "control.json",
            ):
                control = control_path.read_text(encoding="utf-8")
                self.assertNotIn(content, control)
                self.assertNotIn(capture_key, control)
                self.assertNotIn(feedback_key, control)
                json.loads(control)

            for database_path in (harness.root / "core-oracle-host").glob(
                "core.sqlite3*"
            ):
                database_bytes = database_path.read_bytes()
                self.assertNotIn(capture_key.encode("utf-8"), database_bytes)
                self.assertNotIn(feedback_key.encode("utf-8"), database_bytes)
            self.assertEqual(2, harness.store.queued_count())
        finally:
            harness.close()


if __name__ == "__main__":
    unittest.main()
