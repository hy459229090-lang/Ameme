# Purpose: keep Android production Agent access audit durable, content-free, bounded, and owner-visible.
# Input: Android audit policy/persistence/runtime/UI, migrations, regressions, and current evidence docs.
# Output: static JSON checks; this gate claims no iOS Host audit, real user, physical device, or release pass.

from __future__ import annotations

import json
from pathlib import Path
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[2]
FILES = {
    "model": ROOT
    / "apps/android/app/src/main/java/com/ameme/android/data/AgentAccessAuditRepository.kt",
    "recovery_plan": ROOT
    / "apps/android/app/src/main/java/com/ameme/android/data/AgentAccessAuditRecovery.kt",
    "persistence": ROOT
    / "apps/android/app/src/main/java/com/ameme/android/data/local"
    / "LocalAgentAccessAuditPersistence.kt",
    "database": ROOT
    / "apps/android/app/src/main/java/com/ameme/android/data/local/LocalEventDatabase.kt",
    "recovery_merge": ROOT
    / "apps/android/app/src/main/java/com/ameme/android/data/local"
    / "LocalRecoveryAgentAccessAudit.kt",
    "recovery_activation": ROOT
    / "apps/android/app/src/main/java/com/ameme/android/data/local"
    / "LocalRecoveryActivation.kt",
    "repository": ROOT
    / "apps/android/app/src/main/java/com/ameme/android/data/local/LocalMemoryRepository.kt",
    "audit_transport": ROOT
    / "apps/android/app/src/main/java/com/ameme/android/data/transport/AgentAccessAudit.kt",
    "endpoint": ROOT
    / "apps/android/app/src/main/java/com/ameme/android/data/transport"
    / "MemoryRepositoryAgentLocalNodeEndpoint.kt",
    "runtime": ROOT
    / "apps/android/app/src/main/java/com/ameme/android/data/transport/AgentLocalNodeRuntime.kt",
    "settings": ROOT
    / "apps/android/app/src/main/java/com/ameme/android/ui/screens/SettingsScreen.kt",
    "app": ROOT / "apps/android/app/src/main/java/com/ameme/android/ui/AmemeApp.kt",
    "policy_test": ROOT
    / "apps/android/app/src/test/java/com/ameme/android/data"
    / "AgentAccessAuditRepositoryTest.kt",
    "endpoint_test": ROOT
    / "apps/android/app/src/test/java/com/ameme/android/data/transport"
    / "MemoryRepositoryAgentLocalNodeEndpointTest.kt",
    "database_test": ROOT
    / "apps/android/app/src/androidTest/java/com/ameme/android/data/local"
    / "LocalAgentAccessAuditInstrumentedTest.kt",
    "recovery_test": ROOT
    / "apps/android/app/src/androidTest/java/com/ameme/android/data/local"
    / "RecoveryBackupInstrumentedTest.kt",
    "runtime_test": ROOT
    / "apps/android/app/src/androidTest/java/com/ameme/android/data/transport"
    / "AgentLocalNodeEndpointInstrumentedTest.kt",
    "ui_test": ROOT
    / "apps/android/app/src/androidTest/java/com/ameme/android/AmemeUiSmokeTest.kt",
    "current": ROOT / "docs/_CURRENT.md",
    "privacy": ROOT / "docs/privacy-security/MVP数据分类保留与用户权利.md",
    "report": ROOT
    / "docs/quality/P0-Android生产Agent-访问审计与只读投影-20260729.md",
}


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
    for label, path in FILES.items():
        require(path.is_file(), f"{label} file exists: {path.relative_to(ROOT)}", checks)
    texts = {
        label: path.read_text(encoding="utf-8")
        for label, path in FILES.items()
    }

    require_markers(
        texts["model"],
        (
            "data class AgentAccessAuditRecord",
            "enum class AgentAccessAuditPhase",
            "Started",
            "Completed",
            "object AgentAccessAuditPolicy",
            "Duration.ofDays(180)",
            "MAX_RECENT_RECORDS = 100",
            "MAX_RETAINED_RECORDS = 50_000",
            "retentionUntil == occurredAt.plus(AgentAccessAuditPolicy.retention)",
        ),
        "content-free audit model and centralized policy",
        checks,
    )
    record_fields = texts["model"].split(
        "data class AgentAccessAuditRecord(", 1
    )[1].split(") {", 1)[0]
    for forbidden in (
        "content",
        "query",
        "payload",
        "idempotency",
        "objectId",
        "locator",
        "rawPath",
        "secret",
        "grantId",
        "requestId",
        "modelInput",
        "exception",
    ):
        require(
            forbidden not in record_fields,
            f"audit record excludes {forbidden}",
            checks,
        )

    require_markers(
        texts["persistence"],
        (
            "SQLCipher-backed append-only Agent access audit",
            "pruneExpired(record.occurredAt)",
            "MAX_RETAINED_RECORDS - 2",
            "requireMatchingStart(record)",
            "pruneExpired(at)",
            "ORDER BY occurred_at DESC, audit_id DESC",
            "limit in 1..AgentAccessAuditPolicy.MAX_RECENT_RECORDS",
            "retention_until <= ?",
            "spacesJson == canonicalArray(completed.spaces)",
        ),
        "SQLCipher audit persistence",
        checks,
    )
    require_markers(
        texts["recovery_plan"] + texts["recovery_merge"] + texts["recovery_activation"],
        (
            "AgentAccessAuditRecoveryPlanner",
            "candidateRetainedRecords",
            "Recovery Agent access audit union exceeds capacity",
            "Recovery Agent access audit trace-phase conflict",
            "mergeIntoStagedCandidate",
            "PRAGMA journal_mode=DELETE",
            "mergedSnapshotSha256",
            "verifyRetainedEvidence",
            "AgentAccessAuditMerged",
            "deleteStagedArtifacts",
        ),
        "same-install recovery keeps the security ledger monotonic",
        checks,
    )
    require_markers(
        texts["database"],
        (
            "const val SCHEMA_VERSION = 14",
            "const val USER_CONFIRMATION_SCHEMA_VERSION = 13",
            "migrateV13ToV14",
            "migrate_v13_to_v14_agent_access_audit",
            "CREATE TABLE IF NOT EXISTS ${LocalAgentAccessAuditPersistence.TABLE}",
            "UNIQUE(trace_id, phase)",
            "agent_access_audit_no_update",
            "agent_access_audit_no_early_delete",
        ),
        "v14 encrypted schema and migration",
        checks,
    )
    audit_schema = texts["database"].split(
        "private fun createAgentAccessAuditTables", 1
    )[1].split("private fun createCurrentTableV3", 1)[0]
    for forbidden_column in (
        "content TEXT",
        "query TEXT",
        "payload",
        "digest",
        "object_id",
        "locator",
        "raw_path",
        "secret",
        "grant_id",
        "request_id",
        "error_text",
    ):
        require(
            forbidden_column not in audit_schema,
            f"audit schema excludes {forbidden_column}",
            checks,
        )

    require_markers(
        texts["repository"],
        (
            "AgentAccessAuditRepository",
            "recentAgentAccessAudit",
            "pruneExpiredAgentAccessAudit",
            "durableAgentAccessAuditSink",
            "database.appendAgentAccessAudit(it.startedRecord())",
            "attempt.completedRecord(resultCode, objectCount, at)",
        ),
        "production repository audit projection and sink",
        checks,
    )
    require_markers(
        texts["audit_transport"],
        (
            "interface AgentAccessAuditSink",
            "fun begin(control: AgentLocalNodeControl",
            "fun complete(",
            "fun from(control: AgentLocalNodeControl",
            "objectCountBucket = AgentAccessAuditObjectCountBucket.fromCount(objectCount)",
        ),
        "request audit lifecycle",
        checks,
    )
    require_markers(
        texts["endpoint"],
        (
            "sink.begin(control, clock.instant())",
            "executeRequest(request)",
            "sink.complete(",
            "RESULT_CANCELLED",
            "outcome.response.close()",
            "AgentLocalNodeErrorCode.TEMPORARILY_UNAVAILABLE",
            "successfulObjectCount = result.events.size",
            "accessAuditSink: AgentAccessAuditSink",
        ),
        "fail-closed audited endpoint",
        checks,
    )
    require(
        texts["endpoint"].index("sink.begin(control, clock.instant())")
        < texts["endpoint"].index("executeRequest(request)"),
        "audit STARTED persists before repository request execution",
        checks,
    )
    require(
        "accessAuditSink = repository.durableAgentAccessAuditSink()"
        in texts["runtime"],
        "production runtime injects durable audit before serving operations",
        checks,
    )

    require_markers(
        texts["settings"] + texts["app"],
        (
            "Agent 访问记录",
            "安全审计保留 180 天",
            "agent-access-audit-card",
            "record.callerId",
            "record.purpose",
            "record.spaces",
            "record.dataTypes",
            "record.resultCode",
            "record.occurredAt",
            "pruneExpiredAgentAccessAudit(at)",
            "recentAgentAccessAudit(limit = 20, at = at)",
            "未改用合成记录",
        ),
        "owner-visible bounded Settings projection",
        checks,
    )
    require_markers(
        texts["policy_test"] + texts["endpoint_test"],
        (
            "objectCountsUseBoundedNonIdentifyingBuckets",
            "recordRequiresCanonicalScopeExactRetentionAndValidPhasePair",
            "accessAuditRecordsContentFreeSuccessAndAuthorizationFailure",
            "accessAuditFailureClosesBeforeMutationAndCompletionRetryRemainsIdempotent",
            "assertFalse(auditProjection.contains(payload.content))",
            "AgentAccessAuditPhase.Started",
            "AgentAccessAuditObjectCountBucket.Zero",
            "recoveryPlannerKeepsCandidateEvidenceAndAddsPostBackupCompletion",
            "recoveryPlannerFailsClosedOnTracePhaseConflictOrExpiredLiveEvidence",
            "recoveryPlannerIgnoresExpiredCandidateEvidenceForConflictsAndCapacity",
        ),
        "JVM policy and negative regressions",
        checks,
    )
    require_markers(
        texts["database_test"] +
        texts["recovery_test"] +
        texts["runtime_test"] +
        texts["ui_test"],
        (
            "contentFreeAuditPersistsAppendOnlyAndPrunesOnlyExpiredRows",
            "v13MigrationCreatesAuditSchemaWithoutSyntheticRows",
            "assertSqlRejected",
            "recentAgentAccessAudit(limit = 10",
            "assertFalse(audit.joinToString().contains(CONTENT))",
            "settingsAgentAudit_explainsContentFreeRetentionAndUsesProductionProjection",
            "activationPreservesPostBackupAgentAuditAndMergeFailureLeavesLiveLedger",
            "preparedRecoveryRemovesOrphanedAuditMergeSidecarsBeforeRestoringLive",
        ),
        "SQLCipher, production runtime, and UI test sources",
        checks,
    )

    docs = texts["current"] + texts["privacy"] + texts["report"]
    require_markers(
        docs,
        (
            "Android schema v14",
            "180 天",
            "STARTED",
            "COMPLETED",
            "不记录正文",
            "物理设备",
            "真实用户",
            "iOS",
            "hold",
        ),
        "current evidence boundary",
        checks,
    )
    for forbidden_claim in (
        "realUserExecutionClaim=true",
        "physicalDeviceExecutionClaim=true",
        "iosHostAuditClaim=true",
        "sharedAccountAuditClaim=true",
        "releaseClaim=true",
    ):
        require(
            forbidden_claim not in docs,
            f"slice does not assert {forbidden_claim}",
            checks,
        )

    ignored = [
        subprocess.run(
            ["git", "check-ignore", "--quiet", str(path.relative_to(ROOT))],
            cwd=ROOT,
            check=False,
        ).returncode
        for path in FILES.values()
    ]
    require(ignored == [1] * len(ignored), "Agent audit sources are tracked by Git", checks)

    print(
        json.dumps(
            {
                "ok": True,
                "checks": len(checks),
                "scope": "static_android_agent_access_audit",
                "android_sqlcipher_schema": 14,
                "retention_days": 180,
                "content_values_persisted_claim": False,
                "fail_closed_before_mutation_claim": True,
                "owner_visible_projection_claim": True,
                "same_install_recovery_monotonic_audit_claim": True,
                "ios_host_audit_claim": False,
                "real_user_execution_claim": False,
                "physical_device_execution_claim": False,
                "shared_account_audit_claim": False,
                "release_claim": False,
            },
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (AssertionError, IndexError, ValueError) as error:
        print(json.dumps({"ok": False, "error": str(error)}, indent=2))
        sys.exit(1)
