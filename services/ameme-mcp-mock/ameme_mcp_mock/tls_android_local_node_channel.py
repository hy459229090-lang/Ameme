"""Outbound TLS 1.3 channel client for a paired Android Local Node endpoint."""

from __future__ import annotations

from dataclasses import dataclass
import hashlib
import hmac
import os
from pathlib import Path
import re
import secrets
import socket
import ssl
import threading
from typing import Mapping, Protocol

from ameme_agent_local_node_protocol import (
    MAX_CHANNEL_LINE_BYTES,
    ChannelViolation,
    build_client_hello,
    build_request_frame,
    canonical_json_bytes,
    derive_session_key,
    parse_pairing_material,
    parse_response_frame,
    parse_strict_json_line,
    verify_client_hello,
    verify_server_hello,
)

from .android_local_node_store import (
    AndroidLocalNodeChannelConfig,
    AndroidLocalNodeRequest,
    AndroidLocalNodeResponse,
    AndroidLocalNodeSessionBinding,
)


MAX_CHANNEL_SECONDS = 5.0
_ENV_REF = re.compile(r"^credential-ref:env/([A-Z][A-Z0-9_]{0,127})$")


class TlsAndroidLocalNodeChannelError(RuntimeError):
    """Stable content-free network channel failure."""


@dataclass(frozen=True, repr=False)
class AndroidLocalNodePairingMaterial:
    channel_protocol: str
    endpoint_ref: str
    credential_ref: str
    expected_device_id: str
    session_binding_ref: str
    pairing_id: str
    host: str
    port: int
    tls_certificate_sha256: str

    def __post_init__(self) -> None:
        try:
            parse_pairing_material(canonical_json_bytes(self.as_document()))
        except Exception:
            raise TlsAndroidLocalNodeChannelError(
                "android_local_node_pairing_material_invalid"
            ) from None

    @classmethod
    def load(cls, path: Path) -> "AndroidLocalNodePairingMaterial":
        try:
            document = parse_pairing_material(path.read_bytes())
            return cls(**document)
        except Exception:
            raise TlsAndroidLocalNodeChannelError(
                "android_local_node_pairing_material_invalid"
            ) from None

    def as_document(self) -> dict[str, object]:
        return {
            "channel_protocol": self.channel_protocol,
            "endpoint_ref": self.endpoint_ref,
            "credential_ref": self.credential_ref,
            "expected_device_id": self.expected_device_id,
            "session_binding_ref": self.session_binding_ref,
            "pairing_id": self.pairing_id,
            "host": self.host,
            "port": self.port,
            "tls_certificate_sha256": self.tls_certificate_sha256,
        }

    def __repr__(self) -> str:
        return (
            "AndroidLocalNodePairingMaterial(endpoint=<redacted>, "
            "credential=<reference>, device=<redacted>, pin=<redacted>)"
        )


class AndroidLocalNodeCredentialResolver(Protocol):
    def resolve(self, credential_ref: str) -> bytearray: ...


class EnvironmentCredentialResolver:
    """Resolve only an environment reference; never place the secret in CLI arguments."""

    def __init__(self, environment: Mapping[str, str] | None = None) -> None:
        self._environment = environment if environment is not None else os.environ

    def resolve(self, credential_ref: str) -> bytearray:
        match = _ENV_REF.fullmatch(credential_ref)
        if match is None:
            raise TlsAndroidLocalNodeChannelError(
                "android_local_node_credential_reference_invalid"
            )
        value = self._environment.get(match.group(1))
        if value is None:
            raise TlsAndroidLocalNodeChannelError(
                "android_local_node_credential_unavailable"
            )
        encoded = value.encode("utf-8")
        if not 32 <= len(encoded) <= 256:
            raise TlsAndroidLocalNodeChannelError(
                "android_local_node_credential_invalid"
            )
        return bytearray(encoded)

    def __repr__(self) -> str:
        return "EnvironmentCredentialResolver(environment=<redacted>)"


class TlsAndroidLocalNodeChannel:
    """Single TLS/session-bound, sequential channel implementing the injected port."""

    def __init__(
        self,
        tls_socket: ssl.SSLSocket,
        *,
        pairing: AndroidLocalNodePairingMaterial,
        session_key: bytes,
        session_id: str,
        supported_operations: frozenset[str],
        timeout_seconds: float,
    ) -> None:
        self._socket = tls_socket
        self._session_key = bytearray(session_key)
        self._session_id = session_id
        self._timeout_seconds = timeout_seconds
        self._sequence = 0
        self._response_nonces: set[str] = set()
        self._lock = threading.Lock()
        self._closed = False
        self.binding = AndroidLocalNodeSessionBinding(
            pairing.expected_device_id,
            pairing.session_binding_ref,
        )
        self.supported_operations = supported_operations

    @classmethod
    def connect(
        cls,
        pairing: AndroidLocalNodePairingMaterial,
        secret: bytes | bytearray,
        *,
        timeout_seconds: float = MAX_CHANNEL_SECONDS,
    ) -> "TlsAndroidLocalNodeChannel":
        if timeout_seconds <= 0 or timeout_seconds > MAX_CHANNEL_SECONDS:
            raise TlsAndroidLocalNodeChannelError(
                "android_local_node_channel_timeout_invalid"
            )
        raw_socket: socket.socket | None = None
        tls_socket: ssl.SSLSocket | None = None
        try:
            raw_socket = socket.create_connection(
                (pairing.host, pairing.port),
                timeout=timeout_seconds,
            )
            context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
            context.minimum_version = ssl.TLSVersion.TLSv1_3
            context.maximum_version = ssl.TLSVersion.TLSv1_3
            context.check_hostname = False
            context.verify_mode = ssl.CERT_NONE
            tls_socket = context.wrap_socket(raw_socket, server_hostname=pairing.host)
            raw_socket = None
            tls_socket.settimeout(timeout_seconds)
            if tls_socket.version() != "TLSv1.3":
                raise TlsAndroidLocalNodeChannelError(
                    "android_local_node_tls_version_invalid"
                )
            certificate = tls_socket.getpeercert(binary_form=True)
            observed_pin = "sha256_" + hashlib.sha256(certificate or b"").hexdigest()
            if not certificate or not hmac.compare_digest(
                observed_pin,
                pairing.tls_certificate_sha256,
            ):
                raise TlsAndroidLocalNodeChannelError(
                    "android_local_node_tls_pin_mismatch"
                )

            client_line = build_client_hello(
                pairing.as_document(),
                secret,
                client_nonce=cls._new_nonce(),
            )
            client_hello = verify_client_hello(
                client_line,
                pairing.as_document(),
                secret,
            )
            cls._send_line(tls_socket, client_line)
            server_line = cls._receive_line(tls_socket)
            server_hello = verify_server_hello(
                server_line,
                client_hello,
                pairing.as_document(),
                secret,
            )
            session_key = derive_session_key(client_hello, server_hello, secret)
            return cls(
                tls_socket,
                pairing=pairing,
                session_key=session_key,
                session_id=server_hello["session_id"],
                supported_operations=frozenset(
                    server_hello["supported_operations"]
                ),
                timeout_seconds=timeout_seconds,
            )
        except Exception:
            if tls_socket is not None:
                cls._close_socket(tls_socket)
            elif raw_socket is not None:
                cls._close_socket(raw_socket)
            raise TlsAndroidLocalNodeChannelError(
                "android_local_node_tls_handshake_failed"
            ) from None

    def exchange(self, request: AndroidLocalNodeRequest) -> AndroidLocalNodeResponse:
        with self._lock:
            if self._closed:
                raise TlsAndroidLocalNodeChannelError(
                    "android_local_node_tls_channel_closed"
                )
            try:
                application_line = request.wire_copy()
                application_request = parse_strict_json_line(
                    application_line,
                    maximum=65_536,
                )
                self._sequence += 1
                request_line = build_request_frame(
                    application_line,
                    self._session_key,
                    session_id=self._session_id,
                    sequence=self._sequence,
                    nonce=self._new_nonce(),
                )
                request_frame = parse_strict_json_line(
                    request_line,
                    maximum=MAX_CHANNEL_LINE_BYTES,
                )
                self._send_line(self._socket, request_line)
                response_line = self._receive_line(self._socket)
                _, application_response, _ = parse_response_frame(
                    response_line,
                    self._session_key,
                    request_frame=request_frame,
                    application_request=application_request,
                    seen_nonces=self._response_nonces,
                )
                return AndroidLocalNodeResponse(application_response)
            except Exception:
                self._close_locked()
                raise TlsAndroidLocalNodeChannelError(
                    "android_local_node_tls_exchange_failed"
                ) from None

    def close(self) -> None:
        with self._lock:
            self._close_locked()

    def _close_locked(self) -> None:
        if self._closed:
            return
        self._closed = True
        self._session_key[:] = b"\0" * len(self._session_key)
        self._response_nonces.clear()
        self._close_socket(self._socket)

    @staticmethod
    def _new_nonce() -> str:
        return "nonce_" + secrets.token_hex(32)

    @staticmethod
    def _send_line(stream: socket.socket, line: bytes) -> None:
        if not line or b"\n" in line or len(line) > MAX_CHANNEL_LINE_BYTES:
            raise ChannelViolation("INVALID_CHANNEL_MESSAGE")
        stream.sendall(line + b"\n")

    @staticmethod
    def _receive_line(stream: socket.socket) -> bytes:
        value = bytearray()
        while len(value) <= MAX_CHANNEL_LINE_BYTES:
            chunk = stream.recv(min(16_384, MAX_CHANNEL_LINE_BYTES + 1 - len(value)))
            if not chunk:
                raise TlsAndroidLocalNodeChannelError(
                    "android_local_node_tls_peer_closed"
                )
            newline = chunk.find(b"\n")
            if newline >= 0:
                if newline != len(chunk) - 1:
                    raise ChannelViolation("INVALID_CHANNEL_MESSAGE")
                value.extend(chunk[:newline])
                if not value:
                    raise ChannelViolation("INVALID_CHANNEL_MESSAGE")
                return bytes(value)
            value.extend(chunk)
        raise ChannelViolation("INVALID_CHANNEL_MESSAGE")

    @staticmethod
    def _close_socket(stream: socket.socket) -> None:
        try:
            stream.shutdown(socket.SHUT_RDWR)
        except Exception:
            pass
        try:
            stream.close()
        except Exception:
            pass

    def __repr__(self) -> str:
        state = "closed" if self._closed else "authenticated"
        return (
            "TlsAndroidLocalNodeChannel(endpoint=<redacted>, device=<redacted>, "
            f"session=<redacted>, state={state})"
        )


@dataclass(frozen=True, repr=False)
class TlsAndroidLocalNodeChannelFactory:
    pairing: AndroidLocalNodePairingMaterial
    credential_resolver: AndroidLocalNodeCredentialResolver
    timeout_seconds: float = MAX_CHANNEL_SECONDS

    def __call__(
        self,
        config: AndroidLocalNodeChannelConfig,
    ) -> TlsAndroidLocalNodeChannel:
        if (
            config.endpoint_ref != self.pairing.endpoint_ref
            or config.credential_ref != self.pairing.credential_ref
            or config.expected_device_id != self.pairing.expected_device_id
            or config.session_binding_ref != self.pairing.session_binding_ref
        ):
            raise TlsAndroidLocalNodeChannelError(
                "android_local_node_pairing_binding_failed"
            )
        secret = self.credential_resolver.resolve(config.credential_ref)
        try:
            return TlsAndroidLocalNodeChannel.connect(
                self.pairing,
                secret,
                timeout_seconds=self.timeout_seconds,
            )
        finally:
            secret[:] = b"\0" * len(secret)

    def __repr__(self) -> str:
        return (
            "TlsAndroidLocalNodeChannelFactory(pairing=<redacted>, "
            "credential_resolver=<redacted>)"
        )
