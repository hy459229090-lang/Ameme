# Purpose: validate iOS Local Node client parity for the four Android production v1 operations.
# Input: frozen schema, Swift Grant/channel/client/smokes/tests, and Android endpoint authorization.
# Output: a static JSON verdict; this does not claim new network, device, user, or host execution.

from __future__ import annotations

import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCHEMA = ROOT / "packages/contracts/schemas/ameme-agent-local-node.schema.json"
SWIFT_GRANT = ROOT / "apps/ios/Ameme/Shared/AgentAccessGrant.swift"
SWIFT_CHANNEL = ROOT / "apps/ios/Ameme/Shared/AgentLocalNodeChannel.swift"
SWIFT_CLIENT = ROOT / "apps/ios/Ameme/Shared/AgentLocalNodeNetworkClient.swift"
SWIFT_CONNECTOR = ROOT / "apps/ios/Ameme/Shared/AgentExperienceConnector.swift"
SWIFT_SHARED_SMOKE = ROOT / "apps/ios/Smoke/main.swift"
SWIFT_NETWORK_SMOKE = ROOT / "apps/ios/LocalNodeSmoke/main.swift"
SWIFT_TEST = ROOT / "apps/ios/Tests/AmemeSharedTests/AgentExperienceTests.swift"
ANDROID_ENDPOINT = (
    ROOT
    / "apps/android/app/src/main/java/com/ameme/android/data/transport/"
    "MemoryRepositoryAgentLocalNodeEndpoint.kt"
)
ANDROID_GRANT = (
    ROOT
    / "apps/android/app/src/main/java/com/ameme/android/data/transport/AgentAccessGrant.kt"
)

IMPLEMENTED_OPERATIONS = {
    "create_event",
    "append_revision",
    "undo_capture",
    "visible_events",
}
CLIENT_BUILDERS = {
    "create_event": "buildCreateEventRequest",
    "append_revision": "buildAppendRevisionRequest",
    "undo_capture": "buildUndoCaptureRequest",
    "visible_events": "buildVisibleEventsRequest",
}
CLIENT_EXCHANGES = {
    "create_event": "exchangeCreateEvent",
    "append_revision": "exchangeAppendRevision",
    "undo_capture": "exchangeUndoCapture",
    "visible_events": "exchangeVisibleEvents",
}
CLIENT_PARSERS = {
    "create_event": "parseCreateEventResponse",
    "append_revision": "parseAppendRevisionResponse",
    "undo_capture": "parseUndoCaptureResponse",
    "visible_events": "parseVisibleEventsResponse",
}


def read(path: Path) -> str:
    if not path.is_file():
        raise AssertionError(f"missing required file: {path.relative_to(ROOT)}")
    return path.read_text(encoding="utf-8")


def require(condition: bool, message: str, checks: list[str]) -> None:
    if not condition:
        raise AssertionError(message)
    checks.append(message)


def main() -> int:
    checks: list[str] = []
    schema = json.loads(read(SCHEMA))
    grant = read(SWIFT_GRANT)
    channel = read(SWIFT_CHANNEL)
    client = read(SWIFT_CLIENT)
    connector = read(SWIFT_CONNECTOR)
    shared_smoke = read(SWIFT_SHARED_SMOKE)
    network_smoke = read(SWIFT_NETWORK_SMOKE)
    test = read(SWIFT_TEST)
    android_endpoint = read(ANDROID_ENDPOINT)
    android_grant = read(ANDROID_GRANT)

    definitions = schema["$defs"]
    schema_operations = set(definitions["Operation"]["enum"])
    require(
        IMPLEMENTED_OPERATIONS.issubset(schema_operations),
        "frozen v1 schema contains all four production client operations",
        checks,
    )
    require(
        {"get_event", "set_policy_blocked"}.issubset(schema_operations),
        "frozen v1 keeps known but production-disabled operations explicit",
        checks,
    )
    require(
        definitions["VisibleEventsPayload"]["properties"]["limit"]["maximum"] == 100,
        "visible_events remains bounded to 100 results",
        checks,
    )
    require(
        definitions["VisibleEventsPayload"]["properties"]["query"]["maxLength"] == 1_000,
        "visible_events query remains bounded to 1,000 code points",
        checks,
    )
    require(
        definitions["AppendRevisionPayload"]["properties"]["content"]["maxLength"] == 4_000,
        "append_revision content remains bounded to 4,000 code points",
        checks,
    )
    require(
        definitions["UndoCapturePayload"]["properties"]["undo_token"]["$ref"]
        == "#/$defs/Identifier",
        "undo_capture carries only an opaque bounded identifier",
        checks,
    )

    require(
        "public func authorizeScope(" in grant
        and "spaces.isSubset(of: Set(self.spaces))" in grant
        and "dataTypes.isSubset(of: Set(self.dataTypes))" in grant,
        "Swift Grant authorizes explicit minimal scope subsets",
        checks,
    )
    require(
        'dataTypes: ["event"],' in grant,
        "ordinary-user Swift default Grant remains event-only",
        checks,
    )
    require(
        'capabilities: ["写入结构化工作记录"]' in connector,
        "ordinary-user QR capability copy does not silently expand",
        checks,
    )
    require(
        "client.supportedOperations().contains(" in connector
        and "AgentLocalNodeChannelCodec.operationCreateEvent" in connector,
        "ordinary-user connector verifies its advertised Event-write capability",
        checks,
    )

    for operation, builder in CLIENT_BUILDERS.items():
        require(
            f"public static func {builder}(" in channel,
            f"Swift channel exposes {operation} canonical builder",
            checks,
        )
        require(
            f'operation{operation.title().replace("_", "")}' not in channel
            or f'"{operation}"' in channel,
            f"Swift {operation} remains bound to its frozen wire value",
            checks,
        )
    for operation, exchange in CLIENT_EXCHANGES.items():
        require(
            f"public func {exchange}(" in client,
            f"Swift network client exposes typed {operation} exchange",
            checks,
        )
    require(
        "private func exchange(applicationLine:" in client
        and "public func exchange(applicationLine:" not in client,
        "raw application exchange cannot bypass typed Grant and operation checks",
        checks,
    )
    require(
        "authorizationDate: Date = .now" not in client
        and client.count("authorizationDate: .now") == len(CLIENT_EXCHANGES),
        "production exchanges authorize against current time rather than caller-supplied time",
        checks,
    )
    for operation, parser in CLIENT_PARSERS.items():
        require(
            f"public static func {parser}(" in channel,
            f"Swift channel validates typed {operation} response",
            checks,
        )

    for marker, description in (
        ("negotiatedOperations = Set(serverHello.supportedOperations)", "handshake operations are retained"),
        ("try requireOperation(", "each typed exchange checks negotiated operation support"),
        ("case operationUnsupported", "unsupported negotiated operations fail locally"),
        ("private func exchangeValidated<Result>(", "typed application validation is centralized"),
        ("catch let remoteError as AgentLocalNodeRemoteError", "valid remote errors preserve the channel"),
        ("close()\n            throw channelError", "malformed typed responses destroy the channel"),
    ):
        require(marker in client or marker in channel, description, checks)

    for marker, description in (
        ('keys: [\n                "protocol_version", "request_id", "status",', "response envelope has exact fields"),
        ("guard try canonicalJSONData(application) == applicationLine", "responses must be canonical bytes"),
        ("digest(resultData) == resultDigest", "successful result digest is verified"),
        ("retryable == code.retryable", "remote retryability cannot drift"),
        ("application[\"result\"] is NSNull", "error responses cannot smuggle a result"),
        ("application[\"error\"] is NSNull", "successful responses cannot smuggle an error"),
        ("eventIDs.insert(eventID).inserted", "visible events reject duplicate identities"),
        ("rawEvents.count <= 100", "visible response count is bounded"),
        ("value.unicodeScalars.count <= maximum", "Swift validates code-point bounds"),
        ("start! <= end!", "visible time ranges reject inversion"),
        ("memoryTypes == [\"event\"]", "production visible read is Event-only"),
    ):
        require(marker in channel, description, checks)

    error_values = set(definitions["ErrorCode"]["enum"])
    for value in sorted(error_values):
        require(
            f'= "{value}"' in channel,
            f"Swift maps frozen error {value}",
            checks,
        )
    require(
        "self == .temporarilyUnavailable || self == .internalError" in channel,
        "only frozen transient/internal errors are retryable",
        checks,
    )

    require(
        "buildGetEventRequest" not in channel
        and "exchangeGetEvent" not in client
        and "buildSetPolicyBlockedRequest" not in channel
        and "exchangeSetPolicyBlocked" not in client,
        "iOS keeps get_event and set_policy_blocked closed",
        checks,
    )
    require(
        "LongTermMemory" not in channel and "LongTermMemory" not in client,
        "Local Node client does not confirm long-term Memory",
        checks,
    )
    require(
        "print(" not in channel and "print(" not in client,
        "production channel/client do not log content or secrets",
        checks,
    )

    for operation in sorted(IMPLEMENTED_OPERATIONS):
        require(
            f'"{operation}"' in android_endpoint,
            f"Android endpoint still implements {operation}",
            checks,
        )
    require(
        "dataTypes = setOf(\"event\", \"revision\")" in android_grant,
        "Android pairing policy can authorize explicit Event/Revision scope",
        checks,
    )
    require(
        "allowedOperations = grantedOperations" in read(
            ROOT
            / "apps/android/app/src/main/java/com/ameme/android/data/transport/"
            "AgentLocalNodeRuntime.kt"
        ),
        "Android runtime binds the negotiated operation set",
        checks,
    )

    for marker, description in (
        ("testLocalNodeBuildersCoverBoundedReadRevisionAndExactUndo", "XCTest covers the three new builders"),
        ("testLocalNodeTypedResponsesRejectDigestAndRetryabilityDrift", "XCTest covers malicious responses"),
        ("iOS response parser accepted a mismatched result digest", "Shared Smoke rejects digest drift"),
        ("iOS event-only ordinary-user Grant unexpectedly expanded to revision", "Shared Smoke rejects scope expansion"),
        ("bounded_visible_events", "network smoke reports bounded read only after execution"),
        ("append_revision", "network smoke exercises revision write"),
        ("exact_event_and_revision_undo", "network smoke exercises exact Event/Revision undo"),
        ("!finalRead.events.contains", "network smoke checks Event undo disappearance"),
    ):
        source = test if marker.startswith("test") else (
            network_smoke if marker in network_smoke else shared_smoke
        )
        require(marker in source, description, checks)

    print(
        json.dumps(
            {
                "ok": True,
                "checks": len(checks),
                "scope": "static_ios_agent_local_node_four_operation_client_and_response_validation",
                "ios_client_builder_claim": True,
                "ios_typed_response_claim": True,
                "new_operation_network_execution_claim": False,
                "physical_device_execution_claim": False,
                "ordinary_user_scope_expansion_claim": False,
                "get_event_claim": False,
                "set_policy_blocked_claim": False,
                "long_term_memory_confirmation_claim": False,
                "shared_account_grant_claim": False,
                "release_claim": False,
            },
            indent=2,
        )
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (AssertionError, KeyError, OSError, ValueError, json.JSONDecodeError) as error:
        print(json.dumps({"ok": False, "errors": [str(error)]}, indent=2))
        raise SystemExit(1)
