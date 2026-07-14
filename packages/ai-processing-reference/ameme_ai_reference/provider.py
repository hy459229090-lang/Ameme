"""Provider-neutral interface and an offline synthetic-only fake adapter."""

from __future__ import annotations

from copy import deepcopy
from typing import Any, Mapping, Protocol

from .errors import ErrorCode, ProcessingError
from .model import PromptEnvelope


class ProviderAdapter(Protocol):
    adapter_name: str
    processing_location: str

    def generate(self, envelope: PromptEnvelope) -> Mapping[str, Any]: ...


class FakeSyntheticProvider:
    """Deterministic fixture adapter. It has no network implementation."""

    adapter_name = "fake-synthetic-provider"
    processing_location = "model_provider"

    def __init__(self, responses: Mapping[str, Mapping[str, Any]]) -> None:
        self._responses = deepcopy(dict(responses))
        self.calls = 0

    def generate(self, envelope: PromptEnvelope) -> Mapping[str, Any]:
        if not envelope.synthetic_fixture_id or not all(
            block.synthetic for block in envelope.content_blocks
        ):
            raise ProcessingError(ErrorCode.PROVIDER_ERROR, safe_context={"reason": "synthetic_only"})
        response = self._responses.get(envelope.synthetic_fixture_id)
        if response is None:
            raise ProcessingError(ErrorCode.PROVIDER_ERROR, safe_context={"reason": "fixture_missing"})
        self.calls += 1
        return deepcopy(response)
