"""Deterministic synthetic-only evaluation and differential reporting."""

from __future__ import annotations

from copy import deepcopy
from dataclasses import replace
import json
from pathlib import Path
import re
from typing import Any, Iterable, Mapping

from .errors import ProcessingError
from .model import (
    ContentBlock,
    PolicyContext,
    PromptEnvelope,
    canonical_json,
    stable_digest,
)
from .observability import SafeObserver
from .pipeline import AIProcessingReference
from .provider import FakeSyntheticProvider
from .rules import build_event_draft, decide_merge
from .runtime import SYNTHETIC_TEST_SCOPE_HMAC_KEY, ScopedAIRuntime
from .schema import MachineContract


EVAL_CATEGORIES = (
    "fact_correctness",
    "time_plan",
    "evidence_grounding",
    "deletion_no_resurrection",
    "provider_injection",
    "fallback_insufficient",
)
REPORT_KIND = "synthetic_reference_differential"
REPORT_CLAIM = "synthetic_reference_regression_only"
REPORT_IDENTIFIER = re.compile(r"^[a-z][a-z0-9_.:-]{0,127}$")


def load_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def run_synthetic_differential(
    *,
    base_fixture_path: Path,
    dataset_paths: Iterable[Path],
) -> dict[str, Any]:
    base_fixture = load_json(base_fixture_path)
    datasets = [(path, load_json(path)) for path in dataset_paths]
    case_reports: list[dict[str, Any]] = []
    matrix = {
        category: {"total": 0, "matched": 0, "mismatched": 0, "hard_failures": 0}
        for category in EVAL_CATEGORIES
    }
    hard_gates: dict[str, int] = {}

    for _, dataset in datasets:
        _require_report_identifier(dataset["dataset_id"])
        for case in dataset["cases"]:
            category = str(case["category"])
            if category not in matrix:
                raise ValueError(f"unknown eval category: {category}")
            _require_report_identifier(case["case_id"])
            _require_report_identifier(case["hard_gate"])
            actual = _run_case(case, dataset, base_fixture)
            assertions = _compare_expected(case["expected"], actual)
            matched = all(assertions.values())
            assertion_ids = sorted(assertions)
            expected_assertions = {assertion_id: True for assertion_id in assertion_ids}
            case_report = {
                "case_id": case["case_id"],
                "category": category,
                "hard_gate": case["hard_gate"],
                "assertion_count": len(assertions),
                "expected_digest": stable_digest(expected_assertions),
                "actual_digest": stable_digest(dict(sorted(assertions.items()))),
                "result": "match" if matched else "mismatch",
                "failed_assertion_ids": sorted(
                    key for key, value in assertions.items() if not value
                ),
            }
            case_reports.append(case_report)
            matrix[category]["total"] += 1
            matrix[category]["matched" if matched else "mismatched"] += 1
            gate = str(case["hard_gate"])
            hard_gates.setdefault(gate, 0)
            if not matched:
                hard_gates[gate] += 1
                matrix[category]["hard_failures"] += 1

    changed = sorted(
        item["case_id"] for item in case_reports if item["result"] != "match"
    )
    empty_categories = sorted(
        category for category, counts in matrix.items() if counts["total"] == 0
    )
    verdict = (
        "pass_synthetic_reference_regression"
        if not changed and not empty_categories
        else "fail_synthetic_reference_regression"
    )
    return {
        "schema_version": 1,
        "report_kind": REPORT_KIND,
        "claim": REPORT_CLAIM,
        "verdict": verdict,
        "real_model_quality_proven": False,
        "network_calls": 0,
        "provider_mode": "fixed_committed_synthetic_responses_only",
        "generator_version": "ai-reference-eval-v1",
        "digest_basis": "assertion_paths_and_boolean_results_only_no_expected_or_actual_values",
        "datasets": [
            {
                "dataset_id": dataset["dataset_id"],
                "dataset_version": dataset["dataset_version"],
                "sha256": stable_digest(dataset),
            }
            for _, dataset in datasets
        ],
        "baseline": "committed_case_expectations_v1",
        "candidate": "deterministic_r0_and_fixed_fake_provider_reference",
        "case_count": len(case_reports),
        "matrix": matrix,
        "hard_gate_failures": dict(sorted(hard_gates.items())),
        "differential": {
            "changed_case_ids": changed,
            "regression_count": len(changed),
            "empty_categories": empty_categories,
        },
        "cases": sorted(case_reports, key=lambda item: item["case_id"]),
        "limitations": [
            "no_real_model_or_network_provider",
            "no_model_language_quality_or_latency_claim",
            "no_real_user_or_production_data",
            "no_complete_grant_or_provider_retention_proof",
            "summary_generation_remains_unimplemented",
        ],
    }


def _run_case(
    case: Mapping[str, Any],
    dataset: Mapping[str, Any],
    base_fixture: Mapping[str, Any],
) -> dict[str, Any]:
    operation = case["operation"]
    if operation == "candidate":
        return _run_candidate(case, dataset, base_fixture)
    if operation == "merge":
        left = _observations(case["left_observations"], base_fixture)
        right = _observations(case["right_observations"], base_fixture)
        decision = decide_merge(
            build_event_draft(left, contract=MachineContract()),
            build_event_draft(right, contract=MachineContract()),
        )
        return {"action": decision.action, "reason_code": decision.reason_code}
    if operation == "summary":
        decision = AIProcessingReference().no_summary_when_unavailable(
            case.get("event_ids", []),
            context=_context(case),
        )
        return {
            "state": decision.state,
            "text": decision.text,
            "reason_code": decision.reason_code,
        }
    if operation == "delete_cache_replay":
        return _run_delete_cache_replay(case, dataset, base_fixture)
    if operation == "revoke_batch_replay":
        return _run_revoke_batch_replay(case, base_fixture)
    raise ValueError(f"unknown eval operation: {operation}")


def _run_candidate(
    case: Mapping[str, Any],
    dataset: Mapping[str, Any],
    base_fixture: Mapping[str, Any],
) -> dict[str, Any]:
    observations = _observations(case["observations"], base_fixture)
    context = _context(case)
    reference = AIProcessingReference()
    provider: FakeSyntheticProvider | None = None
    fixture_id: str | None = None
    if case.get("route") == "fake_provider":
        fixture_id = str(case["provider_response_id"])
        provider = FakeSyntheticProvider(
            {fixture_id: deepcopy(dataset["provider_responses"][fixture_id])}
        )
    try:
        draft = reference.create_candidate(
            observations,
            context=context,
            provider=provider,
            synthetic_fixture_id=fixture_id,
        )
    except ProcessingError as exc:
        return {
            "outcome": "error",
            "error_code": exc.code.value,
            "provider_calls": provider.calls if provider is not None else 0,
        }
    evidence_ids = {
        str(item["field"]): sorted(str(value) for value in item["observation_ids"])
        for item in draft.candidate["field_evidence"]
    }
    return {
        "outcome": "draft",
        "fact_status": draft.fact_status,
        "candidate_status": draft.candidate["status"],
        "candidate_id": draft.candidate["candidate_id"],
        "fallback_reason": draft.fallback_reason,
        "time_range": draft.candidate.get("time_range"),
        "semantic_fields": dict(draft.semantic_fields),
        "semantic_field_names": sorted(draft.semantic_fields),
        "evidence_ids": evidence_ids,
        "evidence_fields": sorted(evidence_ids),
        "superseded_evidence_ids": list(draft.superseded_evidence_ids),
        "provider_calls": provider.calls if provider is not None else 0,
    }


def _run_delete_cache_replay(
    case: Mapping[str, Any],
    dataset: Mapping[str, Any],
    base_fixture: Mapping[str, Any],
) -> dict[str, Any]:
    observations = _observations(case["observations"], base_fixture)
    fixture_id = str(case["provider_response_id"])
    provider = FakeSyntheticProvider(
        {fixture_id: deepcopy(dataset["provider_responses"][fixture_id])}
    )
    reference = AIProcessingReference()
    context = _context(case)
    first = reference.create_candidate(
        observations,
        context=context,
        provider=provider,
        synthetic_fixture_id=fixture_id,
    )
    cached = reference.create_candidate(
        observations,
        context=context,
        provider=provider,
        synthetic_fixture_id=fixture_id,
    )
    calls_before = provider.calls
    evidence_id = str(observations[0]["observation_id"])
    deleted = replace(
        context,
        deleted_evidence_ids=frozenset({evidence_id}),
        revocation_generation="eval-revocation-v2",
    )
    delete_error = _candidate_error(
        reference,
        observations,
        deleted,
        provider,
        fixture_id,
    )
    replay_error = _candidate_error(
        reference,
        observations,
        context,
        provider,
        fixture_id,
    )
    return {
        "first_fallback_reason": first.fallback_reason,
        "cache_reused": dict(first.candidate) == dict(cached.candidate),
        "provider_calls_before_delete": calls_before,
        "delete_error": delete_error,
        "old_snapshot_replay_error": replay_error,
        "provider_calls_after_delete": provider.calls,
    }


def _run_revoke_batch_replay(
    case: Mapping[str, Any], base_fixture: Mapping[str, Any]
) -> dict[str, Any]:
    observations = _observations(case["observations"], base_fixture)
    observation = observations[0]
    observer = SafeObserver()
    runtime = ScopedAIRuntime(
        observer,
        scope_hmac_key=SYNTHETIC_TEST_SCOPE_HMAC_KEY,
    )
    context = _context(case)
    evidence_id = str(observation["observation_id"])
    envelope = PromptEnvelope(
        task_id="T06_EVENT_DRAFT",
        task_version="1",
        input_schema="ObservationBundle/v1",
        output_schema="EventCandidate/v1",
        prompt_template_version="event-draft-zh-v1",
        policy_version="mvp-policy-v1",
        adapter_name="fake-synthetic-provider",
        locale="zh-CN",
        space_id=context.space_id,
        evidence_manifest=(evidence_id,),
        content_blocks=(
            ContentBlock(
                evidence_id=evidence_id,
                content=canonical_json(observation["value"]),
                synthetic=True,
            ),
        ),
        constraints={"no_unreferenced_facts": True},
        synthetic_fixture_id="revoke_batch",
    )
    runtime.enqueue_batch(envelope, context)
    runtime.invalidate_evidence(context, revoked_ids={evidence_id})
    drained = runtime.drain_batch(context)
    try:
        runtime.enqueue_batch(envelope, context)
    except ProcessingError as exc:
        enqueue_error = exc.code.value
    else:
        enqueue_error = None
    return {
        "queued_before_revoke": 1,
        "drained_after_revoke": len(drained),
        "old_snapshot_enqueue_error": enqueue_error,
    }


def _candidate_error(
    reference: AIProcessingReference,
    observations: list[dict[str, Any]],
    context: PolicyContext,
    provider: FakeSyntheticProvider,
    fixture_id: str,
) -> str | None:
    try:
        reference.create_candidate(
            observations,
            context=context,
            provider=provider,
            synthetic_fixture_id=fixture_id,
        )
    except ProcessingError as exc:
        return exc.code.value
    return None


def _context(case: Mapping[str, Any]) -> PolicyContext:
    overrides = dict(case.get("context", {}))
    return PolicyContext(
        space_id=str(overrides.get("space_id", "space_personal")),
        purpose=str(overrides.get("purpose", "form_today")),
        sensitivity=str(overrides.get("sensitivity", "personal")),
        processing_locations=frozenset(
            overrides.get("processing_locations", ["device", "model_provider"])
        ),
        allowed_provider_adapters=frozenset(
            overrides.get("allowed_provider_adapters", ["fake-synthetic-provider"])
        ),
        policy_generation=str(overrides.get("policy_generation", "eval-policy-v1")),
        revocation_generation=str(
            overrides.get("revocation_generation", "eval-revocation-v1")
        ),
        budget_remaining=int(overrides.get("budget_remaining", 2)),
    )


def _observations(
    names: Iterable[str], base_fixture: Mapping[str, Any]
) -> list[dict[str, Any]]:
    return [deepcopy(base_fixture["observations"][name]) for name in names]


def _compare_expected(
    expected: Mapping[str, Any], actual: Mapping[str, Any]
) -> dict[str, bool]:
    assertions: dict[str, bool] = {}
    for path, expected_value in _flatten(expected):
        found, actual_value = _lookup(actual, path)
        assertion_id = ".".join(path)
        _require_report_identifier(assertion_id)
        assertions[assertion_id] = found and actual_value == expected_value
    return assertions


def _flatten(
    value: Mapping[str, Any], prefix: tuple[str, ...] = ()
) -> Iterable[tuple[tuple[str, ...], Any]]:
    for key in sorted(value):
        child = value[key]
        path = prefix + (str(key),)
        if isinstance(child, Mapping):
            yield from _flatten(child, path)
        else:
            yield path, child


def _lookup(
    value: Mapping[str, Any], path: tuple[str, ...]
) -> tuple[bool, Any]:
    current: Any = value
    for key in path:
        if not isinstance(current, Mapping) or key not in current:
            return False, None
        current = current[key]
    return True, current


def _require_report_identifier(value: Any) -> str:
    text = str(value)
    if REPORT_IDENTIFIER.fullmatch(text) is None:
        raise ValueError("eval report identifiers must be content-free categories")
    return text
