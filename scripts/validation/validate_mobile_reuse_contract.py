# Purpose: keep Android/iOS reuse scenarios and content-free telemetry boundaries aligned.
# Input: production reuse models, encrypted persistence, tests, smoke, and generated iOS project.
# Output: static JSON checks; this gate does not claim real-user usefulness or device execution.

from __future__ import annotations

import json
from pathlib import Path
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[2]
ANDROID_MODEL = ROOT / "apps/android/app/src/main/java/com/ameme/android/data/ReuseRepository.kt"
ANDROID_STORE = (
    ROOT / "apps/android/app/src/main/java/com/ameme/android/data/local/LocalEventDatabase.kt"
)
ANDROID_PERSISTENCE = ANDROID_STORE.with_name("LocalReusePersistence.kt")
ANDROID_ADAPTER = ANDROID_STORE.with_name("LocalMemoryRepository.kt")
ANDROID_TEST = (
    ROOT
    / "apps/android/app/src/androidTest/java/com/ameme/android/data/local"
    / "LocalReusePersistenceInstrumentedTest.kt"
)
ANDROID_CONTRACT_TEST = (
    ROOT / "apps/android/app/src/test/java/com/ameme/android/data/ReuseRepositoryContractTest.kt"
)
ANDROID_SEARCH = ROOT / "apps/android/app/src/main/java/com/ameme/android/ui/screens/SearchScreen.kt"
ANDROID_DIALOG = ANDROID_SEARCH.with_name("ReuseJourneyDialog.kt")
ANDROID_UI_TEST = (
    ROOT / "apps/android/app/src/androidTest/java/com/ameme/android/AmemeUiSmokeTest.kt"
)
IOS_MODEL = ROOT / "apps/ios/Ameme/Shared/ReuseContext.swift"
IOS_STORE = IOS_MODEL.with_name("LocalMemoryStore.swift")
IOS_JOURNEY = IOS_MODEL.with_name("ReuseJourney.swift")
IOS_TEST = ROOT / "apps/ios/Tests/AmemeSharedTests/ReuseContextTests.swift"
IOS_SMOKE = ROOT / "apps/ios/Smoke/main.swift"
IOS_APP = ROOT / "apps/ios/AmemeApp/AmemeApp.swift"
IOS_UI_TEST = ROOT / "apps/ios/UITests/AmemeUITests.swift"
IOS_PROJECT = ROOT / "apps/ios/Ameme.xcodeproj/project.pbxproj"
ANDROID_AGENT = ROOT / "apps/android/app/src/main/java/com/ameme/android/data/transport"
IOS_AGENT = ROOT / "apps/ios/Ameme/Shared/AgentLocalNodeChannel.swift"


def require(condition: bool, message: str, checks: list[str]) -> None:
    if not condition:
        raise AssertionError(message)
    checks.append(message)


def between(value: str, start: str, end: str) -> str:
    start_index = value.index(start)
    end_index = value.index(end, start_index + len(start))
    return value[start_index:end_index]


def main() -> int:
    checks: list[str] = []
    paths = (
        ANDROID_MODEL,
        ANDROID_STORE,
        ANDROID_PERSISTENCE,
        ANDROID_ADAPTER,
        ANDROID_TEST,
        ANDROID_CONTRACT_TEST,
        ANDROID_SEARCH,
        ANDROID_DIALOG,
        ANDROID_UI_TEST,
        IOS_MODEL,
        IOS_STORE,
        IOS_JOURNEY,
        IOS_TEST,
        IOS_SMOKE,
        IOS_APP,
        IOS_UI_TEST,
        IOS_PROJECT,
        IOS_AGENT,
    )
    for path in paths:
        require(path.is_file(), f"required file exists: {path.relative_to(ROOT)}", checks)

    android_model = ANDROID_MODEL.read_text(encoding="utf-8")
    android_store = ANDROID_STORE.read_text(encoding="utf-8")
    android_persistence = ANDROID_PERSISTENCE.read_text(encoding="utf-8")
    android_adapter = ANDROID_ADAPTER.read_text(encoding="utf-8")
    android_tests = (
        ANDROID_TEST.read_text(encoding="utf-8")
        + ANDROID_CONTRACT_TEST.read_text(encoding="utf-8")
    )
    android_ui = (
        ANDROID_SEARCH.read_text(encoding="utf-8")
        + ANDROID_DIALOG.read_text(encoding="utf-8")
    )
    android_ui_test = ANDROID_UI_TEST.read_text(encoding="utf-8")
    ios_model = IOS_MODEL.read_text(encoding="utf-8")
    ios_store = IOS_STORE.read_text(encoding="utf-8")
    ios_journey = IOS_JOURNEY.read_text(encoding="utf-8")
    ios_tests = IOS_TEST.read_text(encoding="utf-8") + IOS_SMOKE.read_text(encoding="utf-8")
    ios_ui = IOS_APP.read_text(encoding="utf-8")
    ios_ui_tests = IOS_UI_TEST.read_text(encoding="utf-8")
    project = IOS_PROJECT.read_text(encoding="utf-8")

    scenarios = (
        ("HistoricalSearch", "historicalSearch", "historical_search"),
        ("ProjectResume", "projectResume", "project_resume"),
        ("PreMeetingContext", "preMeetingContext", "pre_meeting_context"),
        (
            "DecisionCommitmentRecall",
            "decisionCommitmentRecall",
            "decision_commitment_recall",
        ),
    )
    for android_name, ios_name, wire_name in scenarios:
        require(android_name in android_model, f"Android declares reuse intent {wire_name}", checks)
        require(ios_name in ios_model, f"iOS declares reuse intent {wire_name}", checks)
        require(wire_name in android_model, f"Android freezes reuse wire value {wire_name}", checks)
        require(wire_name in ios_model, f"iOS freezes reuse wire value {wire_name}", checks)

    guardrails = (
        ("WrongMemory", "wrongMemory", "wrong_memory"),
        ("ImportantMiss", "importantMiss", "important_miss"),
        ("Outdated", "outdated", "outdated"),
        ("PermissionDenied", "permissionDenied", "permission_denied"),
        ("DeletionFailure", "deletionFailure", "deletion_failure"),
        ("RecoveryFailure", "recoveryFailure", "recovery_failure"),
        (
            "RestrictedEgressBlocked",
            "restrictedEgressBlocked",
            "restricted_egress_blocked",
        ),
    )
    for android_name, ios_name, wire_name in guardrails:
        require(android_name in android_model, f"Android declares guardrail {wire_name}", checks)
        require(ios_name in ios_model, f"iOS declares guardrail {wire_name}", checks)

    for marker in (
        "reuse_attempts",
        "reuse_outcomes",
        "migrate_v9_to_v10_reuse_telemetry",
        "const val SCHEMA_VERSION = 13",
        "const val FIELD_EVIDENCE_SCHEMA_VERSION = 12",
        "const val REUSE_SCHEMA_VERSION = 10",
        'listOf("reuse_attempts", "reuse_outcomes")',
        "${table}_no_update",
        "${table}_no_delete",
        "buildReuseContext",
        "revalidateReuseContext",
        "resolveReuseContext",
        "ResolvedReuseItem",
        "helpfulReuseCount",
    ):
        require(marker in android_store, f"Android reuse persistence declares {marker}", checks)
    for marker in (
        "ReuseRepository",
        "override fun buildReuseContext",
        "override fun resolveReuseContext",
        "override fun recordReuseOutcome",
        "override fun helpfulReuseCount",
    ):
        require(marker in android_adapter, f"Android production adapter declares {marker}", checks)
    for marker in (
        "currentSchemaVersion = 8",
        "LegacyLocalStoreEnvelopeV6",
        "reuseAttempts",
        "reuseOutcomes",
        "buildReuseContext",
        "revalidateReuseContext",
        "resolveReuseContext",
        "ResolvedReuseItem",
        "helpfulReuseCount",
        "ReuseTelemetry.attempt",
    ):
        require(marker in ios_store, f"iOS encrypted store declares {marker}", checks)

    for marker in (
        "MessageDigest.getInstance(\"SHA-256\")",
        "context.attemptId",
        "event_ref_digests",
        "memory_ref_digests",
        "lineage_digests",
        "result_count_bucket",
    ):
        require(marker in android_persistence, f"Android telemetry declares {marker}", checks)
    for marker in (
        "SHA256.hash",
        "context.attemptID.uuidString",
        "eventReferenceDigests",
        "memoryReferenceDigests",
        "lineageDigests",
        "resultCountBucket",
    ):
        require(marker in ios_model, f"iOS telemetry declares {marker}", checks)

    android_table = between(
        android_store,
        "CREATE TABLE IF NOT EXISTS reuse_attempts",
        '""".trimIndent(),',
    )
    ios_record = between(
        ios_model,
        "public struct ReuseAttemptRecord",
        "public struct ReuseOutcomeRecord",
    )
    forbidden_persistence_names = (
        "query",
        "title",
        "detail",
        "user_words",
        "value_summary",
        "source_locator",
        "object_id",
        "source_event_id",
        "prompt",
        "full_path",
        "contact",
        "health_value",
    )
    for forbidden in forbidden_persistence_names:
        require(
            forbidden.lower() not in android_table.lower(),
            f"Android reuse attempt schema excludes {forbidden}",
            checks,
        )
        require(
            forbidden.lower() not in ios_record.lower(),
            f"iOS reuse attempt record excludes {forbidden}",
            checks,
        )

    for marker in (
        "project resume requires explicit user keywords",
        "pre-meeting context requires explicit keywords or a user-selected date",
        "Sensitivity.Restricted",
        "REUSE_CONTEXT_TTL_SECONDS",
        "memory.isVisible(at)",
        "source.revision != memory.sourceEventRevision",
    ):
        require(marker in android_model + android_store, f"Android reuse policy enforces {marker}", checks)
    for marker in (
        "project resume requires explicit user keywords",
        "pre-meeting context requires keywords or a user-selected date",
        ".restricted",
        "addingTimeInterval(15 * 60)",
        "memory.isVisible(at: date)",
        "source.revision == memory.sourceEventRevision",
    ):
        require(marker in ios_model + ios_store, f"iOS reuse policy enforces {marker}", checks)

    for marker in (
        "fourReuseJourneysAreBoundedRevalidatedAndPersistOnlyContentFreeTelemetry",
        "v9MigratesThroughCurrentSchemaWithoutChangingExistingEvent",
        "assertFalse(persisted.contains(queryCanary))",
        "ReuseExclusion.Expired",
        "ReuseExclusion.Invalidated",
        "resolvedContexts",
        "memorySummary",
    ):
        require(marker in android_tests, f"Android reuse tests cover {marker}", checks)
    for marker in (
        "testFourReuseJourneysRevalidateAndPersistOnlyContentFreeTelemetry",
        "testProjectAndMeetingScopesAreExplicit",
        "testSharedJourneyControllerBuildsResolvedContextAndRecordsFeedback",
        "resolvedReuseContexts",
        "!reuseTelemetryJSON.contains(reuseCanary)",
        "four bounded reuse journeys/content-free telemetry/revision revalidation",
        ".expired",
        ".invalidated",
    ):
        require(marker in ios_tests, f"iOS reuse tests/smoke cover {marker}", checks)

    for marker in (
        '"历史找回" to ReuseIntent.HistoricalSearch',
        '"继续项目" to ReuseIntent.ProjectResume',
        '"准备会面" to ReuseIntent.PreMeetingContext',
        '"决定与承诺" to ReuseIntent.DecisionCommitmentRecall',
        "buildAndResolveReuseContext",
        "ReuseOutcome.WrongMemory",
        "ReuseOutcome.ImportantMiss",
        "ReuseOutcome.Outdated",
        "userAction = ReuseUserAction.None",
        "反馈不包含正文、搜索词或原始对象 ID",
        "ReuseJourneyLauncher",
        "ReuseIntentPickerDialog",
        'testTag("reuse-journey-launcher")',
    ):
        require(marker in android_ui, f"Android user-visible reuse flow declares {marker}", checks)
    for marker in (
        'onNodeWithTag("reuse-journey-launcher")',
        'onNodeWithText("历史找回")',
        'onNodeWithText("继续项目")',
        'onNodeWithText("准备会面")',
        'onNodeWithText("决定与承诺")',
    ):
        require(marker in android_ui_test, f"Android UI test exercises {marker}", checks)

    for marker in (
        "ReuseJourneyController",
        "ReuseJourneyStatus",
        "store.buildReuseContext",
        "store.resolveReuseContext",
        "submitFeedback",
        "A non-none user action must be supplied only after",
    ):
        require(marker in ios_journey, f"iOS Shared reuse flow declares {marker}", checks)

    for marker in (
        'accessibilityIdentifier("search.reuse")',
        "ForEach(ReuseIntent.allCases",
        "reuseController.start",
        "ReuseJourneySheet",
        'accessibilityIdentifier("reuse.result.\\(index)")',
        'accessibilityIdentifier("reuse.feedback.\\(outcome.rawValue)")',
        "反馈只记录结果类型和动作，不记录正文、搜索词或原始对象 ID",
    ):
        require(marker in ios_ui, f"iOS user-visible reuse flow declares {marker}", checks)
    for marker in (
        'app.buttons["search.reuse"]',
        'app.buttons["历史找回（按当前筛选）"]',
        'app.buttons["reuse.result.0"]',
        'app.buttons["reuse.feedback.useful"]',
        '"08-real-local-reuse"',
    ):
        require(marker in ios_ui_tests, f"iOS UI test exercises {marker}", checks)

    for source in ("ReuseContext.swift", "ReuseJourney.swift", "ReuseContextTests.swift"):
        require(source in project, f"iOS project includes {source}", checks)

    android_agent_text = "\n".join(
        path.read_text(encoding="utf-8")
        for path in ANDROID_AGENT.rglob("*.kt")
    )
    ios_agent_text = IOS_AGENT.read_text(encoding="utf-8")
    for forbidden in ("ReuseRepository", "ReuseContext", "buildReuseContext"):
        require(
            forbidden not in android_agent_text,
            f"Android Agent Local Node remains free of {forbidden}",
            checks,
        )
        require(
            forbidden not in ios_agent_text,
            f"iOS Agent Local Node remains free of {forbidden}",
            checks,
        )

    tracked_inputs = (
        ANDROID_MODEL,
        ANDROID_PERSISTENCE,
        ANDROID_TEST,
        ANDROID_CONTRACT_TEST,
        ANDROID_UI_TEST,
        IOS_MODEL,
        IOS_JOURNEY,
        IOS_TEST,
        IOS_APP,
        IOS_UI_TEST,
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
        "mobile reuse sources and tests are not ignored by Git",
        checks,
    )

    print(
        json.dumps(
            {
                "ok": True,
                "checks": len(checks),
                "scope": "static_cross_platform_local_reuse_and_content_free_telemetry",
                "real_user_helpfulness_claim": False,
                "device_execution_claim": False,
                "agent_read_protocol_claim": False,
            },
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (AssertionError, ValueError) as error:
        print(json.dumps({"ok": False, "error": str(error)}, indent=2))
        sys.exit(1)
