"""Authenticated TLS channel envelope for Agent Local Node application protocol v1."""

from __future__ import annotations

import base64
import binascii
from copy import deepcopy
import hashlib
import hmac
import ipaddress
import re
from typing import Any, Iterable, Mapping

from .protocol import (
    MAX_REQUEST_BYTES,
    MAX_RESPONSE_BYTES,
    OPERATIONS,
    PROTOCOL_VERSION,
    ProtocolViolation,
    canonical_json_bytes,
    parse_request_line,
    parse_response_line,
    parse_strict_json_line,
)


CHANNEL_PROTOCOL_VERSION = "ameme.agent-local-node.channel.v1"
MAX_PAIRING_MATERIAL_BYTES = 8_192
MAX_CHANNEL_LINE_BYTES = 786_432
NONCE_PATTERN = re.compile(r"^nonce_[0-9a-f]{64}$")
DIGEST_PATTERN = re.compile(r"^sha256_[0-9a-f]{64}$")
PROOF_PATTERN = re.compile(r"^hmac_[0-9a-f]{64}$")
IDEMPOTENCY_PATTERN = re.compile(r"^idem_[0-9a-f]{64}$")
ID_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
REF_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._/-]{0,239}$")
HOST_PATTERN = re.compile(r"^[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$")
PAIRING_KEYS = {
    "channel_protocol",
    "endpoint_ref",
    "credential_ref",
    "expected_device_id",
    "session_binding_ref",
    "pairing_id",
    "host",
    "port",
    "tls_certificate_sha256",
}
CLIENT_HELLO_KEYS = {
    "channel_protocol",
    "message_type",
    "pairing_id",
    "expected_device_id",
    "session_binding_ref",
    "tls_certificate_sha256",
    "client_nonce",
    "sequence",
    "proof",
}
SERVER_HELLO_KEYS = {
    "channel_protocol",
    "message_type",
    "pairing_id",
    "device_id",
    "session_binding_ref",
    "tls_certificate_sha256",
    "client_nonce",
    "server_nonce",
    "session_id",
    "supported_operations",
    "sequence",
    "proof",
}
REQUEST_FRAME_KEYS = {
    "channel_protocol",
    "message_type",
    "session_id",
    "sequence",
    "nonce",
    "idempotency_ref",
    "application_protocol",
    "application_digest",
    "application_b64",
    "proof",
}
RESPONSE_FRAME_KEYS = {
    "channel_protocol",
    "message_type",
    "session_id",
    "sequence",
    "request_nonce",
    "nonce",
    "idempotency_ref",
    "application_protocol",
    "application_digest",
    "application_b64",
    "proof",
}
CHANNEL_ERROR_CODES = frozenset(
    {
        "INVALID_CHANNEL_MESSAGE",
        "CHANNEL_AUTH_FAILED",
        "CHANNEL_BINDING_FAILED",
        "CHANNEL_SEQUENCE_INVALID",
        "CHANNEL_REPLAY",
        "CHANNEL_PAYLOAD_INVALID",
    }
)


class ChannelViolation(ValueError):
    """Stable content-free channel contract failure."""

    def __init__(self, code: str) -> None:
        if code not in CHANNEL_ERROR_CODES:
            raise ValueError("unknown channel error code")
        self.code = code
        super().__init__(code)


def _exact(value: Any, keys: set[str]) -> dict[str, Any]:
    if not isinstance(value, dict) or set(value) != keys:
        raise ChannelViolation("INVALID_CHANNEL_MESSAGE")
    return value


def _identifier(value: Any) -> str:
    if not isinstance(value, str) or not ID_PATTERN.fullmatch(value):
        raise ChannelViolation("INVALID_CHANNEL_MESSAGE")
    return value


def _reference(value: Any, prefix: str) -> str:
    if not isinstance(value, str) or not value.startswith(prefix):
        raise ChannelViolation("INVALID_CHANNEL_MESSAGE")
    if not REF_PATTERN.fullmatch(value.removeprefix(prefix)):
        raise ChannelViolation("INVALID_CHANNEL_MESSAGE")
    return value


def _nonce(value: Any) -> str:
    if not isinstance(value, str) or not NONCE_PATTERN.fullmatch(value):
        raise ChannelViolation("INVALID_CHANNEL_MESSAGE")
    return value


def _digest(value: Any) -> str:
    if not isinstance(value, str) or not DIGEST_PATTERN.fullmatch(value):
        raise ChannelViolation("INVALID_CHANNEL_MESSAGE")
    return value


def _sequence(value: Any, *, allow_zero: bool = False) -> int:
    minimum = 0 if allow_zero else 1
    if (
        not isinstance(value, int)
        or isinstance(value, bool)
        or value < minimum
        or value > 9_223_372_036_854_775_807
    ):
        raise ChannelViolation("CHANNEL_SEQUENCE_INVALID")
    return value


def _idempotency_ref(value: Any) -> str:
    if not isinstance(value, str) or not IDEMPOTENCY_PATTERN.fullmatch(value):
        raise ChannelViolation("INVALID_CHANNEL_MESSAGE")
    return value


def _operations(value: Any) -> list[str]:
    if (
        not isinstance(value, list)
        or not value
        or any(not isinstance(item, str) for item in value)
        or value != sorted(set(value))
        or not set(value).issubset(OPERATIONS)
    ):
        raise ChannelViolation("INVALID_CHANNEL_MESSAGE")
    return value


def _secret(value: bytes | bytearray) -> bytes:
    if not isinstance(value, (bytes, bytearray)) or not 32 <= len(value) <= 256:
        raise ChannelViolation("CHANNEL_AUTH_FAILED")
    return bytes(value)


def _sha256_bytes(value: bytes) -> str:
    return "sha256_" + hashlib.sha256(value).hexdigest()


def _proof(key: bytes, label: str, document: Mapping[str, Any]) -> str:
    message = label.encode("ascii") + b"\0" + canonical_json_bytes(document)
    return "hmac_" + hmac.new(key, message, hashlib.sha256).hexdigest()


def _without_proof(document: Mapping[str, Any]) -> dict[str, Any]:
    return {key: value for key, value in document.items() if key != "proof"}


def _verify_proof(
    key: bytes,
    label: str,
    document: Mapping[str, Any],
) -> None:
    proof = document.get("proof")
    if not isinstance(proof, str) or not PROOF_PATTERN.fullmatch(proof):
        raise ChannelViolation("CHANNEL_AUTH_FAILED")
    if not hmac.compare_digest(proof, _proof(key, label, _without_proof(document))):
        raise ChannelViolation("CHANNEL_AUTH_FAILED")


def validate_pairing_material(value: Any) -> dict[str, Any]:
    value = _exact(value, PAIRING_KEYS)
    if value["channel_protocol"] != CHANNEL_PROTOCOL_VERSION:
        raise ChannelViolation("INVALID_CHANNEL_MESSAGE")
    _reference(value["endpoint_ref"], "endpoint-ref:")
    _reference(value["credential_ref"], "credential-ref:")
    _identifier(value["expected_device_id"])
    _reference(value["session_binding_ref"], "session-binding-ref:")
    _identifier(value["pairing_id"])
    host = value["host"]
    if not isinstance(host, str) or not host or len(host) > 253:
        raise ChannelViolation("INVALID_CHANNEL_MESSAGE")
    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        if not HOST_PATTERN.fullmatch(host):
            raise ChannelViolation("INVALID_CHANNEL_MESSAGE")
    else:
        if address.is_unspecified:
            raise ChannelViolation("INVALID_CHANNEL_MESSAGE")
    port = value["port"]
    if not isinstance(port, int) or isinstance(port, bool) or not 1 <= port <= 65_535:
        raise ChannelViolation("INVALID_CHANNEL_MESSAGE")
    _digest(value["tls_certificate_sha256"])
    return deepcopy(value)


def parse_pairing_material(line: bytes) -> dict[str, Any]:
    try:
        value = parse_strict_json_line(line, maximum=MAX_PAIRING_MATERIAL_BYTES)
    except ProtocolViolation as exc:
        raise ChannelViolation("INVALID_CHANNEL_MESSAGE") from exc
    return validate_pairing_material(value)


def build_client_hello(
    pairing: Mapping[str, Any],
    secret: bytes | bytearray,
    *,
    client_nonce: str,
) -> bytes:
    pairing = validate_pairing_material(pairing)
    key = _secret(secret)
    document = {
        "channel_protocol": CHANNEL_PROTOCOL_VERSION,
        "message_type": "client_hello",
        "pairing_id": pairing["pairing_id"],
        "expected_device_id": pairing["expected_device_id"],
        "session_binding_ref": pairing["session_binding_ref"],
        "tls_certificate_sha256": pairing["tls_certificate_sha256"],
        "client_nonce": _nonce(client_nonce),
        "sequence": 0,
    }
    document["proof"] = _proof(key, "client-hello", document)
    return canonical_json_bytes(document)


def verify_client_hello(
    line: bytes,
    pairing: Mapping[str, Any],
    secret: bytes | bytearray,
) -> dict[str, Any]:
    pairing = validate_pairing_material(pairing)
    key = _secret(secret)
    try:
        document = _exact(
            parse_strict_json_line(line, maximum=MAX_CHANNEL_LINE_BYTES),
            CLIENT_HELLO_KEYS,
        )
    except ProtocolViolation as exc:
        raise ChannelViolation("INVALID_CHANNEL_MESSAGE") from exc
    if (
        document["channel_protocol"] != CHANNEL_PROTOCOL_VERSION
        or document["message_type"] != "client_hello"
        or document["pairing_id"] != pairing["pairing_id"]
        or document["expected_device_id"] != pairing["expected_device_id"]
        or document["session_binding_ref"] != pairing["session_binding_ref"]
        or document["tls_certificate_sha256"]
        != pairing["tls_certificate_sha256"]
        or document["sequence"] != 0
    ):
        raise ChannelViolation("CHANNEL_BINDING_FAILED")
    _nonce(document["client_nonce"])
    _verify_proof(key, "client-hello", document)
    return deepcopy(document)


def build_server_hello(
    client_hello: Mapping[str, Any],
    pairing: Mapping[str, Any],
    secret: bytes | bytearray,
    *,
    server_nonce: str,
    session_id: str,
    supported_operations: Iterable[str],
) -> bytes:
    pairing = validate_pairing_material(pairing)
    key = _secret(secret)
    try:
        operations = _operations(sorted(set(supported_operations)))
    except TypeError as exc:
        raise ChannelViolation("INVALID_CHANNEL_MESSAGE") from exc
    document = {
        "channel_protocol": CHANNEL_PROTOCOL_VERSION,
        "message_type": "server_hello",
        "pairing_id": pairing["pairing_id"],
        "device_id": pairing["expected_device_id"],
        "session_binding_ref": pairing["session_binding_ref"],
        "tls_certificate_sha256": pairing["tls_certificate_sha256"],
        "client_nonce": _nonce(client_hello.get("client_nonce")),
        "server_nonce": _nonce(server_nonce),
        "session_id": _identifier(session_id),
        "supported_operations": operations,
        "sequence": 0,
    }
    document["proof"] = _proof(key, "server-hello", document)
    return canonical_json_bytes(document)


def verify_server_hello(
    line: bytes,
    client_hello: Mapping[str, Any],
    pairing: Mapping[str, Any],
    secret: bytes | bytearray,
) -> dict[str, Any]:
    pairing = validate_pairing_material(pairing)
    key = _secret(secret)
    try:
        document = _exact(
            parse_strict_json_line(line, maximum=MAX_CHANNEL_LINE_BYTES),
            SERVER_HELLO_KEYS,
        )
    except ProtocolViolation as exc:
        raise ChannelViolation("INVALID_CHANNEL_MESSAGE") from exc
    operations = document.get("supported_operations")
    if (
        document["channel_protocol"] != CHANNEL_PROTOCOL_VERSION
        or document["message_type"] != "server_hello"
        or document["pairing_id"] != pairing["pairing_id"]
        or document["device_id"] != pairing["expected_device_id"]
        or document["session_binding_ref"] != pairing["session_binding_ref"]
        or document["tls_certificate_sha256"]
        != pairing["tls_certificate_sha256"]
        or document["client_nonce"] != client_hello.get("client_nonce")
        or document["sequence"] != 0
    ):
        raise ChannelViolation("CHANNEL_BINDING_FAILED")
    _operations(operations)
    _nonce(document["server_nonce"])
    _identifier(document["session_id"])
    _verify_proof(key, "server-hello", document)
    return deepcopy(document)


def derive_session_key(
    client_hello: Mapping[str, Any],
    server_hello: Mapping[str, Any],
    secret: bytes | bytearray,
) -> bytes:
    key = _secret(secret)
    material = canonical_json_bytes(
        {
            "channel_protocol": CHANNEL_PROTOCOL_VERSION,
            "pairing_id": server_hello["pairing_id"],
            "device_id": server_hello["device_id"],
            "session_binding_ref": server_hello["session_binding_ref"],
            "tls_certificate_sha256": server_hello["tls_certificate_sha256"],
            "client_nonce": client_hello["client_nonce"],
            "server_nonce": server_hello["server_nonce"],
            "session_id": server_hello["session_id"],
        }
    )
    return hmac.new(key, b"session-key\0" + material, hashlib.sha256).digest()


def _application_bytes(value: Any, maximum: int) -> bytes:
    if not isinstance(value, bytes) or not value or len(value) > maximum:
        raise ChannelViolation("CHANNEL_PAYLOAD_INVALID")
    return value


def _encoded_application(value: bytes) -> str:
    return base64.b64encode(value).decode("ascii")


def _decoded_application(value: Any, maximum: int) -> bytes:
    if not isinstance(value, str):
        raise ChannelViolation("CHANNEL_PAYLOAD_INVALID")
    try:
        decoded = base64.b64decode(value, validate=True)
    except (ValueError, binascii.Error) as exc:
        raise ChannelViolation("CHANNEL_PAYLOAD_INVALID") from exc
    return _application_bytes(decoded, maximum)


def build_request_frame(
    application_line: bytes,
    session_key: bytes | bytearray,
    *,
    session_id: str,
    sequence: int,
    nonce: str,
) -> bytes:
    application_line = _application_bytes(application_line, MAX_REQUEST_BYTES)
    try:
        request = parse_request_line(application_line)
    except ProtocolViolation as exc:
        raise ChannelViolation("CHANNEL_PAYLOAD_INVALID") from exc
    document = {
        "channel_protocol": CHANNEL_PROTOCOL_VERSION,
        "message_type": "request",
        "session_id": _identifier(session_id),
        "sequence": _sequence(sequence),
        "nonce": _nonce(nonce),
        "idempotency_ref": _idempotency_ref(
            request["control"]["idempotency_slot"]
        ),
        "application_protocol": PROTOCOL_VERSION,
        "application_digest": _sha256_bytes(application_line),
        "application_b64": _encoded_application(application_line),
    }
    document["proof"] = _proof(_secret(session_key), "request-frame", document)
    return canonical_json_bytes(document)


def parse_request_frame(
    line: bytes,
    session_key: bytes | bytearray,
    *,
    expected_session_id: str,
    expected_sequence: int,
    seen_nonces: set[str],
) -> tuple[dict[str, Any], bytes, dict[str, Any]]:
    try:
        document = _exact(
            parse_strict_json_line(line, maximum=MAX_CHANNEL_LINE_BYTES),
            REQUEST_FRAME_KEYS,
        )
    except ProtocolViolation as exc:
        raise ChannelViolation("INVALID_CHANNEL_MESSAGE") from exc
    if (
        document["channel_protocol"] != CHANNEL_PROTOCOL_VERSION
        or document["message_type"] != "request"
        or document["session_id"] != expected_session_id
        or document["application_protocol"] != PROTOCOL_VERSION
    ):
        raise ChannelViolation("CHANNEL_BINDING_FAILED")
    if _sequence(document["sequence"]) != expected_sequence:
        raise ChannelViolation("CHANNEL_SEQUENCE_INVALID")
    nonce = _nonce(document["nonce"])
    if nonce in seen_nonces:
        raise ChannelViolation("CHANNEL_REPLAY")
    _verify_proof(_secret(session_key), "request-frame", document)
    application = _decoded_application(
        document["application_b64"], MAX_REQUEST_BYTES
    )
    if _digest(document["application_digest"]) != _sha256_bytes(application):
        raise ChannelViolation("CHANNEL_PAYLOAD_INVALID")
    try:
        request = parse_request_line(application)
    except ProtocolViolation as exc:
        raise ChannelViolation("CHANNEL_PAYLOAD_INVALID") from exc
    if _idempotency_ref(document["idempotency_ref"]) != request["control"][
        "idempotency_slot"
    ]:
        raise ChannelViolation("CHANNEL_BINDING_FAILED")
    seen_nonces.add(nonce)
    return deepcopy(document), application, request


def build_response_frame(
    application_line: bytes,
    request_frame: Mapping[str, Any],
    session_key: bytes | bytearray,
    *,
    nonce: str,
) -> bytes:
    application_line = _application_bytes(application_line, MAX_RESPONSE_BYTES)
    try:
        parse_strict_json_line(application_line, maximum=MAX_RESPONSE_BYTES)
    except ProtocolViolation as exc:
        raise ChannelViolation("CHANNEL_PAYLOAD_INVALID") from exc
    document = {
        "channel_protocol": CHANNEL_PROTOCOL_VERSION,
        "message_type": "response",
        "session_id": _identifier(request_frame["session_id"]),
        "sequence": _sequence(request_frame["sequence"]),
        "request_nonce": _nonce(request_frame["nonce"]),
        "nonce": _nonce(nonce),
        "idempotency_ref": _idempotency_ref(request_frame["idempotency_ref"]),
        "application_protocol": PROTOCOL_VERSION,
        "application_digest": _sha256_bytes(application_line),
        "application_b64": _encoded_application(application_line),
    }
    document["proof"] = _proof(_secret(session_key), "response-frame", document)
    return canonical_json_bytes(document)


def parse_response_frame(
    line: bytes,
    session_key: bytes | bytearray,
    *,
    request_frame: Mapping[str, Any],
    application_request: Mapping[str, Any],
    seen_nonces: set[str],
) -> tuple[dict[str, Any], bytes, dict[str, Any]]:
    try:
        document = _exact(
            parse_strict_json_line(line, maximum=MAX_CHANNEL_LINE_BYTES),
            RESPONSE_FRAME_KEYS,
        )
    except ProtocolViolation as exc:
        raise ChannelViolation("INVALID_CHANNEL_MESSAGE") from exc
    if (
        document["channel_protocol"] != CHANNEL_PROTOCOL_VERSION
        or document["message_type"] != "response"
        or document["session_id"] != request_frame.get("session_id")
        or document["sequence"] != request_frame.get("sequence")
        or document["request_nonce"] != request_frame.get("nonce")
        or document["idempotency_ref"] != request_frame.get("idempotency_ref")
        or document["application_protocol"] != PROTOCOL_VERSION
    ):
        raise ChannelViolation("CHANNEL_BINDING_FAILED")
    _sequence(document["sequence"])
    _idempotency_ref(document["idempotency_ref"])
    nonce = _nonce(document["nonce"])
    if nonce == request_frame.get("nonce") or nonce in seen_nonces:
        raise ChannelViolation("CHANNEL_REPLAY")
    _verify_proof(_secret(session_key), "response-frame", document)
    application = _decoded_application(
        document["application_b64"], MAX_RESPONSE_BYTES
    )
    if _digest(document["application_digest"]) != _sha256_bytes(application):
        raise ChannelViolation("CHANNEL_PAYLOAD_INVALID")
    try:
        response = parse_response_line(application, request=application_request)
    except ProtocolViolation as exc:
        raise ChannelViolation("CHANNEL_PAYLOAD_INVALID") from exc
    seen_nonces.add(nonce)
    return deepcopy(document), application, response
