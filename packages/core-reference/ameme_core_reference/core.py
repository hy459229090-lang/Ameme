"""Deterministic SQLite reference/oracle for Ameme's local memory loop.

This module intentionally models domain behavior, not production storage. Its
Raw Vault and durable queues are failure-testable references, not mobile runtime,
SQLCipher, Keychain/Keystore, networking, or release-readiness evidence.
"""

from __future__ import annotations

from contextlib import contextmanager
from copy import deepcopy
from datetime import datetime, timedelta, timezone
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
from typing import Any, Callable, Iterator

from .errors import (
    IdempotencyConflict,
    InvariantViolation,
    NotFound,
    QueueLeaseConflict,
    RawIntegrityError,
    RawQuotaExceeded,
    RevisionConflict,
)
from .raw_vault import FaultInjector, KeyProvider, RawVaultIO
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
RAW_RETENTION_CLASSES = {
    "ephemeral_recovery",
    "user_retained",
    "derived_rebuildable",
}
EXPIRING_RAW_RETENTION_CLASSES = {"ephemeral_recovery", "derived_rebuildable"}
QUEUE_TYPES = {"processing", "sync", "delete", "export", "recompute"}


def _canonical(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def _hash(value: Any) -> str:
    return hashlib.sha256(_canonical(value).encode("utf-8")).hexdigest()


def _default_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def _parse_timestamp(value: str) -> datetime:
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except (AttributeError, TypeError, ValueError) as exc:
        raise InvariantViolation("timestamp must be valid ISO-8601") from exc
    if parsed.tzinfo is None:
        raise InvariantViolation("timestamp must include an offset")
    return parsed


def _utc_timestamp(value: str) -> str:
    return _parse_timestamp(value).astimezone(timezone.utc).isoformat(timespec="seconds")


def _plus_seconds(value: str, seconds: int) -> str:
    return (_parse_timestamp(value) + timedelta(seconds=seconds)).isoformat(
        timespec="seconds"
    )


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
        raw_vault_dir: str | Path | None = None,
        raw_key_provider: KeyProvider | None = None,
        raw_quota_bytes: int = 64 * 1024 * 1024,
        raw_fault_injector: FaultInjector | None = None,
    ) -> None:
        if raw_quota_bytes <= 0:
            raise InvariantViolation("raw_quota_bytes must be positive")
        self._now = now or _default_now
        self._raw_quota_bytes = raw_quota_bytes
        self._raw_vault = (
            RawVaultIO(
                raw_vault_dir,
                key_provider=raw_key_provider,
                fault_injector=raw_fault_injector,
            )
            if raw_vault_dir is not None
            else None
        )
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
        if self._raw_vault is not None:
            self._recover_raw_vault()

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

    def store_raw(
        self,
        source_object_id: str,
        content: bytes,
        *,
        key_id: str,
        mime_type: str,
        retention_class: str,
        expires_at: str | None,
        selected_for_sync: bool = False,
        idempotency_key: str,
    ) -> dict[str, Any]:
        if not isinstance(content, bytes) or not content:
            raise InvariantViolation("Raw Vault content must be non-empty bytes")
        normalized_expires_at = (
            _utc_timestamp(expires_at) if expires_at is not None else None
        )
        payload = {
            "source_object_id": source_object_id,
            "content_sha256": hashlib.sha256(content).hexdigest(),
            "content_size": len(content),
            "key_id": key_id,
            "mime_type": mime_type,
            "retention_class": retention_class,
            "expires_at": normalized_expires_at,
            "selected_for_sync": selected_for_sync,
        }
        written_relative_path: str | None = None

        def action() -> dict[str, Any]:
            nonlocal written_relative_path
            vault = self._require_raw_vault()
            source = self._active_source(source_object_id)
            if not key_id or not mime_type:
                raise InvariantViolation("Raw Vault key_id and mime_type are required")
            if retention_class not in RAW_RETENTION_CLASSES:
                raise InvariantViolation("unsupported Raw Vault retention class")
            created_at = _utc_timestamp(self._now())
            if retention_class in EXPIRING_RAW_RETENTION_CLASSES:
                if normalized_expires_at is None:
                    raise InvariantViolation("expiring Raw Vault content requires expires_at")
                if _parse_timestamp(normalized_expires_at) <= _parse_timestamp(created_at):
                    raise InvariantViolation("Raw Vault expires_at must be in the future")
            projected_size = len(content) + 16
            used = self.raw_vault_usage()["ciphertext_bytes"]
            if used + projected_size > self._raw_quota_bytes:
                raise RawQuotaExceeded("Raw Vault quota would be exceeded")
            raw_object_id = self._next_id("raw")
            relative_path = f"objects/{raw_object_id}.agcm"
            nonce = self._unique_raw_nonce()
            nonce_b64 = base64.b64encode(nonce).decode("ascii")
            aad_value = {
                "raw_object_id": raw_object_id,
                "source_object_id": source_object_id,
                "space_id": source["space_id"],
                "created_at": created_at,
                "schema_version": 1,
            }
            aad = _canonical(aad_value).encode("utf-8")
            written = vault.write_encrypted(
                relative_path=relative_path,
                plaintext=content,
                key_id=key_id,
                nonce=nonce,
                aad=aad,
            )
            written_relative_path = relative_path
            try:
                self.connection.execute(
                    "INSERT INTO raw_manifests VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    (
                        raw_object_id,
                        source_object_id,
                        source["space_id"],
                        relative_path,
                        key_id,
                        nonce_b64,
                        _canonical(aad_value),
                        written["plaintext_sha256"],
                        written["ciphertext_sha256"],
                        written["plaintext_size"],
                        written["ciphertext_size"],
                        mime_type,
                        retention_class,
                        created_at,
                        normalized_expires_at,
                        "active",
                        1 if selected_for_sync else 0,
                        "ready",
                        created_at,
                    ),
                )
                self._lineage(
                    "raw_manifest",
                    raw_object_id,
                    "source_object",
                    source_object_id,
                    "derived_from",
                )
                return self.raw_manifest(raw_object_id)
            except Exception:
                vault.cleanup_file(relative_path)
                written_relative_path = None
                raise

        try:
            return self._command("store_raw", idempotency_key, payload, action)
        except Exception:
            if written_relative_path is not None and self._raw_vault is not None:
                try:
                    self._raw_vault.cleanup_file(written_relative_path)
                except OSError:
                    pass
            raise

    def read_raw(self, raw_object_id: str) -> bytes:
        vault = self._require_raw_vault()
        row = self._raw_manifest_row(raw_object_id)
        if row["state"] != "ready" or row["deletion_state"] != "active":
            raise NotFound("Raw Vault object is not available")
        plaintext = vault.read_encrypted(
            relative_path=row["relative_path"],
            key_id=row["key_id"],
            nonce=base64.b64decode(row["nonce_b64"], validate=True),
            aad=row["aad_json"].encode("utf-8"),
            expected_ciphertext_sha256=row["ciphertext_sha256"],
        )
        if hashlib.sha256(plaintext).hexdigest() != row["plaintext_sha256"]:
            raise RawIntegrityError("Raw Vault plaintext hash mismatch")
        return plaintext

    def raw_manifest(self, raw_object_id: str) -> dict[str, Any]:
        row = self._raw_manifest_row(raw_object_id)
        return {
            "raw_object_id": row["raw_object_id"],
            "source_object_id": row["source_object_id"],
            "space_id": row["space_id"],
            "relative_path": row["relative_path"],
            "key_id": row["key_id"],
            "nonce_b64": row["nonce_b64"],
            "aad": json.loads(row["aad_json"]),
            "plaintext_sha256": row["plaintext_sha256"],
            "ciphertext_sha256": row["ciphertext_sha256"],
            "plaintext_size": row["plaintext_size"],
            "ciphertext_size": row["ciphertext_size"],
            "mime_type": row["mime_type"],
            "retention_class": row["retention_class"],
            "created_at": row["created_at"],
            "expires_at": row["expires_at"],
            "deletion_state": row["deletion_state"],
            "selected_for_sync": bool(row["selected_for_sync"]),
            "state": row["state"],
            "updated_at": row["updated_at"],
        }

    def raw_vault_usage(self) -> dict[str, int]:
        row = self.connection.execute(
            "SELECT COUNT(*) object_count, COALESCE(SUM(ciphertext_size), 0) ciphertext_bytes "
            "FROM raw_manifests WHERE state = 'ready' AND deletion_state = 'active'"
        ).fetchone()
        return {
            "object_count": int(row["object_count"]),
            "ciphertext_bytes": int(row["ciphertext_bytes"]),
            "quota_bytes": self._raw_quota_bytes,
        }

    def purge_expired_raw(
        self, *, at: str, idempotency_key: str
    ) -> dict[str, Any]:
        normalized_at = _utc_timestamp(at)
        payload = {"at": normalized_at}

        def action() -> dict[str, Any]:
            rows = self.connection.execute(
                "SELECT raw_object_id FROM raw_manifests WHERE state = 'ready' "
                "AND deletion_state = 'active' AND retention_class IN (?, ?) "
                "AND expires_at IS NOT NULL AND expires_at <= ? ORDER BY raw_object_id",
                (*sorted(EXPIRING_RAW_RETENTION_CLASSES), normalized_at),
            ).fetchall()
            deleted: list[str] = []
            failed: list[str] = []
            for row in rows:
                try:
                    self._delete_raw_manifest_tx(
                        row["raw_object_id"], "expired", normalized_at
                    )
                except OSError:
                    failed.append(row["raw_object_id"])
                else:
                    deleted.append(row["raw_object_id"])
            return {"deleted_raw_object_ids": deleted, "failed_raw_object_ids": failed}

        return self._command("purge_expired_raw", idempotency_key, payload, action)

    def enqueue_job(
        self,
        queue_type: str,
        job_payload: dict[str, Any],
        *,
        priority: int,
        max_attempts: int = 5,
        available_at: str | None = None,
        idempotency_key: str,
    ) -> dict[str, Any]:
        payload = {
            "queue_type": queue_type,
            "job_payload": deepcopy(job_payload),
            "priority": priority,
            "max_attempts": max_attempts,
            "available_at": available_at,
        }

        def action() -> dict[str, Any]:
            return self._enqueue_job_tx(
                queue_type,
                payload["job_payload"],
                priority=priority,
                max_attempts=max_attempts,
                available_at=available_at,
                queue_idempotency_key=idempotency_key,
            )

        return self._command("enqueue_job", idempotency_key, payload, action)

    def lease_next_job(
        self,
        *,
        worker_id: str,
        lease_seconds: int,
        queue_types: list[str] | None = None,
        idempotency_key: str,
    ) -> dict[str, Any]:
        requested_types = sorted(set(queue_types or QUEUE_TYPES))
        payload = {
            "worker_id": worker_id,
            "lease_seconds": lease_seconds,
            "queue_types": requested_types,
        }

        def action() -> dict[str, Any]:
            if not worker_id:
                raise InvariantViolation("worker_id is required")
            if not 1 <= lease_seconds <= 3600:
                raise InvariantViolation("lease_seconds must be between 1 and 3600")
            if not requested_types or not set(requested_types) <= QUEUE_TYPES:
                raise InvariantViolation("unsupported durable queue type")
            now = _utc_timestamp(self._now())
            self._release_expired_leases_tx(now)
            placeholders = ",".join("?" for _ in requested_types)
            row = self.connection.execute(
                "SELECT * FROM durable_jobs WHERE state = 'queued' AND available_at <= ? "
                f"AND queue_type IN ({placeholders}) "
                "ORDER BY priority DESC, created_at, job_id LIMIT 1",
                (now, *requested_types),
            ).fetchone()
            if not row:
                return {"job": None}
            lease_expires_at = _plus_seconds(now, lease_seconds)
            self.connection.execute(
                "UPDATE durable_jobs SET state = 'leased', lease_owner = ?, "
                "lease_expires_at = ?, attempt_count = attempt_count + 1, updated_at = ? "
                "WHERE job_id = ? AND state = 'queued'",
                (worker_id, lease_expires_at, now, row["job_id"]),
            )
            return {"job": self.get_job(row["job_id"])}

        return self._command("lease_next_job", idempotency_key, payload, action)

    def complete_job(
        self,
        job_id: str,
        *,
        worker_id: str,
        result: dict[str, Any] | None,
        idempotency_key: str,
    ) -> dict[str, Any]:
        payload = {
            "job_id": job_id,
            "worker_id": worker_id,
            "result": deepcopy(result),
        }

        def action() -> dict[str, Any]:
            row = self._leased_job(job_id, worker_id)
            now = _utc_timestamp(self._now())
            if _parse_timestamp(row["lease_expires_at"]) <= _parse_timestamp(now):
                raise QueueLeaseConflict("job lease expired before completion")
            if row["queue_type"] == "delete":
                deletion_job_id = json.loads(row["payload_json"]).get(
                    "deletion_job_id"
                )
                deletion = self._deletion_job_row(deletion_job_id)
                affected = json.loads(deletion["affected_json"])
                if deletion["state"] != "completed" or not affected.get(
                    "proof_complete"
                ):
                    raise QueueLeaseConflict(
                        "delete queue cannot complete before deletion proof"
                    )
            self.connection.execute(
                "UPDATE durable_jobs SET state = 'completed', lease_owner = NULL, "
                "lease_expires_at = NULL, result_json = ?, updated_at = ? WHERE job_id = ?",
                (_canonical(result) if result is not None else None, now, job_id),
            )
            return self.get_job(job_id)

        return self._command("complete_job", idempotency_key, payload, action)

    def fail_job(
        self,
        job_id: str,
        *,
        worker_id: str,
        error_code: str,
        base_backoff_seconds: int = 5,
        max_backoff_seconds: int = 3600,
        idempotency_key: str,
    ) -> dict[str, Any]:
        payload = {
            "job_id": job_id,
            "worker_id": worker_id,
            "error_code": error_code,
            "base_backoff_seconds": base_backoff_seconds,
            "max_backoff_seconds": max_backoff_seconds,
        }

        def action() -> dict[str, Any]:
            row = self._leased_job(job_id, worker_id)
            if not error_code or len(error_code) > 64:
                raise InvariantViolation("error_code must be a short non-content code")
            if base_backoff_seconds <= 0 or max_backoff_seconds < base_backoff_seconds:
                raise InvariantViolation("invalid durable queue backoff")
            now = _utc_timestamp(self._now())
            if _parse_timestamp(row["lease_expires_at"]) <= _parse_timestamp(now):
                raise QueueLeaseConflict("job lease expired before failure handling")
            attempts = int(row["attempt_count"])
            if attempts >= int(row["max_attempts"]):
                state = "failed"
                available_at = now
            else:
                state = "queued"
                delay = min(
                    max_backoff_seconds,
                    base_backoff_seconds * (2 ** max(0, attempts - 1)),
                )
                available_at = _plus_seconds(now, delay)
            self.connection.execute(
                "UPDATE durable_jobs SET state = ?, lease_owner = NULL, "
                "lease_expires_at = NULL, available_at = ?, last_error_code = ?, "
                "updated_at = ? WHERE job_id = ?",
                (state, available_at, error_code, now, job_id),
            )
            return self.get_job(job_id)

        return self._command("fail_job", idempotency_key, payload, action)

    def get_job(self, job_id: str) -> dict[str, Any]:
        row = self.connection.execute(
            "SELECT * FROM durable_jobs WHERE job_id = ?", (job_id,)
        ).fetchone()
        if not row:
            raise NotFound("durable job not found")
        return {
            "job_id": row["job_id"],
            "queue_type": row["queue_type"],
            "payload": json.loads(row["payload_json"]),
            "priority": row["priority"],
            "state": row["state"],
            "lease_owner": row["lease_owner"],
            "lease_expires_at": row["lease_expires_at"],
            "attempt_count": row["attempt_count"],
            "max_attempts": row["max_attempts"],
            "available_at": row["available_at"],
            "last_error_code": row["last_error_code"],
            "result": json.loads(row["result_json"]) if row["result_json"] else None,
            "created_at": row["created_at"],
            "updated_at": row["updated_at"],
        }

    def get_deletion_job(self, deletion_job_id: str) -> dict[str, Any]:
        row = self._deletion_job_row(deletion_job_id)
        queue = self.connection.execute(
            "SELECT job_id FROM durable_jobs WHERE queue_idempotency_key = ?",
            (f"deletion:{deletion_job_id}",),
        ).fetchone()
        return {
            "deletion_job_id": row["deletion_job_id"],
            "queue_job_id": queue["job_id"] if queue else None,
            "owner_id": row["owner_id"],
            "space_id": row["space_id"],
            "target_type": row["target_type"],
            "target_id": row["target_id"],
            "state": row["state"],
            "affected": json.loads(row["affected_json"]),
            "proof_hash": row["proof_hash"],
            "created_at": row["created_at"],
            "updated_at": row["updated_at"],
        }

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
            target_evidence, target_source_ids = self._validate_evidence(
                target["space_id"], deepcopy(target["field_evidence"])
            )
            if target_source_ids != sorted(target["source_object_ids"]):
                raise InvariantViolation(
                    "restore revision evidence does not match its source snapshot"
                )
            changes = {
                "title": target["title"],
                "description": target.get("description", ""),
                "fact_status": target["fact_status"],
                "state": target["state"],
                "time_range": target["time_range"],
                "undo_of_revision": restore_revision,
            }
            return self._append_event_revision_tx(
                event_id,
                base_revision,
                changes,
                "user",
                "user_edit",
                target_evidence,
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
        required_replica_ids: list[str] | None = None,
    ) -> dict[str, Any]:
        replicas = sorted(set(required_replica_ids or []))
        payload = {
            "source_object_id": source_object_id,
            "reason": reason,
            "required_replica_ids": replicas,
        }

        def action() -> dict[str, Any]:
            source = self._source(source_object_id)
            if source["processing_state"] == "deleted":
                raise InvariantViolation("source is already deleted without an idempotent replay key")
            impact = self.deletion_impact(source_object_id)
            affected_events = impact["event_ids"]
            tombstone_id = self._next_id("tmb")
            now = _utc_timestamp(self._now())
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
            retained_event_ids: list[str] = []
            deleted_event_ids: list[str] = []
            for event_id in affected_events:
                current = self._event_current(event_id)
                remaining_evidence = self._evidence_after_source_deletion(
                    current["field_evidence"], source_object_id
                )
                if remaining_evidence:
                    fact_status = self._fact_status_from_evidence(remaining_evidence)
                    title, description = self._safe_recomputed_event_text(
                        current, remaining_evidence
                    )
                    self._append_event_revision_tx(
                        event_id,
                        current["revision"],
                        {
                            "title": title,
                            "description": description,
                            "fact_status": fact_status,
                            "state": "conflict" if fact_status == "conflict" else "active",
                            "deletion_source_id": source_object_id,
                        },
                        "system",
                        "deletion_recompute",
                        remaining_evidence,
                        refresh=False,
                    )
                    retained_event_ids.append(event_id)
                else:
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
                    deleted_event_ids.append(event_id)
                snapshot = self._event_current(event_id)
                touched_dates.add(
                    (
                        snapshot["owner_id"],
                        snapshot["space_id"],
                        snapshot["local_date"],
                        snapshot["time_range"].get("timezone", "UTC"),
                    )
                )
            self._recompute_episodes_after_deletion(set(deleted_event_ids), touched_dates)
            for owner_id, space_id, local_date, timezone_name in touched_dates:
                self._refresh_ledger(owner_id, space_id, local_date, timezone_name)
            failed_raw_object_ids: list[str] = []
            for raw_object_id in impact["raw_object_ids"]:
                try:
                    self._delete_raw_manifest_tx(raw_object_id, reason, now)
                except Exception:
                    failed_raw_object_ids.append(raw_object_id)
            job_id = self._next_id("del")
            local_cleanup_complete = not failed_raw_object_ids and self._raw_cleanup_complete(
                impact["raw_object_ids"]
            )
            state = (
                "completed"
                if local_cleanup_complete and not replicas
                else "partial_failed"
            )
            affected = {
                "source_object_ids": [source_object_id],
                "raw_object_ids": impact["raw_object_ids"],
                "observation_ids": impact["observation_ids"],
                "event_ids": affected_events,
                "retained_event_ids": retained_event_ids,
                "deleted_event_ids": deleted_event_ids,
                "episode_ids": impact["episode_ids"],
                "day_ledger_ids": impact["day_ledger_ids"],
                "lineage_edge_ids": impact["lineage_edge_ids"],
                "failed_raw_object_ids": failed_raw_object_ids,
                "local_cleanup_complete": local_cleanup_complete,
                "pending_replica_ids": replicas,
                "acked_replica_ids": [],
                "proof_complete": state == "completed",
                "tombstone_precedence": True,
            }
            proof_hash = _hash(affected)
            self.connection.execute(
                "INSERT INTO deletion_jobs VALUES (?, ?, ?, 'source_object', ?, ?, ?, ?, ?, ?)",
                (
                    job_id,
                    source["owner_id"],
                    source["space_id"],
                    source_object_id,
                    state,
                    _canonical(affected),
                    proof_hash,
                    now,
                    now,
                ),
            )
            queue_job = self._enqueue_job_tx(
                "delete",
                {"deletion_job_id": job_id, "source_object_id": source_object_id},
                priority=100,
                max_attempts=10,
                available_at=now,
                queue_idempotency_key=f"deletion:{job_id}",
            )
            if state == "completed":
                self.connection.execute(
                    "UPDATE durable_jobs SET state = 'completed', result_json = ?, updated_at = ? "
                    "WHERE job_id = ?",
                    (_canonical({"proof_hash": proof_hash}), now, queue_job["job_id"]),
                )
            return {
                "deletion_job_id": job_id,
                "queue_job_id": queue_job["job_id"],
                "state": state,
                "affected": affected,
                "proof_hash": proof_hash,
            }

        return self._command("delete_source", idempotency_key, payload, action)

    def retry_deletion_job(
        self, deletion_job_id: str, *, idempotency_key: str
    ) -> dict[str, Any]:
        payload = {"deletion_job_id": deletion_job_id}

        def action() -> dict[str, Any]:
            row = self._deletion_job_row(deletion_job_id)
            affected = json.loads(row["affected_json"])
            now = _utc_timestamp(self._now())
            failed: list[str] = []
            for raw_object_id in affected.get("raw_object_ids", []):
                try:
                    self._delete_raw_manifest_tx(raw_object_id, "deletion_retry", now)
                except Exception:
                    failed.append(raw_object_id)
            affected["failed_raw_object_ids"] = failed
            affected["local_cleanup_complete"] = not failed and self._raw_cleanup_complete(
                affected.get("raw_object_ids", [])
            )
            state = self._deletion_state(affected)
            affected["proof_complete"] = state == "completed"
            proof_hash = _hash(affected)
            self._update_deletion_job_tx(
                deletion_job_id, state, affected, proof_hash, now
            )
            return {
                "deletion_job_id": deletion_job_id,
                "state": state,
                "affected": affected,
                "proof_hash": proof_hash,
            }

        return self._command("retry_deletion_job", idempotency_key, payload, action)

    def acknowledge_deletion(
        self,
        deletion_job_id: str,
        *,
        replica_id: str,
        idempotency_key: str,
    ) -> dict[str, Any]:
        payload = {"deletion_job_id": deletion_job_id, "replica_id": replica_id}

        def action() -> dict[str, Any]:
            if not replica_id:
                raise InvariantViolation("replica_id is required")
            row = self._deletion_job_row(deletion_job_id)
            affected = json.loads(row["affected_json"])
            pending = set(affected.get("pending_replica_ids", []))
            acked = set(affected.get("acked_replica_ids", []))
            if replica_id not in pending and replica_id not in acked:
                raise InvariantViolation("replica is outside this deletion proof scope")
            pending.discard(replica_id)
            acked.add(replica_id)
            affected["pending_replica_ids"] = sorted(pending)
            affected["acked_replica_ids"] = sorted(acked)
            state = self._deletion_state(affected)
            affected["proof_complete"] = state == "completed"
            now = _utc_timestamp(self._now())
            proof_hash = _hash(affected)
            self._update_deletion_job_tx(
                deletion_job_id, state, affected, proof_hash, now
            )
            return {
                "deletion_job_id": deletion_job_id,
                "state": state,
                "affected": affected,
                "proof_hash": proof_hash,
            }

        return self._command("acknowledge_deletion", idempotency_key, payload, action)

    def deletion_impact(self, source_object_id: str) -> dict[str, list[str]]:
        self._source(source_object_id)
        observation_ids = sorted(
            row["observation_id"]
            for row in self.connection.execute(
                "SELECT observation_id FROM observations WHERE source_object_id = ?",
                (source_object_id,),
            )
        )
        event_ids = sorted(
            row["event_id"]
            for row in self.connection.execute(
                "SELECT DISTINCT fe.event_id FROM field_evidence fe "
                "JOIN events_current ec ON ec.event_id = fe.event_id "
                "AND ec.revision = fe.revision "
                "WHERE fe.source_object_id = ? AND ec.state != 'deleted'",
                (source_object_id,),
            )
        )
        event_set = set(event_ids)
        episode_ids: list[str] = []
        for row in self.connection.execute(
            "SELECT episode_id, snapshot_json FROM episodes_current WHERE state != 'deleted'"
        ):
            snapshot = json.loads(row["snapshot_json"])
            if any(ref["event_id"] in event_set for ref in snapshot["event_refs"]):
                episode_ids.append(row["episode_id"])
        day_ledger_ids = sorted(
            {
                row["day_ledger_id"]
                for row in self.connection.execute(
                    "SELECT DISTINCT dle.day_ledger_id FROM day_ledger_entries dle "
                    "WHERE dle.object_id IN ("
                    "SELECT fe.event_id FROM field_evidence fe WHERE fe.source_object_id = ?"
                    ")",
                    (source_object_id,),
                )
            }
        )
        lineage_edge_ids = sorted(
            row["lineage_edge_id"]
            for row in self.connection.execute(
                "SELECT lineage_edge_id FROM lineage_edges WHERE "
                "(to_type = 'source_object' AND to_id = ?) OR "
                "(from_type = 'observation' AND from_id IN ("
                "SELECT observation_id FROM observations WHERE source_object_id = ?))",
                (source_object_id, source_object_id),
            )
        )
        raw_object_ids = sorted(
            row["raw_object_id"]
            for row in self.connection.execute(
                "SELECT raw_object_id FROM raw_manifests WHERE source_object_id = ? "
                "AND deletion_state = 'active'",
                (source_object_id,),
            )
        )
        return {
            "raw_object_ids": raw_object_ids,
            "observation_ids": observation_ids,
            "event_ids": event_ids,
            "episode_ids": sorted(episode_ids),
            "day_ledger_ids": day_ledger_ids,
            "lineage_edge_ids": lineage_edge_ids,
        }

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
            "raw_manifests",
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
            "durable_jobs",
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

    def _require_raw_vault(self) -> RawVaultIO:
        if self._raw_vault is None:
            raise InvariantViolation("Raw Vault directory was not configured")
        return self._raw_vault

    def _raw_manifest_row(self, raw_object_id: str) -> sqlite3.Row:
        row = self.connection.execute(
            "SELECT * FROM raw_manifests WHERE raw_object_id = ?", (raw_object_id,)
        ).fetchone()
        if not row:
            raise NotFound("Raw Vault manifest not found")
        return row

    def _unique_raw_nonce(self) -> bytes:
        for _ in range(16):
            nonce = os.urandom(12)
            encoded = base64.b64encode(nonce).decode("ascii")
            if not self.connection.execute(
                "SELECT 1 FROM raw_manifests WHERE nonce_b64 = ?", (encoded,)
            ).fetchone():
                return nonce
        raise InvariantViolation("failed to allocate a unique Raw Vault nonce")

    def _recover_raw_vault(self) -> None:
        vault = self._require_raw_vault()
        rows = self.connection.execute(
            "SELECT raw_object_id, relative_path FROM raw_manifests "
            "WHERE state = 'ready' AND deletion_state = 'active'"
        ).fetchall()
        vault.recover(row["relative_path"] for row in rows)
        now = _utc_timestamp(self._now())
        with self._transaction():
            for row in rows:
                if not vault.exists(row["relative_path"]):
                    self.connection.execute(
                        "UPDATE raw_manifests SET state = 'missing', updated_at = ? "
                        "WHERE raw_object_id = ?",
                        (now, row["raw_object_id"]),
                    )

    def _delete_raw_manifest_tx(
        self, raw_object_id: str, deletion_state: str, now: str
    ) -> None:
        row = self._raw_manifest_row(raw_object_id)
        if row["deletion_state"] != "active":
            return
        vault = self._require_raw_vault()
        vault.delete(row["relative_path"])
        self.connection.execute(
            "UPDATE raw_manifests SET state = 'deleted', deletion_state = ?, updated_at = ? "
            "WHERE raw_object_id = ?",
            (deletion_state, now, raw_object_id),
        )

    def _raw_cleanup_complete(self, raw_object_ids: list[str]) -> bool:
        if not raw_object_ids:
            return True
        if self._raw_vault is None:
            return False
        for raw_object_id in raw_object_ids:
            row = self._raw_manifest_row(raw_object_id)
            if row["deletion_state"] == "active":
                return False
            if self._raw_vault.exists(row["relative_path"]):
                return False
        return True

    def _enqueue_job_tx(
        self,
        queue_type: str,
        job_payload: dict[str, Any],
        *,
        priority: int,
        max_attempts: int,
        available_at: str | None,
        queue_idempotency_key: str,
    ) -> dict[str, Any]:
        if queue_type not in QUEUE_TYPES:
            raise InvariantViolation("unsupported durable queue type")
        if not 0 <= priority <= 100:
            raise InvariantViolation("job priority must be between 0 and 100")
        if max_attempts < 1:
            raise InvariantViolation("max_attempts must be positive")
        if len(queue_idempotency_key) < 8:
            raise InvariantViolation("queue idempotency key is too short")
        payload_hash = _hash(
            {
                "queue_type": queue_type,
                "payload": job_payload,
                "priority": priority,
                "max_attempts": max_attempts,
                "available_at": available_at,
            }
        )
        existing = self.connection.execute(
            "SELECT job_id, payload_hash FROM durable_jobs WHERE queue_idempotency_key = ?",
            (queue_idempotency_key,),
        ).fetchone()
        if existing:
            if existing["payload_hash"] != payload_hash:
                raise IdempotencyConflict(
                    f"IDEMPOTENCY_CONFLICT for durable job {queue_idempotency_key}"
                )
            return self.get_job(existing["job_id"])
        created_at = _utc_timestamp(self._now())
        ready_at = _utc_timestamp(available_at) if available_at else created_at
        job_id = self._next_id("job")
        self.connection.execute(
            "INSERT INTO durable_jobs VALUES (?, ?, ?, ?, ?, ?, 'queued', NULL, NULL, 0, ?, ?, NULL, NULL, ?, ?)",
            (
                job_id,
                queue_type,
                queue_idempotency_key,
                payload_hash,
                _canonical(job_payload),
                priority,
                max_attempts,
                ready_at,
                created_at,
                created_at,
            ),
        )
        return self.get_job(job_id)

    def _release_expired_leases_tx(self, now: str) -> None:
        self.connection.execute(
            "UPDATE durable_jobs SET state = 'failed', lease_owner = NULL, "
            "lease_expires_at = NULL, last_error_code = 'LEASE_EXPIRED_MAX_ATTEMPTS', "
            "updated_at = ? WHERE state = 'leased' AND lease_expires_at <= ? "
            "AND attempt_count >= max_attempts",
            (now, now),
        )
        self.connection.execute(
            "UPDATE durable_jobs SET state = 'queued', lease_owner = NULL, "
            "lease_expires_at = NULL, updated_at = ? WHERE state = 'leased' "
            "AND lease_expires_at <= ? AND attempt_count < max_attempts",
            (now, now),
        )

    def _leased_job(self, job_id: str, worker_id: str) -> sqlite3.Row:
        row = self.connection.execute(
            "SELECT * FROM durable_jobs WHERE job_id = ?", (job_id,)
        ).fetchone()
        if not row:
            raise NotFound("durable job not found")
        if row["state"] != "leased" or row["lease_owner"] != worker_id:
            raise QueueLeaseConflict("job is not leased by this worker")
        return row

    def _deletion_job_row(self, deletion_job_id: str) -> sqlite3.Row:
        row = self.connection.execute(
            "SELECT * FROM deletion_jobs WHERE deletion_job_id = ?",
            (deletion_job_id,),
        ).fetchone()
        if not row:
            raise NotFound("deletion job not found")
        return row

    @staticmethod
    def _deletion_state(affected: dict[str, Any]) -> str:
        if affected.get("local_cleanup_complete") and not affected.get(
            "pending_replica_ids"
        ):
            return "completed"
        return "partial_failed"

    def _update_deletion_job_tx(
        self,
        deletion_job_id: str,
        state: str,
        affected: dict[str, Any],
        proof_hash: str,
        now: str,
    ) -> None:
        self.connection.execute(
            "UPDATE deletion_jobs SET state = ?, affected_json = ?, proof_hash = ?, "
            "updated_at = ? WHERE deletion_job_id = ?",
            (state, _canonical(affected), proof_hash, now, deletion_job_id),
        )
        if state == "completed":
            self.connection.execute(
                "UPDATE durable_jobs SET state = 'completed', lease_owner = NULL, "
                "lease_expires_at = NULL, result_json = ?, updated_at = ? "
                "WHERE queue_idempotency_key = ?",
                (
                    _canonical({"proof_hash": proof_hash}),
                    now,
                    f"deletion:{deletion_job_id}",
                ),
            )
        else:
            self.connection.execute(
                "UPDATE durable_jobs SET state = 'queued', lease_owner = NULL, "
                "lease_expires_at = NULL, available_at = ?, updated_at = ? "
                "WHERE queue_idempotency_key = ? AND state != 'completed'",
                (now, now, f"deletion:{deletion_job_id}"),
            )

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

    def _evidence_after_source_deletion(
        self,
        evidences: list[dict[str, Any]],
        deleted_source_id: str,
    ) -> list[dict[str, Any]]:
        remaining: list[dict[str, Any]] = []
        for evidence in evidences:
            observations = []
            for observation_id in evidence["observation_ids"]:
                row = self.connection.execute(
                    "SELECT observation_id, source_object_id, fact_status, confidence "
                    "FROM observations "
                    "WHERE observation_id = ?",
                    (observation_id,),
                ).fetchone()
                if not row:
                    raise NotFound(f"Observation not found: {observation_id}")
                if row["source_object_id"] != deleted_source_id:
                    observations.append(row)
            if not observations:
                continue
            statuses = {row["fact_status"] for row in observations}
            if "observed" in statuses:
                status = "observed"
            elif "user_asserted" in statuses:
                status = "user_asserted"
            elif "inferred" in statuses:
                status = "inferred"
            elif "planned" in statuses:
                status = "planned"
            else:
                status = "unknown"
            confidence = min(
                [float(evidence["confidence"])]
                + [float(row["confidence"]) for row in observations]
            )
            remaining.append(
                {
                    "field": evidence["field"],
                    "observation_ids": sorted(
                        row["observation_id"] for row in observations
                    ),
                    "confidence": confidence,
                    "status": status,
                }
            )
        return remaining

    @staticmethod
    def _fact_status_from_evidence(evidences: list[dict[str, Any]]) -> str:
        statuses = {evidence["status"] for evidence in evidences}
        if "conflict" in statuses:
            return "conflict"
        if "inferred" in statuses or "unknown" in statuses:
            minimum_confidence = min(float(item["confidence"]) for item in evidences)
            return (
                "high_confidence_inference"
                if minimum_confidence >= 0.7
                else "low_confidence_candidate"
            )
        if "observed" in statuses:
            return "confirmed"
        if "user_asserted" in statuses:
            return "user_asserted"
        if "planned" in statuses:
            return "planned"
        return "low_confidence_candidate"

    @staticmethod
    def _safe_recomputed_event_text(
        event: dict[str, Any], evidences: list[dict[str, Any]]
    ) -> tuple[str, str]:
        event_label = event["event_type"].replace("_", " ").strip().capitalize()
        fields = ", ".join(sorted({item["field"] for item in evidences}))
        return (
            event_label,
            f"Recomputed after source deletion from remaining evidence fields: {fields}.",
        )

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
