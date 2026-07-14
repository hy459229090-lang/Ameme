"""No-network reference implementation for Ameme AI task semantics."""

from .errors import ErrorCode, ProcessingError
from .model import (
    ContentBlock,
    EventDraft,
    MergeDecision,
    PolicyContext,
    PromptEnvelope,
    SummaryDecision,
    TaskSpec,
)
from .observability import SafeObserver
from .pipeline import AIProcessingReference
from .policy import PrivacyPolicyGate
from .provider import FakeSyntheticProvider, ProviderAdapter
from .registry import TaskRegistry, default_registry
from .rules import (
    DedupResult,
    build_event_draft,
    decide_merge,
    exact_deduplicate,
    normalize_time_range,
    observation_from_addendum,
    safe_deduplicate,
)
from .schema import MachineContract

__all__ = [
    "AIProcessingReference",
    "ContentBlock",
    "DedupResult",
    "ErrorCode",
    "EventDraft",
    "FakeSyntheticProvider",
    "MachineContract",
    "MergeDecision",
    "PolicyContext",
    "PrivacyPolicyGate",
    "ProcessingError",
    "PromptEnvelope",
    "ProviderAdapter",
    "SafeObserver",
    "SummaryDecision",
    "TaskRegistry",
    "TaskSpec",
    "build_event_draft",
    "decide_merge",
    "default_registry",
    "exact_deduplicate",
    "normalize_time_range",
    "observation_from_addendum",
    "safe_deduplicate",
]
