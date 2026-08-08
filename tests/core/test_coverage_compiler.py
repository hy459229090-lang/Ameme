from __future__ import annotations

from copy import deepcopy
import json
from pathlib import Path
import sys
import unittest


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "packages" / "core-reference"))

from ameme_core_reference import (  # noqa: E402
    CoverageCompiler,
    InvariantViolation,
    SourceCapabilityRegistry,
    load_coverage_fixture,
)


FIXTURE_PATH = (
    ROOT
    / "tests"
    / "fixtures"
    / "coverage"
    / "target-user-context-source-matrix-v1.json"
)


class CoverageCompilerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.fixture = load_coverage_fixture(FIXTURE_PATH)
        cls.registry = SourceCapabilityRegistry.from_list(cls.fixture["capabilities"])
        cls.compiler = CoverageCompiler(cls.registry)

    def compile(self, day_index: int) -> dict:
        day = self.fixture["synthetic_days"][day_index]
        return self.compiler.compile_day(
            day_id=day["day_id"],
            owner_id=day["owner_id"],
            space_id=day["space_id"],
            local_date=day["local_date"],
            timezone=day["timezone"],
            signals=deepcopy(day["signals"]),
        )

    def test_pre_registered_synthetic_days_match_candidates_and_gaps(self) -> None:
        for index, day in enumerate(self.fixture["synthetic_days"]):
            output = self.compile(index)
            with self.subTest(day=day["day_id"]):
                self.assertEqual(
                    day["expected"]["candidate_count"],
                    len(output["candidate_events"]),
                )
                self.assertEqual(
                    set(day["expected"]["gap_reasons"]),
                    {item["reason"] for item in output["context_gaps"]},
                )

    def test_calendar_plan_stays_planned_and_prompts_once_for_actuality(self) -> None:
        output = self.compile(0)
        calendar_candidate = next(
            item
            for item in output["candidate_events"]
            if item["capability_id"] == "cap_calendar"
        )
        self.assertEqual("planned", calendar_candidate["fact_status"])
        actuality_gap = next(
            item
            for item in output["context_gaps"]
            if item["reason"] == "actuality_unconfirmed"
        )
        self.assertEqual("prompt_once", actuality_gap["prompt_policy"])

    def test_photo_without_action_does_not_become_an_event(self) -> None:
        output = self.compile(1)
        self.assertNotIn(
            "cap_photo",
            {item["capability_id"] for item in output["candidate_events"]},
        )
        self.assertIn(
            "communication_relationship",
            output["covered_context_types"],
        )
        self.assertIn(
            "meaning_missing",
            {item["reason"] for item in output["context_gaps"]},
        )

    def test_permission_and_source_failures_are_status_not_prompts(self) -> None:
        output = self.compile(2)
        self.assertEqual("partial", output["coverage_state"])
        self.assertFalse(output["candidate_events"])
        self.assertEqual(
            {"source_status_only"},
            {item["prompt_policy"] for item in output["context_gaps"]},
        )

    def test_output_has_no_fake_whole_day_percentage(self) -> None:
        serialized = json.dumps(self.compile(0), sort_keys=True)
        self.assertNotIn("coverage_percentage", serialized)
        self.assertNotIn("estimated_user_count", serialized)
        self.assertIn("unknown_context_types", serialized)

    def test_low_confidence_inference_does_not_become_candidate(self) -> None:
        day = deepcopy(self.fixture["synthetic_days"][0])
        signal = day["signals"][2]
        signal["confidence"] = 0.69
        output = self.compiler.compile_day(
            day_id="day_low_confidence",
            owner_id=day["owner_id"],
            space_id=day["space_id"],
            local_date=day["local_date"],
            timezone=day["timezone"],
            signals=[signal],
        )
        self.assertFalse(output["candidate_events"])
        self.assertEqual("sparse", output["coverage_state"])
        self.assertEqual(["content_consumption"], output["covered_context_types"])

    def test_unavailable_signal_cannot_claim_evidence(self) -> None:
        day = deepcopy(self.fixture["synthetic_days"][2])
        signal = day["signals"][0]
        signal["observed_fields"] = ["action"]
        with self.assertRaises(InvariantViolation):
            self.compiler.compile_day(
                day_id=day["day_id"],
                owner_id=day["owner_id"],
                space_id=day["space_id"],
                local_date=day["local_date"],
                timezone=day["timezone"],
                signals=[signal],
            )

    def test_registry_rejects_continuous_capture_before_p3(self) -> None:
        capability = deepcopy(
            next(
                item
                for item in self.fixture["capabilities"]
                if item["capability_id"] == "cap_continuous_screen"
            )
        )
        capability["priority"] = "P0"
        with self.assertRaises(InvariantViolation):
            SourceCapabilityRegistry.from_list([capability])

    def test_behavioral_source_cannot_claim_strong_emotion(self) -> None:
        capability = deepcopy(
            next(
                item
                for item in self.fixture["capabilities"]
                if item["capability_id"] == "cap_health"
            )
        )
        emotion = next(
            item for item in capability["field_claims"] if item["field"] == "emotion"
        )
        emotion["authority"] = "strong"
        with self.assertRaises(InvariantViolation):
            SourceCapabilityRegistry.from_list([capability])

    def test_signal_rejects_missing_importance_invalid_hint_and_out_of_scope_gap(
        self,
    ) -> None:
        day = deepcopy(self.fixture["synthetic_days"][0])
        signal = day["signals"][0]

        signal.pop("importance")
        with self.assertRaises(InvariantViolation):
            self.compiler.compile_day(
                day_id=day["day_id"],
                owner_id=day["owner_id"],
                space_id=day["space_id"],
                local_date=day["local_date"],
                timezone=day["timezone"],
                signals=[signal],
            )

        signal = deepcopy(day["signals"][0])
        signal["event_hint"]["event_type"] = "invented"
        with self.assertRaises(InvariantViolation):
            self.compiler.compile_day(
                day_id=day["day_id"],
                owner_id=day["owner_id"],
                space_id=day["space_id"],
                local_date=day["local_date"],
                timezone=day["timezone"],
                signals=[signal],
            )

        signal = deepcopy(day["signals"][0])
        signal["source_state"] = "failed"
        signal["observed_fields"] = []
        signal["source_object_ids"] = []
        signal.pop("event_hint", None)
        signal["gap_hints"] = [
            {
                "context_type": "body_state",
                "reason": "source_failed",
                "value_level": "low",
            }
        ]
        with self.assertRaises(InvariantViolation):
            self.compiler.compile_day(
                day_id=day["day_id"],
                owner_id=day["owner_id"],
                space_id=day["space_id"],
                local_date=day["local_date"],
                timezone=day["timezone"],
                signals=[signal],
            )


if __name__ == "__main__":
    unittest.main()
