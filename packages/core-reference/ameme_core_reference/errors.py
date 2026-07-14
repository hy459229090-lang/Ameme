"""Stable errors emitted by the non-production core oracle."""


class CoreOracleError(Exception):
    """Base class for expected oracle failures."""


class IdempotencyConflict(CoreOracleError):
    """An idempotency key was reused with a different command payload."""


class RevisionConflict(CoreOracleError):
    """A revision command did not target the current base revision."""


class InvariantViolation(CoreOracleError):
    """A domain invariant was violated."""


class NotFound(CoreOracleError):
    """A referenced object does not exist in the requested space."""


class CryptoUnavailable(CoreOracleError):
    """The approved reference cryptography implementation is unavailable."""


class RawIntegrityError(CoreOracleError):
    """A Raw Vault object failed ciphertext hash or AES-GCM authentication."""


class RawQuotaExceeded(CoreOracleError):
    """A Raw Vault write would exceed its configured local quota."""


class QueueLeaseConflict(CoreOracleError):
    """A durable job was completed or failed without its active lease."""
