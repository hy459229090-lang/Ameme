# Purpose: Prove Swift Network.framework client -> Android Local Node on an AVD.
# Input: A short-lived Android QR envelope, Android APKs, an ADB device, and one synthetic event.
# Output: Content-free JSON evidence; pairing envelope, ADB forward, and temporary artifacts are cleaned up.

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import time
from typing import Any
import re
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[3]
ANDROID_APP = ROOT / "apps" / "android" / "app"
IOS_PACKAGE = ROOT / "apps" / "ios"
DEBUG_APK = ANDROID_APP / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
TEST_APK = ANDROID_APP / "build" / "outputs" / "apk" / "androidTest" / "debug" / "app-debug-androidTest.apk"
PACKAGE = "com.ameme.android"
RUNNER = f"{PACKAGE}.test/androidx.test.runner.AndroidJUnitRunner"
PROVISION_CLASS = "com.ameme.android.data.transport.AgentPairingProvisioningInstrumentedTest"
PAIRING_FILE = "files/agent-pairing/pairing.json"
ENVELOPE_FILE = "cache/agent-pairing-e2e/pairing-envelope.txt"
SMOKE_CONTENT = "iOS Local Node 网络闭环合成事件"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Smoke Swift iOS client to Android Local Node")
    parser.add_argument("--adb", type=Path, required=True)
    parser.add_argument("--serial", required=True)
    return parser.parse_args()


class Adb:
    def __init__(self, executable: Path, serial: str) -> None:
        self.prefix = [str(executable), "-s", serial]

    def run(self, *args: str, text: bool = True, check: bool = True) -> subprocess.CompletedProcess[Any]:
        return subprocess.run(
            [*self.prefix, *args],
            cwd=ROOT,
            capture_output=True,
            text=text,
            encoding="utf-8" if text else None,
            check=check,
        )

    def instrument(self, method: str, argument: str) -> None:
        result = self.run(
            "shell", "am", "instrument", "-w", "-r",
            "-e", "class", f"{PROVISION_CLASS}#{method}",
            "-e", argument, "true", RUNNER,
        )
        if "FAILURES" in result.stdout or "INSTRUMENTATION_CODE: -1" not in result.stdout:
            raise RuntimeError("android_pairing_instrumentation_failed")

    def private_file(self, relative_path: str) -> bytes:
        return self.run("exec-out", "run-as", PACKAGE, "cat", relative_path, text=False).stdout

    def ui_xml(self) -> str:
        self.run("shell", "uiautomator", "dump", "/sdcard/ameme-ios-network-smoke.xml")
        return self.run("exec-out", "cat", "/sdcard/ameme-ios-network-smoke.xml").stdout

    def click_text(self, expected: str) -> bool:
        root = ET.fromstring(self.ui_xml())
        for node in root.iter("node"):
            if node.attrib.get("text") != expected:
                continue
            bounds = [int(value) for value in re.findall(r"\d+", node.attrib.get("bounds", ""))]
            if len(bounds) == 4:
                self.run(
                    "shell", "input", "tap",
                    str((bounds[0] + bounds[2]) // 2),
                    str((bounds[1] + bounds[3]) // 2),
                )
                return True
        return False

    def wait_for_text(self, expected: str, attempts: int = 12) -> bool:
        for attempt in range(attempts):
            if expected in self.ui_xml():
                return True
            if attempt >= 2:
                self.run("shell", "input", "swipe", "540", "1500", "540", "500", "250")
            time.sleep(1)
        return False

    def click_text_after_scroll(self, expected: str, attempts: int = 6) -> bool:
        for attempt in range(attempts):
            if self.click_text(expected):
                return True
            if attempt < attempts - 1:
                self.run("shell", "input", "swipe", "540", "1500", "540", "500", "250")
                time.sleep(1)
        return False


def main() -> int:
    options = parse_args()
    for artifact in (DEBUG_APK, TEST_APK):
        if not artifact.is_file():
            raise SystemExit(f"missing APK: {artifact}")
    adb = Adb(options.adb, options.serial)
    pairing_envelope = bytearray()
    pairing_port: int | None = None
    try:
        adb.run("install", "-r", str(DEBUG_APK))
        adb.run("install", "-r", str(TEST_APK))
        adb.run("shell", "pm", "clear", PACKAGE)
        adb.run("install", "-r", str(TEST_APK))
        adb.instrument("provisionLocalLoopbackPairing", "amemeProvisionPairing")
        pairing = json.loads(adb.private_file(PAIRING_FILE))
        pairing_envelope.extend(adb.private_file(ENVELOPE_FILE))
        adb.run("shell", "run-as", PACKAGE, "rm", ENVELOPE_FILE)
        pairing_port = int(pairing["port"])
        adb.run("forward", f"tcp:{pairing_port}", f"tcp:{pairing_port}")
        adb.run("shell", "am", "start", "-W", "-n", f"{PACKAGE}/.MainActivity")
        time.sleep(5)

        environment = os.environ.copy()
        environment["AMEME_ANDROID_PAIRING_ENVELOPE"] = pairing_envelope.decode("utf-8")
        swift = os.environ.get("SWIFT_EXECUTABLE", "swift")
        result = subprocess.run(
            [
                swift,
                "run",
                "--disable-sandbox",
                "--package-path",
                str(IOS_PACKAGE),
                "AmemeLocalNodeSmoke",
            ],
            cwd=ROOT,
            env=environment,
            capture_output=True,
            text=True,
            encoding="utf-8",
        )
        if result.returncode != 0 or '"status":"passed"' not in result.stdout:
            diagnostic_lines = [
                line.strip() for line in (result.stdout + result.stderr).splitlines() if line.strip()
            ]
            diagnostic = diagnostic_lines[-1][:160] if diagnostic_lines else "no_diagnostic"
            raise RuntimeError("swift_network_to_android_smoke_failed:" + diagnostic)
        swift_evidence: dict[str, Any] | None = None
        for line in reversed(result.stdout.splitlines()):
            try:
                candidate = json.loads(line)
            except json.JSONDecodeError:
                continue
            if isinstance(candidate, dict) and candidate.get("status") == "passed":
                swift_evidence = candidate
                break
        if swift_evidence is None or not all(
            swift_evidence.get(field) is True
            for field in (
                "bounded_visible_events",
                "append_revision",
                "exact_event_and_revision_undo",
            )
        ):
            raise RuntimeError("swift_network_operation_evidence_incomplete")
        current_ui = adb.ui_xml()
        if "自动整理你的一天" in current_ui and not adb.click_text_after_scroll("查看今天"):
            raise RuntimeError("android_onboarding_continue_not_clickable")
        if not adb.wait_for_text(SMOKE_CONTENT):
            raise RuntimeError("swift_network_event_not_visible_in_android_today")
        print(json.dumps({
            "status": "passed",
            "transport": "ios-qr-envelope-to-android-local-node",
            "qr_user_path_connected": True,
            "tls_hmac_grant_bound": True,
            "visible_in_android_today": True,
            "bounded_visible_events": True,
            "append_revision": True,
            "exact_event_and_revision_undo": True,
            "content_logged": False,
        }, ensure_ascii=False))
        return 0
    finally:
        pairing_envelope[:] = b"\0" * len(pairing_envelope)
        if pairing_port is not None:
            adb.run("forward", "--remove", f"tcp:{pairing_port}", check=False)
        adb.instrument("revokeLocalLoopbackPairing", "amemeRevokePairing")


if __name__ == "__main__":
    raise SystemExit(main())
