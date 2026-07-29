# Purpose: keep Android/iOS explicit user-confirmation provenance exact, content-free, and fail-closed.
# Input: production models/stores, encrypted migrations, backup restoration, tests, smoke, and current docs.
# Output: static JSON checks; this gate claims no real user, physical device, account, provider, or peer proof.

from __future__ import annotations

import json
from pathlib import Path
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[2]
ANDROID_MODEL = (
    ROOT / "apps/android/app/src/main/java/com/ameme/android/data/SourceDeletionRepository.kt"
)
ANDROID_STORE = (
    ROOT / "apps/android/app/src/main/java/com/ameme/android/data/local/LocalEventDatabase.kt"
)
ANDROID_TEST = (
    ROOT
    / "apps/android/app/src/androidTest/java/com/ameme/android/data/local"
    / "LocalSourceDeletionInstrumentedTest.kt"
)
ANDROID_JVM_TEST = (
    ROOT
    / "apps/android/app/src/test/java/com/ameme/android/coverage"
    / "CoverageRepositoryContractTest.kt"
)
IOS_MODEL = ROOT / "apps/ios/Ameme/Shared/SourceLineage.swift"
IOS_STORE = ROOT / "apps/ios/Ameme/Shared/LocalMemoryStore.swift"
IOS_BACKUP = ROOT / "apps/ios/Ameme/Shared/LocalBackupStore.swift"
IOS_TEST = ROOT / "apps/ios/Tests/AmemeSharedTests/SourceDeletionTests.swift"
IOS_SMOKE = ROOT / "apps/ios/Smoke/main.swift"
CURRENT = ROOT / "docs/_CURRENT.md"
PRIVACY = ROOT / "docs/privacy-security/MVP数据分类保留与用户权利.md"
REPORT = ROOT / "docs/quality/P0-双端用户确认字段证据与来源删除保留验证-20260729.md"


def require(condition: bool, message: str, checks: list[str]) -> None:
    if not condition:
        raise AssertionError(message)
    checks.append(message)


def require_markers(
    source: str,
    markers: tuple[str, ...],
    label: str,
    checks: list[str],
) -> None:
    for marker in markers:
        require(marker in source, f"{label} declares {marker}", checks)


def main() -> int:
    checks: list[str] = []
    paths = (
        ANDROID_MODEL,
        ANDROID_STORE,
        ANDROID_TEST,
        ANDROID_JVM_TEST,
        IOS_MODEL,
        IOS_STORE,
        IOS_BACKUP,
        IOS_TEST,
        IOS_SMOKE,
        CURRENT,
        PRIVACY,
        REPORT,
    )
    for path in paths:
        require(path.is_file(), f"required file exists: {path.relative_to(ROOT)}", checks)

    android_model = ANDROID_MODEL.read_text(encoding="utf-8")
    android_store = ANDROID_STORE.read_text(encoding="utf-8")
    android_test = ANDROID_TEST.read_text(encoding="utf-8")
    android_jvm_test = ANDROID_JVM_TEST.read_text(encoding="utf-8")
    ios_model = IOS_MODEL.read_text(encoding="utf-8")
    ios_store = IOS_STORE.read_text(encoding="utf-8")
    ios_backup = IOS_BACKUP.read_text(encoding="utf-8")
    ios_test = IOS_TEST.read_text(encoding="utf-8")
    ios_smoke = IOS_SMOKE.read_text(encoding="utf-8")
    docs = "\n".join(
        path.read_text(encoding="utf-8") for path in (CURRENT, PRIVACY, REPORT)
    )

    require_markers(
        android_model,
        (
            "Content-free, exact-revision proof of one explicit user action",
            "LocalEventUserConfirmation",
            "UserConfirmationKind",
            "CoverageAcceptance",
            "FactStatusConfirmation",
            "UserRevision",
            "confirmedFields: Set<EvidenceField>",
            "completeFieldSet: Boolean",
            "userConfirmationsForEvent",
            "Partial",
        ),
        "Android user-confirmation model",
        checks,
    )
    require_markers(
        android_store,
        (
            "const val USER_CONFIRMATION_SCHEMA_VERSION = 13",
            "migrate_v12_to_v13_user_confirmation_provenance",
            "event_user_confirmations",
            "event_user_confirmations_no_delete",
            "event_user_confirmations_terminal_only",
            "confirmed_fields_json TEXT NOT NULL",
            "complete_field_set INTEGER NOT NULL",
            "acceptance.mode == CoverageAcceptanceMode.UserConfirmed",
            "confirmedFields = candidate.observedFields",
            "completeFieldSet = true",
            "completeFieldSet = false",
            "completeUserConfirmationFields",
            "terminalizeUserConfirmations",
            "carryUserConfirmations",
            "USER_CONFIRMED_SOURCE_DELETED_LABEL",
            "appendAgentRevision",
            "undoAgentCapture",
        ),
        "Android production store",
        checks,
    )
    require_markers(
        android_test,
        (
            "completeUserConfirmationPreservesSingleSourceEventWithoutRetainingSourceClaim",
            "partialConfirmationIsAuditedButCannotPreserveUnsupportedCapturedEvent",
            "v12MigrationPreservesFieldEvidenceWithoutInventingUserConfirmation",
            "UserConfirmationState.Deleted",
            "USER_CONFIRMED_SOURCE_DELETED_LABEL",
        ),
        "Android instrumentation source",
        checks,
    )
    require_markers(
        android_jvm_test,
        (
            "userConfirmationStoresOnlyExactRevisionFieldNamesAndTerminalState",
            "confirmedFields = setOf(EvidenceField.Time, EvidenceField.Action)",
            "confirmation.copy(confirmedFields = emptySet())",
        ),
        "Android JVM contract test",
        checks,
    )

    require_markers(
        ios_model,
        (
            "Content-free, exact-revision proof of one explicit user action",
            "EventUserConfirmation",
            "UserConfirmationKind",
            'coverageAcceptance = "coverage_acceptance"',
            'factStatusConfirmation = "fact_status_confirmation"',
            'userRevision = "user_revision"',
            "confirmedFields: [EvidenceField]",
            "completeFieldSet: Bool",
        ),
        "iOS user-confirmation model",
        checks,
    )
    require_markers(
        ios_store,
        (
            "currentSchemaVersion = 8",
            "LegacyLocalStoreEnvelopeV7",
            "migratedUserConfirmationEnvelope",
            "eventUserConfirmations",
            "acceptance.mode == .userConfirmed",
            "confirmedFields: Set(candidate.observedFields)",
            "completeFieldSet: true",
            "completeFieldSet: completeBefore != nil",
            "completeFieldSet: completeFields != nil",
            "completeUserConfirmationFields",
            "terminalizeUserConfirmations",
            "carryUserConfirmations",
            'userConfirmedSourceDeletedLabel = "用户确认（来源已删除）"',
            "activeCompleteConfirmationGroups",
        ),
        "iOS encrypted production store",
        checks,
    )
    require(
        "eventUserConfirmations: verified.envelope.eventUserConfirmations" in ios_backup,
        "iOS authenticated recovery preserves user-confirmation provenance",
        checks,
    )
    require_markers(
        ios_test,
        (
            "testCompleteUserConfirmationPreservesSingleSourceEventWithoutSourceClaim",
            "testPartialConfirmationIsAuditedButCannotPreserveCapturedEvent",
            "testV7EnvelopePreservesFieldEvidenceAndMigratesWithoutGuessingConfirmation",
            'legacy.removeValue(forKey: "eventUserConfirmations")',
            'XCTAssertEqual(migratedJSON["schemaVersion"] as? Int, 8)',
        ),
        "iOS XCTest source",
        checks,
    )
    require_markers(
        ios_smoke,
        (
            "complete user-confirmation retention/partial-confirmation fail-closed/reload",
            "originalConfirmation.completeFieldSet",
            "partialCascade.deletedEventCount == 1",
            "userConfirmationReloaded",
        ),
        "iOS executable production smoke",
        checks,
    )

    require_markers(
        docs,
        (
            "Android schema v13",
            "iOS envelope v8",
            "用户确认",
            "content-free",
            "exact-revision",
            "partial",
            "物理设备",
            "真实用户",
            "hold",
        ),
        "current documentation",
        checks,
    )

    combined = android_model + android_store + ios_model + ios_store + docs
    for forbidden, claim in (
        ("productionUserConfirmationProof=true", "production user proof"),
        ("realUserExecutionClaim=true", "real-user execution"),
        ("physicalDeviceExecutionClaim=true", "physical-device execution"),
        ("providerDeletionProof=true", "provider deletion"),
        ("peerDeletionProof=true", "peer deletion"),
    ):
        require(forbidden not in combined, f"slice does not claim {claim}", checks)

    tracked = (
        ANDROID_MODEL,
        ANDROID_STORE,
        ANDROID_TEST,
        ANDROID_JVM_TEST,
        IOS_MODEL,
        IOS_STORE,
        IOS_BACKUP,
        IOS_TEST,
        IOS_SMOKE,
        CURRENT,
        PRIVACY,
        REPORT,
    )
    ignored = [
        subprocess.run(
            ["git", "check-ignore", "--quiet", str(path.relative_to(ROOT))],
            cwd=ROOT,
            check=False,
        ).returncode
        for path in tracked
    ]
    require(ignored == [1] * len(tracked), "user-confirmation sources are tracked by Git", checks)

    print(
        json.dumps(
            {
                "ok": True,
                "checks": len(checks),
                "scope": "static_cross_platform_exact_revision_user_confirmation_provenance",
                "content_values_persisted_in_confirmation_claim": False,
                "complete_confirmation_retention_claim": True,
                "partial_confirmation_retention_claim": False,
                "legacy_confirmation_backfill_claim": False,
                "real_user_execution_claim": False,
                "physical_device_execution_claim": False,
                "account_provider_or_peer_proof_claim": False,
            },
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except AssertionError as error:
        print(json.dumps({"ok": False, "error": str(error)}, indent=2))
        sys.exit(1)
