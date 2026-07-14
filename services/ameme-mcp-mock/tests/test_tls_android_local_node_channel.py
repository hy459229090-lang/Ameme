from __future__ import annotations

from datetime import datetime, timedelta, timezone
import hashlib
import ipaddress
from pathlib import Path
import socket
import ssl
import sys
import threading
from tempfile import TemporaryDirectory
import unittest

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID


SERVICE_ROOT = Path(__file__).resolve().parents[1]
PROTOCOL_ROOT = SERVICE_ROOT.parents[1] / "packages" / "agent-local-node-protocol"
for path in (SERVICE_ROOT, PROTOCOL_ROOT):
    if str(path) not in sys.path:
        sys.path.insert(0, str(path))

from ameme_agent_local_node_protocol import (  # noqa: E402
    CHANNEL_PROTOCOL_VERSION,
    build_response_frame,
    build_server_hello,
    canonical_json_bytes,
    derive_session_key,
    digest_json,
    parse_request_frame,
    verify_client_hello,
)
from ameme_mcp_mock.android_local_node_store import (  # noqa: E402
    AndroidLocalNodeChannelConfig,
    AndroidLocalNodeStore,
    AndroidLocalNodeUnavailable,
)
from ameme_mcp_mock.event_store import EventNodeScope  # noqa: E402
from ameme_mcp_mock.tls_android_local_node_channel import (  # noqa: E402
    AndroidLocalNodePairingMaterial,
    EnvironmentCredentialResolver,
    TlsAndroidLocalNodeChannelError,
    TlsAndroidLocalNodeChannelFactory,
)


SYNTHETIC_SECRET = b"synthetic-pairing-secret-value-at-least-32-bytes"


class SyntheticCredentialResolver:
    def __init__(self, secret: bytes = SYNTHETIC_SECRET) -> None:
        self.secret = secret
        self.resolved = []

    def resolve(self, credential_ref: str) -> bytearray:
        value = bytearray(self.secret)
        self.resolved.append((credential_ref, value))
        return value


class SyntheticTlsLocalNodeServer:
    def __init__(self, root: Path, *, behavior: str = "success") -> None:
        self.behavior = behavior
        self.root = root
        self.error: Exception | None = None
        self.request_frame = None
        self.application_request = None
        self.tls_version = None
        self._certificate, self._key = self._certificate_files()
        self._listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._listener.bind(("127.0.0.1", 0))
        self._listener.listen(1)
        self.port = self._listener.getsockname()[1]
        self.thread = threading.Thread(target=self._run, daemon=True)

    def _certificate_files(self) -> tuple[Path, Path]:
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "synthetic-ameme")])
        now = datetime.now(timezone.utc)
        certificate = (
            x509.CertificateBuilder()
            .subject_name(name)
            .issuer_name(name)
            .public_key(key.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(now - timedelta(minutes=1))
            .not_valid_after(now + timedelta(days=1))
            .add_extension(
                x509.SubjectAlternativeName(
                    [x509.IPAddress(ipaddress.ip_address("127.0.0.1"))]
                ),
                critical=False,
            )
            .sign(key, hashes.SHA256())
        )
        certificate_path = self.root / "synthetic-cert.pem"
        key_path = self.root / "synthetic-key.pem"
        certificate_path.write_bytes(certificate.public_bytes(serialization.Encoding.PEM))
        key_path.write_bytes(
            key.private_bytes(
                serialization.Encoding.PEM,
                serialization.PrivateFormat.PKCS8,
                serialization.NoEncryption(),
            )
        )
        self.certificate_pin = "sha256_" + hashlib.sha256(
            certificate.public_bytes(serialization.Encoding.DER)
        ).hexdigest()
        return certificate_path, key_path

    def pairing(self, *, certificate_pin: str | None = None) -> AndroidLocalNodePairingMaterial:
        return AndroidLocalNodePairingMaterial(
            channel_protocol=CHANNEL_PROTOCOL_VERSION,
            endpoint_ref="endpoint-ref:synthetic/android",
            credential_ref="credential-ref:env/SYNTHETIC_SECRET",
            expected_device_id="device_synthetic_001",
            session_binding_ref="session-binding-ref:synthetic/session",
            pairing_id="pair_synthetic_001",
            host="127.0.0.1",
            port=self.port,
            tls_certificate_sha256=certificate_pin or self.certificate_pin,
        )

    def start(self) -> None:
        self.thread.start()

    def join(self) -> None:
        self.thread.join(timeout=3)
        self._listener.close()

    def _run(self) -> None:
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.minimum_version = ssl.TLSVersion.TLSv1_3
        context.maximum_version = ssl.TLSVersion.TLSv1_3
        context.load_cert_chain(self._certificate, self._key)
        try:
            connection, _ = self._listener.accept()
            with context.wrap_socket(connection, server_side=True) as stream:
                stream.settimeout(2)
                self.tls_version = stream.version()
                client_line = self._receive_line(stream)
                pairing = self.pairing().as_document()
                client_hello = verify_client_hello(
                    client_line,
                    pairing,
                    SYNTHETIC_SECRET,
                )
                hello_secret = (
                    b"wrong-synthetic-secret-value-at-least-32-bytes"
                    if self.behavior == "bad_server_proof"
                    else SYNTHETIC_SECRET
                )
                server_line = build_server_hello(
                    client_hello,
                    pairing,
                    hello_secret,
                    server_nonce="nonce_" + "2" * 64,
                    session_id="sess_synthetic_tls_001",
                    supported_operations={"create_event"},
                )
                self._send_line(stream, server_line)
                if self.behavior == "bad_server_proof":
                    return
                from ameme_agent_local_node_protocol import verify_server_hello

                server_hello = verify_server_hello(
                    server_line,
                    client_hello,
                    pairing,
                    SYNTHETIC_SECRET,
                )
                session_key = derive_session_key(
                    client_hello,
                    server_hello,
                    SYNTHETIC_SECRET,
                )
                request_line = self._receive_line(stream)
                frame, _, application = parse_request_frame(
                    request_line,
                    session_key,
                    expected_session_id=server_hello["session_id"],
                    expected_sequence=1,
                    seen_nonces=set(),
                )
                self.request_frame = frame
                self.application_request = application
                result = {
                    "object_type": "event",
                    "event_id": "evt_synthetic_tls_001",
                    "revision": 1,
                }
                application_response = canonical_json_bytes(
                    {
                        "protocol_version": application["protocol_version"],
                        "request_id": application["request_id"],
                        "status": "ok",
                        "result": result,
                        "result_digest": digest_json(result),
                        "error": None,
                    }
                )
                response_request_frame = dict(frame)
                if self.behavior == "bad_response_sequence":
                    response_request_frame["sequence"] += 1
                response_frame = build_response_frame(
                    application_response,
                    response_request_frame,
                    session_key,
                    nonce="nonce_" + "3" * 64,
                )
                self._send_line(stream, response_frame)
        except Exception as exc:
            self.error = exc

    @staticmethod
    def _receive_line(stream: ssl.SSLSocket) -> bytes:
        value = bytearray()
        while len(value) < 786_432:
            chunk = stream.recv(16_384)
            if not chunk:
                raise EOFError("synthetic peer closed")
            if b"\n" in chunk:
                before, after = chunk.split(b"\n", 1)
                if after:
                    raise ValueError("synthetic extra frame")
                value.extend(before)
                return bytes(value)
            value.extend(chunk)
        raise ValueError("synthetic frame too large")

    @staticmethod
    def _send_line(stream: ssl.SSLSocket, value: bytes) -> None:
        stream.sendall(value + b"\n")


class TlsAndroidLocalNodeChannelTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.scope = EventNodeScope(
            owner_id="owner_synthetic",
            caller_id="agent_synthetic",
            grant_id="grant_synthetic",
            purpose="autonomous_memory",
            spaces=("space_work",),
            memory_types=("event",),
        )

    def tearDown(self) -> None:
        self.temp.cleanup()

    @staticmethod
    def config(pairing: AndroidLocalNodePairingMaterial) -> AndroidLocalNodeChannelConfig:
        return AndroidLocalNodeChannelConfig(
            endpoint_ref=pairing.endpoint_ref,
            credential_ref=pairing.credential_ref,
            expected_device_id=pairing.expected_device_id,
            session_binding_ref=pairing.session_binding_ref,
        )

    def test_tls13_pin_handshake_and_create_event_end_to_end(self) -> None:
        server = SyntheticTlsLocalNodeServer(self.root)
        server.start()
        pairing = server.pairing()
        resolver = SyntheticCredentialResolver()
        channel = TlsAndroidLocalNodeChannelFactory(pairing, resolver)(
            self.config(pairing)
        )
        store = AndroidLocalNodeStore(
            self.root / "mcp-control.json",
            channel=channel,
            channel_config=self.config(pairing),
        )
        result = store.create_event(
            scope=self.scope,
            space="space_work",
            content="SYNTHETIC_TLS_MEMORY_CONTENT",
            event_time="2026-07-14T08:00:00+00:00",
            event_type="result",
            evidence_state="observed",
            fact_status="confirmed",
            sensitivity="personal",
            data_class="structured",
            now="2026-07-14T08:01:00+00:00",
            idempotency_key="RAW_SYNTHETIC_TLS_IDEMPOTENCY_KEY",
        )
        self.assertEqual("evt_synthetic_tls_001", result["event_id"])
        self.assertEqual("TLSv1.3", server.tls_version)
        self.assertEqual(
            server.application_request["control"]["idempotency_slot"],
            server.request_frame["idempotency_ref"],
        )
        self.assertNotIn(
            "RAW_SYNTHETIC_TLS_IDEMPOTENCY_KEY",
            str(server.request_frame),
        )
        self.assertTrue(all(not any(secret for secret in value) for _, value in resolver.resolved))
        self.assertNotIn("SYNTHETIC_TLS_MEMORY_CONTENT", repr(channel))
        store.close()
        server.join()
        self.assertIsNone(server.error)

    def test_pin_and_server_proof_mismatch_fail_before_channel_is_returned(self) -> None:
        for behavior, pin in (
            ("success", "sha256_" + "0" * 64),
            ("bad_server_proof", None),
        ):
            with self.subTest(behavior=behavior):
                server = SyntheticTlsLocalNodeServer(self.root, behavior=behavior)
                server.start()
                pairing = server.pairing(certificate_pin=pin)
                resolver = SyntheticCredentialResolver()
                with self.assertRaisesRegex(
                    TlsAndroidLocalNodeChannelError,
                    "^android_local_node_tls_handshake_failed$",
                ):
                    TlsAndroidLocalNodeChannelFactory(pairing, resolver)(
                        self.config(pairing)
                    )
                self.assertTrue(
                    all(not any(secret for secret in value) for _, value in resolver.resolved)
                )
                server.join()

    def test_bad_response_sequence_closes_channel_and_store_fails_closed(self) -> None:
        server = SyntheticTlsLocalNodeServer(
            self.root,
            behavior="bad_response_sequence",
        )
        server.start()
        pairing = server.pairing()
        channel = TlsAndroidLocalNodeChannelFactory(
            pairing,
            SyntheticCredentialResolver(),
        )(self.config(pairing))
        store = AndroidLocalNodeStore(
            self.root / "mcp-control.json",
            channel=channel,
            channel_config=self.config(pairing),
        )
        with self.assertRaisesRegex(
            AndroidLocalNodeUnavailable,
            "^android_local_node_exchange_failed$",
        ) as raised:
            store.create_event(
                scope=self.scope,
                space="space_work",
                content="SYNTHETIC_BAD_SEQUENCE_CONTENT",
                event_time=None,
                event_type="result",
                evidence_state="observed",
                fact_status="confirmed",
                sensitivity="personal",
                data_class="structured",
                now="2026-07-14T08:01:00+00:00",
                idempotency_key="synthetic-bad-sequence-key",
            )
        self.assertNotIn("SYNTHETIC_BAD_SEQUENCE_CONTENT", str(raised.exception))
        self.assertIn("state=closed", repr(channel))
        store.close()
        server.join()

    def test_pairing_binding_and_environment_reference_are_fail_closed(self) -> None:
        resolver = EnvironmentCredentialResolver(
            {"SYNTHETIC_SECRET": SYNTHETIC_SECRET.decode("ascii")}
        )
        secret = resolver.resolve("credential-ref:env/SYNTHETIC_SECRET")
        self.assertEqual(SYNTHETIC_SECRET, bytes(secret))
        self.assertNotIn("SYNTHETIC_SECRET", repr(resolver))
        with self.assertRaisesRegex(
            TlsAndroidLocalNodeChannelError,
            "credential_reference_invalid",
        ):
            resolver.resolve("credential-ref:literal/forbidden")


if __name__ == "__main__":
    unittest.main()
