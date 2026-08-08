import Foundation

/// Swift representation of the canonical AccessGrant contract.
///
/// This is authorization metadata only. Pairing secrets, endpoints, private
/// keys and memory content remain outside the Grant and its persistence layer.
public struct AgentAccessGrant: Codable, Equatable, Sendable {
    public static let currentSchemaVersion = 1

    public let schemaVersion: Int
    public let grantID: String
    public let ownerID: String
    public let callerID: String
    public let purposes: [String]
    public let spaces: [String]
    public let dataTypes: [String]
    public let notBefore: Date
    public let expiresAt: Date
    public let status: AgentAccessGrantStatus
    public let keyFingerprint: String?
    public let createdAt: Date
    public let revokedAt: Date?

    public init(
        grantID: String,
        ownerID: String,
        callerID: String,
        purposes: [String],
        spaces: [String],
        dataTypes: [String],
        notBefore: Date,
        expiresAt: Date,
        status: AgentAccessGrantStatus,
        keyFingerprint: String? = nil,
        createdAt: Date,
        revokedAt: Date? = nil
    ) {
        self.schemaVersion = Self.currentSchemaVersion
        self.grantID = grantID
        self.ownerID = ownerID
        self.callerID = callerID
        self.purposes = purposes
        self.spaces = spaces
        self.dataTypes = dataTypes
        self.notBefore = notBefore
        self.expiresAt = expiresAt
        self.status = status
        self.keyFingerprint = keyFingerprint
        self.createdAt = createdAt
        self.revokedAt = revokedAt
        precondition(Self.isValidIdentifier(grantID))
        precondition(Self.isValidIdentifier(ownerID))
        precondition(Self.isValidIdentifier(callerID))
        Self.preconditionScope(purposes, maxLength: 80)
        Self.preconditionScope(spaces, maxLength: 128)
        Self.preconditionScope(dataTypes, maxLength: 64)
        precondition(expiresAt >= notBefore)
        precondition(expiresAt > createdAt)
        precondition(keyFingerprint?.count ?? 0 <= 256)
        if status == .revoked {
            precondition(revokedAt != nil)
        } else {
            precondition(revokedAt == nil)
        }
    }

    public func isActive(at date: Date = .now) -> Bool {
        status == .active && date >= notBefore && date < expiresAt
    }

    public func authorize(_ request: AgentAccessGrantRequest, at date: Date = .now) throws {
        try authorizeScope(
            callerID: request.callerID,
            grantID: request.grantID,
            purpose: request.purpose,
            spaces: [request.space],
            dataTypes: [request.dataType],
            at: date
        )
    }

    /// Checks an explicit minimal request scope against this Grant.
    ///
    /// This mirrors the Android Local Node authorization boundary for bounded
    /// reads, whose payload may name more than one already-granted space/type.
    public func authorizeScope(
        callerID: String,
        grantID: String,
        purpose: String,
        spaces: Set<String>,
        dataTypes: Set<String>,
        at date: Date = .now
    ) throws {
        guard callerID == self.callerID, grantID == self.grantID else {
            throw AgentAccessGrantError.authRequired
        }
        switch status {
        case .revoked:
            throw AgentAccessGrantError.grantRevoked
        case .expired:
            throw AgentAccessGrantError.grantExpired
        case .active:
            guard date >= notBefore, date < expiresAt else {
                throw AgentAccessGrantError.grantExpired
            }
        }
        guard purposes.contains(purpose) else {
            throw AgentAccessGrantError.purposeDenied
        }
        guard !spaces.isEmpty, spaces.isSubset(of: Set(self.spaces)) else {
            throw AgentAccessGrantError.spaceDenied
        }
        guard !dataTypes.isEmpty, dataTypes.isSubset(of: Set(self.dataTypes)) else {
            throw AgentAccessGrantError.dataTypeDenied
        }
    }

    private static func isValidIdentifier(_ value: String) -> Bool {
        value.range(of: "^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$", options: .regularExpression) != nil
    }

    private static func preconditionScope(_ values: [String], maxLength: Int) {
        precondition(!values.isEmpty && values.count <= 8)
        precondition(Set(values).count == values.count)
        precondition(values.allSatisfy { !$0.isEmpty && $0.count <= maxLength && !$0.contains("*") })
    }
}

/// The maximum scope the user approved locally for one pairing.
///
/// The host still supplies the caller and Grant identifiers. Binding those
/// identifiers to this policy creates the request-time [AgentAccessGrant]
/// checked by the local node; the policy itself never contains channel secrets.
public struct AgentAccessGrantPolicy: Codable, Equatable, Sendable {
    public let schemaVersion: Int
    public let ownerID: String
    public let purposes: [String]
    public let spaces: [String]
    public let dataTypes: [String]
    public let notBefore: Date
    public let expiresAt: Date
    public let status: AgentAccessGrantStatus
    public let createdAt: Date

    public init(
        ownerID: String,
        purposes: [String],
        spaces: [String],
        dataTypes: [String],
        notBefore: Date,
        expiresAt: Date,
        status: AgentAccessGrantStatus,
        createdAt: Date
    ) {
        self.schemaVersion = AgentAccessGrant.currentSchemaVersion
        self.ownerID = ownerID
        self.purposes = purposes
        self.spaces = spaces
        self.dataTypes = dataTypes
        self.notBefore = notBefore
        self.expiresAt = expiresAt
        self.status = status
        self.createdAt = createdAt
        precondition(Self.isValidIdentifier(ownerID))
        Self.preconditionScope(purposes, maxLength: 80)
        Self.preconditionScope(spaces, maxLength: 128)
        Self.preconditionScope(dataTypes, maxLength: 64)
        precondition(expiresAt >= notBefore && expiresAt > createdAt)
    }

    public func isActive(at date: Date = .now) -> Bool {
        status == .active && date >= notBefore && date < expiresAt
    }

    public func bind(callerID: String, grantID: String) -> AgentAccessGrant {
        AgentAccessGrant(
            grantID: grantID,
            ownerID: ownerID,
            callerID: callerID,
            purposes: purposes,
            spaces: spaces,
            dataTypes: dataTypes,
            notBefore: notBefore,
            expiresAt: expiresAt,
            status: status,
            createdAt: createdAt
        )
    }

    public static func `default`(createdAt: Date, expiresAt: Date) -> AgentAccessGrantPolicy {
        AgentAccessGrantPolicy(
            ownerID: "owner_local",
            purposes: ["autonomous_memory"],
            spaces: ["space_personal"],
            dataTypes: ["event"],
            notBefore: createdAt,
            expiresAt: expiresAt,
            status: .active,
            createdAt: createdAt
        )
    }

    private static func isValidIdentifier(_ value: String) -> Bool {
        value.range(of: "^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$", options: .regularExpression) != nil
    }

    private static func preconditionScope(_ values: [String], maxLength: Int) {
        precondition(!values.isEmpty && values.count <= 8)
        precondition(Set(values).count == values.count)
        precondition(values.allSatisfy { !$0.isEmpty && $0.count <= maxLength && !$0.contains("*") })
    }
}

public enum AgentAccessGrantStatus: String, Codable, Sendable {
    case active
    case revoked
    case expired
}

public struct AgentAccessGrantRequest: Codable, Equatable, Sendable {
    public let callerID: String
    public let grantID: String
    public let purpose: String
    public let space: String
    public let dataType: String
    public let operation: String

    public init(
        callerID: String,
        grantID: String,
        purpose: String,
        space: String,
        dataType: String,
        operation: String
    ) {
        self.callerID = callerID
        self.grantID = grantID
        self.purpose = purpose
        self.space = space
        self.dataType = dataType
        self.operation = operation
        precondition(callerID.range(of: "^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$", options: .regularExpression) != nil)
        precondition(grantID.range(of: "^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$", options: .regularExpression) != nil)
        precondition(!purpose.isEmpty && purpose.count <= 80)
        precondition(!space.isEmpty && space.count <= 128)
        precondition(!dataType.isEmpty && dataType.count <= 64)
        precondition(operation.range(of: "^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$", options: .regularExpression) != nil)
    }
}

public enum AgentAccessGrantError: Error, Equatable, Sendable {
    case authRequired
    case grantRevoked
    case grantExpired
    case purposeDenied
    case spaceDenied
    case dataTypeDenied
}
