from __future__ import annotations

from pathlib import Path
import sys
from tempfile import TemporaryDirectory
import unittest


SERVICE_ROOT = Path(__file__).resolve().parents[1]
if str(SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVICE_ROOT))

import server  # noqa: E402
from ameme_mcp_mock import JsonStore  # noqa: E402
from ameme_mcp_mock.android_local_node_store import (  # noqa: E402
    AndroidLocalNodeSessionBinding,
)


class NoopChannel:
    binding = AndroidLocalNodeSessionBinding(
        device_id="device_synthetic_001",
        session_binding_ref="session-binding-ref:synthetic-session",
    )
    supported_operations = frozenset({"create_event"})

    def exchange(self, request):
        raise AssertionError("exchange not expected")

    def close(self) -> None:
        pass


class ServerAndroidBackendTest(unittest.TestCase):
    def android_arguments(self, root: Path, *extra: str):
        return server._arguments(
            [
                "--data-dir",
                str(root),
                "--store-backend",
                "android-local-node",
                "--android-endpoint-ref",
                "endpoint-ref:synthetic-android",
                "--android-credential-ref",
                "credential-ref:synthetic-credential",
                "--android-expected-device-id",
                "device_synthetic_001",
                "--android-session-binding-ref",
                "session-binding-ref:synthetic-session",
                *extra,
            ]
        )

    def test_json_remains_default_backend(self) -> None:
        with TemporaryDirectory() as directory:
            arguments = server._arguments(["--data-dir", directory])
            store = server._build_store(arguments)
            self.assertIsInstance(store, JsonStore)
            store.close()

    def test_android_backend_requires_every_reference_and_provider(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            arguments = self.android_arguments(root)
            with self.assertRaisesRegex(
                SystemExit,
                "injected authenticated channel provider",
            ):
                server._build_store(arguments)

            for option, attribute in (
                ("--android-endpoint-ref", "android_endpoint_ref"),
                ("--android-credential-ref", "android_credential_ref"),
                ("--android-expected-device-id", "android_expected_device_id"),
                ("--android-session-binding-ref", "android_session_binding_ref"),
            ):
                with self.subTest(option=option):
                    arguments = self.android_arguments(root)
                    setattr(arguments, attribute, None)
                    with self.assertRaisesRegex(SystemExit, option):
                        server._build_store(arguments, android_channel_factory=lambda _: NoopChannel())

    def test_factory_receives_refs_without_persisting_or_logging_values(self) -> None:
        with TemporaryDirectory() as directory:
            root = Path(directory)
            observed = []

            def factory(config):
                observed.append(config)
                return NoopChannel()

            store = server._build_store(
                self.android_arguments(root),
                android_channel_factory=factory,
            )
            self.assertEqual(1, len(observed))
            diagnostic = repr(observed[0])
            for value in (
                "synthetic-android",
                "synthetic-credential",
                "device_synthetic_001",
                "synthetic-session",
            ):
                self.assertNotIn(value, diagnostic)
            store.close()
            persisted = (root / "mcp-control.json").read_text(encoding="utf-8")
            self.assertNotIn("synthetic-credential", persisted)
            self.assertNotIn("synthetic-session", persisted)

    def test_seed_file_is_rejected_for_android_backend(self) -> None:
        with TemporaryDirectory() as directory:
            arguments = self.android_arguments(Path(directory))
            arguments.seed_file = Path("synthetic.json")
            with self.assertRaisesRegex(SystemExit, "only valid for the json"):
                server._build_store(
                    arguments,
                    android_channel_factory=lambda _: NoopChannel(),
                )


if __name__ == "__main__":
    unittest.main()
