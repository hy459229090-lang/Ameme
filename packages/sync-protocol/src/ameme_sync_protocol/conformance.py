"""Canonical cross-language conformance vector construction and execution."""

from __future__ import annotations

from copy import deepcopy
from datetime import datetime, timedelta, timezone
from typing import Any, Iterable, Mapping

from .model import Envelope, SimulationResult
from .simulator import DeterministicSimulator, Peer


DEFAULT_SEED = 20260714
DEFAULT_SPACE = "space_sync_synthetic"


def _envelope(
    ref: str,
    device_id: str,
    sequence: int,
    operation: str,
    object_type: str,
    object_id: str,
    *,
    payload: Mapping[str, Any] | None = None,
    base_revision: int | None = None,
    schema_version: int = 1,
    idempotency_key: str | None = None,
) -> dict[str, Any]:
    created = datetime(2026, 1, 1, tzinfo=timezone.utc) + timedelta(seconds=sequence)
    value: dict[str, Any] = {
        "schema_version": schema_version,
        "sync_envelope_id": f"syn_{ref}",
        "space_id": DEFAULT_SPACE,
        "device_id": device_id,
        "device_sequence": sequence,
        "operation": operation,
        "object_type": object_type,
        "object_id": object_id,
        "idempotency_key": idempotency_key or f"idem_{ref}_00000001",
        "created_at": created.isoformat().replace("+00:00", "Z"),
        "payload": dict(payload or {}),
    }
    if base_revision is not None:
        value["base_revision"] = base_revision
    return {"ref": ref, "value": value}


def _revision(
    ref: str,
    device_id: str,
    sequence: int,
    event_id: str,
    changes: Mapping[str, Any],
    *,
    base_revision: int,
    schema_version: int = 1,
    idempotency_key: str | None = None,
) -> dict[str, Any]:
    return _envelope(
        ref,
        device_id,
        sequence,
        "append_revision",
        "event_revision",
        f"rev_{ref}",
        payload={"event_id": event_id, "changes": dict(changes)},
        base_revision=base_revision,
        schema_version=schema_version,
        idempotency_key=idempotency_key,
    )


def _deliver(envelope_ref: str, target_peer_id: str) -> dict[str, Any]:
    return {"action": "deliver", "envelope_ref": envelope_ref, "target_peer_id": target_peer_id}


def _online(peer_id: str, online: bool) -> dict[str, Any]:
    return {"action": "set_online", "peer_id": peer_id, "online": online}


def _replay(peer_id: str, *, reverse: bool = False) -> dict[str, Any]:
    return {"action": "replay_offline", "peer_id": peer_id, "reverse": reverse}


def _peer_initial_state(
    peer_ids: Iterable[str],
    *,
    authorization_expiry: Mapping[str, Mapping[str, int]] | None = None,
) -> dict[str, Any]:
    authorization_expiry = authorization_expiry or {}
    return {
        peer_id: {
            "projection": "empty",
            "supported_schema_versions": [1],
            "authorization_expiry_by_device": dict(authorization_expiry.get(peer_id, {})),
        }
        for peer_id in peer_ids
    }


def _vector(
    vector_id: str,
    description: str,
    peer_ids: tuple[str, ...],
    envelopes: list[dict[str, Any]],
    steps: list[dict[str, Any]],
    key_evidence_codes: list[str],
    *,
    authorization_expiry: Mapping[str, Mapping[str, int]] | None = None,
) -> dict[str, Any]:
    return {
        "id": vector_id,
        "description": description,
        "seed": DEFAULT_SEED,
        "peer_ids": list(peer_ids),
        "peer_initial_state": _peer_initial_state(
            peer_ids, authorization_expiry=authorization_expiry
        ),
        "envelopes": envelopes,
        "steps": steps,
        "expected": {"key_evidence_codes": sorted(key_evidence_codes)},
    }


def build_vector_specs() -> list[dict[str, Any]]:
    """Build input-only specifications; materialization adds golden expectations."""

    ab = ("peer_alpha", "peer_beta")
    abg = ("peer_alpha", "peer_beta", "peer_gamma")
    vectors: list[dict[str, Any]] = []

    duplicate = _revision("duplicate_initial", "peer_alpha", 1, "event_duplicate", {"title": "One"}, base_revision=0)
    vectors.append(
        _vector(
            "duplicate_idempotent_convergence",
            "An identical envelope delivered twice returns the stable duplicate result without a second revision.",
            ab,
            [duplicate],
            [_deliver("duplicate_initial", "peer_alpha"), _deliver("duplicate_initial", "peer_beta"), _deliver("duplicate_initial", "peer_beta")],
            ["DUPLICATE_ENVELOPE"],
        )
    )

    gap_initial = _revision("gap_initial", "peer_alpha", 1, "event_gap", {"title": "One"}, base_revision=0)
    gap_second = _revision("gap_second", "peer_alpha", 2, "event_gap", {"status": "Two"}, base_revision=1)
    vectors.append(
        _vector(
            "out_of_order_gap_then_convergence",
            "A later sequence is held until the missing envelope arrives, then the cursor drains deterministically.",
            ab,
            [gap_initial, gap_second],
            [_deliver("gap_initial", "peer_alpha"), _deliver("gap_second", "peer_alpha"), _deliver("gap_second", "peer_beta"), _deliver("gap_initial", "peer_beta")],
            ["SYNC_SEQUENCE_GAP"],
        )
    )

    offline = _revision("offline_initial", "peer_alpha", 1, "event_offline", {"title": "Queued"}, base_revision=0)
    vectors.append(
        _vector(
            "offline_replay_convergence",
            "An offline peer queues an envelope and converges after deterministic replay.",
            ab,
            [offline],
            [_deliver("offline_initial", "peer_alpha"), _online("peer_beta", False), _deliver("offline_initial", "peer_beta"), _online("peer_beta", True), _replay("peer_beta")],
            ["PEER_OFFLINE"],
        )
    )

    old_schema = _revision("old_schema", "peer_alpha", 1, "event_old_schema", {"title": "Old"}, base_revision=0, schema_version=0)
    vectors.append(
        _vector(
            "old_schema_quarantine_partial",
            "An unsupported old major is quarantined and does not advance the completion cursor.",
            ab,
            [old_schema],
            [_deliver("old_schema", "peer_beta")],
            ["SCHEMA_UNSUPPORTED"],
        )
    )

    merge_initial = _revision("merge_initial", "peer_alpha", 1, "event_merge", {"title": "Initial"}, base_revision=0)
    merge_beta = _revision("merge_beta", "peer_beta", 1, "event_merge", {"status": "reviewed"}, base_revision=1)
    merge_gamma = _revision("merge_gamma", "peer_gamma", 1, "event_merge", {"category": "demo"}, base_revision=1)
    vectors.append(
        _vector(
            "disjoint_revision_convergence",
            "Concurrent revisions from the same base merge when their changed fields are disjoint.",
            abg,
            [merge_initial, merge_beta, merge_gamma],
            [
                _deliver("merge_initial", "peer_alpha"), _deliver("merge_initial", "peer_beta"), _deliver("merge_initial", "peer_gamma"),
                _deliver("merge_beta", "peer_beta"), _deliver("merge_beta", "peer_alpha"), _deliver("merge_beta", "peer_gamma"),
                _deliver("merge_gamma", "peer_gamma"), _deliver("merge_gamma", "peer_beta"), _deliver("merge_gamma", "peer_alpha"),
            ],
            ["REVISION_APPLIED"],
        )
    )

    conflict_initial = _revision("conflict_initial", "peer_alpha", 1, "event_conflict", {"title": "Initial"}, base_revision=0)
    conflict_beta = _revision("conflict_beta", "peer_beta", 1, "event_conflict", {"title": "Beta"}, base_revision=1)
    conflict_gamma = _revision("conflict_gamma", "peer_gamma", 1, "event_conflict", {"title": "Gamma"}, base_revision=1)
    vectors.append(
        _vector(
            "same_field_revision_conflict",
            "Concurrent user-visible changes to the same field preserve deterministic alternatives instead of LWW.",
            abg,
            [conflict_initial, conflict_beta, conflict_gamma],
            [
                _deliver("conflict_initial", "peer_alpha"), _deliver("conflict_initial", "peer_beta"), _deliver("conflict_initial", "peer_gamma"),
                _deliver("conflict_beta", "peer_beta"), _deliver("conflict_beta", "peer_alpha"), _deliver("conflict_beta", "peer_gamma"),
                _deliver("conflict_gamma", "peer_gamma"), _deliver("conflict_gamma", "peer_beta"), _deliver("conflict_gamma", "peer_alpha"),
            ],
            ["REVISION_APPLIED"],
        )
    )

    tomb_initial = _revision("tomb_initial", "peer_alpha", 1, "event_tombstone", {"title": "Initial"}, base_revision=0)
    tomb_stale = _revision("tomb_stale", "peer_alpha", 2, "event_tombstone", {"title": "Stale"}, base_revision=1)
    tombstone = _envelope(
        "tombstone_priority",
        "peer_alpha",
        3,
        "tombstone",
        "event",
        "event_tombstone",
        payload={"required_peer_ids": ["peer_alpha", "peer_beta"]},
    )
    vectors.append(
        _vector(
            "tombstone_priority_no_resurrection",
            "A tombstone preapplies across a sequence gap and prevents older revisions from resurrecting the event.",
            ab,
            [tomb_initial, tomb_stale, tombstone],
            [
                _deliver("tomb_initial", "peer_alpha"), _deliver("tomb_stale", "peer_alpha"), _deliver("tombstone_priority", "peer_alpha"),
                _deliver("tombstone_priority", "peer_beta"), _deliver("tomb_stale", "peer_beta"), _deliver("tomb_initial", "peer_beta"),
            ],
            ["SYNC_SEQUENCE_GAP", "TOMBSTONE_APPLIED", "TOMBSTONE_PRECEDENCE"],
        )
    )

    proof_initial = _revision("proof_initial", "peer_alpha", 1, "event_proof", {"title": "Delete"}, base_revision=0)
    proof_tombstone = _envelope(
        "proof_tombstone",
        "peer_alpha",
        2,
        "tombstone",
        "event",
        "event_proof",
        payload={"required_peer_ids": ["peer_alpha", "peer_beta", "peer_gamma"]},
    )
    vectors.append(
        _vector(
            "offline_delete_proof_incomplete",
            "Deletion stays proof_incomplete while one required peer is offline and has not applied the tombstone.",
            abg,
            [proof_initial, proof_tombstone],
            [
                _deliver("proof_initial", "peer_alpha"), _deliver("proof_initial", "peer_beta"), _deliver("proof_initial", "peer_gamma"),
                _online("peer_gamma", False), _deliver("proof_tombstone", "peer_alpha"), _deliver("proof_tombstone", "peer_beta"), _deliver("proof_tombstone", "peer_gamma"),
            ],
            ["PEER_OFFLINE", "TOMBSTONE_APPLIED"],
        )
    )

    revoke = _envelope(
        "device_revoke",
        "peer_gamma",
        1,
        "grant_revocation",
        "device_grant",
        "peer_alpha",
        payload={"target_type": "device", "target_id": "peer_alpha"},
    )
    revoked_message = _revision("revoked_message", "peer_alpha", 1, "event_revoked", {"title": "Rejected"}, base_revision=0)
    vectors.append(
        _vector(
            "revoked_device_message_partial",
            "A peer rejects a content envelope after the origin device has been revoked.",
            abg,
            [revoke, revoked_message],
            [
                _deliver("device_revoke", "peer_gamma"), _deliver("device_revoke", "peer_beta"),
                _deliver("revoked_message", "peer_alpha"), _deliver("revoked_message", "peer_beta"),
            ],
            ["DEVICE_REVOKED", "REVOCATION_APPLIED"],
        )
    )

    expired = _revision("expired_message", "peer_alpha", 1, "event_expired", {"title": "Rejected"}, base_revision=0)
    vectors.append(
        _vector(
            "authorization_expired_message_partial",
            "A receiving peer rejects an envelope after its deterministic authorization expiry tick.",
            ab,
            [expired],
            [_deliver("expired_message", "peer_alpha"), _deliver("expired_message", "peer_beta")],
            ["AUTHORIZATION_EXPIRED"],
            authorization_expiry={"peer_beta": {"peer_alpha": 1}},
        )
    )

    replay_original = _revision("replay_original", "peer_alpha", 1, "event_replay", {"title": "Original"}, base_revision=0)
    replay_altered = _revision("replay_altered", "peer_alpha", 1, "event_replay", {"title": "Altered"}, base_revision=0)
    vectors.append(
        _vector(
            "altered_sequence_replay_partial",
            "A different digest reusing an observed device sequence is rejected as a replay collision.",
            ab,
            [replay_original, replay_altered],
            [_deliver("replay_original", "peer_alpha"), _deliver("replay_original", "peer_beta"), _deliver("replay_altered", "peer_beta")],
            ["REPLAY_SEQUENCE_COLLISION"],
        )
    )

    idem_first = _revision(
        "idem_first", "peer_alpha", 1, "event_idempotency", {"title": "One"}, base_revision=0, idempotency_key="idem_shared_vector_0001"
    )
    idem_second = _revision(
        "idem_second", "peer_alpha", 2, "event_idempotency", {"title": "Two"}, base_revision=1, idempotency_key="idem_shared_vector_0001"
    )
    vectors.append(
        _vector(
            "idempotency_content_collision_partial",
            "The same idempotency key with different logical content is rejected on every receiving peer.",
            ab,
            [idem_first, idem_second],
            [_deliver("idem_first", "peer_alpha"), _deliver("idem_second", "peer_alpha"), _deliver("idem_first", "peer_beta"), _deliver("idem_second", "peer_beta")],
            ["IDEMPOTENCY_CONFLICT"],
        )
    )

    return vectors


def execute_vector(vector: Mapping[str, Any]) -> SimulationResult:
    peer_ids = tuple(str(peer_id) for peer_id in vector["peer_ids"])
    sim = DeterministicSimulator(peer_ids, seed=int(vector.get("seed", DEFAULT_SEED)))
    for peer_id, state in vector["peer_initial_state"].items():
        supported = tuple(int(version) for version in state["supported_schema_versions"])
        if supported != (1,):
            sim.peers[peer_id] = Peer(peer_id, supported_schema_versions=supported)
        if state.get("projection") != "empty":
            raise ValueError("v1 conformance vectors only support an empty initial projection")
        for device_id, tick in state.get("authorization_expiry_by_device", {}).items():
            sim.peers[peer_id].set_authorization_expiry(
                device_id, expires_after_tick=int(tick)
            )

    envelopes = {
        item["ref"]: Envelope(**item["value"])
        for item in vector["envelopes"]
    }
    for step in vector["steps"]:
        action = step["action"]
        if action == "deliver":
            sim.deliver(envelopes[step["envelope_ref"]], step["target_peer_id"])
        elif action == "set_online":
            sim.set_online(step["peer_id"], bool(step["online"]))
        elif action == "replay_offline":
            sim.replay_offline(step["peer_id"], reverse=bool(step.get("reverse", False)))
        else:
            raise ValueError(f"unknown conformance step action: {action}")
    return sim.evaluate(required_peer_ids=vector.get("evaluation_peer_ids"))


def _assert_projection_uses_v1_json_types(value: Any, path: str = "projection") -> None:
    if isinstance(value, float):
        raise AssertionError(f"{path}: floats are not allowed in v1 projection vectors")
    if isinstance(value, Mapping):
        for key, item in value.items():
            _assert_projection_uses_v1_json_types(item, f"{path}.{key}")
    elif isinstance(value, (list, tuple)):
        for index, item in enumerate(value):
            _assert_projection_uses_v1_json_types(item, f"{path}[{index}]")


def expected_from_result(
    result: SimulationResult, *, key_evidence_codes: Iterable[str]
) -> dict[str, Any]:
    _assert_projection_uses_v1_json_types(result.peer_snapshots)
    requested_codes = sorted(set(key_evidence_codes))
    available_codes = {str(item.get("code")) for item in result.evidence if item.get("code")}
    missing_codes = sorted(set(requested_codes) - available_codes)
    if missing_codes:
        raise AssertionError(f"conformance vector missing evidence codes: {missing_codes}")
    event_states = {
        peer_id: {
            event_id: event["state"]
            for event_id, event in snapshot["events"].items()
        }
        for peer_id, snapshot in result.peer_snapshots.items()
    }
    return {
        "result_class": result.outcome.value,
        "converged": result.converged,
        "peer_projection_digests": dict(sorted(result.peer_state_digests.items())),
        "peer_projections": {
            peer_id: result.peer_snapshots[peer_id]
            for peer_id in sorted(result.peer_snapshots)
        },
        "event_states": event_states,
        "key_evidence_codes": requested_codes,
        "partial_reasons": {
            peer_id: list(reasons)
            for peer_id, reasons in sorted(result.partial_reasons.items())
        },
        "deletion_proofs": list(result.deletion_proofs),
    }


def materialize_document() -> dict[str, Any]:
    vectors = build_vector_specs()
    materialized: list[dict[str, Any]] = []
    for vector in vectors:
        item = deepcopy(vector)
        result = execute_vector(item)
        item["expected"] = expected_from_result(
            result, key_evidence_codes=item["expected"]["key_evidence_codes"]
        )
        materialized.append(item)
    return {
        "fixture_version": 1,
        "protocol_scope": "SYNC-01 deterministic executable specification only",
        "digest_algorithm": "sha256(ameme-canonical-json-v1)",
        "canonicalization": {
            "name": "ameme-canonical-json-v1",
            "encoding": "UTF-8 without BOM",
            "object_keys": "lexicographic Unicode code point order",
            "arrays": "preserve declared order",
            "whitespace": "none",
            "non_ascii": "literal UTF-8 JSON strings",
            "numbers": "base-10 integers only in v1 projection vectors",
            "digest_output": "lowercase hexadecimal SHA-256",
        },
        "canonical_consumer_contract": "packages/contracts/schemas/ameme-domain.schema.json#/$defs/SyncEnvelope",
        "required_future_consumers": [
            "iOS Swift Network.framework conformance tests",
            "Android Kotlin NSD conformance tests",
        ],
        "non_claims": [
            "no real LAN discovery or socket validation",
            "no production cryptography validation",
            "no mobile background or persistence validation",
        ],
        "vector_count": len(materialized),
        "vectors": materialized,
    }


def validate_document(document: Mapping[str, Any]) -> list[str]:
    errors: list[str] = []
    vectors = document.get("vectors")
    if not isinstance(vectors, list):
        return ["vectors must be an array"]
    if document.get("vector_count") != len(vectors):
        errors.append("vector_count does not match vectors length")
    seen: set[str] = set()
    for vector in vectors:
        vector_id = str(vector.get("id", "<missing>"))
        if vector_id in seen:
            errors.append(f"{vector_id}: duplicate vector id")
            continue
        seen.add(vector_id)
        try:
            result = execute_vector(vector)
            actual = expected_from_result(
                result,
                key_evidence_codes=vector.get("expected", {}).get("key_evidence_codes", []),
            )
            if actual != vector.get("expected"):
                errors.append(f"{vector_id}: golden expectation drift")
        except Exception as exc:  # validation reports every malformed vector
            errors.append(f"{vector_id}: {type(exc).__name__}: {exc}")
    return errors
