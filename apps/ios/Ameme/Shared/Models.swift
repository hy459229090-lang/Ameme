import Foundation

public enum CaptureKind: String, Codable, CaseIterable, Identifiable {
    case text
    case voice
    case photo
    case importFile

    public var id: String { rawValue }

    public var label: String {
        switch self {
        case .text: "文字"
        case .voice: "语音"
        case .photo: "照片"
        case .importFile: "导入"
        }
    }

    public var systemImage: String {
        switch self {
        case .text: "square.and.pencil"
        case .voice: "mic"
        case .photo: "photo"
        case .importFile: "doc.badge.plus"
        }
    }
}

public enum FactStatus: String, Codable {
    case confirmed
    case userAsserted
    case planned
    case inferred
    case needsReview
    case conflict
    case processing

    public var wireValue: String {
        switch self {
        case .confirmed: "confirmed"
        case .userAsserted: "user_asserted"
        case .planned: "planned"
        case .inferred: "inferred"
        case .needsReview: "needs_review"
        case .conflict: "conflict"
        case .processing: "processing"
        }
    }

    public var label: String {
        switch self {
        case .confirmed: "已记录"
        case .userAsserted: "用户陈述"
        case .planned: "计划，未确认发生"
        case .inferred: "推测"
        case .needsReview: "待核验"
        case .conflict: "存在冲突"
        case .processing: "整理中"
        }
    }
}

public enum EventType: String, Codable {
    case activity
    case communication
    case decision
    case result
    case stateChange
    case milestone
    case experience

    public var wireValue: String {
        switch self {
        case .activity: "activity"
        case .communication: "communication"
        case .decision: "decision"
        case .result: "result"
        case .stateChange: "state_change"
        case .milestone: "milestone"
        case .experience: "experience"
        }
    }
}

public enum EvidenceState: String, Codable {
    case observed
    case userAsserted
    case inferred

    public var wireValue: String {
        switch self {
        case .observed: "observed"
        case .userAsserted: "user_asserted"
        case .inferred: "inferred"
        }
    }
}

public enum Sensitivity: String, Codable {
    case personal
    case confidential
    case restricted

    public var wireValue: String { rawValue }
}

public enum ExperienceMode: String, CaseIterable, Identifiable {
    case ready
    case empty
    case sparse
    case loading
    case partial
    case offline
    case recoverableError
    case permissionLimited

    public var id: String { rawValue }

    public var label: String {
        switch self {
        case .ready: "本机可用"
        case .empty: "空白"
        case .sparse: "数据稀疏"
        case .loading: "正在读取"
        case .partial: "部分范围"
        case .offline: "离线"
        case .recoverableError: "可恢复错误"
        case .permissionLimited: "权限受限"
        }
    }

    public var detail: String {
        switch self {
        case .ready: "已加载本机事件"
        case .empty: "当前范围没有可见记录"
        case .sparse: "当前只有少量获准记录"
        case .loading: "本机索引正在更新"
        case .partial: "部分设备或索引尚未参与"
        case .offline: "继续使用本机记录"
        case .recoverableError: "已有内容安全，可以重试"
        case .permissionLimited: "一个来源未连接，其他路径仍可用"
        }
    }

    /// Resolves the content-driven state used by the ordinary local product path.
    /// Explicit transport and permission states always win; deterministic preview
    /// callers can opt out so their requested state remains stable.
    public func resolvedFor(eventCount: Int, deriveFromEvents: Bool) -> ExperienceMode {
        precondition(eventCount >= 0, "eventCount must not be negative")
        guard deriveFromEvents else { return self }
        switch self {
        case .loading, .partial, .offline, .recoverableError, .permissionLimited:
            return self
        case .ready, .empty, .sparse:
            switch eventCount {
            case 0: return .empty
            case 1: return .sparse
            default: return .ready
            }
        }
    }
}

public struct MemoryEvent: Identifiable, Codable, Hashable {
    public let id: UUID
    public let localDate: Date
    public let time: Date?
    public var title: String
    public var detail: String
    public var factStatus: FactStatus
    public let sourceLabel: String
    public let sourceLocator: String?
    public let captureKind: CaptureKind
    public let isLocalOnly: Bool
    public var userWords: String?
    public var revision: Int
    public let eventType: EventType
    public let evidenceState: EvidenceState
    public let sensitivity: Sensitivity
    public let importance: Int

    public init(
        id: UUID = UUID(),
        localDate: Date = .now,
        time: Date? = .now,
        title: String,
        detail: String,
        factStatus: FactStatus = .userAsserted,
        sourceLabel: String,
        sourceLocator: String? = nil,
        captureKind: CaptureKind,
        isLocalOnly: Bool = true,
        userWords: String? = nil,
        revision: Int = 1,
        eventType: EventType = .experience,
        evidenceState: EvidenceState = .userAsserted,
        sensitivity: Sensitivity = .personal,
        importance: Int = 50
    ) {
        self.id = id
        self.localDate = Calendar.current.startOfDay(for: localDate)
        self.time = time
        self.title = title
        self.detail = detail
        self.factStatus = factStatus
        self.sourceLabel = sourceLabel
        self.sourceLocator = sourceLocator
        self.captureKind = captureKind
        self.isLocalOnly = isLocalOnly
        self.userWords = userWords
        self.revision = revision
        self.eventType = eventType
        self.evidenceState = evidenceState
        self.sensitivity = sensitivity
        self.importance = importance
    }
}

/// A persistence-ready event description used when a source import must commit atomically.
/// The draft has no identity until the local store accepts the whole batch.
public struct MemoryEventDraft {
    public let localDate: Date
    public let time: Date?
    public let title: String
    public let detail: String
    public let factStatus: FactStatus
    public let sourceLabel: String
    public let sourceLocator: String?
    public let captureKind: CaptureKind
    public let userWords: String?
    public let eventType: EventType
    public let evidenceState: EvidenceState
    public let sensitivity: Sensitivity
    public let importance: Int

    public init(
        localDate: Date = .now,
        time: Date? = .now,
        title: String,
        detail: String,
        factStatus: FactStatus = .userAsserted,
        sourceLabel: String,
        sourceLocator: String? = nil,
        captureKind: CaptureKind,
        userWords: String? = nil,
        eventType: EventType = .experience,
        evidenceState: EvidenceState = .userAsserted,
        sensitivity: Sensitivity = .personal,
        importance: Int = 50
    ) {
        self.localDate = localDate
        self.time = time
        self.title = title
        self.detail = detail
        self.factStatus = factStatus
        self.sourceLabel = sourceLabel
        self.sourceLocator = sourceLocator
        self.captureKind = captureKind
        self.userWords = userWords
        self.eventType = eventType
        self.evidenceState = evidenceState
        self.sensitivity = sensitivity
        self.importance = importance
    }
}

public struct DaySummary: Hashable {
    public let state: State
    public let text: String
    public let basedOnRevision: Int

    public enum State: String {
        case absent
        case processing
        case insufficient
        case stale
        case ready
    }
}

public enum DeleteStep: String, CaseIterable, Identifiable {
    case queued
    case localDeleting
    case syncPropagating
    case recomputing
    case partialFailed
    case completed

    public var id: String { rawValue }

    public var label: String {
        switch self {
        case .queued: "已受理"
        case .localDeleting: "清理本机对象"
        case .syncPropagating: "等待其他设备确认"
        case .recomputing: "重算日流与索引"
        case .partialFailed: "部分失败，可重试"
        case .completed: "已完成"
        }
    }
}
