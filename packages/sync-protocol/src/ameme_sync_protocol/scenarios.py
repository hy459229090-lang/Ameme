"""Reusable synthetic scenarios for the demo and regression tests."""

from __future__ import annotations

from .model import Envelope, SimulationResult
from .simulator import DeterministicSimulator


def convergence_scenario(*, seed: int = 20260714) -> SimulationResult:
    sim = DeterministicSimulator(("peer_alpha", "peer_beta", "peer_gamma"), seed=seed)
    initial = sim.emit(
        "peer_alpha",
        "append_revision",
        "event_revision",
        "rev_event_001_initial",
        base_revision=0,
        payload={"event_id": "event_sync_001", "changes": {"title": "Synthetic event"}},
    )
    sim.broadcast(initial)

    beta = sim.emit(
        "peer_beta",
        "append_revision",
        "event_revision",
        "rev_event_001_beta",
        base_revision=1,
        payload={"event_id": "event_sync_001", "changes": {"status": "reviewed"}},
    )
    gamma = sim.emit(
        "peer_gamma",
        "append_revision",
        "event_revision",
        "rev_event_001_gamma",
        base_revision=1,
        payload={"event_id": "event_sync_001", "changes": {"category": "demo"}},
    )
    sim.set_online("peer_gamma", False)
    sim.deliver(beta, "peer_alpha")
    sim.deliver(beta, "peer_gamma")
    sim.deliver(gamma, "peer_alpha")
    sim.deliver(gamma, "peer_beta")
    sim.deliver(beta, "peer_beta")  # safe duplicate
    sim.set_online("peer_gamma", True)
    sim.replay_offline("peer_gamma")
    return sim.evaluate()


def conflict_scenario(*, seed: int = 20260714) -> SimulationResult:
    sim = DeterministicSimulator(("peer_alpha", "peer_beta", "peer_gamma"), seed=seed)
    initial = sim.emit(
        "peer_alpha",
        "append_revision",
        "event_revision",
        "rev_event_002_initial",
        base_revision=0,
        payload={"event_id": "event_sync_002", "changes": {"title": "Initial"}},
    )
    sim.broadcast(initial)
    beta = sim.emit(
        "peer_beta",
        "append_revision",
        "event_revision",
        "rev_event_002_beta",
        base_revision=1,
        payload={"event_id": "event_sync_002", "changes": {"title": "Beta choice"}},
    )
    gamma = sim.emit(
        "peer_gamma",
        "append_revision",
        "event_revision",
        "rev_event_002_gamma",
        base_revision=1,
        payload={"event_id": "event_sync_002", "changes": {"title": "Gamma choice"}},
    )
    sim.broadcast(beta)
    sim.broadcast(gamma)
    return sim.evaluate()


def partial_scenario(*, seed: int = 20260714) -> SimulationResult:
    sim = DeterministicSimulator(("peer_alpha", "peer_beta"), seed=seed)
    old_schema = Envelope(
        schema_version=0,
        sync_envelope_id="syn_peer_alpha_old_schema_000001",
        space_id="space_sync_synthetic",
        device_id="peer_alpha",
        device_sequence=1,
        operation="append_revision",
        object_type="event_revision",
        object_id="rev_event_old_schema_001",
        base_revision=0,
        payload={"event_id": "event_old_schema_001", "changes": {"title": "Old schema"}},
        idempotency_key="idem_old_schema_000001",
        created_at="2026-01-01T00:00:01Z",
    )
    sim.deliver(old_schema, "peer_beta")
    return sim.evaluate()


def proof_incomplete_scenario(*, seed: int = 20260714) -> SimulationResult:
    sim = DeterministicSimulator(("peer_alpha", "peer_beta", "peer_gamma"), seed=seed)
    initial = sim.emit(
        "peer_alpha",
        "append_revision",
        "event_revision",
        "rev_event_003_initial",
        base_revision=0,
        payload={"event_id": "event_sync_003", "changes": {"title": "Delete me"}},
    )
    sim.broadcast(initial)
    sim.set_online("peer_gamma", False)
    tombstone = sim.emit(
        "peer_alpha",
        "tombstone",
        "event",
        "event_sync_003",
        payload={"required_peer_ids": ["peer_alpha", "peer_beta", "peer_gamma"]},
    )
    sim.broadcast(tombstone)
    return sim.evaluate()


SCENARIOS = {
    "convergence": convergence_scenario,
    "conflict": conflict_scenario,
    "partial": partial_scenario,
    "proof_incomplete": proof_incomplete_scenario,
}
