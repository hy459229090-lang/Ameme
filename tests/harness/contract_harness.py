from __future__ import annotations

import json
import re
from collections import defaultdict, deque
from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Any
from urllib.parse import urlsplit, urlunsplit


SAFE_TOKEN = re.compile(r"^[a-zA-Z0-9_.:-]{1,128}$")
SAFE_EVENT = re.compile(r"^[a-z][a-z0-9_.-]{1,79}$")
SAFE_BUCKETS = {"0", "1", "2-5", "6-20", "21-100", "101+", "lt_10", "10-99", "100-999", "1000+"}
SAFE_COMPONENTS = {"contract_runtime", "contract_compatibility", "test_harness"}
SAFE_OPERATIONS = {"validate_bundle", "compare_schema", "compare_openapi", "recall", "emit", "fault_injection"}
SAFE_RESULT_CODES = {
    "OK",
    "AUTH_REQUIRED",
    "GRANT_MISSING",
    "GRANT_EXPIRED",
    "GRANT_REVOKED",
    "PURPOSE_DENIED",
    "SPACE_DENIED",
    "DATA_TYPE_DENIED",
    "POLICY_BLOCKED",
    "REVISION_CONFLICT",
    "IDEMPOTENCY_CONFLICT",
    "SCHEMA_UNSUPPORTED",
    "DELETE_PROOF_INCOMPLETE",
    "VALIDATION_FAILED",
}
SAFE_OBJECT_TYPES = {
    "acquisition_contract",
    "source_object",
    "observation",
    "user_addendum",
    "event_candidate",
    "event",
    "event_revision",
    "episode",
    "episode_revision",
    "artifact",
    "day_ledger",
    "summary",
    "feedback_event",
    "access_grant",
    "recall_query",
    "recall_page",
    "context_pack",
    "lineage_edge",
    "sync_envelope",
    "deletion_job",
    "export_job",
}
SAFE_PROCESSING_LOCATIONS = {"device", "trusted_cloud", "model_provider"}


class FixedClock:
    def __init__(self, instant: datetime) -> None:
        if instant.tzinfo is None:
            raise ValueError("fixed clock requires a timezone-aware instant")
        self._instant = instant

    def now(self) -> datetime:
        return self._instant

    def isoformat(self) -> str:
        return self._instant.isoformat()

    def advance(self, delta: timedelta) -> datetime:
        if delta.total_seconds() < 0:
            raise ValueError("fixed clock cannot move backwards")
        self._instant += delta
        return self._instant


class DeterministicIdFactory:
    def __init__(self, namespace: str = "test") -> None:
        if not SAFE_TOKEN.fullmatch(namespace):
            raise ValueError("namespace must be a safe non-content token")
        self._namespace = namespace.lower()
        self._counters: dict[str, int] = defaultdict(int)

    def next(self, prefix: str) -> str:
        if not re.fullmatch(r"[a-z][a-z0-9_]{1,31}", prefix):
            raise ValueError("prefix must be a safe contract identifier prefix")
        self._counters[prefix] += 1
        return f"{prefix}_{self._namespace}_{self._counters[prefix]:04d}"


@dataclass(frozen=True)
class InjectedFault(RuntimeError):
    point: str
    code: str

    def __str__(self) -> str:
        return f"injected fault at {self.point}: {self.code}"


class FaultInjector:
    def __init__(self) -> None:
        self._scheduled: dict[str, deque[str]] = defaultdict(deque)
        self._calls: dict[str, int] = defaultdict(int)

    def fail_next(self, point: str, code: str, times: int = 1) -> None:
        if not SAFE_TOKEN.fullmatch(point) or not SAFE_TOKEN.fullmatch(code):
            raise ValueError("fault point and code must be safe non-content tokens")
        if times < 1:
            raise ValueError("times must be positive")
        self._scheduled[point].extend(code for _ in range(times))

    def checkpoint(self, point: str) -> None:
        self._calls[point] += 1
        if self._scheduled[point]:
            raise InjectedFault(point=point, code=self._scheduled[point].popleft())

    def call_count(self, point: str) -> int:
        return self._calls[point]

    def pending_count(self, point: str) -> int:
        return len(self._scheduled[point])


class AllowListLogger:
    _TOKEN_FIELDS = {"component", "operation", "result_code", "trace_id", "caller_id_hash", "object_type", "processing_location", "contract_version"}
    _BUCKET_FIELDS = {"object_count_bucket", "duration_ms_bucket", "size_bytes_bucket"}
    _INTEGER_FIELDS = {"schema_version"}
    _ALLOWED_FIELDS = _TOKEN_FIELDS | _BUCKET_FIELDS | _INTEGER_FIELDS | {"endpoint"}

    def __init__(self, clock: FixedClock) -> None:
        self._clock = clock
        self._records: list[dict[str, Any]] = []

    @staticmethod
    def _safe_endpoint(value: Any) -> str | None:
        if not isinstance(value, str):
            return None
        try:
            parsed = urlsplit(value)
        except ValueError:
            return None
        if parsed.scheme not in {"https", "http"} or not parsed.hostname:
            return None
        host = parsed.hostname
        if parsed.port:
            host = f"{host}:{parsed.port}"
        return urlunsplit((parsed.scheme, host, parsed.path, "", ""))

    def emit(self, event_name: str, **fields: Any) -> dict[str, Any]:
        if not SAFE_EVENT.fullmatch(event_name):
            event_name = "invalid.event"
        record: dict[str, Any] = {"timestamp": self._clock.isoformat(), "event_name": event_name}
        for name, value in fields.items():
            if name not in self._ALLOWED_FIELDS:
                continue
            if name == "component" and value in SAFE_COMPONENTS:
                record[name] = value
            elif name == "operation" and value in SAFE_OPERATIONS:
                record[name] = value
            elif name == "result_code" and value in SAFE_RESULT_CODES:
                record[name] = value
            elif name == "object_type" and value in SAFE_OBJECT_TYPES:
                record[name] = value
            elif name == "processing_location" and value in SAFE_PROCESSING_LOCATIONS:
                record[name] = value
            elif name == "contract_version" and isinstance(value, str) and re.fullmatch(r"[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}", value):
                record[name] = value
            elif name == "trace_id" and isinstance(value, str) and re.fullmatch(r"trc_[a-z0-9]{8,64}", value):
                record[name] = value
            elif name == "caller_id_hash" and isinstance(value, str) and re.fullmatch(r"sha256:[0-9a-f]{16,64}", value):
                record[name] = value
            elif name in self._BUCKET_FIELDS and value in SAFE_BUCKETS:
                record[name] = value
            elif name in self._INTEGER_FIELDS and isinstance(value, int) and not isinstance(value, bool) and 0 <= value <= 9999:
                record[name] = value
            elif name == "endpoint":
                endpoint = self._safe_endpoint(value)
                if endpoint is not None:
                    record[name] = endpoint
        self._records.append(record)
        return dict(record)

    def json_lines(self) -> str:
        return "\n".join(json.dumps(record, ensure_ascii=True, sort_keys=True, separators=(",", ":")) for record in self._records)


def assert_canaries_absent(log_text: str, canaries: list[str]) -> None:
    for canary in canaries:
        if canary and canary in log_text:
            raise AssertionError("sensitive canary reached allow-list log output")
