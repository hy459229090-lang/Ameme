from __future__ import annotations

from datetime import datetime, timezone
import json
from pathlib import Path
import sys
import threading
import time
from tempfile import TemporaryDirectory
import unittest


SERVICE_ROOT = Path(__file__).resolve().parents[1]
PROTOCOL_ROOT = SERVICE_ROOT.parents[1] / "packages" / "agent-local-node-protocol"
if str(SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVICE_ROOT))
if str(PROTOCOL_ROOT) not in sys.path:
    sys.path.insert(0, str(PROTOCOL_ROOT))

from ameme_mcp_mock.android_local_node_store import (  # noqa: E402
    AndroidLocalNodeChannelConfig,
    AndroidLocalNodeOperationUnsupported,
    AndroidLocalNodeProtocolError,
    AndroidLocalNodeResponse,
    AndroidLocalNodeSessionBinding,
    AndroidLocalNodeStore,
    AndroidLocalNodeUnavailable,
    IMPLEMENTED_OPERATIONS,
)
from ameme_mcp_mock import AmemeMock  # noqa: E402
from ameme_agent_local_node_protocol import (  # noqa: E402
    PROTOCOL_VERSION,
    canonical_json_bytes,
    digest_json,
    parse_request_line,
)
from server import MCPServer  # noqa: E402
from ameme_mcp_mock.event_store import (  # noqa: E402
    EventNodeConflict,
    EventNodeIdempotencyConflict,
    EventNodeNotVisible,
    EventNodeScope,
)


class FakeChannel:
    def __init__(self, handler=None, *, supported_operations=None) -> None:
        self.binding = AndroidLocalNodeSessionBinding(
            device_id="device_synthetic_001",
            session_binding_ref="session-binding-ref:synthetic-session",
        )
        self.handler = handler or self._default_handler
        self.supported_operations = frozenset(
            supported_operations
            or {
                "create_event",
                "append_revision",
                "undo_capture",
                "visible_events",
            }
        )
        self.calls = []
        self.closed = 0

    def _default_handler(self, request):
        document = parse_request_line(request.wire_copy())
        operation = document["control"]["operation"]
        if operation == "create_event":
            result = {"object_type": "event", "event_id": "evt_synthetic", "revision": 1}
        elif operation == "append_revision":
            result = {
                "object_type": "revision",
                "event_revision_id": "rev_synthetic_002",
                "target_event_id": "evt_synthetic",
                "revision": 2,
            }
        elif operation == "undo_capture":
            memory_type = document["payload"]["memory_type"]
            result = {
                "state": "undone",
                "target_event_id": "evt_synthetic",
                "undone_object_type": memory_type,
                "undone_object_id": (
                    "evt_synthetic" if memory_type == "event" else "rev_synthetic_002"
                ),
                "activity_visible": True,
            }
            if memory_type == "revision":
                result["compensation_revision_id"] = "rev_synthetic_003"
        elif operation == "visible_events":
            result = {
                "events": [
                    {
                        "event_id": "evt_visible_synthetic",
                        "space_id": document["payload"]["spaces"][0],
                        "memory_type": "event",
                        "revision": 3,
                        "event_type": "result",
                        "title": "Synthetic visible event",
                        "description": "Synthetic visible memory for bounded recall.",
                        "fact_status": "confirmed",
                        "evidence_state": "observed",
                        "sensitivity": "personal",
                        "data_class": "structured",
                        "content_truncated": False,
                    }
                ],
                "risk_filtered": False,
            }
        else:
            raise AssertionError("fake channel operation is unsupported")
        return AndroidLocalNodeResponse.for_request(request, result=result)

    def exchange(self, request):
        payload = request.wire_copy()
        self.calls.append((parse_request_line(payload), payload, repr(request)))
        return self.handler(request)

    def close(self) -> None:
        self.closed += 1


class AndroidLocalNodeStoreTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.config = AndroidLocalNodeChannelConfig(
            endpoint_ref="endpoint-ref:synthetic-android",
            credential_ref="credential-ref:synthetic-credential",
            expected_device_id="device_synthetic_001",
            session_binding_ref="session-binding-ref:synthetic-session",
        )
        self.scope = EventNodeScope(
            owner_id="owner_synthetic",
            caller_id="agent_synthetic",
            grant_id="grant_synthetic",
            purpose="autonomous_memory",
            spaces=("space_personal", "space_work"),
            memory_types=("event", "revision"),
        )

    def tearDown(self) -> None:
        self.temp.cleanup()

    def store(self, channel: FakeChannel, **kwargs) -> AndroidLocalNodeStore:
        return AndroidLocalNodeStore(
            self.root / "mcp-control.json",
            channel=channel,
            channel_config=self.config,
            request_id_factory=lambda: "req_synthetic_001",
            **kwargs,
        )

    def create(self, store: AndroidLocalNodeStore, **overrides):
        arguments = {
            "scope": self.scope,
            "space": "space_work",
            "content": "SYNTHETIC_PRIVATE_MEMORY_CONTENT",
            "event_time": "2026-07-14T08:00:00+00:00",
            "event_type": "result",
            "evidence_state": "observed",
            "fact_status": "confirmed",
            "sensitivity": "personal",
            "data_class": "structured",
            "now": "2026-07-14T08:01:00+00:00",
            "idempotency_key": "RAW_IDEMPOTENCY_KEY_SYNTHETIC",
        }
        arguments.update(overrides)
        return store.create_event(**arguments)

    def append(self, store: AndroidLocalNodeStore, **overrides):
        arguments = {
            "scope": self.scope,
            "space": "space_work",
            "event_id": "evt_synthetic",
            "content": "SYNTHETIC_PRIVATE_REVISION_CONTENT",
            "evidence_state": "user_asserted",
            "fact_status": "user_asserted",
            "now": "2026-07-14T08:02:00+00:00",
            "idempotency_key": "RAW_REVISION_IDEMPOTENCY_KEY_SYNTHETIC",
        }
        arguments.update(overrides)
        return store.append_revision(**arguments)

    def visible(self, store: AndroidLocalNodeStore, **overrides):
        arguments = {
            "scope": self.scope,
            "spaces": ("space_work",),
            "memory_types": ("event",),
            "query": "visible memory",
            "allow_high_risk": False,
            "start_at": datetime(2026, 7, 1, tzinfo=timezone.utc),
            "end_at": datetime(2026, 7, 31, tzinfo=timezone.utc),
            "limit": 7,
        }
        arguments.update(overrides)
        return store.visible_events(**arguments)

    def test_grant_superset_emits_minimal_request_scope_and_redacted_control(self) -> None:
        channel = FakeChannel()
        store = self.store(channel)

        result = self.create(store)

        self.assertEqual("evt_synthetic", result["event_id"])
        request, payload, diagnostic = channel.calls[0]
        control = request["control"]
        self.assertEqual(["space_work"], control["spaces"])
        self.assertEqual(["event"], control["memory_types"])
        self.assertEqual("create_event", control["operation"])
        self.assertEqual(
            digest_json(request["payload"]),
            control["payload_digest"],
        )
        self.assertEqual(canonical_json_bytes(request), payload)
        self.assertEqual("space_work", request["payload"]["space"])
        self.assertEqual("event", request["payload"]["memory_type"])
        self.assertNotIn("SYNTHETIC_PRIVATE_MEMORY_CONTENT", diagnostic)
        self.assertNotIn("RAW_IDEMPOTENCY_KEY_SYNTHETIC", diagnostic)
        self.assertNotIn("RAW_IDEMPOTENCY_KEY_SYNTHETIC", json.dumps(control))
        store.close()
        persisted = (self.root / "mcp-control.json").read_text(encoding="utf-8")
        for secret in (
            "SYNTHETIC_PRIVATE_MEMORY_CONTENT",
            "RAW_IDEMPOTENCY_KEY_SYNTHETIC",
            "synthetic-credential",
            "synthetic-session",
        ):
            self.assertNotIn(secret, persisted)

    def test_mcp_capture_reaches_injected_channel_without_core_fallback(self) -> None:
        channel = FakeChannel()
        store = self.store(channel)
        fixed_now = datetime(2026, 7, 14, 8, 0, tzinfo=timezone.utc)
        mock = AmemeMock(store, clock=lambda: fixed_now)
        pending = mock.call(
            "pair",
            {
                "action": "begin",
                "caller_id": "agent_synthetic",
                "purposes": ["autonomous_memory"],
                "spaces": ["space_work"],
                "data_types": ["event"],
                "expires_at": "2026-08-13T08:00:00+00:00",
            },
        )
        grant = mock.call(
            "pair",
            {
                "action": "approve",
                "caller_id": "agent_synthetic",
                "challenge_id": pending["challenge_id"],
                "confirmation": {
                    "confirmed": True,
                    "terms_digest": pending["terms_digest"],
                },
            },
        )
        server = MCPServer(mock, runtime_label="android-local-node")
        response = server.handle(
            {
                "jsonrpc": "2.0",
                "id": 7,
                "method": "tools/call",
                "params": {
                    "name": "capture",
                    "arguments": {
                        "caller_id": "agent_synthetic",
                        "grant_id": grant["grant_id"],
                        "purpose": "autonomous_memory",
                        "space": "space_work",
                        "memory_type": "event",
                        "content": "Synthetic MCP capture through Android adapter.",
                        "evidence_kind": "direct_evidence",
                        "idempotency_key": "synthetic-mcp-key-0001",
                    },
                },
            }
        )
        self.assertFalse(response["result"]["isError"])
        self.assertEqual("evt_synthetic", response["result"]["structuredContent"]["event_id"])
        self.assertEqual(1, len(channel.calls))
        self.assertEqual("create_event", channel.calls[0][0]["control"]["operation"])
        revision_pending = mock.call(
            "pair",
            {
                "action": "begin",
                "caller_id": "agent_synthetic",
                "purposes": ["autonomous_memory"],
                "spaces": ["space_work"],
                "data_types": ["revision"],
                "expires_at": "2026-08-13T08:00:00+00:00",
            },
        )
        revision_grant = mock.call(
            "pair",
            {
                "action": "approve",
                "caller_id": "agent_synthetic",
                "challenge_id": revision_pending["challenge_id"],
                "confirmation": {
                    "confirmed": True,
                    "terms_digest": revision_pending["terms_digest"],
                },
            },
        )
        revision = server.handle(
            {
                "jsonrpc": "2.0",
                "id": 8,
                "method": "tools/call",
                "params": {
                    "name": "capture",
                    "arguments": {
                        "caller_id": "agent_synthetic",
                        "grant_id": revision_grant["grant_id"],
                        "purpose": "autonomous_memory",
                        "space": "space_work",
                        "memory_type": "revision",
                        "target_event_id": "evt_synthetic",
                        "content": "Synthetic MCP revision through Android adapter.",
                        "evidence_kind": "user_statement",
                        "idempotency_key": "synthetic-mcp-key-0002",
                    },
                },
            }
        )
        self.assertFalse(revision["result"]["isError"])
        self.assertEqual(
            "rev_synthetic_002",
            revision["result"]["structuredContent"]["event_revision_id"],
        )
        self.assertEqual(2, len(channel.calls))
        self.assertEqual("append_revision", channel.calls[1][0]["control"]["operation"])
        undone = server.handle(
            {
                "jsonrpc": "2.0",
                "id": 9,
                "method": "tools/call",
                "params": {
                    "name": "capture",
                    "arguments": {
                        "operation": "undo",
                        "caller_id": "agent_synthetic",
                        "grant_id": revision_grant["grant_id"],
                        "purpose": "autonomous_memory",
                        "space": "space_work",
                        "memory_type": "revision",
                        "undo_token": revision["result"]["structuredContent"]["undo_token"],
                    },
                },
            }
        )
        self.assertFalse(undone["result"]["isError"])
        self.assertEqual("undone", undone["result"]["structuredContent"]["state"])
        self.assertEqual(
            "rev_synthetic_003",
            undone["result"]["structuredContent"]["compensation_revision_id"],
        )
        self.assertEqual("undo_capture", channel.calls[2][0]["control"]["operation"])
        recalled = server.handle(
            {
                "jsonrpc": "2.0",
                "id": 10,
                "method": "tools/call",
                "params": {
                    "name": "recall",
                    "arguments": {
                        "caller_id": "agent_synthetic",
                        "grant_id": grant["grant_id"],
                        "purpose": "autonomous_memory",
                        "spaces": ["space_work"],
                        "memory_types": ["event"],
                        "invocation": "autonomous",
                    },
                },
            }
        )
        recall_result = recalled["result"]["structuredContent"]
        self.assertFalse(recalled["result"]["isError"])
        self.assertEqual("complete_for_requested_scope", recall_result["range_state"])
        self.assertEqual("evt_visible_synthetic", recall_result["results"][0]["object_id"])
        self.assertEqual("visible_events", channel.calls[3][0]["control"]["operation"])
        context = server.handle(
            {
                "jsonrpc": "2.0",
                "id": 11,
                "method": "tools/call",
                "params": {
                    "name": "get_context",
                    "arguments": {
                        "caller_id": "agent_synthetic",
                        "grant_id": grant["grant_id"],
                        "purpose": "autonomous_memory",
                        "space": "space_work",
                        "memory_types": ["event"],
                        "invocation": "autonomous",
                    },
                },
            }
        )
        context_result = context["result"]["structuredContent"]
        self.assertFalse(context["result"]["isError"])
        self.assertEqual(
            "Synthetic visible memory for bounded recall.",
            context_result["items"][0]["content"],
        )
        self.assertEqual("visible_events", channel.calls[4][0]["control"]["operation"])
        self.assertEqual(5, len(channel.calls))
        store.close()

    def test_visible_events_and_mutations_are_supported_but_target_read_stays_closed(self) -> None:
        channel = FakeChannel(
            supported_operations={
                "create_event",
                "get_event",
                "append_revision",
                "undo_capture",
                "visible_events",
            }
        )
        store = self.store(channel)
        self.assertEqual(
            {"create_event", "append_revision", "undo_capture", "visible_events"},
            IMPLEMENTED_OPERATIONS,
        )
        self.assertEqual(
            frozenset(
                {"create_event", "append_revision", "undo_capture", "visible_events"}
            ),
            store.supported_operations,
        )
        self.assertIn("append_revision", store.channel_operations)
        with self.assertRaisesRegex(
            AndroidLocalNodeOperationUnsupported,
            "^android_local_node_operation_unsupported$",
        ):
            store.get_event(
                "evt_synthetic",
                scope=self.scope,
                space="space_work",
                memory_type="event",
            )
        self.assertEqual([], channel.calls)
        revision = self.append(store)
        self.assertEqual("rev_synthetic_002", revision["event_revision_id"])
        request = channel.calls[0][0]
        self.assertEqual(["space_work"], request["control"]["spaces"])
        self.assertEqual(["revision"], request["control"]["memory_types"])
        self.assertEqual("append_revision", request["control"]["operation"])
        self.assertEqual("evt_synthetic", request["payload"]["event_id"])
        self.assertNotIn(
            "RAW_REVISION_IDEMPOTENCY_KEY_SYNTHETIC",
            json.dumps(request["control"]),
        )
        self.create(store)
        undone = store.undo_capture(
            {
                "target_event_id": "evt_synthetic",
                "created_object_type": "event",
                "created_object_id": "evt_synthetic",
                "internal": "must-not-cross-wire",
            },
            scope=self.scope,
            space="space_work",
            memory_type="event",
            now="2026-07-14T08:03:00+00:00",
            idempotency_key=channel.calls[1][0]["control"]["idempotency_slot"],
        )
        self.assertEqual("undone", undone["state"])
        self.assertEqual(3, len(channel.calls))
        undo_request = channel.calls[2][0]
        self.assertEqual(
            {
                "undo_token": channel.calls[1][0]["control"]["idempotency_slot"],
                "space": "space_work",
                "memory_type": "event",
                "now": "2026-07-14T08:03:00+00:00",
            },
            undo_request["payload"],
        )
        self.assertNotIn("internal", json.dumps(undo_request))
        self.assertNotIn(b"must-not-cross-wire", channel.calls[0][1])
        self.assertNotIn(b"must-not-cross-wire", channel.calls[1][1])
        self.assertNotIn(b"must-not-cross-wire", channel.calls[2][1])
        store.close()

    def test_visible_events_is_bounded_and_read_only_channel_is_accepted(self) -> None:
        channel = FakeChannel(supported_operations={"visible_events"})
        store = self.store(channel)

        events, risk_filtered = self.visible(store)

        self.assertFalse(risk_filtered)
        self.assertEqual(["evt_visible_synthetic"], [event["event_id"] for event in events])
        self.assertEqual(frozenset({"visible_events"}), store.supported_operations)
        request, wire, diagnostic = channel.calls[0]
        self.assertEqual(
            {
                "spaces": ["space_work"],
                "memory_types": ["event"],
                "query": "visible memory",
                "allow_high_risk": False,
                "start_at": "2026-07-01T00:00:00+00:00",
                "end_at": "2026-07-31T00:00:00+00:00",
                "limit": 7,
            },
            request["payload"],
        )
        self.assertEqual(["space_work"], request["control"]["spaces"])
        self.assertEqual(["event"], request["control"]["memory_types"])
        self.assertEqual("visible_events", request["control"]["operation"])
        self.assertEqual(canonical_json_bytes(request), wire)
        self.assertNotIn("visible memory", diagnostic)
        with self.assertRaisesRegex(
            AndroidLocalNodeOperationUnsupported,
            "^android_local_node_operation_unsupported$",
        ):
            store.get_event(
                "evt_visible_synthetic",
                scope=self.scope,
                space="space_work",
                memory_type="event",
            )
        self.assertEqual(1, len(channel.calls))
        store.close()

    def test_malicious_visible_event_results_fail_closed_and_poison_channel(self) -> None:
        valid_event = {
            "event_id": "evt_visible_synthetic",
            "space_id": "space_work",
            "memory_type": "event",
            "revision": 1,
            "event_type": "result",
            "title": "Visible",
            "description": "Bounded structured content.",
            "fact_status": "confirmed",
            "evidence_state": "observed",
            "sensitivity": "personal",
            "data_class": "structured",
            "content_truncated": False,
        }
        cases = (
            {"events": [{**valid_event, "raw": "MALICIOUS"}], "risk_filtered": False},
            {
                "events": [{**valid_event, "space_id": "space_secret"}],
                "risk_filtered": False,
            },
            {
                "events": [{**valid_event, "sensitivity": "restricted"}],
                "risk_filtered": True,
            },
            {
                "events": [valid_event, dict(valid_event)],
                "risk_filtered": False,
            },
            {"events": [valid_event], "risk_filtered": 0},
            {
                "events": [{**valid_event, "description": "x" * 1_001}],
                "risk_filtered": False,
            },
        )
        for result in cases:
            with self.subTest(result=result):
                channel = FakeChannel(
                    lambda request, value=result: AndroidLocalNodeResponse.for_request(
                        request,
                        result=value,
                    )
                )
                store = self.store(channel)
                with self.assertRaisesRegex(
                    AndroidLocalNodeProtocolError,
                    "^android_local_node_visible_events_result_invalid$",
                ):
                    self.visible(store)
                with self.assertRaisesRegex(
                    AndroidLocalNodeUnavailable,
                    "^android_local_node_unavailable$",
                ):
                    self.visible(store)
                self.assertEqual(1, len(channel.calls))
                self.assertGreaterEqual(channel.closed, 1)
                store.close()

    def test_revision_only_channel_does_not_require_create_authority(self) -> None:
        channel = FakeChannel(supported_operations={"append_revision"})
        store = self.store(channel)

        result = self.append(store)

        self.assertEqual("rev_synthetic_002", result["event_revision_id"])
        self.assertEqual(frozenset({"append_revision"}), store.supported_operations)
        with self.assertRaisesRegex(
            AndroidLocalNodeOperationUnsupported,
            "^android_local_node_operation_unsupported$",
        ):
            self.create(store)
        self.assertEqual(1, len(channel.calls))
        store.close()

    def test_undo_only_channel_can_use_persisted_control_token_without_write_authority(
        self,
    ) -> None:
        token = "idem_" + "7" * 64
        initial = self.store(FakeChannel())
        initial.state["undo"][token] = {
            "caller_id": "agent_synthetic",
            "grant_id": "grant_synthetic",
            "space_id": "space_work",
            "target_event_id": "evt_synthetic",
            "created_object_type": "event",
            "created_object_id": "evt_synthetic",
            "created_revision": 1,
            "expires_at": "2026-07-14T08:10:00Z",
            "used": False,
        }
        initial.save()
        initial.close()
        channel = FakeChannel(supported_operations={"undo_capture"})
        reopened = self.store(channel)

        result = reopened.undo_capture(
            reopened.state["undo"][token],
            scope=self.scope,
            space="space_work",
            memory_type="event",
            now="2026-07-14T08:03:00+00:00",
            idempotency_key=token,
        )

        self.assertEqual("undone", result["state"])
        self.assertEqual(frozenset({"undo_capture"}), reopened.supported_operations)
        with self.assertRaisesRegex(
            AndroidLocalNodeOperationUnsupported,
            "^android_local_node_operation_unsupported$",
        ):
            self.create(reopened)
        self.assertEqual(1, len(channel.calls))
        reopened.close()

    def test_scope_excess_fails_before_channel_use(self) -> None:
        channel = FakeChannel()
        store = self.store(channel)
        with self.assertRaises(EventNodeNotVisible):
            self.create(store, space="space_secret")
        narrow_scope = EventNodeScope(
            owner_id=self.scope.owner_id,
            caller_id=self.scope.caller_id,
            grant_id=self.scope.grant_id,
            purpose=self.scope.purpose,
            spaces=("space_work",),
            memory_types=("revision",),
        )
        with self.assertRaises(EventNodeNotVisible):
            self.create(store, scope=narrow_scope)
        self.assertEqual([], channel.calls)
        store.close()

    def test_remote_statuses_have_stable_error_mapping(self) -> None:
        cases = (
            ("NOT_VISIBLE", EventNodeNotVisible, "android_local_node_not_visible"),
            ("REVISION_CONFLICT", EventNodeConflict, "android_local_node_conflict"),
            (
                "IDEMPOTENCY_CONFLICT",
                EventNodeIdempotencyConflict,
                "android_local_node_idempotency_conflict",
            ),
            (
                "OPERATION_UNSUPPORTED",
                AndroidLocalNodeOperationUnsupported,
                "android_local_node_operation_unsupported",
            ),
            (
                "TEMPORARILY_UNAVAILABLE",
                AndroidLocalNodeUnavailable,
                "android_local_node_unavailable",
            ),
        )
        for code, error_type, message in cases:
            with self.subTest(code=code):
                channel = FakeChannel(
                    lambda request, value=code: AndroidLocalNodeResponse.for_request(
                        request,
                        error_code=value,
                    )
                )
                store = self.store(channel)
                with self.assertRaisesRegex(error_type, f"^{message}$"):
                    self.create(store)
                store.close()

    def test_response_binding_mismatch_fails_closed(self) -> None:
        def wrong_request_id(request):
            result = {"object_type": "event", "event_id": "evt_other", "revision": 1}
            return AndroidLocalNodeResponse(
                canonical_json_bytes(
                    {
                        "protocol_version": PROTOCOL_VERSION,
                        "request_id": "req_other",
                        "status": "ok",
                        "result": result,
                        "result_digest": digest_json(result),
                        "error": None,
                    }
                )
            )

        channel = FakeChannel(wrong_request_id)
        store = self.store(channel)
        with self.assertRaisesRegex(
            AndroidLocalNodeProtocolError,
            "^android_local_node_response_invalid$",
        ):
            self.create(store)
        with self.assertRaisesRegex(
            AndroidLocalNodeUnavailable,
            "^android_local_node_unavailable$",
        ):
            self.create(store)
        self.assertEqual(1, len(channel.calls))
        store.close()

    def test_duplicate_keys_float_nan_and_lone_surrogate_responses_fail_closed(self) -> None:
        invalid_lines = (
            b'{"protocol_version":"ameme.agent-local-node.v1","protocol_version":"ameme.agent-local-node.v1","request_id":"req_synthetic_001","status":"ok","result":{},"result_digest":"sha256_44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a","error":null}',
            b'{"protocol_version":"ameme.agent-local-node.v1","request_id":"req_synthetic_001","status":"ok","result":{"value":1.5},"result_digest":"sha256_0000000000000000000000000000000000000000000000000000000000000000","error":null}',
            b'{"protocol_version":"ameme.agent-local-node.v1","request_id":"req_synthetic_001","status":"ok","result":{"value":NaN},"result_digest":"sha256_0000000000000000000000000000000000000000000000000000000000000000","error":null}',
            b'{"protocol_version":"ameme.agent-local-node.v1","request_id":"req_synthetic_001","status":"ok","result":{"value":"\\ud800"},"result_digest":"sha256_0000000000000000000000000000000000000000000000000000000000000000","error":null}',
        )
        for line in invalid_lines:
            with self.subTest(line=line[:40]):
                channel = FakeChannel(lambda _: AndroidLocalNodeResponse(line))
                store = self.store(channel)
                with self.assertRaisesRegex(
                    AndroidLocalNodeProtocolError,
                    "^android_local_node_response_invalid$",
                ):
                    self.create(store)
                with self.assertRaisesRegex(
                    AndroidLocalNodeUnavailable,
                    "^android_local_node_unavailable$",
                ):
                    self.create(store)
                self.assertEqual(1, len(channel.calls))
                store.close()

    def test_malicious_create_results_are_rejected_and_poison_channel(self) -> None:
        cases = (
            {
                "object_type": "event",
                "event_id": "evt_extra",
                "revision": 1,
                "content": "MALICIOUS_RESPONSE_CONTENT",
                "space_id": "space_secret",
            },
            {"object_type": "revision", "event_id": "evt_wrong", "revision": 1},
            {"object_type": "event", "event_id": "../bad", "revision": 1},
            {"object_type": "event", "event_id": "evt_bool", "revision": True},
            {"object_type": "event", "event_id": "evt_zero", "revision": 0},
            {"object_type": "event", "event_id": "evt_string", "revision": "1"},
        )
        for result in cases:
            with self.subTest(result=result):
                responses = []

                def malicious(request, value=result):
                    response = AndroidLocalNodeResponse.for_request(
                        request,
                        result=value,
                    )
                    responses.append(response)
                    return response

                channel = FakeChannel(malicious)
                store = self.store(channel)
                with self.assertRaisesRegex(
                    AndroidLocalNodeProtocolError,
                    "^android_local_node_create_result_invalid$",
                ):
                    self.create(store)
                with self.assertRaisesRegex(
                    AndroidLocalNodeUnavailable,
                    "^android_local_node_unavailable$",
                ):
                    self.create(store)
                self.assertEqual(1, len(channel.calls))
                self.assertEqual(1, len(responses))
                self.assertIn("<cleared>", repr(responses[0]))
                self.assertNotIn("MALICIOUS_RESPONSE_CONTENT", repr(responses[0]))
                self.assertGreaterEqual(channel.closed, 1)
                store.close()

    def test_malicious_append_results_are_rejected_and_poison_channel(self) -> None:
        cases = (
            {
                "object_type": "revision",
                "event_revision_id": "rev_extra",
                "target_event_id": "evt_synthetic",
                "revision": 2,
                "content": "MALICIOUS_RESPONSE_CONTENT",
            },
            {
                "object_type": "event",
                "event_revision_id": "rev_wrong_type",
                "target_event_id": "evt_synthetic",
                "revision": 2,
            },
            {
                "object_type": "revision",
                "event_revision_id": "../bad",
                "target_event_id": "evt_synthetic",
                "revision": 2,
            },
            {
                "object_type": "revision",
                "event_revision_id": "rev_wrong_target",
                "target_event_id": "evt_other",
                "revision": 2,
            },
            {
                "object_type": "revision",
                "event_revision_id": "rev_bool",
                "target_event_id": "evt_synthetic",
                "revision": True,
            },
            {
                "object_type": "revision",
                "event_revision_id": "rev_one",
                "target_event_id": "evt_synthetic",
                "revision": 1,
            },
        )
        for result in cases:
            with self.subTest(result=result):
                channel = FakeChannel(
                    lambda request, value=result: AndroidLocalNodeResponse.for_request(
                        request,
                        result=value,
                    )
                )
                store = self.store(channel)
                with self.assertRaisesRegex(
                    AndroidLocalNodeProtocolError,
                    "^android_local_node_append_result_invalid$",
                ):
                    self.append(store)
                with self.assertRaisesRegex(
                    AndroidLocalNodeUnavailable,
                    "^android_local_node_unavailable$",
                ):
                    self.append(store)
                self.assertEqual(1, len(channel.calls))
                self.assertGreaterEqual(channel.closed, 1)
                store.close()

    def test_malicious_undo_results_are_rejected_and_poison_channel(self) -> None:
        valid = {
            "state": "undone",
            "target_event_id": "evt_synthetic",
            "undone_object_type": "revision",
            "undone_object_id": "rev_synthetic_002",
            "compensation_revision_id": "rev_synthetic_003",
            "activity_visible": True,
        }
        cases = (
            {**valid, "content": "MALICIOUS_RESPONSE_CONTENT"},
            {**valid, "state": "deleted"},
            {**valid, "target_event_id": "evt_other"},
            {**valid, "undone_object_type": "event"},
            {**valid, "undone_object_id": "rev_other"},
            {**valid, "compensation_revision_id": "../bad"},
            {**valid, "compensation_revision_id": "rev_synthetic_002"},
            {**valid, "activity_visible": 1},
        )
        for result in cases:
            with self.subTest(result=result):
                channel = FakeChannel(
                    lambda request, value=result: AndroidLocalNodeResponse.for_request(
                        request,
                        result=value,
                    )
                )
                store = self.store(channel)
                with self.assertRaisesRegex(
                    AndroidLocalNodeProtocolError,
                    "^android_local_node_undo_result_invalid$",
                ):
                    store.undo_capture(
                        {
                            "target_event_id": "evt_synthetic",
                            "created_object_type": "revision",
                            "created_object_id": "rev_synthetic_002",
                        },
                        scope=self.scope,
                        space="space_work",
                        memory_type="revision",
                        now="2026-07-14T08:03:00+00:00",
                        idempotency_key="idem_" + "1" * 64,
                    )
                with self.assertRaisesRegex(
                    AndroidLocalNodeUnavailable,
                    "^android_local_node_unavailable$",
                ):
                    self.append(store)
                self.assertEqual(1, len(channel.calls))
                self.assertGreaterEqual(channel.closed, 1)
                store.close()

    def test_timeout_allows_only_one_inflight_exchange_then_poison(self) -> None:
        release = threading.Event()
        observed_after_release = []
        late_responses = []

        def hanging(request):
            release.wait(timeout=1.0)
            try:
                request.wire_copy()
            except RuntimeError:
                observed_after_release.append("cleared")
            response = AndroidLocalNodeResponse(b"{}")
            late_responses.append(response)
            return response

        channel = FakeChannel(hanging)
        store = self.store(channel, response_timeout_seconds=0.05)
        started = time.monotonic()
        with self.assertRaisesRegex(
            AndroidLocalNodeUnavailable,
            "^android_local_node_deadline_exceeded$",
        ):
            self.create(store)
        self.assertLess(time.monotonic() - started, 0.5)
        with self.assertRaisesRegex(
            AndroidLocalNodeUnavailable,
            "^android_local_node_unavailable$",
        ):
            self.create(store)
        self.assertEqual(1, len(channel.calls))
        release.set()
        time.sleep(0.05)
        self.assertEqual(["cleared"], observed_after_release)
        self.assertEqual(1, len(late_responses))
        self.assertIn("<cleared>", repr(late_responses[0]))
        self.assertGreaterEqual(channel.closed, 1)
        store.close()

    def test_session_binding_mismatch_closes_and_rejects_channel(self) -> None:
        channel = FakeChannel()
        channel.binding = AndroidLocalNodeSessionBinding(
            device_id="device_other",
            session_binding_ref="session-binding-ref:other",
        )
        with self.assertRaisesRegex(
            AndroidLocalNodeUnavailable,
            "^android_local_node_session_binding_failed$",
        ):
            self.store(channel)
        self.assertEqual(1, channel.closed)
        self.assertNotIn("device_other", repr(channel.binding))
        self.assertNotIn("other", repr(channel.binding))


if __name__ == "__main__":
    unittest.main()
