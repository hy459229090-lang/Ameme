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
            supported_operations or {"create_event"}
        )
        self.calls = []
        self.closed = 0

    def _default_handler(self, request):
        operation = parse_request_line(request.wire_copy())["control"]["operation"]
        if operation == "create_event":
            result = {"object_type": "event", "event_id": "evt_synthetic", "revision": 1}
        elif operation == "append_revision":
            result = {
                "object_type": "revision",
                "event_revision_id": "evr_synthetic",
                "target_event_id": "evt_synthetic",
                "revision": 2,
            }
        else:
            result = None
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

    def test_grant_superset_emits_minimal_request_scope_and_redacted_control(self) -> None:
        channel = FakeChannel(supported_operations={"create_event", "append_revision"})
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
        unsupported = server.handle(
            {
                "jsonrpc": "2.0",
                "id": 8,
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
        problem = unsupported["result"]["structuredContent"]
        self.assertTrue(unsupported["result"]["isError"])
        self.assertEqual("OPERATION_UNSUPPORTED", problem["code"])
        self.assertFalse(problem["retryable"])
        self.assertEqual(1, len(channel.calls))
        store.close()

    def test_undeclared_channel_operation_is_stably_rejected_without_exchange(self) -> None:
        channel = FakeChannel()
        store = self.store(channel)
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
        store.close()

    def test_idempotency_slot_is_operation_domain_separated(self) -> None:
        channel = FakeChannel(supported_operations={"create_event", "append_revision"})
        store = self.store(channel)
        self.create(store)
        store.append_revision(
            scope=self.scope,
            space="space_work",
            event_id="evt_synthetic",
            content="Synthetic revision",
            evidence_state="user_asserted",
            fact_status="user_asserted",
            now="2026-07-14T08:02:00+00:00",
            idempotency_key="RAW_IDEMPOTENCY_KEY_SYNTHETIC",
        )

        self.assertNotEqual(
            channel.calls[0][0]["control"]["idempotency_slot"],
            channel.calls[1][0]["control"]["idempotency_slot"],
        )
        store.close()

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

    def test_response_binding_and_scope_mismatch_fail_closed(self) -> None:
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

    def test_overscoped_success_result_poisons_channel(self) -> None:
        def cross_space_event(request):
            return AndroidLocalNodeResponse.for_request(
                request,
                result={
                    "event_id": "evt_cross_space",
                    "owner_id": self.scope.owner_id,
                    "space_id": "space_secret",
                    "memory_type": "event",
                },
            )

        channel = FakeChannel(cross_space_event, supported_operations={"create_event", "get_event"})
        store = self.store(channel)
        with self.assertRaisesRegex(
            AndroidLocalNodeProtocolError,
            "^android_local_node_result_scope_mismatch$",
        ):
            store.get_event(
                "evt_cross_space",
                scope=self.scope,
                space="space_work",
                memory_type="event",
            )
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
