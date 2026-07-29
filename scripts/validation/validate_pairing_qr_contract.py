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
SWIFT_BOOTSTRAP = ROOT / "apps/ios/Ameme/Shared/AgentPairingBootstrapV2.swift"
SWIFT_BOOTSTRAP_NETWORK = (
    ROOT / "apps/ios/Ameme/Shared/AgentPairingBootstrapNetworkClient.swift"
)
SWIFT_CREDENTIAL_STORE = (
    ROOT / "apps/ios/Ameme/Shared/AgentPairingCredentialStore.swift"
)
SWIFT_CONNECTOR = ROOT / "apps/ios/Ameme/Shared/AgentExperienceConnector.swift"
SWIFT_APP = ROOT / "apps/ios/AmemeApp/AmemeApp.swift"
SWIFT_SMOKE = ROOT / "apps/ios/Smoke/main.swift"
SWIFT_LOCAL_NODE_SMOKE = ROOT / "apps/ios/LocalNodeSmoke/main.swift"
IOS_INFO = ROOT / "apps/ios/Ameme/Info.plist"
KOTLIN_ENVELOPE = (
    ROOT
    / "apps/android/app/src/main/java/com/ameme/android/data/transport/channel/AgentPairingEnvelope.kt"
)
KOTLIN_BOOTSTRAP = (
    ROOT
    / "apps/android/app/src/main/java/com/ameme/android/data/transport/channel/AgentPairingBootstrapV2.kt"
)
KOTLIN_MANAGER = (
    ROOT / "apps/android/app/src/main/java/com/ameme/android/data/transport/AgentPairingManager.kt"
)
KOTLIN_LISTENER = (
    ROOT
    / "apps/android/app/src/main/java/com/ameme/android/data/transport/channel/SingleConnectionTlsLocalNodeListener.kt"
)
KOTLIN_RUNTIME = (
    ROOT / "apps/android/app/src/main/java/com/ameme/android/data/transport/AgentLocalNodeRuntime.kt"
)
KOTLIN_QR_UI = (
    ROOT / "apps/android/app/src/main/java/com/ameme/android/ui/components/PairingQrCode.kt"
)
KOTLIN_SETTINGS = (
    ROOT / "apps/android/app/src/main/java/com/ameme/android/ui/screens/SettingsScreen.kt"
)
KOTLIN_MAIN_ACTIVITY = ROOT / "apps/android/app/src/main/java/com/ameme/android/MainActivity.kt"
KOTLIN_OUTBOUND_CONNECTOR = (
    ROOT
    / "apps/android/app/src/main/java/com/ameme/android/data/transport/ProductionPairingExperienceConnector.kt"
)
KOTLIN_OUTBOUND_NETWORK = (
    ROOT
    / "apps/android/app/src/main/java/com/ameme/android/data/transport/channel/AndroidPairingNetworkClient.kt"
)
KOTLIN_OUTBOUND_STORE = (
    ROOT
    / "apps/android/app/src/main/java/com/ameme/android/data/transport/AndroidPairingCredentialStore.kt"
)
ANDROID_MANIFEST = ROOT / "apps/android/app/src/main/AndroidManifest.xml"
ANDROID_VERSION_CATALOG = ROOT / "apps/android/gradle/libs.versions.toml"
ANDROID_OUTBOUND_STORE_TEST = (
    ROOT
    / "apps/android/app/src/androidTest/java/com/ameme/android/data/transport/AndroidPairingCredentialStoreInstrumentedTest.kt"
)
KOTLIN_TEST = (
    ROOT
    / "apps/android/app/src/test/java/com/ameme/android/data/transport/channel/AgentPairingEnvelopeTest.kt"
)
KOTLIN_MANAGER_TEST = (
    ROOT
    / "apps/android/app/src/androidTest/java/com/ameme/android/data/transport/AgentPairingManagerInstrumentedTest.kt"
)
SWIFT_BOOTSTRAP_TEST = (
    ROOT / "apps/ios/Tests/AmemeSharedTests/AgentPairingBootstrapTests.swift"
)
IOS_PROJECT = ROOT / "apps/ios/Ameme.xcodeproj/project.pbxproj"
IOS_NETWORK_SMOKE_RUNNER = ROOT / "scripts/dev/agent/smoke_ios_network_to_android.py"
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
    swift_bootstrap = read(SWIFT_BOOTSTRAP)
    swift_bootstrap_network = read(SWIFT_BOOTSTRAP_NETWORK)
    swift_credential_store = read(SWIFT_CREDENTIAL_STORE)
    swift_connector = read(SWIFT_CONNECTOR)
    swift_app = read(SWIFT_APP)
    swift_smoke = read(SWIFT_SMOKE)
    swift_local_node_smoke = read(SWIFT_LOCAL_NODE_SMOKE)
    ios_info = read(IOS_INFO)
    kotlin_envelope = read(KOTLIN_ENVELOPE)
    kotlin_bootstrap = read(KOTLIN_BOOTSTRAP)
    kotlin_manager = read(KOTLIN_MANAGER)
    kotlin_runtime = read(KOTLIN_RUNTIME)
    kotlin_listener = read(KOTLIN_LISTENER)
    kotlin_qr_ui = read(KOTLIN_QR_UI)
    kotlin_settings = read(KOTLIN_SETTINGS)
    kotlin_main_activity = read(KOTLIN_MAIN_ACTIVITY)
    kotlin_outbound_connector = read(KOTLIN_OUTBOUND_CONNECTOR)
    kotlin_outbound_network = read(KOTLIN_OUTBOUND_NETWORK)
    kotlin_outbound_store = read(KOTLIN_OUTBOUND_STORE)
    android_manifest = read(ANDROID_MANIFEST)
    android_version_catalog = read(ANDROID_VERSION_CATALOG)
    android_outbound_store_test = read(ANDROID_OUTBOUND_STORE_TEST)
    kotlin_test = read(KOTLIN_TEST)
    kotlin_manager_test = read(KOTLIN_MANAGER_TEST)
    swift_bootstrap_test = read(SWIFT_BOOTSTRAP_TEST)
    ios_project = read(IOS_PROJECT)
    ios_network_smoke_runner = read(IOS_NETWORK_SMOKE_RUNNER)
    release_provider = read(RELEASE_PROVIDER)

    for source, description in (
        (swift_envelope, "Swift envelope"),
        (kotlin_envelope, "Kotlin envelope"),
    ):
        require("ameme-pairing-v2:" in source, f"{description} freezes the v2 prefix", checks)
        require("pairing_expires_at_ms" in source, f"{description} carries host pairing expiry", checks)
        require("16_384" in source, f"{description} bounds payload characters", checks)
        require(
            "bootstrap_id" in source and "bootstrap_secret" in source,
            f"{description} carries only an identified bootstrap credential",
            checks,
        )

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
    prefix = "ameme-pairing-v2:"
    require(swift_golden.startswith(prefix), "Golden payload uses the v2 prefix", checks)
    encoded = swift_golden[len(prefix):]
    encoded += "=" * ((4 - len(encoded) % 4) % 4)
    document = json.loads(base64.urlsafe_b64decode(encoded).decode("utf-8"))
    require(
        set(document) ==
        {
            "bootstrap_id",
            "bootstrap_secret",
            "envelope_version",
            "expires_at_ms",
            "pairing",
            "pairing_expires_at_ms",
        },
        "Golden envelope has only the frozen top-level fields",
        checks,
    )
    require(document["envelope_version"] == 2, "Golden envelope freezes schema version two", checks)
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
    secret = document["bootstrap_secret"] + "=" * (
        (4 - len(document["bootstrap_secret"]) % 4) % 4
    )
    require(
        len(base64.urlsafe_b64decode(secret)) == 32,
        "Golden carries exactly 32 bootstrap-secret bytes",
        checks,
    )
    require(
        re.fullmatch(r"boot_[0-9a-f]{32}", document["bootstrap_id"]) is not None,
        "Golden carries a bounded bootstrap identifier",
        checks,
    )

    require(
        "resolve(pairingPayload: String)" in swift_connector,
        "iOS connector has an explicit QR payload resolution boundary",
        checks,
    )
    require(
        'protocolVersion = "ameme.agent-pairing-bootstrap.v2"' in swift_bootstrap
        and "possession_signature_b64" in swift_bootstrap
        and "P256.Signing.PublicKey" in swift_bootstrap,
        "iOS bootstrap verifies a signed P-256 client possession proof",
        checks,
    )
    require(
        ".TLSv13" in swift_bootstrap_network
        and "tlsCertificateSHA256" in swift_bootstrap_network
        and "certificateData" in swift_bootstrap_network,
        "iOS bootstrap transport requires TLS 1.3 and the QR certificate pin",
        checks,
    )
    require(
        "savePending(" in swift_connector
        and "bootstrapProvisioner.provision(" in swift_connector
        and "saveActive(" in swift_connector,
        "iOS persists same-key retry state before bootstrap and activates the issued credential",
        checks,
    )
    require(
        "secret: issued.channelSecret" in swift_connector
        and "AgentAccessGrantPolicy.default" in swift_connector,
        "iOS QR connection uses only the issued credential and binds it to a Grant",
        checks,
    )
    require(
        "kSecAttrAccessibleWhenUnlockedThisDeviceOnly" in swift_credential_store
        and "clientPrivateKeyRaw" in swift_credential_store,
        "iOS stores the issued credential and bound client key in device-only Keychain state",
        checks,
    )
    require(
        "public func restoreConnection()" in swift_connector
        and "credentialStore.loadActive" in swift_connector
        and "credentialStore.loadPending(now:" in swift_connector
        and "provisionPending(pending)" in swift_connector,
        "iOS reconnects from the persisted issued credential instead of the QR secret",
        checks,
    )
    require(
        "makeConnectedClientWithRetry" in swift_connector
        and "retryDelays" in swift_connector,
        "iOS bounds the post-bootstrap listener-restart transport race",
        checks,
    )
    require(
        "agentExperienceConnector.restoreConnection()" in swift_app,
        "iOS app startup verifies transport before restoring connected UI state",
        checks,
    )
    require(
        "agentExperienceConnector.clearLocalCredentials()" in swift_app,
        "iOS local-space deletion clears persisted pairing credentials",
        checks,
    )
    require(
        "AgentPairingBootstrapNetworkClient()" in swift_local_node_smoke
        and "secret: issuedCredential.channelSecret" in swift_local_node_smoke
        and "bootstrap_credential_separated" in swift_local_node_smoke,
        "iOS-to-Android network smoke provisions then uses a separate issued credential",
        checks,
    )
    require("AgentQRCodeScannerSheet" in swift_app, "iOS exposes a camera QR scanner sheet", checks)
    require(
        'TextField("粘贴 ameme-pairing-v2 配对码"' in swift_app,
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
        'testTag("create-pairing-qr-button")' in kotlin_settings
        and "让另一台 Ameme 设备连接" in kotlin_settings,
        "Android exposes QR generation in the ordinary receive-connection section",
        checks,
    )
    require(
        "ProductionPairingExperienceConnector(context)" in release_provider,
        "Android Release selects the authenticated production connector",
        checks,
    )
    require(
        "GmsBarcodeScanning.getClient" in kotlin_main_activity
        and "Barcode.FORMAT_QR_CODE" in kotlin_main_activity
        and "GoogleApiAvailability" in kotlin_main_activity
        and "payload.length > 16_384" in kotlin_main_activity,
        "Android wires a QR-only bounded system scanner with a visible Play-services failure",
        checks,
    )
    require(
        "play-services-code-scanner" in android_version_catalog
        and 'android:name="com.google.mlkit.vision.DEPENDENCIES"' in android_manifest
        and 'android:value="barcode_ui"' in android_manifest
        and "android.permission.CAMERA" not in android_manifest,
        "Android requests the on-demand system scanner module without app camera permission",
        checks,
    )
    require(
        "AndroidPairingBootstrapNetworkClient" in kotlin_outbound_connector
        and "AndroidLocalNodeNetworkClient" in kotlin_outbound_connector
        and "restoreConnection()" in kotlin_outbound_connector
        and "clearLocalCredentials()" in kotlin_outbound_connector
        and "OPERATION_CREATE_EVENT" in kotlin_outbound_connector,
        "Android publishes connection state only after QR bootstrap and application authentication",
        checks,
    )
    require(
        "TLSv1.3" in kotlin_outbound_network
        and "CertificatePinTrustManager" in kotlin_outbound_network
        and "tlsCertificateSha256" in kotlin_outbound_network
        and "verifyServerHello" in kotlin_outbound_network,
        "Android outbound transport requires TLS 1.3, certificate pinning, and server proof",
        checks,
    )
    require(
        "AliasPinnedServerKeyManager" in kotlin_manager
        and "TLS_KEY_ALIAS" in kotlin_manager
        and "chooseEngineServerAlias" in kotlin_manager,
        "Android Local Node TLS cannot select the unrelated client-possession key alias",
        checks,
    )
    require(
        "KeyProperties.KEY_ALGORITHM_EC" in kotlin_outbound_store
        and 'setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))' in kotlin_outbound_store
        and "noBackupFilesDir" in kotlin_outbound_store
        and "AtomicFile" in kotlin_outbound_store
        and "AES/GCM/NoPadding" in kotlin_outbound_store,
        "Android stores the same P-256 client key and encrypted retry state in device-local storage",
        checks,
    )
    require(
        "pendingAndActiveRoundTripUseNonExportableKeyAndEncryptedNoBackupRecord"
        in android_outbound_store_test
        and "corruptOrExpiredRecordFailsClosedAndIsRemoved" in android_outbound_store_test,
        "Android instrumentation source covers encrypted lifecycle, corruption, and expiry",
        checks,
    )

    require(
        'PROTOCOL = "ameme.agent-pairing-bootstrap.v2"' in kotlin_bootstrap
        and "SHA256withECDSA" in kotlin_bootstrap
        and "bootstrap-client-hello" in kotlin_bootstrap,
        "Android bootstrap verifies P-256 possession and the bootstrap HMAC",
        checks,
    )
    require(
        "developerChannelSecret" in kotlin_manager
        and "bootstrapSecret" in kotlin_manager
        and "qrCredentialAad" in kotlin_manager,
        "Android keeps developer, bootstrap, and issued credentials in separate domains",
        checks,
    )
    require(
        ".putString(KEY_BOOTSTRAP_STATE, BOOTSTRAP_STATE_CONSUMED)" in kotlin_manager
        and ".putString(KEY_QR_CREDENTIAL_ID, credentialId)" in kotlin_manager
        and ".commit()" in kotlin_manager,
        "Android atomically consumes bootstrap state and commits the issued credential",
        checks,
    )
    require(
        "KEY_BOOTSTRAP_CLIENT_KEY_THUMBPRINT" in kotlin_manager
        and "KEY_BOOTSTRAP_CLIENT_PUBLIC_KEY" in kotlin_manager
        and "pairing_bootstrap_already_consumed" in kotlin_manager,
        "Android permits bounded response retry only for the originally bound client key",
        checks,
    )
    require(
        "AgentPairingBootstrapV2Codec.isClientHello" in kotlin_listener
        and "bootstrapHandler" in kotlin_listener
        and "bootstrapProvisioned = true" in kotlin_listener,
        "Android listener routes bootstrap separately from the application channel",
        checks,
    )
    require(
        "pairingManager::loadActive" in kotlin_runtime
        and "pairingManager::provisionBootstrap" in kotlin_runtime,
        "Android runtime reloads credentials after bootstrap issuance",
        checks,
    )
    require(
        "it.kind == AgentPairingCredentialKind.DeviceBootstrapV2" in kotlin_runtime
        and "allowDeveloperCredential" in kotlin_runtime,
        "Android Release application sessions accept only issued QR credentials",
        checks,
    )
    require(
        "enableDeveloperCredential: Boolean = BuildConfig.DEBUG" in kotlin_manager
        and "developerChannelSecret: String?" in kotlin_manager,
        "Android Release persists no unbound developer bearer credential",
        checks,
    )
    require(
        "releaseModePersistsNoDeveloperBearer" in kotlin_manager_test
        and "bootstrap_isConsumedOnce" in kotlin_manager_test
        and "pairing_bootstrap_already_consumed" not in kotlin_manager_test,
        "Android instrumentation source covers release isolation, one-time use, retry, and attacker key rejection",
        checks,
    )
    require(
        "KEY_QR_CREDENTIAL_ID" in kotlin_manager
        and ".remove(KEY_QR_CREDENTIAL_SECRET_CIPHERTEXT)" in kotlin_manager,
        "Android explicit revoke removes bootstrap and issued credential state",
        checks,
    )
    require(
        "testDeviceOnlyCredentialStoreTransitionsPendingToActive" in swift_bootstrap_test
        and "testV2EnvelopeAndSignedBootstrapIssueSeparateCredential" in swift_bootstrap_test,
        "iOS XCTest source covers signed bootstrap and device-only credential lifecycle",
        checks,
    )
    require(
        all(
            file_name in ios_project
            for file_name in (
                "AgentPairingBootstrapNetworkClient.swift",
                "AgentPairingBootstrapV2.swift",
                "AgentPairingCredentialStore.swift",
                "AgentPairingBootstrapTests.swift",
            )
        ),
        "Xcode project includes every v2 production and test source",
        checks,
    )
    require(
        '"bootstrap_credential_separated"' in ios_network_smoke_runner
        and '"bootstrap_issuance_client_key_bound"' in ios_network_smoke_runner,
        "cross-platform runner requires bootstrap separation and key-binding evidence",
        checks,
    )

    print(
        json.dumps(
            {
                "ok": True,
                "checks": len(checks),
                "scope": "static_cross_platform_qr_v2_bootstrap_and_reconnect_contract_only",
                "server_enforced_one_time_secret_claim": True,
                "persistent_reconnect_credential_rotation_claim": True,
                "release_developer_bearer_absent_claim": True,
                "android_permissionless_system_scanner_wiring_claim": True,
                "android_physical_scanner_execution_claim": False,
                "account_or_shared_grant_registry_claim": False,
                "physical_device_execution_claim": False,
                "errors": [],
            },
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, OSError, ValueError, json.JSONDecodeError) as error:
        print(json.dumps({"ok": False, "errors": [str(error)]}, indent=2))
        raise SystemExit(1)
