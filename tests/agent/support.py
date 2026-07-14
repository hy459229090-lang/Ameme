from __future__ import annotations

import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path
from tempfile import TemporaryDirectory
from typing import Any, Callable


ROOT = Path(__file__).resolve().parents[2]
SERVICE_ROOT = ROOT / "services" / "ameme-mcp-mock"
CORE_ROOT = ROOT / "packages" / "core-reference"
if str(SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVICE_ROOT))
if str(CORE_ROOT) not in sys.path:
    sys.path.insert(0, str(CORE_ROOT))

from ameme_mcp_mock import AmemeMock, JsonStore  # noqa: E402
from ameme_mcp_mock.core_store import CoreEventNodeStore  # noqa: E402
from ameme_mcp_mock.native_host_store import CoreOracleHostReferenceStore  # noqa: E402


FIXED_NOW = datetime(2026, 7, 14, 4, 0, tzinfo=timezone.utc)
SEED = ROOT / "tests" / "fixtures" / "agent" / "synthetic-memories.json"


class Harness:
    def __init__(
        self,
        *,
        offline: bool = False,
        seed: bool = True,
        backend: str = "json",
        fault_injector: Callable[[str], None] | None = None,
    ) -> None:
        self.temp = TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.offline = offline
        self.backend = backend
        self.seed = seed
        self.fault_injector = fault_injector
        self._open()

    def _open(self) -> None:
        if self.backend == "json":
            seed_path = SEED if self.seed else None
            self.store = JsonStore(self.root / "state.json", seed_path)
        elif self.backend == "core":
            if self.seed:
                raise ValueError("CoreEventNodeStore tests must capture synthetic data through public commands")
            self.store = CoreEventNodeStore(
                self.root / "control.json",
                self.root / "core.sqlite3",
                clock=lambda: FIXED_NOW,
                fault_injector=self.fault_injector,
            )
        elif self.backend == "core_host":
            if self.seed:
                raise ValueError(
                    "CoreOracleHostReferenceStore tests must capture synthetic data "
                    "through public commands"
                )
            if self.fault_injector is not None:
                raise ValueError(
                    "fault injection belongs to the in-process Core oracle tests"
                )
            self.store = CoreOracleHostReferenceStore(
                self.root / "mcp-control.json",
                self.root / "core-oracle-host",
            )
        else:
            raise ValueError(f"unknown Harness backend: {self.backend}")
        self.mock = AmemeMock(
            self.store, clock=lambda: FIXED_NOW, offline=self.offline
        )

    def restart(
        self, *, fault_injector: Callable[[str], None] | None = None
    ) -> None:
        self.store.close()
        self.fault_injector = fault_injector
        self._open()

    def close(self) -> None:
        self.store.close()
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
