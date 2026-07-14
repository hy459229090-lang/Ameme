"""Deterministic SQLite reference/oracle for Ameme's local memory loop.

This module intentionally models domain behavior, not production storage. It does
not provide encryption, Raw Vault durability, system adapters, networking, or
real-model processing.
"""

from __future__ import annotations

from contextlib import contextmanager
from copy import deepcopy
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import sqlite3
from typing import Any, Callable, Iterator

from .errors import IdempotencyConflict, InvariantViolation, NotFound, RevisionConflict
from .schema import SCHEMA_SQL, SCHEMA_VERSION


EVENT_FACT_STATUSES = {
    "confirmed",
    "high_confidence_inference",
    "low_confidence_candidate",
    "conflict",
    "user_asserted",
    "planned",
}
EVIDENCE_STATUSES = {
    "observed",
    "user_asserted",
    "inferred",
    "planned",
    "conflict",
    "unknown",
}
OBSERVATION_STATUSES = {"observed", "user_asserted", "planned", "inferred"}
LOCATOR_STATES = {
    "available",
    "moved",
    "missing",
    "permission_revoked",
    "deleted",
    "unknown",
}
DAY_COVERAGE_STATES = {
    "empty",
    "sparse",
    "ready_local",
    "processing",
    "partial",
    "syncing",
    "permission_limited",
    "offline",
}


def _canonical(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def _hash(value: Any) -> str:
    return hashlib.sha256(_canonical(value).encode("utf-8")).hexdigest()


def _default_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def _local_date(time_range: dict[str, Any]) -> str:
    start = time_range.get("start")
    if not isinstance(start, str) or len(start) < 10:
        raise InvariantViolation("time_range.start must be an ISO-8601 timestamp")
    return start[:10]


class CoreOracle:
    """A non-production, deterministic reference implementation and test oracle."""

    def __init__(
        self,
        database: str | Path = ":memory:",
        *,
        now: Callable[[], str] | None = None,
    ) -> None:
        self._now = now or _default_now
        self.connection = sqlite3.connect(str(database), isolation_level=None)
        self.connection.row_factory = sqlite3.Row
        self.connection.execute("PRAGMA foreign_keys = ON")
        self.connection.execute("PRAGMA journal_mode = WAL")
        self.connection.execute("PRAGMA synchronous = FULL")
        self.connection.executescript(SCHEMA_SQL)
        self.connection.execute(
            "INSERT OR IGNORE INTO schema_migrations(migration_id, applied_at) VALUES (?, ?)",
            (SCHEMA_VERSION, self._now()),
        )

    def close(self) -> None:
        self.connection.close()

    def __enter__(self) -> "CoreOracle":
        return self

    def __exit__(self, *_: object) -> None:
        self.close()

    @contextmanager
    def _transaction(self) -> Iterator[sqlite3.Connection]:
        self.connection.execute("BEGIN IMMEDIATE")
        try:
            yield self.connection
        except Exception:
            self.connection.rollback()
            raise
        else:
            self.connection.commit()

    def _next_id(self, prefix: str) -> str:
        row = self.connection.execute(
            "SELECT next_value FROM id_sequence WHERE singleton = 1"
        ).fetchone()
        value = int(row["next_value"])
        self.connection.execute(
            "UPDATE id_sequence SET next_value = ? WHERE singleton = 1", (value + 1,)
        )
        return f"{prefix}_{value:08d}"

    def _command(
        self,
        command: str,
        idempotency_key: str,
        payload: dict[str, Any],
        action: Callable[[], dict[str, Any]],
    ) -> dict[str, Any]:
        if len(idempotency_key) < 8:
            raise InvariantViolation("idempotency_key must contain at least 8 characters")
        payload_hash = _hash({"command": command, "payload": payload})
        with self._transaction():
            prior = self.connection.execute(
                "SELECT command, payload_hash, result_json FROM idempotency_records "
                "WHERE idempotency_key = ?",
                (idempotency_key,),
            ).fetchone()
            if prior:
                if prior["command"] != command or prior["payload_hash"] != payload_hash:
                    raise IdempotencyConflict(
                        f"IDEMPOTENCY_CONFLICT for key {idempotency_key}"
                    )
                return json.loads(prior["result_json"])
            result = action()
            self.connection.execute(
                "INSERT INTO idempotency_records VALUES (?, ?, ?, ?, ?)",
                (idempotency_key, command, payload_hash, _canonical(result), self._now()),
            )
            return result

    def create_contract(
        self, contract: dict[str, Any], *, idempotency_key: str
    ) -> dict[str, Any]:
        payload = deepcopy(contract)

        def action() -> dict[str, Any]:
            required = {
                "contract_id",
                "owner_id",
                "space_id",
                "device_id",
                "source_type",
                "permission_state",
                "revocation_state",
            }
            missing = sorted(required - payload.keys())
            if missing:
                raise InvariantViolation(f"contract missing fields: {missing}")
            self.connection.execute(
                "INSERT INTO acquisition_contracts VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                (
                    payload["contract_id"],
                    payload["owner_id"],
                    payload["space_id"],
                    payload["device_id"],
                    payload["source_type"],
                    payload["permission_state"],
                    payload["revocation_state"],
                    _canonical(payload),
                ),
            )
            return {"contract_id": payload["contract_id"], "state": "active"}

        return self._command("create_contract", idempotency_key, payload, action)

    def set_day_coverage(
        self,
        *,
        owner_id: str,
        space_id: str,
        local_date: str,
        timezone_name: str,
        coverage_state: str,
        partial_reasons: list[str] | None = None,
        idempotency_key: str,
    ) -> dict[str, Any]:
        reasons = sorted(set(partial_reasons or []))
        payload = {
            "owner_id": owner_id,
            "space_id": space_id,
            "local_date": local_date,
            "timezone": timezone_name,
            "coverage_state": coverage_state,
            "partial_reasons": reasons,
        }

        def action() -> dict[str, Any]:
            if coverage_state not in DAY_COVERAGE_STATES:
                raise InvariantViolation(f"unsupported coverage state: {coverage_state}")
            ledger_id = self._ledger_identity(owner_id, space_id, local_date, timezone_name)
            prior = self.connection.execute(
                "SELECT version FROM day_coverage_inputs WHERE day_ledger_id = ?",
                (ledger_id,),
            ).fetchone()
            version = int(prior["version"]) + 1 if prior else 1
            self.connection.execute(
                "INSERT INTO day_coverage_inputs VALUES (?, ?, ?, ?, ?) "
                "ON CONFLICT(day_ledger_id) DO UPDATE SET coverage_state=excluded.coverage_state, "
                "partial_reasons_json=excluded.partial_reasons_json, version=excluded.version, "
                "updated_at=excluded.updated_at",
                (ledger_id, coverage_state, _canonical(reasons), version, self._now()),
            )
            self._refresh_ledger(owner_id, space_id, local_date, timezone_name)
            return {"day_ledger_id": ledger_id, "coverage_state": coverage_state}

        return self._command("set_day_coverage", idempotency_key, payload, action)

    def capture_source(
        self,
        source: dict[str, Any],
        *,
        idempotency_key: str,
    ) -> dict[str, Any]:
        payload = deepcopy(source)

        def action() -> dict[str, Any]:
            contract = self.connection.execute(
                "SELECT * FROM acquisition_contracts WHERE contract_id = ?",
                (payload.get("contract_id"),),
            ).fetchone()
            if not contract:
                raise NotFound("acquisition contract not found")
            if contract["permission_state"] not in {"limited", "foreground", "background_limited", "granted"}:
                raise InvariantViolation("acquisition contract is not permitted")
            if contract["revocation_state"] != "active":
                raise InvariantViolation("acquisition contract is not active")
            if payload.get("owner_id") != contract["owner_id"] or payload.get("space_id") != contract["space_id"]:
                raise InvariantViolation("source owner/space must match its contract")
            if payload.get("source_type") != contract["source_type"]:
                raise InvariantViolation("SourceObject source_type must match its contract")
            if not payload.get("sync_mode") or not payload.get("retention"):
                raise InvariantViolation("SourceObject requires sync_mode and retention policy")
            time_range = payload.get("occurred_range") or {}
            local_date = _local_date(time_range) if time_range else None
            locator = payload.get("source_locator")
            locator_state = (locator or {}).get("state", "unknown")
            if locator_state not in LOCATOR_STATES:
                raise InvariantViolation(f"unsupported locator state: {locator_state}")
            source_id = self._next_id("src")
            record = {
                "schema_version": 1,
                "source_object_id": source_id,
                "processing_state": "processed",
                **payload,
            }
            self.connection.execute(
                "INSERT INTO source_objects VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (
                    source_id,
                    payload["contract_id"],
                    payload["owner_id"],
                    payload["space_id"],
                    payload["device_id"],
                    payload["source_type"],
                    payload["capture_method"],
                    payload["acquired_at"],
                    time_range.get("start"),
                    local_date,
                    time_range.get("timezone"),
                    payload.get("content_hash"),
                    "processed",
                    payload.get("sensitivity", "personal"),
                    locator_state,
                    _canonical(locator) if locator else None,
                    _canonical(record),
                ),
            )
            if locator:
                self._append_locator_history(
                    source_id, locator_state, "captured", locator, payload["acquired_at"]
                )
            return {
                "source_object_id": source_id,
                "durability": "capture_saved_local",
                "processing_state": "processed",
            }

        return self._command("capture_source", idempotency_key, payload, action)

    def add_user_addendum(
        self,
        addendum: dict[str, Any],
        *,
        idempotency_key: str,
    ) -> dict[str, Any]:
        payload = deepcopy(addendum)

        def action() -> dict[str, Any]:
            source_id = payload.get("source_object_id")
            if source_id:
                source = self._active_source(source_id)
                if source["owner_id"] != payload["owner_id"] or source["space_id"] != payload["space_id"]:
                    raise InvariantViolation("addendum owner/space must match its source")
            if not payload.get("target_id"):
                raise InvariantViolation("UserAddendum target_id is required")
            if not payload.get("text"):
                raise InvariantViolation("UserAddendum text is required")
            addendum_id = self._next_id("add")
            self.connection.execute(
                "INSERT INTO user_addenda VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (
                    addendum_id,
                    source_id,
                    payload["owner_id"],
                    payload["space_id"],
                    payload["text"],
                    payload["submitted_at"],
                    _canonical(payload.get("event_time")) if payload.get("event_time") else None,
                    payload["target_type"],
                    payload["target_id"],
                    payload.get("assertion_scope", "fact"),
                    payload.get("sensitivity", "personal"),
                ),
            )
            if source_id:
                self._lineage("user_addendum", addendum_id, "source_object", source_id, "derived_from")
            return {"addendum_id": addendum_id, "state": "durable"}

        return self._command("add_user_addendum", idempotency_key, payload, action)

    def create_observation(
        self,
        observation: dict[str, Any],
        *,
        idempotency_key: str,
    ) -> dict[str, Any]:
        payload = deepcopy(observation)

        def action() -> dict[str, Any]:
            source = self._active_source(payload["source_object_id"])
            if source["space_id"] != payload["space_id"]:
                raise InvariantViolation("Observation cannot cross spaces")
            if payload["fact_status"] not in OBSERVATION_STATUSES:
                raise InvariantViolation("unsupported Observation fact_status")
            confidence = float(payload["confidence"])
            if not 0 <= confidence <= 1:
                raise InvariantViolation("confidence must be between 0 and 1")
            observation_id = self._next_id("obs")
            created_at = payload.get("created_at", self._now())
            self.connection.execute(
                "INSERT INTO observations VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (
                    observation_id,
                    payload["source_object_id"],
                    payload["space_id"],
                    payload["kind"],
                    _canonical(payload["value"]),
                    _canonical(payload.get("time_range")) if payload.get("time_range") else None,
                    payload["fact_status"],
                    confidence,
                    payload["parser_version"],
                    created_at,
                ),
            )
            self._lineage(
                "observation", observation_id, "source_object", payload["source_object_id"], "observed_from"
            )
            return {"observation_id": observation_id, "fact_status": payload["fact_status"]}

        return self._command("create_observation", idempotency_key, payload, action)

    def accept_event(
        self,
        event: dict[str, Any],
        evidences: list[dict[str, Any]],
        *,
        idempotency_key: str,
    ) -> dict[str, Any]:
        payload = {"event": deepcopy(event), "evidences": deepcopy(evidences)}

        def action() -> dict[str, Any]:
            event_data = payload["event"]
            self._validate_fact_status(event_data["fact_status"])
            normalized_evidence, source_ids = self._validate_evidence(
                event_data["space_id"], payload["evidences"]
            )
            if not normalized_evidence:
                raise InvariantViolation("Event requires field-level evidence")
            candidate_id = self._next_id("cand")
            event_id = self._next_id("evt")
            revision_id = self._next_id("evr")
            now = event_data.get("created_at", self._now())
            local_date = _local_date(event_data["time_range"])
            candidate = {
                "schema_version": 1,
                "candidate_id": candidate_id,
                "space_id": event_data["space_id"],
                "event_type": event_data["event_type"],
                "time_range": event_data["time_range"],
                "field_evidence": normalized_evidence,
                "status": "accepted",
                "created_at": now,
            }
            self.connection.execute(
                "INSERT INTO event_candidates VALUES (?, ?, ?, 'accepted', ?, ?)",
                (candidate_id, event_data["owner_id"], event_data["space_id"], _canonical(candidate), now),
            )
            snapshot = {
                "schema_version": 1,
                "event_id": event_id,
                "owner_id": event_data["owner_id"],
                "space_id": event_data["space_id"],
                "event_type": event_data["event_type"],
                "time_range": event_data["time_range"],
                "local_date": local_date,
                "title": event_data["title"],
                "description": event_data.get("description", ""),
                "subject": event_data.get("subject", "user"),
                "fact_status": event_data["fact_status"],
                "field_evidence": normalized_evidence,
                "source_object_ids": source_ids,
                "revision_head_id": revision_id,
                "revision": 1,
                "sensitivity": event_data.get("sensitivity", "personal"),
                "state": "conflict" if event_data["fact_status"] == "conflict" else "active",
                "created_at": now,
                "updated_at": now,
            }
            self._insert_event_revision(
                revision_id,
                event_id,
                1,
                None,
                event_data.get("actor", "system"),
                "initial",
                {"candidate_id": candidate_id},
                snapshot,
                None,
                normalized_evidence,
                now,
            )
            self._upsert_event_current(snapshot)
            self._lineage("event", event_id, "event_candidate", candidate_id, "derived_from")
            for evidence in normalized_evidence:
                for observation_id in evidence["observation_ids"]:
                    self._lineage("event", event_id, "observation", observation_id, "derived_from")
            for source_id in source_ids:
                self._lineage("event", event_id, "source_object", source_id, "derived_from")
            self._refresh_ledger(
                event_data["owner_id"],
                event_data["space_id"],
                local_date,
                event_data["time_range"].get("timezone", "UTC"),
            )
            return {
                "candidate_id": candidate_id,
                "event_id": event_id,
                "event_revision_id": revision_id,
                "revision": 1,
                "fact_status": snapshot["fact_status"],
            }

        return self._command("accept_event", idempotency_key, payload, action)

    def append_event_revision(
        self,
        event_id: str,
        *,
        base_revision: int,
        changes: dict[str, Any],
        actor: str,
        reason: str,
        idempotency_key: str,
        evidences: list[dict[str, Any]] | None = None,
    ) -> dict[str, Any]:
        payload = {
            "event_id": event_id,
            "base_revision": base_revision,
            "changes": deepcopy(changes),
            "actor": actor,
            "reason": reason,
            "evidences": deepcopy(evidences),
        }

        def action() -> dict[str, Any]:
            return self._append_event_revision_tx(
                event_id,
                base_revision,
                payload["changes"],
                actor,
                reason,
                payload["evidences"],
            )

        return self._command("append_event_revision", idempotency_key, payload, action)

    def record_conflict(
        self,
        event_id: str,
        *,
        competing_base_revision: int,
        competing_changes: dict[str, Any],
        idempotency_key: str,
    ) -> dict[str, Any]:
        payload = {
            "event_id": event_id,
            "competing_base_revision": competing_base_revision,
            "competing_changes": deepcopy(competing_changes),
        }

        def action() -> dict[str, Any]:
            current = self._event_current(event_id)
            if competing_base_revision >= current["revision"]:
                raise InvariantViolation("a conflict must reference a stale competing base")
            changes = {
                "fact_status": "conflict",
                "state": "conflict",
                "conflict": {
                    "competing_base_revision": competing_base_revision,
                    "competing_changes": competing_changes,
                },
            }
            return self._append_event_revision_tx(
                event_id,
                current["revision"],
                changes,
                "system",
                "source_update",
                None,
                conflict=changes["conflict"],
            )

        return self._command("record_conflict", idempotency_key, payload, action)

    def undo_event(
        self,
        event_id: str,
        *,
        base_revision: int,
        restore_revision: int,
        idempotency_key: str,
    ) -> dict[str, Any]:
        payload = {
            "event_id": event_id,
            "base_revision": base_revision,
            "restore_revision": restore_revision,
        }

        def action() -> dict[str, Any]:
            current = self._event_current(event_id)
            if current["revision"] != base_revision:
                raise RevisionConflict(
                    f"REVISION_CONFLICT current={current['revision']} base={base_revision}"
                )
            row = self.connection.execute(
                "SELECT snapshot_json FROM event_revisions WHERE event_id = ? AND revision = ?",
                (event_id, restore_revision),
            ).fetchone()
            if not row:
                raise NotFound("restore revision not found")
            target = json.loads(row["snapshot_json"])
            changes = {
                "title": target["title"],
                "description": target.get("description", ""),
                "fact_status": target["fact_status"],
                "state": target["state"],
                "time_range": target["time_range"],
                "undo_of_revision": restore_revision,
            }
            return self._append_event_revision_tx(
                event_id, base_revision, changes, "user", "user_edit", None
            )

        return self._command("undo_event", idempotency_key, payload, action)

    def create_episode(
        self,
        episode: dict[str, Any],
        event_refs: list[dict[str, Any]],
        *,
        idempotency_key: str,
    ) -> dict[str, Any]:
        payload = {"episode": deepcopy(episode), "event_refs": deepcopy(event_refs)}

        def action() -> dict[str, Any]:
            data = payload["episode"]
            if not payload["event_refs"]:
                raise InvariantViolation("Episode requires at least one Event")
            normalized_refs: list[dict[str, Any]] = []
            for ref in payload["event_refs"]:
                event = self._event_current(ref["event_id"])
                if event["owner_id"] != data["owner_id"] or event["space_id"] != data["space_id"]:
                    raise InvariantViolation("Episode cannot combine owner/space boundaries")
                normalized_refs.append(
                    {
                        "event_id": event["event_id"],
                        "revision": event["revision"],
                        "role": ref.get("role", "middle"),
                    }
                )
            episode_id = self._next_id("epi")
            revision_id = self._next_id("epr")
            now = data.get("created_at", self._now())
            snapshot = {
                "schema_version": 1,
                "episode_id": episode_id,
                "owner_id": data["owner_id"],
                "space_id": data["space_id"],
                "title": data["title"],
                "time_range": data["time_range"],
                "local_date": _local_date(data["time_range"]),
                "event_refs": normalized_refs,
                "revision_head_id": revision_id,
                "revision": 1,
                "sensitivity": data.get("sensitivity", "personal"),
                "state": "active",
                "created_at": now,
                "updated_at": now,
            }
            self._insert_episode_revision(
                revision_id, episode_id, 1, None, "system", "initial", {}, snapshot, now
            )
            self._upsert_episode_current(snapshot)
            for ref in normalized_refs:
                self._lineage("episode", episode_id, "event", ref["event_id"], "part_of")
            self._refresh_ledger(
                data["owner_id"],
                data["space_id"],
                snapshot["local_date"],
                data["time_range"].get("timezone", "UTC"),
            )
            return {"episode_id": episode_id, "episode_revision_id": revision_id, "revision": 1}

        return self._command("create_episode", idempotency_key, payload, action)

    def degrade_source_locator(
        self,
        source_object_id: str,
        *,
        state: str,
        reason: str,
        idempotency_key: str,
    ) -> dict[str, Any]:
        payload = {"source_object_id": source_object_id, "state": state, "reason": reason}

        def action() -> dict[str, Any]:
            if state not in LOCATOR_STATES - {"available"}:
                raise InvariantViolation("degradation must use a non-available locator state")
            source = self._source(source_object_id)
            locator = json.loads(source["locator_json"]) if source["locator_json"] else None
            if locator:
                locator["state"] = state
                locator["last_verified_at"] = self._now()
            self.connection.execute(
                "UPDATE source_objects SET locator_state = ?, locator_json = ? WHERE source_object_id = ?",
                (state, _canonical(locator) if locator else None, source_object_id),
            )
            self._append_locator_history(source_object_id, state, reason, locator, self._now())
            return {"source_object_id": source_object_id, "locator_state": state}

        return self._command("degrade_source_locator", idempotency_key, payload, action)

    def delete_source(
        self,
        source_object_id: str,
        *,
        idempotency_key: str,
        reason: str = "user_delete",
    ) -> dict[str, Any]:
        payload = {"source_object_id": source_object_id, "reason": reason}

        def action() -> dict[str, Any]:
            source = self._source(source_object_id)
            if source["processing_state"] == "deleted":
                raise InvariantViolation("source is already deleted without an idempotent replay key")
            affected_events = [
                row["event_id"]
                for row in self.connection.execute(
                    "SELECT DISTINCT event_id FROM field_evidence WHERE source_object_id = ?",
                    (source_object_id,),
                )
            ]
            tombstone_id = self._next_id("tmb")
            now = self._now()
            self.connection.execute(
                "INSERT INTO tombstones VALUES (?, 'source_object', ?, ?, ?)",
                (tombstone_id, source_object_id, reason, now),
            )
            self.connection.execute(
                "UPDATE source_objects SET processing_state = 'deleted', locator_state = 'deleted' "
                "WHERE source_object_id = ?",
                (source_object_id,),
            )
            self._append_locator_history(source_object_id, "deleted", reason, None, now)
            touched_dates: set[tuple[str, str, str, str]] = set()
            for event_id in affected_events:
                current = self._event_current(event_id)
                self.connection.execute(
                    "INSERT OR IGNORE INTO tombstones VALUES (?, 'event', ?, ?, ?)",
                    (self._next_id("tmb"), event_id, reason, now),
                )
                self._append_event_revision_tx(
                    event_id,
                    current["revision"],
                    {"state": "deleted", "deletion_source_id": source_object_id},
                    "system",
                    "deletion_recompute",
                    None,
                    refresh=False,
                )
                snapshot = self._event_current(event_id)
                touched_dates.add(
                    (
                        snapshot["owner_id"],
                        snapshot["space_id"],
                        snapshot["local_date"],
                        snapshot["time_range"].get("timezone", "UTC"),
                    )
                )
            self._recompute_episodes_after_deletion(set(affected_events), touched_dates)
            for owner_id, space_id, local_date, timezone_name in touched_dates:
                self._refresh_ledger(owner_id, space_id, local_date, timezone_name)
            job_id = self._next_id("del")
            affected = {
                "source_object_ids": [source_object_id],
                "event_ids": affected_events,
                "tombstone_precedence": True,
            }
            proof_hash = _hash(affected)
            self.connection.execute(
                "INSERT INTO deletion_jobs VALUES (?, ?, ?, 'source_object', ?, 'completed', ?, ?, ?, ?)",
                (
                    job_id,
                    source["owner_id"],
                    source["space_id"],
                    source_object_id,
                    _canonical(affected),
                    proof_hash,
                    now,
                    now,
                ),
            )
            return {
                "deletion_job_id": job_id,
                "state": "completed",
                "affected": affected,
                "proof_hash": proof_hash,
            }

        return self._command("delete_source", idempotency_key, payload, action)

    def today(
        self,
        *,
        owner_id: str,
        space_id: str,
        local_date: str,
        timezone_name: str,
    ) -> dict[str, Any]:
        ledger = self.connection.execute(
            "SELECT * FROM day_ledgers WHERE owner_id = ? AND space_id = ? "
            "AND local_date = ? AND timezone = ?",
            (owner_id, space_id, local_date, timezone_name),
        ).fetchone()
        if not ledger:
            with self._transaction():
                self._refresh_ledger(owner_id, space_id, local_date, timezone_name)
            ledger = self.connection.execute(
                "SELECT * FROM day_ledgers WHERE owner_id = ? AND space_id = ? "
                "AND local_date = ? AND timezone = ?",
                (owner_id, space_id, local_date, timezone_name),
            ).fetchone()
        entries = [
            dict(row)
            for row in self.connection.execute(
                "SELECT object_type, object_id, revision, sort_key FROM day_ledger_entries "
                "WHERE day_ledger_id = ? ORDER BY sort_key, object_type, object_id",
                (ledger["day_ledger_id"],),
            )
        ]
        events = [
            self._event_with_evidence(json.loads(row["snapshot_json"]))
            for row in self.connection.execute(
                "SELECT snapshot_json FROM events_current WHERE owner_id = ? AND space_id = ? "
                "AND local_date = ? AND state != 'deleted' ORDER BY occurred_start, event_id",
                (owner_id, space_id, local_date),
            )
        ]
        episodes = [
            json.loads(row["snapshot_json"])
            for row in self.connection.execute(
                "SELECT snapshot_json FROM episodes_current WHERE owner_id = ? AND space_id = ? "
                "AND local_date = ? AND state != 'deleted' ORDER BY occurred_start, episode_id",
                (owner_id, space_id, local_date),
            )
        ]
        ledger_object = {
            "schema_version": 1,
            "day_ledger_id": ledger["day_ledger_id"],
            "owner_id": owner_id,
            "space_id": space_id,
            "local_date": local_date,
            "timezone": timezone_name,
            "revision": ledger["revision"],
            "entries": entries,
            "coverage_state": ledger["coverage_state"],
            "partial_reasons": json.loads(ledger["partial_reasons_json"]),
            "summary_state": ledger["summary_state"],
            "updated_at": ledger["updated_at"],
        }
        range_state = (
            "partial"
            if ledger["coverage_state"] in {"partial", "syncing", "permission_limited", "offline"}
            else "ready_local"
        )
        return {
            "schema_version": 1,
            "ledger": ledger_object,
            "events": events,
            "episodes": episodes,
            "range_state": range_state,
            "generated_at": self._now(),
        }

    def recall(
        self,
        *,
        owner_id: str,
        space_id: str,
        date_from: str | None = None,
        date_to: str | None = None,
        keyword: str | None = None,
        limit: int = 20,
    ) -> dict[str, Any]:
        if not 1 <= limit <= 100:
            raise InvariantViolation("Recall limit must be in [1, 100]")
        params: list[Any] = [owner_id, space_id]
        where = ["e.owner_id = ?", "e.space_id = ?", "e.state != 'deleted'"]
        join = ""
        if date_from:
            where.append("e.local_date >= ?")
            params.append(date_from)
        if date_to:
            where.append("e.local_date <= ?")
            params.append(date_to)
        if keyword:
            match_query = self._fts_query(keyword)
            if not match_query:
                return self._empty_recall()
            join = "JOIN recall_fts f ON f.event_id = e.event_id"
            where.append("recall_fts MATCH ?")
            params.append(match_query)
        params.append(limit)
        rows = self.connection.execute(
            f"SELECT e.* FROM events_current e {join} WHERE {' AND '.join(where)} "
            "ORDER BY e.local_date DESC, e.occurred_start DESC, e.event_id LIMIT ?",
            params,
        ).fetchall()
        results: list[dict[str, Any]] = []
        partial_reasons: set[str] = set()
        for rank, row in enumerate(rows, 1):
            event = self._event_with_evidence(json.loads(row["snapshot_json"]))
            ledger = self.connection.execute(
                "SELECT day_ledger_id, revision, coverage_state, partial_reasons_json "
                "FROM day_ledgers WHERE owner_id = ? AND space_id = ? AND local_date = ? "
                "ORDER BY timezone LIMIT 1",
                (owner_id, space_id, row["local_date"]),
            ).fetchone()
            if ledger and ledger["coverage_state"] in {"partial", "syncing", "permission_limited", "offline"}:
                partial_reasons.update(json.loads(ledger["partial_reasons_json"]))
            results.append(
                {
                    "object_type": "event",
                    "object_id": row["event_id"],
                    "revision": row["revision"],
                    "local_date": row["local_date"],
                    "rank": rank,
                    "event": event,
                    "day_evidence": {
                        "day_ledger_id": ledger["day_ledger_id"] if ledger else None,
                        "day_ledger_revision": ledger["revision"] if ledger else None,
                        "coverage_state": ledger["coverage_state"] if ledger else "empty",
                    },
                }
            )
        if not results:
            return self._empty_recall()
        return {
            "schema_version": 1,
            "range_state": "partial" if partial_reasons else "complete_for_requested_scope",
            "partial_reasons": sorted(partial_reasons),
            "results": results,
            "generated_at": self._now(),
        }

    def rebuild(self) -> dict[str, Any]:
        """Rebuild all current, day-ledger, and FTS projections from revisions."""
        with self._transaction():
            self.connection.execute("DELETE FROM recall_fts")
            self.connection.execute("DELETE FROM day_ledger_entries")
            self.connection.execute("DELETE FROM day_ledgers")
            self.connection.execute("DELETE FROM events_current")
            self.connection.execute("DELETE FROM episodes_current")
            event_rows = self.connection.execute(
                "SELECT r.* FROM event_revisions r JOIN ("
                "SELECT event_id, MAX(revision) revision FROM event_revisions GROUP BY event_id"
                ") h ON h.event_id = r.event_id AND h.revision = r.revision "
                "ORDER BY r.event_id"
            ).fetchall()
            dates: set[tuple[str, str, str, str]] = set()
            for row in event_rows:
                snapshot = json.loads(row["snapshot_json"])
                tombstoned = self.connection.execute(
                    "SELECT 1 FROM tombstones WHERE target_type = 'event' AND target_id = ?",
                    (snapshot["event_id"],),
                ).fetchone()
                if tombstoned:
                    snapshot["state"] = "deleted"
                self._upsert_event_current(snapshot)
                dates.add(
                    (
                        snapshot["owner_id"],
                        snapshot["space_id"],
                        snapshot["local_date"],
                        snapshot["time_range"].get("timezone", "UTC"),
                    )
                )
            episode_rows = self.connection.execute(
                "SELECT r.* FROM episode_revisions r JOIN ("
                "SELECT episode_id, MAX(revision) revision FROM episode_revisions GROUP BY episode_id"
                ") h ON h.episode_id = r.episode_id AND h.revision = r.revision "
                "ORDER BY r.episode_id"
            ).fetchall()
            for row in episode_rows:
                snapshot = json.loads(row["snapshot_json"])
                tombstoned = self.connection.execute(
                    "SELECT 1 FROM tombstones WHERE target_type = 'episode' AND target_id = ?",
                    (snapshot["episode_id"],),
                ).fetchone()
                if tombstoned:
                    snapshot["state"] = "deleted"
                self._upsert_episode_current(snapshot)
                dates.add(
                    (
                        snapshot["owner_id"],
                        snapshot["space_id"],
                        snapshot["local_date"],
                        snapshot["time_range"].get("timezone", "UTC"),
                    )
                )
            for identity in self.connection.execute("SELECT * FROM ledger_identities"):
                dates.add(
                    (
                        identity["owner_id"],
                        identity["space_id"],
                        identity["local_date"],
                        identity["timezone"],
                    )
                )
            for owner_id, space_id, local_date, timezone_name in sorted(dates):
                self._refresh_ledger(owner_id, space_id, local_date, timezone_name)
            active_events = self.connection.execute(
                "SELECT COUNT(*) count FROM events_current WHERE state != 'deleted'"
            ).fetchone()["count"]
            return {
                "events_replayed": len(event_rows),
                "active_events": active_events,
                "episodes_replayed": len(episode_rows),
                "days_rebuilt": len(dates),
                "tombstones_applied": self.connection.execute(
                    "SELECT COUNT(*) count FROM tombstones"
                ).fetchone()["count"],
            }

    def table_count(self, table: str) -> int:
        allowed = {
            "source_objects",
            "source_locator_history",
            "user_addenda",
            "observations",
            "event_candidates",
            "event_revisions",
            "events_current",
            "field_evidence",
            "episode_revisions",
            "episodes_current",
            "day_ledgers",
            "lineage_edges",
            "tombstones",
            "deletion_jobs",
            "idempotency_records",
        }
        if table not in allowed:
            raise InvariantViolation("table_count only accepts an allow-listed table")
        return int(self.connection.execute(f"SELECT COUNT(*) count FROM {table}").fetchone()["count"])

    def locator_history(self, source_object_id: str) -> list[dict[str, Any]]:
        return [
            dict(row)
            for row in self.connection.execute(
                "SELECT state, reason, recorded_at FROM source_locator_history "
                "WHERE source_object_id = ? ORDER BY rowid",
                (source_object_id,),
            )
        ]

    def event_revision_snapshots(self, event_id: str) -> list[dict[str, Any]]:
        return [
            json.loads(row["snapshot_json"])
            for row in self.connection.execute(
                "SELECT snapshot_json FROM event_revisions WHERE event_id = ? ORDER BY revision",
                (event_id,),
            )
        ]

    def _source(self, source_id: str) -> sqlite3.Row:
        row = self.connection.execute(
            "SELECT * FROM source_objects WHERE source_object_id = ?", (source_id,)
        ).fetchone()
        if not row:
            raise NotFound("SourceObject not found")
        return row

    def _active_source(self, source_id: str) -> sqlite3.Row:
        row = self._source(source_id)
        if row["processing_state"] == "deleted":
            raise InvariantViolation("SourceObject is tombstoned/deleted")
        return row

    def _event_current(self, event_id: str) -> dict[str, Any]:
        row = self.connection.execute(
            "SELECT snapshot_json FROM events_current WHERE event_id = ?", (event_id,)
        ).fetchone()
        if not row:
            raise NotFound("Event not found")
        return json.loads(row["snapshot_json"])

    def _validate_fact_status(self, status: str) -> None:
        if status not in EVENT_FACT_STATUSES:
            raise InvariantViolation(f"unsupported Event fact_status: {status}")

    def _validate_evidence(
        self, space_id: str, evidences: list[dict[str, Any]]
    ) -> tuple[list[dict[str, Any]], list[str]]:
        normalized: list[dict[str, Any]] = []
        source_ids: set[str] = set()
        for evidence in evidences:
            observation_ids = sorted(set(evidence.get("observation_ids", [])))
            if not evidence.get("field") or not observation_ids:
                raise InvariantViolation("each fact field requires Observation evidence")
            if evidence.get("status") not in EVIDENCE_STATUSES:
                raise InvariantViolation("unsupported FieldEvidence status")
            confidence = float(evidence.get("confidence", -1))
            if not 0 <= confidence <= 1:
                raise InvariantViolation("FieldEvidence confidence must be in [0, 1]")
            for observation_id in observation_ids:
                row = self.connection.execute(
                    "SELECT o.space_id, o.source_object_id, s.processing_state "
                    "FROM observations o JOIN source_objects s "
                    "ON s.source_object_id = o.source_object_id WHERE o.observation_id = ?",
                    (observation_id,),
                ).fetchone()
                if not row:
                    raise NotFound(f"Observation not found: {observation_id}")
                if row["space_id"] != space_id:
                    raise InvariantViolation("FieldEvidence cannot cross spaces")
                if row["processing_state"] == "deleted":
                    raise InvariantViolation("FieldEvidence cannot use a deleted source")
                source_ids.add(row["source_object_id"])
            normalized.append(
                {
                    "field": evidence["field"],
                    "observation_ids": observation_ids,
                    "confidence": confidence,
                    "status": evidence["status"],
                }
            )
        return normalized, sorted(source_ids)

    def _insert_event_revision(
        self,
        revision_id: str,
        event_id: str,
        revision: int,
        base_revision: int | None,
        actor: str,
        reason: str,
        changes: dict[str, Any],
        snapshot: dict[str, Any],
        conflict: dict[str, Any] | None,
        evidences: list[dict[str, Any]],
        created_at: str,
    ) -> None:
        self.connection.execute(
            "INSERT INTO event_revisions(event_revision_id, event_id, revision, base_revision, "
            "actor, reason, changes_json, snapshot_json, conflict_json, created_at) "
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (
                revision_id,
                event_id,
                revision,
                base_revision,
                actor,
                reason,
                _canonical(changes),
                _canonical(snapshot),
                _canonical(conflict) if conflict else None,
                created_at,
            ),
        )
        for evidence in evidences:
            for observation_id in evidence["observation_ids"]:
                observation = self.connection.execute(
                    "SELECT source_object_id FROM observations WHERE observation_id = ?",
                    (observation_id,),
                ).fetchone()
                self.connection.execute(
                    "INSERT INTO field_evidence VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    (
                        revision_id,
                        event_id,
                        revision,
                        evidence["field"],
                        observation_id,
                        observation["source_object_id"],
                        evidence["confidence"],
                        evidence["status"],
                    ),
                )

    def _append_event_revision_tx(
        self,
        event_id: str,
        base_revision: int,
        changes: dict[str, Any],
        actor: str,
        reason: str,
        evidences: list[dict[str, Any]] | None,
        *,
        conflict: dict[str, Any] | None = None,
        refresh: bool = True,
    ) -> dict[str, Any]:
        current = self._event_current(event_id)
        if current["revision"] != base_revision:
            raise RevisionConflict(
                f"REVISION_CONFLICT current={current['revision']} base={base_revision}"
            )
        old_local_date = current["local_date"]
        snapshot = deepcopy(current)
        allowed = {"title", "description", "fact_status", "state", "time_range", "sensitivity"}
        for key in allowed:
            if key in changes:
                snapshot[key] = deepcopy(changes[key])
        self._validate_fact_status(snapshot["fact_status"])
        if snapshot["fact_status"] == "conflict":
            snapshot["state"] = "conflict"
        elif snapshot["state"] == "conflict":
            snapshot["state"] = "active"
        if "time_range" in changes:
            snapshot["local_date"] = _local_date(snapshot["time_range"])
        if evidences is None:
            normalized_evidence = deepcopy(current["field_evidence"])
            source_ids = deepcopy(current["source_object_ids"])
        else:
            normalized_evidence, source_ids = self._validate_evidence(
                snapshot["space_id"], evidences
            )
        revision = base_revision + 1
        revision_id = self._next_id("evr")
        now = self._now()
        snapshot.update(
            {
                "field_evidence": normalized_evidence,
                "source_object_ids": source_ids,
                "revision": revision,
                "revision_head_id": revision_id,
                "updated_at": now,
            }
        )
        self._insert_event_revision(
            revision_id,
            event_id,
            revision,
            base_revision,
            actor,
            reason,
            changes,
            snapshot,
            conflict,
            normalized_evidence,
            now,
        )
        self._upsert_event_current(snapshot)
        self._lineage("event_revision", revision_id, "event", event_id, "revises")
        if refresh:
            timezone_name = snapshot["time_range"].get("timezone", "UTC")
            self._refresh_ledger(
                snapshot["owner_id"], snapshot["space_id"], snapshot["local_date"], timezone_name
            )
            if old_local_date != snapshot["local_date"]:
                self._refresh_ledger(
                    snapshot["owner_id"], snapshot["space_id"], old_local_date, timezone_name
                )
        return {
            "event_id": event_id,
            "event_revision_id": revision_id,
            "revision": revision,
            "fact_status": snapshot["fact_status"],
            "state": snapshot["state"],
        }

    def _upsert_event_current(self, snapshot: dict[str, Any]) -> None:
        self.connection.execute(
            "INSERT INTO events_current VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
            "ON CONFLICT(event_id) DO UPDATE SET owner_id=excluded.owner_id, "
            "space_id=excluded.space_id, local_date=excluded.local_date, "
            "occurred_start=excluded.occurred_start, title=excluded.title, "
            "description=excluded.description, fact_status=excluded.fact_status, "
            "state=excluded.state, revision=excluded.revision, "
            "revision_head_id=excluded.revision_head_id, snapshot_json=excluded.snapshot_json",
            (
                snapshot["event_id"],
                snapshot["owner_id"],
                snapshot["space_id"],
                snapshot["local_date"],
                snapshot["time_range"]["start"],
                snapshot["title"],
                snapshot.get("description", ""),
                snapshot["fact_status"],
                snapshot["state"],
                snapshot["revision"],
                snapshot["revision_head_id"],
                _canonical(snapshot),
            ),
        )
        self.connection.execute("DELETE FROM recall_fts WHERE event_id = ?", (snapshot["event_id"],))
        if snapshot["state"] != "deleted":
            self.connection.execute(
                "INSERT INTO recall_fts(event_id, title, description) VALUES (?, ?, ?)",
                (snapshot["event_id"], snapshot["title"], snapshot.get("description", "")),
            )

    def _insert_episode_revision(
        self,
        revision_id: str,
        episode_id: str,
        revision: int,
        base_revision: int | None,
        actor: str,
        reason: str,
        changes: dict[str, Any],
        snapshot: dict[str, Any],
        created_at: str,
    ) -> None:
        self.connection.execute(
            "INSERT INTO episode_revisions(episode_revision_id, episode_id, revision, "
            "base_revision, actor, reason, changes_json, snapshot_json, created_at) "
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            (
                revision_id,
                episode_id,
                revision,
                base_revision,
                actor,
                reason,
                _canonical(changes),
                _canonical(snapshot),
                created_at,
            ),
        )

    def _upsert_episode_current(self, snapshot: dict[str, Any]) -> None:
        self.connection.execute(
            "INSERT INTO episodes_current VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) "
            "ON CONFLICT(episode_id) DO UPDATE SET owner_id=excluded.owner_id, "
            "space_id=excluded.space_id, local_date=excluded.local_date, "
            "occurred_start=excluded.occurred_start, state=excluded.state, "
            "revision=excluded.revision, revision_head_id=excluded.revision_head_id, "
            "snapshot_json=excluded.snapshot_json",
            (
                snapshot["episode_id"],
                snapshot["owner_id"],
                snapshot["space_id"],
                snapshot["local_date"],
                snapshot["time_range"]["start"],
                snapshot["state"],
                snapshot["revision"],
                snapshot["revision_head_id"],
                _canonical(snapshot),
            ),
        )

    def _recompute_episodes_after_deletion(
        self,
        deleted_event_ids: set[str],
        touched_dates: set[tuple[str, str, str, str]],
    ) -> None:
        rows = self.connection.execute(
            "SELECT snapshot_json FROM episodes_current WHERE state != 'deleted'"
        ).fetchall()
        for row in rows:
            current = json.loads(row["snapshot_json"])
            if not any(ref["event_id"] in deleted_event_ids for ref in current["event_refs"]):
                continue
            remaining = [
                ref for ref in current["event_refs"] if ref["event_id"] not in deleted_event_ids
            ]
            revision = current["revision"] + 1
            revision_id = self._next_id("epr")
            now = self._now()
            snapshot = deepcopy(current)
            snapshot["event_refs"] = remaining
            snapshot["state"] = "active" if remaining else "deleted"
            snapshot["revision"] = revision
            snapshot["revision_head_id"] = revision_id
            snapshot["updated_at"] = now
            self._insert_episode_revision(
                revision_id,
                snapshot["episode_id"],
                revision,
                current["revision"],
                "system",
                "deletion_recompute",
                {"removed_event_ids": sorted(deleted_event_ids)},
                snapshot,
                now,
            )
            if not remaining:
                self.connection.execute(
                    "INSERT OR IGNORE INTO tombstones VALUES (?, 'episode', ?, 'dependency_deleted', ?)",
                    (self._next_id("tmb"), snapshot["episode_id"], now),
                )
            self._upsert_episode_current(snapshot)
            touched_dates.add(
                (
                    snapshot["owner_id"],
                    snapshot["space_id"],
                    snapshot["local_date"],
                    snapshot["time_range"].get("timezone", "UTC"),
                )
            )

    def _ledger_identity(
        self, owner_id: str, space_id: str, local_date: str, timezone_name: str
    ) -> str:
        row = self.connection.execute(
            "SELECT day_ledger_id FROM ledger_identities WHERE owner_id = ? AND space_id = ? "
            "AND local_date = ? AND timezone = ?",
            (owner_id, space_id, local_date, timezone_name),
        ).fetchone()
        if row:
            return row["day_ledger_id"]
        ledger_id = self._next_id("day")
        self.connection.execute(
            "INSERT INTO ledger_identities VALUES (?, ?, ?, ?, ?)",
            (ledger_id, owner_id, space_id, local_date, timezone_name),
        )
        return ledger_id

    def _refresh_ledger(
        self, owner_id: str, space_id: str, local_date: str, timezone_name: str
    ) -> None:
        ledger_id = self._ledger_identity(owner_id, space_id, local_date, timezone_name)
        episode_rows = self.connection.execute(
            "SELECT * FROM episodes_current WHERE owner_id = ? AND space_id = ? "
            "AND local_date = ? AND state != 'deleted' ORDER BY occurred_start, episode_id",
            (owner_id, space_id, local_date),
        ).fetchall()
        episode_event_ids: set[str] = set()
        for row in episode_rows:
            snapshot = json.loads(row["snapshot_json"])
            episode_event_ids.update(ref["event_id"] for ref in snapshot["event_refs"])
        event_rows = self.connection.execute(
            "SELECT * FROM events_current WHERE owner_id = ? AND space_id = ? "
            "AND local_date = ? AND state != 'deleted' ORDER BY occurred_start, event_id",
            (owner_id, space_id, local_date),
        ).fetchall()
        entries: list[tuple[str, str, int, str]] = []
        for row in episode_rows:
            entries.append(("episode", row["episode_id"], row["revision"], row["occurred_start"]))
        for row in event_rows:
            if row["event_id"] not in episode_event_ids:
                entries.append(("event", row["event_id"], row["revision"], row["occurred_start"]))
        entries.sort(key=lambda item: (item[3], item[0], item[1]))
        coverage = self.connection.execute(
            "SELECT * FROM day_coverage_inputs WHERE day_ledger_id = ?", (ledger_id,)
        ).fetchone()
        if coverage:
            coverage_state = coverage["coverage_state"]
            reasons = json.loads(coverage["partial_reasons_json"])
            coverage_version = int(coverage["version"])
        else:
            coverage_state = "sparse" if entries else "empty"
            reasons = []
            coverage_version = 0
        revision_count = 0
        for row in self.connection.execute("SELECT snapshot_json FROM event_revisions"):
            snapshot = json.loads(row["snapshot_json"])
            if (
                snapshot["owner_id"] == owner_id
                and snapshot["space_id"] == space_id
                and snapshot["local_date"] == local_date
            ):
                revision_count += 1
        for row in self.connection.execute("SELECT snapshot_json FROM episode_revisions"):
            snapshot = json.loads(row["snapshot_json"])
            if (
                snapshot["owner_id"] == owner_id
                and snapshot["space_id"] == space_id
                and snapshot["local_date"] == local_date
            ):
                revision_count += 1
        ledger_revision = max(1, 1 + revision_count + coverage_version)
        summary_state = "insufficient" if not entries else "absent"
        now = self._now()
        self.connection.execute(
            "INSERT INTO day_ledgers VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
            "ON CONFLICT(day_ledger_id) DO UPDATE SET revision=excluded.revision, "
            "coverage_state=excluded.coverage_state, partial_reasons_json=excluded.partial_reasons_json, "
            "summary_state=excluded.summary_state, updated_at=excluded.updated_at",
            (
                ledger_id,
                owner_id,
                space_id,
                local_date,
                timezone_name,
                ledger_revision,
                coverage_state,
                _canonical(reasons),
                summary_state,
                now,
            ),
        )
        self.connection.execute(
            "DELETE FROM day_ledger_entries WHERE day_ledger_id = ?", (ledger_id,)
        )
        self.connection.executemany(
            "INSERT INTO day_ledger_entries VALUES (?, ?, ?, ?, ?)",
            [(ledger_id, *entry) for entry in entries],
        )

    def _lineage(
        self,
        from_type: str,
        from_id: str,
        to_type: str,
        to_id: str,
        relationship: str,
    ) -> None:
        existing = self.connection.execute(
            "SELECT lineage_edge_id FROM lineage_edges WHERE from_type = ? AND from_id = ? "
            "AND to_type = ? AND to_id = ? AND relationship = ?",
            (from_type, from_id, to_type, to_id, relationship),
        ).fetchone()
        if existing:
            return
        self.connection.execute(
            "INSERT INTO lineage_edges VALUES (?, ?, ?, ?, ?, ?, 'core-reference-v1', ?)",
            (
                self._next_id("lin"),
                from_type,
                from_id,
                to_type,
                to_id,
                relationship,
                self._now(),
            ),
        )

    def _append_locator_history(
        self,
        source_id: str,
        state: str,
        reason: str,
        locator: dict[str, Any] | None,
        recorded_at: str,
    ) -> None:
        self.connection.execute(
            "INSERT INTO source_locator_history VALUES (?, ?, ?, ?, ?, ?)",
            (
                self._next_id("loc"),
                source_id,
                state,
                reason,
                _canonical(locator) if locator else None,
                recorded_at,
            ),
        )

    def _event_with_evidence(self, event: dict[str, Any]) -> dict[str, Any]:
        evidence = [
            {
                "field": row["field_name"],
                "observation_id": row["observation_id"],
                "source_object_id": row["source_object_id"],
                "confidence": row["confidence"],
                "status": row["status"],
            }
            for row in self.connection.execute(
                "SELECT field_name, observation_id, source_object_id, confidence, status "
                "FROM field_evidence WHERE event_id = ? AND revision = ? "
                "ORDER BY field_name, observation_id",
                (event["event_id"], event["revision"]),
            )
        ]
        result = deepcopy(event)
        result["evidence_detail"] = evidence
        result["lineage"] = [
            dict(row)
            for row in self.connection.execute(
                "SELECT from_type, from_id, to_type, to_id, relationship, processor_version "
                "FROM lineage_edges WHERE from_type = 'event' AND from_id = ? "
                "ORDER BY to_type, to_id",
                (event["event_id"],),
            )
        ]
        return result

    @staticmethod
    def _fts_query(keyword: str) -> str:
        tokens = re.findall(r"\w+", keyword, flags=re.UNICODE)
        return " AND ".join(f'"{token.replace(chr(34), chr(34) * 2)}"' for token in tokens)

    def _empty_recall(self) -> dict[str, Any]:
        return {
            "schema_version": 1,
            "range_state": "empty",
            "partial_reasons": [],
            "results": [],
            "generated_at": self._now(),
        }
