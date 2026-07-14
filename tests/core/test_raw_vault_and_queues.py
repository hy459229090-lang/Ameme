from __future__ import annotations

from datetime import datetime, timedelta
import json
from pathlib import Path
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
        self.keys = {"space-key-v1": bytes(range(32))}
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
