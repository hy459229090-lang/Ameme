import Foundation

/// Application boundary for the three ordinary-user connection methods.
///
/// `resolve` may only produce a bounded, non-sensitive candidate. `connect` is
/// the only operation allowed to produce a persisted non-simulated connection,
/// and implementations must complete authentication/Grant binding before it
/// returns. Discovery alone is never enough.
public protocol AgentExperienceConnector: Sendable {
    var simulated: Bool { get }

    func resolve(method: AgentConnectionMethod) async throws -> AgentExperienceCandidate

    func resolve(pairingPayload: String) async throws -> AgentExperienceCandidate

    func connect(candidate: AgentExperienceCandidate) async throws -> AgentExperienceConnection

    func disconnect(connection: AgentExperienceConnection) async
}

public enum AgentExperienceConnectorError: Error, Equatable {
    case discoveryUnavailable
    case noDeviceFound
    case qrScannerUnavailable
    case accountSignInRequired
    case authorizationRequired
    case candidateUnavailable
    case connectionFailed
    case invalidPairingPayload
}

public extension AgentExperienceConnector {
    func resolve(pairingPayload: String) async throws -> AgentExperienceCandidate {
        throw AgentExperienceConnectorError.qrScannerUnavailable
    }
}

public typealias AgentExperienceAuthenticatedClientFactory = @Sendable (
    AgentExperienceCandidate
) async throws -> AgentLocalNodeNetworkClient

/// Release-path connector for local-network discovery. Discovery only produces
/// a candidate; the injected factory must provide pairing material, a credential
/// and an active Grant before this connector can create a real connection.
public final class BonjourAgentExperienceConnector: @unchecked Sendable, AgentExperienceConnector {
    public let simulated = false

    private let discovery: BonjourAgentExperienceDiscovery
    private let authenticatedClientFactory: AgentExperienceAuthenticatedClientFactory?
    private let now: @Sendable () -> Date
    private let activeClients = ActiveAgentClientRegistry()
    private let pendingPairings = PendingAgentPairingRegistry()

    public init(
        discovery: BonjourAgentExperienceDiscovery = BonjourAgentExperienceDiscovery(),
        authenticatedClientFactory: AgentExperienceAuthenticatedClientFactory? = nil,
        now: @escaping @Sendable () -> Date = { .now }
    ) {
        self.discovery = discovery
        self.authenticatedClientFactory = authenticatedClientFactory
        self.now = now
    }

    public func resolve(method: AgentConnectionMethod) async throws -> AgentExperienceCandidate {
        switch method {
        case .lanDiscovery:
            return try await resolveLanCandidate()
        case .qrCode:
            throw AgentExperienceConnectorError.qrScannerUnavailable
        case .accountDevice:
            throw AgentExperienceConnectorError.accountSignInRequired
        }
    }

    public func resolve(pairingPayload: String) async throws -> AgentExperienceCandidate {
        let envelope: AgentPairingEnvelope
        do {
            envelope = try AgentPairingEnvelope.parse(pairingPayload, now: now())
        } catch {
            throw AgentExperienceConnectorError.invalidPairingPayload
        }
        let candidate = AgentExperienceCandidate(
            id: envelope.pairing.pairingID,
            deviceName: "Ameme 设备",
            agentName: "Ameme Local Node",
            method: .qrCode,
            capabilities: ["写入结构化工作记录"],
            authorizationExpiresAt: envelope.pairingExpiresAt,
            simulated: false
        )
        pendingPairings.insert(envelope, for: candidate.id, now: now())
        return candidate
    }

    public func connect(candidate: AgentExperienceCandidate) async throws -> AgentExperienceConnection {
        guard !candidate.simulated else {
            throw AgentExperienceConnectorError.candidateUnavailable
        }
        let client: AgentLocalNodeNetworkClient
        switch candidate.method {
        case .lanDiscovery:
            guard let authenticatedClientFactory else {
                throw AgentExperienceConnectorError.authorizationRequired
            }
            do {
                client = try await authenticatedClientFactory(candidate)
            } catch {
                throw AgentExperienceConnectorError.authorizationRequired
            }
        case .qrCode:
            guard let envelope = pendingPairings.take(candidate.id, now: now()) else {
                throw AgentExperienceConnectorError.candidateUnavailable
            }
            let createdAt = now()
            guard envelope.pairingExpiresAt > createdAt else {
                throw AgentExperienceConnectorError.candidateUnavailable
            }
            let grant = AgentAccessGrantPolicy.default(
                createdAt: createdAt,
                expiresAt: envelope.pairingExpiresAt
            ).bind(
                callerID: "ameme_ios",
                grantID: "grt_" + UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
            )
            do {
                client = try AgentLocalNodeNetworkClient(
                    pairing: envelope.pairing,
                    secret: envelope.secret,
                    accessGrant: grant
                )
            } catch {
                throw AgentExperienceConnectorError.authorizationRequired
            }
        case .accountDevice:
            throw AgentExperienceConnectorError.accountSignInRequired
        }
        do {
            try await client.connect()
            if let previous = activeClients.insert(client, for: candidate.id) {
                await previous.close()
            }
        } catch {
            await client.close()
            throw AgentExperienceConnectorError.connectionFailed
        }
        let connectedAt = now()
        return AgentExperienceConnection(
            id: candidate.id,
            deviceName: candidate.deviceName,
            agentName: candidate.agentName,
            method: candidate.method,
            capabilities: ["写入结构化工作记录"],
            connectedAt: connectedAt,
            expiresAt: min(connectedAt.addingTimeInterval(AgentExperienceConnection.defaultLifetime), client.accessGrant.expiresAt),
            simulated: false
        )
    }

    public func disconnect(connection: AgentExperienceConnection) async {
        guard let client = activeClients.remove(connection.id) else { return }
        await client.close()
    }

    private func resolveLanCandidate() async throws -> AgentExperienceCandidate {
        let box = CandidateResolutionBox()
        return try await withTaskCancellationHandler(operation: {
            try await withCheckedThrowingContinuation { continuation in
                box.install(continuation)
                let session = discovery.start(
                    onCandidate: { candidate in
                        box.finish(.success(candidate))
                    },
                    onError: { error in
                        box.finish(.failure(Self.map(error)))
                    }
                )
                box.install(session)
            }
        }, onCancel: {
            box.cancel()
        })
    }

    private static func map(_ error: AgentExperienceDiscoveryError) -> Error {
        switch error {
        case .unavailable:
            AgentExperienceConnectorError.discoveryUnavailable
        case .noDeviceFound:
            AgentExperienceConnectorError.noDeviceFound
        }
    }
}

/// Explicit mock connector used by previews and the in-app “演示连接” action.
/// It is never selected implicitly by the release connector.
public struct SimulatedAgentExperienceConnector: AgentExperienceConnector, Sendable {
    public let simulated = true

    public init() {}

    public func resolve(method: AgentConnectionMethod) async throws -> AgentExperienceCandidate {
        let methodIdentifier = method.rawValue.replacingOccurrences(of: "_", with: "-")
        return AgentExperienceCandidate(
            id: "ios-demo-" + methodIdentifier,
            deviceName: "Ameme Desktop",
            agentName: "Codex",
            method: method,
            capabilities: ["写入结构化工作记录"],
            simulated: true
        )
    }

    public func connect(candidate: AgentExperienceCandidate) async throws -> AgentExperienceConnection {
        guard candidate.simulated else {
            throw AgentExperienceConnectorError.candidateUnavailable
        }
        let connectedAt = Date.now
        return AgentExperienceConnection(
            id: candidate.id,
            deviceName: candidate.deviceName,
            agentName: candidate.agentName,
            method: candidate.method,
            capabilities: candidate.capabilities,
            connectedAt: connectedAt,
            expiresAt: connectedAt.addingTimeInterval(AgentExperienceConnection.defaultLifetime),
            simulated: true
        )
    }

    public func disconnect(connection: AgentExperienceConnection) async {}
}

private final class ActiveAgentClientRegistry: @unchecked Sendable {
    private let lock = NSLock()
    private var clients: [String: AgentLocalNodeNetworkClient] = [:]

    func insert(_ client: AgentLocalNodeNetworkClient, for id: String) -> AgentLocalNodeNetworkClient? {
        lock.lock()
        defer { lock.unlock() }
        return clients.updateValue(client, forKey: id)
    }

    func remove(_ id: String) -> AgentLocalNodeNetworkClient? {
        lock.lock()
        defer { lock.unlock() }
        return clients.removeValue(forKey: id)
    }
}

private final class PendingAgentPairingRegistry: @unchecked Sendable {
    private let lock = NSLock()
    private var envelopes: [String: AgentPairingEnvelope] = [:]

    func insert(_ envelope: AgentPairingEnvelope, for id: String, now: Date) {
        lock.lock()
        envelopes = envelopes.filter { $0.value.expiresAt > now }
        envelopes[id] = envelope
        lock.unlock()
    }

    func take(_ id: String, now: Date) -> AgentPairingEnvelope? {
        lock.lock()
        defer { lock.unlock() }
        envelopes = envelopes.filter { $0.value.expiresAt > now }
        return envelopes.removeValue(forKey: id)
    }
}

private final class CandidateResolutionBox: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<AgentExperienceCandidate, Error>?
    private var session: AgentExperienceDiscoverySession?
    private var pendingResult: Result<AgentExperienceCandidate, Error>?
    private var finished = false

    func install(_ continuation: CheckedContinuation<AgentExperienceCandidate, Error>) {
        var result: Result<AgentExperienceCandidate, Error>?
        lock.lock()
        if let pendingResult {
            result = pendingResult
            self.pendingResult = nil
        } else if finished {
            result = .failure(CancellationError())
        } else {
            self.continuation = continuation
        }
        lock.unlock()
        if let result {
            continuation.resume(with: result)
        }
    }

    func install(_ session: AgentExperienceDiscoverySession) {
        var shouldCancel = false
        lock.lock()
        if finished {
            shouldCancel = true
        } else {
            self.session = session
        }
        lock.unlock()
        if shouldCancel {
            session.cancel()
        }
    }

    func finish(_ result: Result<AgentExperienceCandidate, Error>) {
        var continuation: CheckedContinuation<AgentExperienceCandidate, Error>?
        var session: AgentExperienceDiscoverySession?
        lock.lock()
        guard !finished else {
            lock.unlock()
            return
        }
        finished = true
        continuation = self.continuation
        self.continuation = nil
        session = self.session
        self.session = nil
        if continuation == nil {
            pendingResult = result
        }
        lock.unlock()
        session?.cancel()
        continuation?.resume(with: result)
    }

    func cancel() {
        finish(.failure(CancellationError()))
    }
}
