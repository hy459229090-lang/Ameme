# Purpose: Prove MCP Host -> paired TLS/HMAC Android channel -> SQLCipher -> Today UI.
# Input: Debug/test APKs and a synthetic event only; no personal data or ADB Event injection.
# Output: Content-free JSON evidence; pairing secret is removed from device and process state.
from __future__ import annotations

import argparse
import base64
from datetime import datetime, timedelta
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
from typing import Any
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[3]
ANDROID_APP = ROOT / "apps" / "android" / "app"
DEBUG_APK = ANDROID_APP / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
TEST_APK = ANDROID_APP / "build" / "outputs" / "apk" / "androidTest" / "debug" / "app-debug-androidTest.apk"
HOST = ROOT / "scripts" / "dev" / "agent" / "start_android_local_node_host.py"
PACKAGE = "com.ameme.android"
RUNNER = f"{PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner"
PROVISION_CLASS = (
    "com.ameme.android.data.transport.AgentPairingProvisioningInstrumentedTest"
)
PAIRING_ENVELOPE_PREFIX = "ameme-pairing-v1:"
PAIRING_ENVELOPE_FILE = "cache/agent-pairing-e2e/pairing-envelope.txt"
SYNTHETIC_UI_TITLE = "合成 Agent 通道事件：配对传输已完成"


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Smoke the paired Android Agent channel")
    parser.add_argument("--adb", type=Path, required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--reset-app-data", action="store_true")
    return parser.parse_args()


class Adb:
    def __init__(self, executable: Path, serial: str) -> None:
        self.prefix = [str(executable), "-s", serial]

    def run(self, *values: str, text: bool = True, check: bool = True) -> subprocess.CompletedProcess[Any]:
        return subprocess.run(
            [*self.prefix, *values],
            cwd=ROOT,
            check=check,
            capture_output=True,
            text=text,
            encoding="utf-8" if text else None,
        )

    def instrument(self, method: str, argument: str) -> None:
        result = self.run(
            "shell",
            "am",
            "instrument",
            "-w",
            "-r",
            "-e",
            "class",
            f"{PROVISION_CLASS}#{method}",
            "-e",
            argument,
            "true",
            RUNNER,
        )
        if "FAILURES" in result.stdout or "INSTRUMENTATION_CODE: -1" not in result.stdout:
            raise RuntimeError("android_pairing_instrumentation_failed")

    def private_file(self, relative_path: str) -> bytes:
        return self.run(
            "exec-out",
            "run-as",
            PACKAGE,
            "cat",
            relative_path,
            text=False,
        ).stdout


class McpClient:
    def __init__(self, pairing_file: Path, data_dir: Path, secret: str) -> None:
        environment = os.environ.copy()
        environment["AMEME_ANDROID_PAIRING_SECRET"] = secret
        self.next_id = 1
        self.process = subprocess.Popen(
            [
                sys.executable,
                str(HOST),
                "--pairing-file",
                str(pairing_file),
                "--data-dir",
                str(data_dir),
            ],
            cwd=ROOT,
            env=environment,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
        )

    def request(self, method: str, params: dict[str, Any]) -> dict[str, Any]:
        assert self.process.stdin is not None and self.process.stdout is not None
        request = {"jsonrpc": "2.0", "id": self.next_id, "method": method, "params": params}
        self.next_id += 1
        self.process.stdin.write(json.dumps(request, ensure_ascii=False) + "\n")
        self.process.stdin.flush()
        line = self.process.stdout.readline()
        if not line:
            assert self.process.stderr is not None
            diagnostic = self.process.stderr.read().strip()
            raise RuntimeError(
                "paired_android_mcp_host_stopped:" + (diagnostic or "no_diagnostic")
            )
        response = json.loads(line)
        if "error" in response:
            raise RuntimeError("paired_android_mcp_jsonrpc_error")
        return response["result"]

    def tool(self, name: str, tool_arguments: dict[str, Any]) -> dict[str, Any]:
        result = self.request("tools/call", {"name": name, "arguments": tool_arguments})
        if result.get("isError"):
            raise RuntimeError(str(result.get("structuredContent", {}).get("code", "tool_error")))
        return result["structuredContent"]

    def close(self) -> None:
        if self.process.stdin:
            self.process.stdin.close()
        try:
            self.process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self.process.terminate()
            self.process.wait(timeout=5)


def ui_xml(adb: Adb) -> str:
    adb.run("shell", "uiautomator", "dump", "/sdcard/ameme-agent-smoke.xml")
    return adb.run("exec-out", "cat", "/sdcard/ameme-agent-smoke.xml").stdout


def click_text(adb: Adb, expected: str) -> bool:
    root = ET.fromstring(ui_xml(adb))
    for node in root.iter("node"):
        if node.attrib.get("text") != expected:
            continue
        values = [int(value) for value in re.findall(r"\d+", node.attrib["bounds"])]
        if len(values) == 4:
            adb.run("shell", "input", "tap", str((values[0] + values[2]) // 2), str((values[1] + values[3]) // 2))
            return True
    return False


def channel_secret_from_envelope(payload: bytes) -> bytes:
    text = payload.decode("utf-8")
    if not text.startswith(PAIRING_ENVELOPE_PREFIX):
        raise RuntimeError("paired_android_envelope_invalid")
    encoded = text.removeprefix(PAIRING_ENVELOPE_PREFIX)
    padded = encoded + ("=" * (-len(encoded) % 4))
    envelope = json.loads(base64.urlsafe_b64decode(padded))
    if set(envelope) != {
        "envelope_version",
        "expires_at_ms",
        "pairing",
        "pairing_expires_at_ms",
        "secret",
    } or envelope.get("envelope_version") != 1:
        raise RuntimeError("paired_android_envelope_invalid")
    secret = envelope.get("secret")
    if not isinstance(secret, str):
        raise RuntimeError("paired_android_envelope_invalid")
    decoded = base64.urlsafe_b64decode(secret + ("=" * (-len(secret) % 4)))
    if len(decoded) != 32:
        raise RuntimeError("paired_android_envelope_invalid")
    return secret.encode("ascii")


def main() -> int:
    options = arguments()
    for artifact in (DEBUG_APK, TEST_APK):
        if not artifact.is_file():
            raise SystemExit(f"missing APK: {artifact}")
    adb = Adb(options.adb, options.serial)
    adb.run("install", "-r", str(DEBUG_APK))
    adb.run("install", "-r", str(TEST_APK))
    if options.reset_app_data:
        adb.run("shell", "pm", "clear", PACKAGE)
        adb.run("install", "-r", str(TEST_APK))

    client: McpClient | None = None
    secret_bytes = bytearray()
    captured: dict[str, Any] | None = None
    persisted_after_restart = False
    with tempfile.TemporaryDirectory(prefix="ameme-paired-android-") as temporary:
        temp = Path(temporary)
        try:
            adb.instrument("provisionLocalLoopbackPairing", "amemeProvisionPairing")
            pairing_bytes = adb.private_file("files/agent-pairing/pairing.json")
            secret_bytes.extend(channel_secret_from_envelope(adb.private_file(PAIRING_ENVELOPE_FILE)))
            adb.run("shell", "run-as", PACKAGE, "rm", PAIRING_ENVELOPE_FILE)
            pairing_file = temp / "pairing.json"
            pairing_file.write_bytes(pairing_bytes)
            pairing = json.loads(pairing_bytes)
            pairing_bytes = b""

            adb.run("forward", f"tcp:{pairing['port']}", f"tcp:{pairing['port']}")
            adb.run("shell", "am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity")
            time.sleep(5)

            client = McpClient(pairing_file, temp / "mcp-state", secret_bytes.decode("utf-8"))
            initialized = client.request(
                "initialize",
                {"protocolVersion": "2024-11-05", "capabilities": {}, "clientInfo": {"name": "ameme-android-smoke", "version": "0.1"}},
            )
            now = datetime.now().astimezone()
            pending = client.tool(
                "pair",
                {
                    "action": "begin",
                    "caller_id": "agent_android_smoke",
                    "purposes": ["autonomous_memory"],
                    "spaces": ["space_personal"],
                    "data_types": ["event"],
                    "expires_at": (now + timedelta(days=1)).isoformat(),
                },
            )
            ready = client.tool(
                "pair",
                {
                    "action": "approve",
                    "caller_id": "agent_android_smoke",
                    "challenge_id": pending["challenge_id"],
                    "confirmation": {"confirmed": True, "terms_digest": pending["terms_digest"]},
                },
            )
            captured = client.tool(
                "capture",
                {
                    "caller_id": "agent_android_smoke",
                    "grant_id": ready["grant_id"],
                    "purpose": "autonomous_memory",
                    "space": "space_personal",
                    "memory_type": "event",
                    "content": SYNTHETIC_UI_TITLE,
                    "evidence_kind": "direct_evidence",
                    "event_type": "activity",
                    "event_time": now.isoformat(),
                    "sensitivity": "personal",
                    "data_class": "structured",
                    "idempotency_key": f"android-smoke-{now.timestamp():.6f}",
                },
            )
            client.close()
            client = None
            time.sleep(2)

            if "查看今天" in ui_xml(adb):
                if not click_text(adb, "查看今天"):
                    raise RuntimeError("android_onboarding_continue_not_clickable")
                time.sleep(2)
            if SYNTHETIC_UI_TITLE not in ui_xml(adb):
                raise RuntimeError("paired_android_event_not_visible")

            adb.run("shell", "am", "force-stop", PACKAGE)
            adb.run("shell", "am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity")
            time.sleep(4)
            persisted_after_restart = SYNTHETIC_UI_TITLE in ui_xml(adb)
            if not persisted_after_restart:
                raise RuntimeError("paired_android_event_not_persistent")

            print(
                json.dumps(
                    {
                        "status": "passed",
                        "protocol": initialized["protocolVersion"],
                        "channel_protocol": pairing["channel_protocol"],
                        "capture_persistence_state": captured.get("persistence_state"),
                        "capture_delivery_state": captured.get("delivery_state"),
                        "visible_in_today": True,
                        "persistent_after_restart": persisted_after_restart,
                        "adb_used_for_event_injection": False,
                    },
                    ensure_ascii=False,
                    indent=2,
                )
            )
            return 0
        finally:
            if client is not None:
                client.close()
            secret_bytes[:] = b"\0" * len(secret_bytes)
            adb.run("forward", "--remove", "tcp:43821", check=False)
            adb.instrument("revokeLocalLoopbackPairing", "amemeRevokePairing")


if __name__ == "__main__":
    raise SystemExit(main())
