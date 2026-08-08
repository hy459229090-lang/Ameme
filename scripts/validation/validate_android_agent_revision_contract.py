# Purpose: keep the Android production Agent append_revision path minimal, durable, and fail-closed.
# Input: frozen Local Node schema, Android endpoint/repository/runtime/policy, Host adapter, and tests.
# Output: static JSON checks; bounded read is delegated to its own gate and no external pass is claimed.

from __future__ import annotations

import json
from pathlib import Path
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[2]
FILES = {
    "schema": ROOT / "packages/contracts/schemas/ameme-agent-local-node.schema.json",
    "repository": ROOT / "apps/android/app/src/main/java/com/ameme/android/data/MemoryRepository.kt",
    "database": ROOT
    / "apps/android/app/src/main/java/com/ameme/android/data/local/LocalEventDatabase.kt",
    "endpoint": ROOT
    / "apps/android/app/src/main/java/com/ameme/android/data/transport"
    / "MemoryRepositoryAgentLocalNodeEndpoint.kt",
    "runtime": ROOT
    / "apps/android/app/src/main/java/com/ameme/android/data/transport/AgentLocalNodeRuntime.kt",
    "grant": ROOT
    / "apps/android/app/src/main/java/com/ameme/android/data/transport/AgentAccessGrant.kt",
    "settings": ROOT
    / "apps/android/app/src/main/java/com/ameme/android/ui/screens/SettingsScreen.kt",
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
    operations = schema["$defs"]["Operation"]["enum"]
    append_payload = schema["$defs"]["AppendRevisionPayload"]
    require("append_revision" in operations, "shared v1 schema freezes append_revision", checks)
    require(
        append_payload["properties"]["memory_type"]["const"] == "revision",
        "shared append payload requires revision memory type",
        checks,
    )
    require(
        set(append_payload["required"])
        == {
            "event_id",
            "space",
            "memory_type",
            "content",
            "evidence_state",
            "fact_status",
            "now",
        },
        "shared append payload remains exact and bounded",
        checks,
    )

    require_markers(
        texts["repository"],
        (
            "data class AgentRevisionAppendResult",
            "fun appendAgentRevision(",
            "allowedSensitivities: Set<Sensitivity>",
            "A missing or deleted target is deliberately indistinguishable",
        ),
        "Android repository contract",
        checks,
    )
    require_markers(
        texts["database"],
        (
            "fun appendAgentRevision(",
            "findCurrent(eventId, includeDeleted = false)",
            "current.event.sensitivity !in allowedSensitivities",
            'reason = "agent_revision"',
            "terminalizeEventFieldEvidence(",
            "LongTermMemoryInvalidationReason.EventRevisionChanged",
            "refreshSearchIndex(updated, STATE_ACTIVE)",
            "touchDayLedger(updated.localDate)",
            "SELECT revision_id",
            "Agent revision idempotency result is unavailable",
        ),
        "SQLCipher append transaction",
        checks,
    )
    require_markers(
        texts["endpoint"],
        (
            'const val OPERATION_APPEND_REVISION = "append_revision"',
            'const val MEMORY_TYPE_REVISION = "revision"',
            "AgentLocalNodeAppendRevisionPayload",
            "AgentLocalNodeAppendRevisionResult",
            "decodeAppendRevision(payloadBytes)",
            "appendAgentRevision(",
            "allowedSensitivities = allowedSensitivities",
            "AgentLocalNodeErrorCode.NOT_VISIBLE",
            "AgentLocalNodeErrorCode.IDEMPOTENCY_CONFLICT",
            "eventRevisionId = requireNotNull(outcome.revisionId)",
            "decodeRequestEnvelope(bytes",
            "decodeAppendRevisionResult",
        ),
        "Android application endpoint",
        checks,
    )
    require(
        "confirmLongTermMemory" not in texts["endpoint"],
        "Agent revision endpoint cannot confirm long-term Memory",
        checks,
    )
    require_markers(
        texts["runtime"],
        (
            "val grantedMemoryTypes = grantedMemoryTypes(pairing.accessGrantPolicy)",
            "private fun grantedMemoryTypes(",
            "accessGrantPolicy.dataTypes.intersect(",
            "MEMORY_TYPE_REVISION in grantedMemoryTypes",
            "OPERATION_APPEND_REVISION",
            "supportedOperations = grantedOperations",
            "decodeRequestEnvelope(applicationLine)",
            "allowedMemoryTypes = grantedMemoryTypes",
            "allowedOperations = grantedOperations",
        ),
        "Android production runtime",
        checks,
    )
    require(
        'dataTypes = setOf("event", "revision")' in texts["grant"],
        "new pairing policy explicitly grants event and revision",
        checks,
    )
    require(
        "event/revision 写入" in texts["settings"],
        "pairing authorization UI discloses revision scope",
        checks,
    )

    require_markers(
        texts["host"],
        (
            "IMPLEMENTED_OPERATIONS = {",
            '"append_revision",',
            'self._request(\n            "append_revision"',
            '"memory_type": "revision"',
            '"event_revision_id"',
            'result.get("target_event_id") != event_id',
            '"android_local_node_append_result_invalid"',
        ),
        "Host Android adapter",
        checks,
    )
    require_markers(
        texts["host_core"],
        (
            'advertised_operations = getattr(self.store, "supported_operations", None)',
            '"get_event" in advertised_operations',
            "self.store.append_revision(",
        ),
        "Host write-only revision dispatch",
        checks,
    )
    for method in ("get_event", "set_policy_blocked"):
        start = texts["host"].index(f"    def {method}(")
        next_method = texts["host"].find("\n    def ", start + 8)
        section = texts["host"][start : next_method if next_method >= 0 else None]
        require(
            "self._unsupported_operation(" in section,
            f"Host {method} remains fail-closed",
            checks,
        )

    require_markers(
        texts["unit_test"],
        (
            "appendRevisionEnvelopeAndResultRemainStrictCanonicalV1",
            "appendRevisionUpdatesExactEventAndReplaysWithoutSecondMutation",
            "appendRevisionRequiresRevisionGrantAndHidesMissingOrDeletedTargets",
            "AgentLocalNodeErrorCode.NOT_VISIBLE",
            "AgentLocalNodeErrorCode.DATA_TYPE_DENIED",
            "assertEquals(2, stored.revision)",
            "assertEquals(firstResult.eventRevisionId, replayResult.eventRevisionId)",
        ),
        "Android JVM regression",
        checks,
    )
    require_markers(
        texts["instrumented_test"],
        (
            "verifiedCreateAndRevisionPersistAndReplayAcrossSqlCipherRepositoryReopen",
            "AgentLocalNodeApplicationCodec.encodeAppendRevision",
            "assertEquals(appendResult.eventRevisionId, replayResult.eventRevisionId)",
            "assertEquals(2, replayResult.revision)",
        ),
        "Android SQLCipher test source",
        checks,
    )
    require_markers(
        texts["host_test"],
        (
            "test_mcp_capture_reaches_injected_channel_without_core_fallback",
            "test_revision_only_channel_does_not_require_create_authority",
            "test_malicious_append_results_are_rejected_and_poison_channel",
            '"append_revision", channel.calls[1][0]["control"]["operation"]',
        ),
        "Host adapter regression",
        checks,
    )
    require_markers(
        texts["tls_test"],
        (
            "test_tls13_pin_handshake_and_append_revision_end_to_end",
            '"append_revision",',
            '"undo_capture",',
            '"RAW_SYNTHETIC_TLS_REVISION_KEY"',
            'self.assertEqual(["revision"], server.application_request["control"]["memory_types"])',
        ),
        "TLS Host regression",
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
    require(ignored == [1] * len(ignored), "Agent revision sources are tracked by Git", checks)

    print(
        json.dumps(
            {
                "ok": True,
                "checks": len(checks),
                "scope": "static_android_agent_append_revision_regression",
                "create_event_claim": True,
                "append_revision_claim": True,
                "read_context_or_recall_claim": "validated_by_android_agent_read_contract",
                "undo_claim": "validated_by_android_agent_undo_contract",
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
