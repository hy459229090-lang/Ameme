"""EventNodeStore adapter for an injected authenticated Android Local Node channel.

The wire contract comes exclusively from packages/agent-local-node-protocol. This module
deliberately implements no socket, discovery, authentication, credential lookup, or encryption.
Control fields declare an already-authorized request and never authenticate the channel.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
import queue
import re
import threading
import uuid
from typing import Any, Callable, Iterable, NoReturn, Protocol, runtime_checkable

from ameme_agent_local_node_protocol import (
    ERROR_RETRYABLE,
    MAX_REQUEST_BYTES,
    MAX_RESPONSE_BYTES,
    OPERATIONS,
    PROTOCOL_VERSION,
    ProtocolViolation,
    canonical_json_bytes,
    derive_idempotency_slot,
    digest_json,
    parse_request_line,
    parse_response_line,
)

from .event_store import (
    EventNodeConflict,
    EventNodeIdempotencyConflict,
    EventNodeNotVisible,
    EventNodeScope,
    EventNodeStoreError,
    idempotency_slot,
)
from .store import JsonStore


MAX_RESPONSE_SECONDS = 5.0
IMPLEMENTED_OPERATIONS = {
    "create_event",
    "append_revision",
    "undo_capture",
    "visible_events",
}
_IDENTIFIER = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
_REFERENCE_SUFFIX = re.compile(r"[A-Za-z0-9][A-Za-z0-9._/-]{0,239}")
_EVENT_TYPES = {
    "activity",
    "communication",
    "decision",
    "result",
    "state_change",
    "milestone",
    "experience",
}
_EVIDENCE_STATES = {"observed", "user_asserted", "inferred"}
_SENSITIVITIES = {"public", "personal", "confidential", "restricted"}
_VISIBLE_EVENT_KEYS = {
    "event_id",
    "space_id",
    "memory_type",
    "revision",
    "event_type",
    "title",
    "description",
    "fact_status",
    "evidence_state",
    "sensitivity",
    "data_class",
    "content_truncated",
}


class AndroidLocalNodeUnavailable(EventNodeStoreError):
    """The authenticated Android Local Node channel is not usable."""


class AndroidLocalNodeProtocolError(EventNodeStoreError):
    """A response failed shared wire validation or request binding."""


class AndroidLocalNodeOperationUnsupported(EventNodeStoreError):
    """The bound channel does not implement a valid protocol operation."""


def _require_reference(value: str, prefix: str, name: str) -> None:
    if not isinstance(value, str) or not value.startswith(prefix):
        raise ValueError(f"{name} must be an opaque {prefix} reference")
    if not _REFERENCE_SUFFIX.fullmatch(value.removeprefix(prefix)):
        raise ValueError(f"{name} reference is invalid")


@dataclass(frozen=True, repr=False)
class AndroidLocalNodeChannelConfig:
    """Opaque references and identity expectations for an external channel factory."""

    endpoint_ref: str
    credential_ref: str
    expected_device_id: str
    session_binding_ref: str

    def __post_init__(self) -> None:
        _require_reference(self.endpoint_ref, "endpoint-ref:", "endpoint_ref")
        _require_reference(self.credential_ref, "credential-ref:", "credential_ref")
        if not isinstance(self.expected_device_id, str) or not _IDENTIFIER.fullmatch(
            self.expected_device_id
        ):
            raise ValueError("expected_device_id is invalid")
        _require_reference(
            self.session_binding_ref,
            "session-binding-ref:",
            "session_binding_ref",
        )

    def __repr__(self) -> str:
        return (
            "AndroidLocalNodeChannelConfig(endpoint_ref=<reference>, "
            "credential_ref=<reference>, expected_device_id=<redacted>, "
            "session_binding_ref=<reference>)"
        )


@dataclass(frozen=True, repr=False)
class AndroidLocalNodeSessionBinding:
    device_id: str
    session_binding_ref: str

    def __post_init__(self) -> None:
        if not isinstance(self.device_id, str) or not _IDENTIFIER.fullmatch(self.device_id):
            raise ValueError("channel device_id is invalid")
        _require_reference(
            self.session_binding_ref,
            "session-binding-ref:",
            "channel session_binding_ref",
        )

    def __repr__(self) -> str:
        return (
            "AndroidLocalNodeSessionBinding(device_id=<redacted>, "
            "session_binding_ref=<reference>)"
        )


class AndroidLocalNodeRequest:
    """Clearable, diagnostic-safe container for one canonical request line."""

    def __init__(self, request_id: str, operation: str, wire_bytes: bytes) -> None:
        if not wire_bytes or len(wire_bytes) > MAX_REQUEST_BYTES:
            raise ValueError("Android Local Node request line is invalid")
        self.request_id = request_id
        self.operation = operation
        self._wire_bytes = bytearray(wire_bytes)
        self._closed = False

    def wire_copy(self) -> bytes:
        if self._closed:
            raise RuntimeError("Android Local Node request is closed")
        return bytes(self._wire_bytes)

    def close(self) -> None:
        self._wire_bytes[:] = b"\0" * len(self._wire_bytes)
        self._closed = True

    def __repr__(self) -> str:
        state = "cleared" if self._closed else f"redacted:{len(self._wire_bytes)} bytes"
        return (
            f"AndroidLocalNodeRequest(request_id={self.request_id!r}, "
            f"operation={self.operation!r}, wire=<{state}>)"
        )


class AndroidLocalNodeResponse:
    """Clearable, diagnostic-safe container for one untrusted response line."""

    def __init__(self, wire_bytes: bytes) -> None:
        if not wire_bytes or len(wire_bytes) > MAX_RESPONSE_BYTES:
            raise ValueError("Android Local Node response line is invalid")
        self._wire_bytes = bytearray(wire_bytes)
        self._closed = False

    @classmethod
    def for_request(
        cls,
        request: AndroidLocalNodeRequest,
        *,
        result: dict[str, Any] | None = None,
        error_code: str | None = None,
    ) -> "AndroidLocalNodeResponse":
        """Construct a strict synthetic response for tests and injected channel fixtures."""
        request_document = parse_request_line(request.wire_copy())
        if error_code is None:
            result = result or {}
            response = {
                "protocol_version": PROTOCOL_VERSION,
                "request_id": request_document["request_id"],
                "status": "ok",
                "result": result,
                "result_digest": digest_json(result),
                "error": None,
            }
        else:
            response = {
                "protocol_version": PROTOCOL_VERSION,
                "request_id": request_document["request_id"],
                "status": "error",
                "result": None,
                "result_digest": None,
                "error": {
                    "code": error_code,
                    "retryable": ERROR_RETRYABLE[error_code],
                },
            }
        return cls(canonical_json_bytes(response))

    def wire_copy(self) -> bytes:
        if self._closed:
            raise RuntimeError("Android Local Node response is closed")
        return bytes(self._wire_bytes)

    def close(self) -> None:
        self._wire_bytes[:] = b"\0" * len(self._wire_bytes)
        self._closed = True

    def __repr__(self) -> str:
        state = "cleared" if self._closed else f"redacted:{len(self._wire_bytes)} bytes"
        return f"AndroidLocalNodeResponse(wire=<{state}>)"


@runtime_checkable
class AndroidLocalNodeChannel(Protocol):
    """Injected authenticated/encrypted session; this service supplies no implementation."""

    @property
    def binding(self) -> AndroidLocalNodeSessionBinding: ...

    @property
    def supported_operations(self) -> frozenset[str]: ...

    def exchange(self, request: AndroidLocalNodeRequest) -> AndroidLocalNodeResponse: ...

    def close(self) -> None: ...


AndroidLocalNodeChannelFactory = Callable[
    [AndroidLocalNodeChannelConfig], AndroidLocalNodeChannel
]


class AndroidLocalNodeStore:
    """EventNodeStore adapter over an injected, already-authenticated Android session."""

    def __init__(
        self,
        control_path: Path,
        *,
        channel: AndroidLocalNodeChannel,
        channel_config: AndroidLocalNodeChannelConfig,
        response_timeout_seconds: float = MAX_RESPONSE_SECONDS,
        request_id_factory: Callable[[], str] | None = None,
    ) -> None:
        if response_timeout_seconds <= 0 or response_timeout_seconds > MAX_RESPONSE_SECONDS:
            raise ValueError("response_timeout_seconds must be within five seconds")
        if not isinstance(channel, AndroidLocalNodeChannel):
            raise TypeError("channel must implement AndroidLocalNodeChannel")
        try:
            binding = channel.binding
            supported_operations = frozenset(channel.supported_operations)
        except Exception:
            self._close_unaccepted_channel(channel)
            raise AndroidLocalNodeUnavailable(
                "android_local_node_channel_metadata_failed"
            ) from None
        if (
            binding.device_id != channel_config.expected_device_id
            or binding.session_binding_ref != channel_config.session_binding_ref
        ):
            self._close_unaccepted_channel(channel)
            raise AndroidLocalNodeUnavailable("android_local_node_session_binding_failed")
        if not supported_operations or not supported_operations.issubset(OPERATIONS):
            self._close_unaccepted_channel(channel)
            raise AndroidLocalNodeProtocolError(
                "android_local_node_channel_capabilities_invalid"
            )
        if not supported_operations.intersection(IMPLEMENTED_OPERATIONS):
            self._close_unaccepted_channel(channel)
            raise AndroidLocalNodeOperationUnsupported(
                "android_local_node_capability_unsupported"
            )

        self.channel = channel
        try:
            self.control = JsonStore(control_path)
        except Exception:
            self._close_unaccepted_channel(channel)
            raise AndroidLocalNodeUnavailable(
                "android_local_node_control_store_failed"
            ) from None
        self.state = self.control.state
        self.channel_config = channel_config
        self.channel_operations = supported_operations
        self.supported_operations = frozenset(
            supported_operations & IMPLEMENTED_OPERATIONS
        )
        self.response_timeout_seconds = response_timeout_seconds
        self.request_id_factory = request_id_factory or (
            lambda: f"req_{uuid.uuid4().hex}"
        )
        self._lock = threading.Lock()
        self._closed = False
        self._poisoned = False

    @staticmethod
    def _close_unaccepted_channel(channel: AndroidLocalNodeChannel) -> None:
        try:
            channel.close()
        except Exception:
            pass

    def save(self) -> None:
        self.control.save()

    def close(self) -> None:
        with self._lock:
            if self._closed:
                return
            self._closed = True
            try:
                self.control.close()
            finally:
                thread = threading.Thread(
                    target=self._safe_channel_close,
                    name="ameme-android-local-node-close",
                    daemon=True,
                )
                thread.start()
                thread.join(timeout=self.response_timeout_seconds)

    def _safe_channel_close(self) -> None:
        try:
            self.channel.close()
        except Exception:
            pass

    def _poison_channel(self) -> None:
        self._poisoned = True
        threading.Thread(
            target=self._safe_channel_close,
            name="ameme-android-local-node-abort",
            daemon=True,
        ).start()

    @staticmethod
    def _requested_scope(
        scope: EventNodeScope,
        spaces: Iterable[str],
        memory_types: Iterable[str],
    ) -> tuple[list[str], list[str]]:
        requested_spaces = sorted(set(spaces))
        requested_types = sorted(set(memory_types))
        if not requested_spaces or not requested_types:
            raise EventNodeNotVisible("android_local_node_scope_empty")
        for space in requested_spaces:
            for memory_type in requested_types:
                scope.require(space, memory_type)
        return requested_spaces, requested_types

    def _exchange_bounded(
        self, request: AndroidLocalNodeRequest
    ) -> AndroidLocalNodeResponse:
        outcome: queue.Queue[tuple[str, Any]] = queue.Queue(maxsize=1)
        abandoned = threading.Event()

        def worker() -> None:
            try:
                response = self.channel.exchange(request)
                if abandoned.is_set():
                    if isinstance(response, AndroidLocalNodeResponse):
                        response.close()
                    return
                outcome.put(("response", response))
                if abandoned.is_set() and isinstance(
                    response, AndroidLocalNodeResponse
                ):
                    response.close()
            except Exception:
                if not abandoned.is_set():
                    outcome.put(("error", None))

        threading.Thread(
            target=worker,
            name="ameme-android-local-node-exchange",
            daemon=True,
        ).start()
        try:
            kind, value = outcome.get(timeout=self.response_timeout_seconds)
        except queue.Empty as exc:
            abandoned.set()
            self._poison_channel()
            raise AndroidLocalNodeUnavailable(
                "android_local_node_deadline_exceeded"
            ) from exc
        if kind != "response":
            self._poison_channel()
            raise AndroidLocalNodeUnavailable("android_local_node_exchange_failed")
        if not isinstance(value, AndroidLocalNodeResponse):
            self._poison_channel()
            raise AndroidLocalNodeProtocolError("android_local_node_response_invalid")
        return value

    def _request(
        self,
        operation: str,
        *,
        scope: EventNodeScope,
        spaces: Iterable[str],
        memory_types: Iterable[str],
        payload: dict[str, Any],
        idempotency_key: str | None = None,
    ) -> dict[str, Any]:
        requested_spaces, requested_types = self._requested_scope(
            scope, spaces, memory_types
        )
        if (
            operation not in IMPLEMENTED_OPERATIONS
            or operation not in self.supported_operations
        ):
            raise AndroidLocalNodeOperationUnsupported(
                "android_local_node_operation_unsupported"
            )
        slot_material = idempotency_key or digest_json(payload)
        try:
            candidate = {
                "protocol_version": PROTOCOL_VERSION,
                "request_id": self.request_id_factory(),
                "control": {
                    "caller_id": scope.caller_id,
                    "grant_id": scope.grant_id,
                    "purpose": scope.purpose,
                    "spaces": requested_spaces,
                    "memory_types": requested_types,
                    "operation": operation,
                    "idempotency_slot": derive_idempotency_slot(
                        slot_material, operation=operation
                    ),
                    "payload_digest": digest_json(payload),
                },
                "payload": payload,
            }
            wire_bytes = canonical_json_bytes(candidate)
            request_document = parse_request_line(wire_bytes)
            request = AndroidLocalNodeRequest(
                request_document["request_id"],
                operation,
                wire_bytes,
            )
        except ProtocolViolation as exc:
            raise AndroidLocalNodeProtocolError(
                "android_local_node_request_invalid"
            ) from exc

        with self._lock:
            if self._closed or self._poisoned:
                request.close()
                raise AndroidLocalNodeUnavailable("android_local_node_unavailable")
            response: AndroidLocalNodeResponse | None = None
            try:
                response = self._exchange_bounded(request)
                try:
                    response_document = parse_response_line(
                        response.wire_copy(), request=request_document
                    )
                except ProtocolViolation as exc:
                    self._poison_channel()
                    raise AndroidLocalNodeProtocolError(
                        "android_local_node_response_invalid"
                    ) from exc
                if response_document["status"] == "error":
                    self._raise_remote_error(response_document["error"])
                result = response_document["result"]
                if not isinstance(result, dict):
                    self._poison_channel()
                    raise AndroidLocalNodeProtocolError(
                        "android_local_node_result_invalid"
                    )
                return result
            finally:
                request.close()
                if response is not None:
                    response.close()

    def _raise_remote_error(self, error: dict[str, Any]) -> None:
        code = error["code"]
        if code in {
            "AUTH_REQUIRED",
            "GRANT_REVOKED",
            "GRANT_EXPIRED",
            "PURPOSE_DENIED",
            "SPACE_DENIED",
            "DATA_TYPE_DENIED",
            "NOT_VISIBLE",
        }:
            raise EventNodeNotVisible("android_local_node_not_visible")
        if code == "REVISION_CONFLICT":
            raise EventNodeConflict("android_local_node_conflict")
        if code == "IDEMPOTENCY_CONFLICT":
            raise EventNodeIdempotencyConflict(
                "android_local_node_idempotency_conflict"
            )
        if code == "OPERATION_UNSUPPORTED":
            raise AndroidLocalNodeOperationUnsupported(
                "android_local_node_operation_unsupported"
            )
        if code in {"TEMPORARILY_UNAVAILABLE", "INTERNAL_ERROR"}:
            self._poison_channel()
            raise AndroidLocalNodeUnavailable("android_local_node_unavailable")
        self._poison_channel()
        raise AndroidLocalNodeProtocolError("android_local_node_remote_protocol_error")

    def _unsupported_operation(
        self,
        *,
        scope: EventNodeScope,
        spaces: Iterable[str],
        memory_types: Iterable[str],
    ) -> NoReturn:
        self._requested_scope(scope, spaces, memory_types)
        raise AndroidLocalNodeOperationUnsupported(
            "android_local_node_operation_unsupported"
        )

    def get_event(
        self,
        event_id: str,
        *,
        scope: EventNodeScope,
        space: str,
        memory_type: str,
    ) -> dict[str, Any] | None:
        self._unsupported_operation(
            scope=scope,
            spaces=(space,),
            memory_types=(memory_type,),
        )

    def create_event(
        self,
        *,
        scope: EventNodeScope,
        space: str,
        content: str,
        event_time: str | None,
        event_type: str,
        evidence_state: str,
        fact_status: str,
        sensitivity: str,
        data_class: str,
        now: str,
        idempotency_key: str,
    ) -> dict[str, Any]:
        payload = {
            "space": space,
            "memory_type": "event",
            "content": content,
            "event_type": event_type,
            "evidence_state": evidence_state,
            "fact_status": fact_status,
            "sensitivity": sensitivity,
            "data_class": data_class,
            "now": now,
        }
        if event_time is not None:
            payload["event_time"] = event_time
        remote_undo_token = derive_idempotency_slot(
            idempotency_key, operation="create_event"
        )
        result = self._request(
            "create_event",
            scope=scope,
            spaces=(space,),
            memory_types=("event",),
            payload=payload,
            idempotency_key=idempotency_key,
        )
        if (
            set(result) != {"object_type", "event_id", "revision"}
            or result.get("object_type") != "event"
            or not isinstance(result.get("event_id"), str)
            or not _IDENTIFIER.fullmatch(result["event_id"])
            or not isinstance(result.get("revision"), int)
            or isinstance(result.get("revision"), bool)
            or result["revision"] <= 0
        ):
            self._poison_channel()
            raise AndroidLocalNodeProtocolError(
                "android_local_node_create_result_invalid"
            )
        result["_undo_token_hint"] = remote_undo_token
        return result

    def append_revision(
        self,
        *,
        scope: EventNodeScope,
        space: str,
        event_id: str,
        content: str,
        evidence_state: str,
        fact_status: str,
        now: str,
        idempotency_key: str,
    ) -> dict[str, Any]:
        payload = {
            "event_id": event_id,
            "space": space,
            "memory_type": "revision",
            "content": content,
            "evidence_state": evidence_state,
            "fact_status": fact_status,
            "now": now,
        }
        remote_undo_token = derive_idempotency_slot(
            idempotency_key, operation="append_revision"
        )
        result = self._request(
            "append_revision",
            scope=scope,
            spaces=(space,),
            memory_types=("revision",),
            payload=payload,
            idempotency_key=idempotency_key,
        )
        if (
            set(result)
            != {
                "object_type",
                "event_revision_id",
                "target_event_id",
                "revision",
            }
            or result.get("object_type") != "revision"
            or not isinstance(result.get("event_revision_id"), str)
            or not _IDENTIFIER.fullmatch(result["event_revision_id"])
            or result.get("target_event_id") != event_id
            or not isinstance(result.get("revision"), int)
            or isinstance(result.get("revision"), bool)
            or result["revision"] < 2
        ):
            self._poison_channel()
            raise AndroidLocalNodeProtocolError(
                "android_local_node_append_result_invalid"
            )
        result["_undo_token_hint"] = remote_undo_token
        return result

    def undo_capture(
        self,
        undo: dict[str, Any],
        *,
        scope: EventNodeScope,
        space: str,
        memory_type: str,
        now: str,
        idempotency_key: str,
    ) -> dict[str, Any]:
        payload = {
            "undo_token": idempotency_key,
            "space": space,
            "memory_type": memory_type,
            "now": now,
        }
        result = self._request(
            "undo_capture",
            scope=scope,
            spaces=(space,),
            memory_types=(memory_type,),
            payload=payload,
            idempotency_key=idempotency_key,
        )
        expected_event_id = undo.get("target_event_id")
        expected_object_id = undo.get("created_object_id")
        common_keys = {
            "state",
            "target_event_id",
            "undone_object_type",
            "undone_object_id",
            "activity_visible",
        }
        expected_keys = (
            common_keys
            if memory_type == "event"
            else common_keys | {"compensation_revision_id"}
        )
        valid = (
            set(result) == expected_keys
            and result.get("state") == "undone"
            and result.get("target_event_id") == expected_event_id
            and result.get("undone_object_type") == memory_type
            and result.get("undone_object_id") == expected_object_id
            and result.get("activity_visible") is True
        )
        if memory_type == "event":
            valid = valid and expected_object_id == expected_event_id
        else:
            compensation_id = result.get("compensation_revision_id")
            valid = (
                valid
                and isinstance(compensation_id, str)
                and _IDENTIFIER.fullmatch(compensation_id) is not None
                and compensation_id != expected_object_id
            )
        if not valid:
            self._poison_channel()
            raise AndroidLocalNodeProtocolError(
                "android_local_node_undo_result_invalid"
            )
        return result

    def visible_events(
        self,
        *,
        scope: EventNodeScope,
        spaces: Iterable[str],
        memory_types: Iterable[str],
        query: str | None,
        allow_high_risk: bool,
        start_at: datetime | None,
        end_at: datetime | None,
        limit: int = 100,
    ) -> tuple[list[dict[str, Any]], bool]:
        requested_spaces, requested_types = self._requested_scope(
            scope=scope,
            spaces=spaces,
            memory_types=memory_types,
        )
        if requested_types != ["event"]:
            raise EventNodeNotVisible("android_local_node_data_type_not_visible")
        if not isinstance(query, (str, type(None))):
            raise ValueError("visible event query is invalid")
        if query is not None and ("\0" in query or len(query) > 1_000):
            raise ValueError("visible event query is invalid")
        if not isinstance(allow_high_risk, bool):
            raise ValueError("allow_high_risk must be a boolean")
        if not isinstance(limit, int) or isinstance(limit, bool) or not 1 <= limit <= 100:
            raise ValueError("visible event limit is invalid")
        if start_at is not None and not isinstance(start_at, datetime):
            raise ValueError("visible event start is invalid")
        if end_at is not None and not isinstance(end_at, datetime):
            raise ValueError("visible event end is invalid")
        if start_at is not None and end_at is not None and end_at < start_at:
            raise ValueError("visible event time range is invalid")
        payload: dict[str, Any] = {
            "spaces": requested_spaces,
            "memory_types": requested_types,
            "allow_high_risk": allow_high_risk,
            "limit": limit,
        }
        if query is not None:
            payload["query"] = query
        if start_at is not None:
            payload["start_at"] = start_at.isoformat()
        if end_at is not None:
            payload["end_at"] = end_at.isoformat()
        result = self._request(
            "visible_events",
            scope=scope,
            spaces=requested_spaces,
            memory_types=requested_types,
            payload=payload,
        )
        events = result.get("events")
        risk_filtered = result.get("risk_filtered")
        valid = (
            set(result) == {"events", "risk_filtered"}
            and isinstance(events, list)
            and len(events) <= limit
            and isinstance(risk_filtered, bool)
        )
        event_ids: set[str] = set()
        if valid:
            for event in events:
                if not self._valid_visible_event(
                    event,
                    requested_spaces=requested_spaces,
                    allow_high_risk=allow_high_risk,
                ):
                    valid = False
                    break
                event_id = event["event_id"]
                if event_id in event_ids:
                    valid = False
                    break
                event_ids.add(event_id)
        if not valid:
            self._poison_channel()
            raise AndroidLocalNodeProtocolError(
                "android_local_node_visible_events_result_invalid"
            )
        return [dict(event) for event in events], risk_filtered

    @staticmethod
    def _valid_visible_event(
        event: Any,
        *,
        requested_spaces: list[str],
        allow_high_risk: bool,
    ) -> bool:
        if not isinstance(event, dict) or set(event) != _VISIBLE_EVENT_KEYS:
            return False
        revision = event.get("revision")
        title = event.get("title")
        description = event.get("description")
        fact_status = event.get("fact_status")
        if (
            not isinstance(event.get("event_id"), str)
            or _IDENTIFIER.fullmatch(event["event_id"]) is None
            or event.get("space_id") not in requested_spaces
            or event.get("memory_type") != "event"
            or not isinstance(revision, int)
            or isinstance(revision, bool)
            or revision < 1
            or event.get("event_type") not in _EVENT_TYPES
            or not isinstance(title, str)
            or not title.strip()
            or len(title) > 240
            or "\0" in title
            or not isinstance(description, str)
            or len(description) > 1_000
            or "\0" in description
            or not isinstance(fact_status, str)
            or not fact_status.strip()
            or len(fact_status) > 64
            or event.get("evidence_state") not in _EVIDENCE_STATES
            or event.get("sensitivity") not in _SENSITIVITIES
            or event.get("data_class") != "structured"
            or not isinstance(event.get("content_truncated"), bool)
        ):
            return False
        return allow_high_risk or event["sensitivity"] != "restricted"

    def set_policy_blocked(
        self,
        event_id: str,
        *,
        scope: EventNodeScope,
        space: str,
        memory_type: str,
    ) -> bool:
        self._unsupported_operation(
            scope=scope,
            spaces=(space,),
            memory_types=(memory_type,),
        )

    def enqueue_delivery(
        self,
        *,
        object_type: str,
        object_id: str,
        now: str,
        idempotency_key: str,
    ) -> dict[str, Any]:
        semantic = {
            "object_type": object_type,
            "object_id": object_id,
            "created_at": now,
        }
        digest = digest_json(semantic)
        slot = idempotency_slot(
            idempotency_key,
            domain="android-local-node-control:enqueue-delivery",
        )
        prior_by_slot = self.state.setdefault("android_delivery_idempotency", {})
        prior = prior_by_slot.get(slot)
        if prior is not None:
            if prior["payload_digest"] != digest:
                raise EventNodeIdempotencyConflict(
                    "android_local_node_idempotency_conflict"
                )
            return dict(prior["result"])
        queued = {
            "queue_id": f"que_{uuid.uuid4().hex}",
            "object_type": object_type,
            "object_id": object_id,
            "state": "queued",
            "created_at": now,
        }
        self.state["queue"].append(queued)
        prior_by_slot[slot] = {"payload_digest": digest, "result": queued}
        return dict(queued)

    def queued_count(self) -> int:
        return len(self.state["queue"])
