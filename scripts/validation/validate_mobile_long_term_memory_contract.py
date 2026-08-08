# Purpose: keep Android and iOS production long-term Memory safety boundaries aligned.
# Input: native models, encrypted persistence adapters, Event mutation wiring, tests, and iOS project.
# Output: content-free JSON checks; this static gate does not claim SQLCipher, XCTest, or device execution.

from __future__ import annotations

import json
from pathlib import Path
import re
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[2]
ANDROID_MODEL = ROOT / "apps/android/app/src/main/java/com/ameme/android/data/LongTermMemoryRepository.kt"
ANDROID_PERSISTENCE = (
    ROOT / "apps/android/app/src/main/java/com/ameme/android/data/local/LocalLongTermMemoryPersistence.kt"
)
ANDROID_DATABASE = ANDROID_PERSISTENCE.with_name("LocalEventDatabase.kt")
ANDROID_REPOSITORY = ANDROID_PERSISTENCE.with_name("LocalMemoryRepository.kt")
ANDROID_AGENT_ENDPOINT = (
    ROOT
    / "apps/android/app/src/main/java/com/ameme/android/data/transport"
    / "MemoryRepositoryAgentLocalNodeEndpoint.kt"
)
ANDROID_TEST = (
    ROOT
    / "apps/android/app/src/test/java/com/ameme/android/data"
    / "LongTermMemoryRepositoryContractTest.kt"
)
ANDROID_INSTRUMENTED_TEST = (
    ROOT
    / "apps/android/app/src/androidTest/java/com/ameme/android/data/local"
    / "LocalLongTermMemoryInstrumentedTest.kt"
)
IOS_MODEL = ROOT / "apps/ios/Ameme/Shared/LongTermMemory.swift"
IOS_STORE = ROOT / "apps/ios/Ameme/Shared/LocalMemoryStore.swift"
IOS_TEST = ROOT / "apps/ios/Tests/AmemeSharedTests/LongTermMemoryTests.swift"
IOS_SMOKE = ROOT / "apps/ios/Smoke/main.swift"
IOS_PROJECT = ROOT / "apps/ios/Ameme.xcodeproj/project.pbxproj"

MEMORY_TYPES = {
    "fact",
    "decision",
    "commitment",
    "insight",
    "preference",
    "relationship",
    "health",
    "financial",
    "major_decision",
}
MEMORY_STATES = {
    "eligible_for_memory_compiler",
    "candidate_user_confirmation_required",
    "active",
    "invalidated",
    "superseded",
}
INVALIDATION_REASONS = {
    "event_revision_changed",
    "event_deleted",
}


def require(condition: bool, message: str, checks: list[str]) -> None:
    if not condition:
        raise AssertionError(message)
    checks.append(message)


def swift_declares(source: str, wire_value: str) -> bool:
    parts = wire_value.split("_")
    case_name = parts[0] + "".join(part.capitalize() for part in parts[1:])
    return (
        f'"{wire_value}"' in source
        or re.search(rf"\bcase\s+{re.escape(case_name)}\b", source) is not None
    )


def main() -> int:
    checks: list[str] = []
    paths = (
        ANDROID_MODEL,
        ANDROID_PERSISTENCE,
        ANDROID_DATABASE,
        ANDROID_REPOSITORY,
        ANDROID_AGENT_ENDPOINT,
        ANDROID_TEST,
        ANDROID_INSTRUMENTED_TEST,
        IOS_MODEL,
        IOS_STORE,
        IOS_TEST,
        IOS_SMOKE,
        IOS_PROJECT,
    )
    for path in paths:
        require(path.is_file(), f"required file exists: {path.relative_to(ROOT)}", checks)

    android = "\n".join(
        path.read_text(encoding="utf-8")
        for path in (ANDROID_MODEL, ANDROID_PERSISTENCE, ANDROID_DATABASE, ANDROID_REPOSITORY)
    )
    ios = "\n".join(path.read_text(encoding="utf-8") for path in (IOS_MODEL, IOS_STORE))
    android_test = "\n".join(
        path.read_text(encoding="utf-8") for path in (ANDROID_TEST, ANDROID_INSTRUMENTED_TEST)
    )
    ios_test = "\n".join(path.read_text(encoding="utf-8") for path in (IOS_TEST, IOS_SMOKE))
    project = IOS_PROJECT.read_text(encoding="utf-8")
    endpoint = ANDROID_AGENT_ENDPOINT.read_text(encoding="utf-8")

    for wire_value in sorted(MEMORY_TYPES | MEMORY_STATES | INVALIDATION_REASONS):
        require(f'"{wire_value}"' in android, f"Android declares long-term Memory wire value {wire_value}", checks)
        require(swift_declares(ios, wire_value), f"iOS declares long-term Memory wire value {wire_value}", checks)

    for marker in (
        "LongTermMemoryProposal",
        "LongTermMemoryConfirmation",
        "LongTermMemoryRecord",
        "visibleLongTermMemories",
        "requiresUserConfirmation",
    ):
        require(marker in android, f"Android exposes {marker}", checks)
        require(marker in ios, f"iOS exposes {marker}", checks)

    for marker in (
        "long_term_memories_current",
        "long_term_memory_revisions",
        "long_term_memory_event_dependencies",
        "prevent_long_term_memory_revisions_update",
        "prevent_long_term_memory_revisions_delete",
        "migrate_v7_to_v8_long_term_memory",
        "const val LONG_TERM_MEMORY_SCHEMA_VERSION = 8",
    ):
        require(marker in android, f"Android production persistence declares {marker}", checks)
    for marker in (
        "currentSchemaVersion = 8",
        "LegacyLocalStoreEnvelopeV2",
        "longTermMemories",
        "invalidateLongTermMemories",
        "isInternallyValid",
    ):
        require(marker in ios, f"iOS production persistence declares {marker}", checks)

    for marker in (
        "EventRevisionChanged",
        "EventDeleted",
        "invalidateForEvent",
        "user_confirm",
        "user_supersede",
    ):
        require(marker in android, f"Android wires conservative Memory transition {marker}", checks)
    for marker in (
        "eventRevisionChanged",
        "eventDeleted",
        "invalidateLongTermMemories",
        "userConfirm",
        "userSupersede",
    ):
        require(marker in ios, f"iOS wires conservative Memory transition {marker}", checks)

    require(
        "LongTermMemoryRepository" not in endpoint,
        "Android Agent endpoint remains separate from long-term Memory repository",
        checks,
    )
    require(
        "create_long_term_memory" not in endpoint,
        "Android Agent endpoint exposes no long-term Memory operation",
        checks,
    )
    require(
        '"day_ledgers"' not in ANDROID_PERSISTENCE.read_text(encoding="utf-8"),
        "Android long-term Memory persistence does not write day_ledgers",
        checks,
    )
    require(
        "events_fts" not in ANDROID_PERSISTENCE.read_text(encoding="utf-8"),
        "Android long-term Memory persistence does not write Event FTS",
        checks,
    )

    for marker in (
        "proposalNeedsExplicitConfirmationAndEventRevisionInvalidatesAfterReopen",
        "inferredAndSensitiveMemoriesStayCandidatesUntilUserConfirmationAndDeleteInvalidates",
        "explicitReplacementSupersedesOldMemoryWithoutReactivation",
        "v7DatabaseMigratesToV8WithoutChangingExistingEvent",
    ):
        require(marker in android_test, f"Android long-term Memory test covers {marker}", checks)
    for marker in (
        "testProposalConfirmationRevisionInvalidationAndNoResurrection",
        "testInferenceAndSensitiveTypesNeedConfirmationAndDeleteInvalidates",
        "testExplicitReplacementSupersedesOldMemory",
        "testV2EnvelopeMigratesAndInvalidV3FailsClosed",
        "explicit long-term Memory confirmation/revision invalidation/no resurrection",
    ):
        require(marker in ios_test, f"iOS long-term Memory test covers {marker}", checks)

    for source_name in ("LongTermMemory.swift", "LongTermMemoryTests.swift"):
        require(source_name in project, f"iOS project includes {source_name}", checks)

    tracked_inputs = (
        ANDROID_MODEL.relative_to(ROOT),
        ANDROID_PERSISTENCE.relative_to(ROOT),
        ANDROID_TEST.relative_to(ROOT),
        ANDROID_INSTRUMENTED_TEST.relative_to(ROOT),
        IOS_MODEL.relative_to(ROOT),
        IOS_TEST.relative_to(ROOT),
    )
    ignore_results = [
        subprocess.run(
            ["git", "check-ignore", "--quiet", str(path)],
            cwd=ROOT,
            check=False,
        ).returncode
        for path in tracked_inputs
    ]
    require(
        ignore_results == [1] * len(tracked_inputs),
        "mobile long-term Memory sources and tests are not ignored by Git",
        checks,
    )

    print(
        json.dumps(
            {
                "ok": True,
                "checks": len(checks),
                "memory_types": len(MEMORY_TYPES),
                "claims": "static_cross_platform_contract_only",
                "errors": [],
            },
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, OSError, ValueError) as error:
        print(json.dumps({"ok": False, "errors": [str(error)]}, indent=2))
        raise SystemExit(1)
