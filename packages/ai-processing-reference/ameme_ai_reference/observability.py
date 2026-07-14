"""Allow-list observability that never records prompts, bodies or raw values."""

from __future__ import annotations

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
    }

    def __init__(self) -> None:
        self.records: list[dict[str, Any]] = []

    def record(self, **fields: Any) -> None:
        unknown = set(fields) - self._ALLOWED
        if unknown:
            raise ValueError(f"unsafe observability fields: {sorted(unknown)}")
        self.records.append({key: fields[key] for key in sorted(fields)})
