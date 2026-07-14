"""Immutable reference values for task routing and deterministic drafts."""

from __future__ import annotations

from dataclasses import dataclass, field
import hashlib
import json
from types import MappingProxyType
from typing import Any, Mapping


def canonical_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def stable_digest(value: Any) -> str:
    return hashlib.sha256(canonical_json(value).encode("utf-8")).hexdigest()


@dataclass(frozen=True, slots=True)
class TaskSpec:
    task_id: str
    task_version: str
    input_schema: str
    output_schema: str
    default_tier: str
    cloud_allowed: bool
    enabled: bool = True


@dataclass(frozen=True, slots=True)
class ContentBlock:
    evidence_id: str
    content: str
    synthetic: bool = False


@dataclass(frozen=True, slots=True)
class PromptEnvelope:
    task_id: str
    task_version: str
    input_schema: str
    output_schema: str
    prompt_template_version: str
    policy_version: str
    adapter_name: str
    locale: str
    space_id: str
    evidence_manifest: tuple[str, ...]
    content_blocks: tuple[ContentBlock, ...]
    constraints: Mapping[str, Any] = field(default_factory=dict)
    synthetic_fixture_id: str | None = None

    def __post_init__(self) -> None:
        object.__setattr__(self, "constraints", MappingProxyType(dict(self.constraints)))

    @property
    def input_hash(self) -> str:
        return stable_digest(
            {
                "task_id": self.task_id,
                "task_version": self.task_version,
                "space_id": self.space_id,
                "evidence_manifest": self.evidence_manifest,
                "content_blocks": [
                    {"evidence_id": block.evidence_id, "content": block.content}
                    for block in self.content_blocks
                ],
                "constraints": dict(self.constraints),
            }
        )


@dataclass(frozen=True, slots=True)
class PolicyContext:
    """Caller-supplied authorization snapshot; not a complete Grant evaluator."""

    space_id: str
    purpose: str
    sensitivity: str
    processing_locations: frozenset[str]
    allowed_provider_adapters: frozenset[str] = frozenset()
    deleted_evidence_ids: frozenset[str] = frozenset()
    revoked_evidence_ids: frozenset[str] = frozenset()
    policy_generation: str = "policy-snapshot-v1"
    revocation_generation: str = "revocation-snapshot-v1"
    budget_remaining: int = 0


@dataclass(frozen=True, slots=True)
class EventDraft:
    """Internal-only draft; only ``candidate`` is a machine-contract object."""

    candidate: Mapping[str, Any]
    title: str
    description: str | None
    fact_status: str
    fact_confidence: float
    salience: Mapping[str, float]
    semantic_fields: Mapping[str, Any]
    source_object_ids: tuple[str, ...]
    superseded_evidence_ids: tuple[str, ...]
    rule_version: str = "r0-event-draft-v1"
    fallback_reason: str | None = None

    def __post_init__(self) -> None:
        object.__setattr__(self, "candidate", MappingProxyType(dict(self.candidate)))
        object.__setattr__(self, "salience", MappingProxyType(dict(self.salience)))
        object.__setattr__(self, "semantic_fields", MappingProxyType(dict(self.semantic_fields)))


@dataclass(frozen=True, slots=True)
class MergeDecision:
    action: str
    reason_code: str
    candidate_ids: tuple[str, str]


@dataclass(frozen=True, slots=True)
class SummaryDecision:
    state: str
    text: str | None
    reason_code: str
