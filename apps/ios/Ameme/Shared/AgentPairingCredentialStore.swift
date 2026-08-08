import CryptoKit
import Foundation
import Security

public struct PendingAgentPairingBootstrap: Equatable, Sendable {
    public let envelope: AgentPairingEnvelope
    public let clientPrivateKeyRaw: Data
    public let clientKeyThumbprint: String
}

public struct StoredAgentPairingCredential: Equatable, Sendable {
    public let pairing: AgentLocalNodePairingMaterial
    public let credentialID: String
    public let channelSecret: Data
    public let clientPrivateKeyRaw: Data
    public let clientKeyThumbprint: String
    public let expiresAt: Date
}

public protocol AgentPairingCredentialStoring: Sendable {
    func savePending(
        envelope: AgentPairingEnvelope,
        clientPrivateKeyRaw: Data,
        clientKeyThumbprint: String
    ) throws

    func loadPending(
        pairingID: String,
        now: Date
    ) throws -> PendingAgentPairingBootstrap?

    func loadPending(now: Date) throws -> PendingAgentPairingBootstrap?

    func saveActive(
        issued: AgentPairingIssuedCredential,
        clientPrivateKeyRaw: Data
    ) throws

    func loadActive(now: Date) throws -> StoredAgentPairingCredential?

    func clearAndVerify() throws
}

public enum AgentPairingCredentialStoreError: Error, Equatable {
    case unavailable
    case corrupt
    case invalidState
}

/**
 * Stores one current-install QR pairing credential in device-only Keychain.
 *
 * The record includes no Event content, query, object identifier, Grant payload, or access audit.
 * The pending state is retained only so a committed bootstrap response can be retried after a
 * transport loss; it is removed on expiry, replacement, explicit disconnect, or Space deletion.
 */
public final class AgentPairingCredentialStore:
    @unchecked Sendable,
    AgentPairingCredentialStoring
{
    private let service: String
    private let account: String
    private let lock = NSRecursiveLock()

    public init(
        service: String = "com.ameme.ios.agent-pairing-credential.v2",
        account: String = "current"
    ) {
        self.service = service
        self.account = account
    }

    public func savePending(
        envelope: AgentPairingEnvelope,
        clientPrivateKeyRaw: Data,
        clientKeyThumbprint: String
    ) throws {
        lock.lock()
        defer { lock.unlock() }
        let signingKey = try? P256.Signing.PrivateKey(
            rawRepresentation: clientPrivateKeyRaw
        )
        guard
            clientPrivateKeyRaw.count == 32,
            isDigest(clientKeyThumbprint),
            signingKey?.publicKey.x963Representation.count == 65 else {
            throw AgentPairingCredentialStoreError.invalidState
        }
        let payload = Payload(
            schemaVersion: 2,
            state: .pending,
            pairing: StoredPairing(envelope.pairing),
            bootstrapID: envelope.bootstrapID,
            bootstrapSecret: envelope.bootstrapSecret.base64EncodedString(),
            bootstrapExpiresAtMilliseconds: Self.epochMilliseconds(envelope.expiresAt),
            pairingExpiresAtMilliseconds: Self.epochMilliseconds(
                envelope.pairingExpiresAt
            ),
            credentialID: nil,
            channelSecret: nil,
            clientPrivateKeyRaw: clientPrivateKeyRaw.base64EncodedString(),
            clientKeyThumbprint: clientKeyThumbprint
        )
        try write(payload)
    }

    public func loadPending(
        pairingID: String,
        now: Date
    ) throws -> PendingAgentPairingBootstrap? {
        lock.lock()
        defer { lock.unlock() }
        return try loadPendingRecord(pairingID: pairingID, now: now)
    }

    public func loadPending(
        now: Date
    ) throws -> PendingAgentPairingBootstrap? {
        lock.lock()
        defer { lock.unlock() }
        return try loadPendingRecord(pairingID: nil, now: now)
    }

    private func loadPendingRecord(
        pairingID: String?,
        now: Date
    ) throws -> PendingAgentPairingBootstrap? {
        guard var payload = try read() else { return nil }
        guard payload.schemaVersion == 2 else {
            try clearAndVerify()
            throw AgentPairingCredentialStoreError.corrupt
        }
        guard payload.state == .pending else { return nil }
        if let pairingID, payload.pairing.pairingID != pairingID {
            return nil
        }
        guard
            let bootstrapID = payload.bootstrapID,
            let bootstrapSecretText = payload.bootstrapSecret,
            let bootstrapSecret = Data(base64Encoded: bootstrapSecretText),
            bootstrapSecret.count == 32,
            let bootstrapExpiryMilliseconds =
                payload.bootstrapExpiresAtMilliseconds,
            let pairingExpiryMilliseconds =
                payload.pairingExpiresAtMilliseconds,
            let privateKey = Data(base64Encoded: payload.clientPrivateKeyRaw),
            privateKey.count == 32,
            isDigest(payload.clientKeyThumbprint) else {
            payload.clearSecrets()
            try clearAndVerify()
            throw AgentPairingCredentialStoreError.corrupt
        }
        let expiresAt = Self.date(bootstrapExpiryMilliseconds)
        let pairingExpiresAt = Self.date(pairingExpiryMilliseconds)
        guard expiresAt > now, pairingExpiresAt >= expiresAt else {
            payload.clearSecrets()
            try clearAndVerify()
            return nil
        }
        let envelope: AgentPairingEnvelope
        do {
            envelope = try AgentPairingEnvelope(
                pairing: payload.pairing.value(),
                bootstrapID: bootstrapID,
                bootstrapSecret: bootstrapSecret,
                expiresAt: expiresAt,
                pairingExpiresAt: pairingExpiresAt
            )
            let signingKey = try P256.Signing.PrivateKey(
                rawRepresentation: privateKey
            )
            guard
                AgentLocalNodeChannelCodec.digest(
                    signingKey.publicKey.x963Representation
                ) == payload.clientKeyThumbprint else {
                throw AgentPairingCredentialStoreError.corrupt
            }
        } catch {
            payload.clearSecrets()
            try clearAndVerify()
            throw AgentPairingCredentialStoreError.corrupt
        }
        payload.clearSecrets()
        return PendingAgentPairingBootstrap(
            envelope: envelope,
            clientPrivateKeyRaw: privateKey,
            clientKeyThumbprint: payload.clientKeyThumbprint
        )
    }

    public func saveActive(
        issued: AgentPairingIssuedCredential,
        clientPrivateKeyRaw: Data
    ) throws {
        lock.lock()
        defer { lock.unlock() }
        guard
            clientPrivateKeyRaw.count == 32,
            issued.channelSecret.count >= 32,
            issued.channelSecret.count <= 256,
            isDigest(issued.clientKeyThumbprint),
            issued.expiresAt > .now else {
            throw AgentPairingCredentialStoreError.invalidState
        }
        let key = try P256.Signing.PrivateKey(
            rawRepresentation: clientPrivateKeyRaw
        )
        guard
            AgentLocalNodeChannelCodec.digest(
                key.publicKey.x963Representation
            ) == issued.clientKeyThumbprint else {
            throw AgentPairingCredentialStoreError.invalidState
        }
        let payload = Payload(
            schemaVersion: 2,
            state: .active,
            pairing: StoredPairing(issued.pairing),
            bootstrapID: nil,
            bootstrapSecret: nil,
            bootstrapExpiresAtMilliseconds: nil,
            pairingExpiresAtMilliseconds: Self.epochMilliseconds(
                issued.expiresAt
            ),
            credentialID: issued.credentialID,
            channelSecret: issued.channelSecret.base64EncodedString(),
            clientPrivateKeyRaw: clientPrivateKeyRaw.base64EncodedString(),
            clientKeyThumbprint: issued.clientKeyThumbprint
        )
        try write(payload)
    }

    public func loadActive(now: Date = .now) throws -> StoredAgentPairingCredential? {
        lock.lock()
        defer { lock.unlock() }
        guard var payload = try read() else { return nil }
        guard payload.schemaVersion == 2 else {
            try clearAndVerify()
            throw AgentPairingCredentialStoreError.corrupt
        }
        guard payload.state == .active else { return nil }
        guard
            payload.bootstrapID == nil,
            payload.bootstrapSecret == nil,
            payload.bootstrapExpiresAtMilliseconds == nil,
            let credentialID = payload.credentialID,
            isIdentifier(credentialID),
            let channelSecretText = payload.channelSecret,
            let channelSecret = Data(base64Encoded: channelSecretText),
            channelSecret.count >= 32,
            channelSecret.count <= 256,
            let expiryMilliseconds = payload.pairingExpiresAtMilliseconds,
            let privateKey = Data(base64Encoded: payload.clientPrivateKeyRaw),
            privateKey.count == 32,
            isDigest(payload.clientKeyThumbprint) else {
            payload.clearSecrets()
            try clearAndVerify()
            throw AgentPairingCredentialStoreError.corrupt
        }
        let expiresAt = Self.date(expiryMilliseconds)
        guard expiresAt > now else {
            payload.clearSecrets()
            try clearAndVerify()
            return nil
        }
        do {
            let signingKey = try P256.Signing.PrivateKey(
                rawRepresentation: privateKey
            )
            guard
                AgentLocalNodeChannelCodec.digest(
                    signingKey.publicKey.x963Representation
                ) == payload.clientKeyThumbprint else {
                throw AgentPairingCredentialStoreError.corrupt
            }
            let pairing = try payload.pairing.value()
            payload.clearSecrets()
            return StoredAgentPairingCredential(
                pairing: pairing,
                credentialID: credentialID,
                channelSecret: channelSecret,
                clientPrivateKeyRaw: privateKey,
                clientKeyThumbprint: payload.clientKeyThumbprint,
                expiresAt: expiresAt
            )
        } catch {
            payload.clearSecrets()
            try clearAndVerify()
            throw AgentPairingCredentialStoreError.corrupt
        }
    }

    public func clearAndVerify() throws {
        lock.lock()
        defer { lock.unlock() }
        let status = SecItemDelete(baseQuery() as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw AgentPairingCredentialStoreError.unavailable
        }
        guard try read() == nil else {
            throw AgentPairingCredentialStoreError.unavailable
        }
    }

    private func read() throws -> Payload? {
        var query = baseQuery()
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else {
            throw AgentPairingCredentialStoreError.unavailable
        }
        do {
            return try JSONDecoder().decode(Payload.self, from: data)
        } catch {
            throw AgentPairingCredentialStoreError.corrupt
        }
    }

    private func write(_ payload: Payload) throws {
        let data: Data
        do {
            data = try JSONEncoder().encode(payload)
        } catch {
            throw AgentPairingCredentialStoreError.invalidState
        }
        var attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String:
                kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
        ]
        let updateStatus = SecItemUpdate(
            baseQuery() as CFDictionary,
            attributes as CFDictionary
        )
        if updateStatus == errSecSuccess { return }
        guard updateStatus == errSecItemNotFound else {
            throw AgentPairingCredentialStoreError.unavailable
        }
        attributes.merge(baseQuery()) { current, _ in current }
        let addStatus = SecItemAdd(attributes as CFDictionary, nil)
        guard addStatus == errSecSuccess else {
            throw AgentPairingCredentialStoreError.unavailable
        }
    }

    private func baseQuery() -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }

    private static func epochMilliseconds(_ date: Date) -> Int64 {
        Int64((date.timeIntervalSince1970 * 1_000).rounded(.down))
    }

    private static func date(_ milliseconds: Int64) -> Date {
        Date(timeIntervalSince1970: Double(milliseconds) / 1_000)
    }

    private func isDigest(_ value: String) -> Bool {
        value.range(
            of: "^sha256_[0-9a-f]{64}$",
            options: .regularExpression
        ) != nil
    }

    private func isIdentifier(_ value: String) -> Bool {
        value.range(
            of: "^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$",
            options: .regularExpression
        ) != nil
    }
}

private struct Payload: Codable {
    enum State: String, Codable {
        case pending
        case active
    }

    let schemaVersion: Int
    let state: State
    let pairing: StoredPairing
    let bootstrapID: String?
    var bootstrapSecret: String?
    let bootstrapExpiresAtMilliseconds: Int64?
    let pairingExpiresAtMilliseconds: Int64?
    let credentialID: String?
    var channelSecret: String?
    var clientPrivateKeyRaw: String
    let clientKeyThumbprint: String

    mutating func clearSecrets() {
        bootstrapSecret = nil
        channelSecret = nil
        clientPrivateKeyRaw = ""
    }
}

private struct StoredPairing: Codable {
    let channelProtocol: String
    let endpointRef: String
    let credentialRef: String
    let expectedDeviceID: String
    let sessionBindingRef: String
    let pairingID: String
    let host: String
    let port: Int
    let tlsCertificateSHA256: String

    init(_ value: AgentLocalNodePairingMaterial) {
        channelProtocol = value.channelProtocol
        endpointRef = value.endpointRef
        credentialRef = value.credentialRef
        expectedDeviceID = value.expectedDeviceID
        sessionBindingRef = value.sessionBindingRef
        pairingID = value.pairingID
        host = value.host
        port = value.port
        tlsCertificateSHA256 = value.tlsCertificateSHA256
    }

    func value() throws -> AgentLocalNodePairingMaterial {
        let document: [String: Any] = [
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
        return try AgentLocalNodePairingMaterial.parse(
            jsonData: AgentLocalNodeChannelCodec.canonicalJSONData(document)
        )
    }
}
