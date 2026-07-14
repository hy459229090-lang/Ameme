# Purpose: run the Ameme no-network AI processing reference on one synthetic observation.
# Input: tests/fixtures/ai/synthetic_ai_eval.json; no network, model credentials or personal data.
# Output: deterministic JSON with a synthetic EventCandidate and content-free observation metadata.

from __future__ import annotations

import json
from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "packages" / "ai-processing-reference"))

from ameme_ai_reference import AIProcessingReference, PolicyContext  # noqa: E402


def main() -> int:
    fixture = json.loads(
        (ROOT / "tests" / "fixtures" / "ai" / "synthetic_ai_eval.json").read_text(
            encoding="utf-8"
        )
    )
    reference = AIProcessingReference()
    draft = reference.create_candidate(
        [fixture["observations"]["sparse"]],
        context=PolicyContext(
            space_id=fixture["space_id"],
            purpose="form_today",
            sensitivity="personal",
            processing_locations=frozenset({"device"}),
            budget_remaining=0,
        ),
    )
    output = {
        "claim": "synthetic_no_network_reference_only",
        "candidate": dict(draft.candidate),
        "fact_status": draft.fact_status,
        "fact_confidence": draft.fact_confidence,
        "observer": reference.observer.records,
    }
    print(json.dumps(output, ensure_ascii=False, sort_keys=True, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
