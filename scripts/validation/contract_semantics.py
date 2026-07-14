# Purpose: enforce Ameme cross-field and cross-object invariants that JSON Schema cannot express alone.
# Input: validated synthetic wrappers using the public object_type/object contract shape.
# Output: SemanticResult containing deterministic check count and invariant-coded errors.

from __future__ import annotations

import re
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Iterable


OBJECT_ID_FIELDS = {
    "acquisition_contract": "contract_id",
    "source_object": "source_object_id",
    "observation": "observation_id",
    "user_addendum": "addendum_id",
    "event_candidate": "candidate_id",
    "event": "event_id",
    "event_revision": "event_revision_id",
    "episode": "episode_id",
    "episode_revision": "episode_revision_id",
    "artifact": "artifact_id",
    "day_ledger": "day_ledger_id",
    "summary": "summary_id",
    "feedback_event": "feedback_id",
    "access_grant": "grant_id",
    "recall_query": "query_id",
    "recall_page": "query_id",
    "context_pack": "context_pack_id",
    "lineage_edge": "lineage_edge_id",
    "sync_envelope": "sync_envelope_id",
    "deletion_job": "deletion_job_id",
    "export_job": "export_job_id",
}

SENSITIVITY_RANK = {"public": 0, "personal": 1, "confidential": 2, "restricted": 3}
WINDOWS_ABSOLUTE_PATH = re.compile(r"^[A-Za-z]:[\\/]")


@dataclass
class SemanticResult:
    checks: int = 0
    errors: list[str] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return not self.errors

    def check(self, condition: bool, code: str, message: str) -> None:
        self.checks += 1
        if not condition:
            self.errors.append(f"{code}: {message}")


def _timestamp(value: Any) -> datetime | None:
    if not isinstance(value, str):
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        return parsed if parsed.tzinfo is not None else None
    except ValueError:
        return None


def _index(wrappers: Iterable[dict[str, Any]]) -> dict[str, dict[str, dict[str, Any]]]:
    result: dict[str, dict[str, dict[str, Any]]] = {}
    for wrapper in wrappers:
        object_type = wrapper.get("object_type")
        payload = wrapper.get("object")
        id_field = OBJECT_ID_FIELDS.get(object_type)
        if isinstance(payload, dict) and id_field and isinstance(payload.get(id_field), str):
            result.setdefault(object_type, {})[payload[id_field]] = payload
    return result


def _iter_named_dicts(value: Any, path: str = "object") -> Iterable[tuple[str, dict[str, Any]]]:
    if isinstance(value, dict):
        yield path, value
        for key, child in value.items():
            yield from _iter_named_dicts(child, f"{path}.{key}")
    elif isinstance(value, list):
        for index, child in enumerate(value):
            yield from _iter_named_dicts(child, f"{path}[{index}]")


def _validate_common(payload: dict[str, Any], object_type: str, result: SemanticResult) -> None:
    for path, value in _iter_named_dicts(payload):
        if "start" in value and "precision" in value:
            start = _timestamp(value.get("start"))
            end = _timestamp(value.get("end"))
            if "end" in value:
                result.check(start is not None and end is not None and end >= start, "INV-TIME-001", f"{object_type} {path}.end precedes start")
            if value.get("precision") == "range":
                result.check("end" in value, "INV-TIME-002", f"{object_type} {path} precision=range requires end")
        if "retention_class" in value and "deletion_state" in value:
            if value.get("retention_class") == "ephemeral_recovery":
                result.check("expires_at" in value, "INV-RET-001", f"{object_type} {path} ephemeral retention requires expires_at")


def _validate_contracts(index: dict[str, dict[str, dict[str, Any]]], result: SemanticResult) -> None:
    for contract in index.get("acquisition_contract", {}).values():
        valid_from = _timestamp(contract.get("valid_from"))
        expires_at = _timestamp(contract.get("expires_at"))
        if expires_at is not None:
            result.check(valid_from is not None and expires_at > valid_from, "INV-AUTH-001", f"contract {contract['contract_id']} expires before valid_from")
        if contract.get("revocation_state") == "expired":
            result.check(expires_at is not None, "INV-AUTH-002", f"expired contract {contract['contract_id']} requires expires_at")


def _validate_sources(index: dict[str, dict[str, dict[str, Any]]], result: SemanticResult) -> None:
    contracts = index.get("acquisition_contract", {})
    for source in index.get("source_object", {}).values():
        contract = contracts.get(source.get("contract_id"))
        result.check(contract is not None, "INV-SOURCE-001", f"source {source['source_object_id']} references missing contract")
        if contract is not None:
            for field_name in ("owner_id", "space_id", "device_id", "source_type"):
                result.check(source.get(field_name) == contract.get(field_name), "INV-SOURCE-002", f"source {source['source_object_id']} {field_name} differs from contract")
        locator = source.get("source_locator")
        if isinstance(locator, dict):
            opaque = locator.get("opaque_locator_ref", "")
            result.check(not WINDOWS_ABSOLUTE_PATH.match(opaque) and not opaque.startswith(("/", "file://")), "INV-SOURCE-003", f"source {source['source_object_id']} locator contains an absolute path")
            result.check(not (opaque.startswith(("http://", "https://")) and "?" in opaque), "INV-SOURCE-004", f"source {source['source_object_id']} locator contains a full URL query")


def _validate_events(index: dict[str, dict[str, dict[str, Any]]], result: SemanticResult) -> None:
    sources = index.get("source_object", {})
    observations = index.get("observation", {})
    revisions = index.get("event_revision", {})
    for observation in observations.values():
        source = sources.get(observation.get("source_object_id"))
        result.check(source is not None, "INV-LINEAGE-001", f"observation {observation['observation_id']} references missing source")
        if source is not None:
            result.check(observation.get("space_id") == source.get("space_id"), "INV-SPACE-001", f"observation {observation['observation_id']} crosses source space")

    for event in index.get("event", {}).values():
        source_ids = set(event.get("source_object_ids", []))
        evidence_ids: set[str] = set()
        evidence_statuses: set[str] = set()
        for evidence in event.get("field_evidence", []):
            observation_ids = evidence.get("observation_ids", [])
            result.check(bool(observation_ids), "INV-EVIDENCE-001", f"event {event['event_id']} evidence {evidence.get('field')} has no observations")
            evidence_ids.update(observation_ids)
            evidence_statuses.add(evidence.get("status"))
            for observation_id in observation_ids:
                observation = observations.get(observation_id)
                result.check(observation is not None, "INV-EVIDENCE-002", f"event {event['event_id']} references missing observation {observation_id}")
                if observation is not None:
                    result.check(observation.get("space_id") == event.get("space_id"), "INV-SPACE-002", f"event {event['event_id']} evidence crosses space")
                    result.check(observation.get("source_object_id") in source_ids, "INV-LINEAGE-002", f"event {event['event_id']} evidence source is absent from source_object_ids")
        evidence_fields = {evidence.get("field") for evidence in event.get("field_evidence", [])}
        result.check("time" in evidence_fields, "INV-EVIDENCE-003", f"event {event['event_id']} time_range has no time evidence")
        result.check(bool(evidence_fields & {"action", "result", "intent", "description"}), "INV-EVIDENCE-004", f"event {event['event_id']} title has no factual evidence")
        if "description" in event:
            result.check("description" in evidence_fields, "INV-EVIDENCE-005", f"event {event['event_id']} description has no description evidence")
        for source_id in source_ids:
            source = sources.get(source_id)
            result.check(source is not None, "INV-LINEAGE-003", f"event {event['event_id']} references missing source {source_id}")
            if source is not None:
                result.check(source.get("space_id") == event.get("space_id"), "INV-SPACE-003", f"event {event['event_id']} source crosses space")
                result.check(source.get("owner_id") == event.get("owner_id"), "INV-OWNER-001", f"event {event['event_id']} source crosses owner")
                source_rank = SENSITIVITY_RANK.get(source.get("sensitivity"), -1)
                event_rank = SENSITIVITY_RANK.get(event.get("sensitivity"), -1)
                result.check(event_rank >= source_rank, "INV-SENS-001", f"event {event['event_id']} lowers source sensitivity")
        if evidence_statuses and evidence_statuses <= {"planned"}:
            result.check(event.get("fact_status") == "planned", "INV-FACT-001", f"event {event['event_id']} upgrades planned-only evidence")
        head_id = event.get("revision_head_id")
        if head_id is not None:
            revision = revisions.get(head_id)
            result.check(revision is not None, "INV-REV-001", f"event {event['event_id']} references missing revision head")
            if revision is not None:
                result.check(revision.get("event_id") == event.get("event_id") and revision.get("revision") == event.get("revision"), "INV-REV-002", f"event {event['event_id']} head does not match current revision")

    for revision in revisions.values():
        result.check(revision.get("event_id") in index.get("event", {}), "INV-REV-005", f"revision {revision['event_revision_id']} references missing event")
        if revision.get("reason") == "initial":
            result.check(revision.get("revision") == 1 and "base_revision_id" not in revision, "INV-REV-003", f"initial revision {revision['event_revision_id']} must be revision 1 without a base")
        else:
            result.check("base_revision_id" in revision, "INV-REV-004", f"non-initial revision {revision['event_revision_id']} requires base_revision_id")


def _validate_episodes_and_ledgers(index: dict[str, dict[str, dict[str, Any]]], result: SemanticResult) -> None:
    events = index.get("event", {})
    episodes = index.get("episode", {})
    episode_revisions = index.get("episode_revision", {})
    for episode in episodes.values():
        for event_ref in episode.get("event_refs", []):
            event = events.get(event_ref.get("event_id"))
            result.check(event is not None, "INV-EPISODE-001", f"episode {episode['episode_id']} references missing event")
            if event is not None:
                result.check(event.get("owner_id") == episode.get("owner_id") and event.get("space_id") == episode.get("space_id"), "INV-EPISODE-002", f"episode {episode['episode_id']} crosses owner or space")
                result.check(event_ref.get("revision") <= event.get("revision", 0), "INV-EPISODE-003", f"episode {episode['episode_id']} references a future event revision")
        head_id = episode.get("revision_head_id")
        if head_id is not None:
            revision = episode_revisions.get(head_id)
            result.check(revision is not None and revision.get("episode_id") == episode.get("episode_id") and revision.get("revision") == episode.get("revision"), "INV-EPISODE-004", f"episode {episode['episode_id']} head does not match current revision")

    ledgers = index.get("day_ledger", {})
    for ledger in ledgers.values():
        if ledger.get("coverage_state") == "partial":
            result.check(bool(ledger.get("partial_reasons")), "INV-RANGE-001", f"partial ledger {ledger['day_ledger_id']} requires partial_reasons")
        for entry in ledger.get("entries", []):
            target = index.get(entry.get("object_type"), {}).get(entry.get("object_id"))
            result.check(target is not None, "INV-LEDGER-001", f"ledger {ledger['day_ledger_id']} references missing entry")
            if target is not None:
                result.check(target.get("owner_id") == ledger.get("owner_id") and target.get("space_id") == ledger.get("space_id"), "INV-LEDGER-002", f"ledger {ledger['day_ledger_id']} entry crosses owner or space")
                result.check(entry.get("revision") <= target.get("revision", 0), "INV-LEDGER-003", f"ledger {ledger['day_ledger_id']} references a future revision")

    for summary in index.get("summary", {}).values():
        ledger = ledgers.get(summary.get("day_ledger_id"))
        result.check(ledger is not None, "INV-SUMMARY-001", f"summary {summary['summary_id']} references missing ledger")
        if ledger is not None:
            result.check(summary.get("based_on_revision", 0) <= ledger.get("revision", 0), "INV-SUMMARY-002", f"summary {summary['summary_id']} references a future ledger revision")


def _validate_grants_and_context(index: dict[str, dict[str, dict[str, Any]]], result: SemanticResult) -> None:
    grants = index.get("access_grant", {})
    queries = index.get("recall_query", {})
    for grant in grants.values():
        not_before = _timestamp(grant.get("not_before"))
        expires_at = _timestamp(grant.get("expires_at"))
        result.check(not_before is not None and expires_at is not None and expires_at > not_before, "INV-GRANT-001", f"grant {grant['grant_id']} has an invalid validity window")
        if grant.get("status") == "revoked":
            result.check("revoked_at" in grant, "INV-GRANT-002", f"revoked grant {grant['grant_id']} requires revoked_at")
        else:
            result.check("revoked_at" not in grant, "INV-GRANT-003", f"non-revoked grant {grant['grant_id']} carries revoked_at")

    for query in queries.values():
        grant_id = query.get("grant_id")
        if grant_id is None:
            continue
        grant = grants.get(grant_id)
        result.check(grant is not None, "INV-GRANT-004", f"query {query['query_id']} references missing grant")
        if grant is not None:
            result.check(query.get("caller_id") == grant.get("caller_id"), "INV-GRANT-005", f"query {query['query_id']} caller differs from grant")
            result.check(query.get("purpose") in grant.get("purposes", []), "INV-GRANT-006", f"query {query['query_id']} purpose exceeds grant")
            result.check(set(query.get("requested_spaces", [])) <= set(grant.get("spaces", [])), "INV-GRANT-007", f"query {query['query_id']} spaces exceed grant")
            result.check(set(query.get("data_types", [])) <= set(grant.get("data_types", [])), "INV-GRANT-008", f"query {query['query_id']} data types exceed grant")
            created = _timestamp(query.get("created_at"))
            start = _timestamp(grant.get("not_before"))
            end = _timestamp(grant.get("expires_at"))
            result.check(created is not None and start is not None and end is not None and start <= created < end and grant.get("status") == "active", "INV-GRANT-009", f"query {query['query_id']} uses an inactive or out-of-window grant")

    for context in index.get("context_pack", {}).values():
        grant = grants.get(context.get("grant_id"))
        query = queries.get(context.get("query_id"))
        result.check(grant is not None and query is not None, "INV-CONTEXT-001", f"context {context['context_pack_id']} references missing grant or query")
        if grant is not None and query is not None:
            result.check(context.get("caller_id") == grant.get("caller_id") == query.get("caller_id"), "INV-CONTEXT-002", f"context {context['context_pack_id']} caller binding differs")
            result.check(context.get("purpose") == query.get("purpose") and context.get("purpose") in grant.get("purposes", []), "INV-CONTEXT-003", f"context {context['context_pack_id']} purpose binding differs")
        created = _timestamp(context.get("created_at"))
        expires = _timestamp(context.get("expires_at"))
        result.check(created is not None and expires is not None and expires > created, "INV-CONTEXT-004", f"context {context['context_pack_id']} has an invalid expiry")
        if context.get("state") == "denied":
            result.check(bool(context.get("denial_reason")) and not context.get("items"), "INV-CONTEXT-005", f"denied context {context['context_pack_id']} must have a reason and no items")
        else:
            result.check("denial_reason" not in context, "INV-CONTEXT-006", f"non-denied context {context['context_pack_id']} carries denial_reason")
        for item in context.get("items", []):
            result.check(item.get("object_id") in index.get(item.get("object_type"), {}), "INV-CONTEXT-007", f"context {context['context_pack_id']} references a missing item")

    for page in index.get("recall_page", {}).values():
        if page.get("range_state") == "partial":
            result.check(bool(page.get("partial_reasons")), "INV-RANGE-002", f"partial recall page {page['query_id']} requires partial_reasons")
        for item in page.get("results", []):
            result.check(item.get("object_id") in index.get(item.get("object_type"), {}), "INV-RECALL-001", f"recall page {page['query_id']} references a missing result")


def _validate_lineage_sync_and_deletion(index: dict[str, dict[str, dict[str, Any]]], result: SemanticResult) -> None:
    for edge in index.get("lineage_edge", {}).values():
        source = index.get(edge.get("from_type"), {}).get(edge.get("from_id"))
        target = index.get(edge.get("to_type"), {}).get(edge.get("to_id"))
        result.check(source is not None, "INV-LINEAGE-004", f"lineage {edge['lineage_edge_id']} from object is missing")
        result.check(target is not None, "INV-LINEAGE-005", f"lineage {edge['lineage_edge_id']} dependency is missing")
        if source is not None and target is not None and edge.get("relationship") in {"derived_from", "observed_from", "summarizes", "indexed_from"}:
            result.check(edge.get("from_type") not in {"source_object", "observation"} or edge.get("to_type") == "source_object", "INV-LINEAGE-006", f"lineage {edge['lineage_edge_id']} reverses derived-to-dependency direction")

    for envelope in index.get("sync_envelope", {}).values():
        if envelope.get("operation") == "append_revision":
            result.check("base_revision" in envelope and isinstance(envelope.get("payload"), dict), "INV-SYNC-001", f"append envelope {envelope['sync_envelope_id']} requires base_revision and payload")
        if envelope.get("operation") == "tombstone":
            result.check(envelope.get("object_type") != "tombstone", "INV-SYNC-002", f"tombstone envelope {envelope['sync_envelope_id']} must target the deleted object type")

    expected_target = {
        "remove_source_only": "source_object",
        "delete_event_and_dependents": "event",
        "delete_source_contract": "source",
        "delete_space": "space",
        "delete_account": "account",
    }
    for job in index.get("deletion_job", {}).values():
        result.check(expected_target.get(job.get("scope")) == job.get("target_type"), "INV-DELETE-001", f"deletion {job['deletion_job_id']} scope and target_type differ")
        steps = job.get("steps", [])
        step_names = [step.get("step") for step in steps]
        result.check(len(step_names) == len(set(step_names)), "INV-DELETE-002", f"deletion {job['deletion_job_id']} repeats steps")
        if job.get("state") == "completed":
            result.check(bool(job.get("proof_hash")), "INV-DELETE-003", f"completed deletion {job['deletion_job_id']} requires proof_hash")
            result.check(not job.get("pending_replica_ids"), "INV-DELETE-004", f"completed deletion {job['deletion_job_id']} has pending replicas")
            result.check(all(step.get("state") in {"completed", "not_applicable"} for step in steps), "INV-DELETE-005", f"completed deletion {job['deletion_job_id']} has unfinished steps")
        if job.get("state") == "partial_failed":
            result.check(any(step.get("state") == "failed" for step in steps) or bool(job.get("pending_replica_ids")), "INV-DELETE-006", f"partial_failed deletion {job['deletion_job_id']} has no recorded failure")


def _validate_addenda(index: dict[str, dict[str, dict[str, Any]]], result: SemanticResult) -> None:
    for addendum in index.get("user_addendum", {}).values():
        target_type = addendum.get("target_type")
        target_id = addendum.get("target_id")
        if target_type == "event":
            target = index.get("event", {}).get(target_id)
        elif target_type in {"new_event", "day"}:
            target = index.get("day_ledger", {}).get(target_id)
        else:
            target = None
        result.check(target is not None, "INV-ADDENDUM-001", f"addendum {addendum['addendum_id']} target is missing")
        if target is not None:
            result.check(target.get("owner_id") == addendum.get("owner_id") and target.get("space_id") == addendum.get("space_id"), "INV-ADDENDUM-002", f"addendum {addendum['addendum_id']} crosses owner or space")


def validate_bundle(wrappers: list[dict[str, Any]]) -> SemanticResult:
    result = SemanticResult()
    index = _index(wrappers)
    for wrapper in wrappers:
        payload = wrapper.get("object")
        object_type = wrapper.get("object_type", "unknown")
        if isinstance(payload, dict):
            _validate_common(payload, object_type, result)
    _validate_contracts(index, result)
    _validate_sources(index, result)
    _validate_events(index, result)
    _validate_episodes_and_ledgers(index, result)
    _validate_grants_and_context(index, result)
    _validate_lineage_sync_and_deletion(index, result)
    _validate_addenda(index, result)
    return result
