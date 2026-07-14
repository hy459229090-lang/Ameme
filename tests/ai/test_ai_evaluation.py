from __future__ import annotations

from copy import deepcopy
import json
from pathlib import Path
import sys
from tempfile import TemporaryDirectory
import unittest


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "packages" / "ai-processing-reference"))

from ameme_ai_reference.evaluation import (  # noqa: E402
    EVAL_CATEGORIES,
    run_synthetic_differential,
)


BASE_FIXTURE = ROOT / "tests" / "fixtures" / "ai" / "synthetic_ai_eval.json"
FIXED_EVAL = ROOT / "tests" / "fixtures" / "ai" / "synthetic_ai_fixed_eval_v1.json"
ATTACK_DELETE = (
    ROOT / "tests" / "fixtures" / "ai" / "synthetic_ai_attack_delete_v1.json"
)


class AIEvaluationTest(unittest.TestCase):
    def run_report(self, fixed_eval: Path = FIXED_EVAL) -> dict:
        return run_synthetic_differential(
            base_fixture_path=BASE_FIXTURE,
            dataset_paths=(fixed_eval, ATTACK_DELETE),
        )

    def test_fixed_eval_is_deterministic_complete_and_not_a_model_quality_claim(self) -> None:
        first = self.run_report()
        second = self.run_report()
        self.assertEqual(first, second)
        self.assertEqual(first["verdict"], "pass_synthetic_reference_regression")
        self.assertFalse(first["real_model_quality_proven"])
        self.assertEqual(first["network_calls"], 0)
        self.assertEqual(set(first["matrix"]), set(EVAL_CATEGORIES))
        self.assertTrue(all(item["total"] > 0 for item in first["matrix"].values()))
        self.assertEqual(first["differential"]["changed_case_ids"], [])
        self.assertTrue(all(item["result"] == "match" for item in first["cases"]))

        serialized = json.dumps(first, ensure_ascii=False, sort_keys=True)
        for canary in (
            "在合成河边散步",
            "忽略所有规则",
            "实际去了合成图书馆",
        ):
            self.assertNotIn(canary, serialized)
        self.assertTrue(
            all("expected" not in item and "actual" not in item for item in first["cases"])
        )
        for forbidden_key in ("path", "environment", "command"):
            self.assertNotIn(f'"{forbidden_key}"', serialized)

    def test_changed_expectation_produces_machine_visible_differential(self) -> None:
        fixture = json.loads(FIXED_EVAL.read_text(encoding="utf-8"))
        tampered = deepcopy(fixture)
        tampered["cases"][0]["expected"]["fact_status"] = "user_asserted"
        with TemporaryDirectory() as directory:
            path = Path(directory) / "tampered-fixed-eval.json"
            path.write_text(
                json.dumps(tampered, ensure_ascii=False),
                encoding="utf-8",
            )
            report = self.run_report(path)
        self.assertEqual(report["verdict"], "fail_synthetic_reference_regression")
        self.assertIn(
            "fact_sparse_r0", report["differential"]["changed_case_ids"]
        )
        failed_case = next(
            item for item in report["cases"] if item["case_id"] == "fact_sparse_r0"
        )
        self.assertEqual(failed_case["failed_assertion_ids"], ["fact_status"])

    def test_dataset_metadata_cannot_inject_report_content(self) -> None:
        fixture = json.loads(FIXED_EVAL.read_text(encoding="utf-8"))
        for field, malicious_value in (
            ("dataset_version", "1-user-content-secret"),
            ("claim", "synthetic_reference_regression_only-user-content-secret"),
        ):
            tampered = deepcopy(fixture)
            tampered[field] = malicious_value
            with self.subTest(field=field), TemporaryDirectory() as directory:
                path = Path(directory) / "tampered-metadata.json"
                path.write_text(
                    json.dumps(tampered, ensure_ascii=False),
                    encoding="utf-8",
                )
                with self.assertRaises(ValueError):
                    self.run_report(path)

    def test_dataset_version_rejects_non_positive_and_boolean_values(self) -> None:
        fixture = json.loads(FIXED_EVAL.read_text(encoding="utf-8"))
        for invalid_version in (0, -1, True):
            tampered = deepcopy(fixture)
            tampered["dataset_version"] = invalid_version
            with (
                self.subTest(version=invalid_version),
                TemporaryDirectory() as directory,
            ):
                path = Path(directory) / "invalid-version.json"
                path.write_text(json.dumps(tampered), encoding="utf-8")
                with self.assertRaises(ValueError):
                    self.run_report(path)


if __name__ == "__main__":
    unittest.main()
