"""Small durable JSON store used by the local mock."""

from __future__ import annotations

import json
import os
from copy import deepcopy
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable
import uuid

from .event_store import (
    EventNodeConflict,
    EventNodeNotVisible,
    EventNodeScope,
)


EMPTY_STATE: dict[str, Any] = {
    "state_version": 1,
    "challenges": {},
    "grants": {},
    "events": {},
    "revisions": {},
    "feedback": {},
    "context_packs": {},
    "activity": [],
    "idempotency": {},
    "queue": [],
    "undo": {},
    "policy_blocked_events": [],
    "event_store_operations": {},
}


def _identifier(prefix: str) -> str:
    return f"{prefix}_{uuid.uuid4().hex}"


def _parse(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(timezone.utc)


class JsonStore:
    """Non-production JSON fixture backend for EventNodeStore and mock control state."""

    def __init__(self, path: Path, seed_path: Path | None = None) -> None:
        self.path = path
        self.path.parent.mkdir(parents=True, exist_ok=True)
        if self.path.exists():
            self.state = json.loads(self.path.read_text(encoding="utf-8"))
        else:
            self.state = deepcopy(EMPTY_STATE)
            if seed_path:
                seed = json.loads(seed_path.read_text(encoding="utf-8"))
                for event in seed.get("events", []):
                    self.state["events"][event["event_id"]] = event
            self.save()
        for key, value in EMPTY_STATE.items():
            self.state.setdefault(key, deepcopy(value))

    def save(self) -> None:
        temporary = self.path.with_suffix(self.path.suffix + ".tmp")
        temporary.write_text(
            json.dumps(self.state, ensure_ascii=False, indent=2, sort_keys=True),
            encoding="utf-8",
        )
        os.replace(temporary, self.path)

    def close(self) -> None:
        self.save()

    def get_event(
        self,
        event_id: str,
        *,
        scope: EventNodeScope,
        space: str,
        memory_type: str,
    ) -> dict[str, Any] | None:
        scope.require(space, memory_type)
        event = self.state["events"].get(event_id)
        if (
            not event
            or event.get("owner_id") != scope.owner_id
            or event.get("space_id") != space
            or event.get("state") != "active"
        ):
            return None
        return deepcopy(event)

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
    ) -> dict[str, Any]:
        del idempotency_key
        scope.require(space, "event")
        event_id = _identifier("evt")
        event = {
            "schema_version": 1,
            "event_id": event_id,
            "owner_id": scope.owner_id,
            "space_id": space,
            "event_type": event_type,
            "time_range": {"start": event_time or now, "precision": "minute"},
            "title": content[:200],
            "description": content[:4000],
            "fact_status": fact_status,
            "field_evidence": [
                {
                    "field": "description",
                    "observation_ids": [f"obs_{event_id[4:]}"],
                    "confidence": 1.0
                    if evidence_state in {"observed", "user_asserted"}
                    else 0.5,
                    "status": evidence_state,
                }
            ],
            "evidence_state": evidence_state,
            "source_object_ids": [f"src_{event_id[4:]}"],
            "revision": 1,
            "sensitivity": sensitivity,
            "data_class": data_class,
            "memory_type": "event",
            "state": "active",
            "created_at": now,
            "updated_at": now,
        }
        self.state["events"][event_id] = event
        return {"object_type": "event", "event_id": event_id, "revision": 1}

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
    ) -> dict[str, Any]:
        del idempotency_key
        scope.require(space, "revision")
        event = self.state["events"].get(event_id)
        if not event or event.get("state") != "active" or event.get("space_id") != space:
            raise EventNodeNotVisible("target event is not visible in this space")
        revision_number = int(event["revision"]) + 1
        revision_id = _identifier("evr")
        revision = {
            "schema_version": 1,
            "event_revision_id": revision_id,
            "event_id": event_id,
            "revision": revision_number,
            "actor": "agent",
            "reason": "user_addendum"
            if evidence_state == "user_asserted"
            else "model_recompute",
            "changes": {
                "description": content[:4000],
                "evidence_state": evidence_state,
            },
            "created_at": now,
        }
        self.state["revisions"][revision_id] = revision
        event["description"] = content[:4000]
        event["fact_status"] = fact_status
        event["revision"] = revision_number
        event["revision_head_id"] = revision_id
        event["evidence_state"] = evidence_state
        event["updated_at"] = now
        return {
            "object_type": "revision",
            "event_revision_id": revision_id,
            "target_event_id": event_id,
            "revision": revision_number,
        }

    def undo_capture(
        self,
        undo: dict[str, Any],
        *,
        scope: EventNodeScope,
        space: str,
        memory_type: str,
        now: str,
        idempotency_key: str,
    ) -> dict[str, Any]:
        del idempotency_key
        scope.require(space, memory_type)
        if (
            undo.get("space_id") != space
            or undo.get("created_object_type") != memory_type
        ):
            raise EventNodeNotVisible("undo target exceeded its original space/type")
        event = self.state["events"].get(undo["target_event_id"])
        created_object_type = undo["created_object_type"]
        created_object_id = undo["created_object_id"]
        if (
            not event
            or event.get("owner_id") != scope.owner_id
            or event.get("space_id") != space
        ):
            raise EventNodeNotVisible("undo event is not visible in this space")
        if created_object_type == "event":
            if (
                event.get("event_id") != created_object_id
                or int(event.get("revision", 0))
                != int(undo.get("created_revision", 0))
                or int(undo.get("created_revision", 0)) != 1
            ):
                raise EventNodeNotVisible("undo event identity changed")
            event["state"] = "deleted"
            event["updated_at"] = now
            return {
                "state": "undone",
                "target_event_id": event["event_id"],
                "undone_object_type": "event",
                "undone_object_id": created_object_id,
                "activity_visible": True,
            }
        if created_object_type != "revision":
            raise EventNodeNotVisible("unknown undo object type")
        created_revision = self.state["revisions"].get(created_object_id)
        previous_snapshot = undo.get("previous_event_snapshot")
        if (
            not created_revision
            or created_revision.get("event_id") != event.get("event_id")
            or not isinstance(previous_snapshot, dict)
            or previous_snapshot.get("space_id") != space
            or int(event.get("revision", 0))
            != int(undo.get("created_revision", 0))
        ):
            raise EventNodeNotVisible("undo revision is not visible")
        if event.get("revision_head_id") != created_object_id:
            raise EventNodeConflict("undo revision is not the current head")
        compensation_revision_id = _identifier("evr")
        compensation_revision_number = int(event["revision"]) + 1
        self.state["revisions"][compensation_revision_id] = {
            "schema_version": 1,
            "event_revision_id": compensation_revision_id,
            "event_id": event["event_id"],
            "base_revision_id": created_object_id,
            "revision": compensation_revision_number,
            "actor": "system",
            "reason": "user_edit",
            "changes": {
                "description": previous_snapshot.get("description"),
                "evidence_state": previous_snapshot.get("evidence_state"),
                "undo": {"compensates_revision_id": created_object_id},
            },
            "created_at": now,
        }
        restored_event = deepcopy(previous_snapshot)
        restored_event["revision"] = compensation_revision_number
        restored_event["revision_head_id"] = compensation_revision_id
        restored_event["updated_at"] = now
        self.state["events"][event["event_id"]] = restored_event
        return {
            "state": "undone",
            "target_event_id": event["event_id"],
            "undone_object_type": "revision",
            "undone_object_id": created_object_id,
            "compensation_revision_id": compensation_revision_id,
            "activity_visible": True,
        }

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
    ) -> tuple[list[dict[str, Any]], bool]:
        requested_spaces = set(spaces)
        requested_types = set(memory_types)
        for space in requested_spaces:
            for memory_type in requested_types:
                scope.require(space, memory_type)
        query_folded = (query or "").casefold()
        result: list[dict[str, Any]] = []
        risk_filtered = False
        for event in self.state["events"].values():
            if event.get("state") != "active" or event.get("policy_blocked"):
                continue
            if event.get("owner_id") != scope.owner_id:
                continue
            if event.get("space_id") not in requested_spaces:
                continue
            if event.get("memory_type", "event") not in requested_types:
                continue
            if not allow_high_risk and (
                event.get("sensitivity") == "restricted"
                or event.get("data_class") == "raw"
            ):
                risk_filtered = True
                continue
            event_time_value = event.get("time_range", {}).get("start") or event.get(
                "created_at"
            )
            if event_time_value:
                event_time = _parse(event_time_value)
                if start_at and event_time < start_at:
                    continue
                if end_at and event_time > end_at:
                    continue
            body = f"{event.get('title', '')}\n{event.get('description', '')}".casefold()
            if query_folded and query_folded not in body:
                continue
            result.append(deepcopy(event))
        result.sort(
            key=lambda item: item.get("updated_at") or item.get("created_at") or "",
            reverse=True,
        )
        return result, risk_filtered

    def set_policy_blocked(
        self,
        event_id: str,
        *,
        scope: EventNodeScope,
        space: str,
        memory_type: str,
    ) -> bool:
        scope.require(space, memory_type)
        event = self.state["events"].get(event_id)
        if (
            not event
            or event.get("owner_id") != scope.owner_id
            or event.get("space_id") != space
        ):
            return False
        event["policy_blocked"] = True
        return True

    def enqueue_delivery(
        self,
        *,
        object_type: str,
        object_id: str,
        now: str,
        idempotency_key: str,
    ) -> dict[str, Any]:
        del idempotency_key
        queued = {
            "queue_id": _identifier("que"),
            "object_type": object_type,
            "object_id": object_id,
            "state": "queued",
            "created_at": now,
        }
        self.state["queue"].append(queued)
        return deepcopy(queued)

    def queued_count(self) -> int:
        return len(self.state["queue"])
