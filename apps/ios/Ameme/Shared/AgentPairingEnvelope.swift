import Foundation

/// A short-lived, user-mediated pairing payload for a future camera/deep-link
/// importer. The one-time secret is intentionally inside the QR payload; it is
/// never persisted by `AgentExperienceStore` or emitted in diagnostics.
public struct AgentPairingEnvelope: Equatable, Sendable {
    public static let prefix = "ameme-pairing-v1:"
    public static let maxPayloadCharacters = 16_384
    public static let maxLifetime: TimeInterval = 10 * 60
    public static let maxPairingLifetime: TimeInterval = 31 * 24 * 60 * 60
    private static let maxLifetimeMilliseconds: Int64 = 10 * 60 * 1_000
    private static let maxPairingLifetimeMilliseconds: Int64 = 31 * 24 * 60 * 60 * 1_000

    public let pairing: AgentLocalNodePairingMaterial
    public let secret: Data
    public let expiresAt: Date
    public let pairingExpiresAt: Date

    /// Channel HMAC credentials use the same printable base64url value that
    /// Android stores in Keystore and the developer Host receives by reference.
    /// `secret` remains the decoded 32-byte entropy for validation/round-trip.
    public var channelSecret: Data {
        Data(Self.encodeBase64URL(secret).utf8)
    }

    public init(
        pairing: AgentLocalNodePairingMaterial,
        secret: Data,
        expiresAt: Date,
        pairingExpiresAt: Date
    ) throws {
        guard secret.count == 32 else { throw AgentPairingEnvelopeError.invalidSecret }
        guard
            expiresAt.timeIntervalSince1970.isFinite,
            expiresAt.timeIntervalSince1970 > 0,
            pairingExpiresAt.timeIntervalSince1970.isFinite,
            pairingExpiresAt >= expiresAt else {
            throw AgentPairingEnvelopeError.invalidFormat
        }
        self.pairing = pairing
        self.secret = secret
        self.expiresAt = expiresAt
        self.pairingExpiresAt = pairingExpiresAt
    }

    public func encodedPayload() throws -> String {
        guard
            let expiresAtMilliseconds = Self.epochMilliseconds(expiresAt),
            let pairingExpiresAtMilliseconds = Self.epochMilliseconds(pairingExpiresAt) else {
            throw AgentPairingEnvelopeError.invalidFormat
        }
        let object: [String: Any] = [
            "envelope_version": 1,
            "expires_at_ms": String(expiresAtMilliseconds),
            "pairing": pairing.document(),
            "pairing_expires_at_ms": String(pairingExpiresAtMilliseconds),
            "secret": Self.encodeBase64URL(secret),
        ]
        let data = try AgentLocalNodeChannelCodec.canonicalJSONData(object)
        let payload = Self.prefix + Self.encodeBase64URL(data)
        guard payload.count <= Self.maxPayloadCharacters else { throw AgentPairingEnvelopeError.invalidFormat }
        return payload
    }

    public static func parse(_ payload: String, now: Date = .now) throws -> Self {
        guard payload.hasPrefix(prefix), payload.count <= maxPayloadCharacters else {
            throw AgentPairingEnvelopeError.invalidFormat
        }
        let encoded = String(payload.dropFirst(prefix.count))
        guard let jsonData = decodeBase64URL(encoded) else { throw AgentPairingEnvelopeError.invalidFormat }
        var scanner = JSONDuplicateKeyScanner(data: jsonData)
        do { try scanner.validate() } catch { throw AgentPairingEnvelopeError.invalidFormat }
        guard
            let object = try? JSONSerialization.jsonObject(with: jsonData, options: [.fragmentsAllowed]) as? [String: Any],
            Set(object.keys) == ["envelope_version", "expires_at_ms", "pairing", "pairing_expires_at_ms", "secret"],
            let version = object["envelope_version"] as? Int,
            version == 1,
            let expiryText = object["expires_at_ms"] as? String,
            isUnsignedDecimal(expiryText),
            let expiryMilliseconds = Int64(expiryText),
            let pairingExpiryText = object["pairing_expires_at_ms"] as? String,
            isUnsignedDecimal(pairingExpiryText),
            let pairingExpiryMilliseconds = Int64(pairingExpiryText),
            let pairingObject = object["pairing"] as? [String: Any],
            let secretText = object["secret"] as? String,
            let secret = decodeBase64URL(secretText),
            secret.count == 32 else {
            throw AgentPairingEnvelopeError.invalidFormat
        }
        guard
            let canonicalData = try? AgentLocalNodeChannelCodec.canonicalJSONData(object),
            canonicalData == jsonData else {
            throw AgentPairingEnvelopeError.invalidFormat
        }
        let pairingData: Data
        do { pairingData = try AgentLocalNodeChannelCodec.canonicalJSONData(pairingObject) }
        catch { throw AgentPairingEnvelopeError.invalidFormat }
        let pairing: AgentLocalNodePairingMaterial
        do { pairing = try AgentLocalNodePairingMaterial.parse(jsonData: pairingData) }
        catch { throw AgentPairingEnvelopeError.invalidFormat }
        let expiresAt = Date(timeIntervalSince1970: Double(expiryMilliseconds) / 1_000)
        guard let nowMilliseconds = epochMilliseconds(now) else {
            throw AgentPairingEnvelopeError.invalidFormat
        }
        guard expiryMilliseconds > nowMilliseconds else { throw AgentPairingEnvelopeError.expired }
        guard expiryMilliseconds - nowMilliseconds <= maxLifetimeMilliseconds else {
            throw AgentPairingEnvelopeError.lifetimeExceeded
        }
        guard
            pairingExpiryMilliseconds >= expiryMilliseconds,
            pairingExpiryMilliseconds - nowMilliseconds <= maxPairingLifetimeMilliseconds else {
            throw AgentPairingEnvelopeError.invalidFormat
        }
        return try Self(
            pairing: pairing,
            secret: secret,
            expiresAt: expiresAt,
            pairingExpiresAt: Date(timeIntervalSince1970: Double(pairingExpiryMilliseconds) / 1_000)
        )
    }

    private static func encodeBase64URL(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    private static func decodeBase64URL(_ value: String) -> Data? {
        guard !value.isEmpty,
              value.utf8.allSatisfy({ (0x41...0x5A).contains($0) || (0x61...0x7A).contains($0) || (0x30...0x39).contains($0) || $0 == 0x2D || $0 == 0x5F }),
              value.count % 4 != 1 else { return nil }
        var standard = value.replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        let remainder = standard.count % 4
        if remainder != 0 { standard.append(String(repeating: "=", count: 4 - remainder)) }
        return Data(base64Encoded: standard)
    }

    private static func isUnsignedDecimal(_ value: String) -> Bool {
        !value.isEmpty && value.utf8.allSatisfy { (0x30...0x39).contains($0) }
    }

    private static func epochMilliseconds(_ date: Date) -> Int64? {
        let value = date.timeIntervalSince1970 * 1_000
        guard value.isFinite, value > 0, value < Double(Int64.max) else { return nil }
        return Int64(value.rounded(.down))
    }
}

public enum AgentPairingEnvelopeError: Error, Equatable, Sendable {
    case invalidFormat
    case invalidSecret
    case expired
    case lifetimeExceeded
}
