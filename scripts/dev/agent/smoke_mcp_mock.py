# Purpose: Exercise the six Ameme MCP mock tools through the real stdio transport.
# Input: Repository service code and synthetic Agent fixture; no network or personal data.
# Output: A JSON smoke summary; non-zero exit on protocol, policy, or lifecycle failure.
from __future__ import annotations

import argparse
import json
import subprocess
import sys
import tempfile
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[3]
SERVER = ROOT / "services" / "ameme-mcp-mock" / "server.py"
SEED = ROOT / "tests" / "fixtures" / "agent" / "synthetic-memories.json"


class Client:
    def __init__(self, data_dir: Path, backend: str) -> None:
        self.next_id = 1
        command = [
            sys.executable,
            str(SERVER),
            "--data-dir",
            str(data_dir),
            "--store-backend",
            backend,
        ]
        if backend == "json":
            command.extend(["--seed-file", str(SEED)])
        self.process = subprocess.Popen(
            command,
            cwd=ROOT,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
        )

    def request(self, method: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
        assert self.process.stdin is not None
        assert self.process.stdout is not None
        request = {"jsonrpc": "2.0", "id": self.next_id, "method": method}
        self.next_id += 1
        if params is not None:
            request["params"] = params
        self.process.stdin.write(json.dumps(request, ensure_ascii=False) + "\n")
        self.process.stdin.flush()
        line = self.process.stdout.readline()
        if not line:
            assert self.process.stderr is not None
            raise RuntimeError(f"MCP server stopped: {self.process.stderr.read()}")
        response = json.loads(line)
        if "error" in response:
            raise RuntimeError(response["error"])
        return response["result"]

    def tool(self, name: str, arguments: dict[str, Any], *, expect_error: str | None = None) -> dict[str, Any]:
        result = self.request("tools/call", {"name": name, "arguments": arguments})
        structured = result["structuredContent"]
        if expect_error:
            if not result["isError"] or structured.get("code") != expect_error:
                raise AssertionError(f"expected {expect_error}, got {structured}")
        elif result["isError"]:
            raise AssertionError(structured)
        return structured

    def close(self) -> None:
        if self.process.stdin:
            self.process.stdin.close()
        try:
            self.process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self.process.terminate()
            self.process.wait(timeout=5)


def _arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Smoke the Ameme MCP stdio server")
    parser.add_argument(
        "--store-backend",
        choices=("json", "core-oracle-host-reference"),
        default="json",
    )
    return parser.parse_args()


def main() -> int:
    arguments = _arguments()
    with tempfile.TemporaryDirectory() as directory:
        client = Client(Path(directory), arguments.store_backend)
        try:
            initialized = client.request("initialize", {"protocolVersion": "2024-11-05", "capabilities": {}, "clientInfo": {"name": "ameme-smoke", "version": "0.1"}})
            tools = client.request("tools/list")["tools"]
            if {tool["name"] for tool in tools} != {"pair", "capture", "recall", "get_context", "feedback", "status"}:
                raise AssertionError("six-tool manifest mismatch")

            now = datetime.now(timezone.utc)
            pending = client.tool(
                "pair",
                {
                    "action": "begin",
                    "caller_id": "agent_smoke",
                    "purposes": ["autonomous_memory"],
                    "spaces": ["space_work"],
                    "data_types": ["event"],
                    "expires_at": (now + timedelta(days=1)).isoformat(),
                },
            )
            ready = client.tool(
                "pair",
                {
                    "action": "approve",
                    "caller_id": "agent_smoke",
                    "challenge_id": pending["challenge_id"],
                    "confirmation": {"confirmed": True, "terms_digest": pending["terms_digest"]},
                },
            )
            common = {
                "caller_id": "agent_smoke",
                "grant_id": ready["grant_id"],
                "purpose": "autonomous_memory",
            }
            captured = client.tool(
                "capture",
                {
                    **common,
                    "space": "space_work",
                    "memory_type": "event",
                    "content": "Synthetic smoke run completed its local protocol step.",
                    "evidence_kind": "direct_evidence",
                    "idempotency_key": "smoke-capture-0001",
                },
            )
            recalled = client.tool(
                "recall",
                {**common, "spaces": ["space_work"], "memory_types": ["event"], "invocation": "autonomous"},
            )
            context = client.tool(
                "get_context",
                {**common, "space": "space_work", "memory_types": ["event"], "invocation": "autonomous"},
            )
            feedback = client.tool(
                "feedback",
                {
                    **common,
                    "space": "space_work",
                    "memory_type": "event",
                    "target_id": captured["event_id"],
                    "action": "context_useful",
                    "idempotency_key": "smoke-feedback-001",
                },
            )
            status = client.tool("status", {"caller_id": "agent_smoke", "grant_id": ready["grant_id"]})
            revoked = client.tool(
                "pair",
                {
                    "action": "revoke",
                    "caller_id": "agent_smoke",
                    "grant_id": ready["grant_id"],
                    "confirmation": {"confirmed": True},
                },
            )
            client.tool(
                "recall",
                {**common, "spaces": ["space_work"], "memory_types": ["event"]},
                expect_error="GRANT_REVOKED",
            )
            summary = {
                "status": "passed",
                "store_backend": arguments.store_backend,
                "protocol": initialized["protocolVersion"],
                "tools_checked": len(tools),
                "capture_state": [captured["persistence_state"], captured["delivery_state"]],
                "recall_state": recalled["range_state"],
                "context_state": context["state"],
                "feedback_state": [feedback["state"], feedback["persistence_state"]],
                "connection_state": status["connection_state"],
                "revoked": revoked["new_access_blocked"],
            }
            print(json.dumps(summary, ensure_ascii=False, indent=2))
            return 0
        finally:
            client.close()


if __name__ == "__main__":
    raise SystemExit(main())
