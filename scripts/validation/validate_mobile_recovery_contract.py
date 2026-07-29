# Purpose: keep Android and iOS same-install recovery safety boundaries aligned.
# Input: encrypted stores, backup implementations, deletion-watermark wiring, tests, smoke, and iOS project.
# Output: content-free JSON checks; this gate does not claim physical-device or cross-device recovery.

from __future__ import annotations

import json
from pathlib import Path
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[2]
ANDROID_DATABASE = (
    ROOT / "apps/android/app/src/main/java/com/ameme/android/data/local/LocalEventDatabase.kt"
)
ANDROID_BACKUP = ANDROID_DATABASE.with_name("RecoveryBackup.kt")
ANDROID_ACTIVATION = ANDROID_DATABASE.with_name("LocalRecoveryActivation.kt")
ANDROID_POINT_MANAGER = ANDROID_DATABASE.with_name("LocalRecoveryPointManager.kt")
ANDROID_AUDIT_MERGE = ANDROID_DATABASE.with_name("LocalRecoveryAgentAccessAudit.kt")
ANDROID_AUDIT_PLAN = (
    ROOT / "apps/android/app/src/main/java/com/ameme/android/data/AgentAccessAuditRecovery.kt"
)
ANDROID_REPOSITORY = (
    ROOT / "apps/android/app/src/main/java/com/ameme/android/data/RecoveryBackupRepository.kt"
)
ANDROID_REPOSITORY_ADAPTER = ANDROID_DATABASE.with_name("LocalMemoryRepository.kt")
ANDROID_KEY_PROVIDER = ANDROID_DATABASE.with_name("AndroidKeystoreDatabaseKeyProvider.kt")
ANDROID_TEST = (
    ROOT
    / "apps/android/app/src/androidTest/java/com/ameme/android/data/local"
    / "RecoveryBackupInstrumentedTest.kt"
)
ANDROID_POINT_TEST = (
    ROOT
    / "apps/android/app/src/test/java/com/ameme/android/data/local"
    / "LocalRecoveryPointManagerTest.kt"
)
ANDROID_APP = ROOT / "apps/android/app/src/main/java/com/ameme/android/ui/AmemeApp.kt"
ANDROID_SETTINGS = ANDROID_APP.parent / "screens" / "SettingsScreen.kt"
ANDROID_UI_TEST = (
    ROOT / "apps/android/app/src/androidTest/java/com/ameme/android/AmemeUiSmokeTest.kt"
)
IOS_STORE = ROOT / "apps/ios/Ameme/Shared/LocalMemoryStore.swift"
IOS_BACKUP = IOS_STORE.with_name("LocalBackupStore.swift")
IOS_POINT_MANAGER = IOS_STORE.with_name("LocalRecoveryPointManager.swift")
IOS_TEST = ROOT / "apps/ios/Tests/AmemeSharedTests/LocalBackupStoreTests.swift"
IOS_SMOKE = ROOT / "apps/ios/Smoke/main.swift"
IOS_APP = ROOT / "apps/ios/AmemeApp/AmemeApp.swift"
IOS_PROJECT = ROOT / "apps/ios/Ameme.xcodeproj/project.pbxproj"
ANDROID_PENDING = ANDROID_DATABASE.with_name("AndroidKeystorePendingActionStore.kt")
IOS_PENDING = IOS_STORE.with_name("PendingExportStore.swift")


def require(condition: bool, message: str, checks: list[str]) -> None:
    if not condition:
        raise AssertionError(message)
    checks.append(message)


def main() -> int:
    checks: list[str] = []
    paths = (
        ANDROID_DATABASE,
        ANDROID_BACKUP,
        ANDROID_ACTIVATION,
        ANDROID_POINT_MANAGER,
        ANDROID_AUDIT_MERGE,
        ANDROID_AUDIT_PLAN,
        ANDROID_REPOSITORY,
        ANDROID_REPOSITORY_ADAPTER,
        ANDROID_KEY_PROVIDER,
        ANDROID_TEST,
        ANDROID_POINT_TEST,
        ANDROID_APP,
        ANDROID_SETTINGS,
        ANDROID_UI_TEST,
        IOS_STORE,
        IOS_BACKUP,
        IOS_POINT_MANAGER,
        IOS_TEST,
        IOS_SMOKE,
        IOS_APP,
        IOS_PROJECT,
        ANDROID_PENDING,
        IOS_PENDING,
    )
    for path in paths:
        require(path.is_file(), f"required file exists: {path.relative_to(ROOT)}", checks)

    android = "\n".join(
        path.read_text(encoding="utf-8")
        for path in (
            ANDROID_DATABASE,
            ANDROID_BACKUP,
            ANDROID_ACTIVATION,
            ANDROID_AUDIT_MERGE,
            ANDROID_AUDIT_PLAN,
            ANDROID_REPOSITORY,
            ANDROID_REPOSITORY_ADAPTER,
        )
    )
    ios = IOS_STORE.read_text(encoding="utf-8") + IOS_BACKUP.read_text(encoding="utf-8")
    android_test = ANDROID_TEST.read_text(encoding="utf-8")
    ios_test = IOS_TEST.read_text(encoding="utf-8") + IOS_SMOKE.read_text(encoding="utf-8")
    android_point = ANDROID_POINT_MANAGER.read_text(encoding="utf-8")
    android_point_test = ANDROID_POINT_TEST.read_text(encoding="utf-8")
    android_app = ANDROID_APP.read_text(encoding="utf-8")
    android_settings = ANDROID_SETTINGS.read_text(encoding="utf-8")
    android_ui_test = ANDROID_UI_TEST.read_text(encoding="utf-8")
    ios_point = IOS_POINT_MANAGER.read_text(encoding="utf-8")
    ios_app = IOS_APP.read_text(encoding="utf-8")
    project = IOS_PROJECT.read_text(encoding="utf-8")

    for marker in (
        "deletion_watermarks",
        "backup_checkpoints",
        "migrate_v8_to_v9_recovery_safety",
        "deletion_watermarks_no_delete",
        "deletion_watermarks_no_regression",
        "const val SCHEMA_VERSION = 14",
        "const val REUSE_SCHEMA_VERSION = 10",
        "const val RECOVERY_SCHEMA_VERSION = 9",
        "writeDeletionWatermark",
        "createLocalRecoveryBackup",
        "cipher_integrity_check",
        "integrity_check",
        "RecoveryBackupRepository",
        "verifyLocalRecoveryBackup",
        "restoreLocalRecoveryCandidate",
        "RecoveryBackupRepository,",
        "override fun verifyLocalRecoveryBackup",
        "RecoveryActivationAuthorization",
        "LocalRecoveryActivationCoordinator",
        "recoverInterruptedActivation",
        "JournalState.Prepared",
        "JournalState.Committed",
        "RecoveryActivationPhase.LiveVerified",
        "MAX_VALIDITY: Duration = Duration.ofMinutes(15)",
        "candidateBackupId == expectedBackupId",
        "Live SQLCipher sidecars must be closed before recovery activation",
        "Recovery activation journal authentication failed",
        "HmacSHA256",
        "AgentAccessAuditRecoveryPlanner",
        "mergeIntoStagedCandidate",
        "Recovery Agent access audit union exceeds capacity",
        "Recovery Agent access audit trace-phase conflict",
        "PRAGMA journal_mode=DELETE",
        "mergedSnapshotSha256",
        "AgentAccessAuditMerged",
        "deleteStagedArtifacts",
    ):
        require(marker in android, f"Android recovery persistence declares {marker}", checks)
    for marker in (
        "DeletionTombstone",
        "deletionTombstones",
        "currentSchemaVersion = 8",
        "LegacyLocalStoreEnvelopeV6",
        "LegacyLocalStoreEnvelopeV3",
        "createLocalRecoveryBackup",
        "verifyLocalRecoveryBackup",
        "restoreLocalRecoveryCandidate",
        "isInternallyConsistent",
        "LocalRecoveryActivationAuthorization",
        "activateLocalRecoveryCandidate",
        "recoverInterruptedActivation",
        "case prepared",
        "case committed",
        "case liveVerified",
        "maximumValidity: TimeInterval = 15 * 60",
        "candidateBackupID == expectedBackupID",
        "cleanupPending",
        "authenticationMAC",
        "activationJournalInvalid",
    ):
        require(marker in ios, f"iOS recovery persistence declares {marker}", checks)

    for marker in (
        "external_same_install_required",
        "productionRecoveryClaim",
        "manifestMac",
        "HmacSHA256",
        "snapshotSha256",
        "deletionWatermarkDigest",
        "Recovery candidate destination must not already exist",
        "Recovery snapshot predates an authoritative deletion watermark",
    ):
        require(marker in android, f"Android recovery artifact enforces {marker}", checks)
    for marker in (
        "external_same_install_required",
        "productionRecoveryClaim",
        "manifestMAC",
        "HMAC<SHA256>",
        "eventsCiphertextSHA256",
        "deletionWatermarkDigest",
        "destinationExists",
        "snapshotPredatesDeletion",
    ):
        require(marker in ios, f"iOS recovery artifact enforces {marker}", checks)

    for marker in (
        "OPEN_READONLY",
        "MessageDigest.isEqual",
        "authoritativeWatermarks",
        "moveDirectoryAtomically",
        "deleteRecursively",
    ):
        require(marker in android, f"Android recovery verifier covers {marker}", checks)
    for marker in (
        "isRegularNonSymbolicFile",
        "isSafeMediaPath",
        "constantTimeEqual",
        "authoritativeTombstones",
        "restoredEnvelope.isInternallyConsistent",
        "removeItem(at: staging)",
    ):
        require(marker in ios, f"iOS recovery verifier covers {marker}", checks)

    for marker in (
        "verifiedCandidatePreservesTombstoneAndRejectsOldSnapshotCorruptionAndNonemptyTarget",
        "v8MigratesThroughCurrentSchemaAndCreatesRecoverySafetyTables",
        "wrongKeyResult",
        "staleCandidate",
        "corrupted",
        "nonempty",
        "exactAuthorizationActivatesCandidateAndInjectedFailureRollsBackLive",
        "CandidateMovedToLive",
        "expired",
        "forgedJournal",
        "activationPreservesPostBackupAgentAuditAndMergeFailureLeavesLiveLedger",
        "preparedRecoveryRemovesOrphanedAuditMergeSidecarsBeforeRestoringLive",
        "managedRecoveryPointCreatesActivatesReopensAndPersistsSuccessReceipt",
    ):
        require(marker in android_test, f"Android instrumented recovery test covers {marker}", checks)
    for marker in (
        "testVerifiedBackupRestoresToNewRootAndPreservesDeletionTombstone",
        "testOldBackupCorruptionWrongKeyAndNonemptyTargetFailClosed",
        "snapshotPredatesDeletion",
        "authenticationFailed",
        "destinationExists",
        "authenticated same-install recovery candidate/deletion-watermark/nonempty-target fail-closed",
        "testExactAuthorizationActivatesCandidateAndRewritesOwnedMediaToLiveRoot",
        "testActivationFailureRollsBackLiveAndExpiredAuthorizationDoesNotMutate",
        "candidateMovedToLive",
        "exact-confirmation activation",
        "forgedJournal",
        "activationJournalInvalid",
        "testRecoveryPointManagerCreatesPreparesActivatesAndPersistsReceipt",
        "testRecoveryPointManagerFailsClosedForCorruptionAndStaleDeletionWatermark",
        "managed recovery point did not complete an isolated restore verification",
        "managed recovery activation receipt did not survive manager restart",
    ):
        require(marker in ios_test, f"iOS recovery test covers {marker}", checks)

    for marker in (
        "LocalRecoveryPointAvailability",
        "LocalRecoveryActivationPlan",
        "repository.restoreLocalRecoveryCandidate(source, candidateDirectory)",
        "preparedStatus.backupId == candidate.sourceManifest.backupId",
        "authoritativeWatermarks = repository.deletionWatermarks()",
        "recordSuccessfulActivation",
        "receipt.productionRecoveryClaim",
        "last-activation-v1.txt",
        "removeCoreStagingDirectories",
        "Recovery point root contains an unknown artifact",
        "Only the exact recovery point root may be cleared",
    ):
        require(marker in android_point, f"Android recovery-point manager declares {marker}", checks)
    for marker in (
        "createPrepareReceiptRestartAndClearRemainBounded",
        "staleWatermarkCorruptionAndUnknownArtifactFailClosed",
        "LocalRecoveryPointAvailability.Unavailable",
        "manager.prepareActivation(repository)",
        "manager.recordSuccessfulActivation",
        'File(root, "not-owned.txt")',
    ):
        require(marker in android_point_test, f"Android recovery-point JVM covers {marker}", checks)
    for marker in (
        "LocalRecoveryPointAvailability",
        "store.restoreLocalRecoveryCandidate(",
        "preparedStatus.backupID == candidate.sourceManifest.backupID",
        "LocalRecoveryActivationAuthorization(",
        "receipt.productionRecoveryClaim",
        "last-activation-v1.json",
        "removeCoreStagingDirectories",
        "values.isSymbolicLink != true",
        "allowRoot: true",
    ):
        require(marker in ios_point, f"iOS recovery-point manager declares {marker}", checks)

    for marker in (
        'File(appContext.noBackupFilesDir, "ameme-local-recovery-v1")',
        "manuallyClosedRepositories",
        "runtime::closeAndAwait",
        "localRecoveryPointManager.prepareActivation",
        "ioExecutor.close(localRepository)",
        "LocalRecoveryActivationCoordinator(keyProvider).activate",
        "LocalMemoryRepository.open(",
        "localRecoveryPointManager.recordSuccessfulActivation",
        "localRecoveryPointManager.clear()",
        "local-recovery-notice",
    ):
        require(marker in android_app + android_settings, f"Android ordinary-user recovery wires {marker}", checks)
    for marker in (
        "同安装恢复",
        "完整性与隔离候选恢复已通过",
        "最近成功恢复",
        "输入“恢复”以确认",
        "卸载、换机或设备丢失后不能使用",
        "不等同云备份",
        "恢复点之后新增或修改的本机记录会被替换",
    ):
        require(marker in android_settings, f"Android recovery UI discloses {marker}", checks)
    for marker in (
        "settingsLocalRecovery_requiresExactConfirmationAndReopensProductionStore",
        "local-recovery-verified",
        "assertIsNotEnabled",
        'performTextInput("恢复")',
        "本机记录已恢复到恢复点",
    ):
        require(marker in android_ui_test, f"Android recovery UI test covers {marker}", checks)

    for marker in (
        'appendingPathComponent("AmemeLocalRecovery"',
        "This must remain a sibling of the live",
        "localRecoveryPointManager.refresh(using: store)",
        "localRecoveryPointManager.create(using: store)",
        "localRecoveryPointManager.activate(using: store)",
        "localRecoveryPointManager.clear()",
    ):
        require(marker in ios_app, f"iOS ordinary-user recovery wires {marker}", checks)
    for marker in (
        'Section("同安装恢复")',
        "完整性与隔离候选恢复已通过",
        "最近成功恢复",
        "输入“恢复”以确认",
        "卸载、换机或设备丢失后不能使用",
        "不等同云备份",
        "恢复点之后新增或修改的本机记录会被替换",
    ):
        require(marker in ios_app, f"iOS recovery UI discloses {marker}", checks)

    for source in ("LocalBackupStore.swift", "LocalBackupStoreTests.swift"):
        require(source in project, f"iOS project includes {source}", checks)

    require(
        "AndroidKeystorePendingActionStore" not in android,
        "Android recovery implementation does not reuse pending-action storage",
        checks,
    )
    require(
        "PendingExportStore" not in ios,
        "iOS recovery implementation does not reuse pending-export storage",
        checks,
    )
    require(
        "StructuredExport" not in android,
        "Android recovery implementation does not masquerade structured export as backup",
        checks,
    )
    require(
        "exportData(" not in IOS_BACKUP.read_text(encoding="utf-8"),
        "iOS recovery implementation does not masquerade structured export as backup",
        checks,
    )
    require(
        "productionRecoveryClaim: Boolean = false"
        in ANDROID_REPOSITORY.read_text(encoding="utf-8"),
        "Android local recovery claim is hard-coded false",
        checks,
    )
    require(
        "productionRecoveryClaim: false" in IOS_BACKUP.read_text(encoding="utf-8"),
        "iOS local recovery claim is hard-coded false",
        checks,
    )
    require(
        "productionRecoveryClaim: true" not in android,
        "Android activation cannot opt into a production recovery claim",
        checks,
    )
    require(
        "productionRecoveryClaim: true" not in ios,
        "iOS activation cannot opt into a production recovery claim",
        checks,
    )
    require(
        "productionRecoveryClaim: true" not in android_point + ios_point,
        "ordinary-user recovery managers cannot opt into a production recovery claim",
        checks,
    )
    require(
        "ThisDeviceOnly" in IOS_STORE.read_text(encoding="utf-8"),
        "iOS local encryption key remains device-bound",
        checks,
    )
    require(
        "AndroidKeyStore" in ANDROID_KEY_PROVIDER.read_text(encoding="utf-8"),
        "Android local database key remains Keystore-bound",
        checks,
    )

    tracked_inputs = (
        ANDROID_BACKUP.relative_to(ROOT),
        ANDROID_ACTIVATION.relative_to(ROOT),
        ANDROID_POINT_MANAGER.relative_to(ROOT),
        ANDROID_AUDIT_MERGE.relative_to(ROOT),
        ANDROID_AUDIT_PLAN.relative_to(ROOT),
        ANDROID_REPOSITORY.relative_to(ROOT),
        ANDROID_TEST.relative_to(ROOT),
        ANDROID_POINT_TEST.relative_to(ROOT),
        ANDROID_UI_TEST.relative_to(ROOT),
        IOS_BACKUP.relative_to(ROOT),
        IOS_POINT_MANAGER.relative_to(ROOT),
        IOS_TEST.relative_to(ROOT),
    )
    ignored = [
        subprocess.run(
            ["git", "check-ignore", "--quiet", str(path)],
            cwd=ROOT,
            check=False,
        ).returncode
        for path in tracked_inputs
    ]
    require(
        ignored == [1] * len(tracked_inputs),
        "mobile recovery sources and tests are not ignored by Git",
        checks,
    )

    print(
        json.dumps(
            {
                "ok": True,
                "checks": len(checks),
                "scope": "static_cross_platform_user_visible_same_install_recovery_with_rollback_activation",
                "production_recovery_claim": False,
                "user_visible_same_install_recovery_claim": True,
                "cross_device_recovery_claim": False,
                "android_agent_audit_monotonic_merge_claim": True,
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
