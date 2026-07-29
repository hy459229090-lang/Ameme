import CryptoKit
import Foundation
import XCTest
@testable import AmemeShared

final class AgentPairingBootstrapTests: XCTestCase {
    func testV2EnvelopeAndSignedBootstrapIssueSeparateCredential() throws {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        let envelope = try makeEnvelope(now: now)
        let privateKey = P256.Signing.PrivateKey()
        let built = try AgentPairingBootstrapV2Codec.buildClientHello(
            envelope: envelope,
            clientPrivateKey: privateKey,
            clientNonce: "nonce_" + String(repeating: "1", count: 64)
        )
        let verifiedClient = try AgentPairingBootstrapV2Codec.verifyClientHello(
            built.line,
            envelope: envelope
        )
        XCTAssertEqual(verifiedClient.bootstrapID, envelope.bootstrapID)
        XCTAssertEqual(
            verifiedClient.clientKeyThumbprint,
            AgentLocalNodeChannelCodec.digest(
                privateKey.publicKey.x963Representation
            )
        )

        let issuedSecret = Data(repeating: 0x24, count: 32)
        let issuedSecretText = AgentPairingEnvelope.encodeBase64URL(issuedSecret)
        var serverDocument: [String: Any] = [
            "bootstrap_protocol": AgentPairingBootstrapV2Codec.protocolVersion,
            "message_type": "bootstrap_server_hello",
            "bootstrap_id": envelope.bootstrapID,
            "pairing_id": envelope.pairing.pairingID,
            "device_id": envelope.pairing.expectedDeviceID,
            "tls_certificate_sha256": envelope.pairing.tlsCertificateSHA256,
            "client_key_thumbprint": verifiedClient.clientKeyThumbprint,
            "client_nonce": verifiedClient.clientNonce,
            "server_nonce": "nonce_" + String(repeating: "2", count: 64),
            "credential_id": "cred_qr_" + String(repeating: "c", count: 32),
            "credential_secret": issuedSecretText,
            "credential_expires_at_ms": "1802592000000",
            "sequence": 0,
        ]
        serverDocument["proof"] = try bootstrapProof(
            serverDocument,
            secret: envelope.bootstrapSecret,
            label: "bootstrap-server-hello"
        )
        let serverLine = try AgentLocalNodeChannelCodec.canonicalJSONData(
            serverDocument
        )
        let issued = try AgentPairingBootstrapV2Codec.verifyServerHello(
            serverLine,
            clientHello: built.hello,
            envelope: envelope,
            now: now
        )

        XCTAssertEqual(issued.credentialID, "cred_qr_" + String(repeating: "c", count: 32))
        XCTAssertEqual(issued.channelSecret, Data(issuedSecretText.utf8))
        XCTAssertNotEqual(issued.channelSecret, envelope.bootstrapSecret)

        var tampered = built.line
        tampered[tampered.index(before: tampered.endIndex)] ^= 1
        XCTAssertThrowsError(
            try AgentPairingBootstrapV2Codec.verifyClientHello(
                tampered,
                envelope: envelope
            )
        )
    }

    func testDeviceOnlyCredentialStoreTransitionsPendingToActiveAndClearsExpiredState() throws {
        let store = AgentPairingCredentialStore(
            service: "com.ameme.ios.tests.agent-pairing.\(UUID().uuidString)",
            account: "current"
        )
        defer { try? store.clearAndVerify() }
        let now = Date()
        let envelope = try makeEnvelope(now: now)
        let privateKey = P256.Signing.PrivateKey()
        let thumbprint = AgentLocalNodeChannelCodec.digest(
            privateKey.publicKey.x963Representation
        )

        try store.savePending(
            envelope: envelope,
            clientPrivateKeyRaw: privateKey.rawRepresentation,
            clientKeyThumbprint: thumbprint
        )
        let pending = try XCTUnwrap(
            store.loadPending(pairingID: envelope.pairing.pairingID, now: now)
        )
        XCTAssertEqual(try store.loadPending(now: now), pending)
        XCTAssertEqual(pending.envelope, envelope)
        XCTAssertEqual(pending.clientKeyThumbprint, thumbprint)
        XCTAssertEqual(
            try P256.Signing.PrivateKey(
                rawRepresentation: pending.clientPrivateKeyRaw
            ).publicKey.x963Representation,
            privateKey.publicKey.x963Representation
        )

        let issued = AgentPairingIssuedCredential(
            pairing: envelope.pairing,
            bootstrapID: envelope.bootstrapID,
            credentialID: "cred_qr_" + String(repeating: "d", count: 32),
            channelSecret: Data(
                AgentPairingEnvelope.encodeBase64URL(
                    Data(repeating: 0x35, count: 32)
                ).utf8
            ),
            clientKeyThumbprint: thumbprint,
            expiresAt: now.addingTimeInterval(3_600)
        )
        try store.saveActive(
            issued: issued,
            clientPrivateKeyRaw: privateKey.rawRepresentation
        )
        XCTAssertNil(
            try store.loadPending(
                pairingID: envelope.pairing.pairingID,
                now: now
            )
        )
        let active = try XCTUnwrap(store.loadActive(now: now))
        XCTAssertEqual(active.pairing, envelope.pairing)
        XCTAssertEqual(active.credentialID, issued.credentialID)
        XCTAssertEqual(active.channelSecret, issued.channelSecret)
        XCTAssertEqual(active.clientKeyThumbprint, thumbprint)

        XCTAssertNil(
            try store.loadActive(
                now: issued.expiresAt.addingTimeInterval(1)
            )
        )
        XCTAssertNil(try store.loadActive(now: now))
    }

    private func makeEnvelope(now: Date) throws -> AgentPairingEnvelope {
        try AgentPairingEnvelope(
            pairing: AgentLocalNodePairingMaterial(
                endpointRef: "endpoint-ref:synthetic/android",
                credentialRef: "credential-ref:env/SYNTHETIC_SECRET",
                expectedDeviceID: "device_synthetic_001",
                sessionBindingRef: "session-binding-ref:synthetic/session",
                pairingID: "pair_synthetic_001",
                host: "127.0.0.1",
                port: 44_321,
                tlsCertificateSHA256: "sha256_" + String(repeating: "a", count: 64)
            ),
            bootstrapID: "boot_" + String(repeating: "b", count: 32),
            bootstrapSecret: Data(repeating: 0x42, count: 32),
            expiresAt: now.addingTimeInterval(300),
            pairingExpiresAt: now.addingTimeInterval(30 * 24 * 60 * 60)
        )
    }

    private func bootstrapProof(
        _ document: [String: Any],
        secret: Data,
        label: String
    ) throws -> String {
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
        ).map { String(format: "%02x", $0) }.joined()
    }
}
