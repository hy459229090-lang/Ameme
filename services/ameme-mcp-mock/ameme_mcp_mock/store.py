"""Small durable JSON store used by the local mock."""

from __future__ import annotations

import json
import os
from copy import deepcopy
from pathlib import Path
from typing import Any


EMPTY_STATE: dict[str, Any] = {
    "state_version": 1,
    "challenges": {},
    "grants": {},
    "events": {},
    "revisions": {},
    "feedback": {},
    "context_packs": {},
    "activity": [],
    "idempotency": {},
    "queue": [],
    "undo": {},
}


class JsonStore:
    """Persist mock state atomically without external dependencies."""

    def __init__(self, path: Path, seed_path: Path | None = None) -> None:
        self.path = path
        self.path.parent.mkdir(parents=True, exist_ok=True)
        if self.path.exists():
            self.state = json.loads(self.path.read_text(encoding="utf-8"))
        else:
            self.state = deepcopy(EMPTY_STATE)
            if seed_path:
                seed = json.loads(seed_path.read_text(encoding="utf-8"))
                for event in seed.get("events", []):
                    self.state["events"][event["event_id"]] = event
            self.save()

    def save(self) -> None:
        temporary = self.path.with_suffix(self.path.suffix + ".tmp")
        temporary.write_text(
            json.dumps(self.state, ensure_ascii=False, indent=2, sort_keys=True),
            encoding="utf-8",
        )
        os.replace(temporary, self.path)
