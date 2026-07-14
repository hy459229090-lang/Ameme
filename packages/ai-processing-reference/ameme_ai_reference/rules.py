"""Deterministic R0 normalization, deduplication, drafting and merge rules."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from typing import Any, Iterable, Mapping
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from .errors import ErrorCode, ProcessingError
from .model import EventDraft, MergeDecision, canonical_json, stable_digest
from .schema import MachineContract


FACT_FIELDS = ("time", "place", "people", "action", "result", "intent", "description")
SALIENT_FIELDS = ("emotion", "relationship")
FIELD_ORDER = FACT_FIELDS + SALIENT_FIELDS
EVENT_TYPES = {
    "activity",
    "communication",
    "decision",
    "result",
    "state_change",
    "milestone",
    "experience",
}
STATUS_PRIORITY = {"inferred": 0, "planned": 1, "observed": 2, "user_asserted": 3}


@dataclass(frozen=True, slots=True)
class DedupResult:
    kept_ids: tuple[str, ...]
    duplicate_of: Mapping[str, str]


def normalize_time_range(
    start: str,
    timezone_name: str,
    *,
    end: str | None = None,
    precision: str = "minute",
) -> dict[str, Any]:
    try:
        timezone = ZoneInfo(timezone_name)
    except ZoneInfoNotFoundError as exc:
        raise ProcessingError(
            ErrorCode.TASK_INPUT_INVALID, safe_context={"reason": "timezone_unknown"}
        ) from exc

    def parse(value: str) -> datetime:
        try:
            parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError as exc:
            raise ProcessingError(
                ErrorCode.TASK_INPUT_INVALID, safe_context={"reason": "timestamp_invalid"}
            ) from exc
        return parsed.replace(tzinfo=timezone) if parsed.tzinfo is None else parsed.astimezone(timezone)

    start_value = parse(start)
    result: dict[str, Any] = {
        "start": start_value.isoformat(timespec="seconds"),
        "timezone": timezone_name,
        "precision": precision,
    }
    if end is not None:
        end_value = parse(end)
        if end_value < start_value:
            raise ProcessingError(
                ErrorCode.TASK_INPUT_INVALID, safe_context={"reason": "end_precedes_start"}
            )
        result["end"] = end_value.isoformat(timespec="seconds")
    if precision == "range" and end is None:
        raise ProcessingError(
            ErrorCode.TASK_INPUT_INVALID, safe_context={"reason": "range_requires_end"}
        )
    return result


def exact_deduplicate(source_objects: Iterable[Mapping[str, Any]]) -> DedupResult:
    kept: list[str] = []
    duplicate_of: dict[str, str] = {}
    seen: dict[tuple[str, str], str] = {}
    for item in sorted(source_objects, key=lambda value: str(value["source_object_id"])):
        object_id = str(item["source_object_id"])
        content_hash = item.get("content_hash")
        if not content_hash:
            kept.append(object_id)
            continue
        key = (str(item["space_id"]), str(content_hash))
        if key in seen:
            duplicate_of[object_id] = seen[key]
        else:
            seen[key] = object_id
            kept.append(object_id)
    return DedupResult(tuple(kept), duplicate_of)


def safe_deduplicate(observations: Iterable[Mapping[str, Any]]) -> DedupResult:
    kept: list[str] = []
    duplicate_of: dict[str, str] = {}
    seen: dict[str, str] = {}
    for item in sorted(observations, key=lambda value: str(value["observation_id"])):
        observation_id = str(item["observation_id"])
        if item.get("fact_status") == "inferred":
            kept.append(observation_id)
            continue
        signature = stable_digest(
            {
                "space_id": item.get("space_id"),
                "fact_status": item.get("fact_status"),
                "time_range": item.get("time_range"),
                "value": item.get("value"),
            }
        )
        if signature in seen:
            duplicate_of[observation_id] = seen[signature]
        else:
            seen[signature] = observation_id
            kept.append(observation_id)
    return DedupResult(tuple(kept), duplicate_of)


def observation_from_addendum(
    addendum: Mapping[str, Any],
    *,
    observation_id: str,
    source_object_id: str,
    contract: MachineContract,
) -> dict[str, Any]:
    contract.validate("user_addendum", addendum)
    value: dict[str, Any] = {"description": addendum["text"]}
    if addendum["target_type"] == "event":
        value["correction"] = True
    observation: dict[str, Any] = {
        "schema_version": 1,
        "observation_id": observation_id,
        "source_object_id": source_object_id,
        "space_id": addendum["space_id"],
        "kind": "user_addendum",
        "value": value,
        "fact_status": "user_asserted",
        "confidence": 1.0,
        "parser_version": "r0-user-addendum-v1",
        "created_at": addendum["submitted_at"],
    }
    if addendum.get("event_time"):
        observation["time_range"] = addendum["event_time"]
    contract.validate("observation", observation)
    return observation


def build_event_draft(
    observations: Iterable[Mapping[str, Any]],
    *,
    contract: MachineContract,
) -> EventDraft:
    items = [dict(item) for item in observations]
    if not items:
        raise ProcessingError(ErrorCode.DATA_INSUFFICIENT)
    for item in items:
        contract.validate("observation", item)
    spaces = {item["space_id"] for item in items}
    if len(spaces) != 1:
        raise ProcessingError(ErrorCode.SPACE_DENIED)
    items.sort(key=lambda item: item["observation_id"])

    selected: dict[str, tuple[Any, list[str], float, str]] = {}
    superseded: set[str] = set()
    fact_conflict = False
    for field in FIELD_ORDER:
        choices: list[tuple[int, str, Any, Mapping[str, Any]]] = []
        for item in items:
            if field == "time":
                if "time_range" not in item:
                    continue
                value = item["time_range"]
            else:
                if field not in item["value"]:
                    continue
                value = item["value"][field]
            priority = STATUS_PRIORITY[item["fact_status"]]
            if item["value"].get("correction") is True and item["fact_status"] == "user_asserted":
                priority += 1
            choices.append((priority, canonical_json(value), value, item))
        if not choices:
            continue
        top_priority = max(choice[0] for choice in choices)
        top = [choice for choice in choices if choice[0] == top_priority]
        for choice in choices:
            if choice[0] < top_priority:
                superseded.add(str(choice[3]["observation_id"]))
        values = {choice[1] for choice in top}
        evidence_ids = sorted(str(choice[3]["observation_id"]) for choice in top)
        confidence = max(float(choice[3]["confidence"]) for choice in top)
        statuses = {str(choice[3]["fact_status"]) for choice in top}
        if len(values) > 1:
            if field in FACT_FIELDS:
                fact_conflict = True
            status = "conflict"
            value = [choice[2] for choice in sorted(top, key=lambda choice: choice[1])]
        else:
            value = top[0][2]
            status = "user_asserted" if "user_asserted" in statuses else sorted(statuses)[-1]
        selected[field] = (value, evidence_ids, confidence, status)

    if not any(field in selected for field in FACT_FIELDS):
        raise ProcessingError(ErrorCode.DATA_INSUFFICIENT)

    evidence = [
        {
            "field": field,
            "observation_ids": selected[field][1],
            "confidence": selected[field][2],
            "status": selected[field][3],
        }
        for field in FIELD_ORDER
        if field in selected
    ]
    factual_statuses = {
        selected[field][3] for field in FACT_FIELDS if field in selected
    }
    if fact_conflict or "conflict" in factual_statuses:
        fact_status = "conflict"
        candidate_status = "conflict"
    elif factual_statuses == {"planned"}:
        fact_status = "planned"
        candidate_status = "candidate"
    elif "user_asserted" in factual_statuses:
        fact_status = "user_asserted"
        candidate_status = "candidate"
    else:
        fact_status = "low_confidence_candidate"
        candidate_status = "candidate"

    semantic_fields = {field: selected[field][0] for field in selected}
    selected_fact_ids = {
        observation_id
        for field in FACT_FIELDS
        if field in selected
        for observation_id in selected[field][1]
    }
    explicit_types = [
        (
            STATUS_PRIORITY[item["fact_status"]]
            + (1 if item["value"].get("correction") is True else 0),
            str(item["observation_id"]),
            str(item["value"]["event_type"]),
        )
        for item in items
        if item["observation_id"] in selected_fact_ids
        and item["value"].get("event_type") in EVENT_TYPES
    ]
    event_type = max(explicit_types)[2] if explicit_types else "activity"
    candidate_seed = {
        "space_id": next(iter(spaces)),
        "observation_ids": [item["observation_id"] for item in items],
        "fields": semantic_fields,
        "fact_status": fact_status,
    }
    candidate: dict[str, Any] = {
        "schema_version": 1,
        "candidate_id": f"cand_{stable_digest(candidate_seed)[:24]}",
        "space_id": next(iter(spaces)),
        "event_type": event_type,
        "field_evidence": evidence,
        "status": candidate_status,
        "created_at": max(str(item["created_at"]) for item in items),
    }
    if "time" in selected:
        candidate["time_range"] = selected["time"][0]
    contract.validate_event_candidate(candidate, items)

    factual_confidences = [
        float(selected[field][2]) for field in FACT_FIELDS if field in selected
    ]
    fact_confidence = round(sum(factual_confidences) / len(factual_confidences), 6)
    selected_title = selected.get("action", selected.get("description"))
    title = (
        str(selected_title[0])[:200]
        if selected_title is not None and isinstance(selected_title[0], str)
        else "待整理事件"
    )
    description = (
        str(selected["description"][0])[:4000]
        if "description" in selected and isinstance(selected["description"][0], str)
        else None
    )
    salience = {
        "importance": max(float(item["value"].get("importance", 0.0)) for item in items),
        "emotional": max(float(item["value"].get("emotional_salience", 0.0)) for item in items),
        "relationship": max(
            float(item["value"].get("relationship_salience", 0.0)) for item in items
        ),
    }
    return EventDraft(
        candidate=candidate,
        title=title,
        description=description,
        fact_status=fact_status,
        fact_confidence=fact_confidence,
        salience=salience,
        semantic_fields=semantic_fields,
        source_object_ids=tuple(sorted({str(item["source_object_id"]) for item in items})),
        superseded_evidence_ids=tuple(sorted(superseded)),
    )


def draft_from_provider_candidate(
    candidate: Mapping[str, Any],
    observations: Iterable[Mapping[str, Any]],
    *,
    contract: MachineContract,
    r0_draft: EventDraft,
) -> EventDraft:
    """Rebuild internal metadata solely from a validated provider candidate."""

    items = [dict(item) for item in observations]
    contract.validate_event_candidate(
        candidate,
        items,
        provider_output=True,
        r0_candidate=r0_draft.candidate,
    )
    known = {str(item["observation_id"]): item for item in items}
    semantic_fields: dict[str, Any] = {}
    referenced_ids: set[str] = set()
    factual_confidences: list[float] = []
    for evidence in candidate["field_evidence"]:
        field = str(evidence["field"])
        observation_ids = [str(item) for item in evidence["observation_ids"]]
        referenced_ids.update(observation_ids)
        if field in FACT_FIELDS:
            factual_confidences.append(float(evidence["confidence"]))
        if field == "time":
            semantic_fields[field] = candidate["time_range"]
            continue
        values: dict[str, Any] = {}
        for observation_id in observation_ids:
            observation = known[observation_id]
            if field == "description":
                value = observation["value"].get("description")
            else:
                value = observation["value"].get(field)
            values[canonical_json(value)] = value
        semantic_fields[field] = (
            next(iter(values.values()))
            if len(values) == 1
            else [values[key] for key in sorted(values)]
        )

    factual_statuses = {
        str(evidence["status"])
        for evidence in candidate["field_evidence"]
        if evidence["field"] in FACT_FIELDS
    }
    if "conflict" in factual_statuses:
        fact_status = "conflict"
    elif factual_statuses == {"planned"}:
        fact_status = "planned"
    elif "user_asserted" in factual_statuses:
        fact_status = "user_asserted"
    else:
        fact_status = "low_confidence_candidate"

    selected_title = semantic_fields.get("action", semantic_fields.get("description"))
    title = str(selected_title)[:200] if isinstance(selected_title, str) else "待整理事件"
    description_value = semantic_fields.get("description")
    description = (
        str(description_value)[:4000] if isinstance(description_value, str) else None
    )
    referenced = [known[item] for item in sorted(referenced_ids)]
    salience = {
        "importance": max(
            (float(item["value"].get("importance", 0.0)) for item in referenced),
            default=0.0,
        ),
        "emotional": max(
            (float(item["value"].get("emotional_salience", 0.0)) for item in referenced),
            default=0.0,
        ),
        "relationship": max(
            (float(item["value"].get("relationship_salience", 0.0)) for item in referenced),
            default=0.0,
        ),
    }
    return EventDraft(
        candidate=candidate,
        title=title,
        description=description,
        fact_status=fact_status,
        fact_confidence=round(
            sum(factual_confidences) / len(factual_confidences), 6
        ),
        salience=salience,
        semantic_fields=semantic_fields,
        source_object_ids=tuple(
            sorted({str(item["source_object_id"]) for item in referenced})
        ),
        superseded_evidence_ids=(),
        fallback_reason=None,
    )


def decide_merge(left: EventDraft, right: EventDraft) -> MergeDecision:
    ids = (str(left.candidate["candidate_id"]), str(right.candidate["candidate_id"]))
    if left.candidate["space_id"] != right.candidate["space_id"]:
        return MergeDecision("separate", "SPACE_BOUNDARY", ids)
    if ids[0] == ids[1]:
        return MergeDecision("deduplicate", "EXACT_DUPLICATE", ids)
    planned = {left.fact_status == "planned", right.fact_status == "planned"}
    if planned == {True, False}:
        return MergeDecision("separate", "PLANNED_VS_HAPPENED", ids)
    left_intent = left.semantic_fields.get("intent")
    right_intent = right.semantic_fields.get("intent")
    if left_intent and right_intent and canonical_json(left_intent) != canonical_json(right_intent):
        return MergeDecision("separate", "INTENT_MISMATCH", ids)
    left_time = left.candidate.get("time_range")
    right_time = right.candidate.get("time_range")
    if left_time and right_time and not _ranges_overlap(left_time, right_time):
        return MergeDecision("separate", "TIME_DISJOINT", ids)
    left_action = left.semantic_fields.get("action")
    right_action = right.semantic_fields.get("action")
    if left_action and right_action and canonical_json(left_action) == canonical_json(right_action):
        return MergeDecision("merge", "COMPLEMENTARY_EVIDENCE", ids)
    return MergeDecision("relate", "INSUFFICIENT_MERGE_EVIDENCE", ids)


def _ranges_overlap(left: Mapping[str, Any], right: Mapping[str, Any]) -> bool:
    def bounds(value: Mapping[str, Any]) -> tuple[datetime, datetime]:
        start = datetime.fromisoformat(str(value["start"]).replace("Z", "+00:00"))
        end = datetime.fromisoformat(str(value.get("end", value["start"])).replace("Z", "+00:00"))
        return start, end

    left_start, left_end = bounds(left)
    right_start, right_end = bounds(right)
    return left_start <= right_end and right_start <= left_end
