"""Event-node storage boundary shared by the MCP mock backends."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
import hashlib
from typing import Any, Iterable, Protocol, runtime_checkable


def idempotency_slot(value: str, *, domain: str) -> str:
    """Return a stable domain-separated slot without persisting caller input."""
    material = f"ameme:idempotency:v1:{domain}\0{value}".encode("utf-8")
    return f"idem_{hashlib.sha256(material).hexdigest()}"


class EventNodeStoreError(Exception):
    """Base error raised by an EventNodeStore implementation."""


class EventNodeNotVisible(EventNodeStoreError):
    """The requested event is absent, deleted, or outside the authorized space."""


class EventNodeConflict(EventNodeStoreError):
    """The requested revision or undo no longer matches the durable head."""


class EventNodeIdempotencyConflict(EventNodeStoreError):
    """An event-store key was reused with a different semantic payload."""


@dataclass(frozen=True)
class EventNodeScope:
    """Scope already authorized by AmemeMock and rechecked by the store adapter."""

    owner_id: str
    caller_id: str
    grant_id: str
    purpose: str
    spaces: tuple[str, ...]
    memory_types: tuple[str, ...]

    def require(self, space: str, memory_type: str) -> None:
        if space not in self.spaces or memory_type not in self.memory_types:
            raise EventNodeNotVisible("event-node request exceeded its authorized scope")


@runtime_checkable
class EventNodeStore(Protocol):
    """Storage contract used after the MCP policy layer authorizes a request."""

    state: dict[str, Any]

    def save(self) -> None: ...

    def close(self) -> None: ...

    def get_event(
        self,
        event_id: str,
        *,
        scope: EventNodeScope,
        space: str,
        memory_type: str,
    ) -> dict[str, Any] | None: ...

    def create_event(
        self,
        *,
        scope: EventNodeScope,
        space: str,
        content: str,
        event_time: str | None,
        event_type: str,
        evidence_state: str,
        fact_status: str,
        sensitivity: str,
        data_class: str,
        now: str,
        idempotency_key: str,
    ) -> dict[str, Any]: ...

    def append_revision(
        self,
        *,
        scope: EventNodeScope,
        space: str,
        event_id: str,
        content: str,
        evidence_state: str,
        fact_status: str,
        now: str,
        idempotency_key: str,
    ) -> dict[str, Any]: ...

    def undo_capture(
        self,
        undo: dict[str, Any],
        *,
        scope: EventNodeScope,
        space: str,
        memory_type: str,
        now: str,
        idempotency_key: str,
    ) -> dict[str, Any]: ...

    def visible_events(
        self,
        *,
        scope: EventNodeScope,
        spaces: Iterable[str],
        memory_types: Iterable[str],
        query: str | None,
        allow_high_risk: bool,
        start_at: datetime | None,
        end_at: datetime | None,
    ) -> tuple[list[dict[str, Any]], bool]: ...

    def set_policy_blocked(
        self,
        event_id: str,
        *,
        scope: EventNodeScope,
        space: str,
        memory_type: str,
    ) -> bool: ...

    def enqueue_delivery(
        self,
        *,
        object_type: str,
        object_id: str,
        now: str,
        idempotency_key: str,
    ) -> dict[str, Any]: ...

    def queued_count(self) -> int: ...
