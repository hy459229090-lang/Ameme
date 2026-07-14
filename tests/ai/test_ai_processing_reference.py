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
    SYNTHETIC_TEST_SCOPE_HMAC_KEY,
    SafeObserver,
    ScopedAIRuntime,
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
            allowed_provider_adapters=frozenset({"fake-synthetic-provider"}),
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

    def test_provider_draft_preserves_r0_superseded_lineage(self) -> None:
        observations = [self.observation("incorrect"), self.observation("correction")]
        r0_draft = build_event_draft(observations, contract=MachineContract())
        provider_candidate = deepcopy(dict(r0_draft.candidate))
        provider = FakeSyntheticProvider({"correction": provider_candidate})

        draft = self.reference.create_candidate(
            observations,
            context=self.context,
            provider=provider,
            synthetic_fixture_id="correction",
        )

        self.assertIsNone(draft.fallback_reason)
        self.assertEqual(draft.description, "实际去了合成图书馆")
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
        self.assertTrue(self.reference.observer.records)
        self.assertTrue(
            all(
                set(record) <= self.reference.observer._ALLOWED
                for record in self.reference.observer.records
            )
        )

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
            allowed_provider_adapters=frozenset({"fake-synthetic-provider"}),
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

    def test_salience_only_assertion_does_not_upgrade_fact_status_or_title(self) -> None:
        factual = self.observation("salience")
        factual["value"] = {"action": "保留低置信事实标题"}
        salience_only = {
            "schema_version": 1,
            "observation_id": "obs_salience_assertion_only",
            "source_object_id": "src_salience_assertion_only",
            "space_id": "space_personal",
            "kind": "user_salience",
            "value": {
                "title": "不得覆盖事实标题",
                "emotion": "在意",
                "relationship": "合成关系",
                "importance": 1.0,
                "emotional_salience": 1.0,
                "relationship_salience": 1.0
            },
            "fact_status": "user_asserted",
            "confidence": 1.0,
            "parser_version": "synthetic-user-v1",
            "time_range": {
                "start": "2026-07-14T22:02:00+08:00",
                "timezone": "Asia/Shanghai",
                "precision": "minute"
            },
            "created_at": "2026-07-14T22:02:00+08:00"
        }
        draft = build_event_draft([factual, salience_only], contract=MachineContract())
        action_evidence = next(
            item for item in draft.candidate["field_evidence"] if item["field"] == "action"
        )
        time_evidence = next(
            item for item in draft.candidate["field_evidence"] if item["field"] == "time"
        )
        self.assertEqual(action_evidence["status"], "inferred")
        self.assertEqual(action_evidence["confidence"], 0.4)
        self.assertEqual(time_evidence["observation_ids"], ["obs_salience_only"])
        self.assertEqual(time_evidence["status"], "inferred")
        self.assertEqual(draft.fact_status, "low_confidence_candidate")
        self.assertEqual(draft.fact_confidence, 0.4)
        self.assertEqual(draft.title, "保留低置信事实标题")

    def test_salience_only_observation_cannot_form_event_candidate(self) -> None:
        salience_only = self.observation("salience")
        salience_only["value"] = {
            "emotion": "合成情绪",
            "relationship": "合成关系",
            "importance": 1.0,
            "emotional_salience": 1.0,
            "relationship_salience": 1.0
        }
        salience_only["fact_status"] = "user_asserted"
        salience_only["confidence"] = 1.0
        with self.assertRaises(ProcessingError) as raised:
            build_event_draft([salience_only], contract=MachineContract())
        self.assertEqual(raised.exception.code, ErrorCode.DATA_INSUFFICIENT)

    def test_provider_cannot_use_salience_only_timestamp_as_time_evidence(self) -> None:
        factual = self.observation("salience")
        factual["value"] = {"action": "保留事实时间"}
        salience_only = self.observation("salience")
        salience_only["observation_id"] = "obs_provider_salience_only"
        salience_only["source_object_id"] = "src_provider_salience_only"
        salience_only["value"] = {
            "emotion": "合成情绪",
            "importance": 1.0,
            "emotional_salience": 1.0
        }
        salience_only["time_range"]["start"] = "2026-07-14T22:05:00+08:00"
        salience_only["fact_status"] = "user_asserted"
        salience_only["confidence"] = 1.0

        r0_draft = build_event_draft(
            [factual, salience_only], contract=MachineContract()
        )
        provider_candidate = deepcopy(dict(r0_draft.candidate))
        provider_candidate["time_range"] = deepcopy(salience_only["time_range"])
        time_evidence = next(
            item
            for item in provider_candidate["field_evidence"]
            if item["field"] == "time"
        )
        time_evidence.update(
            {
                "observation_ids": [salience_only["observation_id"]],
                "confidence": 1.0,
                "status": "user_asserted"
            }
        )
        provider = FakeSyntheticProvider({"salience_time": provider_candidate})

        draft = self.reference.create_candidate(
            [factual, salience_only],
            context=self.context,
            provider=provider,
            synthetic_fixture_id="salience_time",
        )

        self.assertEqual(draft.fallback_reason, ErrorCode.MISSING_EVIDENCE.value)
        factual_time = next(
            item for item in draft.candidate["field_evidence"] if item["field"] == "time"
        )
        self.assertEqual(factual_time["observation_ids"], ["obs_salience_only"])

    def test_budget_exhaustion_preserves_r0_candidate_and_no_summary(self) -> None:
        context = PolicyContext(
            space_id="space_personal",
            purpose="form_today",
            sensitivity="personal",
            processing_locations=frozenset({"device", "model_provider"}),
            allowed_provider_adapters=frozenset({"fake-synthetic-provider"}),
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
        self.assertEqual(draft.title, "在合成河边散步")
        self.assertIsNone(draft.description)
        self.assertEqual(draft.fact_status, "low_confidence_candidate")
        self.assertEqual(set(draft.semantic_fields), {"time", "action"})
        self.assertEqual(provider.calls, 1)

        non_synthetic = self.reference.create_candidate(
            [self.observation("sparse")], context=self.context, provider=provider
        )
        self.assertEqual(non_synthetic.fallback_reason, ErrorCode.PROVIDER_ERROR.value)
        self.assertEqual(provider.calls, 1)

    def test_provider_adapter_requires_explicit_allow_list_and_restricted_wins(self) -> None:
        provider = FakeSyntheticProvider(
            {"unknown": deepcopy(self.fixture["fake_provider_candidate"])}
        )
        provider.adapter_name = "unknown-synthetic-provider"

        denied = self.reference.create_candidate(
            [self.observation("sparse")],
            context=self.context,
            provider=provider,
            synthetic_fixture_id="unknown",
        )
        self.assertEqual(denied.fallback_reason, ErrorCode.PROVIDER_NOT_ALLOWED.value)
        self.assertEqual(provider.calls, 0)

        restricted = PolicyContext(
            space_id="space_personal",
            purpose="form_today",
            sensitivity="restricted",
            processing_locations=frozenset({"device", "model_provider"}),
            allowed_provider_adapters=frozenset(),
            budget_remaining=2,
        )
        blocked = self.reference.create_candidate(
            [self.observation("sparse")],
            context=restricted,
            provider=provider,
            synthetic_fixture_id="unknown",
        )
        self.assertEqual(blocked.fallback_reason, ErrorCode.SENSITIVE_MODEL_BLOCKED.value)
        self.assertEqual(provider.calls, 0)

    def test_provider_cannot_launder_time_status_across_different_ranges(self) -> None:
        inferred = self.observation("sparse")
        inferred["observation_id"] = "obs_time_inferred_10"
        inferred["source_object_id"] = "src_time_inferred_10"
        inferred["value"] = {"action": "同一合成动作", "event_type": "activity"}
        inferred["time_range"] = {
            "start": "2026-07-14T10:00:00+08:00",
            "timezone": "Asia/Shanghai",
            "precision": "minute"
        }
        inferred["fact_status"] = "inferred"
        inferred["confidence"] = 0.2

        asserted = deepcopy(inferred)
        asserted["observation_id"] = "obs_time_asserted_11"
        asserted["source_object_id"] = "src_time_asserted_11"
        asserted["time_range"]["start"] = "2026-07-14T11:00:00+08:00"
        asserted["fact_status"] = "user_asserted"
        asserted["confidence"] = 1.0
        asserted["created_at"] = "2026-07-14T11:01:00+08:00"

        provider_candidate = {
            "schema_version": 1,
            "candidate_id": "cand_time_status_launder",
            "space_id": "space_personal",
            "event_type": "activity",
            "time_range": deepcopy(inferred["time_range"]),
            "field_evidence": [
                {
                    "field": "time",
                    "observation_ids": [
                        inferred["observation_id"],
                        asserted["observation_id"]
                    ],
                    "confidence": 1.0,
                    "status": "user_asserted"
                },
                {
                    "field": "action",
                    "observation_ids": [
                        inferred["observation_id"],
                        asserted["observation_id"]
                    ],
                    "confidence": 1.0,
                    "status": "user_asserted"
                }
            ],
            "status": "candidate",
            "created_at": asserted["created_at"]
        }
        provider = FakeSyntheticProvider({"time_launder": provider_candidate})

        draft = self.reference.create_candidate(
            [inferred, asserted],
            context=self.context,
            provider=provider,
            synthetic_fixture_id="time_launder",
        )

        self.assertEqual(draft.fallback_reason, ErrorCode.SCHEMA_MISMATCH.value)
        self.assertEqual(draft.candidate["time_range"], asserted["time_range"])
        self.assertEqual(provider.calls, 1)

    def test_provider_non_conflict_evidence_cannot_mix_action_or_description_values(self) -> None:
        for field in ("action", "description"):
            with self.subTest(field=field):
                inferred = self.observation("sparse")
                inferred["observation_id"] = f"obs_{field}_inferred"
                inferred["source_object_id"] = f"src_{field}_inferred"
                inferred["value"] = {field: "合成值甲", "event_type": "activity"}
                inferred["fact_status"] = "inferred"
                inferred["confidence"] = 0.2

                asserted = deepcopy(inferred)
                asserted["observation_id"] = f"obs_{field}_asserted"
                asserted["source_object_id"] = f"src_{field}_asserted"
                asserted["value"][field] = "合成值乙"
                asserted["fact_status"] = "user_asserted"
                asserted["confidence"] = 1.0

                provider_candidate = {
                    "schema_version": 1,
                    "candidate_id": f"cand_{field}_status_launder",
                    "space_id": "space_personal",
                    "event_type": "activity",
                    "field_evidence": [
                        {
                            "field": field,
                            "observation_ids": [
                                inferred["observation_id"],
                                asserted["observation_id"]
                            ],
                            "confidence": 1.0,
                            "status": "user_asserted"
                        }
                    ],
                    "status": "candidate",
                    "created_at": asserted["created_at"]
                }
                provider = FakeSyntheticProvider(
                    {f"{field}_launder": provider_candidate}
                )

                draft = self.reference.create_candidate(
                    [inferred, asserted],
                    context=self.context,
                    provider=provider,
                    synthetic_fixture_id=f"{field}_launder",
                )

                self.assertEqual(
                    draft.fallback_reason, ErrorCode.SCHEMA_MISMATCH.value
                )
                self.assertEqual(provider.calls, 1)

    def test_provider_cannot_invent_conflict_for_same_field_value(self) -> None:
        first = self.observation("sparse")
        first["observation_id"] = "obs_same_action_first"
        first["source_object_id"] = "src_same_action_first"
        first["value"] = {"action": "完全相同的合成动作", "event_type": "activity"}
        second = deepcopy(first)
        second["observation_id"] = "obs_same_action_second"
        second["source_object_id"] = "src_same_action_second"

        provider_candidate = {
            "schema_version": 1,
            "candidate_id": "cand_false_action_conflict",
            "space_id": "space_personal",
            "event_type": "activity",
            "field_evidence": [
                {
                    "field": "action",
                    "observation_ids": [
                        first["observation_id"],
                        second["observation_id"]
                    ],
                    "confidence": 0.82,
                    "status": "conflict"
                }
            ],
            "status": "conflict",
            "created_at": second["created_at"]
        }
        provider = FakeSyntheticProvider({"false_conflict": provider_candidate})

        draft = self.reference.create_candidate(
            [first, second],
            context=self.context,
            provider=provider,
            synthetic_fixture_id="false_conflict",
        )

        self.assertEqual(draft.fallback_reason, ErrorCode.SCHEMA_MISMATCH.value)
        self.assertNotEqual(draft.candidate["status"], "conflict")
        self.assertEqual(provider.calls, 1)

    def test_provider_cannot_invent_time_range_or_add_time_without_evidence(self) -> None:
        invented = deepcopy(self.fixture["fake_provider_candidate"])
        invented["time_range"] = {
            "start": "2099-01-01T00:00:00+08:00",
            "end": "2099-01-01T00:30:00+08:00",
            "timezone": "Asia/Shanghai",
            "precision": "range"
        }
        provider = FakeSyntheticProvider({"invented_time": invented})
        draft = self.reference.create_candidate(
            [self.observation("sparse")],
            context=self.context,
            provider=provider,
            synthetic_fixture_id="invented_time",
        )
        self.assertEqual(draft.fallback_reason, ErrorCode.SCHEMA_MISMATCH.value)
        self.assertNotEqual(draft.candidate["time_range"], invented["time_range"])

        no_time_evidence = deepcopy(invented)
        no_time_evidence["field_evidence"] = [
            item for item in no_time_evidence["field_evidence"] if item["field"] != "time"
        ]
        provider = FakeSyntheticProvider({"unreferenced_time": no_time_evidence})
        draft = self.reference.create_candidate(
            [self.observation("sparse")],
            context=self.context,
            provider=provider,
            synthetic_fixture_id="unreferenced_time",
        )
        self.assertEqual(draft.fallback_reason, ErrorCode.SCHEMA_MISMATCH.value)

    def test_provider_cannot_invent_event_type(self) -> None:
        invented = deepcopy(self.fixture["fake_provider_candidate"])
        invented["event_type"] = "milestone"
        provider = FakeSyntheticProvider({"invented_type": invented})
        draft = self.reference.create_candidate(
            [self.observation("sparse")],
            context=self.context,
            provider=provider,
            synthetic_fixture_id="invented_type",
        )
        self.assertEqual(draft.fallback_reason, ErrorCode.SCHEMA_MISMATCH.value)
        self.assertEqual(draft.candidate["event_type"], "experience")

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
        first_observer = SafeObserver()
        first_reference = AIProcessingReference(
            observer=first_observer,
            runtime=ScopedAIRuntime(
                first_observer,
                scope_hmac_key=SYNTHETIC_TEST_SCOPE_HMAC_KEY,
            ),
        )
        second_observer = SafeObserver()
        second_reference = AIProcessingReference(
            observer=second_observer,
            runtime=ScopedAIRuntime(
                second_observer,
                scope_hmac_key=SYNTHETIC_TEST_SCOPE_HMAC_KEY,
            ),
        )
        first = first_reference.create_candidate(
            [self.observation("sparse")], context=self.context
        )
        second = second_reference.create_candidate(
            [self.observation("sparse")], context=self.context
        )
        self.assertEqual(dict(first.candidate), dict(second.candidate))
        self.assertEqual(first.fact_confidence, second.fact_confidence)
        self.assertEqual(first_observer.records, second_observer.records)


if __name__ == "__main__":
    unittest.main()
