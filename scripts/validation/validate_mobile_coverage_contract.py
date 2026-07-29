# Purpose: keep Android and iOS production coverage models aligned with the shared v1 contract.
# Input: ameme-coverage JSON Schema plus the two native coverage implementations and unit-test sources.
# Output: content-free JSON checks; this static gate does not claim a platform build or device pass.

from __future__ import annotations

import json
from pathlib import Path
import re
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[2]
SCHEMA = ROOT / "packages" / "contracts" / "schemas" / "ameme-coverage.schema.json"
ANDROID_MODELS = (
    ROOT
    / "apps"
    / "android"
    / "app"
    / "src"
    / "main"
    / "java"
    / "com"
    / "ameme"
    / "android"
    / "coverage"
    / "CoverageModels.kt"
)
ANDROID_COMPILER = ANDROID_MODELS.with_name("CoverageCompiler.kt")
ANDROID_RUNTIME = ANDROID_MODELS.with_name("MobileCoverageRuntime.kt")
ANDROID_REPOSITORY = ROOT / "apps/android/app/src/main/java/com/ameme/android/data/CoverageRepository.kt"
ANDROID_PERSISTENCE = ROOT / "apps/android/app/src/main/java/com/ameme/android/data/local/LocalCoveragePersistence.kt"
ANDROID_EVENT_DATABASE = ANDROID_PERSISTENCE.with_name("LocalEventDatabase.kt")
ANDROID_TESTS = (
    ROOT
    / "apps"
    / "android"
    / "app"
    / "src"
    / "test"
    / "java"
    / "com"
    / "ameme"
    / "android"
    / "coverage"
    / "CoverageCompilerTest.kt"
)
ANDROID_PERSISTENCE_TESTS = ANDROID_TESTS.with_name("CoverageRepositoryContractTest.kt")
ANDROID_INSTRUMENTED_PERSISTENCE_TESTS = (
    ROOT
    / "apps"
    / "android"
    / "app"
    / "src"
    / "androidTest"
    / "java"
    / "com"
    / "ameme"
    / "android"
    / "data"
    / "local"
    / "LocalCoveragePersistenceInstrumentedTest.kt"
)
IOS_MODELS = ROOT / "apps" / "ios" / "Ameme" / "Shared" / "Coverage.swift"
IOS_RUNTIME = IOS_MODELS.with_name("MobileCoverageRuntime.swift")
IOS_PERSISTENCE = IOS_MODELS.with_name("CoveragePersistence.swift")
IOS_LOCAL_STORE = IOS_MODELS.with_name("LocalMemoryStore.swift")
IOS_TESTS = (
    ROOT
    / "apps"
    / "ios"
    / "Tests"
    / "AmemeSharedTests"
    / "CoverageCompilerTests.swift"
)
IOS_PERSISTENCE_TESTS = IOS_TESTS.with_name("CoveragePersistenceTests.swift")
IOS_PROJECT = ROOT / "apps" / "ios" / "Ameme.xcodeproj" / "project.pbxproj"

FORBIDDEN_PRECISION_MARKERS = {
    "coverage_percentage",
    "coveragePercentage",
    "estimated_user_count",
    "estimatedUserCount",
    "market_size",
    "marketSize",
}


def require(condition: bool, message: str, checks: list[str]) -> None:
    if not condition:
        raise AssertionError(message)
    checks.append(message)


def schema_wire_values(schema: dict) -> set[str]:
    definitions = schema["$defs"]
    values: set[str] = set()
    enum_paths = (
        ("ContextType", "enum"),
        ("EvidenceField", "enum"),
        ("ContextGapReason", "enum"),
    )
    for definition, key in enum_paths:
        values.update(definitions[definition][key])

    property_paths = (
        ("SourceCapability", "source_type"),
        ("SourceCapability", "acquisition_mode"),
        ("SourceCapability", "priority"),
        ("SourceCapability", "permission_tier"),
        ("SourceCapability", "bystander_risk"),
        ("SourceCapability", "validation_status"),
        ("SourceCapability", "hard_gates"),
        ("SyntheticSignal", "source_state"),
        ("SyntheticSignal", "fact_status"),
        ("CoverageObservation", "state"),
        ("ContextGap", "value_level"),
        ("ContextGap", "prompt_policy"),
        ("ContextGap", "state"),
    )
    for definition, property_name in property_paths:
        enum = definitions[definition]["properties"][property_name].get("enum")
        if enum is not None:
            values.update(enum)

    values.update(definitions["SyntheticSignal"]["properties"]["event_hint"]["properties"]["event_type"]["enum"])
    values.update(definitions["TimeRange"]["properties"]["precision"]["enum"])
    return values


def swift_case_name(wire_value: str) -> str:
    parts = wire_value.lower().split("_")
    return parts[0] + "".join(part.capitalize() for part in parts[1:])


def main() -> int:
    checks: list[str] = []
    paths = (
        SCHEMA,
        ANDROID_MODELS,
        ANDROID_COMPILER,
        ANDROID_RUNTIME,
        ANDROID_REPOSITORY,
        ANDROID_PERSISTENCE,
        ANDROID_EVENT_DATABASE,
        ANDROID_TESTS,
        ANDROID_PERSISTENCE_TESTS,
        ANDROID_INSTRUMENTED_PERSISTENCE_TESTS,
        IOS_MODELS,
        IOS_RUNTIME,
        IOS_PERSISTENCE,
        IOS_LOCAL_STORE,
        IOS_TESTS,
        IOS_PERSISTENCE_TESTS,
        IOS_PROJECT,
    )
    for path in paths:
        require(path.is_file(), f"required file exists: {path.relative_to(ROOT)}", checks)

    schema = json.loads(SCHEMA.read_text(encoding="utf-8"))
    android = (
        ANDROID_MODELS.read_text(encoding="utf-8")
        + ANDROID_COMPILER.read_text(encoding="utf-8")
        + ANDROID_RUNTIME.read_text(encoding="utf-8")
        + ANDROID_REPOSITORY.read_text(encoding="utf-8")
        + ANDROID_PERSISTENCE.read_text(encoding="utf-8")
        + ANDROID_EVENT_DATABASE.read_text(encoding="utf-8")
    )
    ios = (
        IOS_MODELS.read_text(encoding="utf-8")
        + IOS_RUNTIME.read_text(encoding="utf-8")
        + IOS_PERSISTENCE.read_text(encoding="utf-8")
        + IOS_LOCAL_STORE.read_text(encoding="utf-8")
    )
    android_tests = (
        ANDROID_TESTS.read_text(encoding="utf-8")
        + ANDROID_PERSISTENCE_TESTS.read_text(encoding="utf-8")
    )
    ios_tests = (
        IOS_TESTS.read_text(encoding="utf-8")
        + IOS_PERSISTENCE_TESTS.read_text(encoding="utf-8")
    )

    for wire_value in sorted(schema_wire_values(schema)):
        quoted = f'"{wire_value}"'
        require(quoted in android, f"Android declares coverage wire value {wire_value}", checks)
        swift_case = swift_case_name(wire_value)
        require(
            quoted in ios or re.search(rf"\b{re.escape(swift_case)}\b", ios) is not None,
            f"iOS declares coverage wire value {wire_value}",
            checks,
        )

    for marker in (
        "SourceCapabilityRegistry",
        "CoverageObservation",
        "CandidateEvent",
        "ContextGap",
        "CoverageCompiler",
        "unknownContextTypes",
    ):
        require(marker in android, f"Android exposes {marker}", checks)
        require(marker in ios, f"iOS exposes {marker}", checks)

    for marker in FORBIDDEN_PRECISION_MARKERS:
        require(marker not in android, f"Android omits forbidden precision field {marker}", checks)
        require(marker not in ios, f"iOS omits forbidden precision field {marker}", checks)

    runtime_capability_ids = {
        "cap_explicit_capture",
        "cap_share",
        "cap_agent",
        "cap_calendar",
        "cap_photo",
        "cap_explicit_voice",
    }
    for capability_id in sorted(runtime_capability_ids):
        require(
            f'"{capability_id}"' in ANDROID_RUNTIME.read_text(encoding="utf-8"),
            f"Android production registry declares {capability_id}",
            checks,
        )
        require(
            f'"{capability_id}"' in IOS_RUNTIME.read_text(encoding="utf-8"),
            f"iOS production registry declares {capability_id}",
            checks,
        )
    for marker in ("RealDevice", "RealUser", "ImportOrForward", "BystanderNotice"):
        require(marker in ANDROID_RUNTIME.read_text(encoding="utf-8"), f"Android runtime declares {marker}", checks)
    for marker in (".realDevice", ".realUser", ".importOrForward", ".bystanderNotice"):
        require(marker in IOS_RUNTIME.read_text(encoding="utf-8"), f"iOS runtime declares {marker}", checks)

    for marker in (
        "coverage_candidate_states",
        "coverage_event_links",
        "coverage_source_index",
        "const val SCHEMA_VERSION = 13",
        "const val COVERAGE_SCHEMA_VERSION = 7",
        "coverage_candidate_accept",
        "detachEvent",
    ):
        require(marker in android, f"Android production persistence declares {marker}", checks)
    for marker in (
        "LocalStoreEnvelope",
        "currentSchemaVersion = 8",
        "persistCoverageCompilation",
        "acceptCoverageCandidate",
        "CoverageEventLinkState",
        "candidateNotOpen",
    ):
        require(marker in ios, f"iOS production persistence declares {marker}", checks)
    for source_name in ("Coverage.swift", "CoveragePersistence.swift", "MobileCoverageRuntime.swift"):
        require(source_name in IOS_PROJECT.read_text(encoding="utf-8"), f"iOS project includes {source_name}", checks)
    for test_name in ("CoverageCompilerTests.swift", "CoveragePersistenceTests.swift"):
        require(test_name in IOS_PROJECT.read_text(encoding="utf-8"), f"iOS project includes {test_name}", checks)
    for marker in (
        "coveragePersistsWithoutLedgerThenExplicitAcceptAndDeleteStayConverged",
        "CoverageCandidateLifecycle.Consumed",
        "CoverageEventLinkLifecycle.Detached",
    ):
        require(marker in ANDROID_INSTRUMENTED_PERSISTENCE_TESTS.read_text(encoding="utf-8"), f"Android persistence test covers {marker}", checks)
    for marker in (
        "testCoveragePersistsWithoutEventAndAcceptanceDeleteCannotResurrect",
        "testLegacyEncryptedEventArrayLoadsAndMigratesOnNextWrite",
        "testUnknownEnvelopeSchemaFailsClosedWithoutOverwrite",
    ):
        require(marker in ios_tests, f"iOS persistence test covers {marker}", checks)

    for marker in (
        "ContinuousOptIn",
        "CascadeDelete",
        "BystanderNotice",
        "SourceStatusOnly",
        "confidence = 0.69",
        "OutOfScopeGap",
    ):
        require(
            marker in android_tests or marker in android,
            f"Android covers conservative rule {marker}",
            checks,
        )
    for marker in (
        "continuousOptIn",
        "cascadeDelete",
        "bystanderNotice",
        "sourceStatusOnly",
        "confidence: 0.69",
        "outside capability",
    ):
        require(
            marker in ios_tests or marker in ios,
            f"iOS covers conservative rule {marker}",
            checks,
        )

    tracked_inputs = (
        ANDROID_MODELS.relative_to(ROOT),
        ANDROID_TESTS.relative_to(ROOT),
        ANDROID_REPOSITORY.relative_to(ROOT),
        ANDROID_PERSISTENCE.relative_to(ROOT),
        Path("tests/fixtures/coverage/target-user-context-source-matrix-v1.json"),
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
        ignore_results == [1, 1, 1, 1, 1],
        "mobile coverage sources and shared fixture are not ignored by Git",
        checks,
    )

    print(
        json.dumps(
            {
                "ok": True,
                "checks": len(checks),
                "wire_values": len(schema_wire_values(schema)),
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
    except (AssertionError, KeyError, OSError, ValueError) as error:
        print(json.dumps({"ok": False, "errors": [str(error)]}, indent=2))
        raise SystemExit(1)
