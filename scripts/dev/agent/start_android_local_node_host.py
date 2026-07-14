# Purpose: Start the Ameme MCP Host with a paired outbound TLS Android Local Node channel.
# Input: Ignored local pairing JSON plus a credential-ref:env/NAME secret reference.
# Output: MCP JSON-RPC on stdout; no credential, memory content, endpoint, or pin diagnostics.
from __future__ import annotations

import argparse
from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[3]
SERVICE_ROOT = ROOT / "services" / "ameme-mcp-mock"
PROTOCOL_ROOT = ROOT / "packages" / "agent-local-node-protocol"
for path in (SERVICE_ROOT, PROTOCOL_ROOT):
    if str(path) not in sys.path:
        sys.path.insert(0, str(path))

import server  # noqa: E402
from ameme_mcp_mock.tls_android_local_node_channel import (  # noqa: E402
    AndroidLocalNodePairingMaterial,
    EnvironmentCredentialResolver,
    TlsAndroidLocalNodeChannelError,
    TlsAndroidLocalNodeChannelFactory,
)


def _arguments(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Start the paired outbound TLS Android Local Node MCP Host"
    )
    parser.add_argument(
        "--pairing-file",
        type=Path,
        required=True,
        help="Ignored local JSON containing references, device binding, endpoint, and TLS pin.",
    )
    parser.add_argument(
        "--data-dir",
        type=Path,
        default=Path(".tmp/ameme-mcp-android"),
        help="Ignored local MCP control-state directory.",
    )
    parser.add_argument(
        "--offline",
        action="store_true",
        help="Keep normal MCP delivery-state semantics offline; does not weaken TLS.",
    )
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    arguments = _arguments(argv)
    try:
        pairing = AndroidLocalNodePairingMaterial.load(arguments.pairing_file)
    except TlsAndroidLocalNodeChannelError:
        raise SystemExit("android-local-node pairing material is invalid") from None
    factory = TlsAndroidLocalNodeChannelFactory(
        pairing,
        EnvironmentCredentialResolver(),
    )
    server_arguments = [
        "--data-dir",
        str(arguments.data_dir),
        "--store-backend",
        "android-local-node",
        "--android-endpoint-ref",
        pairing.endpoint_ref,
        "--android-credential-ref",
        pairing.credential_ref,
        "--android-expected-device-id",
        pairing.expected_device_id,
        "--android-session-binding-ref",
        pairing.session_binding_ref,
    ]
    if arguments.offline:
        server_arguments.append("--offline")
    return server.main(
        server_arguments,
        android_channel_factory=factory,
    )


if __name__ == "__main__":
    raise SystemExit(main())
