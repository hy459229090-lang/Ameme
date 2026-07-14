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

    def test_capture_is_durable_queueable_visible_and_undoable(self) -> None:
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
        self.assertEqual("deleted", self.harness.store.state["events"][result["event_id"]]["state"])

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
