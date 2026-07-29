# Purpose: keep Android and iOS encrypted local-space deletion and freeze boundaries aligned.
# Input: production models/stores, root watermarks, recovery guards, tests, smoke, and iOS project.
# Output: static JSON checks; this gate does not claim account, peer, provider, purge, or device deletion.

from __future__ import annotations

import json
from pathlib import Path
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[2]
ANDROID_MODEL = (
    ROOT / "apps/android/app/src/main/java/com/ameme/android/data/LocalSpaceDeletionRepository.kt"
)
ANDROID_STORE = (
    ROOT / "apps/android/app/src/main/java/com/ameme/android/data/local/LocalEventDatabase.kt"
)
ANDROID_COVERAGE = ANDROID_STORE.with_name("LocalCoveragePersistence.kt")
ANDROID_ADAPTER = ANDROID_STORE.with_name("LocalMemoryRepository.kt")
ANDROID_INSTRUMENTED_TEST = (
    ROOT
    / "apps/android/app/src/androidTest/java/com/ameme/android/data/local"
    / "LocalSpaceDeletionInstrumentedTest.kt"
)
ANDROID_CONTRACT_TEST = (
    ROOT
    / "apps/android/app/src/test/java/com/ameme/android/data"
    / "LocalSpaceDeletionRepositoryContractTest.kt"
)
IOS_MODEL = ROOT / "apps/ios/Ameme/Shared/LocalSpaceDeletion.swift"
IOS_STORE = IOS_MODEL.with_name("LocalMemoryStore.swift")
IOS_BACKUP = IOS_MODEL.with_name("LocalBackupStore.swift")
IOS_TEST = ROOT / "apps/ios/Tests/AmemeSharedTests/LocalSpaceDeletionTests.swift"
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
        ANDROID_INSTRUMENTED_TEST,
        ANDROID_CONTRACT_TEST,
        IOS_MODEL,
        IOS_STORE,
        IOS_BACKUP,
        IOS_TEST,
        IOS_SMOKE,
        IOS_PROJECT,
    )
    for path in paths:
        require(path.is_file(), f"required file exists: {path.relative_to(ROOT)}", checks)

    android_model = ANDROID_MODEL.read_text(encoding="utf-8")
    android_store = ANDROID_STORE.read_text(encoding="utf-8")
    android_coverage = ANDROID_COVERAGE.read_text(encoding="utf-8")
    android_adapter = ANDROID_ADAPTER.read_text(encoding="utf-8")
    android_instrumented_test = ANDROID_INSTRUMENTED_TEST.read_text(encoding="utf-8")
    android_contract_test = ANDROID_CONTRACT_TEST.read_text(encoding="utf-8")
    ios_model = IOS_MODEL.read_text(encoding="utf-8")
    ios_store = IOS_STORE.read_text(encoding="utf-8")
    ios_backup = IOS_BACKUP.read_text(encoding="utf-8")
    ios_test = IOS_TEST.read_text(encoding="utf-8")
    ios_smoke = IOS_SMOKE.read_text(encoding="utf-8")
    ios_project = IOS_PROJECT.read_text(encoding="utf-8")

    for marker in (
        "LocalSpaceDeletionRepository",
        "CompletedLocalOnly",
        "PendingExternalCleanup",
        "AlreadyDeleted",
        "externalOriginalsRetained",
        "accountDeletionClaim: Boolean = false",
        "peerDeletionProofClaim: Boolean = false",
        "require(!accountDeletionClaim)",
        "require(!peerDeletionProofClaim)",
    ):
        require(marker in android_model, f"Android local-space model declares {marker}", checks)

    for marker in (
        "fun isLocalSpaceDeleted()",
        "fun deleteLocalSpace(requestedAt: Instant)",
        "inTransaction(allowDeletedSpace = true)",
        "tombstoneEvent(eventId, reason = LOCAL_SPACE_DELETE_REASON",
        "coveragePersistence.terminalizeAll(requestedAt)",
        "DELETION_OBJECT_SOURCE",
        "DELETION_OBJECT_SPACE",
        'const val DELETION_OBJECT_SPACE = "SPACE"',
        'const val LOCAL_SPACE_DELETE_REASON = "local_space_delete"',
        "requireLocalSpaceActive()",
        'check(!isLocalSpaceDeleted()) { "local space is deleted and frozen" }',
        'check(!isLocalSpaceDeleted()) { "deleted local space cannot create a recovery backup" }',
        "if (isLocalSpaceDeleted()) return emptyList()",
        "if (isLocalSpaceDeleted()) null else coveragePersistence.load",
        "if (isLocalSpaceDeleted()) null else longTermMemoryPersistence.load",
        "if (isLocalSpaceDeleted()) emptyList() else reusePersistence.aggregates",
        "fun markSourceLocatorReleased(eventId: String): Boolean = inTransaction(allowDeletedSpace = true)",
    ):
        require(marker in android_store, f"Android local-space store declares {marker}", checks)

    require(
        android_store.index("objectType = DELETION_OBJECT_SPACE") >
        android_store.index("coveragePersistence.terminalizeAll(requestedAt)"),
        "Android writes the root SPACE watermark after dependent convergence",
        checks,
    )
    require(
        "fun terminalizeAll(deletedAt: Instant)" in android_coverage
        and "CoverageCandidateLifecycle.Deleted.name" in android_coverage
        and "CoverageEventLinkLifecycle.Detached.name" in android_coverage,
        "Android terminalizes open Coverage candidates and active links",
        checks,
    )
    for marker in (
        "LocalSpaceDeletionRepository",
        "override fun isLocalSpaceDeleted",
        "override fun deleteLocalSpace",
    ):
        require(marker in android_adapter, f"Android production adapter declares {marker}", checks)

    for marker in (
        "localSpaceDeleteFreezesWritesRejectsOldBackupAndLeavesOtherSpaceUntouched",
        "rootWatermarkFailureRollsBackWholeLocalSpaceDelete",
        "LocalSpaceDeletionStatus.PendingExternalCleanup",
        "assertFalse(result.accountDeletionClaim)",
        "assertFalse(result.peerDeletionProofClaim)",
        "repository.capture(CaptureKind.Text, \"删除后的写入必须失败\")",
        "repository.verifyLocalRecoveryBackup(backupRoot)",
        "reloaded.restoreLocalRecoveryCandidate(",
        "assertFalse(other.isLocalSpaceDeleted())",
        "force_space_root_watermark_failure",
        "repository.markSourceLocatorReleased(externalEvent.id)",
    ):
        require(
            marker in android_instrumented_test,
            f"Android instrumented source covers {marker}",
            checks,
        )
    for marker in (
        "localOnlyResultCannotClaimAccountOrPeerCompletion",
        "accountDeletionClaimFailsClosed",
        "peerDeletionProofClaimAndNegativeCountsFailClosed",
        "accountDeletionClaim = true",
        "peerDeletionProofClaim = true",
    ):
        require(marker in android_contract_test, f"Android JVM contract covers {marker}", checks)

    for marker in (
        "completedLocalOnly",
        "pendingRawCleanup",
        "alreadyDeleted",
        "persistenceFailed",
        "externalOriginalsRetained",
        "accountDeletionClaim: Bool = false",
        "peerDeletionProofClaim: Bool = false",
        "precondition(!accountDeletionClaim)",
        "precondition(!peerDeletionProofClaim)",
    ):
        require(marker in ios_model, f"iOS local-space model declares {marker}", checks)

    for marker in (
        "public var isLocalSpaceDeleted: Bool",
        "public func deleteLocalSpace(requestedAt: Date = .now)",
        "coverageDays = []",
        "coverageEventLinks = []",
        "longTermMemories = []",
        "reuseAttempts = []",
        "reuseOutcomes = []",
        "rawState = .pendingCleanup",
        "sourceObjects[index].sourceLocator = nil",
        "upsertDeletionTombstone(.localSpace(deletedAt: requestedAt))",
        "allowsDeletedSpacePersistence = true",
        "retryPendingRawCleanup()",
        "storageState = .deleted",
        "!isLocalSpaceDeleted",
        "guard !isLocalSpaceDeleted || allowsDeletedSpacePersistence else",
        "storageState == .ready || storageState == .deleted",
        "effectiveTombstones",
        "authoritativeTombstones: effectiveTombstones.values.sorted",
    ):
        require(marker in ios_store, f"iOS local-space store declares {marker}", checks)
    require(
        ios_store.index("upsertDeletionTombstone(.localSpace(deletedAt: requestedAt))") >
        ios_store.index("reuseOutcomes = []"),
        "iOS writes the root SPACE tombstone after dependent convergence",
        checks,
    )
    for marker in (
        'spaceObjectType = "SPACE"',
        "public static func localSpace(",
        "objectID: personalSpaceID",
        "case snapshotPredatesDeletion",
        "throw LocalBackupError.snapshotPredatesDeletion",
    ):
        require(marker in ios_backup, f"iOS recovery watermark declares {marker}", checks)

    for marker in (
        "testLocalSpaceDeleteRemovesOwnedRawFreezesWritesAndRejectsOldBackup",
        "testRootPersistenceFailureRollsBackEveryInMemoryProjection",
        "XCTAssertTrue(store.isLocalSpaceDeleted)",
        "XCTAssertNil(store.addText(\"删除后不得重新写入\"))",
        ".snapshotPredatesDeletion",
        "XCTAssertEqual(reloaded.storageState, .deleted)",
        "XCTAssertEqual(result.status, .persistenceFailed)",
    ):
        require(marker in ios_test, f"iOS XCTest source covers {marker}", checks)
    for marker in (
        "local-space root freeze/app-owned Raw cleanup/old-backup rejection",
        "spaceDeletionStore.deleteLocalSpace(",
        "!spaceDeletionResult.accountDeletionClaim",
        "!spaceDeletionResult.peerDeletionProofClaim",
        "LocalBackupError.snapshotPredatesDeletion",
        "spaceDeletionReloaded.storageState == .deleted",
        "spaceDeletionReloaded.deleteLocalSpace().status == .alreadyDeleted",
    ):
        require(marker in ios_smoke, f"iOS production smoke covers {marker}", checks)
    for source in ("LocalSpaceDeletion.swift", "LocalSpaceDeletionTests.swift"):
        require(source in ios_project, f"iOS generated project includes {source}", checks)

    require(
        "deleteAccount(" not in android_model + android_store + ios_model + ios_store,
        "local-space slice does not expose account deletion",
        checks,
    )
    require(
        "GrantRepository" not in android_model + ios_model,
        "local-space result does not masquerade as Grant revocation",
        checks,
    )
    require(
        "physicalPurgeClaim" not in android_model + ios_model,
        "local-space result does not invent physical-purge proof",
        checks,
    )

    tracked_inputs = (
        ANDROID_MODEL,
        ANDROID_INSTRUMENTED_TEST,
        ANDROID_CONTRACT_TEST,
        IOS_MODEL,
        IOS_TEST,
    )
    ignored = [
        subprocess.run(
            ["git", "check-ignore", "--quiet", str(path.relative_to(ROOT))],
            cwd=ROOT,
            check=False,
        ).returncode
        for path in tracked_inputs
    ]
    require(
        ignored == [1] * len(tracked_inputs),
        "local-space deletion sources and tests are not ignored by Git",
        checks,
    )

    print(
        json.dumps(
            {
                "ok": True,
                "checks": len(checks),
                "scope": "static_cross_platform_local_space_freeze_and_deletion_only",
                "account_deletion_claim": False,
                "grant_revocation_claim": False,
                "peer_deletion_proof_claim": False,
                "provider_original_deletion_claim": False,
                "physical_purge_claim": False,
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
