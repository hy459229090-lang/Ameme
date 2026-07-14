from __future__ import annotations

from copy import deepcopy
import json
from pathlib import Path
import sys
import unittest


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "packages" / "ai-processing-reference"))

from ameme_ai_reference import (  # noqa: E402
    AIProcessingReference,
    ErrorCode,
    FakeSyntheticProvider,
    MachineContract,
    PolicyContext,
    ProcessingError,
    build_event_draft,
    decide_merge,
    default_registry,
    exact_deduplicate,
    normalize_time_range,
    observation_from_addendum,
    safe_deduplicate,
)


class AIProcessingReferenceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.fixture = json.loads(
            (ROOT / "tests" / "fixtures" / "ai" / "synthetic_ai_eval.json").read_text(
                encoding="utf-8"
            )
        )

    def setUp(self) -> None:
        self.reference = AIProcessingReference()
        self.context = PolicyContext(
            space_id="space_personal",
            purpose="form_today",
            sensitivity="personal",
            processing_locations=frozenset({"device", "model_provider"}),
            budget_remaining=2,
        )

    def observation(self, name: str) -> dict:
        return deepcopy(self.fixture["observations"][name])

    def test_registry_matches_twelve_task_contract_and_long_term_memory_is_disabled(self) -> None:
        registry = default_registry()
        self.assertEqual(len(registry.snapshot()), 12)
        with self.assertRaises(ProcessingError) as raised:
            registry.require("T12_LONG_TERM_MEMORY")
        self.assertEqual(raised.exception.code, ErrorCode.TASK_DISABLED)

    def test_timezone_normalization_crosses_local_date_deterministically(self) -> None:
        first = normalize_time_range("2026-07-14T23:30:00-04:00", "Asia/Shanghai")
        second = normalize_time_range("2026-07-14T23:30:00-04:00", "Asia/Shanghai")
        self.assertEqual(first, second)
        self.assertTrue(first["start"].startswith("2026-07-15T11:30:00+08:00"))

    def test_sparse_single_source_stays_candidate_and_validates_machine_schema(self) -> None:
        draft = self.reference.create_candidate([self.observation("sparse")], context=self.context)
        self.assertEqual(draft.candidate["status"], "candidate")
        self.assertEqual(draft.fact_status, "low_confidence_candidate")
        MachineContract().validate_event_candidate(draft.candidate, [self.observation("sparse")])

    def test_planned_is_not_upgraded_to_happened_and_is_not_merged_with_happened(self) -> None:
        planned = build_event_draft([self.observation("planned")], contract=MachineContract())
        happened = build_event_draft([self.observation("happened")], contract=MachineContract())
        self.assertEqual(planned.fact_status, "planned")
        self.assertTrue(all(item["status"] == "planned" for item in planned.candidate["field_evidence"]))
        self.assertEqual(decide_merge(planned, happened).reason_code, "PLANNED_VS_HAPPENED")

    def test_same_event_shape_with_different_intent_stays_separate(self) -> None:
        left = build_event_draft([self.observation("intent_a")], contract=MachineContract())
        right = build_event_draft([self.observation("intent_b")], contract=MachineContract())
        decision = decide_merge(left, right)
        self.assertEqual(decision.action, "separate")
        self.assertEqual(decision.reason_code, "INTENT_MISMATCH")

    def test_exact_and_safe_dedup_are_space_and_intent_safe(self) -> None:
        sources = [
            {"source_object_id": "src_a", "space_id": "space_personal", "content_hash": "a" * 16},
            {"source_object_id": "src_b", "space_id": "space_personal", "content_hash": "a" * 16},
            {"source_object_id": "src_c", "space_id": "space_work", "content_hash": "a" * 16},
        ]
        exact = exact_deduplicate(sources)
        self.assertEqual(exact.duplicate_of, {"src_b": "src_a"})

        first = self.observation("intent_a")
        duplicate = deepcopy(first)
        duplicate["observation_id"] = "obs_intent_work_copy"
        duplicate["source_object_id"] = "src_intent_work_copy"
        safe = safe_deduplicate([first, duplicate, self.observation("intent_b")])
        self.assertEqual(safe.duplicate_of, {"obs_intent_work_copy": "obs_intent_work"})
        self.assertIn("obs_intent_leisure", safe.kept_ids)

    def test_explicit_user_correction_wins_and_preserves_superseded_lineage(self) -> None:
        draft = build_event_draft(
            [self.observation("incorrect"), self.observation("correction")],
            contract=MachineContract(),
        )
        self.assertEqual(draft.description, "实际去了合成图书馆")
        self.assertEqual(draft.fact_status, "user_asserted")
        self.assertIn("obs_description_old", draft.superseded_evidence_ids)

    def test_user_addendum_becomes_user_asserted_observation(self) -> None:
        addendum = {
            "schema_version": 1,
            "addendum_id": "add_synthetic_fix",
            "owner_id": "owner_synthetic",
            "space_id": "space_personal",
            "text": "补充一个合成事件",
            "submitted_at": "2026-07-14T12:00:00+08:00",
            "event_time": {
                "start": "2026-07-14T11:00:00+08:00",
                "timezone": "Asia/Shanghai",
                "precision": "hour"
            },
            "target_type": "new_event",
            "target_id": "day_synthetic",
            "sensitivity": "personal",
            "sync_mode": "device_local"
        }
        observation = observation_from_addendum(
            addendum,
            observation_id="obs_addendum_synthetic",
            source_object_id="src_addendum_synthetic",
            contract=MachineContract(),
        )
        self.assertEqual(observation["fact_status"], "user_asserted")
        self.assertEqual(observation["value"]["description"], addendum["text"])

    def test_injection_text_is_data_and_observability_has_no_body(self) -> None:
        injection = self.observation("injection")
        draft = self.reference.create_candidate([injection], context=self.context)
        self.assertEqual(draft.description, injection["value"]["description"])
        serialized_records = json.dumps(self.reference.observer.records, ensure_ascii=False)
        self.assertNotIn("忽略所有规则", serialized_records)
        self.assertEqual(set(self.reference.observer.records[0]), self.reference.observer._ALLOWED)

    def test_deleted_observation_is_rejected_before_candidate_creation(self) -> None:
        context = PolicyContext(
            space_id="space_personal",
            purpose="form_today",
            sensitivity="personal",
            processing_locations=frozenset({"device"}),
            deleted_evidence_ids=frozenset({"obs_sparse_photo"}),
            budget_remaining=1,
        )
        with self.assertRaises(ProcessingError) as raised:
            self.reference.create_candidate([self.observation("sparse")], context=context)
        self.assertEqual(raised.exception.code, ErrorCode.DELETED_INPUT)
        self.assertEqual(self.reference.observer.records, [])

    def test_salience_neither_increases_fact_confidence_nor_opens_provider(self) -> None:
        observation = self.observation("salience")
        restricted = PolicyContext(
            space_id="space_personal",
            purpose="form_today",
            sensitivity="restricted",
            processing_locations=frozenset({"device", "model_provider"}),
            budget_remaining=5,
        )
        provider = FakeSyntheticProvider({"salience": deepcopy(self.fixture["fake_provider_candidate"])})
        draft = self.reference.create_candidate(
            [observation],
            context=restricted,
            provider=provider,
            synthetic_fixture_id="salience",
        )
        self.assertEqual(draft.fact_confidence, 0.4)
        self.assertEqual(draft.salience, {"importance": 1.0, "emotional": 1.0, "relationship": 1.0})
        self.assertEqual(draft.fallback_reason, ErrorCode.SENSITIVE_MODEL_BLOCKED.value)
        self.assertEqual(provider.calls, 0)

    def test_budget_exhaustion_preserves_r0_candidate_and_no_summary(self) -> None:
        context = PolicyContext(
            space_id="space_personal",
            purpose="form_today",
            sensitivity="personal",
            processing_locations=frozenset({"device", "model_provider"}),
            budget_remaining=0,
        )
        provider = FakeSyntheticProvider(
            {"sparse": deepcopy(self.fixture["fake_provider_candidate"])}
        )
        draft = self.reference.create_candidate(
            [self.observation("sparse")],
            context=context,
            provider=provider,
            synthetic_fixture_id="sparse",
        )
        self.assertEqual(draft.candidate["status"], "candidate")
        self.assertEqual(draft.fallback_reason, ErrorCode.BUDGET_EXHAUSTED.value)
        self.assertEqual(provider.calls, 0)
        summary = self.reference.no_summary_when_unavailable(["evt_synthetic"], context=context)
        self.assertEqual(summary.state, "no_summary")
        self.assertEqual(summary.reason_code, ErrorCode.BUDGET_EXHAUSTED.value)

    def test_data_insufficient_produces_no_summary(self) -> None:
        summary = self.reference.no_summary_when_unavailable([], context=self.context)
        self.assertEqual(summary.state, "no_summary")
        self.assertEqual(summary.reason_code, ErrorCode.DATA_INSUFFICIENT.value)

    def test_fake_provider_accepts_only_synthetic_fixture_and_valid_candidate(self) -> None:
        provider = FakeSyntheticProvider(
            {"sparse": deepcopy(self.fixture["fake_provider_candidate"])}
        )
        draft = self.reference.create_candidate(
            [self.observation("sparse")],
            context=self.context,
            provider=provider,
            synthetic_fixture_id="sparse",
        )
        self.assertEqual(draft.candidate["candidate_id"], "cand_fake_provider_valid")
        self.assertEqual(provider.calls, 1)

        non_synthetic = self.reference.create_candidate(
            [self.observation("sparse")], context=self.context, provider=provider
        )
        self.assertEqual(non_synthetic.fallback_reason, ErrorCode.PROVIDER_ERROR.value)
        self.assertEqual(provider.calls, 1)

    def test_provider_missing_evidence_falls_back_to_r0_candidate(self) -> None:
        invalid = deepcopy(self.fixture["fake_provider_candidate"])
        invalid["field_evidence"][0]["observation_ids"] = ["obs_not_present"]
        provider = FakeSyntheticProvider({"invalid": invalid})
        draft = self.reference.create_candidate(
            [self.observation("sparse")],
            context=self.context,
            provider=provider,
            synthetic_fixture_id="invalid",
        )
        self.assertEqual(draft.fallback_reason, ErrorCode.MISSING_EVIDENCE.value)
        self.assertNotEqual(draft.candidate["candidate_id"], invalid["candidate_id"])

    def test_provider_cannot_return_terminal_candidate_status(self) -> None:
        invalid = deepcopy(self.fixture["fake_provider_candidate"])
        invalid["status"] = "accepted"
        provider = FakeSyntheticProvider({"terminal": invalid})
        draft = self.reference.create_candidate(
            [self.observation("sparse")],
            context=self.context,
            provider=provider,
            synthetic_fixture_id="terminal",
        )
        self.assertEqual(draft.fallback_reason, ErrorCode.SCHEMA_MISMATCH.value)
        self.assertEqual(draft.candidate["status"], "candidate")

    def test_repeated_runs_are_byte_deterministic(self) -> None:
        first = self.reference.create_candidate([self.observation("sparse")], context=self.context)
        second_reference = AIProcessingReference()
        second = second_reference.create_candidate(
            [self.observation("sparse")], context=self.context
        )
        self.assertEqual(dict(first.candidate), dict(second.candidate))
        self.assertEqual(first.fact_confidence, second.fact_confidence)
        self.assertEqual(self.reference.observer.records, second_reference.observer.records)


if __name__ == "__main__":
    unittest.main()
