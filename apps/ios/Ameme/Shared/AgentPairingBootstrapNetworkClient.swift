import CryptoKit
import Foundation
import Network
import Security

public protocol AgentPairingBootstrapProvisioning: Sendable {
    func provision(
        envelope: AgentPairingEnvelope,
        clientPrivateKey: P256.Signing.PrivateKey
    ) async throws -> AgentPairingIssuedCredential
}

/// Executes only the TLS-pinned bootstrap exchange. Application operations start on a fresh
/// connection using the separately issued credential.
public actor AgentPairingBootstrapNetworkClient:
    AgentPairingBootstrapProvisioning
{
    private let timeout: TimeInterval
    private let queue = DispatchQueue(
        label: "com.ameme.agent-pairing-bootstrap-v2"
    )

    public init(timeout: TimeInterval = 5) {
        precondition((0.1...5).contains(timeout))
        self.timeout = timeout
    }

    public func provision(
        envelope: AgentPairingEnvelope,
        clientPrivateKey: P256.Signing.PrivateKey
    ) async throws -> AgentPairingIssuedCredential {
        guard
            envelope.expiresAt > .now,
            envelope.pairingExpiresAt > .now else {
            throw AgentLocalNodeChannelError.authenticationFailed
        }
        let connection = makeConnection(pairing: envelope.pairing)
        do {
            try await waitUntilReady(connection)
            let built = try AgentPairingBootstrapV2Codec.buildClientHello(
                envelope: envelope,
                clientPrivateKey: clientPrivateKey,
                clientNonce: Self.newNonce()
            )
            try await sendLine(built.line, on: connection)
            let serverLine = try await receiveLine(
                from: connection,
                maximum: AgentPairingBootstrapV2Codec.maxLineBytes
            )
            let issued = try AgentPairingBootstrapV2Codec.verifyServerHello(
                serverLine,
                clientHello: built.hello,
                envelope: envelope,
                now: .now
            )
            connection.cancel()
            return issued
        } catch {
            connection.cancel()
            if let channelError = error as? AgentLocalNodeChannelError {
                throw channelError
            }
            throw AgentLocalNodeChannelError.transportFailed
        }
    }

    private func makeConnection(
        pairing: AgentLocalNodePairingMaterial
    ) -> NWConnection {
        let tls = NWProtocolTLS.Options()
        sec_protocol_options_set_min_tls_protocol_version(
            tls.securityProtocolOptions,
            .TLSv13
        )
        sec_protocol_options_set_max_tls_protocol_version(
            tls.securityProtocolOptions,
            .TLSv13
        )
        let expectedPin = pairing.tlsCertificateSHA256
        sec_protocol_options_set_verify_block(
            tls.securityProtocolOptions,
            { _, trust, complete in
                let secTrust = sec_trust_copy_ref(trust).takeRetainedValue()
                guard
                    let certificateChain = SecTrustCopyCertificateChain(
                        secTrust
                    ) as? [SecCertificate],
                    let certificate = certificateChain.first else {
                    complete(false)
                    return
                }
                let certificateData = SecCertificateCopyData(
                    certificate
                ) as Data
                complete(
                    AgentLocalNodeChannelCodec.digest(certificateData)
                        == expectedPin
                )
            },
            queue
        )
        let parameters = NWParameters(tls: tls)
        parameters.allowLocalEndpointReuse = false
        return NWConnection(
            host: NWEndpoint.Host(pairing.host),
            port: NWEndpoint.Port(rawValue: UInt16(pairing.port))!,
            using: parameters
        )
    }

    private func waitUntilReady(_ connection: NWConnection) async throws {
        try await withTaskCancellationHandler(operation: {
            try await withCheckedThrowingContinuation { continuation in
                let gate = PairingBootstrapContinuationGate()
                connection.stateUpdateHandler = { state in
                    switch state {
                    case .ready:
                        gate.resume { continuation.resume() }
                    case .failed, .cancelled:
                        gate.resume {
                            continuation.resume(
                                throwing:
                                    AgentLocalNodeChannelError.transportFailed
                            )
                        }
                    default:
                        break
                    }
                }
                connection.start(queue: queue)
                DispatchQueue.global().asyncAfter(
                    deadline: .now() + timeout
                ) {
                    gate.resume {
                        continuation.resume(
                            throwing:
                                AgentLocalNodeChannelError.transportFailed
                        )
                    }
                    connection.cancel()
                }
            }
        }, onCancel: {
            connection.cancel()
        })
    }

    private func sendLine(
        _ line: Data,
        on connection: NWConnection
    ) async throws {
        guard
            !line.isEmpty,
            line.count <= AgentPairingBootstrapV2Codec.maxLineBytes,
            !line.contains(0x0A) else {
            throw AgentLocalNodeChannelError.invalidChannelMessage
        }
        try await withCheckedThrowingContinuation { continuation in
            let gate = PairingBootstrapContinuationGate()
            connection.send(
                content: line + Data([0x0A]),
                completion: .contentProcessed { error in
                    gate.resume {
                        if error == nil {
                            continuation.resume()
                        } else {
                            continuation.resume(
                                throwing:
                                    AgentLocalNodeChannelError.transportFailed
                            )
                        }
                    }
                }
            )
        }
    }

    private func receiveLine(
        from connection: NWConnection,
        maximum: Int
    ) async throws -> Data {
        var buffer = Data()
        while buffer.count <= maximum {
            let available = maximum + 1 - buffer.count
            let chunk: Data = try await withCheckedThrowingContinuation {
                continuation in
                connection.receive(
                    minimumIncompleteLength: 1,
                    maximumLength: min(16_384, available)
                ) { data, _, isComplete, error in
                    if let error {
                        continuation.resume(throwing: error)
                    } else if let data, !data.isEmpty {
                        continuation.resume(returning: data)
                    } else if isComplete {
                        continuation.resume(
                            throwing:
                                AgentLocalNodeChannelError.transportFailed
                        )
                    } else {
                        continuation.resume(
                            throwing:
                                AgentLocalNodeChannelError.transportFailed
                        )
                    }
                }
            }
            if let newline = chunk.firstIndex(of: 0x0A) {
                guard newline == chunk.index(before: chunk.endIndex) else {
                    throw AgentLocalNodeChannelError.invalidChannelMessage
                }
                buffer.append(chunk[..<newline])
                guard !buffer.isEmpty else {
                    throw AgentLocalNodeChannelError.invalidChannelMessage
                }
                return buffer
            }
            buffer.append(chunk)
        }
        throw AgentLocalNodeChannelError.payloadInvalid
    }

    private static func newNonce() -> String {
        var generator = SystemRandomNumberGenerator()
        let bytes = (0..<32).map { _ in
            UInt8.random(
                in: UInt8.min...UInt8.max,
                using: &generator
            )
        }
        return "nonce_" + bytes.map {
            String(format: "%02x", $0)
        }.joined()
    }
}

private final class PairingBootstrapContinuationGate: @unchecked Sendable {
    private let lock = NSLock()
    private var resumed = false

    func resume(_ body: () -> Void) {
        lock.lock()
        guard !resumed else {
            lock.unlock()
            return
        }
        resumed = true
        lock.unlock()
        body()
    }
}
