from __future__ import annotations

import json
from pathlib import Path
import sys
import unittest


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "packages" / "sync-protocol" / "src"))

from ameme_sync_protocol.conformance import materialize_document, validate_document  # noqa: E402


VECTOR_PATH = ROOT / "tests" / "fixtures" / "sync" / "canonical-conformance-vectors.json"


class ConformanceVectorTests(unittest.TestCase):
    def test_committed_vectors_match_deterministic_materialization(self) -> None:
        committed = json.loads(VECTOR_PATH.read_text(encoding="utf-8"))
        self.assertEqual(validate_document(committed), [])
        self.assertEqual(committed, materialize_document())

    def test_vectors_are_cross_language_and_cover_required_results(self) -> None:
        committed = json.loads(VECTOR_PATH.read_text(encoding="utf-8"))
        self.assertEqual(committed["vector_count"], 12)
        self.assertEqual(committed["canonicalization"]["name"], "ameme-canonical-json-v1")
        self.assertIn("iOS Swift Network.framework conformance tests", committed["required_future_consumers"])
        self.assertIn("Android Kotlin NSD conformance tests", committed["required_future_consumers"])
        outcomes = {vector["expected"]["result_class"] for vector in committed["vectors"]}
        self.assertEqual(outcomes, {"convergence", "partial", "conflict", "proof_incomplete"})
        for vector in committed["vectors"]:
            self.assertIn("peer_initial_state", vector)
            self.assertTrue(vector["envelopes"])
            self.assertTrue(vector["expected"]["peer_projection_digests"])
            self.assertTrue(vector["expected"]["peer_projections"])
            self.assertTrue(vector["expected"]["key_evidence_codes"])


if __name__ == "__main__":
    unittest.main()
