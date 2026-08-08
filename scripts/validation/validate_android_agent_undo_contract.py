# Purpose: keep Android production Agent Event/Revision undo exact, durable, bounded, and fail-closed.
# Input: frozen Local Node schema, Android SQLCipher/runtime/pairing code, Host adapter, and regressions.
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
    "policy": ROOT
    / "apps/android/app/src/main/java/com/ameme/android/data/transport/AgentAccessGrant.kt",
    "pairing": ROOT
    / "apps/android/app/src/main/java/com/ameme/android/data/transport/AgentPairingManager.kt",
    "settings": ROOT
    / "apps/android/app/src/main/java/com/ameme/android/ui/screens/SettingsScreen.kt",
    "unit_test": ROOT
    / "apps/android/app/src/test/java/com/ameme/android/data/transport"
    / "MemoryRepositoryAgentLocalNodeEndpointTest.kt",
    "instrumented_test": ROOT
    / "apps/android/app/src/androidTest/java/com/ameme/android/data/transport"
    / "AgentLocalNodeEndpointInstrumentedTest.kt",
    "pairing_test": ROOT
    / "apps/android/app/src/androidTest/java/com/ameme/android/data/transport"
    / "AgentPairingManagerInstrumentedTest.kt",
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
    undo_payload = schema["$defs"]["UndoCapturePayload"]
    require("undo_capture" in operations, "shared v1 schema freezes undo_capture", checks)
    require(
        set(undo_payload["required"]) == {"undo_token", "space", "memory_type", "now"},
        "shared undo payload is exact",
        checks,
    )
    require(
        undo_payload["properties"]["memory_type"]["$ref"] == "#/$defs/MemoryType"
        and schema["$defs"]["MemoryType"]["enum"] == ["event", "revision"],
        "shared undo payload permits only Event or Revision",
        checks,
    )

    require_markers(
        texts["repository"],
        (
            "data class AgentCaptureUndoTarget",
            "data class AgentCaptureUndoResult",
            "class AgentCaptureUndoConflictException",
            "fun undoAgentCapture(",
            "Event capture is tombstoned; Revision capture appends a compensating revision",
        ),
        "Android repository contract",
        checks,
    )
    require_markers(
        texts["database"],
        (
            "fun undoAgentCapture(",
            "current.event.sensitivity !in allowedSensitivities",
            "AGENT_CAPTURE_UNDO_REASON",
            "AGENT_REVISION_UNDO_REASON",
            "throw AgentCaptureUndoConflictException()",
            "findRevision(target.eventId, target.createdRevision - 1)",
            "terminalizeEventFieldEvidence(",
            "LongTermMemoryInvalidationReason.EventRevisionChanged",
            "refreshSearchIndex(restored, STATE_ACTIVE)",
            "resolveAgentUndoIdempotency(",
            'require(binding.operation == "undo_capture")',
            'undoToken.matches(Regex("idem_[0-9a-f]{64}"))',
            "findAgentUndoSource(binding, undoToken)",
            "AGENT_CAPTURE_UNDO_TTL_SECONDS * 1_000L",
            "at.toEpochMilli() >= expiresAtMillis",
            "AgentLocalNodeUndoIdempotencyResult.Conflict",
            "AgentLocalNodeUndoIdempotencyResult.NotVisible",
            'put("operation", binding.operation)',
            'put("payload_digest", payloadDigest)',
            'put("revision", outcome.terminalRevision)',
        ),
        "SQLCipher atomic undo and replay",
        checks,
    )
    require(
        "previous_event_snapshot" not in texts["database"],
        "SQLCipher Agent undo persists no duplicate previous-content snapshot",
        checks,
    )

    require_markers(
        texts["endpoint"],
        (
            'const val OPERATION_UNDO_CAPTURE = "undo_capture"',
            "AgentLocalNodeUndoCapturePayload",
            "AgentLocalNodeUndoCaptureResult",
            "decodeUndoCapture(payloadBytes)",
            "resolveOrUndo(",
            "undoToken = command.undoToken",
            "undoAgentCapture(",
            "AgentLocalNodeErrorCode.REVISION_CONFLICT",
            "AgentLocalNodeErrorCode.IDEMPOTENCY_CONFLICT",
            "AgentLocalNodeErrorCode.NOT_VISIBLE",
            "encodeUndoCaptureResult",
            "decodeUndoCaptureResult",
            "compensationRevisionId = outcome.compensationRevisionId",
        ),
        "Android application endpoint",
        checks,
    )
    require(
        "confirmLongTermMemory" not in texts["endpoint"],
        "Agent undo endpoint cannot confirm long-term Memory",
        checks,
    )
    require_markers(
        texts["runtime"],
        (
            "OPERATION_UNDO_CAPTURE in accessGrantPolicy.operations",
            "add(MemoryRepositoryAgentLocalNodeEndpoint.OPERATION_UNDO_CAPTURE)",
            "supportedOperations = grantedOperations",
            "allowedOperations = grantedOperations",
        ),
        "Android runtime capability derivation",
        checks,
    )
    require_markers(
        texts["policy"],
        (
            "val operations: Set<String>",
            "operations contain an unsupported capability",
            "operations = MemoryRepositoryAgentLocalNodeEndpoint.IMPLEMENTED_OPERATIONS",
        ),
        "local pairing policy",
        checks,
    )
    require_markers(
        texts["pairing"],
        (
            "KEY_GRANT_OPERATIONS",
            ".putString(KEY_GRANT_OPERATIONS",
            ".remove(KEY_GRANT_OPERATIONS)",
            "!preferences.contains(KEY_GRANT_OPERATIONS)",
            "operations = operations",
            "loadAccessGrantPolicy(expiresAt) ?: run",
            "revoke()",
        ),
        "pairing persistence and old-policy reauthorization",
        checks,
    )
    require(
        "写入与 10 分钟撤销" in texts["settings"],
        "pairing UI discloses bounded undo authority",
        checks,
    )

    require_markers(
        texts["host"],
        (
            "IMPLEMENTED_OPERATIONS = {",
            '"undo_capture",',
            'derive_idempotency_slot(\n            idempotency_key, operation="create_event"',
            'derive_idempotency_slot(\n            idempotency_key, operation="append_revision"',
            'result["_undo_token_hint"] = remote_undo_token',
            '"undo_token": idempotency_key',
            'self._request(\n            "undo_capture"',
            '"android_local_node_undo_result_invalid"',
            'result.get("target_event_id") == expected_event_id',
            'result.get("undone_object_id") == expected_object_id',
            "compensation_id != expected_object_id",
        ),
        "Host Android adapter",
        checks,
    )
    undo_method = texts["host"].split("    def undo_capture(", 1)[1].split("\n    def ", 1)[0]
    require(
        '"undo": undo' not in undo_method and '"internal"' not in undo_method,
        "Host sends only opaque undo token and frozen payload fields",
        checks,
    )
    require_markers(
        texts["host_core"],
        (
            'undo_token_hint = result.pop("_undo_token_hint", None)',
            "undo_token = undo_token_hint",
            'raise MockError("INTERNAL_ERROR", "store_undo_token_invalid"',
            "self.store.undo_capture(",
            'idempotency_key=arguments["undo_token"]',
        ),
        "Host control-plane undo binding",
        checks,
    )

    require_markers(
        texts["unit_test"],
        (
            "undoEnvelopeAndConditionalResultsRemainStrictCanonicalV1",
            "eventUndoTombstonesOnlyExactCaptureAndReplaysWithoutSecondMutation",
            "revisionUndoAppendsCompensationAndChangedHeadConflicts",
            "undoHidesUnknownTokenAndRequiresExactTypeGrant",
            "AgentLocalNodeErrorCode.REVISION_CONFLICT",
            "AgentLocalNodeErrorCode.NOT_VISIBLE",
            "assertEquals(3, restored.revision)",
        ),
        "Android JVM undo regressions",
        checks,
    )
    require_markers(
        texts["instrumented_test"],
        (
            "verifiedCreateAndRevisionPersistAndReplayAcrossSqlCipherRepositoryReopen",
            "exactEventUndoTombstoneAndResultReplayAcrossSqlCipherRepositoryReopen",
            "AgentLocalNodeApplicationCodec.encodeUndoCapture",
            "compensationRevisionId,",
            "assertTrue(reopened.loadActiveEvents().none",
        ),
        "Android SQLCipher undo test source",
        checks,
    )
    require_markers(
        texts["pairing_test"],
        (
            "legacy_pairing_without_explicit_operations_is_revoked_instead_of_expanded",
            'remove("grant_operations")',
            "assertNull(manager.loadActive())",
            'setOf("create_event", "append_revision", "undo_capture", "visible_events")',
        ),
        "pairing operation-consent test source",
        checks,
    )
    require_markers(
        texts["host_test"],
        (
            "test_mcp_capture_reaches_injected_channel_without_core_fallback",
            "test_visible_events_and_mutations_are_supported_but_target_read_stays_closed",
            "test_malicious_undo_results_are_rejected_and_poison_channel",
            '"undo_capture", channel.calls[2][0]["control"]["operation"]',
            'self.assertNotIn("internal", json.dumps(undo_request))',
        ),
        "Host adapter undo regressions",
        checks,
    )
    require_markers(
        texts["tls_test"],
        (
            "test_tls13_pin_handshake_and_event_undo_end_to_end",
            '"undo_capture",',
            '"undo_token": undo_token',
            'self.assertNotIn("SYNTHETIC_INTERNAL_UNDO_LINEAGE"',
            'self.assertEqual("TLSv1.3", server.tls_version)',
        ),
        "TLS undo regression",
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
    require(ignored == [1] * len(ignored), "Agent undo sources are tracked by Git", checks)

    print(
        json.dumps(
            {
                "ok": True,
                "checks": len(checks),
                "scope": "static_android_agent_exact_event_revision_undo",
                "create_event_claim": True,
                "append_revision_claim": True,
                "undo_claim": True,
                "undo_window_minutes": 10,
                "event_undo_tombstone_claim": True,
                "revision_undo_compensation_claim": True,
                "read_context_or_recall_claim": "validated_by_android_agent_read_contract",
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
