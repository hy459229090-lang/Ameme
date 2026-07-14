from __future__ import annotations

import importlib.util
from pathlib import Path
import sys
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[3]
SERVICE_ROOT = ROOT / "services" / "ameme-mcp-mock"
PROTOCOL_ROOT = ROOT / "packages" / "agent-local-node-protocol"
for path in (SERVICE_ROOT, PROTOCOL_ROOT):
    if str(path) not in sys.path:
        sys.path.insert(0, str(path))

from ameme_agent_local_node_protocol import (  # noqa: E402
    CHANNEL_PROTOCOL_VERSION,
    canonical_json_bytes,
)


SCRIPT = ROOT / "scripts" / "dev" / "agent" / "start_android_local_node_host.py"
SPEC = importlib.util.spec_from_file_location("start_android_local_node_host", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
host_command = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(host_command)


class AndroidLocalNodeHostCommandTests(unittest.TestCase):
    def test_pairing_file_is_required_and_no_network_default_exists(self) -> None:
        with self.assertRaises(SystemExit) as raised:
            host_command._arguments([])
        self.assertEqual(2, raised.exception.code)

    def test_command_injects_references_and_factory_without_secret_arguments(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            pairing_path = root / "pairing.json"
            pairing_path.write_bytes(
                canonical_json_bytes(
                    {
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
                )
            )
            with patch.object(host_command.server, "main", return_value=0) as invoked:
                result = host_command.main(
                    [
                        "--pairing-file",
                        str(pairing_path),
                        "--data-dir",
                        str(root / "control"),
                    ]
                )
            self.assertEqual(0, result)
            arguments, = invoked.call_args.args
            rendered = " ".join(arguments)
            self.assertIn("android-local-node", rendered)
            self.assertIn("credential-ref:env/SYNTHETIC_SECRET", rendered)
            self.assertNotIn("synthetic-pairing-secret", rendered)
            factory = invoked.call_args.kwargs["android_channel_factory"]
            self.assertNotIn("127.0.0.1", repr(factory))
            self.assertNotIn("SYNTHETIC_SECRET", repr(factory))


if __name__ == "__main__":
    unittest.main()
