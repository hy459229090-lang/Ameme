# Purpose: keep Android production Agent visible_events read bounded, least-privilege, and fail-closed.
# Input: frozen Local Node schema, Android repository/runtime/pairing code, Host adapter, and regressions.
# Output: static JSON checks; this gate claims no real Host, physical device, shared Grant, or release pass.

from __future__ import annotations

import json
from pathlib import Path
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[2]
FILES = {
    "schema": ROOT / "packages/contracts/schemas/ameme-agent-local-node.schema.json",
    "repository": ROOT / "apps/android/app/src/main/java/com/ameme/android/data/MemoryRepository.kt",
    "fake_repository": ROOT
    / "apps/android/app/src/main/java/com/ameme/android/data/FakeMemoryRepository.kt",
    "database": ROOT
    / "apps/android/app/src/main/java/com/ameme/android/data/local/LocalEventDatabase.kt",
    "endpoint": ROOT
    / "apps/android/app/src/main/java/com/ameme/android/data/transport"
    / "MemoryRepositoryAgentLocalNodeEndpoint.kt",
    "runtime": ROOT
    / "apps/android/app/src/main/java/com/ameme/android/data/transport/AgentLocalNodeRuntime.kt",
    "policy": ROOT
    / "apps/android/app/src/main/java/com/ameme/android/data/transport/AgentAccessGrant.kt",
    "pairing": ROOT
    / "apps/android/app/src/main/java/com/ameme/android/data/transport/AgentPairingManager.kt",
    "unit_test": ROOT
    / "apps/android/app/src/test/java/com/ameme/android/data/transport"
    / "MemoryRepositoryAgentLocalNodeEndpointTest.kt",
    "instrumented_test": ROOT
    / "apps/android/app/src/androidTest/java/com/ameme/android/data/transport"
    / "AgentLocalNodeEndpointInstrumentedTest.kt",
    "host": ROOT
    / "services/ameme-mcp-mock/ameme_mcp_mock/android_local_node_store.py",
    "host_core": ROOT / "services/ameme-mcp-mock/ameme_mcp_mock/core.py",
    "host_test": ROOT
    / "services/ameme-mcp-mock/tests/test_android_local_node_store.py",
    "tls_test": ROOT
    / "services/ameme-mcp-mock/tests/test_tls_android_local_node_channel.py",
}


def require(condition: bool, message: str, checks: list[str]) -> None:
    if not condition:
        raise AssertionError(message)
    checks.append(message)


def require_markers(
    text: str,
    markers: tuple[str, ...],
    label: str,
    checks: list[str],
) -> None:
    for marker in markers:
        require(marker in text, f"{label} declares {marker}", checks)


def method_section(text: str, method: str) -> str:
    start = text.index(f"    def {method}(")
    end = text.find("\n    def ", start + 8)
    return text[start : end if end >= 0 else None]


def main() -> int:
    checks: list[str] = []
    for label, path in FILES.items():
        require(path.is_file(), f"{label} file exists: {path.relative_to(ROOT)}", checks)
    texts = {
        label: path.read_text(encoding="utf-8")
        for label, path in FILES.items()
        if label != "schema"
    }
    schema = json.loads(FILES["schema"].read_text(encoding="utf-8"))
    payload = schema["$defs"]["VisibleEventsPayload"]
    require(
        "visible_events" in schema["$defs"]["Operation"]["enum"],
        "shared v1 schema freezes visible_events",
        checks,
    )
    require(
        set(payload["required"]) == {
            "spaces",
            "memory_types",
            "allow_high_risk",
            "limit",
        }
        and payload["additionalProperties"] is False,
        "shared visible payload keeps an exact required surface",
        checks,
    )
    require(
        payload["properties"]["query"]["maxLength"] == 1_000
        and payload["properties"]["limit"]["minimum"] == 1
        and payload["properties"]["limit"]["maximum"] == 100,
        "shared visible query and result count are bounded",
        checks,
    )

    require_markers(
        texts["repository"],
        (
            "data class AgentVisibleEventsReadResult",
            "fun readAgentVisibleEvents(",
            "allowedSensitivities: Set<Sensitivity>",
            "must not influence [AgentVisibleEventsReadResult.riskFiltered]",
        ),
        "Android repository contract",
        checks,
    )
    require_markers(
        texts["database"],
        (
            "fun readAgentVisibleEvents(",
            "if (isLocalSpaceDeleted())",
            '"e.space_id = ?", "e.state = ?"',
            "events_fts MATCH ?",
            "toAgentFtsExpression(terms)",
            "(e.title LIKE ? ESCAPE '\\\\' OR e.detail LIKE ? ESCAPE '\\\\')",
            "e.sensitivity IN ($placeholders)",
            "Sensitivity.Restricted in allowedSensitivities",
            "AND e.sensitivity = ?",
            "LIMIT ?",
            "substr(COALESCE(e.local_time",
        ),
        "SQLCipher bounded current projection",
        checks,
    )
    require_markers(
        texts["fake_repository"],
        (
            "override fun readAgentVisibleEvents(",
            "allowedSensitivities - Sensitivity.Restricted",
            "Sensitivity.Restricted in allowedSensitivities",
            "val haystack = listOf(title, detail)",
            ".take(limit)",
        ),
        "synthetic repository semantic parity",
        checks,
    )

    require_markers(
        texts["endpoint"],
        (
            'const val OPERATION_VISIBLE_EVENTS = "visible_events"',
            "AgentLocalNodeVisibleEventsPayload",
            "AgentLocalNodeEventView",
            "AgentLocalNodeVisibleEventsResult",
            "memoryTypes != setOf(MEMORY_TYPE_EVENT)",
            "readAgentVisibleEvents(",
            "allowedSensitivities = allowedSensitivities",
            "allowHighRisk = command.allowHighRisk",
            "limit = command.limit",
            "MAX_READ_TITLE_CODE_POINTS = 240",
            "MAX_READ_DESCRIPTION_CODE_POINTS = 1_000",
            "contentTruncated = safeTitle != title || safeDescription != detail",
            "decodeVisibleEventsResult",
        ),
        "Android application endpoint",
        checks,
    )
    event_view = texts["endpoint"].split(
        "internal data class AgentLocalNodeEventView(", 1
    )[1].split("\n}\n", 1)[0]
    for forbidden in ("userWords", "sourceLabel", "locator", "raw"):
        require(
            forbidden not in event_view,
            f"Agent visible event view excludes {forbidden}",
            checks,
        )
    require(
        "confirmLongTermMemory" not in texts["endpoint"],
        "Agent read endpoint cannot confirm long-term Memory",
        checks,
    )

    require_markers(
        texts["runtime"],
        (
            "OPERATION_VISIBLE_EVENTS in accessGrantPolicy.operations",
            "add(MemoryRepositoryAgentLocalNodeEndpoint.OPERATION_VISIBLE_EVENTS)",
            'allowedSensitivities = setOf("public", "personal", "confidential")',
            "supportedOperations = grantedOperations",
            "allowedOperations = grantedOperations",
        ),
        "Android production runtime",
        checks,
    )
    persisted_callback = texts["runtime"].split("control.operation in setOf(", 1)[1].split(
        ")", 1
    )[0]
    require(
        "OPERATION_VISIBLE_EVENTS" not in persisted_callback,
        "successful reads do not emit event-persisted callbacks",
        checks,
    )
    require_markers(
        texts["policy"],
        (
            "OPERATION_VISIBLE_EVENTS !in operations",
            "MEMORY_TYPE_EVENT in dataTypes",
            "operations = MemoryRepositoryAgentLocalNodeEndpoint.IMPLEMENTED_OPERATIONS",
        ),
        "pairing operation policy",
        checks,
    )
    require_markers(
        texts["pairing"],
        (
            "KEY_GRANT_OPERATIONS",
            "!preferences.contains(KEY_GRANT_OPERATIONS)",
            "revoke()",
        ),
        "legacy pairing fail-closed persistence",
        checks,
    )

    require_markers(
        texts["host"],
        (
            '"visible_events",',
            'self._request(\n            "visible_events"',
            '"spaces": requested_spaces',
            '"memory_types": requested_types',
            '"allow_high_risk": allow_high_risk',
            '"limit": limit',
            "set(result) == {\"events\", \"risk_filtered\"}",
            "len(events) <= limit",
            "android_local_node_visible_events_result_invalid",
            "event.get(\"data_class\") != \"structured\"",
            'event["sensitivity"] != "restricted"',
        ),
        "Host Android read adapter",
        checks,
    )
    for method in ("get_event", "set_policy_blocked"):
        require(
            "self._unsupported_operation(" in method_section(texts["host"], method),
            f"Host {method} remains fail-closed",
            checks,
        )
    require(
        "self._unsupported_operation(" not in method_section(texts["host"], "visible_events"),
        "Host visible_events reaches the authenticated channel",
        checks,
    )
    require_markers(
        texts["host_core"],
        (
            "limit=limit,",
            "limit=min(DEFAULT_CONTEXT_ITEMS + 1, 100),",
            "INJECTION_PATTERN.search(body)",
            "safe_events[:item_budget]",
            '"untrusted_memory": True',
        ),
        "Host Recall and ContextPack budgets",
        checks,
    )

    require_markers(
        texts["unit_test"],
        (
            "visibleEventsEnvelopeAndResultRemainStrictCanonicalV1",
            "visibleEventsReturnsBoundedCurrentProjectionAndHidesDeletedEvents",
            "visibleEventsRiskFilterDoesNotLeakUnauthorizedSensitivityAndTruncatesContent",
            "visibleEventsRequiresExplicitOperationAndExactEventSpaceScope",
            "assertFalse(unauthorized.riskFiltered)",
            "assertTrue(allowed.events.single().contentTruncated)",
            "req_visible_hidden_user_words",
            "req_visible_hidden_source_label",
        ),
        "Android JVM read regressions",
        checks,
    )
    require_markers(
        texts["instrumented_test"],
        (
            "boundedVisibleEventsPersistsSensitivityAndDeletionBoundariesAcrossReopen",
            "assertTrue(policyFiltered.riskFiltered)",
            "assertFalse(unauthorized.riskFiltered)",
            "assertTrue(result.events.none",
        ),
        "Android SQLCipher read test source",
        checks,
    )
    require_markers(
        texts["host_test"],
        (
            "test_visible_events_is_bounded_and_read_only_channel_is_accepted",
            "test_malicious_visible_event_results_fail_closed_and_poison_channel",
            '"visible_events", channel.calls[3][0]["control"]["operation"]',
            '"visible_events", channel.calls[4][0]["control"]["operation"]',
        ),
        "Host Recall and ContextPack regressions",
        checks,
    )
    require_markers(
        texts["tls_test"],
        (
            "test_tls13_pin_handshake_and_visible_events_end_to_end",
            '"visible_events",',
            '"allow_high_risk": False',
            '"limit": 5',
            'self.assertEqual("TLSv1.3", server.tls_version)',
        ),
        "TLS visible-events regression",
        checks,
    )

    ignored = [
        subprocess.run(
            ["git", "check-ignore", "--quiet", str(path.relative_to(ROOT))],
            cwd=ROOT,
            check=False,
        ).returncode
        for path in FILES.values()
    ]
    require(ignored == [1] * len(ignored), "Agent read sources are tracked by Git", checks)

    print(
        json.dumps(
            {
                "ok": True,
                "checks": len(checks),
                "scope": "static_android_agent_bounded_visible_events",
                "visible_events_claim": True,
                "get_event_claim": False,
                "set_policy_blocked_claim": False,
                "recall_context_host_adapter_claim": True,
                "raw_or_locator_disclosure_claim": False,
                "restricted_runtime_claim": False,
                "long_term_memory_confirmation_claim": False,
                "real_host_execution_claim": False,
                "physical_device_execution_claim": False,
                "shared_account_grant_claim": False,
                "release_claim": False,
            },
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (AssertionError, KeyError, ValueError) as error:
        print(json.dumps({"ok": False, "error": str(error)}, indent=2))
        sys.exit(1)
