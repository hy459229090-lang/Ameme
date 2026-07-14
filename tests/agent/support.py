from __future__ import annotations

import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path
from tempfile import TemporaryDirectory
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
SERVICE_ROOT = ROOT / "services" / "ameme-mcp-mock"
if str(SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVICE_ROOT))

from ameme_mcp_mock import AmemeMock, JsonStore  # noqa: E402


FIXED_NOW = datetime(2026, 7, 14, 4, 0, tzinfo=timezone.utc)
SEED = ROOT / "tests" / "fixtures" / "agent" / "synthetic-memories.json"


class Harness:
    def __init__(self, *, offline: bool = False, seed: bool = True) -> None:
        self.temp = TemporaryDirectory()
        seed_path = SEED if seed else None
        self.store = JsonStore(Path(self.temp.name) / "state.json", seed_path)
        self.mock = AmemeMock(self.store, clock=lambda: FIXED_NOW, offline=offline)

    def close(self) -> None:
        self.temp.cleanup()

    def exact_grant(
        self,
        *,
        caller_id: str = "agent_codex_test",
        purpose: str = "autonomous_memory",
        space: str = "space_work",
        memory_types: list[str] | None = None,
    ) -> dict[str, Any]:
        memory_types = memory_types or ["event"]
        pending = self.mock.call(
            "pair",
            {
                "action": "begin",
                "caller_id": caller_id,
                "purposes": [purpose],
                "spaces": [space],
                "data_types": memory_types,
                "expires_at": (FIXED_NOW + timedelta(days=30)).isoformat(),
            },
        )
        return self.mock.call(
            "pair",
            {
                "action": "approve",
                "caller_id": caller_id,
                "challenge_id": pending["challenge_id"],
                "confirmation": {"confirmed": True, "terms_digest": pending["terms_digest"]},
            },
        )


def capture_arguments(grant_id: str, **overrides: Any) -> dict[str, Any]:
    arguments: dict[str, Any] = {
        "caller_id": "agent_codex_test",
        "grant_id": grant_id,
        "purpose": "autonomous_memory",
        "space": "space_work",
        "memory_type": "event",
        "content": "Synthetic test result was verified by a tool.",
        "evidence_kind": "direct_evidence",
        "idempotency_key": "capture-key-0001",
    }
    arguments.update(overrides)
    return arguments
