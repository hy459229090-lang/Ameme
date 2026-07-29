# Purpose: keep the Android/iOS local source-lineage and deletion boundaries aligned.
# Input: production stores, lineage models, migrations, tests, smoke, backup safety, and iOS project.
# Output: static JSON checks; this gate does not claim device, provider, account, or peer deletion.

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
ANDROID_COVERAGE = ANDROID_STORE.with_name("LocalCoveragePersistence.kt")
ANDROID_ADAPTER = ANDROID_STORE.with_name("LocalMemoryRepository.kt")
ANDROID_TEST = (
    ROOT
    / "apps/android/app/src/androidTest/java/com/ameme/android/data/local"
    / "LocalSourceDeletionInstrumentedTest.kt"
)
IOS_MODEL = ROOT / "apps/ios/Ameme/Shared/SourceLineage.swift"
IOS_STORE = IOS_MODEL.with_name("LocalMemoryStore.swift")
IOS_BACKUP = IOS_MODEL.with_name("LocalBackupStore.swift")
IOS_TEST = ROOT / "apps/ios/Tests/AmemeSharedTests/SourceDeletionTests.swift"
IOS_BACKUP_TEST = ROOT / "apps/ios/Tests/AmemeSharedTests/LocalBackupStoreTests.swift"
IOS_SMOKE = ROOT / "apps/ios/Smoke/main.swift"
IOS_PROJECT = ROOT / "apps/ios/Ameme.xcodeproj/project.pbxproj"


def require(condition: bool, message: str, checks: list[str]) -> None:
    if not condition:
        raise AssertionError(message)
    checks.append(message)


def main() -> int:
    checks: list[str] = []
    paths = (
        ANDROID_MODEL,
        ANDROID_STORE,
        ANDROID_COVERAGE,
        ANDROID_ADAPTER,
        ANDROID_TEST,
        IOS_MODEL,
        IOS_STORE,
        IOS_BACKUP,
        IOS_TEST,
        IOS_BACKUP_TEST,
        IOS_SMOKE,
        IOS_PROJECT,
    )
    for path in paths:
        require(path.is_file(), f"required file exists: {path.relative_to(ROOT)}", checks)

    android_model = ANDROID_MODEL.read_text(encoding="utf-8")
    android_store = ANDROID_STORE.read_text(encoding="utf-8")
    android_coverage = ANDROID_COVERAGE.read_text(encoding="utf-8")
    android_adapter = ANDROID_ADAPTER.read_text(encoding="utf-8")
    android_test = ANDROID_TEST.read_text(encoding="utf-8")
    ios_model = IOS_MODEL.read_text(encoding="utf-8")
    ios_store = IOS_STORE.read_text(encoding="utf-8")
    ios_backup = IOS_BACKUP.read_text(encoding="utf-8")
    ios_test = IOS_TEST.read_text(encoding="utf-8")
    ios_backup_test = IOS_BACKUP_TEST.read_text(encoding="utf-8")
    ios_smoke = IOS_SMOKE.read_text(encoding="utf-8")
    project = IOS_PROJECT.read_text(encoding="utf-8")

    for marker in (
        "const val SCHEMA_VERSION = 14",
        "const val SOURCE_DELETION_SCHEMA_VERSION = 11",
        "const val REUSE_SCHEMA_VERSION = 10",
        "migrate_v10_to_v11_source_deletion",
        "source_objects",
        "event_source_links",
        "deletion_jobs",
        "source_objects_no_delete",
        "source_objects_terminal_only",
        "event_source_links_no_delete",
        "event_source_links_terminal_only",
        "NEW.space_id != OLD.space_id",
        "NEW.source_object_id != OLD.source_object_id",
        "NEW.event_id != OLD.event_id",
        'listOf("deletion_jobs")',
        "${table}_no_update",
        "${table}_no_delete",
        "DELETION_OBJECT_SOURCE",
        "writeDeletionWatermark",
        "registerCapturedSource",
        "registerSourceObject",
        "linkEventToSource",
        "deleteRawOnly",
        "deleteSourceCascade",
        "sourceCascadeAction(eventId, sourceObjectId)",
        "SourceCascadeAction.Recompute",
        "SourceCascadeAction.Delete",
    ):
        require(marker in android_store, f"Android source deletion declares {marker}", checks)

    for marker in (
        "SourceDeletionRepository",
        "LocalRawOwnership",
        "ExternalNotOwned",
        "NoRaw",
        'RawOnly("raw_only")',
        'SourceCascade("source_cascade")',
        'ExternalNotOwned("external_not_owned")',
        'CompletedLocalOnly("completed_local_only")',
        'LineageUnavailable("lineage_unavailable")',
        "A single-source Event is deleted",
        "exact current revision has a complete explicit",
        "remaining source or a complete user confirmation",
    ):
        require(marker in android_model, f"Android source model declares {marker}", checks)

    for marker in (
        "SourceDeletionRepository",
        "override fun sourceObjectsForEvent",
        "override fun deleteRawOnly",
        "override fun deleteSourceCascade",
    ):
        require(marker in android_adapter, f"Android production adapter declares {marker}", checks)

    for marker in (
        "terminalizeSource",
        "CoverageCandidateLifecycle.Deleted",
        "coverage_source_index",
        "source.source_object_id",
    ):
        require(marker in android_coverage, f"Android Coverage deletion declares {marker}", checks)

    for marker in (
        "currentSchemaVersion = 8",
        "LegacyLocalStoreEnvelopeV6",
        "sourceObjects",
        "eventSourceLinks",
        "sourceDeletionRecords",
        "registerCapturedSource",
        "registerCoverageSource",
        "deleteRawOnly",
        "deleteSourceCascade",
        "lineageUnavailable",
        "pendingCleanup",
        "completedLocalOnly",
        "retryPendingRawCleanup",
        "sourceMediaInventoryIsValid",
        "persistenceFailureInjector",
        "removeOwnedRaw",
        "finalizeRawCleanup",
        "sourceObjectType",
        "terminal source watermark allowed coverage evidence to resurrect",
    ):
        require(
            marker in ios_store + ios_smoke,
            f"iOS source deletion declares {marker}",
            checks,
        )

    for marker in (
        "SourceRawOwnership",
        "appOwnedEncrypted",
        "externalNotOwned",
        "noRaw",
        'rawOnly = "raw_only"',
        'sourceCascade = "source_cascade"',
        'pendingCleanup = "pending_cleanup"',
        'lineageUnavailable = "lineage_unavailable"',
        "rawCiphertextSHA256",
        "rawDigestOnly",
        "isInternallyValid",
        "sourceLocator.hasPrefix(\"/\")",
    ):
        require(marker in ios_model, f"iOS source model declares {marker}", checks)

    for marker in (
        'sourceObjectType = "SOURCE_OBJECT"',
        "restoredSourceObject",
        "sourceObjects: sourceObjects",
        "backupMediaMatchesEnvelope",
        "source.rawOwnership == .appOwnedEncrypted",
        "expectedDigest",
        "sourceObjects: restoredSources",
        "eventSourceLinks: verified.envelope.eventSourceLinks",
        "sourceDeletionRecords: verified.envelope.sourceDeletionRecords",
    ):
        require(marker in ios_backup, f"iOS recovery includes {marker}", checks)

    for marker in (
        "rawOnlyReportsExternalOwnershipAndPreservesStructuredEvent",
        "rawOnlyReportsNoRawForCoverageEvidenceWithoutMutation",
        "singleSourceCascadeTombstonesEventAndRejectsCoverageResurrection",
        "multiSourceCascadeFailsClosedWithoutMutation",
        "sourceCascadeIsScopedToOneSpaceWhenSourceIdentityMatches",
        "lateDeletionJobFailureRollsBackEntireSourceCascade",
        "v10MigrationPreservesLegacyEventWithoutInventingSourceLineage",
        "terminalSourceAndLinkIdentityCannotBeRewrittenByDirectSql",
        "SourceDeletionStatus.CompletedLocalOnly",
        "assertThrows(IllegalArgumentException::class.java)",
    ):
        require(marker in android_test, f"Android instrumented test covers {marker}", checks)

    for marker in (
        "testRawOnlyDeletesOwnedCiphertextAndPreservesRevisedEvent",
        "testExternalOriginalIsNotClaimedOrMutated",
        "testSingleSourceCascadePersistsSourceWatermarkAndNoResurrection",
        "testMultiSourceCascadeFailsClosedWithoutMutatingEvent",
        "testCascadeConvergesAfterFinalPersistenceFailureAndReload",
        ".completedLocalOnly",
        ".lineageUnavailable",
    ):
        require(marker in ios_test, f"iOS XCTest source covers {marker}", checks)

    for marker in (
        "testBackupUsesAppOwnedSourceObjectAsAuthoritativeMediaInventory",
        "testAppOwnedAvailableSourceRequiresOwnedMediaLocator",
        "sourceObjects: [source]",
        "manifest.media.count",
    ):
        require(marker in ios_backup_test, f"iOS backup XCTest covers {marker}", checks)

    for marker in (
        "app-owned Raw-only/external-original boundary/source cascade watermark",
        "rawOnlyResult.status == .completed",
        "photoRawResult.status == .externalNotOwned",
        "photoCascadeResult.status == .completedLocalOnly",
        "cascadeResult.status == .completed",
        "DeletionTombstone.sourceObjectType",
    ):
        require(marker in ios_smoke, f"iOS production smoke covers {marker}", checks)

    for source in ("SourceLineage.swift", "SourceDeletionTests.swift"):
        require(source in project, f"iOS generated project includes {source}", checks)

    require(
        "AppOwned" not in android_model and "app_owned" not in android_model.lower(),
        "Android does not claim an app-owned Raw Vault",
        checks,
    )
    require(
        "removeItem(at: candidate)" in ios_store,
        "iOS app-owned Raw cleanup executes a real filesystem delete",
        checks,
    )
    require(
        "!sourceObjects.contains(where: { $0.rawState == .pendingCleanup })" in ios_store,
        "iOS backup refuses a pending physical Raw cleanup",
        checks,
    )
    require(
        "deleteAccount" not in android_model + android_store + ios_model + ios_store,
        "local source slice does not masquerade as account deletion",
        checks,
    )
    require(
        "deleteSpace" not in android_model + android_store + ios_model + ios_store,
        "local source slice does not masquerade as space deletion",
        checks,
    )

    tracked = (
        ANDROID_MODEL,
        ANDROID_TEST,
        IOS_MODEL,
        IOS_TEST,
    )
    ignored = [
        subprocess.run(
            ["git", "check-ignore", "--quiet", str(path.relative_to(ROOT))],
            cwd=ROOT,
            check=False,
        ).returncode
        for path in tracked
    ]
    require(ignored == [1] * len(tracked), "source deletion sources are tracked by Git", checks)

    print(
        json.dumps(
            {
                "ok": True,
                "checks": len(checks),
                "scope": "static_cross_platform_local_source_lineage_and_deletion_only",
                "android_app_owned_raw_claim": False,
                "account_or_space_deletion_claim": False,
                "provider_original_deletion_claim": False,
                "peer_deletion_proof_claim": False,
                "device_execution_claim": False,
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
