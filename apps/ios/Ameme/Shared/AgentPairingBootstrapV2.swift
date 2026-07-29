import CryptoKit
import Foundation

public struct AgentPairingBootstrapClientHello: @unchecked Sendable {
    fileprivate let document: [String: Any]
    public let bootstrapID: String
    public let pairingID: String
    public let clientNonce: String
    public let clientKeyThumbprint: String
    public let clientPublicKeyX963: Data

    public static func == (
        lhs: AgentPairingBootstrapClientHello,
        rhs: AgentPairingBootstrapClientHello
    ) -> Bool {
        lhs.bootstrapID == rhs.bootstrapID
            && lhs.pairingID == rhs.pairingID
            && lhs.clientNonce == rhs.clientNonce
            && lhs.clientKeyThumbprint == rhs.clientKeyThumbprint
            && lhs.clientPublicKeyX963 == rhs.clientPublicKeyX963
    }
}

public struct AgentPairingIssuedCredential: Equatable, Sendable {
    public let pairing: AgentLocalNodePairingMaterial
    public let bootstrapID: String
    public let credentialID: String
    public let channelSecret: Data
    public let clientKeyThumbprint: String
    public let expiresAt: Date

    public init(
        pairing: AgentLocalNodePairingMaterial,
        bootstrapID: String,
        credentialID: String,
        channelSecret: Data,
        clientKeyThumbprint: String,
        expiresAt: Date
    ) {
        precondition(channelSecret.count >= 32 && channelSecret.count <= 256)
        self.pairing = pairing
        self.bootstrapID = bootstrapID
        self.credentialID = credentialID
        self.channelSecret = channelSecret
        self.clientKeyThumbprint = clientKeyThumbprint
        self.expiresAt = expiresAt
    }
}

public enum AgentPairingBootstrapV2Codec {
    public static let protocolVersion = "ameme.agent-pairing-bootstrap.v2"
    public static let keyAlgorithm = "p256-sha256"
    public static let maxLineBytes = 16_384

    private static let clientHelloKeys: Set<String> = [
        "bootstrap_protocol",
        "message_type",
        "bootstrap_id",
        "pairing_id",
        "expected_device_id",
        "tls_certificate_sha256",
        "client_key_algorithm",
        "client_public_key_b64",
        "client_key_thumbprint",
        "client_nonce",
        "sequence",
        "possession_signature_b64",
        "proof",
    ]
    private static let serverHelloKeys: Set<String> = [
        "bootstrap_protocol",
        "message_type",
        "bootstrap_id",
        "pairing_id",
        "device_id",
        "tls_certificate_sha256",
        "client_key_thumbprint",
        "client_nonce",
        "server_nonce",
        "credential_id",
        "credential_secret",
        "credential_expires_at_ms",
        "sequence",
        "proof",
    ]

    public static func buildClientHello(
        envelope: AgentPairingEnvelope,
        clientPrivateKey: P256.Signing.PrivateKey,
        clientNonce: String
    ) throws -> (line: Data, hello: AgentPairingBootstrapClientHello) {
        guard isNonce(clientNonce) else {
            throw AgentLocalNodeChannelError.invalidChannelMessage
        }
        let publicKey = clientPrivateKey.publicKey.x963Representation
        let core = try clientCore(
            envelope: envelope,
            clientPublicKeyX963: publicKey,
            clientNonce: clientNonce
        )
        let possession = Data("client-key-possession\u{0}".utf8)
            + (try AgentLocalNodeChannelCodec.canonicalJSONData(core))
        let signature = try clientPrivateKey.signature(for: possession).derRepresentation
        var document = core
        document["possession_signature_b64"] = AgentPairingEnvelope.encodeBase64URL(signature)
        document["proof"] = try proof(
            document,
            secret: envelope.bootstrapSecret,
            label: "bootstrap-client-hello"
        )
        let line = try AgentLocalNodeChannelCodec.canonicalJSONData(document)
        return (
            line,
            AgentPairingBootstrapClientHello(
                document: document,
                bootstrapID: envelope.bootstrapID,
                pairingID: envelope.pairing.pairingID,
                clientNonce: clientNonce,
                clientKeyThumbprint: AgentLocalNodeChannelCodec.digest(publicKey),
                clientPublicKeyX963: publicKey
            )
        )
    }

    public static func verifyClientHello(
        _ line: Data,
        envelope: AgentPairingEnvelope
    ) throws -> AgentPairingBootstrapClientHello {
        let document = try parseObject(line)
        try exact(document, keys: clientHelloKeys)
        guard
            string(document, "bootstrap_protocol") == protocolVersion,
            string(document, "message_type") == "bootstrap_client_hello",
            string(document, "bootstrap_id") == envelope.bootstrapID,
            string(document, "pairing_id") == envelope.pairing.pairingID,
            string(document, "expected_device_id") == envelope.pairing.expectedDeviceID,
            string(document, "tls_certificate_sha256") == envelope.pairing.tlsCertificateSHA256,
            string(document, "client_key_algorithm") == keyAlgorithm,
            integer(document, "sequence") == 0,
            isNonce(string(document, "client_nonce")),
            let publicKey = AgentPairingEnvelope.decodeBase64URL(
                string(document, "client_public_key_b64")
            ),
            publicKey.count == 65,
            publicKey.first == 0x04,
            string(document, "client_key_thumbprint") ==
                AgentLocalNodeChannelCodec.digest(publicKey),
            let signatureData = AgentPairingEnvelope.decodeBase64URL(
                string(document, "possession_signature_b64")
            ),
            (64...80).contains(signatureData.count)
        else {
            throw AgentLocalNodeChannelError.bindingFailed
        }
        try verifyProof(
            document,
            secret: envelope.bootstrapSecret,
            label: "bootstrap-client-hello"
        )
        var core = document
        core.removeValue(forKey: "proof")
        core.removeValue(forKey: "possession_signature_b64")
        let possession = Data("client-key-possession\u{0}".utf8)
            + (try AgentLocalNodeChannelCodec.canonicalJSONData(core))
        let publicSigningKey: P256.Signing.PublicKey
        let signature: P256.Signing.ECDSASignature
        do {
            publicSigningKey = try P256.Signing.PublicKey(x963Representation: publicKey)
            signature = try P256.Signing.ECDSASignature(derRepresentation: signatureData)
        } catch {
            throw AgentLocalNodeChannelError.authenticationFailed
        }
        guard publicSigningKey.isValidSignature(signature, for: possession) else {
            throw AgentLocalNodeChannelError.authenticationFailed
        }
        return AgentPairingBootstrapClientHello(
            document: document,
            bootstrapID: envelope.bootstrapID,
            pairingID: envelope.pairing.pairingID,
            clientNonce: string(document, "client_nonce"),
            clientKeyThumbprint: string(document, "client_key_thumbprint"),
            clientPublicKeyX963: publicKey
        )
    }

    public static func verifyServerHello(
        _ line: Data,
        clientHello: AgentPairingBootstrapClientHello,
        envelope: AgentPairingEnvelope,
        now: Date = .now
    ) throws -> AgentPairingIssuedCredential {
        let document = try parseObject(line)
        try exact(document, keys: serverHelloKeys)
        guard
            string(document, "bootstrap_protocol") == protocolVersion,
            string(document, "message_type") == "bootstrap_server_hello",
            string(document, "bootstrap_id") == clientHello.bootstrapID,
            string(document, "pairing_id") == envelope.pairing.pairingID,
            string(document, "device_id") == envelope.pairing.expectedDeviceID,
            string(document, "tls_certificate_sha256") == envelope.pairing.tlsCertificateSHA256,
            string(document, "client_key_thumbprint") == clientHello.clientKeyThumbprint,
            string(document, "client_nonce") == clientHello.clientNonce,
            isNonce(string(document, "server_nonce")),
            isIdentifier(string(document, "credential_id")),
            integer(document, "sequence") == 0,
            let credentialEntropy = AgentPairingEnvelope.decodeBase64URL(
                string(document, "credential_secret")
            ),
            credentialEntropy.count == 32,
            let expiryMilliseconds = unsignedMilliseconds(
                string(document, "credential_expires_at_ms")
            )
        else {
            throw AgentLocalNodeChannelError.bindingFailed
        }
        try verifyProof(
            document,
            secret: envelope.bootstrapSecret,
            label: "bootstrap-server-hello"
        )
        let expiresAt = Date(
            timeIntervalSince1970: Double(expiryMilliseconds) / 1_000
        )
        guard
            expiresAt > now,
            expiresAt <= envelope.pairingExpiresAt else {
            throw AgentLocalNodeChannelError.bindingFailed
        }
        return AgentPairingIssuedCredential(
            pairing: envelope.pairing,
            bootstrapID: envelope.bootstrapID,
            credentialID: string(document, "credential_id"),
            // The frozen application channel uses the printable base64url value as its HMAC key.
            channelSecret: Data(string(document, "credential_secret").utf8),
            clientKeyThumbprint: clientHello.clientKeyThumbprint,
            expiresAt: expiresAt
        )
    }

    private static func clientCore(
        envelope: AgentPairingEnvelope,
        clientPublicKeyX963: Data,
        clientNonce: String
    ) throws -> [String: Any] {
        guard
            clientPublicKeyX963.count == 65,
            clientPublicKeyX963.first == 0x04,
            isNonce(clientNonce) else {
            throw AgentLocalNodeChannelError.invalidChannelMessage
        }
        return [
            "bootstrap_protocol": protocolVersion,
            "message_type": "bootstrap_client_hello",
            "bootstrap_id": envelope.bootstrapID,
            "pairing_id": envelope.pairing.pairingID,
            "expected_device_id": envelope.pairing.expectedDeviceID,
            "tls_certificate_sha256": envelope.pairing.tlsCertificateSHA256,
            "client_key_algorithm": keyAlgorithm,
            "client_public_key_b64": AgentPairingEnvelope.encodeBase64URL(
                clientPublicKeyX963
            ),
            "client_key_thumbprint": AgentLocalNodeChannelCodec.digest(
                clientPublicKeyX963
            ),
            "client_nonce": clientNonce,
            "sequence": 0,
        ]
    }

    private static func parseObject(_ line: Data) throws -> [String: Any] {
        guard !line.isEmpty, line.count <= maxLineBytes else {
            throw AgentLocalNodeChannelError.invalidChannelMessage
        }
        var scanner = JSONDuplicateKeyScanner(data: line)
        do {
            try scanner.validate()
        } catch {
            throw AgentLocalNodeChannelError.invalidChannelMessage
        }
        guard
            let document = try? JSONSerialization.jsonObject(
                with: line,
                options: [.fragmentsAllowed]
            ) as? [String: Any],
            let canonical = try? AgentLocalNodeChannelCodec.canonicalJSONData(document),
            canonical == line else {
            throw AgentLocalNodeChannelError.invalidChannelMessage
        }
        return document
    }

    private static func exact(_ document: [String: Any], keys: Set<String>) throws {
        guard Set(document.keys) == keys else {
            throw AgentLocalNodeChannelError.invalidChannelMessage
        }
    }

    private static func proof(
        _ document: [String: Any],
        secret: Data,
        label: String
    ) throws -> String {
        guard secret.count == 32 else {
            throw AgentLocalNodeChannelError.authenticationFailed
        }
        var unsigned = document
        unsigned.removeValue(forKey: "proof")
        let message = Data(label.utf8)
            + Data([0])
            + (try AgentLocalNodeChannelCodec.canonicalJSONData(unsigned))
        return "hmac_" + Data(
            HMAC<SHA256>.authenticationCode(
                for: message,
                using: SymmetricKey(data: secret)
            )
        ).bootstrapHexString
    }

    private static func verifyProof(
        _ document: [String: Any],
        secret: Data,
        label: String
    ) throws {
        let supplied = string(document, "proof")
        let expected = try proof(document, secret: secret, label: label)
        guard
            supplied.range(
                of: "^hmac_[0-9a-f]{64}$",
                options: .regularExpression
            ) != nil,
            constantTimeEqual(Data(supplied.utf8), Data(expected.utf8)) else {
            throw AgentLocalNodeChannelError.authenticationFailed
        }
    }

    private static func constantTimeEqual(_ lhs: Data, _ rhs: Data) -> Bool {
        guard lhs.count == rhs.count else { return false }
        var difference: UInt8 = 0
        for index in lhs.indices {
            difference |= lhs[index] ^ rhs[index]
        }
        return difference == 0
    }

    private static func string(_ object: [String: Any], _ key: String) -> String {
        object[key] as? String ?? ""
    }

    private static func integer(_ object: [String: Any], _ key: String) -> Int64? {
        guard let number = object[key] as? NSNumber,
              CFGetTypeID(number) != CFBooleanGetTypeID() else {
            return nil
        }
        let value = number.int64Value
        return number.doubleValue == Double(value) ? value : nil
    }

    private static func unsignedMilliseconds(_ value: String) -> Int64? {
        guard
            !value.isEmpty,
            value.utf8.allSatisfy({ (0x30...0x39).contains($0) }) else {
            return nil
        }
        return Int64(value)
    }

    private static func isIdentifier(_ value: String) -> Bool {
        value.range(
            of: "^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$",
            options: .regularExpression
        ) != nil
    }

    private static func isNonce(_ value: String) -> Bool {
        value.range(of: "^nonce_[0-9a-f]{64}$", options: .regularExpression) != nil
    }
}

private extension Data {
    var bootstrapHexString: String {
        map { String(format: "%02x", $0) }.joined()
    }
}
