"""Allow-list observability that never records prompts, bodies or raw values."""

from __future__ import annotations

import re
from typing import Any


class SafeObserver:
    _ALLOWED = {
        "task_id",
        "task_version",
        "result_state",
        "result_code",
        "input_hash",
        "evidence_count",
        "adapter_name",
        "processing_location",
        "event_kind",
        "route",
        "budget_state",
        "cache_state",
        "batch_state",
        "scope_hash",
        "item_count",
    }
    _COUNT_FIELDS = {"evidence_count", "item_count"}
    _HASH_FIELDS = {"input_hash", "scope_hash"}
    _CATEGORY = re.compile(r"^[A-Za-z0-9_.:-]{1,128}$")
    _HEX_DIGEST = re.compile(r"^[0-9a-f]{64}$")
    _SENSITIVE_CATEGORY = re.compile(
        r"(?i)(token|secret|password|passwd|bearer|api[_-]?key|sk-[a-z0-9])"
    )

    def __init__(self) -> None:
        self.records: list[dict[str, Any]] = []

    @classmethod
    def safe_category(cls, value: Any, *, fallback: str = "redacted") -> str:
        text = str(value)
        safe = (
            cls._CATEGORY.fullmatch(text) is not None
            and cls._SENSITIVE_CATEGORY.search(text) is None
        )
        return text if safe else fallback

    def record(self, **fields: Any) -> None:
        unknown = set(fields) - self._ALLOWED
        if unknown:
            raise ValueError(f"unsafe observability fields: {sorted(unknown)}")
        for key, value in fields.items():
            if key in self._COUNT_FIELDS:
                if not isinstance(value, int) or isinstance(value, bool) or value < 0:
                    raise ValueError(f"unsafe observability value for {key}")
            elif key in self._HASH_FIELDS:
                if not isinstance(value, str) or self._HEX_DIGEST.fullmatch(value) is None:
                    raise ValueError(f"unsafe observability value for {key}")
            elif (
                not isinstance(value, str)
                or self._CATEGORY.fullmatch(value) is None
                or self._SENSITIVE_CATEGORY.search(value) is not None
            ):
                raise ValueError(f"unsafe observability value for {key}")
        self.records.append({key: fields[key] for key in sorted(fields)})
