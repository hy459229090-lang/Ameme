"""Deterministic synthetic conformance vectors for Agent Local Node v1."""

from __future__ import annotations

from copy import deepcopy
from typing import Any, Mapping

from .protocol import (
    CANONICAL_JSON,
    ERROR_CODES,
    MAX_PAYLOAD_BYTES,
    MAX_REQUEST_BYTES,
    MAX_RESPONSE_BYTES,
    OPERATIONS,
    PROTOCOL_VERSION,
    ProtocolViolation,
    ReplayLedger,
    authorize_request,
    derive_idempotency_slot,
    digest_json,
    validate_request,
    validate_response,
)


def _request(
    vector_id: str,
    operation: str,
    payload: dict[str, Any],
    *,
    spaces: list[str],
    memory_types: list[str],
) -> dict[str, Any]:
    return {
        "protocol_version": PROTOCOL_VERSION,
        "request_id": f"req_{vector_id}",
        "control": {
            "caller_id": "agent_synthetic",
            "grant_id": "grant_synthetic",
            "purpose": "autonomous_memory",
            "spaces": spaces,
            "memory_types": memory_types,
            "operation": operation,
            "idempotency_slot": derive_idempotency_slot(
                f"synthetic-{vector_id}-key", operation=operation
            ),
            "payload_digest": digest_json(payload),
        },
        "payload": payload,
    }


def _positive_requests() -> list[dict[str, Any]]:
    timestamp = "2026-07-14T04:00:00Z"
    requests = [
        _request(
            "get_event",
            "get_event",
            {
                "event_id": "evt_synthetic_001",
                "space": "space_work",
                "memory_type": "event",
            },
            spaces=["space_work"],
            memory_types=["event"],
        ),
        _request(
            "create_event",
            "create_event",
            {
                "space": "space_work",
                "memory_type": "event",
                "content": "Synthetic milestone was verified by a tool.",
                "event_type": "result",
                "evidence_state": "observed",
                "fact_status": "confirmed",
                "sensitivity": "personal",
                "data_class": "structured",
                "now": timestamp,
                "event_time": timestamp,
            },
            spaces=["space_work"],
            memory_types=["event"],
        ),
        _request(
            "append_revision",
            "append_revision",
            {
                "event_id": "evt_synthetic_001",
                "space": "space_work",
                "memory_type": "revision",
                "content": "Synthetic user correction.",
                "evidence_state": "user_asserted",
                "fact_status": "user_asserted",
                "now": timestamp,
            },
            spaces=["space_work"],
            memory_types=["revision"],
        ),
        _request(
            "undo_capture",
            "undo_capture",
            {
                "undo_token": "undo_synthetic_001",
                "space": "space_work",
                "memory_type": "event",
                "now": timestamp,
            },
            spaces=["space_work"],
            memory_types=["event"],
        ),
        _request(
            "visible_events",
            "visible_events",
            {
                "spaces": ["space_personal", "space_work"],
                "memory_types": ["event"],
                "query": "synthetic milestone",
                "allow_high_risk": False,
                "limit": 20,
                "start_at": "2026-07-01T00:00:00Z",
                "end_at": "2026-07-31T23:59:59Z",
            },
            spaces=["space_personal", "space_work"],
            memory_types=["event"],
        ),
        _request(
            "set_policy_blocked",
            "set_policy_blocked",
            {
                "event_id": "evt_synthetic_unsafe",
                "space": "space_work",
                "memory_type": "event",
            },
            spaces=["space_work"],
            memory_types=["event"],
        ),
    ]
    return [
        {
            "id": request["control"]["operation"],
            "request": request,
            "expected_canonical_request_digest": digest_json(request),
        }
        for request in requests
    ]


def _negative_requests(positive: list[dict[str, Any]]) -> list[dict[str, Any]]:
    create = positive[1]["request"]
    visible = positive[4]["request"]
    cases: list[tuple[str, dict[str, Any], str]] = []

    unsupported = deepcopy(create)
    unsupported["protocol_version"] = "ameme.agent-local-node.v2"
    cases.append(("unsupported_major", unsupported, "SCHEMA_UNSUPPORTED"))

    digest_mismatch = deepcopy(create)
    digest_mismatch["payload"]["content"] = "Changed without digest update."
    cases.append(
        ("payload_digest_mismatch", digest_mismatch, "PAYLOAD_DIGEST_MISMATCH")
    )

    raw_key = deepcopy(create)
    raw_key["control"]["idempotency_slot"] = "synthetic-raw-idempotency-key"
    cases.append(("raw_idempotency_key_on_wire", raw_key, "INVALID_REQUEST"))

    space_scope = deepcopy(create)
    space_scope["control"]["spaces"] = ["space_personal"]
    cases.append(("minimal_space_scope_mismatch", space_scope, "SCOPE_MISMATCH"))

    type_scope = deepcopy(create)
    type_scope["control"]["memory_types"] = ["revision"]
    cases.append(("minimal_type_scope_mismatch", type_scope, "SCOPE_MISMATCH"))

    unsorted = deepcopy(visible)
    unsorted["control"]["spaces"] = ["space_work", "space_personal"]
    cases.append(("unsorted_scope", unsorted, "INVALID_REQUEST"))

    unknown_operation = deepcopy(create)
    unknown_operation["control"]["operation"] = "delete_database"
    cases.append(("unknown_operation", unknown_operation, "INVALID_REQUEST"))

    too_large = deepcopy(create)
    too_large["payload"]["content"] = "x" * (MAX_PAYLOAD_BYTES + 1)
    too_large["control"]["payload_digest"] = digest_json(too_large["payload"])
    cases.append(("payload_over_32_kib", too_large, "PAYLOAD_TOO_LARGE"))

    unknown_field = deepcopy(create)
    unknown_field["unexpected"] = True
    cases.append(("unknown_envelope_field", unknown_field, "INVALID_REQUEST"))

    return [
        {"id": vector_id, "request": request, "expected_error": expected}
        for vector_id, request, expected in cases
    ]


def _responses(positive: list[dict[str, Any]]) -> dict[str, Any]:
    request = positive[1]["request"]
    result = {
        "object_type": "event",
        "event_id": "evt_synthetic_001",
        "revision": 1,
    }
    success = {
        "protocol_version": PROTOCOL_VERSION,
        "request_id": request["request_id"],
        "status": "ok",
        "result": result,
        "result_digest": digest_json(result),
        "error": None,
    }
    not_visible = {
        "protocol_version": PROTOCOL_VERSION,
        "request_id": request["request_id"],
        "status": "error",
        "result": None,
        "result_digest": None,
        "error": {"code": "NOT_VISIBLE", "retryable": False},
    }
    operation_unsupported = {
        "protocol_version": PROTOCOL_VERSION,
        "request_id": request["request_id"],
        "status": "error",
        "result": None,
        "result_digest": None,
        "error": {"code": "OPERATION_UNSUPPORTED", "retryable": False},
    }
    mismatched = deepcopy(success)
    mismatched["request_id"] = "req_synthetic_other"
    bad_result_digest = deepcopy(success)
    bad_result_digest["result"]["revision"] = 2
    return {
        "positive": [
            {"id": "success", "request_id": request["request_id"], "response": success},
            {
                "id": "not_visible_no_existence_detail",
                "request_id": request["request_id"],
                "response": not_visible,
            },
            {
                "id": "known_operation_not_supported_by_channel",
                "request_id": request["request_id"],
                "response": operation_unsupported,
            },
        ],
        "negative": [
            {
                "id": "response_request_mismatch",
                "request_id": request["request_id"],
                "response": mismatched,
                "expected_error": "RESPONSE_REQUEST_MISMATCH",
            },
            {
                "id": "result_digest_mismatch",
                "request_id": request["request_id"],
                "response": bad_result_digest,
                "expected_error": "RESULT_DIGEST_MISMATCH",
            },
        ],
    }


def _authorization(positive: list[dict[str, Any]]) -> list[dict[str, Any]]:
    request = positive[1]["request"]
    base = {
        "caller_id": "agent_synthetic",
        "grant_id": "grant_synthetic",
        "purposes": ["autonomous_memory", "manual_recall"],
        "spaces": ["space_personal", "space_work"],
        "memory_types": ["event", "revision"],
        "state": "active",
    }
    cases: list[dict[str, Any]] = [
        {
            "id": "grant_superset_request_subset_allowed",
            "request_id": request["request_id"],
            "grant": base,
            "expected": "AUTHORIZED",
        }
    ]
    mutations = (
        ("wrong_caller", {"caller_id": "agent_other"}, "AUTH_REQUIRED"),
        ("revoked_grant", {"state": "revoked"}, "GRANT_REVOKED"),
        ("expired_grant", {"state": "expired"}, "GRANT_EXPIRED"),
        ("purpose_denied", {"purposes": ["manual_recall"]}, "PURPOSE_DENIED"),
        ("space_denied", {"spaces": ["space_personal"]}, "SPACE_DENIED"),
        ("type_denied", {"memory_types": ["revision"]}, "DATA_TYPE_DENIED"),
    )
    for case_id, changes, expected in mutations:
        grant = deepcopy(base)
        grant.update(changes)
        cases.append(
            {
                "id": case_id,
                "request_id": request["request_id"],
                "grant": grant,
                "expected": expected,
            }
        )
    return cases


def _replay(positive: list[dict[str, Any]]) -> list[dict[str, Any]]:
    request = positive[1]["request"]
    changed = deepcopy(request)
    changed["request_id"] = "req_create_event_retry_changed"
    changed["payload"]["content"] = "Changed synthetic semantics."
    changed["control"]["payload_digest"] = digest_json(changed["payload"])
    return [
        {
            "id": "same_slot_same_semantics_replays",
            "requests": [request, deepcopy(request)],
            "expected": ["NEW", "REPLAY"],
        },
        {
            "id": "same_slot_changed_semantics_conflicts",
            "requests": [request, changed],
            "expected": ["NEW", "IDEMPOTENCY_CONFLICT"],
        },
    ]


def build_conformance_document() -> dict[str, Any]:
    positive = _positive_requests()
    return {
        "fixture_version": 1,
        "synthetic_only": True,
        "protocol_version": PROTOCOL_VERSION,
        "schema": "packages/contracts/schemas/ameme-agent-local-node.schema.json",
        "canonicalization": {
            "name": CANONICAL_JSON,
            "encoding": "UTF-8 without BOM",
            "object_keys": "lexicographic Unicode code point order",
            "arrays": "preserve declared order",
            "scope_arrays": "sorted unique before encoding",
            "whitespace": "none",
            "non_ascii": "literal UTF-8 JSON strings",
            "numbers": "base-10 safe integers only; floats and NaN rejected",
            "digest_output": "sha256_ plus lowercase hexadecimal SHA-256",
        },
        "limits": {
            "request_line_utf8_bytes": MAX_REQUEST_BYTES,
            "payload_canonical_utf8_bytes": MAX_PAYLOAD_BYTES,
            "response_line_utf8_bytes": MAX_RESPONSE_BYTES,
        },
        "operations": sorted(OPERATIONS),
        "stable_error_codes": sorted(ERROR_CODES),
        "positive_requests": positive,
        "negative_requests": _negative_requests(positive),
        "responses": _responses(positive),
        "authorization_cases": _authorization(positive),
        "replay_cases": _replay(positive),
        "non_claims": [
            "no LAN discovery or reachability evidence",
            "no channel or device authentication evidence",
            "no transport encryption or key-management evidence",
            "no Android process lifecycle or physical-device evidence",
            "no production runtime implementation",
        ],
    }


def _request_by_id(document: Mapping[str, Any], request_id: str) -> dict[str, Any]:
    for item in document["positive_requests"]:
        request = item["request"]
        if request["request_id"] == request_id:
            return request
    raise KeyError(request_id)


def validate_conformance_document(document: Mapping[str, Any]) -> list[str]:
    errors: list[str] = []
    if document != build_conformance_document():
        errors.append("committed conformance document differs from deterministic materialization")
        return errors

    for item in document["positive_requests"]:
        try:
            request = validate_request(item["request"])
            if digest_json(request) != item["expected_canonical_request_digest"]:
                errors.append(f"{item['id']}: canonical request digest drift")
        except ProtocolViolation as exc:
            errors.append(f"{item['id']}: unexpected {exc.code}")

    for item in document["negative_requests"]:
        try:
            validate_request(item["request"])
        except ProtocolViolation as exc:
            if exc.code != item["expected_error"]:
                errors.append(
                    f"{item['id']}: expected {item['expected_error']}, got {exc.code}"
                )
        else:
            errors.append(f"{item['id']}: negative request was accepted")

    for item in document["responses"]["positive"]:
        request = _request_by_id(document, item["request_id"])
        try:
            validate_response(item["response"], request=request)
        except ProtocolViolation as exc:
            errors.append(f"{item['id']}: unexpected {exc.code}")
        if item["id"].startswith("not_visible") and set(item["response"]["error"]) != {
            "code",
            "retryable",
        }:
            errors.append(f"{item['id']}: NOT_VISIBLE leaked detail fields")

    for item in document["responses"]["negative"]:
        request = _request_by_id(document, item["request_id"])
        try:
            validate_response(item["response"], request=request)
        except ProtocolViolation as exc:
            if exc.code != item["expected_error"]:
                errors.append(
                    f"{item['id']}: expected {item['expected_error']}, got {exc.code}"
                )
        else:
            errors.append(f"{item['id']}: negative response was accepted")

    for item in document["authorization_cases"]:
        request = _request_by_id(document, item["request_id"])
        try:
            authorize_request(request, item["grant"])
        except ProtocolViolation as exc:
            actual = exc.code
        else:
            actual = "AUTHORIZED"
        if actual != item["expected"]:
            errors.append(f"{item['id']}: expected {item['expected']}, got {actual}")

    for item in document["replay_cases"]:
        ledger = ReplayLedger()
        actual: list[str] = []
        for request in item["requests"]:
            try:
                actual.append(ledger.accept(request))
            except ProtocolViolation as exc:
                actual.append(exc.code)
        if actual != item["expected"]:
            errors.append(f"{item['id']}: expected {item['expected']}, got {actual}")
    return errors
