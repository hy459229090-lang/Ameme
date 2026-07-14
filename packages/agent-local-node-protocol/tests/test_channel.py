from __future__ import annotations

from pathlib import Path
import sys
import unittest


PACKAGE_ROOT = Path(__file__).resolve().parents[1]
if str(PACKAGE_ROOT) not in sys.path:
    sys.path.insert(0, str(PACKAGE_ROOT))

from ameme_agent_local_node_protocol import (  # noqa: E402
    CHANNEL_PROTOCOL_VERSION,
    ChannelViolation,
    build_client_hello,
    build_conformance_document,
    build_request_frame,
    build_response_frame,
    build_server_hello,
    canonical_json_bytes,
    derive_session_key,
    digest_json,
    parse_pairing_material,
    parse_request_frame,
    parse_response_frame,
    verify_client_hello,
    verify_server_hello,
)


class AgentLocalNodeChannelTests(unittest.TestCase):
    def setUp(self) -> None:
        self.secret = b"synthetic-pairing-secret-32-bytes-minimum-value"
        self.pairing = {
            "channel_protocol": CHANNEL_PROTOCOL_VERSION,
            "endpoint_ref": "endpoint-ref:synthetic/android",
            "credential_ref": "credential-ref:env/SYNTHETIC_SECRET",
            "expected_device_id": "device_synthetic_001",
            "session_binding_ref": "session-binding-ref:synthetic/session",
            "pairing_id": "pair_synthetic_001",
            "host": "127.0.0.1",
            "port": 44321,
            "tls_certificate_sha256": "sha256_" + "a" * 64,
        }
        self.application_request = build_conformance_document()["positive_requests"][1][
            "request"
        ]
        self.application_request_line = canonical_json_bytes(self.application_request)

    def assert_channel_code(self, code: str, call) -> None:
        with self.assertRaises(ChannelViolation) as raised:
            call()
        self.assertEqual(code, raised.exception.code)

    def handshake(self):
        client_line = build_client_hello(
            self.pairing,
            self.secret,
            client_nonce="nonce_" + "1" * 64,
        )
        client = verify_client_hello(client_line, self.pairing, self.secret)
        server_line = build_server_hello(
            client,
            self.pairing,
            self.secret,
            server_nonce="nonce_" + "2" * 64,
            session_id="sess_synthetic_001",
            supported_operations={"create_event"},
        )
        server = verify_server_hello(
            server_line,
            client,
            self.pairing,
            self.secret,
        )
        return client, server, derive_session_key(client, server, self.secret)

    def test_pairing_material_is_strict_and_rejects_unspecified_endpoint(self) -> None:
        parsed = parse_pairing_material(canonical_json_bytes(self.pairing))
        self.assertEqual(self.pairing, parsed)
        duplicate = canonical_json_bytes(self.pairing).replace(
            b'{"channel_protocol":',
            b'{"channel_protocol":"duplicate","channel_protocol":',
        )
        self.assert_channel_code(
            "INVALID_CHANNEL_MESSAGE",
            lambda: parse_pairing_material(duplicate),
        )
        unspecified = dict(self.pairing, host="0.0.0.0")
        self.assert_channel_code(
            "INVALID_CHANNEL_MESSAGE",
            lambda: parse_pairing_material(canonical_json_bytes(unspecified)),
        )

    def test_handshake_binds_pairing_device_pin_and_session(self) -> None:
        client, server, session_key = self.handshake()
        self.assertEqual(self.pairing["expected_device_id"], server["device_id"])
        self.assertEqual(self.pairing["session_binding_ref"], server["session_binding_ref"])
        self.assertEqual(self.pairing["tls_certificate_sha256"], server["tls_certificate_sha256"])
        self.assertEqual(client["client_nonce"], server["client_nonce"])
        self.assertEqual(32, len(session_key))

        wrong_pairing = dict(self.pairing, expected_device_id="device_other")
        server_line = build_server_hello(
            client,
            self.pairing,
            self.secret,
            server_nonce="nonce_" + "3" * 64,
            session_id="sess_synthetic_other",
            supported_operations={"create_event"},
        )
        self.assert_channel_code(
            "CHANNEL_BINDING_FAILED",
            lambda: verify_server_hello(
                server_line,
                client,
                wrong_pairing,
                self.secret,
            ),
        )

    def test_frames_bind_sequence_nonce_idempotency_and_application_v1(self) -> None:
        _, server, session_key = self.handshake()
        request_line = build_request_frame(
            self.application_request_line,
            session_key,
            session_id=server["session_id"],
            sequence=1,
            nonce="nonce_" + "4" * 64,
        )
        request_frame, application_line, application_request = parse_request_frame(
            request_line,
            session_key,
            expected_session_id=server["session_id"],
            expected_sequence=1,
            seen_nonces=set(),
        )
        self.assertEqual(self.application_request_line, application_line)
        self.assertEqual(
            self.application_request["control"]["idempotency_slot"],
            request_frame["idempotency_ref"],
        )

        result = {"object_type": "event", "event_id": "evt_synthetic_001", "revision": 1}
        response_line = canonical_json_bytes(
            {
                "protocol_version": self.application_request["protocol_version"],
                "request_id": self.application_request["request_id"],
                "status": "ok",
                "result": result,
                "result_digest": digest_json(result),
                "error": None,
            }
        )
        reused_nonce_line = build_response_frame(
            response_line,
            request_frame,
            session_key,
            nonce=request_frame["nonce"],
        )
        self.assert_channel_code(
            "CHANNEL_REPLAY",
            lambda: parse_response_frame(
                reused_nonce_line,
                session_key,
                request_frame=request_frame,
                application_request=application_request,
                seen_nonces=set(),
            ),
        )
        frame_line = build_response_frame(
            response_line,
            request_frame,
            session_key,
            nonce="nonce_" + "5" * 64,
        )
        seen_response_nonces: set[str] = set()
        _, decoded_response, _ = parse_response_frame(
            frame_line,
            session_key,
            request_frame=request_frame,
            application_request=application_request,
            seen_nonces=seen_response_nonces,
        )
        self.assertEqual(response_line, decoded_response)
        self.assert_channel_code(
            "CHANNEL_REPLAY",
            lambda: parse_response_frame(
                frame_line,
                session_key,
                request_frame=request_frame,
                application_request=application_request,
                seen_nonces=seen_response_nonces,
            ),
        )

    def test_wrong_sequence_and_proof_fail_closed_without_payload_detail(self) -> None:
        _, server, session_key = self.handshake()
        line = build_request_frame(
            self.application_request_line,
            session_key,
            session_id=server["session_id"],
            sequence=1,
            nonce="nonce_" + "6" * 64,
        )
        self.assert_channel_code(
            "CHANNEL_SEQUENCE_INVALID",
            lambda: parse_request_frame(
                line,
                session_key,
                expected_session_id=server["session_id"],
                expected_sequence=2,
                seen_nonces=set(),
            ),
        )
        self.assert_channel_code(
            "CHANNEL_SEQUENCE_INVALID",
            lambda: build_request_frame(
                self.application_request_line,
                session_key,
                session_id=server["session_id"],
                sequence=9_223_372_036_854_775_808,
                nonce="nonce_" + "7" * 64,
            ),
        )
        tampered = line.replace(b'"proof":"hmac_', b'"proof":"hmac_0', 1)
        with self.assertRaises((ChannelViolation, ValueError)) as raised:
            parse_request_frame(
                tampered,
                session_key,
                expected_session_id=server["session_id"],
                expected_sequence=1,
                seen_nonces=set(),
            )
        self.assertNotIn("milestone", str(raised.exception))


if __name__ == "__main__":
    unittest.main()
