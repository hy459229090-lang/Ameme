"""EventNodeStore client for the non-production CoreOracle host process."""

from __future__ import annotations

from datetime import datetime
import json
from pathlib import Path
import queue
import subprocess
import sys
import threading
from typing import Any, Iterable

from .event_store import (
    EventNodeConflict,
    EventNodeIdempotencyConflict,
    EventNodeNotVisible,
    EventNodeScope,
    EventNodeStoreError,
    idempotency_slot,
)
from .store import JsonStore


PROTOCOL = "ameme.core-oracle-host.v1"


class CoreOracleHostReferenceStore:
    """Split MCP control state from an Oracle-backed EventNode subprocess.

    The name describes the host-process boundary consumed by an Agent. The
    current child is still the Python executable specification, not a native
    Android/iOS implementation.
    """

    def __init__(
        self,
        control_path: Path,
        host_data_dir: Path,
        *,
        host_script: Path | None = None,
        response_timeout_seconds: float = 5.0,
    ) -> None:
        if response_timeout_seconds <= 0:
            raise ValueError("response_timeout_seconds must be positive")
        self.control = JsonStore(control_path)
        self.state = self.control.state
        self.control_path = control_path
        self.host_data_dir = host_data_dir
        self.host_data_dir.mkdir(parents=True, exist_ok=True)
        default_script = Path(__file__).resolve().parents[1] / "core_oracle_host.py"
        self.host_script = host_script or default_script
        self.response_timeout_seconds = response_timeout_seconds
        creation_flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
        self._process = subprocess.Popen(
            [
                sys.executable,
                str(self.host_script),
                "--data-dir",
                str(self.host_data_dir),
            ],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            # The host contract forbids logs. DEVNULL also prevents an accidental
            # child stderr writer from applying pipe backpressure to the Agent.
            stderr=subprocess.DEVNULL,
            text=True,
            encoding="utf-8",
            bufsize=1,
            creationflags=creation_flags,
        )
        self._lock = threading.Lock()
        self._responses: queue.Queue[str | None] = queue.Queue()
        self._next_request_id = 1
        self._closed = False
        self._reader = threading.Thread(
            target=self._read_stdout,
            name="ameme-core-oracle-host-reader",
            daemon=True,
        )
        self._reader.start()
        try:
            ping = self._request("ping", {})
            if ping.get("backend") != "core-oracle-reference":
                raise EventNodeStoreError("unexpected local Core host backend")
        except Exception:
            self._terminate_process()
            self.control.close()
            self._closed = True
            raise

    @property
    def host_pid(self) -> int:
        return self._process.pid

    @property
    def host_running(self) -> bool:
        return self._process.poll() is None

    @staticmethod
    def _scope(scope: EventNodeScope) -> dict[str, Any]:
        return {
            "owner_id": scope.owner_id,
            "caller_id": scope.caller_id,
            "grant_id": scope.grant_id,
            "purpose": scope.purpose,
            "spaces": list(scope.spaces),
            "memory_types": list(scope.memory_types),
        }

    @staticmethod
    def _wire_idempotency(value: str) -> str:
        return idempotency_slot(value, domain="core-oracle-host-wire")

    def _read_stdout(self) -> None:
        assert self._process.stdout is not None
        try:
            for line in self._process.stdout:
                self._responses.put(line)
        finally:
            self._responses.put(None)

    def _terminate_process(self) -> None:
        if self._process.stdin is not None and not self._process.stdin.closed:
            self._process.stdin.close()
        if self._process.poll() is None:
            self._process.terminate()
            try:
                self._process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self._process.kill()
                self._process.wait(timeout=5)
        self._reader.join(timeout=1)
        if self._process.stdout is not None and not self._process.stdout.closed:
            self._process.stdout.close()

    def _graceful_stop_process(self) -> None:
        if self._process.stdin is not None and not self._process.stdin.closed:
            self._process.stdin.close()
        try:
            self._process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self._terminate_process()
            return
        self._reader.join(timeout=1)
        if self._process.stdout is not None and not self._process.stdout.closed:
            self._process.stdout.close()

    def _request(self, method: str, params: dict[str, Any]) -> Any:
        with self._lock:
            if self._closed:
                raise EventNodeStoreError("local Core host is closed")
            if self._process.poll() is not None:
                raise EventNodeStoreError("local Core host stopped")
            request_id = self._next_request_id
            self._next_request_id += 1
            request = {
                "protocol": PROTOCOL,
                "request_id": request_id,
                "method": method,
                "params": params,
            }
            assert self._process.stdin is not None
            assert self._process.stdout is not None
            try:
                self._process.stdin.write(
                    json.dumps(
                        request, ensure_ascii=False, separators=(",", ":")
                    )
                    + "\n"
                )
                self._process.stdin.flush()
            except (BrokenPipeError, OSError) as exc:
                self._terminate_process()
                raise EventNodeStoreError("local Core host transport failed") from exc
            try:
                line = self._responses.get(timeout=self.response_timeout_seconds)
            except queue.Empty as exc:
                self._terminate_process()
                raise EventNodeStoreError(
                    "local Core host response deadline exceeded"
                ) from exc
            if line is None:
                self._terminate_process()
                raise EventNodeStoreError("local Core host returned no response")
            try:
                response = json.loads(line)
            except json.JSONDecodeError as exc:
                self._terminate_process()
                raise EventNodeStoreError("local Core host returned invalid JSON") from exc
            if (
                response.get("protocol") != PROTOCOL
                or response.get("request_id") != request_id
            ):
                self._terminate_process()
                raise EventNodeStoreError("local Core host response mismatch")
            if response.get("ok") is True:
                return response.get("result")
            code = response.get("error", {}).get("code")
            errors = {
                "NOT_VISIBLE": EventNodeNotVisible,
                "CONFLICT": EventNodeConflict,
                "IDEMPOTENCY_CONFLICT": EventNodeIdempotencyConflict,
                "STORE_ERROR": EventNodeStoreError,
                "INVALID_REQUEST": EventNodeStoreError,
                "INTERNAL": EventNodeStoreError,
            }
            raise errors.get(code, EventNodeStoreError)(
                f"local Core host request failed: {code or 'UNKNOWN'}"
            )

    def save(self) -> None:
        self.control.save()

    def close(self) -> None:
        if self._closed:
            return
        self.control.close()
        try:
            self._request("shutdown", {})
        except EventNodeStoreError:
            pass
        self._closed = True
        self._graceful_stop_process()

    def get_event(
        self,
        event_id: str,
        *,
        scope: EventNodeScope,
        space: str,
        memory_type: str,
    ) -> dict[str, Any] | None:
        scope.require(space, memory_type)
        return self._request(
            "get_event",
            {
                "event_id": event_id,
                "scope": self._scope(scope),
                "space": space,
                "memory_type": memory_type,
            },
        )

    def create_event(
        self,
        *,
        scope: EventNodeScope,
        space: str,
        content: str,
        event_time: str | None,
        event_type: str,
        evidence_state: str,
        fact_status: str,
        sensitivity: str,
        data_class: str,
        now: str,
        idempotency_key: str,
    ) -> dict[str, Any]:
        scope.require(space, "event")
        return self._request(
            "create_event",
            {
                "scope": self._scope(scope),
                "space": space,
                "content": content,
                "event_time": event_time,
                "event_type": event_type,
                "evidence_state": evidence_state,
                "fact_status": fact_status,
                "sensitivity": sensitivity,
                "data_class": data_class,
                "now": now,
                "idempotency_key": self._wire_idempotency(idempotency_key),
            },
        )

    def append_revision(
        self,
        *,
        scope: EventNodeScope,
        space: str,
        event_id: str,
        content: str,
        evidence_state: str,
        fact_status: str,
        now: str,
        idempotency_key: str,
    ) -> dict[str, Any]:
        scope.require(space, "revision")
        return self._request(
            "append_revision",
            {
                "scope": self._scope(scope),
                "space": space,
                "event_id": event_id,
                "content": content,
                "evidence_state": evidence_state,
                "fact_status": fact_status,
                "now": now,
                "idempotency_key": self._wire_idempotency(idempotency_key),
            },
        )

    def undo_capture(
        self,
        undo: dict[str, Any],
        *,
        scope: EventNodeScope,
        space: str,
        memory_type: str,
        now: str,
        idempotency_key: str,
    ) -> dict[str, Any]:
        scope.require(space, memory_type)
        return self._request(
            "undo_capture",
            {
                "undo": undo,
                "scope": self._scope(scope),
                "space": space,
                "memory_type": memory_type,
                "now": now,
                "idempotency_key": self._wire_idempotency(idempotency_key),
            },
        )

    def visible_events(
        self,
        *,
        scope: EventNodeScope,
        spaces: Iterable[str],
        memory_types: Iterable[str],
        query: str | None,
        allow_high_risk: bool,
        start_at: datetime | None,
        end_at: datetime | None,
        limit: int = 100,
    ) -> tuple[list[dict[str, Any]], bool]:
        if not isinstance(limit, int) or isinstance(limit, bool) or not 1 <= limit <= 100:
            raise ValueError("visible event limit is invalid")
        requested_spaces = list(spaces)
        requested_types = list(memory_types)
        for space in requested_spaces:
            for memory_type in requested_types:
                scope.require(space, memory_type)
        result = self._request(
            "visible_events",
            {
                "scope": self._scope(scope),
                "spaces": requested_spaces,
                "memory_types": requested_types,
                "query": query,
                "allow_high_risk": allow_high_risk,
                "start_at": start_at.isoformat() if start_at else None,
                "end_at": end_at.isoformat() if end_at else None,
                "limit": limit,
            },
        )
        return list(result["events"]), bool(result["risk_filtered"])

    def set_policy_blocked(
        self,
        event_id: str,
        *,
        scope: EventNodeScope,
        space: str,
        memory_type: str,
    ) -> bool:
        scope.require(space, memory_type)
        return bool(
            self._request(
                "set_policy_blocked",
                {
                    "event_id": event_id,
                    "scope": self._scope(scope),
                    "space": space,
                    "memory_type": memory_type,
                },
            )
        )

    def enqueue_delivery(
        self,
        *,
        object_type: str,
        object_id: str,
        now: str,
        idempotency_key: str,
    ) -> dict[str, Any]:
        return self._request(
            "enqueue_delivery",
            {
                "object_type": object_type,
                "object_id": object_id,
                "now": now,
                "idempotency_key": self._wire_idempotency(idempotency_key),
            },
        )

    def queued_count(self) -> int:
        return int(self._request("queued_count", {}))
