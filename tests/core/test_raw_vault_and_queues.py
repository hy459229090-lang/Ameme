from __future__ import annotations

from datetime import datetime, timedelta
import json
from pathlib import Path
import sqlite3
import sys
import tempfile
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "packages" / "core-reference"))

from ameme_core_reference import (  # noqa: E402
    CoreOracle,
    CryptoUnavailable,
    IdempotencyConflict,
    QueueLeaseConflict,
    RawIntegrityError,
    RawQuotaExceeded,
    load_synthetic_day,
)


class MutableClock:
    def __init__(self) -> None:
        self.current = datetime.fromisoformat("2026-07-14T12:00:00+00:00")

    def __call__(self) -> str:
        return self.current.isoformat(timespec="seconds")

    def advance(self, *, seconds: int) -> None:
        self.current += timedelta(seconds=seconds)


class FaultPlan:
    def __init__(self) -> None:
        self.stages: set[str] = set()

    def __call__(self, stage: str) -> None:
        if stage in self.stages:
            raise OSError(f"synthetic fault at {stage}")


class RawVaultAndQueueTest(unittest.TestCase):
    def setUp(self) -> None:
        self.fixture = json.loads(
            (ROOT / "tests" / "fixtures" / "core" / "synthetic_core_day.json").read_text(
                encoding="utf-8"
            )
        )
        self.tempdir = tempfile.TemporaryDirectory()
        self.root = Path(self.tempdir.name)
        self.database = self.root / "oracle.sqlite3"
        self.vault_dir = self.root / "vault"
        self.clock = MutableClock()
        self.keys = {
            "space-key-v1": bytes(range(32)),
            "space-key-v2": bytes(reversed(range(32))),
        }
        self.faults = FaultPlan()
        self.core = self.open_core()

    def tearDown(self) -> None:
        self.core.close()
        self.tempdir.cleanup()

    def open_core(
        self,
        *,
        key_provider=None,
        quota_bytes: int = 1024 * 1024,
    ) -> CoreOracle:
        provider = key_provider or (lambda key_id: self.keys[key_id])
        return CoreOracle(
            self.database,
            now=self.clock,
            raw_vault_dir=self.vault_dir,
            raw_key_provider=provider,
            raw_quota_bytes=quota_bytes,
            raw_fault_injector=self.faults,
        )

    def load_day(self) -> dict:
        return load_synthetic_day(self.core, self.fixture)

    def store_raw(
        self,
        source_object_id: str,
        *,
        content: bytes = b"synthetic encrypted audio fragment",
        key: str = "raw-store-synthetic-001",
        expires_at: str | None = "2026-07-21T12:00:00+00:00",
        retention_class: str = "ephemeral_recovery",
    ) -> dict:
        return self.core.store_raw(
            source_object_id,
            content,
            key_id="space-key-v1",
            mime_type="audio/synthetic",
            retention_class=retention_class,
            expires_at=expires_at,
            idempotency_key=key,
        )

    def test_aesgcm_manifest_atomic_write_and_locator_separation(self) -> None:
        handles = self.load_day()
        source_id = handles["records"]["river_walk"]["capture"]["source_object_id"]
        content = b"synthetic private raw payload"
        first = self.store_raw(source_id, content=content)
        second = self.store_raw(
            source_id,
            content=b"second synthetic payload",
            key="raw-store-synthetic-002",
        )

        self.assertEqual(self.core.read_raw(first["raw_object_id"]), content)
        self.assertNotEqual(first["nonce_b64"], second["nonce_b64"])
        self.assertEqual(len(first["nonce_b64"]), 16)
        self.assertNotIn("key_material", first)
        self.assertNotIn(content.decode("utf-8"), json.dumps(first, sort_keys=True))
        ciphertext = (self.vault_dir / first["relative_path"]).read_bytes()
        self.assertNotEqual(ciphertext, content)
        self.assertEqual(first["ciphertext_size"], len(ciphertext))
        self.assertNotIn("source_locator", first)
        self.assertEqual(
            self.core.locator_history(source_id)[-1]["state"], "available"
        )
        self.assertEqual(list((self.vault_dir / ".tmp").iterdir()), [])

    def test_atomic_failure_leaves_no_manifest_temp_or_ciphertext(self) -> None:
        handles = self.load_day()
        source_id = handles["records"]["river_walk"]["capture"]["source_object_id"]
        for stage in ("after_file_fsync", "after_atomic_replace"):
            self.faults.stages = {stage}
            with self.assertRaises(OSError):
                self.store_raw(
                    source_id,
                    key=f"raw-atomic-{stage}-001",
                )
            self.assertEqual(self.core.table_count("raw_manifests"), 0)
            self.assertEqual(list((self.vault_dir / ".tmp").iterdir()), [])
            self.assertEqual(list((self.vault_dir / "objects").iterdir()), [])
        self.faults.stages.clear()
        with patch.object(
            self.core, "_lineage", side_effect=RuntimeError("synthetic db failure")
        ):
            with self.assertRaises(RuntimeError):
                self.store_raw(source_id, key="raw-atomic-db-rollback-001")
        self.assertEqual(self.core.table_count("raw_manifests"), 0)
        self.assertEqual(list((self.vault_dir / ".tmp").iterdir()), [])
        self.assertEqual(list((self.vault_dir / "objects").iterdir()), [])

    def test_missing_crypto_fails_closed_without_manifest(self) -> None:
        handles = self.load_day()
        source_id = handles["records"]["river_walk"]["capture"]["source_object_id"]
        with patch("ameme_core_reference.raw_vault.AESGCM", None):
            with self.assertRaises(CryptoUnavailable):
                self.store_raw(source_id, key="raw-missing-crypto-001")
        self.assertEqual(self.core.table_count("raw_manifests"), 0)
        self.assertEqual(list((self.vault_dir / "objects").iterdir()), [])

    def test_wrong_key_and_ciphertext_tampering_fail_authentication(self) -> None:
        handles = self.load_day()
        source_id = handles["records"]["river_walk"]["capture"]["source_object_id"]
        stored = self.store_raw(source_id)
        raw_id = stored["raw_object_id"]
        self.core.close()
        self.core = self.open_core(key_provider=lambda _key_id: b"x" * 32)
        with self.assertRaises(RawIntegrityError):
            self.core.read_raw(raw_id)

        self.core.close()
        self.core = self.open_core()
        path = self.vault_dir / stored["relative_path"]
        ciphertext = bytearray(path.read_bytes())
        ciphertext[-1] ^= 0x01
        path.write_bytes(ciphertext)
        with self.assertRaises(RawIntegrityError):
            self.core.read_raw(raw_id)

    def test_aad_binds_policy_key_and_private_path_metadata(self) -> None:
        handles = self.load_day()
        source_id = handles["records"]["river_walk"]["capture"]["source_object_id"]
        content = b"synthetic aad-bound raw payload"
        stored = self.store_raw(source_id, content=content)
        raw_id = stored["raw_object_id"]
        original_aad_json = json.dumps(
            stored["aad"], ensure_ascii=False, sort_keys=True, separators=(",", ":")
        )
        copied_path = "objects/forged-relative-path.agcm"
        (self.vault_dir / copied_path).write_bytes(
            (self.vault_dir / stored["relative_path"]).read_bytes()
        )
        cases = {
            "mime_type": "image/forged",
            "retention_class": "user_retained",
            "expires_at": "2026-07-30T12:00:00+00:00",
            "selected_for_sync": 1,
            "key_id": "space-key-v2",
            "relative_path": copied_path,
        }
        originals = {
            "mime_type": stored["mime_type"],
            "retention_class": stored["retention_class"],
            "expires_at": stored["expires_at"],
            "selected_for_sync": int(stored["selected_for_sync"]),
            "key_id": stored["key_id"],
            "relative_path": stored["relative_path"],
        }
        for field, forged_value in cases.items():
            with self.subTest(field=field):
                self.core.connection.execute(
                    f"UPDATE raw_manifests SET {field} = ? WHERE raw_object_id = ?",
                    (forged_value, raw_id),
                )
                with self.assertRaises(RawIntegrityError):
                    self.core.read_raw(raw_id)
                self.core.connection.execute(
                    f"UPDATE raw_manifests SET {field} = ? WHERE raw_object_id = ?",
                    (originals[field], raw_id),
                )
                self.assertEqual(self.core.read_raw(raw_id), content)

        self.core.connection.execute(
            "UPDATE raw_manifests SET aad_json = '{}' WHERE raw_object_id = ?",
            (raw_id,),
        )
        with self.assertRaises(RawIntegrityError):
            self.core.read_raw(raw_id)
        self.core.connection.execute(
            "UPDATE raw_manifests SET aad_json = ? WHERE raw_object_id = ?",
            (original_aad_json, raw_id),
        )

        self.core.connection.execute(
            "UPDATE raw_manifests SET mime_type = ? WHERE raw_object_id = ?",
            ("image/forged-with-aad", raw_id),
        )
        forged_row = self.core.connection.execute(
            "SELECT * FROM raw_manifests WHERE raw_object_id = ?", (raw_id,)
        ).fetchone()
        forged_aad_json = json.dumps(
            self.core._raw_aad_value(forged_row),
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
        self.core.connection.execute(
            "UPDATE raw_manifests SET aad_json = ? WHERE raw_object_id = ?",
            (forged_aad_json, raw_id),
        )
        with self.assertRaises(RawIntegrityError):
            self.core.read_raw(raw_id)
        self.core.connection.execute(
            "UPDATE raw_manifests SET mime_type = ?, aad_json = ? WHERE raw_object_id = ?",
            (stored["mime_type"], original_aad_json, raw_id),
        )
        self.assertEqual(self.core.read_raw(raw_id), content)

    def test_legacy_aad_v1_fails_closed_with_explicit_migration_error(self) -> None:
        handles = self.load_day()
        source_id = handles["records"]["river_walk"]["capture"]["source_object_id"]
        stored = self.store_raw(source_id)
        self.core.connection.execute(
            "UPDATE raw_manifests SET aad_version = 1 WHERE raw_object_id = ?",
            (stored["raw_object_id"],),
        )
        with self.assertRaisesRegex(RawIntegrityError, "re-encryption migration"):
            self.core.read_raw(stored["raw_object_id"])

    def test_schema_v2_reopen_labels_existing_raw_as_legacy_aad(self) -> None:
        with tempfile.TemporaryDirectory() as tempdir:
            root = Path(tempdir)
            database = root / "legacy-v2.sqlite3"
            vault_dir = root / "vault"
            relative_path = "objects/raw_legacy_v2.agcm"
            connection = sqlite3.connect(database)
            connection.execute(
                "CREATE TABLE raw_manifests ("
                "raw_object_id TEXT PRIMARY KEY, source_object_id TEXT NOT NULL, "
                "space_id TEXT NOT NULL, relative_path TEXT NOT NULL UNIQUE, "
                "key_id TEXT NOT NULL, nonce_b64 TEXT NOT NULL UNIQUE, "
                "aad_json TEXT NOT NULL, plaintext_sha256 TEXT NOT NULL, "
                "ciphertext_sha256 TEXT NOT NULL, plaintext_size INTEGER NOT NULL, "
                "ciphertext_size INTEGER NOT NULL, mime_type TEXT NOT NULL, "
                "retention_class TEXT NOT NULL, created_at TEXT NOT NULL, "
                "expires_at TEXT, deletion_state TEXT NOT NULL, "
                "selected_for_sync INTEGER NOT NULL, state TEXT NOT NULL, "
                "updated_at TEXT NOT NULL)"
            )
            legacy_aad = json.dumps(
                {
                    "raw_object_id": "raw_legacy_v2",
                    "source_object_id": "src_legacy_v2",
                    "space_id": "space_personal",
                    "created_at": "2026-07-14T12:00:00+00:00",
                    "schema_version": 1,
                },
                sort_keys=True,
                separators=(",", ":"),
            )
            connection.execute(
                "INSERT INTO raw_manifests VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                (
                    "raw_legacy_v2",
                    "src_legacy_v2",
                    "space_personal",
                    relative_path,
                    "space-key-v1",
                    "AAAAAAAAAAAAAAAA",
                    legacy_aad,
                    "legacy_plaintext_hash",
                    "legacy_ciphertext_hash",
                    1,
                    16,
                    "audio/synthetic",
                    "ephemeral_recovery",
                    "2026-07-14T12:00:00+00:00",
                    "2026-07-21T12:00:00+00:00",
                    "active",
                    0,
                    "ready",
                    "2026-07-14T12:00:00+00:00",
                ),
            )
            connection.commit()
            connection.close()
            (vault_dir / "objects").mkdir(parents=True)
            (vault_dir / relative_path).write_bytes(b"x" * 16)

            core = CoreOracle(
                database,
                now=MutableClock(),
                raw_vault_dir=vault_dir,
                raw_key_provider=lambda key_id: self.keys[key_id],
            )
            manifest = core.raw_manifest("raw_legacy_v2")
            self.assertEqual(manifest["aad_version"], 1)
            self.assertIsNone(manifest["pending_deletion_state"])
            with self.assertRaisesRegex(RawIntegrityError, "re-encryption migration"):
                core.read_raw("raw_legacy_v2")
            core.close()

    def test_quota_and_ttl_cleanup_keep_source_locator(self) -> None:
        self.core.close()
        self.core = self.open_core(quota_bytes=64)
        handles = self.load_day()
        source_id = handles["records"]["river_walk"]["capture"]["source_object_id"]
        stored = self.store_raw(
            source_id,
            content=b"a" * 40,
            expires_at="2026-07-14T20:00:10+08:00",
        )
        self.assertEqual(stored["expires_at"], "2026-07-14T12:00:10+00:00")
        with self.assertRaises(RawQuotaExceeded):
            self.store_raw(
                source_id,
                content=b"b",
                key="raw-quota-exceeded-001",
            )
        purged = self.core.purge_expired_raw(
            at="2026-07-14T12:00:11+00:00",
            idempotency_key="raw-ttl-purge-001",
        )
        self.assertEqual(purged["deleted_raw_object_ids"], [stored["raw_object_id"]])
        self.assertEqual(self.core.raw_vault_usage()["ciphertext_bytes"], 0)
        self.assertEqual(
            self.core.raw_manifest(stored["raw_object_id"])["deletion_state"],
            "expired",
        )
        self.assertEqual(
            self.core.locator_history(source_id)[-1]["state"], "available"
        )

    def test_ttl_finalize_commit_failure_recovers_after_reopen(self) -> None:
        handles = self.load_day()
        source_id = handles["records"]["river_walk"]["capture"]["source_object_id"]
        stored = self.store_raw(
            source_id,
            expires_at="2026-07-14T12:00:10+00:00",
        )
        self.faults.stages = {"before_raw_delete_finalize_commit"}
        purged = self.core.purge_expired_raw(
            at="2026-07-14T12:00:11+00:00",
            idempotency_key="raw-ttl-two-phase-001",
        )
        self.assertEqual(purged["deleted_raw_object_ids"], [])
        self.assertEqual(purged["failed_raw_object_ids"], [stored["raw_object_id"]])
        manifest = self.core.raw_manifest(stored["raw_object_id"])
        self.assertEqual(
            (manifest["state"], manifest["deletion_state"]),
            ("delete_pending", "pending"),
        )
        self.assertFalse((self.vault_dir / stored["relative_path"]).exists())
        self.core.close()
        self.faults.stages.clear()
        self.core = self.open_core()
        recovered = self.core.raw_manifest(stored["raw_object_id"])
        self.assertEqual((recovered["state"], recovered["deletion_state"]), ("deleted", "expired"))

    def test_reopen_cleans_atomic_orphans_and_temp_files(self) -> None:
        (self.vault_dir / "objects" / "orphan.agcm").write_bytes(b"synthetic orphan")
        (self.vault_dir / ".tmp" / "crash.tmp").write_bytes(b"synthetic temp")
        self.core.close()
        self.core = self.open_core()
        self.assertFalse((self.vault_dir / "objects" / "orphan.agcm").exists())
        self.assertFalse((self.vault_dir / ".tmp" / "crash.tmp").exists())

    def test_durable_queue_priority_idempotency_and_backoff(self) -> None:
        processing = self.core.enqueue_job(
            "processing",
            {"source_object_id": "src_synthetic_low"},
            priority=10,
            idempotency_key="queue-processing-001",
        )
        sync = self.core.enqueue_job(
            "sync",
            {"envelope_id": "env_synthetic_mid"},
            priority=50,
            idempotency_key="queue-sync-000001",
        )
        export = self.core.enqueue_job(
            "export",
            {"export_job_id": "exp_synthetic_high"},
            priority=100,
            idempotency_key="queue-export-0001",
        )
        replay = self.core.enqueue_job(
            "export",
            {"export_job_id": "exp_synthetic_high"},
            priority=100,
            idempotency_key="queue-export-0001",
        )
        self.assertEqual(replay["job_id"], export["job_id"])
        with self.assertRaises(IdempotencyConflict):
            self.core.enqueue_job(
                "export",
                {"export_job_id": "exp_changed"},
                priority=100,
                idempotency_key="queue-export-0001",
            )

        leased = self.core.lease_next_job(
            worker_id="worker-a",
            lease_seconds=30,
            idempotency_key="queue-lease-high-001",
        )["job"]
        self.assertEqual(leased["job_id"], export["job_id"])
        self.core.complete_job(
            leased["job_id"],
            worker_id="worker-a",
            result={"result_code": "SYNTHETIC_OK"},
            idempotency_key="queue-complete-high-001",
        )
        leased_sync = self.core.lease_next_job(
            worker_id="worker-a",
            lease_seconds=30,
            idempotency_key="queue-lease-mid-0001",
        )["job"]
        self.assertEqual(leased_sync["job_id"], sync["job_id"])
        failed = self.core.fail_job(
            leased_sync["job_id"],
            worker_id="worker-a",
            error_code="SYNTHETIC_TRANSIENT",
            base_backoff_seconds=10,
            idempotency_key="queue-fail-mid-00001",
        )
        self.assertEqual(failed["state"], "queued")
        self.assertEqual(
            self.core.lease_next_job(
                worker_id="worker-a",
                lease_seconds=30,
                idempotency_key="queue-lease-low-0001",
            )["job"]["job_id"],
            processing["job_id"],
        )
        self.clock.advance(seconds=11)
        self.assertEqual(
            self.core.lease_next_job(
                worker_id="worker-b",
                lease_seconds=30,
                idempotency_key="queue-lease-retry-01",
            )["job"]["job_id"],
            sync["job_id"],
        )

    def test_expired_lease_is_recovered_after_reopen(self) -> None:
        queued = self.core.enqueue_job(
            "recompute",
            {"event_id": "evt_synthetic_recompute"},
            priority=20,
            idempotency_key="queue-recompute-001",
        )
        first = self.core.lease_next_job(
            worker_id="worker-before-crash",
            lease_seconds=30,
            idempotency_key="queue-lease-before-crash-001",
        )["job"]
        self.assertEqual(first["attempt_count"], 1)
        self.core.close()
        self.clock.advance(seconds=31)
        self.core = self.open_core()
        recovered = self.core.lease_next_job(
            worker_id="worker-after-reopen",
            lease_seconds=30,
            idempotency_key="queue-lease-after-reopen-001",
        )["job"]
        self.assertEqual(recovered["job_id"], queued["job_id"])
        self.assertEqual(recovered["attempt_count"], 2)

    def test_expired_worker_cannot_complete_or_fail_job(self) -> None:
        queued = self.core.enqueue_job(
            "export",
            {"export_job_id": "exp_synthetic_stale_worker"},
            priority=10,
            idempotency_key="queue-export-stale-001",
        )
        leased = self.core.lease_next_job(
            worker_id="worker-stale",
            lease_seconds=10,
            idempotency_key="queue-lease-stale-001",
        )["job"]
        self.assertEqual(leased["job_id"], queued["job_id"])
        self.clock.advance(seconds=11)
        with self.assertRaises(QueueLeaseConflict):
            self.core.complete_job(
                leased["job_id"],
                worker_id="worker-stale",
                result={"result_code": "SHOULD_NOT_COMMIT"},
                idempotency_key="queue-complete-stale-001",
            )
        with self.assertRaises(QueueLeaseConflict):
            self.core.fail_job(
                leased["job_id"],
                worker_id="worker-stale",
                error_code="SHOULD_NOT_COMMIT",
                idempotency_key="queue-fail-stale-001",
            )

    def test_expired_lease_stops_at_max_attempts(self) -> None:
        queued = self.core.enqueue_job(
            "processing",
            {"source_object_id": "src_synthetic_max_attempt"},
            priority=10,
            max_attempts=1,
            idempotency_key="queue-processing-max-001",
        )
        self.core.lease_next_job(
            worker_id="worker-final-attempt",
            lease_seconds=10,
            idempotency_key="queue-lease-final-attempt-001",
        )
        self.clock.advance(seconds=11)
        self.assertIsNone(
            self.core.lease_next_job(
                worker_id="worker-after-final",
                lease_seconds=10,
                idempotency_key="queue-lease-after-final-001",
            )["job"]
        )
        terminal = self.core.get_job(queued["job_id"])
        self.assertEqual(terminal["state"], "failed")
        self.assertEqual(terminal["last_error_code"], "LEASE_EXPIRED_MAX_ATTEMPTS")

    def test_deletion_stage_rollback_keeps_active_manifest_and_file_consistent(self) -> None:
        handles = self.load_day()
        source_id = handles["records"]["river_walk"]["capture"]["source_object_id"]
        stored = self.store_raw(source_id)
        self.faults.stages = {"before_raw_delete_stage_commit"}
        with self.assertRaises(OSError):
            self.core.delete_source(
                source_id,
                idempotency_key="delete-stage-rollback-001",
            )
        manifest = self.core.raw_manifest(stored["raw_object_id"])
        self.assertEqual((manifest["state"], manifest["deletion_state"]), ("ready", "active"))
        self.assertTrue((self.vault_dir / stored["relative_path"]).exists())
        source_state = self.core.connection.execute(
            "SELECT processing_state FROM source_objects WHERE source_object_id = ?",
            (source_id,),
        ).fetchone()["processing_state"]
        self.assertEqual(source_state, "processed")
        self.assertEqual(self.core.table_count("deletion_jobs"), 0)
        self.core.close()
        self.faults.stages.clear()
        self.core = self.open_core()
        self.assertEqual(
            self.core.read_raw(stored["raw_object_id"]),
            b"synthetic encrypted audio fragment",
        )

    def test_delete_enqueue_failure_never_touches_raw_file(self) -> None:
        handles = self.load_day()
        source_id = handles["records"]["river_walk"]["capture"]["source_object_id"]
        stored = self.store_raw(source_id)
        with patch.object(
            self.core,
            "_enqueue_job_tx",
            side_effect=RuntimeError("synthetic enqueue failure"),
        ):
            with self.assertRaises(RuntimeError):
                self.core.delete_source(
                    source_id,
                    idempotency_key="delete-enqueue-rollback-001",
                )
        manifest = self.core.raw_manifest(stored["raw_object_id"])
        self.assertEqual((manifest["state"], manifest["deletion_state"]), ("ready", "active"))
        self.assertTrue((self.vault_dir / stored["relative_path"]).exists())
        source_state = self.core.connection.execute(
            "SELECT processing_state FROM source_objects WHERE source_object_id = ?",
            (source_id,),
        ).fetchone()["processing_state"]
        self.assertEqual(source_state, "processed")
        self.assertEqual(self.core.table_count("deletion_jobs"), 0)

    def test_two_phase_deletion_recovers_every_committed_restart_stage(self) -> None:
        stages = {
            "before_raw_delete": (True, "delete_pending"),
            "before_raw_delete_finalize_commit": (False, "delete_pending"),
            "before_deletion_proof_reconcile": (False, "deleted"),
        }
        for index, (stage, expected) in enumerate(stages.items(), start=1):
            with self.subTest(stage=stage), tempfile.TemporaryDirectory() as tempdir:
                root = Path(tempdir)
                database = root / "oracle.sqlite3"
                vault_dir = root / "vault"
                clock = MutableClock()
                faults = FaultPlan()
                core = CoreOracle(
                    database,
                    now=clock,
                    raw_vault_dir=vault_dir,
                    raw_key_provider=lambda key_id: self.keys[key_id],
                    raw_fault_injector=faults,
                )
                handles = load_synthetic_day(core, self.fixture)
                source_id = handles["records"]["river_walk"]["capture"][
                    "source_object_id"
                ]
                stored = core.store_raw(
                    source_id,
                    b"synthetic restart-stage raw",
                    key_id="space-key-v1",
                    mime_type="audio/synthetic",
                    retention_class="ephemeral_recovery",
                    expires_at="2026-07-21T12:00:00+00:00",
                    idempotency_key=f"raw-restart-stage-{index:03d}",
                )
                faults.stages = {stage}
                deletion = core.delete_source(
                    source_id,
                    idempotency_key=f"delete-restart-stage-{index:03d}",
                )
                manifest = core.raw_manifest(stored["raw_object_id"])
                file_exists = (vault_dir / stored["relative_path"]).exists()
                self.assertEqual(deletion["state"], "partial_failed")
                self.assertFalse(deletion["affected"]["proof_complete"])
                self.assertFalse(deletion["affected"]["local_cleanup_complete"])
                self.assertEqual(file_exists, expected[0])
                self.assertEqual(manifest["state"], expected[1])
                self.assertFalse(
                    manifest["state"] == "ready"
                    and manifest["deletion_state"] == "active"
                    and not file_exists
                )
                deletion_job_id = deletion["deletion_job_id"]
                queue_job_id = deletion["queue_job_id"]
                core.close()

                faults.stages.clear()
                core = CoreOracle(
                    database,
                    now=clock,
                    raw_vault_dir=vault_dir,
                    raw_key_provider=lambda key_id: self.keys[key_id],
                    raw_fault_injector=faults,
                )
                recovered = core.get_deletion_job(deletion_job_id)
                recovered_manifest = core.raw_manifest(stored["raw_object_id"])
                self.assertEqual(recovered_manifest["state"], "deleted")
                self.assertFalse((vault_dir / stored["relative_path"]).exists())
                self.assertEqual(recovered["state"], "completed")
                self.assertTrue(recovered["affected"]["proof_complete"])
                self.assertTrue(recovered["affected"]["local_cleanup_complete"])
                self.assertEqual(core.get_job(queue_job_id)["state"], "completed")
                core.close()

    def test_deletion_proof_waits_for_raw_cleanup_and_replica_ack(self) -> None:
        handles = self.load_day()
        source_id = handles["records"]["river_walk"]["capture"]["source_object_id"]
        event_id = handles["records"]["river_walk"]["event"]["event_id"]
        stored = self.store_raw(source_id)
        deletion = self.core.delete_source(
            source_id,
            required_replica_ids=["peer_synthetic_001"],
            idempotency_key="delete-with-peer-ack-001",
        )
        self.assertEqual(deletion["state"], "partial_failed")
        self.assertTrue(deletion["affected"]["local_cleanup_complete"])
        self.assertFalse(deletion["affected"]["proof_complete"])
        self.assertFalse((self.vault_dir / stored["relative_path"]).exists())
        self.assertEqual(
            self.core.get_job(deletion["queue_job_id"])["state"], "queued"
        )
        leased_delete = self.core.lease_next_job(
            worker_id="worker-delete-proof",
            lease_seconds=30,
            queue_types=["delete"],
            idempotency_key="queue-lease-delete-proof-001",
        )["job"]
        with self.assertRaises(QueueLeaseConflict):
            self.core.complete_job(
                leased_delete["job_id"],
                worker_id="worker-delete-proof",
                result={"result_code": "ACK_NOT_RECEIVED"},
                idempotency_key="queue-complete-delete-early-001",
            )
        acknowledged = self.core.acknowledge_deletion(
            deletion["deletion_job_id"],
            replica_id="peer_synthetic_001",
            idempotency_key="delete-peer-ack-0001",
        )
        self.assertEqual(acknowledged["state"], "completed")
        self.assertTrue(acknowledged["affected"]["proof_complete"])
        self.assertEqual(
            self.core.get_deletion_job(deletion["deletion_job_id"])["proof_hash"],
            acknowledged["proof_hash"],
        )
        self.assertEqual(
            self.core.get_job(deletion["queue_job_id"])["state"], "completed"
        )
        self.core.rebuild()
        today = self.core.today(
            owner_id=self.fixture["owner_id"],
            space_id=self.fixture["space_id"],
            local_date=self.fixture["day"]["local_date"],
            timezone_name=self.fixture["day"]["timezone"],
        )
        self.assertNotIn(event_id, [event["event_id"] for event in today["events"]])

    def test_raw_only_delete_preserves_structure_while_source_delete_cascades(self) -> None:
        handles = self.load_day()
        source_id = handles["records"]["river_walk"]["capture"]["source_object_id"]
        event_id = handles["records"]["river_walk"]["event"]["event_id"]
        stored = self.store_raw(source_id)

        raw_only = self.core.delete_raw_evidence(
            source_id,
            idempotency_key="delete-raw-keep-structured-001",
        )
        self.assertEqual("completed", raw_only["state"])
        self.assertTrue(raw_only["affected"]["structured_source_preserved"])
        self.assertEqual([event_id], raw_only["affected"]["retained_event_ids"])
        self.assertFalse((self.vault_dir / stored["relative_path"]).exists())
        self.assertEqual(
            "processed",
            self.core.connection.execute(
                "SELECT processing_state FROM source_objects WHERE source_object_id = ?",
                (source_id,),
            ).fetchone()["processing_state"],
        )
        self.core.rebuild()
        after_raw_delete = self.core.today(
            owner_id=self.fixture["owner_id"],
            space_id=self.fixture["space_id"],
            local_date=self.fixture["day"]["local_date"],
            timezone_name=self.fixture["day"]["timezone"],
        )
        self.assertIn(
            event_id,
            {event["event_id"] for event in after_raw_delete["events"]},
        )

        cascade = self.core.delete_source(
            source_id,
            idempotency_key="delete-source-and-derived-001",
        )
        self.assertEqual("completed", cascade["state"])
        self.assertIn(event_id, cascade["affected"]["deleted_event_ids"])
        self.core.rebuild()
        after_cascade = self.core.today(
            owner_id=self.fixture["owner_id"],
            space_id=self.fixture["space_id"],
            local_date=self.fixture["day"]["local_date"],
            timezone_name=self.fixture["day"]["timezone"],
        )
        self.assertNotIn(
            event_id,
            {event["event_id"] for event in after_cascade["events"]},
        )

    def test_deletion_proof_waits_for_failed_raw_then_retry_completes(self) -> None:
        handles = self.load_day()
        source_id = handles["records"]["river_walk"]["capture"]["source_object_id"]
        stored = self.store_raw(source_id)
        self.faults.stages = {"before_raw_delete"}
        deletion = self.core.delete_source(
            source_id,
            idempotency_key="delete-raw-failure-001",
        )
        self.assertEqual(deletion["state"], "partial_failed")
        self.assertFalse(deletion["affected"]["local_cleanup_complete"])
        self.assertEqual(
            deletion["affected"]["failed_raw_object_ids"], [stored["raw_object_id"]]
        )
        self.assertTrue((self.vault_dir / stored["relative_path"]).exists())
        self.faults.stages.clear()
        retried = self.core.retry_deletion_job(
            deletion["deletion_job_id"],
            idempotency_key="delete-raw-retry-0001",
        )
        self.assertEqual(retried["state"], "completed")
        self.assertTrue(retried["affected"]["proof_complete"])
        self.assertFalse((self.vault_dir / stored["relative_path"]).exists())
        self.assertEqual(
            self.core.get_job(deletion["queue_job_id"])["state"], "completed"
        )


if __name__ == "__main__":
    unittest.main()
