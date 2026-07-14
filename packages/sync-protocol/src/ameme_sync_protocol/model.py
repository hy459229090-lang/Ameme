"""Immutable protocol values and stable evidence serialization."""

from __future__ import annotations

from collections.abc import Iterator, Mapping
from dataclasses import dataclass, field
from enum import Enum
import hashlib
import json
from typing import Any


JsonValue = None | bool | int | float | str | list["JsonValue"] | dict[str, "JsonValue"]


def canonical_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def digest_json(value: Any) -> str:
    return hashlib.sha256(canonical_json(value).encode("utf-8")).hexdigest()


def _freeze(value: Any) -> Any:
    if isinstance(value, FrozenMap):
        return value
    if isinstance(value, Mapping):
        return FrozenMap(value)
    if isinstance(value, (list, tuple)):
        return tuple(_freeze(item) for item in value)
    if value is None or isinstance(value, (bool, int, float, str)):
        return value
    raise TypeError(f"payload contains unsupported value type: {type(value).__name__}")


def thaw(value: Any) -> Any:
    if isinstance(value, FrozenMap):
        return {key: thaw(item) for key, item in value.items()}
    if isinstance(value, tuple):
        return [thaw(item) for item in value]
    return value


class FrozenMap(Mapping[str, Any]):
    """Small immutable mapping used to make an envelope deeply immutable."""

    __slots__ = ("_items", "_index")

    def __init__(self, source: Mapping[str, Any]) -> None:
        items = tuple(sorted((str(key), _freeze(value)) for key, value in source.items()))
        self._items = items
        self._index = dict(items)

    def __getitem__(self, key: str) -> Any:
        return self._index[key]

    def __iter__(self) -> Iterator[str]:
        return (key for key, _ in self._items)

    def __len__(self) -> int:
        return len(self._items)

    def __repr__(self) -> str:
        return f"FrozenMap({dict(self._items)!r})"


SUPPORTED_OPERATIONS = frozenset(
    {
        "append_revision",
        "structured_upsert",
        "tombstone",
        "grant_revocation",
        "deletion_ack",
        "raw_manifest",
    }
)


@dataclass(frozen=True, slots=True)
class Envelope:
    """Contract-shaped envelope whose payload cannot change after construction."""

    schema_version: int
    sync_envelope_id: str
    space_id: str
    device_id: str
    device_sequence: int
    operation: str
    object_type: str
    object_id: str
    idempotency_key: str
    created_at: str
    payload: Mapping[str, Any] = field(default_factory=lambda: FrozenMap({}))
    base_revision: int | None = None

    def __post_init__(self) -> None:
        if self.device_sequence < 1:
            raise ValueError("device_sequence must be >= 1")
        if self.operation not in SUPPORTED_OPERATIONS:
            raise ValueError(f"unsupported operation: {self.operation}")
        if len(self.idempotency_key) < 8 or len(self.idempotency_key) > 128:
            raise ValueError("idempotency_key must contain 8..128 characters")
        if not self.sync_envelope_id or not self.space_id or not self.device_id:
            raise ValueError("envelope identifiers must be non-empty")
        if not self.object_type or not self.object_id:
            raise ValueError("object identifiers must be non-empty")
        if self.base_revision is not None and self.base_revision < 0:
            raise ValueError("base_revision must be >= 0")
        object.__setattr__(self, "payload", _freeze(self.payload))

    @property
    def is_priority(self) -> bool:
        return self.operation in {"grant_revocation", "tombstone", "deletion_ack"}

    def to_dict(self) -> dict[str, Any]:
        value: dict[str, Any] = {
            "schema_version": self.schema_version,
            "sync_envelope_id": self.sync_envelope_id,
            "space_id": self.space_id,
            "device_id": self.device_id,
            "device_sequence": self.device_sequence,
            "operation": self.operation,
            "object_type": self.object_type,
            "object_id": self.object_id,
            "idempotency_key": self.idempotency_key,
            "created_at": self.created_at,
        }
        if self.base_revision is not None:
            value["base_revision"] = self.base_revision
        payload = thaw(self.payload)
        if payload:
            value["payload"] = payload
        return value

    @property
    def digest(self) -> str:
        return digest_json(self.to_dict())

    @property
    def idempotency_digest(self) -> str:
        return digest_json(
            {
                "space_id": self.space_id,
                "device_id": self.device_id,
                "operation": self.operation,
                "object_type": self.object_type,
                "object_id": self.object_id,
                "base_revision": self.base_revision,
                "payload": thaw(self.payload),
            }
        )


@dataclass(frozen=True, slots=True)
class Receipt:
    peer_id: str
    envelope_id: str
    origin_device_id: str
    device_sequence: int
    status: str
    code: str
    cursor: int
    semantic_applied: bool = False

    def to_dict(self) -> dict[str, Any]:
        return {
            "peer_id": self.peer_id,
            "envelope_id": self.envelope_id,
            "origin_device_id": self.origin_device_id,
            "device_sequence": self.device_sequence,
            "status": self.status,
            "code": self.code,
            "cursor": self.cursor,
            "semantic_applied": self.semantic_applied,
        }


class Outcome(str, Enum):
    CONVERGENCE = "convergence"
    PARTIAL = "partial"
    CONFLICT = "conflict"
    PROOF_INCOMPLETE = "proof_incomplete"


@dataclass(frozen=True, slots=True)
class SimulationResult:
    outcome: Outcome
    converged: bool
    seed: int
    peer_state_digests: Mapping[str, str]
    peer_snapshots: Mapping[str, Any]
    partial_reasons: Mapping[str, tuple[str, ...]]
    conflicts: Mapping[str, Any]
    deletion_proofs: tuple[Mapping[str, Any], ...]
    evidence: tuple[Mapping[str, Any], ...]
    conditions: tuple[str, ...]

    def to_dict(self) -> dict[str, Any]:
        return {
            "outcome": self.outcome.value,
            "converged": self.converged,
            "seed": self.seed,
            "peer_state_digests": dict(sorted(self.peer_state_digests.items())),
            "peer_snapshots": {
                key: self.peer_snapshots[key] for key in sorted(self.peer_snapshots)
            },
            "partial_reasons": {
                key: list(self.partial_reasons[key]) for key in sorted(self.partial_reasons)
            },
            "conflicts": {key: self.conflicts[key] for key in sorted(self.conflicts)},
            "deletion_proofs": list(self.deletion_proofs),
            "conditions": list(self.conditions),
            "evidence": list(self.evidence),
        }

    def to_json(self, *, indent: int = 2) -> str:
        return json.dumps(self.to_dict(), ensure_ascii=False, sort_keys=True, indent=indent)
