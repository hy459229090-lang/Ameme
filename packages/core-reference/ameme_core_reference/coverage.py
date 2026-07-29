"""Deterministic context-coverage compiler for the non-production Core oracle.

The compiler reports only evidence-backed coverage and explicit gap hints. It
does not infer that an unobserved time period contains an event, and it never
emits a synthetic whole-day coverage percentage.
"""

from __future__ import annotations

from copy import deepcopy
from dataclasses import dataclass
import json
from pathlib import Path
from typing import Any, Iterable

from .errors import InvariantViolation


CONTEXT_TYPES = {
    "time_schedule",
    "activity_result",
    "content_consumption",
    "content_creation",
    "communication_relationship",
    "decision_commitment",
    "place_mobility",
    "body_state",
    "consumption_entertainment",
    "intent_feeling",
}
EVIDENCE_FIELDS = {
    "time",
    "place",
    "people",
    "action",
    "result",
    "intent",
    "emotion",
    "relationship",
    "description",
}
SOURCE_STATES = {"available", "not_authorized", "failed", "unavailable"}
FACT_STATUSES = {"observed", "user_asserted", "planned", "inferred"}
EVENT_TYPES = {
    "activity",
    "communication",
    "decision",
    "result",
    "state_change",
    "milestone",
    "experience",
}
ACQUISITION_MODES = {
    "automatic",
    "scene_triggered",
    "one_action",
    "import_or_forward",
    "gap_prompt",
    "continuous_opt_in",
}
PRIORITIES = {"P0", "P1", "P2", "P3"}
GAP_REASONS = {
    "actuality_unconfirmed",
    "result_missing",
    "meaning_missing",
    "identity_ambiguous",
    "source_not_authorized",
    "source_failed",
    "capability_unavailable",
}
ISSUE_STATE_MAP = {
    "not_authorized": "not_authorized",
    "failed": "failed",
    "unavailable": "unavailable",
}


def _require_string(value: Any, label: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise InvariantViolation(f"{label} must be a non-empty string")
    return value


def _require_unique_strings(value: Any, label: str, allowed: set[str] | None = None) -> list[str]:
    if not isinstance(value, list) or not all(isinstance(item, str) for item in value):
        raise InvariantViolation(f"{label} must be an array of strings")
    if len(value) != len(set(value)):
        raise InvariantViolation(f"{label} must not contain duplicates")
    if allowed is not None and not set(value) <= allowed:
        raise InvariantViolation(f"{label} contains unsupported values")
    return list(value)


def _prompt_policy(reason: str, value_level: str) -> str:
    if reason in {"source_not_authorized", "source_failed", "capability_unavailable"}:
        return "source_status_only"
    if value_level == "high":
        return "prompt_once"
    if value_level == "medium":
        return "optional"
    return "do_not_prompt"


@dataclass(frozen=True)
class SourceCapabilityRegistry:
    """Validated, immutable capability registry used by the reference compiler."""

    _capabilities: dict[str, dict[str, Any]]

    @classmethod
    def from_list(cls, capabilities: Iterable[dict[str, Any]]) -> "SourceCapabilityRegistry":
        registry: dict[str, dict[str, Any]] = {}
        for raw in capabilities:
            capability = deepcopy(raw)
            capability_id = _require_string(capability.get("capability_id"), "capability_id")
            if capability_id in registry:
                raise InvariantViolation(f"duplicate capability_id {capability_id}")
            if capability.get("schema_version") != 1:
                raise InvariantViolation(f"{capability_id} has unsupported schema_version")
            acquisition_mode = capability.get("acquisition_mode")
            if acquisition_mode not in ACQUISITION_MODES:
                raise InvariantViolation(f"{capability_id} has unsupported acquisition_mode")
            priority = capability.get("priority")
            if priority not in PRIORITIES:
                raise InvariantViolation(f"{capability_id} has unsupported priority")
            if acquisition_mode == "continuous_opt_in" and priority != "P3":
                raise InvariantViolation("continuous capture cannot be P0/P1/P2")
            context_types = _require_unique_strings(
                capability.get("context_types"), f"{capability_id}.context_types", CONTEXT_TYPES
            )
            if not context_types:
                raise InvariantViolation(f"{capability_id}.context_types must not be empty")
            _require_unique_strings(
                capability.get("eligible_segment_ids"),
                f"{capability_id}.eligible_segment_ids",
            )
            hard_gates = _require_unique_strings(
                capability.get("hard_gates"), f"{capability_id}.hard_gates"
            )
            for required_gate in ("lineage", "cascade_delete", "recovery"):
                if required_gate not in hard_gates:
                    raise InvariantViolation(
                        f"{capability_id} must include hard gate {required_gate}"
                    )
            if capability.get("bystander_risk") == "high" and "bystander_notice" not in hard_gates:
                raise InvariantViolation(
                    f"{capability_id} high bystander risk requires bystander_notice"
                )
            field_claims = capability.get("field_claims")
            if not isinstance(field_claims, list) or not field_claims:
                raise InvariantViolation(f"{capability_id}.field_claims must not be empty")
            seen_fields: set[str] = set()
            for claim in field_claims:
                if not isinstance(claim, dict):
                    raise InvariantViolation(f"{capability_id}.field_claims must be objects")
                field = claim.get("field")
                if field not in EVIDENCE_FIELDS or field in seen_fields:
                    raise InvariantViolation(
                        f"{capability_id}.field_claims has invalid or duplicate field"
                    )
                seen_fields.add(field)
                _require_string(claim.get("limitation"), f"{capability_id}.{field}.limitation")
                if claim.get("authority") not in {"strong", "supporting", "weak", "none"}:
                    raise InvariantViolation(f"{capability_id}.{field} has invalid authority")
                if field in {"intent", "emotion"} and claim.get("authority") == "strong":
                    if capability.get("source_type") not in {"text", "audio"}:
                        raise InvariantViolation(
                            f"{capability_id} cannot strongly infer {field} from behavior"
                        )
            registry[capability_id] = capability
        if not registry:
            raise InvariantViolation("capability registry must not be empty")
        return cls(registry)

    def get(self, capability_id: str) -> dict[str, Any]:
        try:
            return deepcopy(self._capabilities[capability_id])
        except KeyError as exc:
            raise InvariantViolation(f"unknown capability_id {capability_id}") from exc

    def ids(self) -> list[str]:
        return sorted(self._capabilities)


class CoverageCompiler:
    """Compile source signals into observations, candidates and explicit gaps."""

    def __init__(self, registry: SourceCapabilityRegistry) -> None:
        self.registry = registry

    def compile_day(
        self,
        *,
        day_id: str,
        owner_id: str,
        space_id: str,
        local_date: str,
        timezone: str,
        signals: Iterable[dict[str, Any]],
    ) -> dict[str, Any]:
        _require_string(day_id, "day_id")
        _require_string(owner_id, "owner_id")
        _require_string(space_id, "space_id")
        _require_string(local_date, "local_date")
        _require_string(timezone, "timezone")
        observations: list[dict[str, Any]] = []
        candidates: list[dict[str, Any]] = []
        gaps: list[dict[str, Any]] = []
        seen_signal_ids: set[str] = set()
        seen_gap_keys: set[tuple[str, str, str]] = set()

        for raw_signal in signals:
            signal = deepcopy(raw_signal)
            signal_id = _require_string(signal.get("signal_id"), "signal_id")
            if signal_id in seen_signal_ids:
                raise InvariantViolation(f"duplicate signal_id {signal_id}")
            seen_signal_ids.add(signal_id)
            capability_id = _require_string(signal.get("capability_id"), "capability_id")
            capability = self.registry.get(capability_id)
            source_state = signal.get("source_state")
            if source_state not in SOURCE_STATES:
                raise InvariantViolation(f"{signal_id} has unsupported source_state")
            context_types = _require_unique_strings(
                signal.get("context_types"), f"{signal_id}.context_types", CONTEXT_TYPES
            )
            if not context_types or not set(context_types) <= set(capability["context_types"]):
                raise InvariantViolation(
                    f"{signal_id} claims context outside {capability_id}"
                )
            observed_fields = _require_unique_strings(
                signal.get("observed_fields"),
                f"{signal_id}.observed_fields",
                EVIDENCE_FIELDS,
            )
            fact_status = signal.get("fact_status")
            if fact_status not in FACT_STATUSES:
                raise InvariantViolation(f"{signal_id} has unsupported fact_status")
            confidence = signal.get("confidence")
            if (
                isinstance(confidence, bool)
                or not isinstance(confidence, (int, float))
                or not 0 <= confidence <= 1
            ):
                raise InvariantViolation(f"{signal_id}.confidence must be in [0,1]")
            if signal.get("importance") not in {"low", "medium", "high"}:
                raise InvariantViolation(
                    f"{signal_id}.importance must be low, medium or high"
                )
            source_object_ids = _require_unique_strings(
                signal.get("source_object_ids"), f"{signal_id}.source_object_ids"
            )
            if source_state == "available" and not source_object_ids:
                raise InvariantViolation(
                    f"{signal_id} available evidence requires a source object"
                )
            if source_state != "available" and (source_object_ids or observed_fields):
                raise InvariantViolation(
                    f"{signal_id} unavailable evidence cannot claim fields or sources"
                )
            observed_at = _require_string(signal.get("observed_at"), "observed_at")
            observation_state = (
                ISSUE_STATE_MAP[source_state]
                if source_state != "available"
                else ("observed" if observed_fields else "partial")
            )
            observation = {
                "schema_version": 1,
                "coverage_observation_id": f"coverage_{signal_id}",
                "owner_id": owner_id,
                "space_id": space_id,
                "local_date": local_date,
                "capability_id": capability_id,
                "source_object_ids": source_object_ids,
                "context_types": context_types,
                "observed_fields": observed_fields,
                "fact_status": fact_status,
                "state": observation_state,
                "observed_at": observed_at,
            }
            if "time_range" in signal:
                observation["time_range"] = deepcopy(signal["time_range"])
            observations.append(observation)

            event_hint = signal.get("event_hint")
            if event_hint is not None:
                if not isinstance(event_hint, dict):
                    raise InvariantViolation(f"{signal_id}.event_hint must be an object")
                title = event_hint.get("title")
                if (
                    event_hint.get("event_type") not in EVENT_TYPES
                    or not isinstance(title, str)
                    or not 1 <= len(title) <= 200
                ):
                    raise InvariantViolation(f"{signal_id}.event_hint is invalid")
            supports_event = bool(
                source_state == "available"
                and event_hint is not None
                and "time" in observed_fields
                and set(observed_fields) & {"action", "result", "description"}
                and (fact_status != "inferred" or confidence >= 0.7)
            )
            if supports_event:
                candidates.append(
                    {
                        "candidate_id": f"candidate_{signal_id}",
                        "source_object_ids": source_object_ids,
                        "capability_id": capability_id,
                        "event_type": event_hint["event_type"],
                        "title": event_hint["title"],
                        "time_range": deepcopy(signal.get("time_range")),
                        "fact_status": fact_status,
                        "confidence": confidence,
                        "observed_fields": observed_fields,
                    }
                )

            gap_hints = signal.get("gap_hints")
            if not isinstance(gap_hints, list):
                raise InvariantViolation(f"{signal_id}.gap_hints must be an array")
            for hint in gap_hints:
                if not isinstance(hint, dict):
                    raise InvariantViolation(f"{signal_id}.gap_hints must be objects")
                context_type = hint.get("context_type")
                reason = hint.get("reason")
                value_level = hint.get("value_level")
                if context_type not in CONTEXT_TYPES or reason not in GAP_REASONS:
                    raise InvariantViolation(f"{signal_id} contains an invalid gap hint")
                if (
                    reason
                    in {
                        "source_not_authorized",
                        "source_failed",
                        "capability_unavailable",
                    }
                    and context_type not in capability["context_types"]
                ):
                    raise InvariantViolation(
                        f"{signal_id} source-status gap claims context outside {capability_id}"
                    )
                if value_level not in {"low", "medium", "high"}:
                    raise InvariantViolation(f"{signal_id} contains an invalid gap value")
                if reason == "source_not_authorized" and source_state != "not_authorized":
                    raise InvariantViolation(
                        f"{signal_id} source_not_authorized gap lacks matching state"
                    )
                if reason == "source_failed" and source_state != "failed":
                    raise InvariantViolation(
                        f"{signal_id} source_failed gap lacks matching state"
                    )
                if reason == "capability_unavailable" and source_state != "unavailable":
                    raise InvariantViolation(
                        f"{signal_id} capability_unavailable gap lacks matching state"
                    )
                range_key = json.dumps(
                    signal.get("time_range", {}),
                    ensure_ascii=False,
                    sort_keys=True,
                    separators=(",", ":"),
                )
                key = (context_type, reason, range_key)
                if key in seen_gap_keys:
                    continue
                seen_gap_keys.add(key)
                gap = {
                    "schema_version": 1,
                    "context_gap_id": f"gap_{day_id}_{len(gaps) + 1:02d}",
                    "owner_id": owner_id,
                    "space_id": space_id,
                    "local_date": local_date,
                    "context_type": context_type,
                    "reason": reason,
                    "value_level": value_level,
                    "prompt_policy": _prompt_policy(reason, value_level),
                    "capability_ids": [capability_id],
                    "state": "open",
                    "created_at": observed_at,
                }
                if "time_range" in signal:
                    gap["time_range"] = deepcopy(signal["time_range"])
                gaps.append(gap)

        covered_context_types = sorted(
            {
                context_type
                for observation in observations
                if observation["state"] in {"observed", "partial"}
                and observation["observed_fields"]
                for context_type in observation["context_types"]
            }
        )
        unknown_context_types = sorted(CONTEXT_TYPES - set(covered_context_types))
        issue_states = {
            observation["state"]
            for observation in observations
            if observation["state"] in {"not_authorized", "failed", "unavailable"}
        }
        if issue_states:
            coverage_state = "partial"
        elif candidates:
            coverage_state = "evidence_available"
        else:
            coverage_state = "sparse"
        return {
            "schema_version": 1,
            "day_id": day_id,
            "owner_id": owner_id,
            "space_id": space_id,
            "local_date": local_date,
            "timezone": timezone,
            "coverage_state": coverage_state,
            "covered_context_types": covered_context_types,
            "unknown_context_types": unknown_context_types,
            "observations": observations,
            "candidate_events": candidates,
            "context_gaps": gaps,
        }


def load_coverage_fixture(path: str | Path) -> dict[str, Any]:
    payload = json.loads(Path(path).read_text(encoding="utf-8"))
    if payload.get("fixture_version") != 1:
        raise InvariantViolation("unsupported coverage fixture version")
    if payload.get("evidence_status") != "synthetic_planning_baseline_not_market_evidence":
        raise InvariantViolation("coverage fixture must not claim market evidence")
    return payload
