from __future__ import annotations

from copy import deepcopy
from dataclasses import replace
import json
from pathlib import Path
import sys
import unittest


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "packages" / "ai-processing-reference"))

from ameme_ai_reference import (  # noqa: E402
    AIProcessingReference,
    ContentBlock,
    ErrorCode,
    FakeSyntheticProvider,
    PolicyContext,
    ProcessingError,
    PromptEnvelope,
    SafeObserver,
    ScopedAIRuntime,
)
from ameme_ai_reference.model import stable_digest  # noqa: E402


class AIRuntimeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.fixture = json.loads(
            (ROOT / "tests" / "fixtures" / "ai" / "synthetic_ai_eval.json").read_text(
                encoding="utf-8"
            )
        )

    def setUp(self) -> None:
        self.observer = SafeObserver()
        self.runtime = ScopedAIRuntime(
            self.observer,
            scope_hmac_key=b"fixed-synthetic-runtime-test-key-v1",
        )
        self.reference = AIProcessingReference(
            observer=self.observer,
            runtime=self.runtime,
        )
        self.context = PolicyContext(
            space_id="space_personal",
            purpose="form_today",
            sensitivity="personal",
            processing_locations=frozenset({"device", "model_provider"}),
            allowed_provider_adapters=frozenset({"fake-synthetic-provider"}),
            policy_generation="policy-test-v1",
            revocation_generation="revocation-test-v1",
            budget_remaining=2,
        )

    def observation(self) -> dict:
        return deepcopy(self.fixture["observations"]["sparse"])

    def envelope(self, fixture_id: str = "runtime") -> PromptEnvelope:
        return PromptEnvelope(
            task_id="T06_EVENT_DRAFT",
            task_version="1",
            input_schema="ObservationBundle/v1",
            output_schema="EventCandidate/v1",
            prompt_template_version="event-draft-zh-v1",
            policy_version="mvp-policy-v1",
            adapter_name="fake-synthetic-provider",
            locale="zh-CN",
            space_id="space_personal",
            evidence_manifest=("obs_sparse_photo",),
            content_blocks=(
                ContentBlock(
                    evidence_id="obs_sparse_photo",
                    content="fixed synthetic content",
                    synthetic=True,
                ),
            ),
            constraints={"no_unreferenced_facts": True},
            synthetic_fixture_id=fixture_id,
        )

    def test_cache_hit_revalidates_and_deleted_evidence_never_replays(self) -> None:
        provider = FakeSyntheticProvider(
            {"cache": deepcopy(self.fixture["fake_provider_candidate"])}
        )
        first = self.reference.create_candidate(
            [self.observation()],
            context=self.context,
            provider=provider,
            synthetic_fixture_id="cache",
        )
        second = self.reference.create_candidate(
            [self.observation()],
            context=self.context,
            provider=provider,
            synthetic_fixture_id="cache",
        )
        self.assertEqual(dict(first.candidate), dict(second.candidate))
        self.assertEqual(provider.calls, 1)

        deleted = replace(
            self.context,
            deleted_evidence_ids=frozenset({"obs_sparse_photo"}),
            revocation_generation="revocation-test-v2",
        )
        with self.assertRaises(ProcessingError) as raised:
            self.reference.create_candidate(
                [self.observation()],
                context=deleted,
                provider=provider,
                synthetic_fixture_id="cache",
            )
        self.assertEqual(raised.exception.code, ErrorCode.DELETED_INPUT)

        with self.assertRaises(ProcessingError) as replayed:
            self.reference.create_candidate(
                [self.observation()],
                context=self.context,
                provider=provider,
                synthetic_fixture_id="cache",
            )
        self.assertEqual(replayed.exception.code, ErrorCode.DELETED_INPUT)
        self.assertEqual(provider.calls, 1)
        cache_states = {
            record.get("cache_state")
            for record in self.observer.records
            if record.get("event_kind") in {"cache", "invalidation"}
        }
        self.assertTrue({"stored", "hit", "invalidated"} <= cache_states)

    def test_cached_provider_output_rechecks_current_policy_before_hit(self) -> None:
        provider = FakeSyntheticProvider(
            {"policy_cache": deepcopy(self.fixture["fake_provider_candidate"])}
        )
        self.reference.create_candidate(
            [self.observation()],
            context=self.context,
            provider=provider,
            synthetic_fixture_id="policy_cache",
        )
        self.assertEqual(provider.calls, 1)

        local_only = replace(
            self.context,
            processing_locations=frozenset({"device"}),
        )
        local_result = self.reference.create_candidate(
            [self.observation()],
            context=local_only,
            provider=provider,
            synthetic_fixture_id="policy_cache",
        )
        self.assertEqual(
            local_result.fallback_reason, ErrorCode.PROVIDER_NOT_ALLOWED.value
        )

        adapter_denied = replace(
            self.context,
            allowed_provider_adapters=frozenset(),
        )
        adapter_result = self.reference.create_candidate(
            [self.observation()],
            context=adapter_denied,
            provider=provider,
            synthetic_fixture_id="policy_cache",
        )
        self.assertEqual(
            adapter_result.fallback_reason, ErrorCode.PROVIDER_NOT_ALLOWED.value
        )

        restricted = replace(self.context, sensitivity="restricted")
        restricted_result = self.reference.create_candidate(
            [self.observation()],
            context=restricted,
            provider=provider,
            synthetic_fixture_id="policy_cache",
        )
        self.assertEqual(
            restricted_result.fallback_reason,
            ErrorCode.SENSITIVE_MODEL_BLOCKED.value,
        )
        self.assertEqual(provider.calls, 1)

    def test_cache_and_batch_are_scope_and_generation_bound(self) -> None:
        envelope = self.envelope()
        response = deepcopy(self.fixture["fake_provider_candidate"])
        self.runtime.cache_put(envelope, self.context, response)
        self.assertIsNotNone(self.runtime.cache_get(envelope, self.context))

        other_purpose = replace(self.context, purpose="search_history")
        self.assertIsNone(self.runtime.cache_get(envelope, other_purpose))
        newer_policy = replace(self.context, policy_generation="policy-test-v2")
        self.assertIsNone(self.runtime.cache_get(envelope, newer_policy))

        plain_scope_digest = stable_digest(
            {"purpose": self.context.purpose, "space_id": self.context.space_id}
        )
        self.assertNotEqual(self.runtime.scope_hash(self.context), plain_scope_digest)

        ticket = self.runtime.enqueue_batch(envelope, self.context)
        self.assertEqual(ticket.scope_hash, self.runtime.scope_hash(self.context))
        self.assertEqual(self.runtime.drain_batch(newer_policy), ())

    def test_revocation_drops_batch_and_deletion_applies_across_purposes(self) -> None:
        envelope = self.envelope("batch")
        self.runtime.enqueue_batch(envelope, self.context)
        self.runtime.invalidate_evidence(
            self.context,
            revoked_ids={"obs_sparse_photo"},
        )
        self.assertEqual(self.runtime.drain_batch(self.context), ())
        with self.assertRaises(ProcessingError) as revoked:
            self.runtime.enqueue_batch(envelope, self.context)
        self.assertEqual(revoked.exception.code, ErrorCode.REVOKED_INPUT)

        other_purpose = replace(self.context, purpose="search_history")
        self.runtime.enqueue_batch(envelope, other_purpose)
        self.runtime.invalidate_evidence(
            self.context,
            deleted_ids={"obs_sparse_photo"},
        )
        self.assertEqual(self.runtime.drain_batch(other_purpose), ())

    def test_budget_is_scope_bound_and_cache_hit_does_not_spend_again(self) -> None:
        context = replace(self.context, budget_remaining=1)
        responses = {
            "budget_a": deepcopy(self.fixture["fake_provider_candidate"]),
            "budget_b": deepcopy(self.fixture["fake_provider_candidate"]),
        }
        provider = FakeSyntheticProvider(responses)
        self.reference.create_candidate(
            [self.observation()],
            context=context,
            provider=provider,
            synthetic_fixture_id="budget_a",
        )
        self.reference.create_candidate(
            [self.observation()],
            context=context,
            provider=provider,
            synthetic_fixture_id="budget_a",
        )
        exhausted = self.reference.create_candidate(
            [self.observation()],
            context=context,
            provider=provider,
            synthetic_fixture_id="budget_b",
        )
        self.assertEqual(provider.calls, 1)
        self.assertEqual(exhausted.fallback_reason, ErrorCode.BUDGET_EXHAUSTED.value)

        other_purpose = replace(context, purpose="search_history")
        self.reference.create_candidate(
            [self.observation()],
            context=other_purpose,
            provider=provider,
            synthetic_fixture_id="budget_b",
        )
        self.assertEqual(provider.calls, 2)

    def test_observability_rejects_content_fields_and_unsafe_category_values(self) -> None:
        for field in (
            "body",
            "Body",
            "raw",
            "raw_text",
            "prompt",
            "prompt_text",
            "url",
            "request_url",
            "token",
            "api_token",
            "secret",
            "client_secret",
            "exception",
            "error_message",
        ):
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.observer.record(**{field: "synthetic-canary"})
        with self.assertRaises(ValueError):
            self.observer.record(adapter_name="https://example.invalid/?token=secret")
        with self.assertRaises(ValueError):
            self.observer.record(result_code={"exception": "synthetic-canary"})
        with self.assertRaises(ValueError):
            self.observer.record(result_code="ValueError: synthetic canary body")
        with self.assertRaises(ValueError):
            self.observer.record(adapter_name="sk-syntheticcanary123")

        provider = FakeSyntheticProvider(
            {"safe": deepcopy(self.fixture["fake_provider_candidate"])}
        )
        self.reference.create_candidate(
            [self.observation()],
            context=self.context,
            provider=provider,
            synthetic_fixture_id="safe",
        )
        unsafe_provider = FakeSyntheticProvider(
            {"unsafe": deepcopy(self.fixture["fake_provider_candidate"])}
        )
        unsafe_provider.adapter_name = "https://example.invalid/?token=secret"
        denied = self.reference.create_candidate(
            [self.observation()],
            context=self.context,
            provider=unsafe_provider,
            synthetic_fixture_id="unsafe",
        )
        self.assertEqual(denied.fallback_reason, ErrorCode.PROVIDER_NOT_ALLOWED.value)
        self.assertEqual(unsafe_provider.calls, 0)
        serialized = json.dumps(self.observer.records, sort_keys=True)
        for canary in (
            "fixed synthetic content",
            "example.invalid",
            "token=secret",
            "在合成河边散步",
        ):
            self.assertNotIn(canary, serialized)


if __name__ == "__main__":
    unittest.main()
