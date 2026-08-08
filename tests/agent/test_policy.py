from __future__ import annotations

import json
import unittest

from support import Harness, capture_arguments

from ameme_mcp_mock import MockError


class PolicyTests(unittest.TestCase):
    def setUp(self) -> None:
        self.harness = Harness()
        self.grant = self.harness.exact_grant()

    def tearDown(self) -> None:
        self.harness.close()

    def assert_code(self, expected: str, tool: str, arguments: dict) -> None:
        with self.assertRaises(MockError) as raised:
            self.harness.mock.call(tool, arguments)
        self.assertEqual(expected, raised.exception.code)

    def test_grant_intersection_caller_purpose_space_type_and_expiry(self) -> None:
        base = capture_arguments(self.grant["grant_id"])
        for field, value, code in (
            ("caller_id", "agent_other", "AUTH_REQUIRED"),
            ("purpose", "different_purpose", "PURPOSE_DENIED"),
            ("space", "space_personal", "SPACE_DENIED"),
            ("memory_type", "revision", "DATA_TYPE_DENIED"),
        ):
            changed = {**base, field: value, "idempotency_key": f"changed-{field}-01"}
            self.assert_code(code, "capture", changed)

        grant = self.harness.store.state["grants"][self.grant["grant_id"]]
        grant["expires_at"] = "2026-07-14T03:59:59Z"
        self.assert_code("GRANT_EXPIRED", "capture", {**base, "idempotency_key": "expired-key-001"})

    def test_only_exact_autonomous_grant_can_read_implicitly(self) -> None:
        broad = self.harness.exact_grant(memory_types=["event", "revision"])
        self.assert_code(
            "CONSENT_REQUIRED",
            "get_context",
            {
                "caller_id": "agent_codex_test",
                "grant_id": broad["grant_id"],
                "purpose": "autonomous_memory",
                "space": "space_work",
                "memory_types": ["event"],
                "invocation": "autonomous",
            },
        )

    def test_user_statement_and_inference_labels_are_preserved(self) -> None:
        asserted = self.harness.mock.call(
            "capture",
            capture_arguments(
                self.grant["grant_id"],
                evidence_kind="user_statement",
                idempotency_key="asserted-key-001",
            ),
        )
        inferred = self.harness.mock.call(
            "capture",
            capture_arguments(
                self.grant["grant_id"],
                evidence_kind="inference",
                idempotency_key="inferred-key-001",
            ),
        )
        self.assertEqual("user_asserted", asserted["evidence_state"])
        self.assertEqual("inferred", inferred["evidence_state"])
        self.assertEqual("user_asserted", self.harness.store.state["events"][asserted["event_id"]]["fact_status"])
        self.assertEqual("low_confidence_candidate", self.harness.store.state["events"][inferred["event_id"]]["fact_status"])

    def test_cross_space_raw_restricted_and_delete_are_blocked(self) -> None:
        self.assert_code(
            "SPACE_DENIED",
            "recall",
            {
                "caller_id": "agent_codex_test",
                "grant_id": self.grant["grant_id"],
                "purpose": "autonomous_memory",
                "spaces": ["space_personal"],
                "memory_types": ["event"],
            },
        )
        self.assert_code(
            "POLICY_BLOCKED",
            "get_context",
            {
                "caller_id": "agent_codex_test",
                "grant_id": self.grant["grant_id"],
                "purpose": "autonomous_memory",
                "space": "space_work",
                "memory_types": ["event"],
                "data_class": "raw",
            },
        )
        self.assert_code(
            "POLICY_BLOCKED",
            "capture",
            capture_arguments(
                self.grant["grant_id"],
                sensitivity="restricted",
                idempotency_key="restricted-key-1",
            ),
        )
        self.assert_code(
            "DELETION_CONFIRMATION_REQUIRED",
            "capture",
            capture_arguments(
                self.grant["grant_id"],
                requested_action="delete",
                idempotency_key="delete-key-0001",
            ),
        )

    def test_scope_expansion_requires_confirmation(self) -> None:
        self.assert_code(
            "CONSENT_REQUIRED",
            "get_context",
            {
                "caller_id": "agent_codex_test",
                "grant_id": self.grant["grant_id"],
                "purpose": "autonomous_memory",
                "space": "space_work",
                "memory_types": ["event"],
                "time_window_days": 365,
            },
        )

    def test_activity_is_content_free(self) -> None:
        secret_text = "Synthetic body that must not appear in activity records."
        self.harness.mock.call(
            "capture",
            capture_arguments(self.grant["grant_id"], content=secret_text, idempotency_key="activity-key-001"),
        )
        activity_json = json.dumps(self.harness.store.state["activity"], ensure_ascii=False)
        self.assertNotIn(secret_text, activity_json)
        self.assertNotIn("query", activity_json.lower())

    def test_agent_event_write_does_not_auto_confirm_sensitive_long_term_memory(self) -> None:
        for index, memory_type in enumerate(
            ("preference", "relationship", "health", "financial", "major_decision"),
            start=1,
        ):
            result = self.harness.mock.call(
                "capture",
                capture_arguments(
                    self.grant["grant_id"],
                    evidence_kind="direct_evidence",
                    long_term_memory_type=memory_type,
                    idempotency_key=f"sensitive-memory-{index:03d}",
                ),
            )
            with self.subTest(memory_type=memory_type):
                self.assertEqual(
                    "candidate_user_confirmation_required",
                    result["long_term_memory_state"],
                )
                self.assertEqual("durable", result["persistence_state"])
                self.assertEqual("event", result["object_type"])

    def test_inference_never_auto_promotes_to_long_term_fact(self) -> None:
        result = self.harness.mock.call(
            "capture",
            capture_arguments(
                self.grant["grant_id"],
                evidence_kind="inference",
                long_term_memory_type="fact",
                idempotency_key="inferred-long-term-fact-001",
            ),
        )
        self.assertEqual(
            "candidate_user_confirmation_required",
            result["long_term_memory_state"],
        )
        self.assertEqual("inferred", result["evidence_state"])
        self.assertEqual(
            "low_confidence_candidate",
            self.harness.store.state["events"][result["event_id"]]["fact_status"],
        )

    def test_verified_ordinary_fact_is_only_eligible_for_memory_compiler(self) -> None:
        result = self.harness.mock.call(
            "capture",
            capture_arguments(
                self.grant["grant_id"],
                evidence_kind="direct_evidence",
                long_term_memory_type="fact",
                idempotency_key="verified-long-term-fact-001",
            ),
        )
        self.assertEqual(
            "eligible_for_memory_compiler",
            result["long_term_memory_state"],
        )
        self.assertNotEqual(
            "confirmed_long_term_memory",
            result["long_term_memory_state"],
        )

    def test_unknown_long_term_memory_type_is_rejected(self) -> None:
        self.assert_code(
            "INVALID_ARGUMENT",
            "capture",
            capture_arguments(
                self.grant["grant_id"],
                long_term_memory_type="personality_profile",
                idempotency_key="unknown-long-term-type-001",
            ),
        )


if __name__ == "__main__":
    unittest.main()
