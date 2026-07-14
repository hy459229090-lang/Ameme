"""Privacy, policy, deletion and budget gates evaluated before providers."""

from __future__ import annotations

from .errors import ErrorCode, ProcessingError
from .model import PolicyContext, PromptEnvelope, TaskSpec
from .provider import ProviderAdapter


class PrivacyPolicyGate:
    def authorize(
        self,
        envelope: PromptEnvelope,
        spec: TaskSpec,
        context: PolicyContext,
        adapter: ProviderAdapter | None,
    ) -> None:
        if envelope.space_id != context.space_id:
            raise ProcessingError(ErrorCode.SPACE_DENIED)
        if envelope.task_id != spec.task_id or envelope.task_version != spec.task_version:
            raise ProcessingError(ErrorCode.TASK_INPUT_INVALID)
        if not context.purpose:
            raise ProcessingError(ErrorCode.PURPOSE_DENIED)

        manifest = set(envelope.evidence_manifest)
        if len(manifest) != len(envelope.evidence_manifest):
            raise ProcessingError(ErrorCode.MISSING_EVIDENCE, safe_context={"reason": "duplicate_manifest"})
        if any(block.evidence_id not in manifest for block in envelope.content_blocks):
            raise ProcessingError(ErrorCode.MISSING_EVIDENCE, safe_context={"reason": "block_not_manifested"})
        if manifest & context.deleted_evidence_ids:
            raise ProcessingError(ErrorCode.DELETED_INPUT)

        if adapter is None:
            return
        if context.sensitivity == "restricted" and adapter.processing_location == "model_provider":
            raise ProcessingError(ErrorCode.SENSITIVE_MODEL_BLOCKED)
        if (
            not spec.cloud_allowed
            or adapter.processing_location not in context.processing_locations
            or adapter.adapter_name not in context.allowed_provider_adapters
        ):
            raise ProcessingError(ErrorCode.PROVIDER_NOT_ALLOWED)
        if context.budget_remaining <= 0:
            raise ProcessingError(ErrorCode.BUDGET_EXHAUSTED)
