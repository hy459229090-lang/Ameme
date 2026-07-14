from __future__ import annotations

import importlib.util
import json
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "dev" / "agent" / "build_codex_demo_seed.py"
SPEC = importlib.util.spec_from_file_location("build_codex_demo_seed", SCRIPT)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class CodexDemoSeedTest(unittest.TestCase):
    def test_skill_capture_builds_bounded_android_seed(self) -> None:
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source.json"
            output = root / "android-seed.json"
            source.write_text(
                json.dumps(
                    {
                        "thread_id": "thread_synthetic",
                        "events": [
                            {
                                "source_turn_id": "turn_observed",
                                "content": "Synthetic tests passed with direct tool evidence.",
                                "event_time": "2026-07-14T10:00:00+08:00",
                                "event_type": "result",
                                "evidence_kind": "direct_evidence",
                                "sensitivity": "personal",
                                "data_class": "structured",
                            },
                            {
                                "source_turn_id": "turn_user",
                                "content": "The synthetic user requested a local demo.",
                                "event_time": "2026-07-14T10:05:00+08:00",
                                "event_type": "decision",
                                "evidence_kind": "user_statement",
                                "sensitivity": "personal",
                                "data_class": "structured",
                            },
                        ],
                    }
                ),
                encoding="utf-8",
            )

            summary = MODULE.build_seed(source, output, root / "state")
            document = json.loads(output.read_text(encoding="utf-8"))

            self.assertTrue(summary["ok"])
            self.assertEqual(2, summary["event_count"])
            self.assertEqual(1, summary["evidence_counts"]["observed"])
            self.assertEqual(1, summary["evidence_counts"]["user_asserted"])
            self.assertEqual(1, document["schema_version"])
            self.assertEqual("thread_synthetic", document["thread_id"])
            self.assertEqual(2, len(document["events"]))
            self.assertRegex(document["events"][0]["idempotency_slot"], r"^idem_[0-9a-f]{64}$")
            self.assertNotIn("grant_id", output.read_text(encoding="utf-8"))
            self.assertNotIn("idempotency_key", output.read_text(encoding="utf-8"))

    def test_restricted_or_raw_demo_input_is_rejected(self) -> None:
        with TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source.json"
            source.write_text(
                json.dumps(
                    {
                        "thread_id": "thread_synthetic",
                        "events": [
                            {
                                "source_turn_id": "turn_restricted",
                                "content": "Synthetic restricted content.",
                                "event_time": "2026-07-14T10:00:00+08:00",
                                "event_type": "result",
                                "evidence_kind": "direct_evidence",
                                "sensitivity": "restricted",
                                "data_class": "raw",
                            }
                        ],
                    }
                ),
                encoding="utf-8",
            )

            with self.assertRaisesRegex(ValueError, "public or personal"):
                MODULE.build_seed(source, root / "output.json", root / "state")


if __name__ == "__main__":
    unittest.main()
