from __future__ import annotations

import json
from pathlib import Path
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "packages" / "core-reference"))

from ameme_core_reference import (  # noqa: E402
    BackupIntegrityError,
    CoreOracle,
    CryptoUnavailable,
    InvariantViolation,
    create_reference_backup,
    load_synthetic_day,
    restore_reference_backup,
    verify_reference_backup,
)


class ReferenceBackupRecoveryTest(unittest.TestCase):
    def setUp(self) -> None:
        self.fixture = json.loads(
            (ROOT / "tests" / "fixtures" / "core" / "synthetic_core_day.json").read_text(
                encoding="utf-8"
            )
        )
        self.tempdir = tempfile.TemporaryDirectory()
        self.root = Path(self.tempdir.name)
        self.database = self.root / "live" / "oracle.sqlite3"
        self.vault = self.root / "live" / "vault"
        self.database.parent.mkdir(parents=True)
        self.keys = {"space-key-v1": bytes(range(32))}
        self.core = CoreOracle(
            self.database,
            raw_vault_dir=self.vault,
            raw_key_provider=lambda key_id: self.keys[key_id],
        )

    def tearDown(self) -> None:
        self.core.close()
        self.tempdir.cleanup()

    def seed_with_raw(self) -> tuple[dict, bytes]:
        handles = load_synthetic_day(self.core, self.fixture)
        source_id = handles["records"]["river_walk"]["capture"]["source_object_id"]
        plaintext = b"unique synthetic backup raw evidence"
        stored = self.core.store_raw(
            source_id,
            plaintext,
            key_id="space-key-v1",
            mime_type="audio/synthetic",
            retention_class="user_retained",
            expires_at=None,
            idempotency_key="backup-raw-seed-001",
        )
        return stored, plaintext

    def create_backup(self) -> Path:
        backup = self.root / "backups" / "snapshot-001"
        create_reference_backup(
            database_path=self.database,
            raw_vault_dir=self.vault,
            backup_dir=backup,
            created_at="2026-07-26T12:00:00+08:00",
        )
        return backup

    def test_backup_restore_reopens_today_and_decrypts_raw_with_external_key(self) -> None:
        stored, plaintext = self.seed_with_raw()
        backup = self.create_backup()
        health = verify_reference_backup(backup)
        self.assertEqual("ready", health["state"])
        self.assertEqual("ok", health["database_integrity"])
        self.assertEqual(1, health["raw_file_count"])
        self.assertEqual("external_required", health["key_material_state"])
        self.assertFalse(health["production_claim"])

        source_id = self.core.raw_manifest(stored["raw_object_id"])["source_object_id"]
        self.core.delete_source(
            source_id,
            idempotency_key="delete-live-after-backup-001",
        )

        restore_root = self.root / "restores" / "snapshot-001"
        restored = restore_reference_backup(
            backup_dir=backup,
            restore_root=restore_root,
        )
        self.assertEqual("restored", restored["state"])
        recovered = CoreOracle(
            restored["database_path"],
            raw_vault_dir=restored["raw_vault_dir"],
            raw_key_provider=lambda key_id: self.keys[key_id],
        )
        try:
            today = recovered.today(
                owner_id=self.fixture["owner_id"],
                space_id=self.fixture["space_id"],
                local_date=self.fixture["day"]["local_date"],
                timezone_name=self.fixture["day"]["timezone"],
            )
            self.assertEqual(3, len(today["events"]))
            self.assertEqual(plaintext, recovered.read_raw(stored["raw_object_id"]))
        finally:
            recovered.close()

    def test_backup_contains_no_key_and_raw_remains_ciphertext(self) -> None:
        stored, plaintext = self.seed_with_raw()
        backup = self.create_backup()
        manifest_text = (backup / "backup-manifest.json").read_text(encoding="utf-8")
        self.assertNotIn(self.keys["space-key-v1"].hex(), manifest_text)
        relative = self.core.raw_manifest(stored["raw_object_id"])["relative_path"]
        ciphertext = (backup / "raw-vault" / relative).read_bytes()
        self.assertNotEqual(plaintext, ciphertext)
        self.assertNotIn(plaintext, ciphertext)

        restored = restore_reference_backup(
            backup_dir=backup,
            restore_root=self.root / "restores" / "without-key",
        )
        without_key = CoreOracle(
            restored["database_path"],
            raw_vault_dir=restored["raw_vault_dir"],
            raw_key_provider=None,
        )
        try:
            with self.assertRaises(CryptoUnavailable):
                without_key.read_raw(stored["raw_object_id"])
        finally:
            without_key.close()

    def test_corruption_fails_health_and_restore(self) -> None:
        stored, _ = self.seed_with_raw()
        backup = self.create_backup()
        relative = self.core.raw_manifest(stored["raw_object_id"])["relative_path"]
        ciphertext = backup / "raw-vault" / relative
        ciphertext.write_bytes(ciphertext.read_bytes() + b"corruption")
        with self.assertRaises(BackupIntegrityError):
            verify_reference_backup(backup)
        with self.assertRaises(BackupIntegrityError):
            restore_reference_backup(
                backup_dir=backup,
                restore_root=self.root / "restores" / "corrupt",
            )
        self.assertFalse((self.root / "restores" / "corrupt").exists())

    def test_backup_and_restore_never_overwrite_existing_targets(self) -> None:
        self.seed_with_raw()
        backup = self.create_backup()
        with self.assertRaises(InvariantViolation):
            create_reference_backup(
                database_path=self.database,
                raw_vault_dir=self.vault,
                backup_dir=backup,
                created_at="2026-07-26T12:01:00+08:00",
            )
        restore_root = self.root / "restores" / "existing"
        restore_root.mkdir(parents=True)
        with self.assertRaises(InvariantViolation):
            restore_reference_backup(
                backup_dir=backup,
                restore_root=restore_root,
            )


if __name__ == "__main__":
    unittest.main()
