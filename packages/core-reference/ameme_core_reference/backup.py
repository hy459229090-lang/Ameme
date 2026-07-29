"""Verifiable backup/restore semantics for the non-production Core oracle.

The reference snapshot copies a consistent SQLite image and already-encrypted
Raw Vault ciphertext. It deliberately excludes encryption keys. Production
mobile backup, OS key recovery, E2EE transport and cloud durability remain
separate platform gates.
"""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import shutil
import sqlite3
import tempfile
from typing import Any

from .errors import BackupIntegrityError, InvariantViolation


BACKUP_DATABASE_NAME = "structured.sqlite3"
BACKUP_MANIFEST_NAME = "backup-manifest.json"
BACKUP_RAW_DIR = "raw-vault"


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _safe_relative_path(value: str) -> Path:
    relative = Path(value)
    if relative.is_absolute() or ".." in relative.parts or not relative.parts:
        raise BackupIntegrityError("backup contains an unsafe Raw Vault path")
    return relative


def _sqlite_integrity(path: Path) -> str:
    try:
        connection = sqlite3.connect(str(path))
        connection.execute("PRAGMA query_only = ON")
    except sqlite3.Error as exc:
        raise BackupIntegrityError("backup database cannot be opened") from exc
    try:
        row = connection.execute("PRAGMA quick_check").fetchone()
        if not row or row[0] != "ok":
            raise BackupIntegrityError("backup database quick_check failed")
        return "ok"
    except sqlite3.Error as exc:
        raise BackupIntegrityError("backup database integrity query failed") from exc
    finally:
        connection.close()


def create_reference_backup(
    *,
    database_path: str | Path,
    raw_vault_dir: str | Path,
    backup_dir: str | Path,
    created_at: str,
) -> dict[str, Any]:
    """Create a new immutable-style reference snapshot without overwriting."""

    database = Path(database_path).resolve()
    raw_root = Path(raw_vault_dir).resolve()
    destination = Path(backup_dir).resolve()
    if not database.is_file():
        raise InvariantViolation("reference backup requires a file-backed database")
    if destination.exists():
        raise InvariantViolation("backup destination already exists")
    destination.parent.mkdir(parents=True, exist_ok=True)
    temp_root = Path(
        tempfile.mkdtemp(prefix=f".{destination.name}.incomplete-", dir=destination.parent)
    )
    try:
        backup_database = temp_root / BACKUP_DATABASE_NAME
        source_connection = sqlite3.connect(f"file:{database}?mode=ro", uri=True)
        target_connection = sqlite3.connect(backup_database)
        try:
            source_connection.backup(target_connection)
        finally:
            target_connection.close()
            source_connection.close()
        _sqlite_integrity(backup_database)

        manifest_connection = sqlite3.connect(str(backup_database))
        manifest_connection.execute("PRAGMA query_only = ON")
        manifest_connection.row_factory = sqlite3.Row
        try:
            raw_rows = manifest_connection.execute(
                "SELECT raw_object_id, relative_path, ciphertext_sha256, "
                "ciphertext_size, state, deletion_state FROM raw_manifests "
                "ORDER BY raw_object_id"
            ).fetchall()
        except sqlite3.Error as exc:
            raise BackupIntegrityError(
                "reference database lacks the expected Raw Vault manifest"
            ) from exc
        finally:
            manifest_connection.close()

        raw_entries: list[dict[str, Any]] = []
        for row in raw_rows:
            relative = _safe_relative_path(row["relative_path"])
            source_path = raw_root / relative
            if row["state"] not in {"ready", "deleted"}:
                raise BackupIntegrityError(
                    f"Raw Vault object {row['raw_object_id']} is in a transitional state"
                )
            file_expected = row["state"] == "ready"
            if file_expected and not source_path.is_file():
                raise BackupIntegrityError(
                    f"Raw Vault object {row['raw_object_id']} is missing before backup"
                )
            if not file_expected and source_path.exists():
                raise BackupIntegrityError(
                    f"deleted Raw Vault object {row['raw_object_id']} still has ciphertext"
                )
            entry = {
                "raw_object_id": row["raw_object_id"],
                "relative_path": relative.as_posix(),
                "state": row["state"],
                "deletion_state": row["deletion_state"],
                "file_included": file_expected,
            }
            if file_expected:
                actual_hash = _sha256(source_path)
                actual_size = source_path.stat().st_size
                if (
                    actual_hash != row["ciphertext_sha256"]
                    or actual_size != row["ciphertext_size"]
                ):
                    raise BackupIntegrityError(
                        f"Raw Vault object {row['raw_object_id']} fails source integrity"
                    )
                target_path = temp_root / BACKUP_RAW_DIR / relative
                target_path.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source_path, target_path)
                entry.update(
                    {
                        "ciphertext_sha256": actual_hash,
                        "ciphertext_size": actual_size,
                    }
                )
            raw_entries.append(entry)

        manifest = {
            "schema_version": 1,
            "backup_type": "core_reference_synthetic",
            "state": "ready",
            "created_at": created_at,
            "database": {
                "relative_path": BACKUP_DATABASE_NAME,
                "sha256": _sha256(backup_database),
                "integrity": "ok",
            },
            "raw_objects": raw_entries,
            "key_material": {
                "included": False,
                "recovery_requirement": "external_key_provider",
            },
            "production_claim": False,
        }
        (temp_root / BACKUP_MANIFEST_NAME).write_text(
            json.dumps(manifest, ensure_ascii=False, sort_keys=True, indent=2) + "\n",
            encoding="utf-8",
        )
        os.replace(temp_root, destination)
        return verify_reference_backup(destination)
    except Exception:
        shutil.rmtree(temp_root, ignore_errors=True)
        raise


def verify_reference_backup(backup_dir: str | Path) -> dict[str, Any]:
    root = Path(backup_dir).resolve()
    manifest_path = root / BACKUP_MANIFEST_NAME
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (FileNotFoundError, json.JSONDecodeError, UnicodeError) as exc:
        raise BackupIntegrityError("backup manifest is missing or invalid") from exc
    if (
        manifest.get("schema_version") != 1
        or manifest.get("backup_type") != "core_reference_synthetic"
        or manifest.get("state") != "ready"
        or manifest.get("production_claim") is not False
    ):
        raise BackupIntegrityError("backup manifest contract is unsupported")
    key_material = manifest.get("key_material")
    if not isinstance(key_material, dict) or key_material.get("included") is not False:
        raise BackupIntegrityError("reference backup must not contain key material")
    database_info = manifest.get("database")
    if not isinstance(database_info, dict):
        raise BackupIntegrityError("backup database manifest is missing")
    database_relative = _safe_relative_path(database_info.get("relative_path", ""))
    database_path = root / database_relative
    if not database_path.is_file() or _sha256(database_path) != database_info.get("sha256"):
        raise BackupIntegrityError("backup database hash mismatch")
    _sqlite_integrity(database_path)

    raw_entries = manifest.get("raw_objects")
    if not isinstance(raw_entries, list):
        raise BackupIntegrityError("backup Raw Vault manifest is invalid")
    included = 0
    declared_paths: set[Path] = set()
    for entry in raw_entries:
        if not isinstance(entry, dict):
            raise BackupIntegrityError("backup Raw Vault entry is invalid")
        relative = _safe_relative_path(entry.get("relative_path", ""))
        declared_paths.add(relative)
        path = root / BACKUP_RAW_DIR / relative
        if entry.get("file_included") is True:
            included += 1
            if (
                not path.is_file()
                or path.stat().st_size != entry.get("ciphertext_size")
                or _sha256(path) != entry.get("ciphertext_sha256")
            ):
                raise BackupIntegrityError(
                    f"backup Raw Vault object {entry.get('raw_object_id')} is corrupt"
                )
        elif path.exists():
            raise BackupIntegrityError("backup includes an undeclared Raw Vault file")
    raw_root = root / BACKUP_RAW_DIR
    actual_paths = (
        {
            path.relative_to(raw_root)
            for path in raw_root.rglob("*")
            if path.is_file()
        }
        if raw_root.is_dir()
        else set()
    )
    expected_paths = {
        _safe_relative_path(entry["relative_path"])
        for entry in raw_entries
        if entry.get("file_included") is True
    }
    if actual_paths != expected_paths:
        raise BackupIntegrityError("backup Raw Vault contains undeclared files")
    return {
        "schema_version": 1,
        "state": "ready",
        "database_integrity": "ok",
        "database_sha256": database_info["sha256"],
        "raw_manifest_count": len(raw_entries),
        "raw_file_count": included,
        "key_material_state": "external_required",
        "production_claim": False,
    }


def restore_reference_backup(
    *, backup_dir: str | Path, restore_root: str | Path
) -> dict[str, Any]:
    """Restore into a new root; existing targets are never overwritten."""

    source = Path(backup_dir).resolve()
    destination = Path(restore_root).resolve()
    health = verify_reference_backup(source)
    if destination.exists():
        raise InvariantViolation("restore destination already exists")
    destination.parent.mkdir(parents=True, exist_ok=True)
    temp_root = Path(
        tempfile.mkdtemp(prefix=f".{destination.name}.incomplete-", dir=destination.parent)
    )
    try:
        shutil.copy2(source / BACKUP_DATABASE_NAME, temp_root / BACKUP_DATABASE_NAME)
        raw_source = source / BACKUP_RAW_DIR
        raw_target = temp_root / BACKUP_RAW_DIR
        if raw_source.is_dir():
            shutil.copytree(raw_source, raw_target)
        else:
            raw_target.mkdir(parents=True, exist_ok=True)
        shutil.copy2(
            source / BACKUP_MANIFEST_NAME,
            temp_root / BACKUP_MANIFEST_NAME,
        )
        os.replace(temp_root, destination)
        restored_health = verify_reference_backup(destination)
        if restored_health["database_sha256"] != health["database_sha256"]:
            raise BackupIntegrityError("restored database differs from verified backup")
        return {
            "state": "restored",
            "database_path": str(destination / BACKUP_DATABASE_NAME),
            "raw_vault_dir": str(destination / BACKUP_RAW_DIR),
            "database_integrity": restored_health["database_integrity"],
            "raw_file_count": restored_health["raw_file_count"],
            "key_material_state": restored_health["key_material_state"],
            "production_claim": False,
        }
    except Exception:
        shutil.rmtree(temp_root, ignore_errors=True)
        if destination.exists():
            shutil.rmtree(destination, ignore_errors=True)
        raise
