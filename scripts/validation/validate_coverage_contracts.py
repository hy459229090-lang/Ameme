# Purpose: validate the synthetic target-user/context/source matrix and deterministic coverage compiler.
# Input: coverage JSON Schema, synthetic planning fixture, and the non-production Core coverage reference.
# Output: aggregate checks without personal content or fake market-size claims; exits non-zero on failure.

from __future__ import annotations

from copy import deepcopy
import json
from pathlib import Path
import sys
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "packages" / "core-reference"))
sys.path.insert(0, str(ROOT / "scripts" / "validation"))

from ameme_core_reference import (  # noqa: E402
    CoverageCompiler,
    SourceCapabilityRegistry,
    load_coverage_fixture,
)
from validate_contracts import ContractValidator  # noqa: E402


SCHEMA_PATH = ROOT / "packages" / "contracts" / "schemas" / "ameme-coverage.schema.json"
FIXTURE_PATH = (
    ROOT
    / "tests"
    / "fixtures"
    / "coverage"
    / "target-user-context-source-matrix-v1.json"
)
FORBIDDEN_OUTPUT_KEYS = {
    "coverage_percentage",
    "coverage_percent",
    "estimated_user_count",
    "market_size",
}


def _contains_forbidden_key(value: Any) -> bool:
    if isinstance(value, dict):
        return bool(set(value) & FORBIDDEN_OUTPUT_KEYS) or any(
            _contains_forbidden_key(item) for item in value.values()
        )
    if isinstance(value, list):
        return any(_contains_forbidden_key(item) for item in value)
    return False


def main() -> int:
    schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
    fixture = load_coverage_fixture(FIXTURE_PATH)
    validator = ContractValidator(schema)
    validator.validate_instance(fixture, schema, "coverage_fixture")

    context_enum = set(schema["$defs"]["ContextType"]["enum"])
    context_types = set(fixture["context_types"])
    validator.check(context_types == context_enum, "fixture must list the complete context taxonomy")

    segments = {item["segment_id"]: item for item in fixture["segments"]}
    capabilities = {
        item["capability_id"]: item for item in fixture["capabilities"]
    }
    validator.check(
        len(segments) == len(fixture["segments"]),
        "segment_id values must be unique",
    )
    validator.check(
        len(capabilities) == len(fixture["capabilities"]),
        "capability_id values must be unique",
    )

    registry = SourceCapabilityRegistry.from_list(fixture["capabilities"])
    compiler = CoverageCompiler(registry)
    validator.check(
        registry.ids() == sorted(capabilities),
        "registry must retain every capability",
    )

    for segment in fixture["segments"]:
        minimum_ids = set(segment["minimum_capability_ids"])
        validator.check(
            minimum_ids <= set(capabilities),
            f"{segment['segment_id']} references unknown minimum capabilities",
        )
        validator.check(
            "cap_explicit_capture" in minimum_ids,
            f"{segment['segment_id']} lacks the universal explicit fallback",
        )
        minimum_contexts = {
            context
            for capability_id in minimum_ids
            for context in capabilities[capability_id]["context_types"]
        }
        validator.check(
            set(segment["important_contexts"]) <= minimum_contexts,
            f"{segment['segment_id']} minimum pack misses an important context",
        )
        validator.check(
            segment["scale_status"] != "externally_verified",
            f"{segment['segment_id']} synthetic fixture cannot claim verified scale",
        )

    mapped_contexts = {
        context
        for capability in fixture["capabilities"]
        for context in capability["context_types"]
    }
    validator.check(
        mapped_contexts == context_enum,
        "every context type must have at least one source capability",
    )
    for capability in fixture["capabilities"]:
        validator.check(
            set(capability["eligible_segment_ids"]) <= set(segments),
            f"{capability['capability_id']} references an unknown segment",
        )
        validator.check(
            capability["acquisition_mode"] != "continuous_opt_in"
            or capability["priority"] == "P3",
            f"{capability['capability_id']} continuous capture must remain P3",
        )

    compiled_days = 0
    observations = 0
    candidates = 0
    gaps = 0
    for day in fixture["synthetic_days"]:
        validator.check(
            day["segment_id"] in segments,
            f"{day['day_id']} references an unknown segment",
        )
        output = compiler.compile_day(
            day_id=day["day_id"],
            owner_id=day["owner_id"],
            space_id=day["space_id"],
            local_date=day["local_date"],
            timezone=day["timezone"],
            signals=deepcopy(day["signals"]),
        )
        compiled_days += 1
        observations += len(output["observations"])
        candidates += len(output["candidate_events"])
        gaps += len(output["context_gaps"])
        validator.check(
            len(output["candidate_events"]) == day["expected"]["candidate_count"],
            f"{day['day_id']} candidate count differs from the pre-registered fixture",
        )
        validator.check(
            {item["reason"] for item in output["context_gaps"]}
            == set(day["expected"]["gap_reasons"]),
            f"{day['day_id']} gap reasons differ from the pre-registered fixture",
        )
        validator.check(
            not _contains_forbidden_key(output),
            f"{day['day_id']} emitted a fake precision or market-size field",
        )
        for observation in output["observations"]:
            validator.validate_instance(
                observation,
                schema["$defs"]["CoverageObservation"],
                f"{day['day_id']}.coverage_observation",
            )
        for gap in output["context_gaps"]:
            validator.validate_instance(
                gap,
                schema["$defs"]["ContextGap"],
                f"{day['day_id']}.context_gap",
            )

    result = {
        "ok": not validator.errors,
        "checks": validator.checks,
        "errors": validator.errors,
        "synthetic_days": compiled_days,
        "capabilities": len(capabilities),
        "segments": len(segments),
        "coverage_observations": observations,
        "candidate_events": candidates,
        "context_gaps": gaps,
        "market_scale_status": "not_verified",
    }
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    sys.exit(main())
