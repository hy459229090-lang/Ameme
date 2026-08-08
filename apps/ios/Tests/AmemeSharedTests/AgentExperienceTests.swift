import Foundation
import XCTest
@testable import AmemeShared

@MainActor
final class AgentExperienceTests: XCTestCase {
    func testAccessGrantAuthorizesOnlyBoundedMinimalRequest() throws {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let grant = AgentAccessGrant(
            grantID: "grt_synthetic_001",
            ownerID: "user_synthetic_001",
            callerID: "agent_synthetic_001",
            purposes: ["autonomous_memory"],
            spaces: ["space_personal"],
            dataTypes: ["event"],
            notBefore: now,
            expiresAt: now.addingTimeInterval(3_600),
            status: .active,
            createdAt: now
        )
        let request = AgentAccessGrantRequest(
            callerID: "agent_synthetic_001",
            grantID: "grt_synthetic_001",
            purpose: "autonomous_memory",
            space: "space_personal",
            dataType: "event",
            operation: "create_event"
        )
        XCTAssertNoThrow(try grant.authorize(request, at: now.addingTimeInterval(1)))

        let expanded = AgentAccessGrantRequest(
            callerID: request.callerID,
            grantID: request.grantID,
            purpose: request.purpose,
            space: "space_other",
            dataType: request.dataType,
            operation: request.operation
        )
        XCTAssertThrowsError(try grant.authorize(expanded, at: now.addingTimeInterval(1))) { error in
            XCTAssertEqual(error as? AgentAccessGrantError, .spaceDenied)
        }
    }

    func testAccessGrantExpiryAndRevocationFailClosed() throws {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let request = AgentAccessGrantRequest(
            callerID: "agent_synthetic_001",
            grantID: "grt_synthetic_001",
            purpose: "autonomous_memory",
            space: "space_personal",
            dataType: "event",
            operation: "create_event"
        )
        let expired = AgentAccessGrant(
            grantID: request.grantID,
            ownerID: "user_synthetic_001",
            callerID: request.callerID,
            purposes: [request.purpose],
            spaces: [request.space],
            dataTypes: [request.dataType],
            notBefore: now,
            expiresAt: now.addingTimeInterval(10),
            status: .active,
            createdAt: now
        )
        XCTAssertThrowsError(try expired.authorize(request, at: now.addingTimeInterval(10))) { error in
            XCTAssertEqual(error as? AgentAccessGrantError, .grantExpired)
        }

        let revoked = AgentAccessGrant(
            grantID: request.grantID,
            ownerID: "user_synthetic_001",
            callerID: request.callerID,
            purposes: [request.purpose],
            spaces: [request.space],
            dataTypes: [request.dataType],
            notBefore: now,
            expiresAt: now.addingTimeInterval(3_600),
            status: .revoked,
            createdAt: now,
            revokedAt: now.addingTimeInterval(2)
        )
        XCTAssertThrowsError(try revoked.authorize(request, at: now.addingTimeInterval(3))) { error in
            XCTAssertEqual(error as? AgentAccessGrantError, .grantRevoked)
        }
    }

    func testAccessGrantPolicyBindsIncomingGrantToApprovedScope() throws {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let policy = AgentAccessGrantPolicy.default(
            createdAt: now,
            expiresAt: now.addingTimeInterval(3_600)
        )
        let grant = policy.bind(callerID: "agent_synthetic_001", grantID: "grt_from_host_001")
        let request = AgentAccessGrantRequest(
            callerID: "agent_synthetic_001",
            grantID: "grt_from_host_001",
            purpose: "autonomous_memory",
            space: "space_personal",
            dataType: "event",
            operation: "create_event"
        )
        XCTAssertNoThrow(try grant.authorize(request, at: now.addingTimeInterval(1)))
        let expanded = AgentAccessGrantRequest(
            callerID: request.callerID,
            grantID: request.grantID,
            purpose: request.purpose,
            space: "space_other",
            dataType: request.dataType,
            operation: request.operation
        )
        XCTAssertThrowsError(try grant.authorize(expanded, at: now.addingTimeInterval(1))) { error in
            XCTAssertEqual(error as? AgentAccessGrantError, .spaceDenied)
        }
    }

    func testSimulatedConnectorCompletesOnlyExplicitMockPath() async throws {
        let connector = SimulatedAgentExperienceConnector()
        let candidate = try await connector.resolve(method: .accountDevice)
        XCTAssertTrue(connector.simulated)
        XCTAssertTrue(candidate.simulated)
        XCTAssertEqual(candidate.method, .accountDevice)

        let connection = try await connector.connect(candidate: candidate)
        XCTAssertTrue(connection.simulated)
        XCTAssertEqual(connection.id, candidate.id)
    }

    func testBonjourConnectorNeverPromotesDiscoveryToConnection() async {
        let connector = BonjourAgentExperienceConnector()
        let candidate = AgentExperienceCandidate(
            id: "desktop-001",
            deviceName: "Ameme Desktop",
            agentName: "Codex",
            method: .lanDiscovery,
            capabilities: ["能力待授权确认"],
            simulated: false
        )

        do {
            _ = try await connector.connect(candidate: candidate)
            XCTFail("discovery candidate was promoted without authorization")
        } catch let error as AgentExperienceConnectorError {
            XCTAssertEqual(error, .authorizationRequired)
        } catch {
            XCTFail("unexpected connector error: \(error)")
        }
    }

    func testQrEnvelopeResolvesOnlyToAnExplicitBoundedCandidate() async throws {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let pairing = AgentLocalNodePairingMaterial(
            endpointRef: "endpoint-ref:synthetic/android",
            credentialRef: "credential-ref:env/SYNTHETIC_SECRET",
            expectedDeviceID: "device_synthetic_001",
            sessionBindingRef: "session-binding-ref:synthetic/session",
            pairingID: "pair_synthetic_001",
            host: "127.0.0.1",
            port: 44_321,
            tlsCertificateSHA256: "sha256_" + String(repeating: "a", count: 64)
        )
        let envelope = try AgentPairingEnvelope(
            pairing: pairing,
            bootstrapID: "boot_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            bootstrapSecret: Data(repeating: 0x42, count: 32),
            expiresAt: now.addingTimeInterval(300),
            pairingExpiresAt: now.addingTimeInterval(30 * 24 * 60 * 60)
        )
        let payload = try envelope.encodedPayload()
        let connector = BonjourAgentExperienceConnector(now: { now })

        let candidate = try await connector.resolve(pairingPayload: payload)

        XCTAssertEqual(
            envelope.bootstrapSecret,
            Data(repeating: 0x42, count: 32)
        )
        XCTAssertEqual(candidate.id, pairing.pairingID)
        XCTAssertEqual(candidate.method, .qrCode)
        XCTAssertEqual(candidate.capabilities, ["写入结构化工作记录"])
        XCTAssertFalse(candidate.simulated)
        do {
            _ = try await BonjourAgentExperienceConnector(
                now: { now.addingTimeInterval(301) }
            ).resolve(pairingPayload: payload)
            XCTFail("expired QR pairing envelope was accepted")
        } catch {
            // Expected.
        }
    }

    func testBonjourContractProducesNonSensitiveUnscopedCandidate() {
        let candidate = AgentExperienceCandidate(
            id: "desktop-001",
            deviceName: "Ameme Desktop",
            agentName: "Codex",
            method: .lanDiscovery,
            capabilities: ["能力待授权确认"],
            simulated: false
        )

        XCTAssertEqual(AgentExperienceServiceContract.bonjourServiceType, "_ameme-agent._tcp")
        XCTAssertFalse(candidate.simulated)
        XCTAssertEqual(candidate.capabilities, ["能力待授权确认"])
    }

    func testAllMethodsPersistOnlyStructuredNonSensitiveConnectionMetadata() throws {
        let suiteName = "AmemeAgentExperience-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let store = AgentExperienceStore(defaults: defaults, now: { now })

        for method in AgentConnectionMethod.allCases {
            let connection = AgentExperienceConnection.simulatedDemo(method: method, connectedAt: now)
            try store.save(connection)
            XCTAssertEqual(try store.load(), connection)
            XCTAssertTrue(connection.simulated)
            XCTAssertEqual(connection.capabilities, ["写入结构化工作记录"])
            XCTAssertFalse(
                String(decoding: defaults.data(forKey: AgentExperienceStore.userDefaultsKey) ?? Data(), as: UTF8.self)
                    .contains("secret")
            )
        }
        try store.clearAndVerify()
        XCTAssertNil(defaults.object(forKey: AgentExperienceStore.userDefaultsKey))
    }

    func testExpiredConnectionIsClearedAndMalformedStateFailsClosed() throws {
        let suiteName = "AmemeAgentExperienceInvalid-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let connectedAt = Date(timeIntervalSince1970: 1_800_000_000)
        let activeStore = AgentExperienceStore(defaults: defaults, now: { connectedAt })
        let expiredStore = AgentExperienceStore(
            defaults: defaults,
            now: { connectedAt.addingTimeInterval(AgentExperienceConnection.defaultLifetime + 1) }
        )

        try activeStore.save(.simulatedDemo(method: .accountDevice, connectedAt: connectedAt))
        XCTAssertNil(try expiredStore.load())
        XCTAssertNil(defaults.data(forKey: AgentExperienceStore.userDefaultsKey))

        defaults.set(Data("not-json".utf8), forKey: AgentExperienceStore.userDefaultsKey)
        XCTAssertThrowsError(try expiredStore.load())
        XCTAssertNil(defaults.data(forKey: AgentExperienceStore.userDefaultsKey))
    }

    func testRealConnectionMetadataLoadsOnlyAsReconnectHint() throws {
        let suiteName = "AmemeAgentExperienceRealRestore-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let store = AgentExperienceStore(defaults: defaults, now: { now })
        let connection = AgentExperienceConnection(
            id: "desktop-real-001",
            deviceName: "Ameme Desktop",
            agentName: "Codex",
            method: .lanDiscovery,
            capabilities: ["写入结构化工作记录"],
            connectedAt: now,
            expiresAt: now.addingTimeInterval(AgentExperienceConnection.defaultLifetime),
            simulated: false
        )

        try store.save(connection)

        let reconnectHint = try XCTUnwrap(store.load())
        XCTAssertEqual(reconnectHint, connection)
        XCTAssertFalse(reconnectHint.simulated)
        XCTAssertNotNil(defaults.data(forKey: AgentExperienceStore.userDefaultsKey))
    }

    func testLocalNodeBuildersCoverBoundedReadRevisionAndExactUndo() throws {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let grant = AgentAccessGrant(
            grantID: "grant_ios_parity",
            ownerID: "owner_ios_parity",
            callerID: "agent_ios_parity",
            purposes: ["autonomous_memory"],
            spaces: ["space_personal"],
            dataTypes: ["event", "revision"],
            notBefore: now,
            expiresAt: now.addingTimeInterval(3_600),
            status: .active,
            createdAt: now
        )
        let revisionKey = "ios-parity-revision-key"
        let revision = try AgentLocalNodeChannelCodec.buildAppendRevisionRequest(
            draft: AgentLocalNodeAppendRevisionDraft(
                requestID: "req_ios_parity_revision",
                idempotencyKey: revisionKey,
                eventID: "evt_ios_parity",
                space: "space_personal",
                content: "Synthetic correction",
                evidenceState: "user_asserted",
                factStatus: "user_asserted",
                now: "2027-01-15T08:00:00Z"
            ),
            grant: grant,
            authorizationDate: now.addingTimeInterval(1)
        )
        let revisionObject = try XCTUnwrap(
            JSONSerialization.jsonObject(with: revision) as? [String: Any]
        )
        let revisionControl = try XCTUnwrap(revisionObject["control"] as? [String: Any])
        XCTAssertEqual(revisionControl["operation"] as? String, "append_revision")
        XCTAssertEqual(revisionControl["memory_types"] as? [String], ["revision"])

        let undoToken = AgentLocalNodeChannelCodec.deriveIdempotencySlot(
            rawKey: revisionKey,
            operation: AgentLocalNodeChannelCodec.operationAppendRevision
        )
        let undo = try AgentLocalNodeChannelCodec.buildUndoCaptureRequest(
            draft: AgentLocalNodeUndoCaptureDraft(
                requestID: "req_ios_parity_undo",
                idempotencyKey: "ios-parity-undo-key",
                undoToken: undoToken,
                space: "space_personal",
                memoryType: "revision",
                now: "2027-01-15T08:00:01Z"
            ),
            grant: grant,
            authorizationDate: now.addingTimeInterval(2)
        )
        let undoPayload = try XCTUnwrap(
            (JSONSerialization.jsonObject(with: undo) as? [String: Any])?["payload"]
                as? [String: Any]
        )
        XCTAssertEqual(undoPayload["undo_token"] as? String, undoToken)

        let visible = try AgentLocalNodeChannelCodec.buildVisibleEventsRequest(
            draft: AgentLocalNodeVisibleEventsDraft(
                requestID: "req_ios_parity_visible",
                idempotencyKey: "ios-parity-visible-key",
                spaces: ["space_personal"],
                query: "synthetic",
                limit: 20
            ),
            grant: grant,
            authorizationDate: now.addingTimeInterval(3)
        )
        let visibleObject = try XCTUnwrap(
            JSONSerialization.jsonObject(with: visible) as? [String: Any]
        )
        let visibleControl = try XCTUnwrap(visibleObject["control"] as? [String: Any])
        XCTAssertEqual(visibleControl["operation"] as? String, "visible_events")
        XCTAssertEqual(visibleControl["spaces"] as? [String], ["space_personal"])
    }

    func testLocalNodeTypedResponsesRejectDigestAndRetryabilityDrift() throws {
        let result: [String: Any] = [
            "event_revision_id": "rev_ios_parity_002",
            "object_type": "revision",
            "revision": 2,
            "target_event_id": "evt_ios_parity",
        ]
        let resultData = try AgentLocalNodeChannelCodec.canonicalJSONData(result)
        let valid = try AgentLocalNodeChannelCodec.canonicalJSONData([
            "protocol_version": AgentLocalNodeChannelCodec.applicationProtocol,
            "request_id": "req_ios_parity_revision",
            "status": "ok",
            "result": result,
            "result_digest": AgentLocalNodeChannelCodec.digest(resultData),
            "error": NSNull(),
        ])
        XCTAssertEqual(
            try AgentLocalNodeChannelCodec.parseAppendRevisionResponse(
                valid,
                expectedRequestID: "req_ios_parity_revision"
            ).eventRevisionID,
            "rev_ios_parity_002"
        )

        let wrongDigest = try AgentLocalNodeChannelCodec.canonicalJSONData([
            "protocol_version": AgentLocalNodeChannelCodec.applicationProtocol,
            "request_id": "req_ios_parity_revision",
            "status": "ok",
            "result": result,
            "result_digest": "sha256_" + String(repeating: "0", count: 64),
            "error": NSNull(),
        ])
        XCTAssertThrowsError(
            try AgentLocalNodeChannelCodec.parseAppendRevisionResponse(
                wrongDigest,
                expectedRequestID: "req_ios_parity_revision"
            )
        )

        let invalidRetryability = try AgentLocalNodeChannelCodec.canonicalJSONData([
            "protocol_version": AgentLocalNodeChannelCodec.applicationProtocol,
            "request_id": "req_ios_parity_visible",
            "status": "error",
            "result": NSNull(),
            "result_digest": NSNull(),
            "error": ["code": "NOT_VISIBLE", "retryable": true],
        ])
        XCTAssertThrowsError(
            try AgentLocalNodeChannelCodec.parseVisibleEventsResponse(
                invalidRetryability,
                expectedRequestID: "req_ios_parity_visible"
            )
        )
    }
}
