import Foundation
import CryptoKit

/// The pairing material is public connection metadata. It is never sufficient to
/// authenticate a peer; the credential is supplied separately at connect time.
public struct AgentLocalNodePairingMaterial: Equatable, Sendable {
    public let channelProtocol: String
    public let endpointRef: String
    public let credentialRef: String
    public let expectedDeviceID: String
    public let sessionBindingRef: String
    public let pairingID: String
    public let host: String
    public let port: Int
    public let tlsCertificateSHA256: String

    public init(
        channelProtocol: String = AgentLocalNodeChannelCodec.channelProtocol,
        endpointRef: String,
        credentialRef: String,
        expectedDeviceID: String,
        sessionBindingRef: String,
        pairingID: String,
        host: String,
        port: Int,
        tlsCertificateSHA256: String
    ) {
        self.channelProtocol = channelProtocol
        self.endpointRef = endpointRef
        self.credentialRef = credentialRef
        self.expectedDeviceID = expectedDeviceID
        self.sessionBindingRef = sessionBindingRef
        self.pairingID = pairingID
        self.host = host
        self.port = port
        self.tlsCertificateSHA256 = tlsCertificateSHA256
        precondition(Self.isValid(channelProtocol: channelProtocol, endpointRef: endpointRef, credentialRef: credentialRef, expectedDeviceID: expectedDeviceID, sessionBindingRef: sessionBindingRef, pairingID: pairingID, host: host, port: port, pin: tlsCertificateSHA256))
    }

    public func document() -> [String: Any] {
        [
            "channel_protocol": channelProtocol,
            "endpoint_ref": endpointRef,
            "credential_ref": credentialRef,
            "expected_device_id": expectedDeviceID,
            "session_binding_ref": sessionBindingRef,
            "pairing_id": pairingID,
            "host": host,
            "port": port,
            "tls_certificate_sha256": tlsCertificateSHA256,
        ]
    }

    public func canonicalData() throws -> Data {
        try AgentLocalNodeChannelCodec.canonicalJSONData(document())
    }

    public static func parse(jsonData: Data) throws -> Self {
        var scanner = JSONDuplicateKeyScanner(data: jsonData)
        try scanner.validate()
        guard let object = try? JSONSerialization.jsonObject(with: jsonData, options: [.fragmentsAllowed]) as? [String: Any],
              Set(object.keys) == [
                "channel_protocol", "endpoint_ref", "credential_ref", "expected_device_id",
                "session_binding_ref", "pairing_id", "host", "port", "tls_certificate_sha256",
              ],
              let channelProtocol = object["channel_protocol"] as? String,
              let endpointRef = object["endpoint_ref"] as? String,
              let credentialRef = object["credential_ref"] as? String,
              let expectedDeviceID = object["expected_device_id"] as? String,
              let sessionBindingRef = object["session_binding_ref"] as? String,
              let pairingID = object["pairing_id"] as? String,
              let host = object["host"] as? String,
              let port = object["port"] as? Int,
              let pin = object["tls_certificate_sha256"] as? String,
              Self.isValid(channelProtocol: channelProtocol, endpointRef: endpointRef, credentialRef: credentialRef, expectedDeviceID: expectedDeviceID, sessionBindingRef: sessionBindingRef, pairingID: pairingID, host: host, port: port, pin: pin) else {
            throw AgentLocalNodeChannelError.invalidChannelMessage
        }
        return Self(
            channelProtocol: channelProtocol,
            endpointRef: endpointRef,
            credentialRef: credentialRef,
            expectedDeviceID: expectedDeviceID,
            sessionBindingRef: sessionBindingRef,
            pairingID: pairingID,
            host: host,
            port: port,
            tlsCertificateSHA256: pin
        )
    }

    fileprivate static func isValid(
        channelProtocol: String,
        endpointRef: String,
        credentialRef: String,
        expectedDeviceID: String,
        sessionBindingRef: String,
        pairingID: String,
        host: String,
        port: Int,
        pin: String
    ) -> Bool {
        channelProtocol == AgentLocalNodeChannelCodec.channelProtocol
            && AgentLocalNodeChannelCodec.isReference(endpointRef, prefix: "endpoint-ref:")
            && AgentLocalNodeChannelCodec.isReference(credentialRef, prefix: "credential-ref:")
            && AgentLocalNodeChannelCodec.isIdentifier(expectedDeviceID)
            && AgentLocalNodeChannelCodec.isReference(sessionBindingRef, prefix: "session-binding-ref:")
            && AgentLocalNodeChannelCodec.isIdentifier(pairingID)
            && AgentLocalNodeChannelCodec.isHost(host)
            && (1...65_535).contains(port)
            && AgentLocalNodeChannelCodec.isDigest(pin)
    }
}

public enum AgentLocalNodeChannelError: Error, Equatable, Sendable {
    case invalidChannelMessage
    case authenticationFailed
    case bindingFailed
    case sequenceInvalid
    case replay
    case payloadInvalid
    case transportFailed
}

public struct AgentLocalNodeClientHello: Equatable, @unchecked Sendable {
    fileprivate let document: [String: Any]
    public let clientNonce: String

    fileprivate init(document: [String: Any], clientNonce: String) {
        self.document = document
        self.clientNonce = clientNonce
    }

    public static func == (lhs: Self, rhs: Self) -> Bool {
        lhs.clientNonce == rhs.clientNonce
    }
}

public struct AgentLocalNodeServerHello: Equatable, @unchecked Sendable {
    fileprivate let document: [String: Any]
    public let serverNonce: String
    public let sessionID: String
    public let supportedOperations: [String]

    fileprivate init(document: [String: Any], serverNonce: String, sessionID: String, supportedOperations: [String]) {
        self.document = document
        self.serverNonce = serverNonce
        self.sessionID = sessionID
        self.supportedOperations = supportedOperations
    }

    public static func == (lhs: Self, rhs: Self) -> Bool {
        lhs.serverNonce == rhs.serverNonce
            && lhs.sessionID == rhs.sessionID
            && lhs.supportedOperations == rhs.supportedOperations
    }
}

public struct AgentLocalNodeRequestFrame: Equatable, @unchecked Sendable {
    fileprivate let document: [String: Any]
    public let sessionID: String
    public let sequence: Int64
    public let nonce: String
    public let requestID: String
    public let idempotencyRef: String

    fileprivate init(document: [String: Any], sessionID: String, sequence: Int64, nonce: String, requestID: String, idempotencyRef: String) {
        self.document = document
        self.sessionID = sessionID
        self.sequence = sequence
        self.nonce = nonce
        self.requestID = requestID
        self.idempotencyRef = idempotencyRef
    }

    public static func == (lhs: Self, rhs: Self) -> Bool {
        lhs.sessionID == rhs.sessionID
            && lhs.sequence == rhs.sequence
            && lhs.nonce == rhs.nonce
            && lhs.requestID == rhs.requestID
            && lhs.idempotencyRef == rhs.idempotencyRef
    }
}

public struct AgentLocalNodeResponseFrame: Equatable, Sendable {
    public let sessionID: String
    public let sequence: Int64
    public let nonce: String
    public let applicationLine: Data

    public static func == (lhs: Self, rhs: Self) -> Bool {
        lhs.sessionID == rhs.sessionID
            && lhs.sequence == rhs.sequence
            && lhs.nonce == rhs.nonce
            && lhs.applicationLine == rhs.applicationLine
    }
}

public struct AgentLocalNodeCreateEventDraft: Equatable, Sendable {
    public let requestID: String
    public let idempotencyKey: String
    public let content: String
    public let eventType: String
    public let evidenceState: String
    public let factStatus: String
    public let sensitivity: String
    public let now: String
    public let eventTime: String?

    public init(
        requestID: String,
        idempotencyKey: String,
        content: String,
        eventType: String,
        evidenceState: String,
        factStatus: String,
        sensitivity: String,
        now: String,
        eventTime: String? = nil
    ) {
        self.requestID = requestID
        self.idempotencyKey = idempotencyKey
        self.content = content
        self.eventType = eventType
        self.evidenceState = evidenceState
        self.factStatus = factStatus
        self.sensitivity = sensitivity
        self.now = now
        self.eventTime = eventTime
    }
}

/// Swift implementation of `ameme.agent-local-node.channel.v1`.
///
/// This type is intentionally transport-free. It is usable by the iOS app,
/// command-line Smoke target, and a future Network.framework client without
/// duplicating the authentication/framing rules.
public enum AgentLocalNodeChannelCodec {
    public static let channelProtocol = "ameme.agent-local-node.channel.v1"
    public static let applicationProtocol = "ameme.agent-local-node.v1"
    public static let maxChannelLineBytes = 786_432
    public static let maxRequestBytes = 65_536
    public static let maxResponseBytes = 524_288

    private static let identifierPattern = "^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$"
    private static let referencePattern = "^[A-Za-z0-9][A-Za-z0-9._/-]{0,239}$"
    private static let hostPattern = "^[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$"
    private static let noncePattern = "^nonce_[0-9a-f]{64}$"
    private static let digestPattern = "^sha256_[0-9a-f]{64}$"
    private static let proofPattern = "^hmac_[0-9a-f]{64}$"
    private static let idempotencyPattern = "^idem_[0-9a-f]{64}$"

    private static let pairingKeys: Set<String> = [
        "channel_protocol", "endpoint_ref", "credential_ref", "expected_device_id",
        "session_binding_ref", "pairing_id", "host", "port", "tls_certificate_sha256",
    ]
    private static let serverHelloKeys: Set<String> = [
        "channel_protocol", "message_type", "pairing_id", "device_id", "session_binding_ref",
        "tls_certificate_sha256", "client_nonce", "server_nonce", "session_id",
        "supported_operations", "sequence", "proof",
    ]
    private static let requestFrameKeys: Set<String> = [
        "channel_protocol", "message_type", "session_id", "sequence", "nonce", "idempotency_ref",
        "application_protocol", "application_digest", "application_b64", "proof",
    ]
    private static let responseFrameKeys: Set<String> = [
        "channel_protocol", "message_type", "session_id", "sequence", "request_nonce", "nonce",
        "idempotency_ref", "application_protocol", "application_digest", "application_b64", "proof",
    ]

    public static func canonicalJSONData(_ object: Any) throws -> Data {
        guard JSONSerialization.isValidJSONObject(object) else {
            throw AgentLocalNodeChannelError.payloadInvalid
        }
        do {
            let encoded = try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
            // Foundation escapes `/` as `\/`, while the shared contract uses
            // Python/Kotlin's canonical JSON form with an unescaped slash.
            var normalized = Data()
            normalized.reserveCapacity(encoded.count)
            var index = encoded.startIndex
            while index < encoded.endIndex {
                if encoded[index] == 0x5C,
                   encoded.index(after: index) < encoded.endIndex,
                   encoded[encoded.index(after: index)] == 0x2F {
                    normalized.append(0x2F)
                    index = encoded.index(index, offsetBy: 2)
                } else {
                    normalized.append(encoded[index])
                    index = encoded.index(after: index)
                }
            }
            return normalized
        } catch {
            throw AgentLocalNodeChannelError.payloadInvalid
        }
    }

    public static func digest(_ data: Data) -> String {
        "sha256_" + SHA256.hash(data: data).hexString
    }

    public static func deriveIdempotencySlot(rawKey: String, operation: String) -> String {
        let material = "ameme:idempotency:v1:agent-local-node-wire:\(operation)\u{0}\(rawKey)"
        return "idem_" + SHA256.hash(data: Data(material.utf8)).hexString
    }

    /// Builds the only application write currently exposed by the Android
    /// Local Node endpoint and authorizes its exact scope before serialization.
    public static func buildCreateEventRequest(
        draft: AgentLocalNodeCreateEventDraft,
        grant: AgentAccessGrant,
        authorizationDate: Date
    ) throws -> Data {
        guard let space = grant.spaces.first, let dataType = grant.dataTypes.first,
              let purpose = grant.purposes.first else {
            throw AgentLocalNodeChannelError.payloadInvalid
        }
        let request = AgentAccessGrantRequest(
            callerID: grant.callerID,
            grantID: grant.grantID,
            purpose: purpose,
            space: space,
            dataType: dataType,
            operation: "create_event"
        )
        do { try grant.authorize(request, at: authorizationDate) }
        catch { throw AgentLocalNodeChannelError.authenticationFailed }
        guard isIdentifier(draft.requestID), draft.idempotencyKey.count >= 8,
              !draft.content.isEmpty, draft.content.count <= 4_000,
              ["activity", "communication", "decision", "result", "state_change", "milestone", "experience"].contains(draft.eventType),
              ["observed", "user_asserted", "inferred"].contains(draft.evidenceState),
              ["confirmed", "user_asserted", "low_confidence_candidate"].contains(draft.factStatus),
              ["public", "personal", "confidential", "restricted"].contains(draft.sensitivity),
              !draft.now.isEmpty, draft.now.count <= 64,
              draft.eventTime?.count ?? 0 <= 64 else {
            throw AgentLocalNodeChannelError.payloadInvalid
        }
        var payload: [String: Any] = [
            "space": space,
            "memory_type": "event",
            "content": draft.content,
            "event_type": draft.eventType,
            "evidence_state": draft.evidenceState,
            "fact_status": draft.factStatus,
            "sensitivity": draft.sensitivity,
            "data_class": dataType == "event" ? "structured" : dataType,
            "now": draft.now,
        ]
        if let eventTime = draft.eventTime { payload["event_time"] = eventTime }
        let payloadData = try canonicalJSONData(payload)
        let control: [String: Any] = [
            "caller_id": grant.callerID,
            "grant_id": grant.grantID,
            "purpose": purpose,
            "spaces": [space],
            "memory_types": ["event"],
            "operation": "create_event",
            "idempotency_slot": deriveIdempotencySlot(rawKey: draft.idempotencyKey, operation: "create_event"),
            "payload_digest": digest(payloadData),
        ]
        return try canonicalJSONData([
            "protocol_version": applicationProtocol,
            "request_id": draft.requestID,
            "control": control,
            "payload": payload,
        ])
    }

    public static func buildClientHello(
        pairing: AgentLocalNodePairingMaterial,
        secret: Data,
        clientNonce: String
    ) throws -> Data {
        try requireSecret(secret)
        guard isNonce(clientNonce) else { throw AgentLocalNodeChannelError.invalidChannelMessage }
        var document: [String: Any] = [
            "channel_protocol": channelProtocol,
            "message_type": "client_hello",
            "pairing_id": pairing.pairingID,
            "expected_device_id": pairing.expectedDeviceID,
            "session_binding_ref": pairing.sessionBindingRef,
            "tls_certificate_sha256": pairing.tlsCertificateSHA256,
            "client_nonce": clientNonce,
            "sequence": 0,
        ]
        document["proof"] = try proof(document, key: secret, label: "client-hello")
        return try canonicalJSONData(document)
    }

    public static func verifyServerHello(
        _ line: Data,
        clientHello: AgentLocalNodeClientHello,
        pairing: AgentLocalNodePairingMaterial,
        secret: Data
    ) throws -> AgentLocalNodeServerHello {
        try requireSecret(secret)
        let document = try parseObject(line, maximum: maxChannelLineBytes)
        try exact(document, keys: serverHelloKeys)
        guard
            string(document, "channel_protocol") == channelProtocol,
            string(document, "message_type") == "server_hello",
            string(document, "pairing_id") == pairing.pairingID,
            string(document, "device_id") == pairing.expectedDeviceID,
            string(document, "session_binding_ref") == pairing.sessionBindingRef,
            string(document, "tls_certificate_sha256") == pairing.tlsCertificateSHA256,
            string(document, "client_nonce") == clientHello.clientNonce,
            integer(document, "sequence") == 0
        else { throw AgentLocalNodeChannelError.bindingFailed }
        let serverNonce = try requireNonce(string(document, "server_nonce"))
        let sessionID = try requireIdentifier(string(document, "session_id"))
        let operations = try sortedOperations(document["supported_operations"])
        try verifyProof(document, key: secret, label: "server-hello")
        return AgentLocalNodeServerHello(document: document, serverNonce: serverNonce, sessionID: sessionID, supportedOperations: operations)
    }

    public static func makeClientHelloView(_ line: Data, pairing: AgentLocalNodePairingMaterial, secret: Data) throws -> AgentLocalNodeClientHello {
        try requireSecret(secret)
        let document = try parseObject(line, maximum: maxChannelLineBytes)
        let expected: Set<String> = [
            "channel_protocol", "message_type", "pairing_id", "expected_device_id", "session_binding_ref",
            "tls_certificate_sha256", "client_nonce", "sequence", "proof",
        ]
        try exact(document, keys: expected)
        guard string(document, "channel_protocol") == channelProtocol,
              string(document, "message_type") == "client_hello",
              string(document, "pairing_id") == pairing.pairingID,
              string(document, "expected_device_id") == pairing.expectedDeviceID,
              string(document, "session_binding_ref") == pairing.sessionBindingRef,
              string(document, "tls_certificate_sha256") == pairing.tlsCertificateSHA256,
              integer(document, "sequence") == 0 else {
            throw AgentLocalNodeChannelError.bindingFailed
        }
        let nonce = try requireNonce(string(document, "client_nonce"))
        try verifyProof(document, key: secret, label: "client-hello")
        return AgentLocalNodeClientHello(document: document, clientNonce: nonce)
    }

    public static func deriveSessionKey(
        clientHello: AgentLocalNodeClientHello,
        serverHello: AgentLocalNodeServerHello,
        secret: Data
    ) throws -> Data {
        try requireSecret(secret)
        let material: [String: Any] = [
            "channel_protocol": channelProtocol,
            "pairing_id": serverHello.document["pairing_id"] as Any,
            "device_id": serverHello.document["device_id"] as Any,
            "session_binding_ref": serverHello.document["session_binding_ref"] as Any,
            "tls_certificate_sha256": serverHello.document["tls_certificate_sha256"] as Any,
            "client_nonce": clientHello.clientNonce,
            "server_nonce": serverHello.serverNonce,
            "session_id": serverHello.sessionID,
        ]
        let bytes = try canonicalJSONData(material)
        return Data(HMAC<SHA256>.authenticationCode(for: Data("session-key\u{0}".utf8) + bytes, using: SymmetricKey(data: secret)))
    }

    public static func buildRequestFrame(
        applicationLine: Data,
        sessionKey: Data,
        sessionID: String,
        sequence: Int64,
        nonce: String
    ) throws -> (line: Data, frame: AgentLocalNodeRequestFrame) {
        try requireSecret(sessionKey)
        guard applicationLine.count <= maxRequestBytes else { throw AgentLocalNodeChannelError.payloadInvalid }
        let application = try parseObject(applicationLine, maximum: maxRequestBytes)
        let control = try object(application, "control")
        let idempotencyRef = try requireIdempotency(string(control, "idempotency_slot"))
        let requestID = try requireIdentifier(string(application, "request_id"))
        let operation = string(control, "operation")
        guard !operation.isEmpty else { throw AgentLocalNodeChannelError.payloadInvalid }
        let document: [String: Any] = [
            "channel_protocol": channelProtocol,
            "message_type": "request",
            "session_id": try requireIdentifier(sessionID),
            "sequence": try requireSequence(sequence),
            "nonce": try requireNonce(nonce),
            "idempotency_ref": idempotencyRef,
            "application_protocol": applicationProtocol,
            "application_digest": digest(applicationLine),
            "application_b64": applicationLine.base64EncodedString(),
        ]
        var signed = document
        signed["proof"] = try proof(document, key: sessionKey, label: "request-frame")
        return (try canonicalJSONData(signed), AgentLocalNodeRequestFrame(document: signed, sessionID: sessionID, sequence: sequence, nonce: nonce, requestID: requestID, idempotencyRef: idempotencyRef))
    }

    public static func parseResponseFrame(
        _ line: Data,
        sessionKey: Data,
        requestFrame: AgentLocalNodeRequestFrame,
        seenNonces: inout Set<String>
    ) throws -> AgentLocalNodeResponseFrame {
        try requireSecret(sessionKey)
        let document = try parseObject(line, maximum: maxChannelLineBytes)
        try exact(document, keys: responseFrameKeys)
        guard string(document, "channel_protocol") == channelProtocol,
              string(document, "message_type") == "response",
              string(document, "session_id") == requestFrame.sessionID,
              integer(document, "sequence") == requestFrame.sequence,
              string(document, "request_nonce") == requestFrame.nonce,
              string(document, "idempotency_ref") == requestFrame.idempotencyRef,
              string(document, "application_protocol") == applicationProtocol else {
            throw AgentLocalNodeChannelError.bindingFailed
        }
        let responseNonce = try requireNonce(string(document, "nonce"))
        guard responseNonce != requestFrame.nonce, !seenNonces.contains(responseNonce) else {
            throw AgentLocalNodeChannelError.replay
        }
        try verifyProof(document, key: sessionKey, label: "response-frame")
        guard let encoded = document["application_b64"] as? String, let applicationLine = Data(base64Encoded: encoded), !applicationLine.isEmpty, applicationLine.count <= maxResponseBytes else {
            throw AgentLocalNodeChannelError.payloadInvalid
        }
        guard digest(applicationLine) == string(document, "application_digest") else {
            throw AgentLocalNodeChannelError.payloadInvalid
        }
        let application = try parseObject(applicationLine, maximum: maxResponseBytes)
        guard string(application, "protocol_version") == applicationProtocol,
              string(application, "request_id") == requestFrame.requestID,
              ["ok", "error"].contains(string(application, "status")) else {
            throw AgentLocalNodeChannelError.payloadInvalid
        }
        seenNonces.insert(responseNonce)
        return AgentLocalNodeResponseFrame(sessionID: requestFrame.sessionID, sequence: requestFrame.sequence, nonce: responseNonce, applicationLine: applicationLine)
    }

    private static func parseObject(_ data: Data, maximum: Int) throws -> [String: Any] {
        guard !data.isEmpty, data.count <= maximum, !data.contains(0x00), !data.contains(0x0A), !data.contains(0x0D) else {
            throw AgentLocalNodeChannelError.invalidChannelMessage
        }
        var scanner = JSONDuplicateKeyScanner(data: data)
        try scanner.validate()
        let object: Any
        do { object = try JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed]) } catch {
            throw AgentLocalNodeChannelError.invalidChannelMessage
        }
        guard let dictionary = object as? [String: Any] else { throw AgentLocalNodeChannelError.invalidChannelMessage }
        _ = try canonicalJSONData(dictionary)
        return dictionary
    }

    private static func exact(_ document: [String: Any], keys: Set<String>) throws {
        guard Set(document.keys) == keys else { throw AgentLocalNodeChannelError.invalidChannelMessage }
    }

    private static func object(_ document: [String: Any], _ key: String) throws -> [String: Any] {
        guard let value = document[key] as? [String: Any] else { throw AgentLocalNodeChannelError.payloadInvalid }
        return value
    }

    private static func string(_ document: [String: Any], _ key: String) -> String {
        (document[key] as? String) ?? ""
    }

    private static func integer(_ document: [String: Any], _ key: String) -> Int64 {
        (document[key] as? NSNumber)?.int64Value ?? Int64.min
    }

    private static func proof(_ document: [String: Any], key: Data, label: String) throws -> String {
        var unsigned = document
        unsigned.removeValue(forKey: "proof")
        let message = Data(label.utf8) + Data([0]) + (try canonicalJSONData(unsigned))
        return "hmac_" + Data(HMAC<SHA256>.authenticationCode(for: message, using: SymmetricKey(data: key))).hexString
    }

    private static func verifyProof(_ document: [String: Any], key: Data, label: String) throws {
        let supplied = string(document, "proof")
        guard isProof(supplied), let expected = try? proof(document, key: key, label: label), supplied == expected else {
            throw AgentLocalNodeChannelError.authenticationFailed
        }
    }

    private static func requireSecret(_ secret: Data) throws {
        guard secret.count >= 32, secret.count <= 256 else { throw AgentLocalNodeChannelError.authenticationFailed }
    }

    private static func requireIdentifier(_ value: String) throws -> String {
        guard isIdentifier(value) else { throw AgentLocalNodeChannelError.invalidChannelMessage }
        return value
    }

    private static func requireNonce(_ value: String) throws -> String {
        guard isNonce(value) else { throw AgentLocalNodeChannelError.invalidChannelMessage }
        return value
    }

    private static func requireSequence(_ value: Int64) throws -> Int64 {
        guard (1...9_007_199_254_740_991).contains(value) else { throw AgentLocalNodeChannelError.sequenceInvalid }
        return value
    }

    private static func requireIdempotency(_ value: String) throws -> String {
        guard isIdempotency(value) else { throw AgentLocalNodeChannelError.invalidChannelMessage }
        return value
    }

    private static func sortedOperations(_ value: Any?) throws -> [String] {
        guard let values = value as? [String], !values.isEmpty, values == Array(Set(values)).sorted(), values.allSatisfy({ ["get_event", "create_event", "append_revision", "undo_capture", "visible_events", "set_policy_blocked"].contains($0) }) else {
            throw AgentLocalNodeChannelError.invalidChannelMessage
        }
        return values
    }

    fileprivate static func isIdentifier(_ value: String) -> Bool { value.range(of: identifierPattern, options: .regularExpression) != nil }
    fileprivate static func isReference(_ value: String, prefix: String) -> Bool { value.hasPrefix(prefix) && value.dropFirst(prefix.count).range(of: referencePattern, options: .regularExpression) != nil }
    fileprivate static func isHost(_ value: String) -> Bool { !value.isEmpty && value.count <= 253 && value != "0.0.0.0" && value.range(of: hostPattern, options: .regularExpression) != nil }
    fileprivate static func isNonce(_ value: String) -> Bool { value.range(of: noncePattern, options: .regularExpression) != nil }
    fileprivate static func isDigest(_ value: String) -> Bool { value.range(of: digestPattern, options: .regularExpression) != nil }
    private static func isProof(_ value: String) -> Bool { value.range(of: proofPattern, options: .regularExpression) != nil }
    private static func isIdempotency(_ value: String) -> Bool { value.range(of: idempotencyPattern, options: .regularExpression) != nil }
}

/// Foundation's JSON decoder intentionally accepts the last value for a
/// duplicate object key. The wire contract rejects that ambiguity, so perform
/// a small lexical pass before decoding into Foundation objects.
internal struct JSONDuplicateKeyScanner {
    private let bytes: [UInt8]
    private var index = 0

    init(data: Data) { bytes = Array(data) }

    mutating func validate() throws {
        try value()
        whitespace()
        guard index == bytes.count else { throw AgentLocalNodeChannelError.invalidChannelMessage }
    }

    private mutating func value() throws {
        whitespace()
        guard index < bytes.count else { throw AgentLocalNodeChannelError.invalidChannelMessage }
        switch bytes[index] {
        case 0x7B: try object()
        case 0x5B: try array()
        case 0x22: _ = try string()
        case 0x74: try literal(Array("true".utf8))
        case 0x66: try literal(Array("false".utf8))
        case 0x6E: try literal(Array("null".utf8))
        case 0x2D, 0x30...0x39: number()
        default: throw AgentLocalNodeChannelError.invalidChannelMessage
        }
    }

    private mutating func object() throws {
        try consume(0x7B)
        whitespace()
        var keys = Set<String>()
        if consumeIfPresent(0x7D) { return }
        while true {
            whitespace()
            guard index < bytes.count, bytes[index] == 0x22 else { throw AgentLocalNodeChannelError.invalidChannelMessage }
            let key = try string()
            guard keys.insert(key).inserted else { throw AgentLocalNodeChannelError.invalidChannelMessage }
            whitespace()
            try consume(0x3A)
            try value()
            whitespace()
            if consumeIfPresent(0x7D) { return }
            try consume(0x2C)
        }
    }

    private mutating func array() throws {
        try consume(0x5B)
        whitespace()
        if consumeIfPresent(0x5D) { return }
        while true {
            try value()
            whitespace()
            if consumeIfPresent(0x5D) { return }
            try consume(0x2C)
        }
    }

    private mutating func string() throws -> String {
        let start = index
        try consume(0x22)
        while index < bytes.count {
            let byte = bytes[index]
            if byte == 0x22 {
                index += 1
                let token = Data(bytes[start..<index])
                guard let value = try? JSONSerialization.jsonObject(with: token, options: [.fragmentsAllowed]) as? String else {
                    throw AgentLocalNodeChannelError.invalidChannelMessage
                }
                return value
            }
            if byte < 0x20 { throw AgentLocalNodeChannelError.invalidChannelMessage }
            if byte == 0x5C {
                index += 1
                guard index < bytes.count else { throw AgentLocalNodeChannelError.invalidChannelMessage }
                if bytes[index] == 0x75 {
                    guard index + 4 < bytes.count,
                          bytes[(index + 1)...(index + 4)].allSatisfy(Self.isHex) else {
                        throw AgentLocalNodeChannelError.invalidChannelMessage
                    }
                    index += 5
                } else if [0x22, 0x5C, 0x2F, 0x62, 0x66, 0x6E, 0x72, 0x74].contains(bytes[index]) {
                    index += 1
                } else {
                    throw AgentLocalNodeChannelError.invalidChannelMessage
                }
            } else {
                index += 1
            }
        }
        throw AgentLocalNodeChannelError.invalidChannelMessage
    }

    private mutating func literal(_ value: [UInt8]) throws {
        guard bytes[index..<min(bytes.count, index + value.count)].elementsEqual(value) else {
            throw AgentLocalNodeChannelError.invalidChannelMessage
        }
        index += value.count
    }

    private mutating func number() {
        while index < bytes.count && ![0x20, 0x09, 0x0A, 0x0D, 0x2C, 0x5D, 0x7D].contains(bytes[index]) {
            index += 1
        }
    }

    private mutating func whitespace() {
        while index < bytes.count && [0x20, 0x09, 0x0A, 0x0D].contains(bytes[index]) { index += 1 }
    }

    private mutating func consume(_ expected: UInt8) throws {
        guard consumeIfPresent(expected) else { throw AgentLocalNodeChannelError.invalidChannelMessage }
    }

    private mutating func consumeIfPresent(_ expected: UInt8) -> Bool {
        guard index < bytes.count, bytes[index] == expected else { return false }
        index += 1
        return true
    }

    private static func isHex(_ byte: UInt8) -> Bool {
        (0x30...0x39).contains(byte) || (0x41...0x46).contains(byte) || (0x61...0x66).contains(byte)
    }
}

private extension Digest {
    var hexString: String { map { String(format: "%02x", $0) }.joined() }
}

private extension Data {
    var hexString: String { map { String(format: "%02x", $0) }.joined() }
}
