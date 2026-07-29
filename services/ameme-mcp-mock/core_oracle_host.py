"""Run the non-production CoreOracle adapter behind a JSONL stdio boundary.

This process is an executable-spec host for integration and developer preview
only. It is not an Android/iOS Local Node and must never be presented as a
production storage runtime.
"""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import sys
from typing import Any, Callable


ROOT = Path(__file__).resolve().parents[2]
SERVICE_ROOT = ROOT / "services" / "ameme-mcp-mock"
CORE_ROOT = ROOT / "packages" / "core-reference"
for import_root in (SERVICE_ROOT, CORE_ROOT):
    if str(import_root) not in sys.path:
        sys.path.insert(0, str(import_root))

from ameme_mcp_mock.core_store import CoreEventNodeStore  # noqa: E402
from ameme_mcp_mock.event_store import (  # noqa: E402
    EventNodeConflict,
    EventNodeIdempotencyConflict,
    EventNodeNotVisible,
    EventNodeScope,
    EventNodeStoreError,
)


PROTOCOL = "ameme.core-oracle-host.v1"


def _clock() -> datetime:
    return datetime.now(timezone.utc)


def _parse_datetime(value: Any) -> datetime | None:
    if value is None:
        return None
    if not isinstance(value, str):
        raise ValueError("datetime must be an ISO-8601 string")
    return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(
        timezone.utc
    )


def _string(value: Any, field: str) -> str:
    if not isinstance(value, str) or not value:
        raise ValueError(f"{field} must be a non-empty string")
    return value


def _strings(value: Any, field: str) -> list[str]:
    if (
        not isinstance(value, list)
        or not value
        or any(not isinstance(item, str) or not item for item in value)
    ):
        raise ValueError(f"{field} must be a non-empty string array")
    return value


def _scope(value: Any) -> EventNodeScope:
    if not isinstance(value, dict):
        raise ValueError("scope must be an object")
    return EventNodeScope(
        owner_id=_string(value["owner_id"], "owner_id"),
        caller_id=_string(value["caller_id"], "caller_id"),
        grant_id=_string(value["grant_id"], "grant_id"),
        purpose=_string(value["purpose"], "purpose"),
        spaces=tuple(_strings(value["spaces"], "spaces")),
        memory_types=tuple(_strings(value["memory_types"], "memory_types")),
    )


class Host:
    """Strict method dispatcher; stdout is reserved for protocol messages."""

    def __init__(self, store: CoreEventNodeStore) -> None:
        self.store = store
        self.stopping = False
        self._methods: dict[str, Callable[[dict[str, Any]], Any]] = {
            "ping": self._ping,
            "get_event": self._get_event,
            "create_event": self._create_event,
            "append_revision": self._append_revision,
            "undo_capture": self._undo_capture,
            "visible_events": self._visible_events,
            "set_policy_blocked": self._set_policy_blocked,
            "enqueue_delivery": self._enqueue_delivery,
            "queued_count": self._queued_count,
            "shutdown": self._shutdown,
        }

    def handle(self, request: Any) -> dict[str, Any]:
        request_id = request.get("request_id") if isinstance(request, dict) else None
        try:
            if not isinstance(request, dict) or request.get("protocol") != PROTOCOL:
                raise ValueError("unsupported or missing protocol")
            method = request.get("method")
            params = request.get("params", {})
            if method not in self._methods or not isinstance(params, dict):
                raise ValueError("unknown method or invalid params")
            result = self._methods[method](params)
            return {
                "protocol": PROTOCOL,
                "request_id": request_id,
                "ok": True,
                "result": result,
            }
        except EventNodeNotVisible:
            return self._error(request_id, "NOT_VISIBLE")
        except EventNodeConflict:
            return self._error(request_id, "CONFLICT")
        except EventNodeIdempotencyConflict:
            return self._error(request_id, "IDEMPOTENCY_CONFLICT")
        except EventNodeStoreError:
            return self._error(request_id, "STORE_ERROR")
        except (KeyError, TypeError, ValueError):
            return self._error(request_id, "INVALID_REQUEST")
        except Exception:
            # Do not reflect exception strings: they can contain caller data.
            return self._error(request_id, "INTERNAL")

    @staticmethod
    def _error(request_id: Any, code: str) -> dict[str, Any]:
        return {
            "protocol": PROTOCOL,
            "request_id": request_id,
            "ok": False,
            "error": {"code": code, "message": "request_failed"},
        }

    @staticmethod
    def _ping(_params: dict[str, Any]) -> dict[str, Any]:
        return {
            "backend": "core-oracle-reference",
            "production_ready": False,
        }

    def _get_event(self, params: dict[str, Any]) -> dict[str, Any] | None:
        return self.store.get_event(
            str(params["event_id"]),
            scope=_scope(params["scope"]),
            space=str(params["space"]),
            memory_type=str(params["memory_type"]),
        )

    def _create_event(self, params: dict[str, Any]) -> dict[str, Any]:
        return self.store.create_event(
            scope=_scope(params["scope"]),
            space=str(params["space"]),
            content=str(params["content"]),
            event_time=(
                str(params["event_time"])
                if params.get("event_time") is not None
                else None
            ),
            event_type=str(params["event_type"]),
            evidence_state=str(params["evidence_state"]),
            fact_status=str(params["fact_status"]),
            sensitivity=str(params["sensitivity"]),
            data_class=str(params["data_class"]),
            now=str(params["now"]),
            idempotency_key=str(params["idempotency_key"]),
        )

    def _append_revision(self, params: dict[str, Any]) -> dict[str, Any]:
        return self.store.append_revision(
            scope=_scope(params["scope"]),
            space=str(params["space"]),
            event_id=str(params["event_id"]),
            content=str(params["content"]),
            evidence_state=str(params["evidence_state"]),
            fact_status=str(params["fact_status"]),
            now=str(params["now"]),
            idempotency_key=str(params["idempotency_key"]),
        )

    def _undo_capture(self, params: dict[str, Any]) -> dict[str, Any]:
        undo = params["undo"]
        if not isinstance(undo, dict):
            raise ValueError("undo must be an object")
        return self.store.undo_capture(
            undo,
            scope=_scope(params["scope"]),
            space=str(params["space"]),
            memory_type=str(params["memory_type"]),
            now=str(params["now"]),
            idempotency_key=str(params["idempotency_key"]),
        )

    def _visible_events(self, params: dict[str, Any]) -> dict[str, Any]:
        if not isinstance(params["allow_high_risk"], bool):
            raise ValueError("allow_high_risk must be a boolean")
        events, risk_filtered = self.store.visible_events(
            scope=_scope(params["scope"]),
            spaces=_strings(params["spaces"], "spaces"),
            memory_types=_strings(params["memory_types"], "memory_types"),
            query=(str(params["query"]) if params.get("query") is not None else None),
            allow_high_risk=params["allow_high_risk"],
            start_at=_parse_datetime(params.get("start_at")),
            end_at=_parse_datetime(params.get("end_at")),
            limit=int(params.get("limit", 100)),
        )
        return {"events": events, "risk_filtered": risk_filtered}

    def _set_policy_blocked(self, params: dict[str, Any]) -> bool:
        result = self.store.set_policy_blocked(
            str(params["event_id"]),
            scope=_scope(params["scope"]),
            space=str(params["space"]),
            memory_type=str(params["memory_type"]),
        )
        self.store.save()
        return result

    def _enqueue_delivery(self, params: dict[str, Any]) -> dict[str, Any]:
        result = self.store.enqueue_delivery(
            object_type=str(params["object_type"]),
            object_id=str(params["object_id"]),
            now=str(params["now"]),
            idempotency_key=str(params["idempotency_key"]),
        )
        self.store.save()
        return result

    def _queued_count(self, _params: dict[str, Any]) -> int:
        return self.store.queued_count()

    def _shutdown(self, _params: dict[str, Any]) -> dict[str, bool]:
        self.stopping = True
        return {"stopping": True}


def _arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Non-production Ameme CoreOracle JSONL host"
    )
    parser.add_argument("--data-dir", required=True, type=Path)
    return parser.parse_args()


def main() -> int:
    arguments = _arguments()
    arguments.data_dir.mkdir(parents=True, exist_ok=True)
    store = CoreEventNodeStore(
        arguments.data_dir / "control.json",
        arguments.data_dir / "core.sqlite3",
        clock=_clock,
    )
    host = Host(store)
    try:
        for line in sys.stdin:
            if not line.strip():
                continue
            try:
                request = json.loads(line)
            except json.JSONDecodeError:
                response = Host._error(None, "INVALID_REQUEST")
            else:
                response = host.handle(request)
            sys.stdout.write(
                json.dumps(response, ensure_ascii=False, separators=(",", ":"))
                + "\n"
            )
            sys.stdout.flush()
            if host.stopping:
                break
    finally:
        store.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
