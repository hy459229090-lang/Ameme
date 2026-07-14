# Purpose: pass a local Codex task event export through the Ameme Skill/MCP capture policy and build an Android debug seed.
# Input: a local-only JSON event export plus ignored state/output paths; no cloud or raw Codex session database access.
# Output: a content-bearing local debug seed for one-time Android instrumentation injection and a content-free stdout summary.

from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timedelta, timezone
import hashlib
import json
from pathlib import Path
import re
import sys
from typing import Any


ROOT = Path(__file__).resolve().parents[3]
SERVICE_ROOT = ROOT / "services" / "ameme-mcp-mock"
PROTOCOL_ROOT = ROOT / "packages" / "agent-local-node-protocol"
for package_root in (SERVICE_ROOT, PROTOCOL_ROOT):
    if str(package_root) not in sys.path:
        sys.path.insert(0, str(package_root))

from ameme_agent_local_node_protocol import derive_idempotency_slot  # noqa: E402
from ameme_mcp_mock import AmemeMock, JsonStore  # noqa: E402


CALLER_ID = "agent_codex_local_demo"
PURPOSE = "autonomous_memory"
SPACE_ID = "space_work"
MAX_EVENTS = 12
MAX_SOURCE_BYTES = 64 * 1024
IDENTIFIER = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
EVENT_TYPES = {
    "activity",
    "communication",
    "decision",
    "result",
    "state_change",
    "milestone",
    "experience",
}
EVIDENCE_KINDS = {"direct_evidence", "user_statement", "inference"}


def _parse_time(value: Any, field: str) -> datetime:
    if not isinstance(value, str) or len(value) > 64 or "\x00" in value:
        raise ValueError(f"{field} must be a bounded ISO-8601 string")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise ValueError(f"{field} must be ISO-8601") from exc
    if parsed.tzinfo is None:
        raise ValueError(f"{field} must include an offset")
    return parsed.astimezone(timezone.utc)


def _load_source(source_path: Path) -> tuple[str, list[dict[str, Any]], datetime]:
    if source_path.stat().st_size > MAX_SOURCE_BYTES:
        raise ValueError("source export exceeds the local demo limit")
    source = json.loads(source_path.read_text(encoding="utf-8"))
    if not isinstance(source, dict) or set(source) != {"thread_id", "events"}:
        raise ValueError("source export must contain only thread_id and events")
    thread_id = source["thread_id"]
    if not isinstance(thread_id, str) or not IDENTIFIER.fullmatch(thread_id):
        raise ValueError("thread_id must be a bounded identifier")
    events = source["events"]
    if not isinstance(events, list) or not 1 <= len(events) <= MAX_EVENTS:
        raise ValueError(f"events must contain 1..{MAX_EVENTS} entries")

    latest = datetime.min.replace(tzinfo=timezone.utc)
    seen_turns: set[str] = set()
    allowed_keys = {
        "source_turn_id",
        "content",
        "event_time",
        "event_type",
        "evidence_kind",
        "sensitivity",
        "data_class",
    }
    for event in events:
        if not isinstance(event, dict) or set(event) != allowed_keys:
            raise ValueError("each event must use the exact local demo fields")
        turn_id = event["source_turn_id"]
        if not isinstance(turn_id, str) or not IDENTIFIER.fullmatch(turn_id):
            raise ValueError("source_turn_id must be a bounded identifier")
        if turn_id in seen_turns:
            raise ValueError("source_turn_id must be unique")
        seen_turns.add(turn_id)
        content = event["content"]
        if (
            not isinstance(content, str)
            or not content.strip()
            or len(content) > 4_000
            or "\x00" in content
        ):
            raise ValueError("content must be non-empty, bounded, and NUL-free")
        if event["event_type"] not in EVENT_TYPES:
            raise ValueError("unsupported event_type")
        if event["evidence_kind"] not in EVIDENCE_KINDS:
            raise ValueError("unsupported evidence_kind")
        if event["sensitivity"] not in {"public", "personal"}:
            raise ValueError("local Codex demo permits only public or personal sensitivity")
        if event["data_class"] != "structured":
            raise ValueError("local Codex demo permits only structured data")
        latest = max(latest, _parse_time(event["event_time"], "event_time"))
    return thread_id, events, latest + timedelta(seconds=1)


def _raw_idempotency_key(thread_id: str, event: dict[str, Any]) -> str:
    semantic = json.dumps(
        {
            "thread_id": thread_id,
            "source_turn_id": event["source_turn_id"],
            "content": event["content"],
            "event_time": event["event_time"],
            "evidence_kind": event["evidence_kind"],
        },
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return "codex-demo-v1-" + hashlib.sha256(semantic).hexdigest()


def build_seed(source_path: Path, output_path: Path, state_dir: Path) -> dict[str, Any]:
    thread_id, events, fixed_now = _load_source(source_path)
    if state_dir.exists() and any(state_dir.iterdir()):
        raise ValueError("state_dir must be empty for a one-time local demo export")
    if output_path.exists():
        raise ValueError("output must not already exist for a one-time local demo export")
    state_dir.mkdir(parents=True, exist_ok=True)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    store = JsonStore(state_dir / "state.json")
    mock = AmemeMock(store, clock=lambda: fixed_now)
    try:
        pending = mock.call(
            "pair",
            {
                "action": "begin",
                "caller_id": CALLER_ID,
                "purposes": [PURPOSE],
                "spaces": [SPACE_ID],
                "data_types": ["event"],
                "expires_at": (fixed_now + timedelta(days=30)).isoformat(),
            },
        )
        grant = mock.call(
            "pair",
            {
                "action": "approve",
                "caller_id": CALLER_ID,
                "challenge_id": pending["challenge_id"],
                "confirmation": {
                    "confirmed": True,
                    "terms_digest": pending["terms_digest"],
                },
            },
        )

        android_events: list[dict[str, Any]] = []
        for event in events:
            raw_key = _raw_idempotency_key(thread_id, event)
            result = mock.call(
                "capture",
                {
                    "caller_id": CALLER_ID,
                    "grant_id": grant["grant_id"],
                    "purpose": PURPOSE,
                    "space": SPACE_ID,
                    "memory_type": "event",
                    "content": event["content"],
                    "event_time": event["event_time"],
                    "event_type": event["event_type"],
                    "evidence_kind": event["evidence_kind"],
                    "sensitivity": event["sensitivity"],
                    "data_class": event["data_class"],
                    "idempotency_key": raw_key,
                },
            )
            stored = store.state["events"][result["event_id"]]
            android_events.append(
                {
                    "source_turn_id": event["source_turn_id"],
                    "content": stored["description"],
                    "event_time": stored["time_range"]["start"],
                    "event_type": stored["event_type"],
                    "evidence_state": stored["evidence_state"],
                    "fact_status": stored["fact_status"],
                    "sensitivity": stored["sensitivity"],
                    "data_class": stored["data_class"],
                    "idempotency_slot": derive_idempotency_slot(
                        raw_key,
                        operation="create_event",
                    ),
                }
            )

        document = {
            "schema_version": 1,
            "thread_id": thread_id,
            "generated_at": fixed_now.isoformat().replace("+00:00", "Z"),
            "events": android_events,
        }
        output_path.write_text(
            json.dumps(document, ensure_ascii=False, sort_keys=True, separators=(",", ":")),
            encoding="utf-8",
        )
        evidence_counts = Counter(event["evidence_state"] for event in android_events)
        return {
            "ok": True,
            "event_count": len(android_events),
            "evidence_counts": dict(sorted(evidence_counts.items())),
            "scope": {"space": SPACE_ID, "data_class": "structured"},
            "processing_location": "local_only",
        }
    finally:
        store.close()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--state-dir", type=Path, required=True)
    parser.add_argument(
        "--confirm-local-private-data",
        action="store_true",
        help="acknowledge that source/output/state contain local private data and must stay ignored",
    )
    args = parser.parse_args()
    if not args.confirm_local_private_data:
        raise SystemExit("--confirm-local-private-data is required")
    summary = build_seed(args.source, args.output, args.state_dir)
    print(json.dumps(summary, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
