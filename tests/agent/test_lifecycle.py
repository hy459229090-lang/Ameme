from __future__ import annotations

import unittest

from support import Harness, capture_arguments

from ameme_mcp_mock import MockError


class LifecycleTests(unittest.TestCase):
    def setUp(self) -> None:
        self.harness = Harness()
        self.grant = self.harness.exact_grant()

    def tearDown(self) -> None:
        self.harness.close()

    def test_event_capture_undo_tombstones_only_the_created_event(self) -> None:
        result = self.harness.mock.call(
            "capture",
            capture_arguments(
                self.grant["grant_id"],
                simulate_offline=True,
                idempotency_key="queued-key-0001",
            ),
        )
        self.assertEqual("durable", result["persistence_state"])
        self.assertEqual("queued", result["delivery_state"])
        self.assertFalse(result["synced"])
        self.assertTrue(result["activity_visible"])
        undone = self.harness.mock.call(
            "capture",
            {
                "operation": "undo",
                "caller_id": "agent_codex_test",
                "grant_id": self.grant["grant_id"],
                "purpose": "autonomous_memory",
                "space": "space_work",
                "memory_type": "event",
                "undo_token": result["undo_token"],
            },
        )
        self.assertEqual("undone", undone["state"])
        undo_record = self.harness.store.state["undo"][result["undo_token"]]
        self.assertEqual("event", undo_record["created_object_type"])
        self.assertEqual(result["event_id"], undo_record["created_object_id"])
        self.assertEqual("event", undone["undone_object_type"])
        self.assertEqual(result["event_id"], undone["undone_object_id"])
        self.assertEqual("deleted", self.harness.store.state["events"][result["event_id"]]["state"])
        self.assertEqual("active", self.harness.store.state["events"]["evt_synthetic_architecture"]["state"])
        replay = self.harness.mock.call(
            "capture",
            {
                "operation": "undo",
                "caller_id": "agent_codex_test",
                "grant_id": self.grant["grant_id"],
                "purpose": "autonomous_memory",
                "space": "space_work",
                "memory_type": "event",
                "undo_token": result["undo_token"],
            },
        )
        self.assertTrue(replay["replayed"])
        self.assertEqual(result["event_id"], replay["undone_object_id"])

    def test_revision_capture_undo_appends_compensation_and_restores_snapshot(self) -> None:
        grant = self.harness.exact_grant(memory_types=["revision"])
        event_id = "evt_synthetic_architecture"
        before = dict(self.harness.store.state["events"][event_id])
        result = self.harness.mock.call(
            "capture",
            capture_arguments(
                grant["grant_id"],
                memory_type="revision",
                target_event_id=event_id,
                content="Synthetic replacement description.",
                evidence_kind="user_statement",
                idempotency_key="revision-undo-0001",
            ),
        )
        created_revision_id = result["event_revision_id"]
        undo_record = self.harness.store.state["undo"][result["undo_token"]]
        self.assertEqual("revision", undo_record["created_object_type"])
        self.assertEqual(created_revision_id, undo_record["created_object_id"])
        self.assertEqual(2, self.harness.store.state["events"][event_id]["revision"])

        undone = self.harness.mock.call(
            "capture",
            {
                "operation": "undo",
                "caller_id": "agent_codex_test",
                "grant_id": grant["grant_id"],
                "purpose": "autonomous_memory",
                "space": "space_work",
                "memory_type": "revision",
                "undo_token": result["undo_token"],
            },
        )

        event = self.harness.store.state["events"][event_id]
        compensation = self.harness.store.state["revisions"][undone["compensation_revision_id"]]
        self.assertEqual("revision", undone["undone_object_type"])
        self.assertEqual(created_revision_id, undone["undone_object_id"])
        self.assertEqual("active", event["state"])
        self.assertEqual(before["description"], event["description"])
        self.assertEqual(before["evidence_state"], event["evidence_state"])
        self.assertEqual(3, event["revision"])
        self.assertEqual(undone["compensation_revision_id"], event["revision_head_id"])
        self.assertIn(created_revision_id, self.harness.store.state["revisions"])
        self.assertEqual(created_revision_id, compensation["base_revision_id"])
        self.assertEqual(created_revision_id, compensation["changes"]["undo"]["compensates_revision_id"])
        self.assertEqual("user_edit", compensation["reason"])
        self.assertEqual("UNDONE", self.harness.store.state["activity"][-1]["result_code"])

    def test_context_token_budget_one_returns_no_oversized_item(self) -> None:
        pack = self.harness.mock.call(
            "get_context",
            {
                "caller_id": "agent_codex_test",
                "grant_id": self.grant["grant_id"],
                "purpose": "autonomous_memory",
                "space": "space_work",
                "memory_types": ["event"],
                "item_budget": 1,
                "token_budget": 1,
            },
        )
        self.assertEqual([], pack["items"])
        self.assertEqual(1, pack["item_budget"])
        self.assertLessEqual(pack["token_count_approx"], pack["token_budget"])
        self.assertEqual("partial", pack["state"])
        self.assertIn("token_budget_exhausted", pack["partial_reasons"])

    def test_context_item_budget_is_preserved_and_marks_partial(self) -> None:
        self.harness.mock.call(
            "capture",
            capture_arguments(
                self.grant["grant_id"],
                content="A second safe synthetic event.",
                idempotency_key="item-budget-0001",
            ),
        )
        pack = self.harness.mock.call(
            "get_context",
            {
                "caller_id": "agent_codex_test",
                "grant_id": self.grant["grant_id"],
                "purpose": "autonomous_memory",
                "space": "space_work",
                "memory_types": ["event"],
                "item_budget": 1,
                "token_budget": 2_000,
            },
        )
        self.assertEqual(1, pack["item_budget"])
        self.assertEqual(1, len(pack["items"]))
        self.assertEqual("partial", pack["state"])
        self.assertIn("item_budget_exhausted", pack["partial_reasons"])
        self.assertLessEqual(pack["token_count_approx"], pack["token_budget"])

    def test_context_exact_boundary_and_long_item_truncation_are_deterministic(self) -> None:
        common = {
            "caller_id": "agent_codex_test",
            "grant_id": self.grant["grant_id"],
            "purpose": "autonomous_memory",
            "space": "space_work",
            "memory_types": ["event"],
            "item_budget": 1,
        }
        full = self.harness.mock.call("get_context", {**common, "token_budget": 2_000})
        exact_budget = full["token_count_approx"]
        boundary = self.harness.mock.call("get_context", {**common, "token_budget": exact_budget})
        self.assertEqual(full["items"], boundary["items"])
        self.assertEqual(exact_budget, boundary["token_count_approx"])
        self.assertNotIn("token_budget_exhausted", boundary["partial_reasons"])

        event = self.harness.store.state["events"]["evt_synthetic_architecture"]
        event["description"] = "x" * 4_000
        event["updated_at"] = "2026-07-14T03:59:59Z"
        limited = self.harness.mock.call("get_context", {**common, "token_budget": 100})
        self.assertEqual(1, len(limited["items"]))
        self.assertTrue(limited["items"][0]["content_truncated"])
        self.assertLess(len(limited["items"][0]["content"]), 4_000)
        self.assertLessEqual(limited["token_count_approx"], limited["token_budget"])
        self.assertIn("token_budget_exhausted", limited["partial_reasons"])

    def test_capture_replay_is_idempotent_and_changed_payload_conflicts(self) -> None:
        arguments = capture_arguments(self.grant["grant_id"], idempotency_key="replay-key-0001")
        first = self.harness.mock.call("capture", arguments)
        replay = self.harness.mock.call("capture", arguments)
        self.assertEqual(first["event_id"], replay["event_id"])
        self.assertTrue(replay["replayed"])
        with self.assertRaises(MockError) as raised:
            self.harness.mock.call("capture", {**arguments, "content": "Changed synthetic payload"})
        self.assertEqual("IDEMPOTENCY_CONFLICT", raised.exception.code)

    def test_pair_challenge_replay_is_rejected(self) -> None:
        pending = self.harness.mock.call(
            "pair",
            {
                "action": "begin",
                "caller_id": "agent_replay_test",
                "purposes": ["autonomous_memory"],
                "spaces": ["space_work"],
                "data_types": ["event"],
                "expires_at": "2026-08-13T04:00:00Z",
            },
        )
        approval = {
            "action": "approve",
            "caller_id": "agent_replay_test",
            "challenge_id": pending["challenge_id"],
            "confirmation": {"confirmed": True, "terms_digest": pending["terms_digest"]},
        }
        self.harness.mock.call("pair", approval)
        with self.assertRaises(MockError) as raised:
            self.harness.mock.call("pair", approval)
        self.assertEqual("PAIRING_REPLAY", raised.exception.code)

    def test_revoke_blocks_new_access_and_expires_context_pack(self) -> None:
        pack = self.harness.mock.call(
            "get_context",
            {
                "caller_id": "agent_codex_test",
                "grant_id": self.grant["grant_id"],
                "purpose": "autonomous_memory",
                "space": "space_work",
                "memory_types": ["event"],
            },
        )
        revoked = self.harness.mock.call(
            "pair",
            {
                "action": "revoke",
                "caller_id": "agent_codex_test",
                "grant_id": self.grant["grant_id"],
                "confirmation": {"confirmed": True},
            },
        )
        self.assertTrue(revoked["new_access_blocked"])
        self.assertEqual("expired", self.harness.store.state["context_packs"][pack["context_pack_id"]]["state"])
        with self.assertRaises(MockError) as raised:
            self.harness.mock.call(
                "recall",
                {
                    "caller_id": "agent_codex_test",
                    "grant_id": self.grant["grant_id"],
                    "purpose": "autonomous_memory",
                    "spaces": ["space_work"],
                    "memory_types": ["event"],
                },
            )
        self.assertEqual("GRANT_REVOKED", raised.exception.code)

    def test_recall_feedback_status_and_revision_paths(self) -> None:
        recall = self.harness.mock.call(
            "recall",
            {
                "caller_id": "agent_codex_test",
                "grant_id": self.grant["grant_id"],
                "purpose": "autonomous_memory",
                "spaces": ["space_work"],
                "memory_types": ["event"],
                "invocation": "autonomous",
            },
        )
        self.assertEqual("partial", recall["range_state"])
        self.assertIn("policy_filtered", recall["partial_reasons"])

        feedback = self.harness.mock.call(
            "feedback",
            {
                "caller_id": "agent_codex_test",
                "grant_id": self.grant["grant_id"],
                "purpose": "autonomous_memory",
                "space": "space_work",
                "memory_type": "event",
                "target_id": "evt_synthetic_architecture",
                "action": "context_useful",
                "idempotency_key": "feedback-key-001",
            },
        )
        self.assertEqual("accepted", feedback["state"])
        self.assertEqual("durable", feedback["persistence_state"])

        revision_grant = self.harness.exact_grant(memory_types=["revision"])
        corrected = self.harness.mock.call(
            "feedback",
            {
                "caller_id": "agent_codex_test",
                "grant_id": revision_grant["grant_id"],
                "purpose": "autonomous_memory",
                "space": "space_work",
                "memory_type": "revision",
                "target_id": "evt_synthetic_architecture",
                "action": "correct",
                "user_statement": "Synthetic correction supplied by the user.",
                "idempotency_key": "correct-key-0001",
            },
        )
        self.assertEqual("revision", corrected["object_type"])

        status = self.harness.mock.call(
            "status",
            {"caller_id": "agent_codex_test", "grant_id": revision_grant["grant_id"]},
        )
        self.assertEqual("ready", status["connection_state"])
        self.assertEqual(1, len(status["grants"]))
        self.assertIn("recent_activity", status)


if __name__ == "__main__":
    unittest.main()
