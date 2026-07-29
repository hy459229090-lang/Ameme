import Foundation

public enum AgentConnectionMethod: String, Codable, CaseIterable, Identifiable, Sendable {
    case lanDiscovery = "lan_discovery"
    case qrCode = "qr_code"
    case accountDevice = "account_device"

    public var id: String { rawValue }

    public var label: String {
        switch self {
        case .lanDiscovery: "自动发现电脑"
        case .qrCode: "扫描二维码"
        case .accountDevice: "账户设备"
        }
    }

    public var detail: String {
        switch self {
        case .lanDiscovery: "适合同一局域网"
        case .qrCode: "适合电脑已显示配对码"
        case .accountDevice: "适合同一账户下的已登录设备"
        }
    }
}

public struct AgentExperienceCandidate: Codable, Equatable, Sendable {
    public let id: String
    public let deviceName: String
    public let agentName: String
    public let method: AgentConnectionMethod
    public let capabilities: [String]
    public let authorizationExpiresAt: Date?
    public let simulated: Bool

    public init(
        id: String,
        deviceName: String,
        agentName: String,
        method: AgentConnectionMethod,
        capabilities: [String],
        authorizationExpiresAt: Date? = nil,
        simulated: Bool
    ) {
        self.id = id
        self.deviceName = deviceName
        self.agentName = agentName
        self.method = method
        self.capabilities = capabilities
        self.authorizationExpiresAt = authorizationExpiresAt
        self.simulated = simulated
        precondition(AgentExperienceConnection.isValidIdentifier(id))
        precondition(!deviceName.isEmpty && deviceName.count <= 80)
        precondition(!agentName.isEmpty && agentName.count <= 80)
        precondition(!capabilities.isEmpty && capabilities.count <= 8)
        precondition(capabilities.allSatisfy { !$0.isEmpty && $0.count <= 80 })
    }
}

public struct AgentExperienceConnection: Codable, Equatable, Sendable {
    public static let currentSchemaVersion = 1
    public static let defaultLifetime: TimeInterval = 30 * 24 * 60 * 60

    public let schemaVersion: Int
    public let id: String
    public let deviceName: String
    public let agentName: String
    public let method: AgentConnectionMethod
    public let capabilities: [String]
    public let connectedAt: Date
    public let expiresAt: Date
    public let simulated: Bool

    public init(
        id: String,
        deviceName: String,
        agentName: String,
        method: AgentConnectionMethod,
        capabilities: [String],
        connectedAt: Date,
        expiresAt: Date,
        simulated: Bool
    ) {
        self.schemaVersion = Self.currentSchemaVersion
        self.id = id
        self.deviceName = deviceName
        self.agentName = agentName
        self.method = method
        self.capabilities = capabilities
        self.connectedAt = connectedAt
        self.expiresAt = expiresAt
        self.simulated = simulated
        precondition(Self.isValidIdentifier(id))
        precondition(!deviceName.isEmpty && deviceName.count <= 80)
        precondition(!agentName.isEmpty && agentName.count <= 80)
        precondition(!capabilities.isEmpty && capabilities.count <= 8)
        precondition(capabilities.allSatisfy { !$0.isEmpty && $0.count <= 80 })
        precondition(expiresAt > connectedAt)
    }

    public static func simulatedDemo(
        method: AgentConnectionMethod,
        connectedAt: Date = .now
    ) -> Self {
        let methodIdentifier = method.rawValue.replacingOccurrences(of: "_", with: "-")
        return Self(
            id: "ios-demo-" + methodIdentifier,
            deviceName: "Ameme Desktop",
            agentName: "Codex",
            method: method,
            capabilities: ["写入结构化工作记录"],
            connectedAt: connectedAt,
            expiresAt: connectedAt.addingTimeInterval(Self.defaultLifetime),
            simulated: true
        )
    }

    public func isValid(at date: Date) -> Bool {
        schemaVersion == Self.currentSchemaVersion && expiresAt > date
    }

    fileprivate static func isValidIdentifier(_ value: String) -> Bool {
        value.range(of: "^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$", options: .regularExpression) != nil
    }
}

public enum AgentExperienceDiscoveryError: Error, Equatable {
    case unavailable
    case noDeviceFound
}

public enum AgentExperienceServiceContract {
    public static let bonjourServiceType = "_ameme-agent._tcp"
    public static let capabilityAttribute = "capabilities"
    public static let deviceIDAttribute = "device_id"
    public static let deviceNameAttribute = "device_name"
    public static let agentNameAttribute = "agent_name"
}

public enum AgentExperienceStoreError: Error, Equatable {
    case invalidState
    case unsupportedVersion
    case unavailable
}

/// Stores only non-sensitive connection display metadata. Pairing secrets,
/// endpoints and certificate pins never enter this store.
public struct AgentExperienceStore {
    public static let userDefaultsKey = "ameme.agent.experience.connection.v1"

    private let defaults: UserDefaults
    private let now: @Sendable () -> Date

    public init(
        defaults: UserDefaults = .standard,
        now: @escaping @Sendable () -> Date = { .now }
    ) {
        self.defaults = defaults
        self.now = now
    }

    public func load() throws -> AgentExperienceConnection? {
        guard let data = defaults.data(forKey: Self.userDefaultsKey) else { return nil }
        do {
            let connection = try JSONDecoder().decode(AgentExperienceConnection.self, from: data)
            guard connection.schemaVersion == AgentExperienceConnection.currentSchemaVersion else {
                clear()
                throw AgentExperienceStoreError.unsupportedVersion
            }
            guard connection.isValid(at: now()) else {
                clear()
                return nil
            }
            // This store contains display metadata only. A real channel/client is
            // process-owned and its credential is deliberately not persisted here;
            // never relaunch into a UI state that claims an active transport.
            guard connection.simulated else {
                clear()
                return nil
            }
            return connection
        } catch let error as AgentExperienceStoreError {
            throw error
        } catch {
            clear()
            throw AgentExperienceStoreError.invalidState
        }
    }

    public func save(_ connection: AgentExperienceConnection) throws {
        guard connection.isValid(at: now()) else {
            throw AgentExperienceStoreError.invalidState
        }
        do {
            defaults.set(try JSONEncoder().encode(connection), forKey: Self.userDefaultsKey)
        } catch {
            throw AgentExperienceStoreError.unavailable
        }
    }

    public func clear() {
        defaults.removeObject(forKey: Self.userDefaultsKey)
    }

    public func clearAndVerify() throws {
        clear()
        guard defaults.object(forKey: Self.userDefaultsKey) == nil else {
            throw AgentExperienceStoreError.unavailable
        }
    }
}
