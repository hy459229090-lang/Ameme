"""Adapter over the current machine schema and semantic validator."""

from __future__ import annotations

import json
from pathlib import Path
import sys
from typing import Any, Iterable, Mapping

from .errors import ErrorCode, ProcessingError
from .model import canonical_json


ROOT = Path(__file__).resolve().parents[3]
VALIDATION_DIR = ROOT / "scripts" / "validation"
if str(VALIDATION_DIR) not in sys.path:
    sys.path.insert(0, str(VALIDATION_DIR))

from contract_semantics import validate_bundle  # type: ignore[import-not-found]  # noqa: E402
from validate_contracts import ContractValidator, TYPE_TO_DEF  # type: ignore[import-not-found]  # noqa: E402


FIELD_VALUE_KEYS = {
    "place": {"place"},
    "people": {"people"},
    "action": {"action"},
    "result": {"result"},
    "intent": {"intent"},
    "emotion": {"emotion"},
    "relationship": {"relationship"},
    "description": {"description"},
}
NON_TIME_FACT_VALUE_KEYS = frozenset(
    {"place", "people", "action", "result", "intent", "description"}
)
# A future contract may explicitly allow a kind whose time range is itself the
# factual payload. No current Observation kind has that contract.
TIME_ONLY_FACT_KINDS: frozenset[str] = frozenset()


def observation_supports_event_time(observation: Mapping[str, Any]) -> bool:
    """Return whether an Observation may contribute factual event time."""

    if "time_range" not in observation:
        return False
    value_keys = set(observation.get("value", {}))
    return bool(NON_TIME_FACT_VALUE_KEYS & value_keys) or str(
        observation.get("kind", "")
    ) in TIME_ONLY_FACT_KINDS


class MachineContract:
    def __init__(self, schema_path: Path | None = None) -> None:
        self.schema_path = schema_path or (
            ROOT / "packages" / "contracts" / "schemas" / "ameme-domain.schema.json"
        )
        self.schema = json.loads(self.schema_path.read_text(encoding="utf-8"))

    def validate(self, object_type: str, payload: Mapping[str, Any]) -> None:
        definition_name = TYPE_TO_DEF.get(object_type)
        if definition_name is None:
            raise ProcessingError(ErrorCode.SCHEMA_MISMATCH, safe_context={"reason": "unknown_type"})
        validator = ContractValidator(self.schema)
        validator.validate_instance(dict(payload), self.schema["$defs"][definition_name], object_type)
        if validator.errors:
            raise ProcessingError(
                ErrorCode.SCHEMA_MISMATCH,
                safe_context={"error_count": len(validator.errors), "object_type": object_type},
            )

    def validate_bundle(self, wrappers: list[dict[str, Any]]) -> None:
        for wrapper in wrappers:
            self.validate(wrapper["object_type"], wrapper["object"])
        result = validate_bundle(wrappers)
        if not result.ok:
            raise ProcessingError(
                ErrorCode.SCHEMA_MISMATCH,
                safe_context={"error_count": len(result.errors), "reason": "semantic_invariant"},
            )

    def validate_event_candidate(
        self,
        candidate: Mapping[str, Any],
        observations: Iterable[Mapping[str, Any]],
        *,
        provider_output: bool = False,
        r0_candidate: Mapping[str, Any] | None = None,
    ) -> None:
        observation_list = [dict(item) for item in observations]
        for item in observation_list:
            self.validate("observation", item)
        candidate_wrapper = {
            "schema_version": 1,
            "object_type": "event_candidate",
            "object": dict(candidate),
        }
        # Full bundle semantics require SourceObject lineage, which is outside
        # this task input. Run the current semantic validator on the candidate,
        # then enforce the Observation references below without fabricating a
        # SourceObject.
        self.validate("event_candidate", candidate)
        semantic = validate_bundle([candidate_wrapper])
        if not semantic.ok:
            raise ProcessingError(
                ErrorCode.SCHEMA_MISMATCH,
                safe_context={"error_count": len(semantic.errors), "reason": "semantic_invariant"},
            )

        known = {item["observation_id"]: item for item in observation_list}
        if candidate.get("space_id") not in {item.get("space_id") for item in observation_list}:
            raise ProcessingError(ErrorCode.SPACE_DENIED)
        if provider_output and candidate.get("status") not in {"candidate", "needs_review", "conflict"}:
            raise ProcessingError(
                ErrorCode.SCHEMA_MISMATCH, safe_context={"reason": "provider_terminal_status"}
            )

        factual_fields = {
            evidence.get("field")
            for evidence in candidate.get("field_evidence", [])
            if evidence.get("field") in {"time", "place", "people", "action", "result", "intent", "description"}
        }
        if not factual_fields:
            raise ProcessingError(
                ErrorCode.DATA_INSUFFICIENT, safe_context={"reason": "no_factual_evidence"}
            )

        for evidence in candidate.get("field_evidence", []):
            field = evidence.get("field")
            observation_ids = evidence.get("observation_ids", [])
            referenced = [known.get(item) for item in observation_ids]
            if not observation_ids or any(item is None for item in referenced):
                raise ProcessingError(ErrorCode.MISSING_EVIDENCE)
            typed = [item for item in referenced if item is not None]
            if any(item["space_id"] != candidate["space_id"] for item in typed):
                raise ProcessingError(ErrorCode.SPACE_DENIED)
            if field == "time":
                supported = all(observation_supports_event_time(item) for item in typed)
            else:
                keys = FIELD_VALUE_KEYS.get(str(field), set())
                supported = bool(keys) and all(bool(keys & set(item.get("value", {}))) for item in typed)
            if not supported:
                raise ProcessingError(
                    ErrorCode.MISSING_EVIDENCE, safe_context={"reason": "field_not_supported"}
                )
            maximum = max(float(item["confidence"]) for item in typed)
            if float(evidence.get("confidence", 0)) > maximum + 1e-9:
                raise ProcessingError(
                    ErrorCode.SCHEMA_MISMATCH,
                    safe_context={"reason": "confidence_exceeds_evidence"},
                )
            if provider_output:
                source_statuses = {str(item["fact_status"]) for item in typed}
                evidence_status = str(evidence.get("status"))
                if evidence_status == "conflict":
                    status_supported = len(typed) > 1
                else:
                    status_supported = evidence_status in source_statuses
                if not status_supported:
                    raise ProcessingError(
                        ErrorCode.SCHEMA_MISMATCH,
                        safe_context={"reason": "fact_status_not_supported"},
                    )

        if provider_output:
            factual_evidence = [
                evidence
                for evidence in candidate.get("field_evidence", [])
                if evidence.get("field")
                in {"time", "place", "people", "action", "result", "intent", "description"}
            ]
            has_fact_conflict = any(
                evidence.get("status") == "conflict" for evidence in factual_evidence
            )
            if (candidate.get("status") == "conflict") != has_fact_conflict:
                raise ProcessingError(
                    ErrorCode.SCHEMA_MISMATCH,
                    safe_context={"reason": "candidate_conflict_state_mismatch"},
                )
            time_evidence = [
                evidence
                for evidence in candidate.get("field_evidence", [])
                if evidence.get("field") == "time"
            ]
            if bool(candidate.get("time_range")) != bool(time_evidence):
                raise ProcessingError(
                    ErrorCode.SCHEMA_MISMATCH,
                    safe_context={"reason": "time_evidence_range_mismatch"},
                )
            if time_evidence:
                supported_ranges = {
                    canonical_json(known[observation_id]["time_range"])
                    for evidence in time_evidence
                    for observation_id in evidence["observation_ids"]
                }
                if canonical_json(candidate["time_range"]) not in supported_ranges:
                    raise ProcessingError(
                        ErrorCode.SCHEMA_MISMATCH,
                        safe_context={"reason": "time_range_not_supported"},
                    )

            referenced_fact_ids = {
                str(observation_id)
                for evidence in candidate.get("field_evidence", [])
                if evidence.get("field")
                in {"time", "place", "people", "action", "result", "intent", "description"}
                for observation_id in evidence.get("observation_ids", [])
            }
            explicit_types = {
                str(known[observation_id]["value"]["event_type"])
                for observation_id in referenced_fact_ids
                if known[observation_id]["value"].get("event_type") is not None
            }
            safe_types = set(explicit_types)
            if r0_candidate is not None:
                safe_types.add(str(r0_candidate["event_type"]))
            if str(candidate.get("event_type")) not in safe_types:
                raise ProcessingError(
                    ErrorCode.SCHEMA_MISMATCH,
                    safe_context={"reason": "event_type_not_supported"},
                )
