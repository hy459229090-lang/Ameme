# Purpose: validate the cross-platform short-lived QR pairing contract and ordinary-user entry points.
# Input: Swift/Kotlin envelope implementations, golden tests, iOS scanner inputs, and Android QR UI inputs.
# Output: a static JSON verdict; this does not claim camera, device, or network execution.

from __future__ import annotations

import base64
import json
from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[2]
SWIFT_ENVELOPE = ROOT / "apps/ios/Ameme/Shared/AgentPairingEnvelope.swift"
SWIFT_CONNECTOR = ROOT / "apps/ios/Ameme/Shared/AgentExperienceConnector.swift"
SWIFT_APP = ROOT / "apps/ios/AmemeApp/AmemeApp.swift"
SWIFT_SMOKE = ROOT / "apps/ios/Smoke/main.swift"
SWIFT_LOCAL_NODE_SMOKE = ROOT / "apps/ios/LocalNodeSmoke/main.swift"
IOS_INFO = ROOT / "apps/ios/Ameme/Info.plist"
KOTLIN_ENVELOPE = (
    ROOT
    / "apps/android/app/src/main/java/com/ameme/android/data/transport/channel/AgentPairingEnvelope.kt"
)
KOTLIN_MANAGER = (
    ROOT / "apps/android/app/src/main/java/com/ameme/android/data/transport/AgentPairingManager.kt"
)
KOTLIN_QR_UI = (
    ROOT / "apps/android/app/src/main/java/com/ameme/android/ui/components/PairingQrCode.kt"
)
KOTLIN_SETTINGS = (
    ROOT / "apps/android/app/src/main/java/com/ameme/android/ui/screens/SettingsScreen.kt"
)
KOTLIN_TEST = (
    ROOT
    / "apps/android/app/src/test/java/com/ameme/android/data/transport/channel/AgentPairingEnvelopeTest.kt"
)
RELEASE_PROVIDER = (
    ROOT
    / "apps/android/app/src/release/java/com/ameme/android/data/transport/PairingExperienceConnectorProvider.kt"
)


def require(condition: bool, message: str, checks: list[str]) -> None:
    if not condition:
        raise AssertionError(message)
    checks.append(message)


def read(path: Path) -> str:
    if not path.is_file():
        raise AssertionError(f"missing required file: {path.relative_to(ROOT)}")
    return path.read_text(encoding="utf-8")


def extract(pattern: str, source: str, description: str) -> str:
    match = re.search(pattern, source)
    if match is None:
        raise AssertionError(f"missing {description}")
    return match.group(1)


def main() -> int:
    checks: list[str] = []
    swift_envelope = read(SWIFT_ENVELOPE)
    swift_connector = read(SWIFT_CONNECTOR)
    swift_app = read(SWIFT_APP)
    swift_smoke = read(SWIFT_SMOKE)
    swift_local_node_smoke = read(SWIFT_LOCAL_NODE_SMOKE)
    ios_info = read(IOS_INFO)
    kotlin_envelope = read(KOTLIN_ENVELOPE)
    kotlin_manager = read(KOTLIN_MANAGER)
    kotlin_qr_ui = read(KOTLIN_QR_UI)
    kotlin_settings = read(KOTLIN_SETTINGS)
    kotlin_test = read(KOTLIN_TEST)
    release_provider = read(RELEASE_PROVIDER)

    for source, description in (
        (swift_envelope, "Swift envelope"),
        (kotlin_envelope, "Kotlin envelope"),
    ):
        require("ameme-pairing-v1:" in source, f"{description} freezes the v1 prefix", checks)
        require("pairing_expires_at_ms" in source, f"{description} carries host pairing expiry", checks)
        require("16_384" in source, f"{description} bounds payload characters", checks)
        require("32" in source, f"{description} requires a 32-byte secret", checks)

    require("10 * 60" in swift_envelope, "Swift accepts at most a ten-minute QR window", checks)
    require(
        "Duration.ofMinutes(10)" in kotlin_envelope,
        "Kotlin accepts at most a ten-minute QR window",
        checks,
    )
    require(
        "Duration.ofMinutes(5)" in kotlin_manager,
        "Android exports a five-minute QR window",
        checks,
    )
    require(
        "canonicalData == jsonData" in swift_envelope,
        "Swift rejects non-canonical envelope bytes",
        checks,
    )
    require(
        "canonicalBytes(root).contentEquals(json)" in kotlin_envelope,
        "Kotlin rejects non-canonical envelope bytes",
        checks,
    )

    swift_golden = extract(r'let envelopeGolden = "([^"]+)"', swift_smoke, "Swift QR golden")
    kotlin_golden = extract(r'val golden = "([^"]+)"', kotlin_test, "Kotlin QR golden")
    require(swift_golden == kotlin_golden, "Swift and Kotlin use the same byte-exact QR golden", checks)
    prefix = "ameme-pairing-v1:"
    require(swift_golden.startswith(prefix), "Golden payload uses the v1 prefix", checks)
    encoded = swift_golden[len(prefix):]
    encoded += "=" * ((4 - len(encoded) % 4) % 4)
    document = json.loads(base64.urlsafe_b64decode(encoded).decode("utf-8"))
    require(
        set(document) ==
        {"envelope_version", "expires_at_ms", "pairing", "pairing_expires_at_ms", "secret"},
        "Golden envelope has only the frozen top-level fields",
        checks,
    )
    require(
        int(document["expires_at_ms"]) - 1_800_000_000_000 == 300_000,
        "Golden QR exchange lifetime is five minutes",
        checks,
    )
    require(
        int(document["pairing_expires_at_ms"]) - 1_800_000_000_000 == 30 * 24 * 60 * 60 * 1_000,
        "Golden host pairing lifetime remains thirty days",
        checks,
    )
    secret = document["secret"] + "=" * ((4 - len(document["secret"]) % 4) % 4)
    require(len(base64.urlsafe_b64decode(secret)) == 32, "Golden carries exactly 32 secret bytes", checks)

    require(
        "resolve(pairingPayload: String)" in swift_connector,
        "iOS connector has an explicit QR payload resolution boundary",
        checks,
    )
    require(
        "AgentLocalNodeNetworkClient" in swift_connector and "AgentAccessGrantPolicy.default" in swift_connector,
        "iOS QR connection binds the authenticated Local Node client to a Grant",
        checks,
    )
    require(
        "var channelSecret: Data" in swift_envelope
        and "Data(Self.encodeBase64URL(secret).utf8)" in swift_envelope,
        "iOS derives channel HMAC bytes from the QR base64url secret text",
        checks,
    )
    require(
        "secret: envelope.channelSecret" in swift_connector,
        "iOS production QR connector uses the cross-platform channel HMAC representation",
        checks,
    )
    require(
        "secret: envelope.channelSecret" in swift_local_node_smoke,
        "iOS-to-Android smoke uses the production channel HMAC representation",
        checks,
    )
    require("AgentQRCodeScannerSheet" in swift_app, "iOS exposes a camera QR scanner sheet", checks)
    require(
        'TextField("粘贴 ameme-pairing-v1 配对码"' in swift_app,
        "iOS exposes an accessible manual pairing-code fallback",
        checks,
    )
    require("NSCameraUsageDescription" in ios_info, "iOS declares a purpose-specific camera permission", checks)

    require("QRCodeWriter" in kotlin_qr_ui, "Android renders a standards-based QR image", checks)
    require(
        'contentDescription = "设备配对二维码，五分钟内有效"' in kotlin_qr_ui,
        "Android QR exposes bounded accessibility semantics",
        checks,
    )
    require(
        'testTag("copy-pairing-code-button")' in kotlin_settings,
        "Android exposes a TalkBack-compatible copy-code fallback",
        checks,
    )
    require(
        'testTag("pairing-qr-expired")' in kotlin_settings,
        "Android hides stale QR material after expiry",
        checks,
    )
    require(
        "PairingExperienceConnector? = null" in release_provider,
        "Android Release does not present unauthenticated discovery as a usable connection",
        checks,
    )

    print(json.dumps({"ok": True, "checks": len(checks), "errors": []}, indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, OSError, ValueError, json.JSONDecodeError) as error:
        print(json.dumps({"ok": False, "errors": [str(error)]}, indent=2))
        raise SystemExit(1)
