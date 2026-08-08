# Purpose: keep Android/iOS exact-revision field provenance and multi-source deletion recompute fail-closed.
# Input: production models/stores, schema migrations, tests, smoke, backup restoration, and generated iOS project.
# Output: static JSON checks; this gate claims no real user, device, account, provider, peer, or physical deletion.

from __future__ import annotations

import json
from pathlib import Path
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[2]
ANDROID_COVERAGE = (
    ROOT / "apps/android/app/src/main/java/com/ameme/android/data/CoverageRepository.kt"
)
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
    / "apps/android/app/src/test/java/com/ameme/android/data"
    / "SourceDeletionRepositoryContractTest.kt"
)
IOS_COVERAGE = ROOT / "apps/ios/Ameme/Shared/CoveragePersistence.swift"
IOS_MODEL = ROOT / "apps/ios/Ameme/Shared/SourceLineage.swift"
IOS_STORE = ROOT / "apps/ios/Ameme/Shared/LocalMemoryStore.swift"
IOS_BACKUP = ROOT / "apps/ios/Ameme/Shared/LocalBackupStore.swift"
IOS_TEST = ROOT / "apps/ios/Tests/AmemeSharedTests/SourceDeletionTests.swift"
IOS_SMOKE = ROOT / "apps/ios/Smoke/main.swift"
IOS_PROJECT = ROOT / "apps/ios/Ameme.xcodeproj/project.pbxproj"


def require(condition: bool, message: str, checks: list[str]) -> None:
    if not condition:
        raise AssertionError(message)
    checks.append(message)


def main() -> int:
    checks: list[str] = []
    paths = (
        ANDROID_COVERAGE,
        ANDROID_MODEL,
        ANDROID_STORE,
        ANDROID_TEST,
        ANDROID_JVM_TEST,
        IOS_COVERAGE,
        IOS_MODEL,
        IOS_STORE,
        IOS_BACKUP,
        IOS_TEST,
        IOS_SMOKE,
        IOS_PROJECT,
    )
    for path in paths:
        require(path.is_file(), f"required file exists: {path.relative_to(ROOT)}", checks)

    android_coverage = ANDROID_COVERAGE.read_text(encoding="utf-8")
    android_model = ANDROID_MODEL.read_text(encoding="utf-8")
    android_store = ANDROID_STORE.read_text(encoding="utf-8")
    android_test = ANDROID_TEST.read_text(encoding="utf-8")
    android_jvm_test = ANDROID_JVM_TEST.read_text(encoding="utf-8")
    ios_coverage = IOS_COVERAGE.read_text(encoding="utf-8")
    ios_model = IOS_MODEL.read_text(encoding="utf-8")
    ios_store = IOS_STORE.read_text(encoding="utf-8")
    ios_backup = IOS_BACKUP.read_text(encoding="utf-8")
    ios_test = IOS_TEST.read_text(encoding="utf-8")
    ios_smoke = IOS_SMOKE.read_text(encoding="utf-8")
    ios_project = IOS_PROJECT.read_text(encoding="utf-8")

    for marker in (
        "fieldSourceObjectIds: Map<EvidenceField, Set<String>>",
        "values.none(Set<String>::isEmpty)",
        "values.flatten().none(String::isBlank)",
    ):
        require(marker in android_coverage, f"Android acceptance declares {marker}", checks)
    for marker in (
        "fieldSourceObjectIDs: [EvidenceField: Set<String>]",
        "!$0.isEmpty && $0.allSatisfy { !$0.isEmpty }",
        "Exact-revision field provenance",
    ):
        require(marker in ios_coverage, f"iOS acceptance declares {marker}", checks)

    for marker in (
        "LocalEventFieldEvidence",
        "EventFieldEvidenceState",
        "LocalEventUserConfirmation",
        "UserConfirmationKind",
        "UserConfirmationState",
        "completeFieldSet",
        "eventRevision: Int",
        "field: EvidenceField",
        "recomputedEventCount",
        "deletedEventCount",
        "recomputedEventCount + deletedEventCount <= affectedEventCount",
    ):
        require(marker in android_model, f"Android lineage model declares {marker}", checks)
    for marker in (
        "EventFieldEvidence",
        "EventFieldEvidenceState",
        "EventUserConfirmation",
        "UserConfirmationKind",
        "UserConfirmationState",
        "completeFieldSet",
        "eventRevision: Int",
        "field: EvidenceField",
        "Content-free, exact-revision field provenance",
        "recomputedEventCount",
        "deletedEventCount",
        "recomputedEventCount + deletedEventCount <= affectedEventCount",
        "decodeIfPresent(Int.self, forKey: .recomputedEventCount) ?? 0",
        "decodeIfPresent(Int.self, forKey: .deletedEventCount) ?? 0",
    ):
        require(marker in ios_model, f"iOS lineage model declares {marker}", checks)

    for marker in (
        "const val SCHEMA_VERSION = 14",
        "const val FIELD_EVIDENCE_SCHEMA_VERSION = 12",
        "migrate_v12_to_v13_user_confirmation_provenance",
        "const val SOURCE_DELETION_SCHEMA_VERSION = 11",
        "migrate_v11_to_v12_field_evidence",
        "event_field_evidence",
        "event_revision INTEGER NOT NULL",
        "evidence_field TEXT NOT NULL",
        "event_field_evidence_no_delete",
        "event_field_evidence_terminal_only",
        "event_user_confirmations",
        "event_user_confirmations_no_delete",
        "event_user_confirmations_terminal_only",
        "recomputed_event_count",
        "deleted_event_count",
        "validatedFieldProvenance",
        "acceptance.fieldSourceObjectIds.keys == candidate.observedFields",
        "acceptance.fieldSourceObjectIds.values.flatten().toSet() == candidate.sourceObjectIds",
        "registerEventFieldEvidence",
        "sourceCascadeAction",
        "activeFieldEvidence(eventId, current.revision)",
        "SourceCascadeAction.Recompute",
        "SourceCascadeAction.Delete",
        "recomputeEventAfterSourceDeletion",
        'reason = "source_deletion_recompute"',
        "terminalizeEventFieldEvidence",
        "completeUserConfirmationFields",
        "terminalizeUserConfirmations",
        "carryUserConfirmations",
        "USER_CONFIRMED_SOURCE_DELETED_LABEL",
        "coveragePersistence.detachEvent",
        "LongTermMemoryInvalidationReason.EventRevisionChanged",
        'RECOMPUTED_SOURCE_LABEL = "多来源（已重算）"',
        "SourceDeletionStatus.LineageUnavailable",
    ):
        require(marker in android_store, f"Android production store declares {marker}", checks)

    for marker in (
        "currentSchemaVersion = 8",
        "LegacyLocalStoreEnvelopeV7",
        "eventFieldEvidence",
        "eventUserConfirmations",
        "validatedFieldProvenance",
        "Set(acceptance.fieldSourceObjectIDs.keys) == candidateFields",
        "Set(acceptance.fieldSourceObjectIDs.values.flatMap { $0 }) == candidateSources",
        "sourceCascadeAction",
        "$0.eventRevision == event.revision",
        "case recompute",
        "case delete",
        "recomputeEventAfterSourceDeletion",
        "current.revision + 1",
        "terminalizeEventFieldEvidence",
        "completeUserConfirmationFields",
        "terminalizeUserConfirmations",
        "carryUserConfirmations",
        "invalidateLongTermMemories",
        "coverageEventLinks[index].state = .detached",
        'recomputedSourceLabel = "多来源（已重算）"',
        'userConfirmedSourceDeletedLabel = "用户确认（来源已删除）"',
        "status: .lineageUnavailable",
        "eventFieldEvidence: []",
        "eventFieldEvidence = envelope.eventFieldEvidence",
    ):
        require(marker in ios_store, f"iOS production store declares {marker}", checks)

    for marker in (
        "eventFieldEvidence: verified.envelope.eventFieldEvidence",
        "eventUserConfirmations: verified.envelope.eventUserConfirmations",
        "sourceDeletionRecords: verified.envelope.sourceDeletionRecords",
    ):
        require(marker in ios_backup, f"iOS recovery preserves {marker}", checks)

    for marker in (
        "completeExactRevisionFieldEvidenceRecomputesWithOnlySupportedContent",
        "fieldThatLosesItsFinalSourceDeletesEventInsteadOfRetainingUnsupportedText",
        "userRevisionMakesFieldEvidenceStaleAndCascadeFailsClosed",
        "completeUserConfirmationPreservesSingleSourceEventWithoutRetainingSourceClaim",
        "partialConfirmationIsAuditedButCannotPreserveUnsupportedCapturedEvent",
        "multiSourceCascadeFailsClosedWithoutMutation",
        "fieldSourceObjectIds = mapOf",
        "assertEquals(1, result.recomputedEventCount)",
        "assertEquals(1, result.deletedEventCount)",
    ):
        require(marker in android_test, f"Android instrumented test covers {marker}", checks)
    for marker in (
        "recomputedEventCount = 1",
        "deletedEventCount = 1",
        "del_invalid_breakdown",
    ):
        require(marker in android_jvm_test, f"Android JVM model test covers {marker}", checks)

    for marker in (
        "testMultiSourceCascadeFailsClosedWithoutMutatingEvent",
        "testCompleteFieldEvidenceRecomputesOnlyWhenEveryFieldStillHasSupport",
        "testFieldLosingItsFinalSourceDeletesEventInsteadOfRetainingContent",
        "testUserRevisionMakesExactFieldEvidenceStaleAndCascadeFailsClosed",
        "testCompleteUserConfirmationPreservesSingleSourceEventWithoutSourceClaim",
        "testPartialConfirmationIsAuditedButCannotPreserveCapturedEvent",
        "fieldSourceObjectIDs:",
        "XCTAssertEqual(result.recomputedEventCount, 1)",
        "XCTAssertEqual(result.deletedEventCount, 1)",
    ):
        require(marker in ios_test, f"iOS XCTest covers {marker}", checks)
    for marker in (
        "exact-revision multi-source field evidence/recompute/reload",
        "multiSourceCascade.recomputedEventCount == 1",
        "multiSourceCascade.deletedEventCount == 0",
        "multiSourceReloaded.event(id: multiSourceEvent.id)?.revision == 2",
        'sourceObjectID == "source_smoke_multi_a"',
    ):
        require(marker in ios_smoke, f"iOS production smoke covers {marker}", checks)

    for source in ("CoveragePersistence.swift", "SourceLineage.swift", "SourceDeletionTests.swift"):
        require(source in ios_project, f"iOS generated project includes {source}", checks)

    combined = android_store + android_model + ios_store + ios_model
    require(
        "deleteAccount" not in combined,
        "field provenance slice does not claim account deletion",
        checks,
    )
    require(
        "deleteProviderOriginal" not in combined,
        "field provenance slice does not claim provider-original deletion",
        checks,
    )
    require(
        "peerDeletionProof" not in combined,
        "field provenance slice does not claim peer deletion proof",
        checks,
    )
    require(
        "physicalPurgeProof" not in combined,
        "field provenance slice does not claim physical purge proof",
        checks,
    )

    tracked = (
        ANDROID_COVERAGE,
        ANDROID_MODEL,
        ANDROID_STORE,
        ANDROID_TEST,
        IOS_COVERAGE,
        IOS_MODEL,
        IOS_STORE,
        IOS_TEST,
        IOS_SMOKE,
    )
    ignored = [
        subprocess.run(
            ["git", "check-ignore", "--quiet", str(path.relative_to(ROOT))],
            cwd=ROOT,
            check=False,
        ).returncode
        for path in tracked
    ]
    require(ignored == [1] * len(tracked), "field provenance sources are tracked by Git", checks)

    print(
        json.dumps(
            {
                "ok": True,
                "checks": len(checks),
                "scope": "static_cross_platform_exact_revision_field_and_user_confirmation_provenance",
                "content_retention_without_field_evidence_claim": False,
                "user_confirmation_preservation_claim": True,
                "real_user_execution_claim": False,
                "physical_device_execution_claim": False,
                "account_or_provider_deletion_claim": False,
                "peer_or_physical_deletion_proof_claim": False,
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
