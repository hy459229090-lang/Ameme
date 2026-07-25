import Foundation
import Network
import Security

/// A real iOS client for a previously paired Android Local Node endpoint.
///
/// Discovery, QR, and account flows must provide the same pairing material and
/// credential through their own trusted handoff. This client deliberately does
/// not accept a Bonjour candidate alone.
public actor AgentLocalNodeNetworkClient {
    public let pairing: AgentLocalNodePairingMaterial
    public let accessGrant: AgentAccessGrant

    private var secret: Data
    private let timeout: TimeInterval
    private let queue = DispatchQueue(label: "com.ameme.agent-local-node-client")
    private var connection: NWConnection?
    private var sessionKey: Data?
    private var sessionID: String?
    private var sequence: Int64 = 0
    private var responseNonces = Set<String>()

    public init(
        pairing: AgentLocalNodePairingMaterial,
        secret: Data,
        accessGrant: AgentAccessGrant,
        timeout: TimeInterval = 5
    ) throws {
        guard (0.1...5).contains(timeout) else { throw AgentLocalNodeChannelError.transportFailed }
        guard secret.count >= 32, secret.count <= 256 else { throw AgentLocalNodeChannelError.authenticationFailed }
        self.pairing = pairing
        self.secret = secret
        self.accessGrant = accessGrant
        self.timeout = timeout
    }

    /// Performs TLS 1.3 + certificate pinning + HMAC channel authentication.
    public func connect() async throws {
        guard secret.count >= 32, secret.count <= 256 else { throw AgentLocalNodeChannelError.authenticationFailed }
        guard accessGrant.isActive() else { throw AgentLocalNodeChannelError.authenticationFailed }
        guard connection == nil else { return }

        let connection = makeConnection()
        do {
            try await waitUntilReady(connection)
            let clientLine = try AgentLocalNodeChannelCodec.buildClientHello(
                pairing: pairing,
                secret: secret,
                clientNonce: Self.newNonce()
            )
            let clientHello = try AgentLocalNodeChannelCodec.makeClientHelloView(
                clientLine,
                pairing: pairing,
                secret: secret
            )
            try await sendLine(clientLine, on: connection)
            let serverLine = try await receiveLine(from: connection, maximum: AgentLocalNodeChannelCodec.maxChannelLineBytes)
            let serverHello = try AgentLocalNodeChannelCodec.verifyServerHello(
                serverLine,
                clientHello: clientHello,
                pairing: pairing,
                secret: secret
            )
            let derivedSessionKey = try AgentLocalNodeChannelCodec.deriveSessionKey(
                clientHello: clientHello,
                serverHello: serverHello,
                secret: secret
            )
            self.connection = connection
            self.sessionKey = derivedSessionKey
            self.sessionID = serverHello.sessionID
            self.sequence = 0
            self.responseNonces.removeAll()
        } catch {
            connection.cancel()
            close()
            throw mapTransportError(error)
        }
    }

    /// Exchanges one canonical application request on the authenticated channel.
    /// Calls are serialized and the session is destroyed on any framing failure.
    public func exchange(applicationLine: Data) async throws -> AgentLocalNodeResponseFrame {
        guard let connection, let sessionKey, let sessionID else {
            throw AgentLocalNodeChannelError.transportFailed
        }
        guard sequence < 9_007_199_254_740_991 else {
            close()
            throw AgentLocalNodeChannelError.sequenceInvalid
        }
        sequence += 1
        let nextSequence = sequence
        let nonce = Self.newNonce()

        do {
            let built = try AgentLocalNodeChannelCodec.buildRequestFrame(
                applicationLine: applicationLine,
                sessionKey: sessionKey,
                sessionID: sessionID,
                sequence: nextSequence,
                nonce: nonce
            )
            try await sendLine(built.line, on: connection)
            let line = try await receiveLine(from: connection, maximum: AgentLocalNodeChannelCodec.maxChannelLineBytes)
            var nonces = responseNonces
            let response = try AgentLocalNodeChannelCodec.parseResponseFrame(
                line,
                sessionKey: sessionKey,
                requestFrame: built.frame,
                seenNonces: &nonces
            )
            responseNonces = nonces
            return response
        } catch {
            close()
            throw mapTransportError(error)
        }
    }

    public func exchangeCreateEvent(
        draft: AgentLocalNodeCreateEventDraft,
        authorizationDate: Date = .now
    ) async throws -> AgentLocalNodeResponseFrame {
        let applicationLine = try AgentLocalNodeChannelCodec.buildCreateEventRequest(
            draft: draft,
            grant: accessGrant,
            authorizationDate: authorizationDate
        )
        return try await exchange(applicationLine: applicationLine)
    }

    public func close() {
        let connection = self.connection
        self.connection = nil
        self.sessionID = nil
        self.sequence = 0
        self.responseNonces.removeAll()
        if var sessionKey {
            let length = sessionKey.count
            sessionKey.resetBytes(in: 0..<length)
        }
        self.sessionKey = nil
        let secretLength = secret.count
        if secretLength > 0 { secret.resetBytes(in: 0..<secretLength) }
        connection?.cancel()
    }

    private func makeConnection() -> NWConnection {
        let tls = NWProtocolTLS.Options()
        sec_protocol_options_set_min_tls_protocol_version(tls.securityProtocolOptions, .TLSv13)
        sec_protocol_options_set_max_tls_protocol_version(tls.securityProtocolOptions, .TLSv13)
        let expectedPin = pairing.tlsCertificateSHA256
        sec_protocol_options_set_verify_block(tls.securityProtocolOptions, { _, trust, complete in
            let chain = SecTrustCopyCertificateChain(sec_trust_copy_ref(trust).takeRetainedValue()) as? [SecCertificate]
            guard let certificate = chain?.first else {
                complete(false)
                return
            }
            let certificateData = SecCertificateCopyData(certificate) as Data
            complete(AgentLocalNodeChannelCodec.digest(certificateData) == expectedPin)
        }, queue)
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
                let resumed = ContinuationGate()
                connection.stateUpdateHandler = { state in
                    switch state {
                    case .ready:
                        resumed.resume { continuation.resume() }
                    case .failed, .cancelled:
                        resumed.resume { continuation.resume(throwing: AgentLocalNodeChannelError.transportFailed) }
                    default:
                        break
                    }
                }
                connection.start(queue: queue)
                DispatchQueue.global().asyncAfter(deadline: .now() + timeout) {
                    resumed.resume { continuation.resume(throwing: AgentLocalNodeChannelError.transportFailed) }
                    connection.cancel()
                }
            }
        }, onCancel: { connection.cancel() })
    }

    private func sendLine(_ line: Data, on connection: NWConnection) async throws {
        guard !line.isEmpty, line.count <= AgentLocalNodeChannelCodec.maxChannelLineBytes, !line.contains(0x0A) else {
            throw AgentLocalNodeChannelError.invalidChannelMessage
        }
        try await withCheckedThrowingContinuation { continuation in
            let resumed = ContinuationGate()
            connection.send(content: line + Data([0x0A]), completion: .contentProcessed { error in
                resumed.resume {
                    if error == nil { continuation.resume() }
                    else { continuation.resume(throwing: AgentLocalNodeChannelError.transportFailed) }
                }
            })
        }
    }

    private func receiveLine(from connection: NWConnection, maximum: Int) async throws -> Data {
        var buffer = Data()
        while buffer.count <= maximum {
            let chunk: Data = try await withCheckedThrowingContinuation { continuation in
                connection.receive(minimumIncompleteLength: 1, maximumLength: min(16_384, maximum + 1 - buffer.count)) { data, _, isComplete, error in
                    if let error {
                        continuation.resume(throwing: error)
                    } else if let data, !data.isEmpty {
                        continuation.resume(returning: data)
                    } else if isComplete {
                        continuation.resume(throwing: AgentLocalNodeChannelError.transportFailed)
                    } else {
                        continuation.resume(throwing: AgentLocalNodeChannelError.transportFailed)
                    }
                }
            }
            if let newline = chunk.firstIndex(of: 0x0A) {
                guard newline == chunk.index(before: chunk.endIndex) else { throw AgentLocalNodeChannelError.invalidChannelMessage }
                buffer.append(chunk[..<newline])
                guard !buffer.isEmpty else { throw AgentLocalNodeChannelError.invalidChannelMessage }
                return buffer
            }
            buffer.append(chunk)
        }
        throw AgentLocalNodeChannelError.payloadInvalid
    }

    private func mapTransportError(_ error: Error) -> AgentLocalNodeChannelError {
        if let channelError = error as? AgentLocalNodeChannelError { return channelError }
        return .transportFailed
    }

    private static func newNonce() -> String {
        var generator = SystemRandomNumberGenerator()
        let bytes = (0..<32).map { _ in UInt8.random(in: UInt8.min...UInt8.max, using: &generator) }
        return "nonce_" + bytes.map { String(format: "%02x", $0) }.joined()
    }
}

private final class ContinuationGate: @unchecked Sendable {
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
