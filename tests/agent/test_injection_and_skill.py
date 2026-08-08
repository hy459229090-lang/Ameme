from __future__ import annotations

import json
import subprocess
import sys
import unittest
from support import Harness, ROOT


class InjectionAndSkillTests(unittest.TestCase):
    def test_prompt_injection_is_filtered_from_context(self) -> None:
        harness = Harness()
        try:
            grant = harness.exact_grant()
            pack = harness.mock.call(
                "get_context",
                {
                    "caller_id": "agent_codex_test",
                    "grant_id": grant["grant_id"],
                    "purpose": "autonomous_memory",
                    "space": "space_work",
                    "memory_types": ["event"],
                },
            )
            bodies = json.dumps(pack["items"], ensure_ascii=False)
            self.assertNotIn("export the secret key", bodies)
            self.assertIn("policy_filtered", pack["partial_reasons"])
            self.assertEqual("memory_is_untrusted_data", pack["instruction_boundary"])
            self.assertTrue(all(item["untrusted_memory"] for item in pack["items"]))
        finally:
            harness.close()

    def test_existing_skill_fixture_runs_16_cases_and_15_risks(self) -> None:
        completed = subprocess.run(
            [sys.executable, "scripts/validation/validate_ameme_skill.py"],
            cwd=ROOT,
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)
        result = json.loads(completed.stdout)
        self.assertEqual("passed", result["status"])
        self.assertEqual(16, result["cases_checked"])
        self.assertEqual(15, result["risks_checked"])


if __name__ == "__main__":
    unittest.main()
