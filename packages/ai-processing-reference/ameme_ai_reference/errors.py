"""Stable, content-free errors for the AI processing reference package."""

from __future__ import annotations

from enum import Enum
from types import MappingProxyType
from typing import Any, Mapping


class ErrorCode(str, Enum):
    TASK_NOT_REGISTERED = "TASK_NOT_REGISTERED"
    TASK_DISABLED = "TASK_DISABLED"
    TASK_INPUT_INVALID = "TASK_INPUT_INVALID"
    SPACE_DENIED = "SPACE_DENIED"
    PURPOSE_DENIED = "PURPOSE_DENIED"
    PROVIDER_NOT_ALLOWED = "PROVIDER_NOT_ALLOWED"
    SENSITIVE_MODEL_BLOCKED = "SENSITIVE_MODEL_BLOCKED"
    DELETED_INPUT = "DELETED_INPUT"
    REVOKED_INPUT = "REVOKED_INPUT"
    BUDGET_EXHAUSTED = "BUDGET_EXHAUSTED"
    DATA_INSUFFICIENT = "DATA_INSUFFICIENT"
    SCHEMA_MISMATCH = "SCHEMA_MISMATCH"
    MISSING_EVIDENCE = "MISSING_EVIDENCE"
    PROVIDER_ERROR = "PROVIDER_ERROR"


class ProcessingError(Exception):
    """An error whose public representation never includes input content."""

    def __init__(
        self,
        code: ErrorCode,
        *,
        retryable: bool = False,
        safe_context: Mapping[str, Any] | None = None,
    ) -> None:
        self.code = code
        self.retryable = retryable
        self.safe_context = MappingProxyType(dict(safe_context or {}))
        super().__init__(code.value)

    def to_dict(self) -> dict[str, Any]:
        return {
            "code": self.code.value,
            "retryable": self.retryable,
            "safe_context": dict(sorted(self.safe_context.items())),
        }
