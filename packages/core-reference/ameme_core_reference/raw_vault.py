"""Fail-closed AES-GCM file I/O for the non-production Raw Vault oracle."""

from __future__ import annotations

import hashlib
import os
from pathlib import Path
from typing import Callable, Iterable

from .errors import CryptoUnavailable, InvariantViolation, RawIntegrityError

try:
    from cryptography.exceptions import InvalidTag
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
except ImportError:  # pragma: no cover - exercised through dependency injection
    AESGCM = None

    class InvalidTag(Exception):
        """Fallback type used only so missing cryptography fails closed."""


FaultInjector = Callable[[str], None]
KeyProvider = Callable[[str], bytes]


class RawVaultIO:
    """Atomic encrypted file operations; manifest persistence remains in CoreOracle."""

    def __init__(
        self,
        root: str | Path,
        *,
        key_provider: KeyProvider | None,
        fault_injector: FaultInjector | None = None,
    ) -> None:
        self.root = Path(root).resolve()
        self.objects_dir = self.root / "objects"
        self.temp_dir = self.root / ".tmp"
        self.key_provider = key_provider
        self.fault_injector = fault_injector
        self._ensure_private_directory(self.root)
        self._ensure_private_directory(self.objects_dir)
        self._ensure_private_directory(self.temp_dir)

    def _fault(self, stage: str) -> None:
        if self.fault_injector is not None:
            self.fault_injector(stage)

    @staticmethod
    def _ensure_private_directory(path: Path) -> None:
        path.mkdir(parents=True, exist_ok=True)
        try:
            path.chmod(0o700)
        except OSError:
            pass

    @staticmethod
    def _require_crypto() -> None:
        if AESGCM is None:
            raise CryptoUnavailable(
                "cryptography AESGCM is required; insecure fallback is disabled"
            )

    def key(self, key_id: str) -> bytes:
        self._require_crypto()
        if self.key_provider is None:
            raise CryptoUnavailable("Raw Vault key provider was not injected")
        key = self.key_provider(key_id)
        if not isinstance(key, bytes) or len(key) != 32:
            raise InvariantViolation("Raw Vault key must be exactly 32 bytes")
        return key

    def _resolve_relative(self, relative_path: str) -> Path:
        candidate = (self.root / relative_path).resolve()
        try:
            candidate.relative_to(self.root)
        except ValueError as exc:
            raise InvariantViolation("Raw Vault path escaped its private root") from exc
        return candidate

    def write_encrypted(
        self,
        *,
        relative_path: str,
        plaintext: bytes,
        key_id: str,
        nonce: bytes,
        aad: bytes,
    ) -> dict[str, object]:
        if len(nonce) != 12:
            raise InvariantViolation("AES-GCM nonce must be 12 bytes")
        key = self.key(key_id)
        ciphertext = AESGCM(key).encrypt(nonce, plaintext, aad)
        final_path = self._resolve_relative(relative_path)
        final_path.parent.mkdir(parents=True, exist_ok=True)
        temp_path = self.temp_dir / f"{final_path.name}.{os.urandom(8).hex()}.tmp"
        replaced = False
        try:
            self._fault("before_temp_write")
            descriptor = os.open(temp_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, "wb") as stream:
                stream.write(ciphertext)
                stream.flush()
                os.fsync(stream.fileno())
            self._fault("after_file_fsync")
            os.replace(temp_path, final_path)
            replaced = True
            self._fault("after_atomic_replace")
            self._fsync_directory(final_path.parent)
            return {
                "plaintext_sha256": hashlib.sha256(plaintext).hexdigest(),
                "ciphertext_sha256": hashlib.sha256(ciphertext).hexdigest(),
                "plaintext_size": len(plaintext),
                "ciphertext_size": len(ciphertext),
            }
        except Exception:
            temp_path.unlink(missing_ok=True)
            if replaced:
                final_path.unlink(missing_ok=True)
            raise

    def read_encrypted(
        self,
        *,
        relative_path: str,
        key_id: str,
        nonce: bytes,
        aad: bytes,
        expected_ciphertext_sha256: str,
    ) -> bytes:
        path = self._resolve_relative(relative_path)
        try:
            ciphertext = path.read_bytes()
        except FileNotFoundError as exc:
            raise RawIntegrityError("Raw Vault ciphertext is missing") from exc
        if hashlib.sha256(ciphertext).hexdigest() != expected_ciphertext_sha256:
            raise RawIntegrityError("Raw Vault ciphertext hash mismatch")
        try:
            return AESGCM(self.key(key_id)).decrypt(nonce, ciphertext, aad)
        except InvalidTag as exc:
            raise RawIntegrityError("Raw Vault authentication failed") from exc

    def delete(self, relative_path: str) -> None:
        path = self._resolve_relative(relative_path)
        self._fault("before_raw_delete")
        path.unlink(missing_ok=True)
        self._fault("after_raw_delete")
        self._fsync_directory(path.parent)

    def cleanup_file(self, relative_path: str) -> None:
        """Best-effort rollback cleanup that deliberately bypasses fault injection."""
        path = self._resolve_relative(relative_path)
        path.unlink(missing_ok=True)
        self._fsync_directory(path.parent)

    def exists(self, relative_path: str) -> bool:
        return self._resolve_relative(relative_path).is_file()

    def recover(self, referenced_paths: Iterable[str]) -> dict[str, int]:
        referenced = {self._resolve_relative(path) for path in referenced_paths}
        temp_removed = 0
        orphan_removed = 0
        for path in self.temp_dir.glob("*.tmp"):
            path.unlink(missing_ok=True)
            temp_removed += 1
        for path in self.objects_dir.glob("*.agcm"):
            if path.resolve() not in referenced:
                path.unlink(missing_ok=True)
                orphan_removed += 1
        if temp_removed or orphan_removed:
            self._fsync_directory(self.objects_dir)
        return {"temp_removed": temp_removed, "orphan_removed": orphan_removed}

    @staticmethod
    def _fsync_directory(path: Path) -> None:
        if os.name == "nt":
            return
        descriptor = os.open(path, os.O_RDONLY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
