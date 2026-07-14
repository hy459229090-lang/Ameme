from __future__ import annotations

from dataclasses import FrozenInstanceError, replace
import json
from pathlib import Path
import sys
import unittest


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "packages" / "sync-protocol" / "src"))

from ameme_sync_protocol import DeterministicSimulator, Envelope, Outcome  # noqa: E402
from ameme_sync_protocol.scenarios import (  # noqa: E402
    conflict_scenario,
    convergence_scenario,
    partial_scenario,
    proof_incomplete_scenario,
)


class SyncProtocolTests(unittest.TestCase):
    def test_fixture_is_synthetic_and_declares_all_outcomes(self) -> None:
        fixture = json.loads(
            (ROOT / "tests" / "fixtures" / "sync" / "sync01-fixture.json").read_text(
                encoding="utf-8"
            )
        )
        self.assertEqual(fixture["seed"], 20260714)
        self.assertEqual(len(fixture["peers"]), 3)
        self.assertEqual(
            set(fixture["expected_outcomes"]),
            {"convergence", "partial", "conflict", "proof_incomplete"},
        )

    def test_envelope_is_deeply_immutable(self) -> None:
        envelope = Envelope(
            schema_version=1,
            sync_envelope_id="syn_immutable_001",
            space_id="space_sync_synthetic",
            device_id="peer_alpha",
            device_sequence=1,
            operation="append_revision",
            object_type="event_revision",
            object_id="rev_immutable_001",
            base_revision=0,
            payload={"event_id": "event_immutable", "changes": {"title": "Synthetic"}},
            idempotency_key="idem_immutable_001",
            created_at="2026-01-01T00:00:01Z",
        )
        with self.assertRaises(FrozenInstanceError):
            envelope.object_id = "changed"  # type: ignore[misc]
        with self.assertRaises(TypeError):
            envelope.payload["event_id"] = "changed"  # type: ignore[index]
        with self.assertRaises(TypeError):
            envelope.payload["changes"]["title"] = "changed"  # type: ignore[index]

    def test_duplicate_is_idempotent_and_converges(self) -> None:
        sim = DeterministicSimulator(("peer_alpha", "peer_beta"))
        envelope = self._revision(sim, "peer_alpha", "event_dup", "rev_dup", 0, {"title": "One"})
        first = sim.deliver(envelope, "peer_beta")
        duplicate = sim.deliver(envelope, "peer_beta")
        self.assertEqual(first.code, "REVISION_APPLIED")
        self.assertEqual(duplicate.code, "DUPLICATE_ENVELOPE")
        self.assertEqual(sim.evaluate().outcome, Outcome.CONVERGENCE)

    def test_out_of_order_gap_then_drain(self) -> None:
        sim = DeterministicSimulator(("peer_alpha", "peer_beta"))
        first = self._revision(sim, "peer_alpha", "event_gap", "rev_gap_1", 0, {"title": "One"})
        second = self._revision(sim, "peer_alpha", "event_gap", "rev_gap_2", 1, {"status": "Two"})
        gap = sim.deliver(second, "peer_beta")
        self.assertEqual(gap.code, "SYNC_SEQUENCE_GAP")
        self.assertEqual(sim.evaluate().outcome, Outcome.PARTIAL)
        sim.deliver(first, "peer_beta")
        self.assertEqual(sim.peers["peer_beta"].cursors["peer_alpha"], 2)
        self.assertEqual(sim.evaluate().outcome, Outcome.CONVERGENCE)

    def test_offline_replay_converges(self) -> None:
        sim = DeterministicSimulator(("peer_alpha", "peer_beta"))
        sim.set_online("peer_beta", False)
        envelope = self._revision(
            sim, "peer_alpha", "event_offline", "rev_offline", 0, {"title": "Queued"}
        )
        queued = sim.deliver(envelope, "peer_beta")
        self.assertEqual(queued.code, "PEER_OFFLINE")
        partial = sim.evaluate()
        self.assertEqual(partial.outcome, Outcome.PARTIAL)
        self.assertIn("peer_offline", partial.partial_reasons["peer_beta"])
        self.assertIn("offline_replay_pending:1", partial.partial_reasons["peer_beta"])
        sim.set_online("peer_beta", True)
        sim.replay_offline("peer_beta")
        self.assertEqual(sim.evaluate().outcome, Outcome.CONVERGENCE)

    def test_old_schema_is_quarantined_without_cursor_advance(self) -> None:
        sim = DeterministicSimulator(("peer_alpha", "peer_beta"))
        old = Envelope(
            schema_version=0,
            sync_envelope_id="syn_old_schema_001",
            space_id="space_sync_synthetic",
            device_id="peer_alpha",
            device_sequence=1,
            operation="append_revision",
            object_type="event_revision",
            object_id="rev_old_schema_001",
            base_revision=0,
            payload={"event_id": "event_old", "changes": {"title": "Old"}},
            idempotency_key="idem_old_schema_001",
            created_at="2026-01-01T00:00:01Z",
        )
        receipt = sim.deliver(old, "peer_beta")
        self.assertEqual(receipt.code, "SCHEMA_UNSUPPORTED")
        self.assertEqual(sim.peers["peer_beta"].cursors["peer_alpha"], 0)
        result = sim.evaluate()
        self.assertEqual(result.outcome, Outcome.PARTIAL)
        self.assertIn("schema_unsupported:peer_alpha:1", result.partial_reasons["peer_beta"])

    def test_partial_demo_scenario_is_stable(self) -> None:
        first = partial_scenario()
        second = partial_scenario()
        self.assertEqual(first.outcome, Outcome.PARTIAL)
        self.assertEqual(first.to_json(), second.to_json())

    def test_disjoint_concurrent_fields_converge(self) -> None:
        result = convergence_scenario()
        self.assertEqual(result.outcome, Outcome.CONVERGENCE)
        self.assertTrue(result.converged)
        event = result.peer_snapshots["peer_alpha"]["events"]["event_sync_001"]
        self.assertEqual(event["fields"]["status"], "reviewed")
        self.assertEqual(event["fields"]["category"], "demo")

    def test_same_field_concurrent_revisions_preserve_conflict(self) -> None:
        first = conflict_scenario()
        second = conflict_scenario()
        self.assertEqual(first.outcome, Outcome.CONFLICT)
        self.assertTrue(first.converged)
        self.assertIn("title", first.conflicts["event_sync_002"])
        self.assertEqual(first.to_json(), second.to_json())

    def test_priority_tombstone_blocks_older_update_and_drain(self) -> None:
        sim = DeterministicSimulator(("peer_alpha", "peer_beta"))
        initial = self._revision(sim, "peer_alpha", "event_delete", "rev_delete_1", 0, {"title": "One"})
        stale = self._revision(sim, "peer_alpha", "event_delete", "rev_delete_2", 1, {"title": "Stale"})
        tombstone = sim.emit(
            "peer_alpha",
            "tombstone",
            "event",
            "event_delete",
            payload={"required_peer_ids": ["peer_alpha", "peer_beta"]},
        )
        priority = sim.deliver(tombstone, "peer_beta")
        self.assertEqual(priority.status, "priority_applied_with_gap")
        sim.deliver(stale, "peer_beta")
        sim.deliver(initial, "peer_beta")
        snapshot = sim.peers["peer_beta"].state_snapshot()
        self.assertEqual(snapshot["events"]["event_delete"]["state"], "deleted")
        self.assertEqual(sim.peers["peer_beta"].cursors["peer_alpha"], 3)
        self.assertEqual(sim.evaluate().outcome, Outcome.CONVERGENCE)

    def test_deletion_proof_is_incomplete_for_offline_peer(self) -> None:
        result = proof_incomplete_scenario()
        self.assertEqual(result.outcome, Outcome.PROOF_INCOMPLETE)
        self.assertFalse(result.converged)
        self.assertEqual(result.deletion_proofs[0]["pending_peer_ids"], ["peer_gamma"])

    def test_deletion_proof_completes_after_offline_replay(self) -> None:
        sim = DeterministicSimulator(("peer_alpha", "peer_beta", "peer_gamma"))
        initial = self._revision(sim, "peer_alpha", "event_proof", "rev_proof", 0, {"title": "One"})
        sim.broadcast(initial)
        sim.set_online("peer_gamma", False)
        tombstone = sim.emit(
            "peer_alpha",
            "tombstone",
            "event",
            "event_proof",
            payload={"required_peer_ids": ["peer_alpha", "peer_beta", "peer_gamma"]},
        )
        sim.broadcast(tombstone)
        self.assertEqual(sim.evaluate().outcome, Outcome.PROOF_INCOMPLETE)
        sim.set_online("peer_gamma", True)
        sim.replay_offline("peer_gamma")
        result = sim.evaluate()
        self.assertEqual(result.outcome, Outcome.CONVERGENCE)
        self.assertEqual(result.deletion_proofs[0]["status"], "complete")

    def test_revoked_device_message_is_rejected(self) -> None:
        sim = DeterministicSimulator(("peer_alpha", "peer_beta", "peer_gamma"))
        revoke = sim.emit(
            "peer_gamma",
            "grant_revocation",
            "device_grant",
            "peer_alpha",
            payload={"target_type": "device", "target_id": "peer_alpha"},
        )
        sim.deliver(revoke, "peer_beta")
        stale = sim.emit(
            "peer_alpha",
            "append_revision",
            "event_revision",
            "rev_revoked_001",
            base_revision=0,
            payload={"event_id": "event_revoked", "changes": {"title": "Rejected"}},
        )
        receipt = sim.deliver(stale, "peer_beta")
        self.assertEqual(receipt.code, "DEVICE_REVOKED")
        self.assertNotIn("event_revoked", sim.peers["peer_beta"].state_snapshot()["events"])

    def test_message_after_authorization_expiry_is_rejected(self) -> None:
        sim = DeterministicSimulator(("peer_alpha", "peer_beta"))
        sim.peers["peer_beta"].set_authorization_expiry("peer_alpha", expires_after_tick=1)
        envelope = self._revision(
            sim, "peer_alpha", "event_expired", "rev_expired", 0, {"title": "Rejected"}
        )
        receipt = sim.deliver(envelope, "peer_beta")
        self.assertEqual(receipt.code, "AUTHORIZATION_EXPIRED")
        self.assertEqual(sim.evaluate().outcome, Outcome.PARTIAL)

    def test_altered_same_sequence_is_replay_attack(self) -> None:
        sim = DeterministicSimulator(("peer_alpha", "peer_beta"))
        envelope = self._revision(sim, "peer_alpha", "event_replay", "rev_replay", 0, {"title": "One"})
        sim.deliver(envelope, "peer_beta")
        altered = replace(
            envelope,
            payload={"event_id": "event_replay", "changes": {"title": "Altered"}},
        )
        receipt = sim.deliver(altered, "peer_beta")
        self.assertEqual(receipt.code, "REPLAY_SEQUENCE_COLLISION")
        self.assertEqual(sim.evaluate().outcome, Outcome.PARTIAL)

    def test_idempotency_key_with_different_content_is_rejected(self) -> None:
        sim = DeterministicSimulator(("peer_alpha", "peer_beta"))
        first = sim.emit(
            "peer_alpha",
            "append_revision",
            "event_revision",
            "rev_idem_1",
            base_revision=0,
            idempotency_key="idem_shared_0001",
            payload={"event_id": "event_idem", "changes": {"title": "One"}},
        )
        second = sim.emit(
            "peer_alpha",
            "append_revision",
            "event_revision",
            "rev_idem_2",
            base_revision=1,
            idempotency_key="idem_shared_0001",
            payload={"event_id": "event_idem", "changes": {"title": "Two"}},
        )
        sim.deliver(first, "peer_beta")
        receipt = sim.deliver(second, "peer_beta")
        self.assertEqual(receipt.code, "IDEMPOTENCY_CONFLICT")
        self.assertEqual(sim.evaluate().outcome, Outcome.PARTIAL)

    @staticmethod
    def _revision(
        sim: DeterministicSimulator,
        peer_id: str,
        event_id: str,
        revision_id: str,
        base_revision: int,
        changes: dict[str, str],
    ) -> Envelope:
        return sim.emit(
            peer_id,
            "append_revision",
            "event_revision",
            revision_id,
            base_revision=base_revision,
            payload={"event_id": event_id, "changes": changes},
        )


if __name__ == "__main__":
    unittest.main()
