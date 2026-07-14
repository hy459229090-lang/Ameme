"""Deterministic peer state machine with no network or cryptographic claims."""

from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any, Iterable, Mapping

from .model import Envelope, Outcome, Receipt, SimulationResult, canonical_json, digest_json, thaw


@dataclass(slots=True)
class _Pending:
    envelope: Envelope
    preapplied: bool
    semantic_applied: bool
    code: str


class Peer:
    """One deterministic local node with append-only protocol state."""

    def __init__(
        self,
        peer_id: str,
        *,
        space_id: str = "space_sync_synthetic",
        supported_schema_versions: Iterable[int] = (1,),
    ) -> None:
        self.peer_id = peer_id
        self.space_id = space_id
        self.supported_schema_versions = frozenset(supported_schema_versions)
        self._next_sequence = 1
        self.outbox: list[Envelope] = []
        self.cursors: dict[str, int] = defaultdict(int)
        self._sequence_digests: dict[str, dict[int, str]] = defaultdict(dict)
        self._pending: dict[str, dict[int, _Pending]] = defaultdict(dict)
        self._idempotency: dict[str, str] = {}
        self._revisions: dict[str, dict[str, Envelope]] = defaultdict(dict)
        self._tombstones: dict[str, set[str]] = defaultdict(set)
        self._revoked_devices: set[str] = set()
        self._authorization_expiry: dict[str, int] = {}
        self._quarantine: list[dict[str, Any]] = []
        self._security_reasons: set[str] = set()
        self.audit: list[dict[str, Any]] = []

    def set_authorization_expiry(self, device_id: str, *, expires_after_tick: int) -> None:
        self._authorization_expiry[device_id] = expires_after_tick

    def emit(
        self,
        operation: str,
        object_type: str,
        object_id: str,
        *,
        payload: Mapping[str, Any] | None = None,
        base_revision: int | None = None,
        idempotency_key: str | None = None,
        schema_version: int = 1,
        now_tick: int = 0,
    ) -> tuple[Envelope, Receipt]:
        sequence = self._next_sequence
        self._next_sequence += 1
        created = datetime(2026, 1, 1, tzinfo=timezone.utc) + timedelta(seconds=sequence)
        envelope = Envelope(
            schema_version=schema_version,
            sync_envelope_id=f"syn_{self.peer_id}_{sequence:06d}",
            space_id=self.space_id,
            device_id=self.peer_id,
            device_sequence=sequence,
            operation=operation,
            object_type=object_type,
            object_id=object_id,
            base_revision=base_revision,
            payload=payload or {},
            idempotency_key=idempotency_key or f"idem_{self.peer_id}_{sequence:08d}",
            created_at=created.isoformat().replace("+00:00", "Z"),
        )
        self.outbox.append(envelope)
        receipt = self.receive(envelope, now_tick=now_tick)
        return envelope, receipt

    def receive(self, envelope: Envelope, *, now_tick: int) -> Receipt:
        origin = envelope.device_id
        sequence = envelope.device_sequence
        existing_digest = self._sequence_digests[origin].get(sequence)
        if existing_digest is not None:
            if existing_digest != envelope.digest:
                self._security_reasons.add(f"replay_sequence_collision:{origin}:{sequence}")
                return self._receipt(envelope, "rejected", "REPLAY_SEQUENCE_COLLISION")
            return self._receipt(envelope, "duplicate", "DUPLICATE_ENVELOPE")

        expected = self.cursors[origin] + 1
        if envelope.schema_version not in self.supported_schema_versions:
            self._sequence_digests[origin][sequence] = envelope.digest
            self._quarantine.append(
                {
                    "envelope_id": envelope.sync_envelope_id,
                    "origin_device_id": origin,
                    "device_sequence": sequence,
                    "code": "SCHEMA_UNSUPPORTED",
                }
            )
            return self._receipt(envelope, "quarantined", "SCHEMA_UNSUPPORTED")

        self._sequence_digests[origin][sequence] = envelope.digest
        if sequence < expected:
            self._security_reasons.add(f"stale_sequence:{origin}:{sequence}")
            return self._receipt(envelope, "rejected", "REPLAY_STALE_SEQUENCE")

        if sequence > expected:
            if envelope.is_priority:
                applied, code = self._apply_semantics(envelope, now_tick=now_tick)
                self._pending[origin][sequence] = _Pending(envelope, True, applied, code)
                return self._receipt(
                    envelope,
                    "priority_applied_with_gap" if applied else "rejected_with_gap",
                    "SYNC_SEQUENCE_GAP" if applied else code,
                    semantic_applied=applied,
                )
            self._pending[origin][sequence] = _Pending(envelope, False, False, "SYNC_SEQUENCE_GAP")
            return self._receipt(envelope, "pending_gap", "SYNC_SEQUENCE_GAP")

        applied, code = self._apply_semantics(envelope, now_tick=now_tick)
        self.cursors[origin] = sequence
        self._drain(origin, now_tick=now_tick)
        status = "applied" if applied else "rejected"
        return self._receipt(envelope, status, code, semantic_applied=applied)

    def _drain(self, origin: str, *, now_tick: int) -> None:
        while True:
            sequence = self.cursors[origin] + 1
            pending = self._pending[origin].pop(sequence, None)
            if pending is None:
                return
            if not pending.preapplied:
                self._apply_semantics(pending.envelope, now_tick=now_tick)
            self.cursors[origin] = sequence

    def _apply_semantics(self, envelope: Envelope, *, now_tick: int) -> tuple[bool, str]:
        origin = envelope.device_id
        if origin in self._revoked_devices:
            self._security_reasons.add(f"device_revoked:{origin}")
            self._record_audit(envelope, "DEVICE_REVOKED")
            return False, "DEVICE_REVOKED"
        expires_at = self._authorization_expiry.get(origin)
        if expires_at is not None and now_tick > expires_at:
            self._security_reasons.add(f"authorization_expired:{origin}")
            self._record_audit(envelope, "AUTHORIZATION_EXPIRED")
            return False, "AUTHORIZATION_EXPIRED"

        idem_digest = self._idempotency.get(envelope.idempotency_key)
        if idem_digest is not None:
            if idem_digest == envelope.idempotency_digest:
                self._record_audit(envelope, "IDEMPOTENT_REPLAY")
                return False, "IDEMPOTENT_REPLAY"
            self._security_reasons.add(f"idempotency_conflict:{envelope.idempotency_key}")
            self._record_audit(envelope, "IDEMPOTENCY_CONFLICT")
            return False, "IDEMPOTENCY_CONFLICT"
        self._idempotency[envelope.idempotency_key] = envelope.idempotency_digest

        if envelope.operation == "tombstone":
            self._tombstones[envelope.object_id].add(envelope.digest)
            self._record_audit(envelope, "TOMBSTONE_APPLIED")
            return True, "TOMBSTONE_APPLIED"

        if envelope.operation == "grant_revocation":
            target_type = envelope.payload.get("target_type", envelope.object_type)
            target_id = envelope.payload.get("target_id", envelope.object_id)
            if target_type in {"device", "device_grant"}:
                self._revoked_devices.add(str(target_id))
            self._record_audit(envelope, "REVOCATION_APPLIED")
            return True, "REVOCATION_APPLIED"

        if envelope.operation in {"append_revision", "structured_upsert"}:
            event_id = str(envelope.payload.get("event_id", envelope.object_id))
            if event_id in self._tombstones:
                self._record_audit(envelope, "TOMBSTONE_PRECEDENCE")
                return False, "TOMBSTONE_PRECEDENCE"
            changes = envelope.payload.get("changes")
            if not isinstance(changes, Mapping) or not changes:
                self._security_reasons.add(f"invalid_revision:{envelope.sync_envelope_id}")
                self._record_audit(envelope, "INVALID_REVISION_PAYLOAD")
                return False, "INVALID_REVISION_PAYLOAD"
            self._revisions[event_id][envelope.digest] = envelope
            self._record_audit(envelope, "REVISION_APPLIED")
            return True, "REVISION_APPLIED"

        self._record_audit(envelope, "ACCEPTED_NO_PROJECTION")
        return True, "ACCEPTED_NO_PROJECTION"

    def _record_audit(self, envelope: Envelope, code: str) -> None:
        self.audit.append(
            {
                "envelope_id": envelope.sync_envelope_id,
                "origin_device_id": envelope.device_id,
                "device_sequence": envelope.device_sequence,
                "operation": envelope.operation,
                "object_type": envelope.object_type,
                "object_id": envelope.object_id,
                "result_code": code,
                "envelope_digest": envelope.digest[:16],
            }
        )

    def _receipt(
        self,
        envelope: Envelope,
        status: str,
        code: str,
        *,
        semantic_applied: bool = False,
    ) -> Receipt:
        return Receipt(
            peer_id=self.peer_id,
            envelope_id=envelope.sync_envelope_id,
            origin_device_id=envelope.device_id,
            device_sequence=envelope.device_sequence,
            status=status,
            code=code,
            cursor=self.cursors[envelope.device_id],
            semantic_applied=semantic_applied,
        )

    def _project_event(self, event_id: str) -> dict[str, Any]:
        if event_id in self._tombstones:
            return {
                "event_id": event_id,
                "state": "deleted",
                "revision": 0,
                "fields": {},
                "conflicts": {},
                # A peer that saw tombstone first may correctly reject older revisions,
                # while another peer retains them in its local audit. Deleted current
                # state therefore excludes revision-history membership from its digest.
                "revision_ids": [],
                "tombstone_count": len(self._tombstones[event_id]),
            }

        grouped: dict[int, list[Envelope]] = defaultdict(list)
        for envelope in self._revisions.get(event_id, {}).values():
            grouped[envelope.base_revision or 0].append(envelope)

        current_revision = 0
        fields: dict[str, Any] = {}
        conflicts: dict[str, list[dict[str, Any]]] = {}
        revision_ids: list[str] = []
        ancestry_gaps: list[int] = []
        for base_revision in sorted(grouped):
            revisions = sorted(
                grouped[base_revision],
                key=lambda item: (item.device_id, item.device_sequence, item.digest),
            )
            revision_ids.extend(item.object_id for item in revisions)
            if base_revision != current_revision:
                ancestry_gaps.append(base_revision)
                continue
            by_field: dict[str, list[tuple[str, Any, Envelope]]] = defaultdict(list)
            for revision in revisions:
                for field_name, value in revision.payload["changes"].items():
                    by_field[field_name].append((canonical_json(thaw(value)), thaw(value), revision))
            for field_name in sorted(by_field):
                alternatives_by_value: dict[str, tuple[Any, list[Envelope]]] = {}
                for value_key, value, revision in by_field[field_name]:
                    if value_key not in alternatives_by_value:
                        alternatives_by_value[value_key] = (value, [])
                    alternatives_by_value[value_key][1].append(revision)
                if len(alternatives_by_value) == 1:
                    value, _ = next(iter(alternatives_by_value.values()))
                    fields[field_name] = value
                    conflicts.pop(field_name, None)
                else:
                    alternatives: list[dict[str, Any]] = []
                    for value_key in sorted(alternatives_by_value):
                        value, source_revisions = alternatives_by_value[value_key]
                        alternatives.append(
                            {
                                "value": value,
                                "revision_ids": sorted(item.object_id for item in source_revisions),
                            }
                        )
                    conflicts[field_name] = alternatives
            current_revision += 1

        return {
            "event_id": event_id,
            "state": "conflict" if conflicts else "active",
            "revision": current_revision,
            "fields": {key: fields[key] for key in sorted(fields)},
            "conflicts": {key: conflicts[key] for key in sorted(conflicts)},
            "revision_ids": sorted(revision_ids),
            "ancestry_gaps": ancestry_gaps,
            "tombstone_count": 0,
        }

    def state_snapshot(self) -> dict[str, Any]:
        event_ids = sorted(set(self._revisions) | set(self._tombstones))
        return {
            "space_id": self.space_id,
            "events": {event_id: self._project_event(event_id) for event_id in event_ids},
            "revoked_devices": sorted(self._revoked_devices),
        }

    @property
    def state_digest(self) -> str:
        return digest_json(self.state_snapshot())

    def partial_reasons(self) -> tuple[str, ...]:
        reasons = set(self._security_reasons)
        for origin, pending in self._pending.items():
            if pending:
                reasons.add(f"sequence_gap:{origin}:{self.cursors[origin] + 1}")
        for item in self._quarantine:
            reasons.add(
                f"schema_unsupported:{item['origin_device_id']}:{item['device_sequence']}"
            )
        for event_id in set(self._revisions) | set(self._tombstones):
            projection = self._project_event(event_id)
            for gap in projection.get("ancestry_gaps", []):
                reasons.add(f"revision_gap:{event_id}:{gap}")
        return tuple(sorted(reasons))

    def conflicts(self) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for event_id in sorted(set(self._revisions) | set(self._tombstones)):
            projection = self._project_event(event_id)
            if projection["state"] == "conflict":
                result[event_id] = projection["conflicts"]
        return result


class DeterministicSimulator:
    """A fixed-seed delivery harness for two or three in-memory peers."""

    def __init__(self, peer_ids: Iterable[str], *, seed: int = 20260714) -> None:
        ids = tuple(peer_ids)
        if len(ids) not in {2, 3} or len(set(ids)) != len(ids):
            raise ValueError("SYNC-01 simulator requires 2 or 3 unique peers")
        self.seed = seed
        self.peers = {peer_id: Peer(peer_id) for peer_id in ids}
        self.tick = 0
        self.offline_peers: set[str] = set()
        self._offline_queue: dict[str, list[Envelope]] = defaultdict(list)
        self._deletion_required: dict[str, set[str]] = defaultdict(set)
        self._deletion_receipts: dict[str, set[str]] = defaultdict(set)
        self.evidence: list[dict[str, Any]] = []

    def emit(
        self,
        peer_id: str,
        operation: str,
        object_type: str,
        object_id: str,
        **kwargs: Any,
    ) -> Envelope:
        self.tick += 1
        envelope, receipt = self.peers[peer_id].emit(
            operation,
            object_type,
            object_id,
            now_tick=self.tick,
            **kwargs,
        )
        self._record("local_emit", receipt)
        self._track_tombstone(envelope, peer_id, receipt)
        return envelope

    def deliver(self, envelope: Envelope, target_peer_id: str) -> Receipt:
        self.tick += 1
        if target_peer_id in self.offline_peers:
            self._offline_queue[target_peer_id].append(envelope)
            receipt = Receipt(
                peer_id=target_peer_id,
                envelope_id=envelope.sync_envelope_id,
                origin_device_id=envelope.device_id,
                device_sequence=envelope.device_sequence,
                status="queued_offline",
                code="PEER_OFFLINE",
                cursor=self.peers[target_peer_id].cursors[envelope.device_id],
            )
            self._record("delivery_queued", receipt)
            return receipt
        receipt = self.peers[target_peer_id].receive(envelope, now_tick=self.tick)
        self._record("delivery", receipt)
        self._track_tombstone(envelope, target_peer_id, receipt)
        return receipt

    def broadcast(self, envelope: Envelope, *, exclude_origin: bool = True) -> list[Receipt]:
        receipts = []
        for peer_id in sorted(self.peers):
            if exclude_origin and peer_id == envelope.device_id:
                continue
            receipts.append(self.deliver(envelope, peer_id))
        return receipts

    def set_online(self, peer_id: str, online: bool) -> None:
        if online:
            self.offline_peers.discard(peer_id)
        else:
            self.offline_peers.add(peer_id)
        self.evidence.append(
            {"tick": self.tick, "action": "peer_online_state", "peer_id": peer_id, "online": online}
        )

    def replay_offline(self, peer_id: str, *, reverse: bool = False) -> list[Receipt]:
        if peer_id in self.offline_peers:
            raise ValueError(f"peer {peer_id} is still offline")
        queued = self._offline_queue.pop(peer_id, [])
        if reverse:
            queued.reverse()
        return [self.deliver(envelope, peer_id) for envelope in queued]

    def _track_tombstone(self, envelope: Envelope, peer_id: str, receipt: Receipt) -> None:
        if envelope.operation != "tombstone":
            return
        required = envelope.payload.get("required_peer_ids", tuple(sorted(self.peers)))
        self._deletion_required[envelope.object_id].update(str(item) for item in required)
        if receipt.semantic_applied or receipt.code == "DUPLICATE_ENVELOPE":
            self._deletion_receipts[envelope.object_id].add(peer_id)

    def _record(self, action: str, receipt: Receipt) -> None:
        item = receipt.to_dict()
        item["tick"] = self.tick
        item["action"] = action
        self.evidence.append(item)

    def evaluate(self, *, required_peer_ids: Iterable[str] | None = None) -> SimulationResult:
        peer_ids = tuple(sorted(required_peer_ids or self.peers))
        snapshots = {peer_id: self.peers[peer_id].state_snapshot() for peer_id in peer_ids}
        digests = {peer_id: digest_json(snapshots[peer_id]) for peer_id in peer_ids}
        converged = len(set(digests.values())) == 1
        partial_reasons: dict[str, tuple[str, ...]] = {}
        for peer_id in peer_ids:
            reasons = set(self.peers[peer_id].partial_reasons())
            if peer_id in self.offline_peers:
                reasons.add("peer_offline")
            if self._offline_queue.get(peer_id):
                reasons.add(f"offline_replay_pending:{len(self._offline_queue[peer_id])}")
            if reasons:
                partial_reasons[peer_id] = tuple(sorted(reasons))
        conflicts: dict[str, Any] = {}
        for peer_id in peer_ids:
            for event_id, fields in self.peers[peer_id].conflicts().items():
                conflicts[event_id] = fields

        proofs: list[dict[str, Any]] = []
        proof_incomplete = False
        for target_id in sorted(self._deletion_required):
            required = self._deletion_required[target_id]
            receipts = self._deletion_receipts[target_id]
            missing = sorted(required - receipts)
            status = "proof_incomplete" if missing else "complete"
            proof_incomplete = proof_incomplete or bool(missing)
            proofs.append(
                {
                    "target_id": target_id,
                    "status": status,
                    "required_peer_ids": sorted(required),
                    "acknowledged_peer_ids": sorted(required & receipts),
                    "pending_peer_ids": missing,
                }
            )

        conditions: list[str] = []
        if converged:
            conditions.append("state_digests_equal")
        else:
            conditions.append("state_digests_differ")
        if partial_reasons:
            conditions.append("partial_scope_or_rejection_present")
        if conflicts:
            conditions.append("revision_conflict_preserved")
        if proof_incomplete:
            conditions.append("deletion_proof_incomplete")

        if proof_incomplete:
            outcome = Outcome.PROOF_INCOMPLETE
        elif conflicts:
            outcome = Outcome.CONFLICT
        elif not converged or partial_reasons:
            outcome = Outcome.PARTIAL
        else:
            outcome = Outcome.CONVERGENCE

        return SimulationResult(
            outcome=outcome,
            converged=converged,
            seed=self.seed,
            peer_state_digests=digests,
            peer_snapshots=snapshots,
            partial_reasons=partial_reasons,
            conflicts=conflicts,
            deletion_proofs=tuple(proofs),
            evidence=tuple(self.evidence),
            conditions=tuple(conditions),
        )
