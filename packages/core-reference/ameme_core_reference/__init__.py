"""Non-production Ameme local-memory reference implementation and test oracle."""

from .backup import (
    create_reference_backup,
    restore_reference_backup,
    verify_reference_backup,
)
from .core import CoreOracle
from .coverage import (
    CoverageCompiler,
    SourceCapabilityRegistry,
    load_coverage_fixture,
)
from .errors import (
    BackupIntegrityError,
    CoreOracleError,
    CryptoUnavailable,
    IdempotencyConflict,
    InvariantViolation,
    NotFound,
    QueueLeaseConflict,
    RawIntegrityError,
    RawQuotaExceeded,
    RevisionConflict,
)
from .fixture_runner import load_synthetic_day

__all__ = [
    "BackupIntegrityError",
    "CoreOracle",
    "CoverageCompiler",
    "CoreOracleError",
    "CryptoUnavailable",
    "IdempotencyConflict",
    "InvariantViolation",
    "NotFound",
    "QueueLeaseConflict",
    "RawIntegrityError",
    "RawQuotaExceeded",
    "RevisionConflict",
    "SourceCapabilityRegistry",
    "create_reference_backup",
    "load_coverage_fixture",
    "load_synthetic_day",
    "restore_reference_backup",
    "verify_reference_backup",
]
