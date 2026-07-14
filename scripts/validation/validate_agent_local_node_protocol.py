# Purpose: validate Agent Local Node v1 schema, stable enums, limits and synthetic conformance vectors.
# Input: packages/contracts schema, executable spec and committed tests/fixtures/agent vector document.
# Output: JSON verdict with check/error counts; exits non-zero on protocol or golden-vector drift.

from __future__ import annotations

import json
from pathlib import Path
import sys
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
PACKAGE_ROOT = ROOT / "packages" / "agent-local-node-protocol"
SCHEMA_PATH = ROOT / "packages" / "contracts" / "schemas" / "ameme-agent-local-node.schema.json"
FIXTURE_PATH = ROOT / "tests" / "fixtures" / "agent" / "agent-local-node-conformance-v1.json"
if str(PACKAGE_ROOT) not in sys.path:
    sys.path.insert(0, str(PACKAGE_ROOT))

from ameme_agent_local_node_protocol import (  # noqa: E402
    ERROR_CODES,
    MAX_PAYLOAD_BYTES,
    MAX_REQUEST_BYTES,
    MAX_RESPONSE_BYTES,
    OPERATIONS,
    PROTOCOL_VERSION,
    validate_conformance_document,
)


class Checks:
    def __init__(self) -> None:
        self.count = 0
        self.errors: list[str] = []

    def check(self, condition: bool, message: str) -> None:
        self.count += 1
        if not condition:
            self.errors.append(message)


def _walk_refs(node: Any, defs: dict[str, Any], checks: Checks, path: str = "schema") -> None:
    if isinstance(node, dict):
        ref = node.get("$ref")
        if isinstance(ref, str) and ref.startswith("#/$defs/"):
            checks.check(ref.removeprefix("#/$defs/") in defs, f"{path}: missing {ref}")
        for key, value in node.items():
            _walk_refs(value, defs, checks, f"{path}.{key}")
    elif isinstance(node, list):
        for index, value in enumerate(node):
            _walk_refs(value, defs, checks, f"{path}[{index}]")


def main() -> int:
    checks = Checks()
    try:
        schema = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
        fixture = json.loads(FIXTURE_PATH.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        print(json.dumps({"ok": False, "errors": [f"{type(exc).__name__}: {exc}"]}, indent=2))
        return 1

    checks.check(
        schema.get("$schema") == "https://json-schema.org/draft/2020-12/schema",
        "wire schema must use JSON Schema 2020-12",
    )
    defs = schema.get("$defs")
    checks.check(isinstance(defs, dict), "wire schema must have $defs")
    defs = defs if isinstance(defs, dict) else {}
    _walk_refs(schema, defs, checks)
    for name, definition in defs.items():
        if isinstance(definition, dict) and definition.get("type") == "object":
            checks.check(
                definition.get("additionalProperties") is False,
                f"$defs/{name} must set additionalProperties=false",
            )
    checks.check(
        defs.get("ProtocolVersion", {}).get("const") == PROTOCOL_VERSION,
        "schema/executable protocol version mismatch",
    )
    checks.check(
        set(defs.get("Operation", {}).get("enum", [])) == set(OPERATIONS),
        "schema/executable operation enum mismatch",
    )
    checks.check(
        set(defs.get("ErrorCode", {}).get("enum", [])) == set(ERROR_CODES),
        "schema/executable error enum mismatch",
    )
    comment = str(schema.get("$comment", ""))
    for limit in (MAX_REQUEST_BYTES, MAX_PAYLOAD_BYTES, MAX_RESPONSE_BYTES):
        checks.check(str(limit) in comment, f"schema comment missing byte limit {limit}")
    checks.check(fixture.get("synthetic_only") is True, "fixture must declare synthetic_only")
    checks.check(
        fixture.get("protocol_version") == PROTOCOL_VERSION,
        "fixture protocol version mismatch",
    )
    checks.errors.extend(validate_conformance_document(fixture))
    checks.count += 1
    verdict = {
        "ok": not checks.errors,
        "checks": checks.count,
        "positive_requests": len(fixture.get("positive_requests", [])),
        "negative_requests": len(fixture.get("negative_requests", [])),
        "errors": checks.errors,
        "claims": "schema_and_synthetic_conformance_only",
    }
    print(json.dumps(verdict, ensure_ascii=False, indent=2))
    return 0 if verdict["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
