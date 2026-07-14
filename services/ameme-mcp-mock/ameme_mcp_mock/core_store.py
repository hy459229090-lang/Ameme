"""Synthetic EventNodeStore adapter backed by the Core reference oracle.

This module is integration-test infrastructure.  The Python CoreOracle is a
non-production semantic oracle, not a desktop, mobile, or service runtime.
"""

from __future__ import annotations

from copy import deepcopy
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import sqlite3
from typing import Any, Callable, Iterable

from ameme_core_reference import (
    CoreOracle,
    CoreOracleError,
    IdempotencyConflict,
    RevisionConflict,
)

from .event_store import (
    EventNodeConflict,
    EventNodeIdempotencyConflict,
    EventNodeNotVisible,
    EventNodeScope,
    EventNodeStoreError,
    idempotency_slot,
)
from .store import JsonStore


def _parse(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(
        timezone.utc
    )


def _digest(value: Any) -> str:
    canonical = json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    )
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def _evidence_state(snapshot: dict[str, Any]) -> str:
    evidences = snapshot.get("field_evidence") or []
    return str(evidences[0].get("status", "unknown")) if evidences else "unknown"


class CoreEventNodeStore:
    """Test adapter that projects EventNodeStore operations onto CoreOracle.

    Grant, policy, feedback, ContextPack, activity, and MCP idempotency state stay
    in the JSON control plane. Event content is written only through CoreOracle's
    public commands.
    """

    def __init__(
        self,
        control_path: Path,
        database_path: Path,
        *,
        clock: Callable[[], datetime],
        fault_injector: Callable[[str], None] | None = None,
    ) -> None:
        self.control = JsonStore(control_path)
        self.state = self.control.state
        self._clock = clock
        self._fault_injector = fault_injector
        self.core = CoreOracle(database_path, now=self._now)

    def _now(self) -> str:
        return self._clock().astimezone(timezone.utc).isoformat()

    def save(self) -> None:
        self.control.save()

    def close(self) -> None:
        self.control.close()
        self.core.close()

    def _fault(self, stage: str) -> None:
        if self._fault_injector is not None:
            self._fault_injector(stage)

    def _begin_operation(
        self,
        *,
        kind: str,
        idempotency_key: str,
        semantic_payload: dict[str, Any],
        effective: dict[str, Any],
    ) -> tuple[dict[str, Any] | None, dict[str, Any]]:
        """Persist a content-free reconciliation record before Core mutation."""
        operation_key = idempotency_slot(
            idempotency_key, domain=f"event-store-operation:{kind}"
        )
        payload_hash = _digest(semantic_payload)
        operations = self.state.setdefault("event_store_operations", {})
        operation = operations.get(operation_key)
        if operation:
            if operation["payload_hash"] != payload_hash:
                raise EventNodeIdempotencyConflict(
                    "event-store idempotency key was reused with changed semantics"
                )
            if operation["state"] == "core_committed":
                return deepcopy(operation["result"]), operation
            if operation["state"] == "failed":
                raise EventNodeStoreError(
                    "event-store reconciliation is permanently failed for this key"
                )
            operation["attempt_count"] = int(operation.get("attempt_count", 0)) + 1
            operation["reconciliation_state"] = "in_progress"
            self.control.save()
            return None, operation
        operation = {
            "operation_key": operation_key,
            "kind": kind,
            "payload_hash": payload_hash,
            "state": "prepared",
            "reconciliation_state": "prepared",
            "attempt_count": 0,
            "last_error": None,
            "effective": deepcopy(effective),
        }
        operations[operation_key] = operation
        self.control.save()
        self._fault(f"{kind}:after_prepare")
        operation["attempt_count"] = 1
        operation["reconciliation_state"] = "in_progress"
        self.control.save()
        return None, operation

    def _complete_operation(
        self,
        operation: dict[str, Any],
        result: dict[str, Any],
    ) -> dict[str, Any]:
        """Record the Core result; replay uses the same Core idempotency keys."""
        self._fault(f"{operation['kind']}:after_core_commit")
        operation["state"] = "core_committed"
        operation["reconciliation_state"] = "reconciled"
        operation["last_error"] = None
        operation["result"] = deepcopy(result)
        self.control.save()
        self._fault(f"{operation['kind']}:after_control_commit")
        return result

    def _record_core_failure(
        self,
        operation: dict[str, Any],
        error: Exception,
        *,
        retryable: bool,
    ) -> None:
        operation["state"] = "retry_pending" if retryable else "failed"
        operation["reconciliation_state"] = operation["state"]
        operation["last_error"] = type(error).__name__
        self.control.save()

    @staticmethod
    def _map_event(snapshot: dict[str, Any]) -> dict[str, Any]:
        event = deepcopy(snapshot)
        event["memory_type"] = "event"
        event["evidence_state"] = _evidence_state(snapshot)
        event["data_class"] = "structured"
        return event

    @staticmethod
    def _active_snapshot(
        core: CoreOracle,
        event_id: str,
        *,
        scope: EventNodeScope,
        space: str,
    ) -> dict[str, Any] | None:
        snapshots = core.event_revision_snapshots(event_id)
        if not snapshots:
            return None
        snapshot = snapshots[-1]
        if (
            snapshot.get("owner_id") != scope.owner_id
            or snapshot.get("space_id") != space
            or snapshot.get("state") != "active"
        ):
            return None
        return snapshot

    def get_event(
        self,
        event_id: str,
        *,
        scope: EventNodeScope,
        space: str,
        memory_type: str,
    ) -> dict[str, Any] | None:
        scope.require(space, memory_type)
        snapshot = self._active_snapshot(
            self.core, event_id, scope=scope, space=space
        )
        return self._map_event(snapshot) if snapshot else None

    def _ensure_contract(self, *, scope: EventNodeScope, space: str) -> str:
        identity = _digest({"owner_id": scope.owner_id, "space_id": space})[:24]
        contract_id = f"contract_mcp_{identity}"
        self.core.create_contract(
            {
                "contract_id": contract_id,
                "owner_id": scope.owner_id,
                "space_id": space,
                "device_id": "device_synthetic_mcp_adapter",
                "source_type": "agent",
                "permission_state": "granted",
                "revocation_state": "active",
                "purpose": "Synthetic MCP integration-test capture",
                "data_types": ["structured_event"],
                "processing_locations": ["device"],
                "sync_mode": "structured_sync",
            },
            idempotency_key=idempotency_slot(
                contract_id, domain="core-command:create-contract"
            ),
        )
        return contract_id

    def _capture_evidence(
        self,
        *,
        scope: EventNodeScope,
        space: str,
        content: str,
        event_time: str | None,
        evidence_state: str,
        sensitivity: str,
        now: str,
        idempotency_key: str,
    ) -> tuple[str, str]:
        contract_id = self._ensure_contract(scope=scope, space=space)
        content_hash = hashlib.sha256(content.encode("utf-8")).hexdigest()
        source = self.core.capture_source(
            {
                "contract_id": contract_id,
                "owner_id": scope.owner_id,
                "space_id": space,
                "device_id": "device_synthetic_mcp_adapter",
                "source_type": "agent",
                "capture_method": "agent_writeback",
                "acquired_at": now,
                "occurred_range": {
                    "start": event_time,
                    "timezone": "UTC",
                    "precision": "minute",
                },
                "content_hash": f"sha256_{content_hash}",
                "sensitivity": sensitivity,
                "sync_mode": "structured_sync",
                "retention": {
                    "retention_class": "structured_active",
                    "deletion_state": "active",
                },
                "source_locator": {
                    "locator_type": "app_private_object",
                    "opaque_locator_ref": (
                        "synthetic:mcp:"
                        + idempotency_slot(
                            idempotency_key, domain="source-locator"
                        )
                    ),
                    "display_label": "Synthetic MCP event source",
                    "state": "available",
                    "last_verified_at": now,
                },
            },
            idempotency_key=idempotency_slot(
                idempotency_key, domain="core-command:capture-source"
            ),
        )
        confidence = 1.0 if evidence_state in {"observed", "user_asserted"} else 0.5
        observation = self.core.create_observation(
            {
                "source_object_id": source["source_object_id"],
                "space_id": space,
                "kind": "agent_memory_statement",
                "value": {"content": content[:4000]},
                "time_range": {
                    "start": event_time,
                    "timezone": "UTC",
                    "precision": "minute",
                },
                "fact_status": evidence_state,
                "confidence": confidence,
                "parser_version": "mcp-core-store-synthetic-v1",
                "created_at": now,
            },
            idempotency_key=idempotency_slot(
                idempotency_key, domain="core-command:create-observation"
            ),
        )
        return source["source_object_id"], observation["observation_id"]

    def create_event(
        self,
        *,
        scope: EventNodeScope,
        space: str,
        content: str,
        event_time: str,
        event_type: str,
        evidence_state: str,
        fact_status: str,
        sensitivity: str,
        data_class: str,
        now: str,
        idempotency_key: str,
    ) -> dict[str, Any]:
        scope.require(space, "event")
        if data_class != "structured":
            raise EventNodeStoreError("Core test adapter accepts structured data only")
        cached, operation = self._begin_operation(
            kind="create_event",
            idempotency_key=idempotency_key,
            semantic_payload={
                "owner_id": scope.owner_id,
                "space": space,
                "content_hash": hashlib.sha256(content.encode("utf-8")).hexdigest(),
                "event_time": event_time,
                "event_type": event_type,
                "evidence_state": evidence_state,
                "fact_status": fact_status,
                "sensitivity": sensitivity,
                "data_class": data_class,
            },
            effective={"now": now, "event_time": event_time or now},
        )
        if cached is not None:
            return cached
        effective = operation["effective"]
        try:
            _, observation_id = self._capture_evidence(
                scope=scope,
                space=space,
                content=content,
                event_time=effective["event_time"],
                evidence_state=evidence_state,
                sensitivity=sensitivity,
                now=effective["now"],
                idempotency_key=idempotency_key,
            )
            confidence = (
                1.0 if evidence_state in {"observed", "user_asserted"} else 0.5
            )
            result = self.core.accept_event(
                {
                    "owner_id": scope.owner_id,
                    "space_id": space,
                    "event_type": event_type,
                    "time_range": {
                        "start": effective["event_time"],
                        "timezone": "UTC",
                        "precision": "minute",
                    },
                    "title": content[:200],
                    "description": content[:4000],
                    "fact_status": fact_status,
                    "sensitivity": sensitivity,
                    "actor": "agent",
                    "created_at": effective["now"],
                },
                [
                    {
                        "field": "description",
                        "observation_ids": [observation_id],
                        "confidence": confidence,
                        "status": evidence_state,
                    }
                ],
                idempotency_key=idempotency_slot(
                    idempotency_key, domain="core-command:accept-event"
                ),
            )
        except CoreOracleError as exc:
            self._record_core_failure(operation, exc, retryable=False)
            if isinstance(exc, IdempotencyConflict):
                raise EventNodeIdempotencyConflict(str(exc)) from exc
            raise EventNodeStoreError(str(exc)) from exc
        except sqlite3.OperationalError as exc:
            self._record_core_failure(operation, exc, retryable=True)
            raise EventNodeStoreError(str(exc)) from exc
        projected = {
            "object_type": "event",
            "event_id": result["event_id"],
            "revision": result["revision"],
        }
        return self._complete_operation(operation, projected)

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
        scope.require(space, "revision")
        snapshots = self.core.event_revision_snapshots(event_id)
        current = snapshots[-1] if snapshots else None
        if (
            not current
            or current.get("owner_id") != scope.owner_id
            or current.get("space_id") != space
            or current.get("state") != "active"
        ):
            raise EventNodeNotVisible("target event is not visible in this space")
        cached, operation = self._begin_operation(
            kind="append_revision",
            idempotency_key=idempotency_key,
            semantic_payload={
                "owner_id": scope.owner_id,
                "space": space,
                "event_id": event_id,
                "content_hash": hashlib.sha256(content.encode("utf-8")).hexdigest(),
                "evidence_state": evidence_state,
                "fact_status": fact_status,
            },
            effective={
                "now": now,
                "event_time": current["time_range"]["start"],
                "sensitivity": current.get("sensitivity", "personal"),
                "base_revision": int(current["revision"]),
            },
        )
        if cached is not None:
            return cached
        effective = operation["effective"]
        try:
            _, observation_id = self._capture_evidence(
                scope=scope,
                space=space,
                content=content,
                event_time=effective["event_time"],
                evidence_state=evidence_state,
                sensitivity=effective["sensitivity"],
                now=effective["now"],
                idempotency_key=idempotency_key,
            )
            confidence = (
                1.0 if evidence_state in {"observed", "user_asserted"} else 0.5
            )
            result = self.core.append_event_revision(
                event_id,
                base_revision=int(effective["base_revision"]),
                changes={
                    "description": content[:4000],
                    "fact_status": fact_status,
                },
                actor="agent",
                reason=(
                    "user_addendum"
                    if evidence_state == "user_asserted"
                    else "model_recompute"
                ),
                evidences=[
                    {
                        "field": "description",
                        "observation_ids": [observation_id],
                        "confidence": confidence,
                        "status": evidence_state,
                    }
                ],
                idempotency_key=idempotency_slot(
                    idempotency_key, domain="core-command:append-revision"
                ),
            )
        except RevisionConflict as exc:
            self._record_core_failure(operation, exc, retryable=False)
            raise EventNodeConflict(str(exc)) from exc
        except CoreOracleError as exc:
            self._record_core_failure(operation, exc, retryable=False)
            if isinstance(exc, IdempotencyConflict):
                raise EventNodeIdempotencyConflict(str(exc)) from exc
            raise EventNodeStoreError(str(exc)) from exc
        except sqlite3.OperationalError as exc:
            self._record_core_failure(operation, exc, retryable=True)
            raise EventNodeStoreError(str(exc)) from exc
        projected = {
            "object_type": "revision",
            "event_revision_id": result["event_revision_id"],
            "target_event_id": result["event_id"],
            "revision": result["revision"],
            "_undo_lineage": {
                "event_id": event_id,
                "space_id": space,
                "previous_revision": int(effective["base_revision"]),
                "created_revision_id": result["event_revision_id"],
            },
        }
        return self._complete_operation(operation, projected)

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
        scope.require(space, memory_type)
        if undo.get("space_id") != space or undo.get("created_object_type") != memory_type:
            raise EventNodeNotVisible("undo target exceeded its original space/type")
        event_id = str(undo["target_event_id"])
        snapshots = self.core.event_revision_snapshots(event_id)
        current = snapshots[-1] if snapshots else None
        if (
            not current
            or current.get("owner_id") != scope.owner_id
            or current.get("space_id") != space
        ):
            raise EventNodeNotVisible("undo event is not visible in this space")
        created_object_id = str(undo["created_object_id"])
        created_revision = int(undo.get("created_revision", 0))
        operation_key = idempotency_slot(
            idempotency_key,
            domain=f"event-store-operation:undo_{memory_type}",
        )
        operation_exists = operation_key in self.state.setdefault(
            "event_store_operations", {}
        )

        if memory_type == "event":
            if event_id != created_object_id or created_revision != 1:
                raise EventNodeNotVisible("undo event identity or lineage changed")
            if not operation_exists and (
                current.get("state") != "active"
                or int(current.get("revision", 0)) != created_revision
            ):
                raise EventNodeConflict("undo target is no longer the current revision")
            source_ids = list(current.get("source_object_ids") or [])
            if not source_ids:
                raise EventNodeNotVisible("undo event has no durable source lineage")
            cached, operation = self._begin_operation(
                kind="undo_event",
                idempotency_key=idempotency_key,
                semantic_payload={
                    "owner_id": scope.owner_id,
                    "space": space,
                    "event_id": event_id,
                    "created_object_id": created_object_id,
                    "created_revision": created_revision,
                },
                effective={"now": now, "source_ids": source_ids},
            )
            if cached is not None:
                return cached
            try:
                for index, source_id in enumerate(
                    operation["effective"]["source_ids"], 1
                ):
                    self.core.delete_source(
                        source_id,
                        reason="capture_undo",
                        idempotency_key=idempotency_slot(
                            idempotency_key,
                            domain=f"core-command:delete-source:{index}",
                        ),
                    )
            except CoreOracleError as exc:
                self._record_core_failure(operation, exc, retryable=False)
                if isinstance(exc, IdempotencyConflict):
                    raise EventNodeIdempotencyConflict(str(exc)) from exc
                raise EventNodeStoreError(str(exc)) from exc
            except sqlite3.OperationalError as exc:
                self._record_core_failure(operation, exc, retryable=True)
                raise EventNodeStoreError(str(exc)) from exc
            snapshots = self.core.event_revision_snapshots(event_id)
            if not snapshots or snapshots[-1].get("state") != "deleted":
                raise EventNodeStoreError("Core deletion did not tombstone the event")
            projected = {
                "state": "undone",
                "target_event_id": event_id,
                "undone_object_type": "event",
                "undone_object_id": created_object_id,
                "activity_visible": True,
            }
            return self._complete_operation(operation, projected)

        if memory_type != "revision":
            raise EventNodeNotVisible("unknown undo object type")
        lineage = undo.get("store_lineage")
        if (
            not isinstance(lineage, dict)
            or lineage.get("event_id") != event_id
            or lineage.get("space_id") != space
            or lineage.get("created_revision_id") != created_object_id
            or int(lineage.get("previous_revision", 0)) != created_revision - 1
        ):
            raise EventNodeNotVisible("undo revision lineage is incomplete")
        if not operation_exists and (
            current.get("state") != "active"
            or int(current.get("revision", 0)) != created_revision
            or current.get("revision_head_id") != created_object_id
        ):
            raise EventNodeConflict("undo revision is not the current durable head")
        cached, operation = self._begin_operation(
            kind="undo_revision",
            idempotency_key=idempotency_key,
            semantic_payload={
                "owner_id": scope.owner_id,
                "space": space,
                "event_id": event_id,
                "created_object_id": created_object_id,
                "created_revision": created_revision,
                "restore_revision": int(lineage["previous_revision"]),
            },
            effective={
                "now": now,
                "base_revision": created_revision,
                "restore_revision": int(lineage["previous_revision"]),
            },
        )
        if cached is not None:
            return cached
        try:
            result = self.core.undo_event(
                event_id,
                base_revision=int(operation["effective"]["base_revision"]),
                restore_revision=int(operation["effective"]["restore_revision"]),
                idempotency_key=idempotency_slot(
                    idempotency_key, domain="core-command:undo-event"
                ),
            )
        except RevisionConflict as exc:
            self._record_core_failure(operation, exc, retryable=False)
            raise EventNodeConflict(str(exc)) from exc
        except CoreOracleError as exc:
            self._record_core_failure(operation, exc, retryable=False)
            if isinstance(exc, IdempotencyConflict):
                raise EventNodeIdempotencyConflict(str(exc)) from exc
            raise EventNodeStoreError(str(exc)) from exc
        except sqlite3.OperationalError as exc:
            self._record_core_failure(operation, exc, retryable=True)
            raise EventNodeStoreError(str(exc)) from exc
        projected = {
            "state": "undone",
            "target_event_id": event_id,
            "undone_object_type": "revision",
            "undone_object_id": created_object_id,
            "compensation_revision_id": result["event_revision_id"],
            "activity_visible": True,
        }
        return self._complete_operation(operation, projected)

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
        if "event" not in requested_types:
            return [], False
        blocked = set(self.state.get("policy_blocked_events", []))
        events: list[dict[str, Any]] = []
        risk_filtered = False
        for space in sorted(requested_spaces):
            recall = self.core.recall(
                owner_id=scope.owner_id,
                space_id=space,
                keyword=query,
                limit=100,
            )
            for item in recall["results"]:
                event = self._map_event(item["event"])
                if event["event_id"] in blocked:
                    continue
                if not allow_high_risk and event.get("sensitivity") == "restricted":
                    risk_filtered = True
                    continue
                event_time = _parse(
                    event.get("time_range", {}).get("start") or event["created_at"]
                )
                if start_at and event_time < start_at:
                    continue
                if end_at and event_time > end_at:
                    continue
                events.append(event)
        events.sort(
            key=lambda item: item.get("updated_at") or item.get("created_at") or "",
            reverse=True,
        )
        return events, risk_filtered

    def set_policy_blocked(
        self,
        event_id: str,
        *,
        scope: EventNodeScope,
        space: str,
        memory_type: str,
    ) -> bool:
        scope.require(space, memory_type)
        if not self._active_snapshot(self.core, event_id, scope=scope, space=space):
            return False
        blocked = self.state.setdefault("policy_blocked_events", [])
        if event_id not in blocked:
            blocked.append(event_id)
        return True

    def enqueue_delivery(
        self,
        *,
        object_type: str,
        object_id: str,
        now: str,
        idempotency_key: str,
    ) -> dict[str, Any]:
        try:
            durable = self.core.enqueue_job(
                "sync",
                {"object_type": object_type, "object_id": object_id},
                priority=50,
                idempotency_key=idempotency_slot(
                    idempotency_key, domain="core-command:enqueue-sync"
                ),
            )
        except (CoreOracleError, IdempotencyConflict) as exc:
            raise EventNodeStoreError(str(exc)) from exc
        queued = {
            "queue_id": durable["job_id"],
            "object_type": object_type,
            "object_id": object_id,
            "state": "queued",
            "created_at": now,
        }
        if not any(
            item.get("queue_id") == queued["queue_id"] for item in self.state["queue"]
        ):
            self.state["queue"].append(queued)
        return deepcopy(queued)

    def queued_count(self) -> int:
        return len(self.state["queue"])
