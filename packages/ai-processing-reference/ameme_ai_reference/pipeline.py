"""Offline reference orchestration for R0 rules and optional provider adapters."""

from __future__ import annotations

from dataclasses import replace
from typing import Any, Iterable, Mapping

from .errors import ErrorCode, ProcessingError
from .model import ContentBlock, EventDraft, PolicyContext, PromptEnvelope, SummaryDecision, canonical_json
from .observability import SafeObserver
from .policy import PrivacyPolicyGate
from .provider import ProviderAdapter
from .registry import TaskRegistry, default_registry
from .rules import FACT_FIELDS, build_event_draft
from .schema import MachineContract


FALLBACK_CODES = {
    ErrorCode.BUDGET_EXHAUSTED,
    ErrorCode.SENSITIVE_MODEL_BLOCKED,
    ErrorCode.PROVIDER_NOT_ALLOWED,
    ErrorCode.PROVIDER_ERROR,
    ErrorCode.SCHEMA_MISMATCH,
    ErrorCode.MISSING_EVIDENCE,
    ErrorCode.DATA_INSUFFICIENT,
}


class AIProcessingReference:
    def __init__(
        self,
        *,
        registry: TaskRegistry | None = None,
        contract: MachineContract | None = None,
        observer: SafeObserver | None = None,
    ) -> None:
        self.registry = registry or default_registry()
        self.contract = contract or MachineContract()
        self.observer = observer or SafeObserver()
        self.gate = PrivacyPolicyGate()

    def create_candidate(
        self,
        observations: Iterable[Mapping[str, Any]],
        *,
        context: PolicyContext,
        provider: ProviderAdapter | None = None,
        synthetic_fixture_id: str | None = None,
    ) -> EventDraft:
        items = [dict(item) for item in observations]
        spec = self.registry.require("T06_EVENT_DRAFT")
        envelope = self._event_prompt(
            spec.task_version,
            spec.input_schema,
            spec.output_schema,
            items,
            context=context,
            adapter_name=provider.adapter_name if provider else "deterministic-r0",
            synthetic_fixture_id=synthetic_fixture_id,
        )

        # Local processing still obeys space, deletion, purpose and evidence gates.
        self.gate.authorize(envelope, spec, context, None)
        if any(item.get("space_id") != context.space_id for item in items):
            raise ProcessingError(ErrorCode.SPACE_DENIED)
        draft = build_event_draft(items, contract=self.contract)
        if provider is None:
            self._observe(envelope, "candidate", "R0_RULE_APPLIED", None)
            return draft

        try:
            self.gate.authorize(envelope, spec, context, provider)
            response = dict(provider.generate(envelope))
            self.contract.validate_event_candidate(response, items, provider_output=True)
            fact_confidence = self._candidate_fact_confidence(response)
            provider_draft = replace(
                draft,
                candidate=response,
                fact_confidence=fact_confidence,
                fallback_reason=None,
            )
            self._observe(envelope, "candidate", "PROVIDER_CANDIDATE_VALID", provider)
            return provider_draft
        except ProcessingError as exc:
            if exc.code not in FALLBACK_CODES:
                self._observe(envelope, "rejected", exc.code.value, provider)
                raise
            self._observe(envelope, "candidate", exc.code.value, provider)
            return replace(draft, fallback_reason=exc.code.value)

    def no_summary_when_unavailable(
        self,
        event_ids: Iterable[str],
        *,
        context: PolicyContext,
        provider: ProviderAdapter | None = None,
    ) -> SummaryDecision:
        ids = tuple(sorted(set(event_ids)))
        if not ids:
            return SummaryDecision("no_summary", None, ErrorCode.DATA_INSUFFICIENT.value)
        if context.deleted_evidence_ids & set(ids):
            return SummaryDecision("no_summary", None, ErrorCode.DELETED_INPUT.value)
        if context.budget_remaining <= 0:
            return SummaryDecision("no_summary", None, ErrorCode.BUDGET_EXHAUSTED.value)
        if provider is not None and context.sensitivity == "restricted":
            return SummaryDecision("no_summary", None, ErrorCode.SENSITIVE_MODEL_BLOCKED.value)
        return SummaryDecision("no_summary", None, "SUMMARY_NOT_IMPLEMENTED_IN_REFERENCE_SEGMENT")

    def _event_prompt(
        self,
        task_version: str,
        input_schema: str,
        output_schema: str,
        observations: list[dict[str, Any]],
        *,
        context: PolicyContext,
        adapter_name: str,
        synthetic_fixture_id: str | None,
    ) -> PromptEnvelope:
        evidence_ids = tuple(sorted(str(item["observation_id"]) for item in observations))
        by_id = {str(item["observation_id"]): item for item in observations}
        blocks = tuple(
            ContentBlock(
                evidence_id=evidence_id,
                content=canonical_json(by_id[evidence_id].get("value", {})),
                synthetic=synthetic_fixture_id is not None,
            )
            for evidence_id in evidence_ids
        )
        return PromptEnvelope(
            task_id="T06_EVENT_DRAFT",
            task_version=task_version,
            input_schema=input_schema,
            output_schema=output_schema,
            prompt_template_version="event-draft-zh-v1",
            policy_version="mvp-policy-v1",
            adapter_name=adapter_name,
            locale="zh-CN",
            space_id=context.space_id,
            evidence_manifest=evidence_ids,
            content_blocks=blocks,
            constraints={"no_unreferenced_facts": True, "max_candidates": 3},
            synthetic_fixture_id=synthetic_fixture_id,
        )

    def _candidate_fact_confidence(self, candidate: Mapping[str, Any]) -> float:
        values = [
            float(evidence["confidence"])
            for evidence in candidate["field_evidence"]
            if evidence["field"] in FACT_FIELDS
        ]
        if not values:
            raise ProcessingError(ErrorCode.DATA_INSUFFICIENT)
        return round(sum(values) / len(values), 6)

    def _observe(
        self,
        envelope: PromptEnvelope,
        result_state: str,
        result_code: str,
        provider: ProviderAdapter | None,
    ) -> None:
        self.observer.record(
            task_id=envelope.task_id,
            task_version=envelope.task_version,
            result_state=result_state,
            result_code=result_code,
            input_hash=envelope.input_hash,
            evidence_count=len(envelope.evidence_manifest),
            adapter_name=provider.adapter_name if provider else "deterministic-r0",
            processing_location=provider.processing_location if provider else "device",
        )
