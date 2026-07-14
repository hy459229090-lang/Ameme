"""Non-production executable specification for Agent Local Node protocol v1."""

from __future__ import annotations

from copy import deepcopy
from datetime import datetime
import hashlib
import json
import re
from typing import Any, Mapping


PROTOCOL_VERSION = "ameme.agent-local-node.v1"
CANONICAL_JSON = "ameme-canonical-json-v1"
MAX_REQUEST_BYTES = 65_536
MAX_PAYLOAD_BYTES = 32_768
MAX_RESPONSE_BYTES = 524_288
MAX_SCOPE_ITEMS = 8
MAX_SAFE_INTEGER = 9_007_199_254_740_991

OPERATIONS = frozenset(
    {
        "get_event",
        "create_event",
        "append_revision",
        "undo_capture",
        "visible_events",
        "set_policy_blocked",
    }
)

ERROR_CODES = frozenset(
    {
        "AUTH_REQUIRED",
        "GRANT_REVOKED",
        "GRANT_EXPIRED",
        "PURPOSE_DENIED",
        "SPACE_DENIED",
        "DATA_TYPE_DENIED",
        "SCHEMA_UNSUPPORTED",
        "INVALID_REQUEST",
        "PAYLOAD_TOO_LARGE",
        "PAYLOAD_DIGEST_MISMATCH",
        "RESULT_DIGEST_MISMATCH",
        "SCOPE_MISMATCH",
        "NOT_VISIBLE",
        "OPERATION_UNSUPPORTED",
        "REVISION_CONFLICT",
        "IDEMPOTENCY_CONFLICT",
        "TEMPORARILY_UNAVAILABLE",
        "INTERNAL_ERROR",
        "RESPONSE_REQUEST_MISMATCH",
    }
)

ERROR_RETRYABLE = {
    "AUTH_REQUIRED": False,
    "GRANT_REVOKED": False,
    "GRANT_EXPIRED": False,
    "PURPOSE_DENIED": False,
    "SPACE_DENIED": False,
    "DATA_TYPE_DENIED": False,
    "SCHEMA_UNSUPPORTED": False,
    "INVALID_REQUEST": False,
    "PAYLOAD_TOO_LARGE": False,
    "PAYLOAD_DIGEST_MISMATCH": False,
    "RESULT_DIGEST_MISMATCH": False,
    "SCOPE_MISMATCH": False,
    "NOT_VISIBLE": False,
    "OPERATION_UNSUPPORTED": False,
    "REVISION_CONFLICT": False,
    "IDEMPOTENCY_CONFLICT": False,
    "TEMPORARILY_UNAVAILABLE": True,
    "INTERNAL_ERROR": True,
    "RESPONSE_REQUEST_MISMATCH": False,
}

ID_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
HEX_64_PATTERN = re.compile(r"^[0-9a-f]{64}$")
REQUEST_KEYS = {"protocol_version", "request_id", "control", "payload"}
CONTROL_KEYS = {
    "caller_id",
    "grant_id",
    "purpose",
    "spaces",
    "memory_types",
    "operation",
    "idempotency_slot",
    "payload_digest",
}
RESPONSE_KEYS = {
    "protocol_version",
    "request_id",
    "status",
    "result",
    "result_digest",
    "error",
}


class ProtocolViolation(ValueError):
    """Stable, content-free validation failure."""

    def __init__(self, code: str) -> None:
        if code not in ERROR_CODES:
            raise ValueError(f"unknown protocol error code: {code}")
        self.code = code
        super().__init__(code)


def _walk_json(value: Any, *, path: str = "$") -> None:
    if value is None or isinstance(value, bool):
        return
    if isinstance(value, str):
        if "\x00" in value or any(0xD800 <= ord(character) <= 0xDFFF for character in value):
            raise ProtocolViolation("INVALID_REQUEST")
        return
    if isinstance(value, int):
        if abs(value) > MAX_SAFE_INTEGER:
            raise ProtocolViolation("INVALID_REQUEST")
        return
    if isinstance(value, float):
        raise ProtocolViolation("INVALID_REQUEST")
    if isinstance(value, list):
        for index, item in enumerate(value):
            _walk_json(item, path=f"{path}[{index}]")
        return
    if isinstance(value, Mapping):
        for key, item in value.items():
            if not isinstance(key, str):
                raise ProtocolViolation("INVALID_REQUEST")
            _walk_json(item, path=f"{path}.{key}")
        return
    raise ProtocolViolation("INVALID_REQUEST")


def canonical_json_bytes(value: Any) -> bytes:
    """Return ameme-canonical-json-v1 bytes or reject ambiguous JSON types."""
    _walk_json(value)
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")


def digest_json(value: Any) -> str:
    return "sha256_" + hashlib.sha256(canonical_json_bytes(value)).hexdigest()


def derive_idempotency_slot(raw_key: str, *, operation: str) -> str:
    """Test/helper derivation; only the resulting slot belongs on the wire."""
    if operation not in OPERATIONS or not isinstance(raw_key, str) or len(raw_key) < 8:
        raise ProtocolViolation("INVALID_REQUEST")
    material = (
        f"ameme:idempotency:v1:agent-local-node-wire:{operation}\0{raw_key}"
    ).encode("utf-8")
    return "idem_" + hashlib.sha256(material).hexdigest()


def _exact_keys(value: Any, expected: set[str]) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != expected:
        raise ProtocolViolation("INVALID_REQUEST")
    return value


def _bounded_string(value: Any, *, maximum: int = 128, allow_empty: bool = False) -> str:
    if not isinstance(value, str) or len(value) > maximum or (not allow_empty and not value):
        raise ProtocolViolation("INVALID_REQUEST")
    return value


def _identifier(value: Any) -> str:
    value = _bounded_string(value)
    if not ID_PATTERN.fullmatch(value):
        raise ProtocolViolation("INVALID_REQUEST")
    return value


def _timestamp(value: Any) -> str:
    value = _bounded_string(value, maximum=64)
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise ProtocolViolation("INVALID_REQUEST") from exc
    if parsed.tzinfo is None:
        raise ProtocolViolation("INVALID_REQUEST")
    return value


def _enum(value: Any, allowed: set[str] | frozenset[str]) -> str:
    if not isinstance(value, str) or value not in allowed:
        raise ProtocolViolation("INVALID_REQUEST")
    return value


def _scope_list(value: Any, *, memory_types: bool = False) -> list[str]:
    if not isinstance(value, list) or not value or len(value) > MAX_SCOPE_ITEMS:
        raise ProtocolViolation("INVALID_REQUEST")
    if memory_types:
        for item in value:
            _enum(item, {"event", "revision"})
    else:
        for item in value:
            _identifier(item)
    if value != sorted(value) or len(set(value)) != len(value):
        raise ProtocolViolation("INVALID_REQUEST")
    return value


def _payload_shape(
    payload: dict[str, Any],
    *,
    required: set[str],
    optional: set[str] | None = None,
) -> None:
    optional = optional or set()
    keys = set(payload)
    if not required.issubset(keys) or not keys.issubset(required | optional):
        raise ProtocolViolation("INVALID_REQUEST")


def _validate_payload(operation: str, payload: Any) -> tuple[list[str], list[str]]:
    if not isinstance(payload, dict):
        raise ProtocolViolation("INVALID_REQUEST")
    if len(canonical_json_bytes(payload)) > MAX_PAYLOAD_BYTES:
        raise ProtocolViolation("PAYLOAD_TOO_LARGE")

    if operation == "get_event":
        _payload_shape(payload, required={"event_id", "space", "memory_type"})
        _identifier(payload["event_id"])
        _identifier(payload["space"])
        _enum(payload["memory_type"], {"event", "revision"})
        return [payload["space"]], [payload["memory_type"]]

    if operation == "create_event":
        _payload_shape(
            payload,
            required={
                "space",
                "memory_type",
                "content",
                "event_type",
                "evidence_state",
                "fact_status",
                "sensitivity",
                "data_class",
                "now",
            },
            optional={"event_time"},
        )
        _identifier(payload["space"])
        _enum(payload["memory_type"], {"event"})
        _bounded_string(payload["content"], maximum=4_000)
        _enum(
            payload["event_type"],
            {
                "activity",
                "communication",
                "decision",
                "result",
                "state_change",
                "milestone",
                "experience",
            },
        )
        _enum(payload["evidence_state"], {"observed", "user_asserted", "inferred"})
        _enum(
            payload["fact_status"],
            {"confirmed", "user_asserted", "low_confidence_candidate"},
        )
        _enum(payload["sensitivity"], {"public", "personal", "confidential", "restricted"})
        _enum(payload["data_class"], {"structured"})
        _timestamp(payload["now"])
        if "event_time" in payload:
            _timestamp(payload["event_time"])
        return [payload["space"]], [payload["memory_type"]]

    if operation == "append_revision":
        _payload_shape(
            payload,
            required={
                "event_id",
                "space",
                "memory_type",
                "content",
                "evidence_state",
                "fact_status",
                "now",
            },
        )
        _identifier(payload["event_id"])
        _identifier(payload["space"])
        _enum(payload["memory_type"], {"revision"})
        _bounded_string(payload["content"], maximum=4_000)
        _enum(payload["evidence_state"], {"observed", "user_asserted", "inferred"})
        _enum(
            payload["fact_status"],
            {"confirmed", "user_asserted", "low_confidence_candidate"},
        )
        _timestamp(payload["now"])
        return [payload["space"]], [payload["memory_type"]]

    if operation == "undo_capture":
        _payload_shape(
            payload,
            required={"undo_token", "space", "memory_type", "now"},
        )
        _identifier(payload["undo_token"])
        _identifier(payload["space"])
        _enum(payload["memory_type"], {"event", "revision"})
        _timestamp(payload["now"])
        return [payload["space"]], [payload["memory_type"]]

    if operation == "visible_events":
        _payload_shape(
            payload,
            required={"spaces", "memory_types", "allow_high_risk", "limit"},
            optional={"query", "start_at", "end_at"},
        )
        spaces = _scope_list(payload["spaces"])
        memory_types = _scope_list(payload["memory_types"], memory_types=True)
        if not isinstance(payload["allow_high_risk"], bool):
            raise ProtocolViolation("INVALID_REQUEST")
        if not isinstance(payload["limit"], int) or isinstance(payload["limit"], bool):
            raise ProtocolViolation("INVALID_REQUEST")
        if not 1 <= payload["limit"] <= 100:
            raise ProtocolViolation("INVALID_REQUEST")
        if "query" in payload:
            _bounded_string(payload["query"], maximum=1_000, allow_empty=True)
        if "start_at" in payload:
            _timestamp(payload["start_at"])
        if "end_at" in payload:
            _timestamp(payload["end_at"])
        return spaces, memory_types

    if operation == "set_policy_blocked":
        _payload_shape(payload, required={"event_id", "space", "memory_type"})
        _identifier(payload["event_id"])
        _identifier(payload["space"])
        _enum(payload["memory_type"], {"event"})
        return [payload["space"]], [payload["memory_type"]]

    raise ProtocolViolation("INVALID_REQUEST")


def validate_request(request: Any) -> dict[str, Any]:
    request = _exact_keys(request, REQUEST_KEYS)
    if request["protocol_version"] != PROTOCOL_VERSION:
        raise ProtocolViolation("SCHEMA_UNSUPPORTED")
    _identifier(request["request_id"])
    control = _exact_keys(request["control"], CONTROL_KEYS)
    _identifier(control["caller_id"])
    _identifier(control["grant_id"])
    _identifier(control["purpose"])
    spaces = _scope_list(control["spaces"])
    memory_types = _scope_list(control["memory_types"], memory_types=True)
    operation = _enum(control["operation"], OPERATIONS)
    slot = _bounded_string(control["idempotency_slot"], maximum=69)
    if not slot.startswith("idem_") or not HEX_64_PATTERN.fullmatch(slot[5:]):
        raise ProtocolViolation("INVALID_REQUEST")
    payload_digest = _bounded_string(control["payload_digest"], maximum=71)
    if not payload_digest.startswith("sha256_") or not HEX_64_PATTERN.fullmatch(
        payload_digest[7:]
    ):
        raise ProtocolViolation("INVALID_REQUEST")
    requested_spaces, requested_types = _validate_payload(operation, request["payload"])
    if spaces != requested_spaces or memory_types != requested_types:
        raise ProtocolViolation("SCOPE_MISMATCH")
    if payload_digest != digest_json(request["payload"]):
        raise ProtocolViolation("PAYLOAD_DIGEST_MISMATCH")
    if len(canonical_json_bytes(request)) > MAX_REQUEST_BYTES:
        raise ProtocolViolation("PAYLOAD_TOO_LARGE")
    return deepcopy(request)


def authorize_request(request: Mapping[str, Any], grant: Any) -> dict[str, Any]:
    """Check a validated minimal request scope against an authenticated Grant view.

    The Grant is authorization context, not part of the v1 wire envelope. This
    function proves subset semantics only; it does not authenticate a channel.
    """
    validated = validate_request(request)
    grant = _exact_keys(
        grant,
        {"caller_id", "grant_id", "purposes", "spaces", "memory_types", "state"},
    )
    if (
        grant["caller_id"] != validated["control"]["caller_id"]
        or grant["grant_id"] != validated["control"]["grant_id"]
    ):
        raise ProtocolViolation("AUTH_REQUIRED")
    state = _enum(grant["state"], {"active", "revoked", "expired"})
    if state == "revoked":
        raise ProtocolViolation("GRANT_REVOKED")
    if state == "expired":
        raise ProtocolViolation("GRANT_EXPIRED")
    purposes = _scope_list(grant["purposes"])
    grant_spaces = _scope_list(grant["spaces"])
    grant_types = _scope_list(grant["memory_types"], memory_types=True)
    if validated["control"]["purpose"] not in purposes:
        raise ProtocolViolation("PURPOSE_DENIED")
    if not set(validated["control"]["spaces"]).issubset(grant_spaces):
        raise ProtocolViolation("SPACE_DENIED")
    if not set(validated["control"]["memory_types"]).issubset(grant_types):
        raise ProtocolViolation("DATA_TYPE_DENIED")
    return validated


def validate_response(
    response: Any,
    *,
    request: Mapping[str, Any] | None = None,
) -> dict[str, Any]:
    response = _exact_keys(response, RESPONSE_KEYS)
    if response["protocol_version"] != PROTOCOL_VERSION:
        raise ProtocolViolation("SCHEMA_UNSUPPORTED")
    _identifier(response["request_id"])
    if request is not None and response["request_id"] != request.get("request_id"):
        raise ProtocolViolation("RESPONSE_REQUEST_MISMATCH")
    status = _enum(response["status"], {"ok", "error"})
    if status == "ok":
        if not isinstance(response["result"], dict) or response["error"] is not None:
            raise ProtocolViolation("INVALID_REQUEST")
        expected_digest = digest_json(response["result"])
        if response["result_digest"] != expected_digest:
            raise ProtocolViolation("RESULT_DIGEST_MISMATCH")
    else:
        if response["result"] is not None or response["result_digest"] is not None:
            raise ProtocolViolation("INVALID_REQUEST")
        error = _exact_keys(response["error"], {"code", "retryable"})
        code = _enum(error["code"], ERROR_CODES)
        if not isinstance(error["retryable"], bool):
            raise ProtocolViolation("INVALID_REQUEST")
        if error["retryable"] is not ERROR_RETRYABLE[code]:
            raise ProtocolViolation("INVALID_REQUEST")
    if len(canonical_json_bytes(response)) > MAX_RESPONSE_BYTES:
        raise ProtocolViolation("PAYLOAD_TOO_LARGE")
    return deepcopy(response)


def _parse_line(line: bytes, *, maximum: int) -> Any:
    if not isinstance(line, bytes) or not line or len(line) > maximum:
        raise ProtocolViolation("PAYLOAD_TOO_LARGE")
    if line.startswith(b"\xef\xbb\xbf") or b"\x00" in line:
        raise ProtocolViolation("INVALID_REQUEST")
    if line.count(b"\n") > 1 or (b"\n" in line and not line.endswith(b"\n")):
        raise ProtocolViolation("INVALID_REQUEST")
    payload = line[:-1] if line.endswith(b"\n") else line
    try:
        text = payload.decode("utf-8", errors="strict")
        value = json.loads(text, object_pairs_hook=_reject_duplicate_keys)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ProtocolViolation("INVALID_REQUEST") from exc
    return value


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        if key in value:
            raise ProtocolViolation("INVALID_REQUEST")
        value[key] = item
    return value


def parse_strict_json_line(line: bytes, *, maximum: int) -> Any:
    """Parse one bounded JSON line with the protocol's ambiguity checks."""
    value = _parse_line(line, maximum=maximum)
    canonical_json_bytes(value)
    return deepcopy(value)


def parse_request_line(line: bytes) -> dict[str, Any]:
    return validate_request(parse_strict_json_line(line, maximum=MAX_REQUEST_BYTES))


def parse_response_line(
    line: bytes,
    *,
    request: Mapping[str, Any] | None = None,
) -> dict[str, Any]:
    return validate_response(
        parse_strict_json_line(line, maximum=MAX_RESPONSE_BYTES), request=request
    )


class ReplayLedger:
    """Deterministic v1 idempotency semantics, not a production store."""

    def __init__(self) -> None:
        self._seen: dict[str, str] = {}

    def accept(self, request: Mapping[str, Any]) -> str:
        validated = validate_request(request)
        control = validated["control"]
        fingerprint = digest_json(
            {
                "caller_id": control["caller_id"],
                "grant_id": control["grant_id"],
                "purpose": control["purpose"],
                "spaces": control["spaces"],
                "memory_types": control["memory_types"],
                "operation": control["operation"],
                "payload_digest": control["payload_digest"],
            }
        )
        slot = control["idempotency_slot"]
        previous = self._seen.get(slot)
        if previous is None:
            self._seen[slot] = fingerprint
            return "NEW"
        if previous != fingerprint:
            raise ProtocolViolation("IDEMPOTENCY_CONFLICT")
        return "REPLAY"
