"""Adapter over the current machine schema and semantic validator."""

from __future__ import annotations

import json
from pathlib import Path
import sys
from typing import Any, Iterable, Mapping

from .errors import ErrorCode, ProcessingError


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
    "description": {"description", "title"},
}


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
                supported = all("time_range" in item for item in typed)
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
