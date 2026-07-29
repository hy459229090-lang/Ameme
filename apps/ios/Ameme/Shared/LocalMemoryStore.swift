import Combine
import CryptoKit
import Foundation
import Security

public protocol KeyMaterialStore: Sendable {
    func loadOrCreateKey() throws -> SymmetricKey
}

@MainActor
public final class LocalMemoryStore: ObservableObject {
    @Published public private(set) var events: [MemoryEvent] = []
    @Published public private(set) var coverageDays: [PersistedCoverageDay] = []
    @Published public private(set) var coverageEventLinks: [CoverageEventLink] = []
    @Published public private(set) var longTermMemories: [LongTermMemoryRecord] = []
    @Published public private(set) var deletionTombstones: [DeletionTombstone] = []
    @Published public private(set) var reuseAttempts: [ReuseAttemptRecord] = []
    @Published public private(set) var reuseOutcomes: [ReuseOutcomeRecord] = []
    @Published public private(set) var sourceObjects: [SourceObject] = []
    @Published public private(set) var eventSourceLinks: [EventSourceLink] = []
    @Published public private(set) var eventFieldEvidence: [EventFieldEvidence] = []
    @Published public private(set) var eventUserConfirmations: [EventUserConfirmation] = []
    @Published public private(set) var sourceDeletionRecords: [SourceDeletionRecord] = []
    @Published public private(set) var storageState: StorageState = .loading
    @Published public private(set) var isDemoMode = false

    public enum StorageState: Equatable {
        case loading
        case ready
        case deleted
        case recoverableError
    }

    private let fileManager: FileManager
    private let rootDirectory: URL
    private let fileURL: URL
    private let mediaDirectory: URL
    private let keyStore: any KeyMaterialStore
    private var key: SymmetricKey?
    private var allowsDeletedSpacePersistence = false
    var persistenceFailureInjector: (() -> Bool)?
    var recoveryActivationFailureInjector: ((LocalRecoveryActivationPhase) throws -> Void)?

    public init(
        fileManager: FileManager = .default,
        keyStore: any KeyMaterialStore = KeyStore(),
        rootDirectory: URL? = nil
    ) {
        self.fileManager = fileManager
        self.keyStore = keyStore
        let root: URL
        if let rootDirectory {
            root = rootDirectory
        } else {
            let applicationSupport = (try? fileManager.url(
                for: .applicationSupportDirectory,
                in: .userDomainMask,
                appropriateFor: nil,
                create: true
            )) ?? fileManager.temporaryDirectory
            root = applicationSupport.appendingPathComponent("Ameme", isDirectory: true)
        }
        self.rootDirectory = root
        self.fileURL = root.appendingPathComponent("events.enc")
        self.mediaDirectory = root.appendingPathComponent("media", isDirectory: true)
        load()
    }

    public var mode: ExperienceMode {
        switch storageState {
        case .loading: return .loading
        case .deleted: return .empty
        case .recoverableError: return .recoverableError
        case .ready:
            return ExperienceMode.ready.resolvedFor(
                eventCount: events.count,
                deriveFromEvents: true
            )
        }
    }

    public var isLocalSpaceDeleted: Bool {
        deletionTombstones.contains {
            $0.spaceID == SourceObject.personalSpaceID &&
                $0.objectType == DeletionTombstone.spaceObjectType &&
                $0.objectID == SourceObject.personalSpaceID
        }
    }

    @discardableResult
    public func addText(_ text: String) -> MemoryEvent? {
        let cleaned = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleaned.isEmpty else { return nil }
        let event = add(
            title: Self.title(for: cleaned),
            detail: cleaned,
            kind: .text,
            sourceLabel: "主动输入",
            factStatus: .userAsserted
        )
        return storageState == .ready ? event : nil
    }

    @discardableResult
    public func add(
        title: String,
        detail: String,
        kind: CaptureKind,
        sourceLabel: String,
        sourceLocator: String? = nil,
        factStatus: FactStatus = .userAsserted,
        userWords: String? = nil,
        localDate: Date = .now,
        time: Date? = .now,
        eventType: EventType = .experience,
        evidenceState: EvidenceState = .userAsserted,
        sensitivity: Sensitivity = .personal,
        importance: Int = 50
    ) -> MemoryEvent {
        let previousEvents = events
        let previousSources = sourceObjects
        let previousSourceLinks = eventSourceLinks
        let event = MemoryEvent(
            localDate: localDate,
            time: time,
            title: title,
            detail: detail,
            factStatus: factStatus,
            sourceLabel: sourceLabel,
            sourceLocator: sourceLocator,
            captureKind: kind,
            userWords: userWords,
            eventType: eventType,
            evidenceState: evidenceState,
            sensitivity: sensitivity,
            importance: importance
        )
        events.append(event)
        if sourceLocator != nil {
            registerCapturedSource(for: event)
        }
        sortEvents()
        if !persist() {
            events = previousEvents
            sourceObjects = previousSources
            eventSourceLinks = previousSourceLinks
        }
        return event
    }

    /// Commits a source import as one local transaction. If persistence fails, no draft
    /// remains visible, matching the all-or-nothing semantics of the Android adapter.
    @discardableResult
    public func addBatch(_ drafts: [MemoryEventDraft]) -> [MemoryEvent]? {
        guard !drafts.isEmpty else { return [] }
        var knownLocators = Set(events.compactMap(\.sourceLocator))
        let acceptedDrafts = drafts.filter { draft in
            guard let locator = draft.sourceLocator else { return true }
            guard knownLocators.insert(locator).inserted else { return false }
            return true
        }
        guard !acceptedDrafts.isEmpty else { return [] }
        let previousEvents = events
        let previousSources = sourceObjects
        let previousSourceLinks = eventSourceLinks
        let added = acceptedDrafts.map { draft in
            MemoryEvent(
                localDate: draft.localDate,
                time: draft.time,
                title: draft.title,
                detail: draft.detail,
                factStatus: draft.factStatus,
                sourceLabel: draft.sourceLabel,
                sourceLocator: draft.sourceLocator,
                captureKind: draft.captureKind,
                userWords: draft.userWords,
                eventType: draft.eventType,
                evidenceState: draft.evidenceState,
                sensitivity: draft.sensitivity,
                importance: draft.importance
            )
        }
        events.append(contentsOf: added)
        added.filter { $0.sourceLocator != nil }.forEach(registerCapturedSource)
        sortEvents()
        guard persist() else {
            events = previousEvents
            sourceObjects = previousSources
            eventSourceLinks = previousSourceLinks
            return nil
        }
        return added
    }

    @discardableResult
    public func addMedia(_ data: Data, kind: CaptureKind, fileExtension: String) -> MemoryEvent {
        let locator = persistMedia(data, fileExtension: fileExtension)
        let isSaved = locator != nil
        let title: String
        switch kind {
        case .photo: title = "选择了一张照片"
        case .voice: title = "保存了一段语音"
        case .text: title = "保存了一条文字"
        case .importFile: title = "收到一份分享文档"
        }
        let detail: String
        if isDemoMode {
            detail = kind == .photo
                ? "演示数据：照片来源已模拟；不会写入本机媒体目录。"
                : kind == .importFile
                    ? "演示数据：分享文档来源已模拟；不会写入本机媒体目录。"
                    : "演示数据：语音来源已模拟；不会写入本机媒体目录。"
        } else {
            switch kind {
            case .photo:
                detail = isSaved ? "照片已由你主动选择并保存为本机记录。" : "照片已选择，但本机媒体文件暂未写入；事件说明仍保留。"
            case .voice:
                detail = isSaved ? "语音已由你主动录制或选择并保存为本机记录，转写可在后续处理。" : "语音已选择，但本机媒体文件暂未写入；事件说明仍保留。"
            case .importFile:
                detail = isSaved ? "分享文档已保存为本机加密来源，整理尚未完成。" : "分享文档已选择，但本机媒体文件暂未写入；事件说明仍保留。"
            case .text:
                detail = "文字来源应通过文字入口保存。"
            }
        }
        let event = add(
            title: title,
            detail: detail,
            kind: kind,
            sourceLabel: isDemoMode
                ? "演示来源"
                : (kind == .photo ? "系统照片选择器" : kind == .importFile ? "系统分享" : "系统音频选择器"),
            sourceLocator: isDemoMode ? nil : locator,
            factStatus: kind == .voice || kind == .importFile ? .processing : .confirmed,
            sensitivity: .confidential
        )
        if storageState != .ready, locator != nil { removeOwnedMedia(for: event) }
        return event
    }

    /// Stores only the opaque Photos library identifier. The selected original remains in Photos;
    /// Ameme does not copy it into its own media directory by default.
    @discardableResult
    public func addPhotoReference(_ identifier: String?) -> MemoryEvent? {
        guard let identifier = identifier?.trimmingCharacters(in: .whitespacesAndNewlines), !identifier.isEmpty else {
            return nil
        }
        let event = add(
            title: "选择了一张照片",
            detail: isDemoMode
                ? "演示数据：照片来源已模拟；不会读取或保存真实照片。"
                : "照片由你主动选择；本机只保存照片引用，不复制系统照片原图。",
            kind: .photo,
            sourceLabel: isDemoMode ? "演示照片来源" : "系统照片选择器",
            sourceLocator: isDemoMode ? nil : "photos://\(identifier)",
            factStatus: .confirmed,
            sensitivity: .confidential
        )
        return storageState == .ready ? event : nil
    }

    @discardableResult
    public func addImportedText(_ text: String, sourceLabel: String = "文件导入") -> MemoryEvent? {
        let cleaned = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleaned.isEmpty else { return nil }
        let event = add(
            title: Self.title(for: cleaned),
            detail: cleaned,
            kind: .importFile,
            sourceLabel: sourceLabel,
            factStatus: .userAsserted
        )
        return storageState == .ready ? event : nil
    }

    public func events(on date: Date) -> [MemoryEvent] {
        events.filter { Calendar.current.isDate($0.localDate, inSameDayAs: date) }
    }

    public func search(query: String, date: Date? = nil) -> [MemoryEvent] {
        search(query: query, startDate: date, endDate: date)
    }

    public func search(query: String, startDate: Date? = nil, endDate: Date? = nil) -> [MemoryEvent] {
        let terms = Self.searchTerms(query)
        return events.filter { event in
            let calendar = Calendar.current
            let day = calendar.startOfDay(for: event.localDate)
            let startsAfter = startDate.map { day >= calendar.startOfDay(for: $0) } ?? true
            let endsBefore = endDate.map { day <= calendar.startOfDay(for: $0) } ?? true
            let searchable = [event.title, event.detail, event.sourceLabel, event.userWords ?? ""].joined(separator: " ")
            let queryMatches = terms.allSatisfy { searchable.localizedCaseInsensitiveContains($0) }
            return startsAfter && endsBefore && queryMatches
        }
    }

    public func event(id: UUID) -> MemoryEvent? {
        events.first { $0.id == id }
    }

    public func coverageDay(id: String) -> PersistedCoverageDay? {
        coverageDays.first { $0.dayID == id }
    }

    /// Persists a compiled coverage snapshot without creating an Event, touching a
    /// DayLedger, or changing the current event projection. Terminal candidate
    /// lifecycle states survive a recompile so detached Events cannot resurrect.
    @discardableResult
    public func persistCoverageCompilation(
        _ compilation: CoverageCompilation,
        registryVersion: String,
        compiledAt: String
    ) -> Bool {
        guard !isDemoMode else { return false }
        let normalizedRegistry = registryVersion.trimmingCharacters(in: .whitespacesAndNewlines)
        let normalizedCompiledAt = compiledAt.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalizedRegistry.isEmpty, !normalizedCompiledAt.isEmpty else { return false }
        let incomingSourceIDs = Set(
            compilation.observations.flatMap(\.sourceObjectIDs) +
                compilation.candidateEvents.flatMap(\.sourceObjectIDs)
        )
        guard incomingSourceIDs.allSatisfy({ sourceObjectID in
            !deletionTombstones.contains {
                $0.objectType == DeletionTombstone.sourceObjectType &&
                    $0.objectID == sourceObjectID
            } &&
                sourceObjects.first { $0.sourceObjectID == sourceObjectID }?.state != .deleted &&
                isCompatibleCoverageSource(sourceObjectID)
        }) else {
            return false
        }

        var replacement = PersistedCoverageDay(
            compilation: compilation,
            registryVersion: normalizedRegistry,
            compiledAt: normalizedCompiledAt
        )
        let previousDays = coverageDays
        let previousSources = sourceObjects
        if let existing = coverageDay(id: compilation.dayID) {
            guard existing.ownerID == compilation.ownerID,
                  existing.spaceID == compilation.spaceID,
                  existing.localDate == compilation.localDate else {
                return false
            }
            let existingByID = Dictionary(
                uniqueKeysWithValues: existing.candidates.map { ($0.candidate.candidateID, $0) }
            )
            replacement.candidates = replacement.candidates.map { candidate in
                guard let prior = existingByID[candidate.candidate.candidateID],
                      prior.state != .open else {
                    return candidate
                }
                return prior
            }
            let replacementIDs = Set(replacement.candidates.map(\.candidate.candidateID))
            replacement.candidates.append(
                contentsOf: existing.candidates.filter {
                    $0.state != .open && !replacementIDs.contains($0.candidate.candidateID)
                }
            )
        }
        coverageDays.removeAll { $0.dayID == compilation.dayID }
        coverageDays.append(replacement)
        coverageDays.sort { $0.dayID < $1.dayID }
        let createdAt = Self.epochMilliseconds(.now)
        for sourceObjectID in incomingSourceIDs {
            guard registerCoverageSource(
                sourceObjectID: sourceObjectID,
                eventID: nil,
                createdRevision: nil,
                createdAtEpochMilliseconds: createdAt
            ) else {
                coverageDays = previousDays
                sourceObjects = previousSources
                return false
            }
        }
        guard persist() else {
            coverageDays = previousDays
            sourceObjects = previousSources
            return false
        }
        return true
    }

    /// Explicitly accepts one compiled candidate and commits the Event, consumed
    /// candidate state, and lineage link in the same encrypted envelope write.
    @discardableResult
    public func acceptCoverageCandidate(
        _ acceptance: CoverageCandidateAcceptance
    ) throws -> MemoryEvent {
        guard !isDemoMode else {
            throw CoveragePersistenceError.invalid("coverage acceptance is unavailable in demo mode")
        }
        guard !acceptance.detail.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !acceptance.sourceLabel.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !acceptance.linkedAt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              (0...100).contains(acceptance.importance) else {
            throw CoveragePersistenceError.invalid("candidate acceptance is invalid")
        }
        guard let dayIndex = coverageDays.firstIndex(where: { $0.dayID == acceptance.dayID }) else {
            throw CoveragePersistenceError.dayNotFound
        }
        guard let candidateIndex = coverageDays[dayIndex].candidates.firstIndex(where: {
            $0.candidate.candidateID == acceptance.candidateID
        }) else {
            throw CoveragePersistenceError.candidateNotFound
        }
        let persistedCandidate = coverageDays[dayIndex].candidates[candidateIndex]
        guard persistedCandidate.state == .open else {
            throw CoveragePersistenceError.candidateNotOpen
        }
        let candidate = persistedCandidate.candidate
        guard !candidate.sourceObjectIDs.isEmpty else {
            throw CoveragePersistenceError.invalid("candidate has no source lineage")
        }
        let fieldProvenance = try validatedFieldProvenance(
            candidate: candidate,
            acceptance: acceptance
        )
        let previousEvents = events
        let previousDays = coverageDays
        let previousLinks = coverageEventLinks
        let previousSources = sourceObjects
        let previousSourceLinks = eventSourceLinks
        let previousFieldEvidence = eventFieldEvidence
        let previousUserConfirmations = eventUserConfirmations
        let statuses = Self.eventStatuses(for: candidate.factStatus, mode: acceptance.mode)
        let event = MemoryEvent(
            localDate: acceptance.localDate,
            time: acceptance.time,
            title: candidate.title,
            detail: acceptance.detail.trimmingCharacters(in: .whitespacesAndNewlines),
            factStatus: statuses.factStatus,
            sourceLabel: acceptance.sourceLabel.trimmingCharacters(in: .whitespacesAndNewlines),
            captureKind: acceptance.captureKind,
            userWords: acceptance.userWords?.trimmingCharacters(in: .whitespacesAndNewlines),
            eventType: Self.eventType(for: candidate.eventType),
            evidenceState: statuses.evidenceState,
            sensitivity: acceptance.sensitivity,
            importance: acceptance.importance
        )
        events.append(event)
        sortEvents()
        coverageDays[dayIndex].candidates[candidateIndex].state = .consumed
        coverageDays[dayIndex].candidates[candidateIndex].updatedAt = acceptance.linkedAt
        coverageEventLinks.append(
            CoverageEventLink(
                dayID: coverageDays[dayIndex].dayID,
                spaceID: coverageDays[dayIndex].spaceID,
                candidateID: candidate.candidateID,
                eventID: event.id,
                createdRevision: event.revision,
                sourceObjectIDs: candidate.sourceObjectIDs,
                linkedAt: acceptance.linkedAt
            )
        )
        let createdAt = Self.epochMilliseconds(.now)
        for sourceObjectID in candidate.sourceObjectIDs {
            guard registerCoverageSource(
                sourceObjectID: sourceObjectID,
                eventID: event.id,
                createdRevision: event.revision,
                createdAtEpochMilliseconds: createdAt
            ) else {
                events = previousEvents
                coverageDays = previousDays
                coverageEventLinks = previousLinks
                sourceObjects = previousSources
                eventSourceLinks = previousSourceLinks
                eventFieldEvidence = previousFieldEvidence
                eventUserConfirmations = previousUserConfirmations
                throw CoveragePersistenceError.invalid("coverage source identity is incompatible")
            }
        }
        for (field, sourceObjectIDs) in fieldProvenance {
            for sourceObjectID in sourceObjectIDs {
                eventFieldEvidence.append(
                    EventFieldEvidence(
                        eventID: event.id,
                        eventRevision: event.revision,
                        field: field,
                        sourceObjectID: sourceObjectID,
                        createdAtEpochMilliseconds: createdAt
                    )
                )
            }
        }
        if acceptance.mode == .userConfirmed {
            eventUserConfirmations.append(
                EventUserConfirmation(
                    confirmationID: "confirm_\(UUID().uuidString.lowercased())",
                    eventID: event.id,
                    eventRevision: event.revision,
                    kind: .coverageAcceptance,
                    confirmedFields: Set(candidate.observedFields),
                    completeFieldSet: true,
                    confirmedAtEpochMilliseconds: createdAt
                )
            )
        }
        guard persist() else {
            events = previousEvents
            coverageDays = previousDays
            coverageEventLinks = previousLinks
            sourceObjects = previousSources
            eventSourceLinks = previousSourceLinks
            eventFieldEvidence = previousFieldEvidence
            eventUserConfirmations = previousUserConfirmations
            throw CoveragePersistenceError.persistenceFailed
        }
        return event
    }

    @discardableResult
    public func dismissCoverageCandidate(
        dayID: String,
        candidateID: String,
        updatedAt: String
    ) -> Bool {
        guard !isDemoMode,
              !updatedAt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              let dayIndex = coverageDays.firstIndex(where: { $0.dayID == dayID }),
              let candidateIndex = coverageDays[dayIndex].candidates.firstIndex(where: {
                  $0.candidate.candidateID == candidateID && $0.state == .open
              }) else {
            return false
        }
        let previousDays = coverageDays
        coverageDays[dayIndex].candidates[candidateIndex].state = .dismissed
        coverageDays[dayIndex].candidates[candidateIndex].updatedAt = updatedAt
        guard persist() else {
            coverageDays = previousDays
            return false
        }
        return true
    }

    /// Compiles one exact current Event revision into a long-term Memory candidate.
    /// The proposal never becomes active without a later explicit user confirmation.
    @discardableResult
    public func proposeLongTermMemory(
        _ proposal: LongTermMemoryProposal
    ) throws -> LongTermMemoryRecord {
        guard !isDemoMode else {
            throw LongTermMemoryError.invalid("long-term Memory is unavailable in demo mode")
        }
        let summary = proposal.valueSummary.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !proposal.ownerID.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              proposal.spaceID == "space_personal",
              proposal.expectedEventRevision >= 1,
              !summary.isEmpty,
              summary.count <= 4_096,
              proposal.validUntil == nil || proposal.validUntil! > proposal.validFrom else {
            throw LongTermMemoryError.invalid("long-term Memory proposal is invalid")
        }
        if let existing = longTermMemories.first(where: {
            $0.spaceID == proposal.spaceID &&
            $0.sourceEventID == proposal.sourceEventID &&
            $0.sourceEventRevision == proposal.expectedEventRevision &&
            $0.type == proposal.type
        }) {
            return existing
        }
        guard let event = event(id: proposal.sourceEventID) else {
            throw LongTermMemoryError.sourceEventNotFound
        }
        guard event.revision == proposal.expectedEventRevision else {
            throw LongTermMemoryError.sourceEventRevisionChanged
        }
        let state: LongTermMemoryState = if
            event.evidenceState == .inferred ||
            event.sensitivity == .restricted ||
            proposal.type.requiresUserConfirmation {
            .candidateUserConfirmationRequired
        } else {
            .eligibleForMemoryCompiler
        }
        let record = LongTermMemoryRecord(
            ownerID: proposal.ownerID.trimmingCharacters(in: .whitespacesAndNewlines),
            spaceID: proposal.spaceID,
            type: proposal.type,
            valueSummary: summary,
            sourceEventID: proposal.sourceEventID,
            sourceEventRevision: proposal.expectedEventRevision,
            evidenceState: event.evidenceState,
            sensitivity: event.sensitivity,
            state: state,
            validFrom: proposal.validFrom,
            validUntil: proposal.validUntil,
            createdAt: proposal.proposedAt,
            updatedAt: proposal.proposedAt,
            revisions: [
                LongTermMemoryRevision(
                    revision: 1,
                    reason: .compilerProposal,
                    state: state,
                    changedAt: proposal.proposedAt
                ),
            ]
        )
        let previous = longTermMemories
        longTermMemories.append(record)
        guard persist() else {
            longTermMemories = previous
            throw LongTermMemoryError.persistenceFailed
        }
        return record
    }

    /// Explicit user confirmation is the only transition into active long-term
    /// Memory. An optional replacement supersedes one active Memory of the same type.
    @discardableResult
    public func confirmLongTermMemory(
        _ confirmation: LongTermMemoryConfirmation
    ) throws -> LongTermMemoryRecord {
        guard !isDemoMode,
              confirmation.supersedesMemoryID != confirmation.memoryID else {
            throw LongTermMemoryError.invalid("long-term Memory confirmation is invalid")
        }
        guard let candidateIndex = longTermMemories.firstIndex(where: {
            $0.memoryID == confirmation.memoryID
        }) else {
            throw LongTermMemoryError.memoryNotFound
        }
        let candidate = longTermMemories[candidateIndex]
        guard candidate.state == .eligibleForMemoryCompiler ||
                candidate.state == .candidateUserConfirmationRequired else {
            throw LongTermMemoryError.memoryNotConfirmable
        }
        guard let event = event(id: candidate.sourceEventID) else {
            throw LongTermMemoryError.sourceEventNotFound
        }
        guard event.revision == candidate.sourceEventRevision else {
            throw LongTermMemoryError.sourceEventRevisionChanged
        }
        var supersededIndex: Int?
        if let previousID = confirmation.supersedesMemoryID {
            guard let index = longTermMemories.firstIndex(where: { $0.memoryID == previousID }),
                  longTermMemories[index].state == .active,
                  longTermMemories[index].type == candidate.type else {
                throw LongTermMemoryError.supersededMemoryInvalid
            }
            supersededIndex = index
        }

        let previous = longTermMemories
        if let index = supersededIndex {
            longTermMemories[index].state = .superseded
            longTermMemories[index].supersededByMemoryID = candidate.memoryID
            longTermMemories[index].revision += 1
            longTermMemories[index].updatedAt = confirmation.confirmedAt
            longTermMemories[index].revisions.append(
                LongTermMemoryRevision(
                    revision: longTermMemories[index].revision,
                    reason: .userSupersede,
                    state: .superseded,
                    changedAt: confirmation.confirmedAt
                )
            )
        }
        longTermMemories[candidateIndex].state = .active
        longTermMemories[candidateIndex].confirmedAt = confirmation.confirmedAt
        longTermMemories[candidateIndex].supersedesMemoryID = confirmation.supersedesMemoryID
        longTermMemories[candidateIndex].revision += 1
        longTermMemories[candidateIndex].updatedAt = confirmation.confirmedAt
        longTermMemories[candidateIndex].revisions.append(
            LongTermMemoryRevision(
                revision: longTermMemories[candidateIndex].revision,
                reason: .userConfirm,
                state: .active,
                changedAt: confirmation.confirmedAt
            )
        )
        guard persist() else {
            longTermMemories = previous
            throw LongTermMemoryError.persistenceFailed
        }
        return longTermMemories[candidateIndex]
    }

    public func longTermMemory(id: UUID) -> LongTermMemoryRecord? {
        longTermMemories.first { $0.memoryID == id }
    }

    public func visibleLongTermMemories(at date: Date = .now) -> [LongTermMemoryRecord] {
        longTermMemories
            .filter { $0.isVisible(at: date) }
            .sorted {
                if $0.updatedAt != $1.updatedAt { return $0.updatedAt > $1.updatedAt }
                return $0.memoryID.uuidString > $1.memoryID.uuidString
            }
    }

    /// Builds one bounded, local-only context for an explicit reuse scenario. Exact references
    /// remain in memory for at most fifteen minutes; persistence receives salted digests only.
    public func buildReuseContext(_ request: ReuseRequest) throws -> ReuseContext {
        guard storageState == .ready, !isDemoMode else { throw ReuseError.unavailable }
        let terms = Self.searchTerms(request.query)
        guard request.spaceID == "space_personal",
              request.query.count <= 1_024,
              request.limit >= 1, request.limit <= 50,
              terms.count <= 16,
              request.startDate == nil || request.endDate == nil ||
                Calendar.current.startOfDay(for: request.endDate!) >=
                    Calendar.current.startOfDay(for: request.startDate!) else {
            throw ReuseError.invalidRequest("reuse request is outside the bounded local contract")
        }
        switch request.intent {
        case .historicalSearch:
            guard !terms.isEmpty || request.startDate != nil || request.endDate != nil else {
                throw ReuseError.invalidRequest("historical search requires keywords or a date")
            }
        case .projectResume:
            guard !terms.isEmpty else {
                throw ReuseError.invalidRequest("project resume requires explicit user keywords")
            }
        case .preMeetingContext:
            guard !terms.isEmpty || request.meetingAnchorDate != nil else {
                throw ReuseError.invalidRequest(
                    "pre-meeting context requires keywords or a user-selected date"
                )
            }
        case .decisionCommitmentRecall:
            break
        }

        let effectiveStart = request.startDate ??
            (request.intent == .preMeetingContext && request.endDate == nil
                ? request.meetingAnchorDate : nil)
        let effectiveEnd = request.endDate ??
            (request.intent == .preMeetingContext && request.startDate == nil
                ? request.meetingAnchorDate : nil)
        let candidateEvents = search(
            query: request.query,
            startDate: effectiveStart,
            endDate: effectiveEnd
        )
        let candidateMemories = visibleLongTermMemories(at: request.requestedAt).filter { memory in
            let typeAllowed: Bool
            switch request.intent {
            case .preMeetingContext, .decisionCommitmentRecall:
                typeAllowed = memory.type == .decision || memory.type == .commitment
            case .historicalSearch, .projectResume:
                typeAllowed = true
            }
            let queryMatches = terms.isEmpty || terms.allSatisfy {
                memory.valueSummary.localizedCaseInsensitiveContains($0)
            }
            return typeAllowed && queryMatches
        }
        var exclusions: Set<ReuseExclusion> = []
        if candidateEvents.contains(where: { $0.sensitivity == .restricted }) ||
            candidateMemories.contains(where: { $0.sensitivity == .restricted }) {
            exclusions.insert(.restricted)
        }
        let eventReferences = candidateEvents
            .filter {
                $0.sensitivity != .restricted &&
                    (request.intent != .decisionCommitmentRecall || $0.eventType == .decision)
            }
            .map { event in
                ReuseReference(
                    objectType: .event,
                    objectID: event.id,
                    revision: event.revision,
                    localDate: event.localDate,
                    sensitivity: event.sensitivity,
                    selectionReason: request.intent == .preMeetingContext &&
                        request.meetingAnchorDate != nil
                        ? .meetingAnchor
                        : (!terms.isEmpty ? .keywordMatch : .dateMatch),
                    sourceEventID: nil,
                    sourceEventRevision: nil
                )
            }
        let memoryReferences = candidateMemories.compactMap { memory -> ReuseReference? in
            guard memory.sensitivity != .restricted else { return nil }
            guard let source = event(id: memory.sourceEventID),
                  source.revision == memory.sourceEventRevision,
                  source.sensitivity != .restricted else {
                exclusions.insert(.invalidated)
                return nil
            }
            let reason: ReuseSelectionReason
            switch memory.type {
            case .decision: reason = .activeDecision
            case .commitment: reason = .activeCommitment
            default: reason = terms.isEmpty ? .dateMatch : .keywordMatch
            }
            return ReuseReference(
                objectType: .longTermMemory,
                objectID: memory.memoryID,
                revision: memory.revision,
                localDate: source.localDate,
                sensitivity: memory.sensitivity,
                selectionReason: reason,
                sourceEventID: memory.sourceEventID,
                sourceEventRevision: memory.sourceEventRevision
            )
        }
        let ordered = request.intent == .historicalSearch
            ? eventReferences + memoryReferences
            : memoryReferences + eventReferences
        var seen: Set<String> = []
        let uniqueReferences = ordered.filter { reference in
            seen.insert("\(reference.objectType.rawValue)\u{1f}\(reference.objectID.uuidString)")
                .inserted
        }
        let boundedReferences = Array(uniqueReferences.prefix(request.limit))
        let context = ReuseContext(
            attemptID: UUID(),
            intent: request.intent,
            rangeState: boundedReferences.isEmpty
                ? .empty
                : (uniqueReferences.count > request.limit
                    ? .partialForLocalScope : .completeForLocalScope),
            references: boundedReferences,
            exclusions: exclusions,
            createdAt: request.requestedAt,
            expiresAt: request.requestedAt.addingTimeInterval(15 * 60)
        )
        let previous = reuseAttempts
        reuseAttempts.append(ReuseTelemetry.attempt(from: context))
        guard persist() else {
            reuseAttempts = previous
            throw ReuseError.persistenceFailed
        }
        return context
    }

    /// Revalidates every exact revision and policy boundary immediately before content resolution.
    /// A stale context is narrowed; it is never silently rebuilt from new content.
    public func revalidateReuseContext(_ context: ReuseContext, at date: Date) -> ReuseContext {
        guard context.expiresAt > date else {
            return ReuseContext(
                attemptID: context.attemptID,
                intent: context.intent,
                rangeState: .empty,
                references: [],
                exclusions: context.exclusions.union([.expired]),
                createdAt: context.createdAt,
                expiresAt: context.expiresAt
            )
        }
        var exclusions = context.exclusions
        let valid = context.references.filter { reference in
            switch reference.objectType {
            case .event:
                guard let current = event(id: reference.objectID) else {
                    exclusions.insert(.deleted)
                    return false
                }
                guard current.revision == reference.revision else {
                    exclusions.insert(.invalidated)
                    return false
                }
                guard current.sensitivity != .restricted else {
                    exclusions.insert(.restricted)
                    return false
                }
                return true
            case .longTermMemory:
                guard let memory = longTermMemory(id: reference.objectID),
                      let source = event(id: memory.sourceEventID) else {
                    exclusions.insert(.deleted)
                    return false
                }
                guard memory.revision == reference.revision,
                      memory.isVisible(at: date),
                      source.revision == memory.sourceEventRevision else {
                    exclusions.insert(.invalidated)
                    return false
                }
                guard memory.sensitivity != .restricted, source.sensitivity != .restricted else {
                    exclusions.insert(.restricted)
                    return false
                }
                return true
            }
        }
        return ReuseContext(
            attemptID: context.attemptID,
            intent: context.intent,
            rangeState: valid.isEmpty
                ? .empty
                : (context.rangeState == .partialForLocalScope
                    ? .partialForLocalScope : .completeForLocalScope),
            references: valid,
            exclusions: exclusions,
            createdAt: context.createdAt,
            expiresAt: context.expiresAt
        )
    }

    /// Revalidates and resolves content synchronously on the store's MainActor. No mutation can
    /// interleave between the exact-revision check and content resolution.
    public func resolveReuseContext(
        _ context: ReuseContext,
        at date: Date
    ) throws -> ResolvedReuseContext {
        let valid = revalidateReuseContext(context, at: date)
        var items: [ResolvedReuseItem] = []
        for reference in valid.references {
            switch reference.objectType {
            case .event:
                guard let source = event(id: reference.objectID),
                      source.revision == reference.revision else {
                    throw ReuseError.unavailable
                }
                items.append(
                    ResolvedReuseItem(reference: reference, sourceEvent: source)
                )
            case .longTermMemory:
                guard let memory = longTermMemory(id: reference.objectID),
                      memory.revision == reference.revision,
                      let source = event(id: memory.sourceEventID),
                      source.revision == reference.sourceEventRevision else {
                    throw ReuseError.unavailable
                }
                items.append(
                    ResolvedReuseItem(
                        reference: reference,
                        sourceEvent: source,
                        memorySummary: memory.valueSummary,
                        memoryType: memory.type
                    )
                )
            }
        }
        return ResolvedReuseContext(context: valid, items: items)
    }

    @discardableResult
    public func recordReuseOutcome(_ submission: ReuseOutcomeSubmission) throws -> Bool {
        guard let attempt = reuseAttempts.first(where: {
            $0.attemptID == submission.attemptID
        }) else {
            throw ReuseError.attemptNotFound
        }
        guard submission.submittedAt >= attempt.createdAt else {
            throw ReuseError.invalidRequest("reuse outcome predates its attempt")
        }
        guard !reuseOutcomes.contains(where: { $0.attemptID == submission.attemptID }) else {
            throw ReuseError.outcomeAlreadyRecorded
        }
        let previous = reuseOutcomes
        reuseOutcomes.append(
            ReuseOutcomeRecord(
                attemptID: submission.attemptID,
                outcome: submission.outcome,
                userAction: submission.userAction,
                submittedAt: submission.submittedAt
            )
        )
        guard persist() else {
            reuseOutcomes = previous
            throw ReuseError.persistenceFailed
        }
        return true
    }

    public func reuseTelemetryAggregates(since date: Date) -> [ReuseTelemetryAggregate] {
        let outcomesByAttempt = Dictionary(
            uniqueKeysWithValues: reuseOutcomes.map { ($0.attemptID, $0) }
        )
        var counts: [ReuseAggregateKey: Int] = [:]
        for attempt in reuseAttempts where attempt.createdAt >= date {
            let outcome = outcomesByAttempt[attempt.attemptID]
            let key = ReuseAggregateKey(
                intent: attempt.intent,
                outcome: outcome?.outcome,
                userAction: outcome?.userAction,
                resultCountBucket: attempt.resultCountBucket
            )
            counts[key, default: 0] += 1
        }
        return counts.map { key, count in
            ReuseTelemetryAggregate(
                intent: key.intent,
                outcome: key.outcome,
                userAction: key.userAction,
                resultCountBucket: key.resultCountBucket,
                attemptCount: count
            )
        }.sorted {
            let left = "\($0.intent.rawValue)|\($0.outcome?.rawValue ?? "")|" +
                "\($0.userAction?.rawValue ?? "")|\($0.resultCountBucket.rawValue)"
            let right = "\($1.intent.rawValue)|\($1.outcome?.rawValue ?? "")|" +
                "\($1.userAction?.rawValue ?? "")|\($1.resultCountBucket.rawValue)"
            return left < right
        }
    }

    public func helpfulReuseCount(since date: Date) -> Int {
        return reuseOutcomes.count {
            $0.outcome == .useful && $0.submittedAt >= date
        }
    }

    @discardableResult
    public func addUserWords(_ text: String, to id: UUID) -> Bool {
        guard let index = events.firstIndex(where: { $0.id == id }) else { return false }
        let cleaned = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleaned.isEmpty else { return false }
        let previous = events[index]
        let previousMemories = longTermMemories
        let previousFieldEvidence = eventFieldEvidence
        let previousUserConfirmations = eventUserConfirmations
        let completeBefore = completeUserConfirmationFields(
            eventID: id,
            eventRevision: previous.revision
        )
        let changedAt = Date.now
        events[index].userWords = cleaned
        events[index].revision += 1
        terminalizeEventFieldEvidence(
            eventID: id,
            deletedAtEpochMilliseconds: Self.epochMilliseconds(changedAt),
            eventRevision: previous.revision
        )
        terminalizeUserConfirmations(
            eventID: id,
            deletedAtEpochMilliseconds: Self.epochMilliseconds(changedAt),
            eventRevision: previous.revision
        )
        appendUserConfirmation(
            eventID: id,
            eventRevision: events[index].revision,
            kind: .userRevision,
            confirmedFields: completeBefore ?? [.description],
            completeFieldSet: completeBefore != nil,
            confirmedAt: changedAt
        )
        invalidateLongTermMemories(
            for: id,
            currentRevision: events[index].revision,
            reason: .eventRevisionChanged,
            changedAt: changedAt
        )
        guard persist() else {
            events[index] = previous
            longTermMemories = previousMemories
            eventFieldEvidence = previousFieldEvidence
            eventUserConfirmations = previousUserConfirmations
            return false
        }
        return true
    }

    @discardableResult
    public func updateFactStatus(_ status: FactStatus, to id: UUID) -> Bool {
        guard let index = events.firstIndex(where: { $0.id == id }) else { return false }
        let previous = events[index]
        let previousMemories = longTermMemories
        let previousFieldEvidence = eventFieldEvidence
        let previousUserConfirmations = eventUserConfirmations
        guard events[index].factStatus != status else { return true }
        let changedAt = Date.now
        let completeFields = completeSourceEvidenceFields(
            eventID: id,
            eventRevision: previous.revision
        ) ?? completeUserConfirmationFields(
            eventID: id,
            eventRevision: previous.revision
        )
        events[index].factStatus = status
        events[index].revision += 1
        terminalizeEventFieldEvidence(
            eventID: id,
            deletedAtEpochMilliseconds: Self.epochMilliseconds(changedAt),
            eventRevision: previous.revision
        )
        terminalizeUserConfirmations(
            eventID: id,
            deletedAtEpochMilliseconds: Self.epochMilliseconds(changedAt),
            eventRevision: previous.revision
        )
        if status == .confirmed {
            appendUserConfirmation(
                eventID: id,
                eventRevision: events[index].revision,
                kind: .factStatusConfirmation,
                confirmedFields: completeFields ?? [.time, .action, .description],
                completeFieldSet: completeFields != nil,
                confirmedAt: changedAt
            )
        }
        invalidateLongTermMemories(
            for: id,
            currentRevision: events[index].revision,
            reason: .eventRevisionChanged,
            changedAt: changedAt
        )
        guard persist() else {
            events[index] = previous
            longTermMemories = previousMemories
            eventFieldEvidence = previousFieldEvidence
            eventUserConfirmations = previousUserConfirmations
            return false
        }
        return true
    }

    @discardableResult
    public func delete(id: UUID) -> Bool {
        guard let deletedEvent = events.first(where: { $0.id == id }) else { return false }
        let previousEvents = events
        let previousLinks = coverageEventLinks
        let previousMemories = longTermMemories
        let previousTombstones = deletionTombstones
        let previousSourceLinks = eventSourceLinks
        let previousFieldEvidence = eventFieldEvidence
        let previousUserConfirmations = eventUserConfirmations
        let previousSources = sourceObjects
        let previousRecords = sourceDeletionRecords
        let linkedSourceIDs = Set(eventSourceLinks.filter {
            $0.eventID == id && $0.state == .active
        }.map(\.sourceObjectID))
        let deletedAt = Date.now
        for sourceIndex in sourceObjects.indices where
            linkedSourceIDs.contains(sourceObjects[sourceIndex].sourceObjectID) &&
            sourceObjects[sourceIndex].rawOwnership == .appOwnedEncrypted &&
            sourceObjects[sourceIndex].rawState == .available {
            sourceObjects[sourceIndex].rawState = .pendingCleanup
            appendSourceDeletionRecord(
                sourceObjectID: sourceObjects[sourceIndex].sourceObjectID,
                operation: .rawOnly,
                status: .pendingCleanup,
                affectedEventCount: 1,
                rawDigestOnly: sourceObjects[sourceIndex].rawCiphertextSHA256,
                at: deletedAt
            )
        }
        guard applyEventDeletion(id: id, reason: "user_delete", deletedAt: deletedAt) else {
            sourceObjects = previousSources
            sourceDeletionRecords = previousRecords
            return false
        }
        guard persist() else {
            events = previousEvents
            coverageEventLinks = previousLinks
            longTermMemories = previousMemories
            deletionTombstones = previousTombstones
            eventSourceLinks = previousSourceLinks
            eventFieldEvidence = previousFieldEvidence
            eventUserConfirmations = previousUserConfirmations
            sourceObjects = previousSources
            sourceDeletionRecords = previousRecords
            return false
        }
        for sourceID in linkedSourceIDs {
            guard let source = sourceObjects.first(where: {
                $0.sourceObjectID == sourceID && $0.rawState == .pendingCleanup
            }) else { continue }
            if removeOwnedRaw(at: source.sourceLocator) {
                _ = finalizeRawCleanup(
                    sourceObjectID: sourceID,
                    operation: .rawOnly,
                    affectedEventCount: 1,
                    completedAt: deletedAt
                )
            }
        }
        // Legacy/unlinked events retain the pre-v6 cleanup behavior.
        if linkedSourceIDs.isEmpty { removeOwnedMedia(for: deletedEvent) }
        return true
    }

    public func sourceObjects(for eventID: UUID) -> [SourceObject] {
        let sourceIDs = Set(eventSourceLinks.filter {
            $0.eventID == eventID
        }.map(\.sourceObjectID))
        return sourceObjects
            .filter { sourceIDs.contains($0.sourceObjectID) }
            .sorted { $0.sourceObjectID < $1.sourceObjectID }
    }

    @discardableResult
    public func deleteRawOnly(
        sourceObjectID: String,
        requestedAt: Date = .now
    ) -> SourceDeletionResult {
        guard let sourceIndex = sourceObjects.firstIndex(where: {
            $0.sourceObjectID == sourceObjectID
        }) else {
            return persistTerminalDeletionAttempt(
                sourceObjectID: sourceObjectID,
                operation: .rawOnly,
                status: .notFound,
                affectedEventCount: 0,
                rawDigestOnly: nil,
                requestedAt: requestedAt
            )
        }
        let source = sourceObjects[sourceIndex]
        switch source.rawOwnership {
        case .externalNotOwned:
            return persistTerminalDeletionAttempt(
                sourceObjectID: sourceObjectID,
                operation: .rawOnly,
                status: .externalNotOwned,
                affectedEventCount: 0,
                rawDigestOnly: nil,
                requestedAt: requestedAt
            )
        case .noRaw:
            return persistTerminalDeletionAttempt(
                sourceObjectID: sourceObjectID,
                operation: .rawOnly,
                status: .noRaw,
                affectedEventCount: 0,
                rawDigestOnly: nil,
                requestedAt: requestedAt
            )
        case .appOwnedEncrypted:
            if source.rawState == .deleted {
                return persistTerminalDeletionAttempt(
                    sourceObjectID: sourceObjectID,
                    operation: .rawOnly,
                    status: .completed,
                    affectedEventCount: 0,
                    rawDigestOnly: source.rawCiphertextSHA256,
                    requestedAt: requestedAt
                )
            }
        }

        let previousSources = sourceObjects
        let previousEvents = events
        let previousMemories = longTermMemories
        let previousFieldEvidence = eventFieldEvidence
        let previousUserConfirmations = eventUserConfirmations
        let previousRecords = sourceDeletionRecords
        sourceObjects[sourceIndex].rawState = .pendingCleanup
        let linkedEventIDs = Set(eventSourceLinks.filter {
            $0.sourceObjectID == sourceObjectID && $0.state == .active
        }.map(\.eventID))
        for eventIndex in events.indices where linkedEventIDs.contains(events[eventIndex].id) {
            if events[eventIndex].sourceLocator == source.sourceLocator {
                let previousRevision = events[eventIndex].revision
                let confirmations = activeUserConfirmations(
                    eventID: events[eventIndex].id,
                    eventRevision: previousRevision
                )
                events[eventIndex].sourceLocator = nil
                events[eventIndex].revision += 1
                terminalizeEventFieldEvidence(
                    eventID: events[eventIndex].id,
                    deletedAtEpochMilliseconds: Self.epochMilliseconds(requestedAt),
                    eventRevision: previousRevision
                )
                terminalizeUserConfirmations(
                    eventID: events[eventIndex].id,
                    deletedAtEpochMilliseconds: Self.epochMilliseconds(requestedAt),
                    eventRevision: previousRevision
                )
                carryUserConfirmations(
                    confirmations,
                    to: events[eventIndex].revision
                )
                invalidateLongTermMemories(
                    for: events[eventIndex].id,
                    currentRevision: events[eventIndex].revision,
                    reason: .eventRevisionChanged,
                    changedAt: requestedAt
                )
            }
        }
        appendSourceDeletionRecord(
            sourceObjectID: sourceObjectID,
            operation: .rawOnly,
            status: .pendingCleanup,
            affectedEventCount: linkedEventIDs.count,
            rawDigestOnly: source.rawCiphertextSHA256,
            at: requestedAt
        )
        guard persist() else {
            sourceObjects = previousSources
            events = previousEvents
            longTermMemories = previousMemories
            eventFieldEvidence = previousFieldEvidence
            eventUserConfirmations = previousUserConfirmations
            sourceDeletionRecords = previousRecords
            return SourceDeletionResult(
                sourceObjectID: sourceObjectID,
                operation: .rawOnly,
                status: .persistenceFailed,
                affectedEventCount: 0
            )
        }
        guard removeOwnedRaw(at: source.sourceLocator) else {
            return SourceDeletionResult(
                sourceObjectID: sourceObjectID,
                operation: .rawOnly,
                status: .pendingCleanup,
                affectedEventCount: linkedEventIDs.count
            )
        }
        return finalizeRawCleanup(
            sourceObjectID: sourceObjectID,
            operation: .rawOnly,
            affectedEventCount: linkedEventIDs.count,
            completedAt: requestedAt
        )
    }

    @discardableResult
    public func deleteSourceCascade(
        sourceObjectID: String,
        requestedAt: Date = .now
    ) -> SourceDeletionResult {
        guard let sourceIndex = sourceObjects.firstIndex(where: {
            $0.sourceObjectID == sourceObjectID
        }) else {
            return persistTerminalDeletionAttempt(
                sourceObjectID: sourceObjectID,
                operation: .sourceCascade,
                status: .notFound,
                affectedEventCount: 0,
                rawDigestOnly: nil,
                requestedAt: requestedAt
            )
        }
        if sourceObjects[sourceIndex].state == .deleted {
            return persistTerminalDeletionAttempt(
                sourceObjectID: sourceObjectID,
                operation: .sourceCascade,
                status: sourceObjects[sourceIndex].rawOwnership == .externalNotOwned
                    ? .completedLocalOnly
                    : .completed,
                affectedEventCount: 0,
                rawDigestOnly: sourceObjects[sourceIndex].rawCiphertextSHA256,
                requestedAt: requestedAt
            )
        }
        let linkedEventIDs = Set(eventSourceLinks.filter {
            $0.sourceObjectID == sourceObjectID && $0.state == .active
        }.map(\.eventID))
        let actions = Dictionary(
            uniqueKeysWithValues: linkedEventIDs.map {
                ($0, sourceCascadeAction(eventID: $0, deleting: sourceObjectID))
            }
        )
        guard actions.values.allSatisfy({ $0 != nil }) else {
            return persistTerminalDeletionAttempt(
                sourceObjectID: sourceObjectID,
                operation: .sourceCascade,
                status: .lineageUnavailable,
                affectedEventCount: 0,
                rawDigestOnly: sourceObjects[sourceIndex].rawCiphertextSHA256,
                requestedAt: requestedAt
            )
        }

        let previousEvents = events
        let previousCoverageDays = coverageDays
        let previousCoverageLinks = coverageEventLinks
        let previousMemories = longTermMemories
        let previousTombstones = deletionTombstones
        let previousSources = sourceObjects
        let previousSourceLinks = eventSourceLinks
        let previousFieldEvidence = eventFieldEvidence
        let previousUserConfirmations = eventUserConfirmations
        let previousRecords = sourceDeletionRecords
        var recomputedEventCount = 0
        var deletedEventCount = 0
        for eventID in linkedEventIDs {
            guard let action = actions[eventID] ?? nil else {
                preconditionFailure("source cascade action disappeared after validation")
            }
            switch action {
            case .delete:
                guard applyEventDeletion(
                    id: eventID,
                    reason: "source_cascade_delete",
                    deletedAt: requestedAt
                ) else {
                    events = previousEvents
                    coverageDays = previousCoverageDays
                    coverageEventLinks = previousCoverageLinks
                    longTermMemories = previousMemories
                    deletionTombstones = previousTombstones
                    sourceObjects = previousSources
                    eventSourceLinks = previousSourceLinks
                    eventFieldEvidence = previousFieldEvidence
                    eventUserConfirmations = previousUserConfirmations
                    sourceDeletionRecords = previousRecords
                    return SourceDeletionResult(
                        sourceObjectID: sourceObjectID,
                        operation: .sourceCascade,
                        status: .lineageUnavailable,
                        affectedEventCount: 0
                    )
                }
                deletedEventCount += 1
            case .recompute:
                guard recomputeEventAfterSourceDeletion(
                    eventID: eventID,
                    sourceObjectID: sourceObjectID,
                    requestedAt: requestedAt
                ) else {
                    events = previousEvents
                    coverageDays = previousCoverageDays
                    coverageEventLinks = previousCoverageLinks
                    longTermMemories = previousMemories
                    deletionTombstones = previousTombstones
                    sourceObjects = previousSources
                    eventSourceLinks = previousSourceLinks
                    eventFieldEvidence = previousFieldEvidence
                    eventUserConfirmations = previousUserConfirmations
                    sourceDeletionRecords = previousRecords
                    return SourceDeletionResult(
                        sourceObjectID: sourceObjectID,
                        operation: .sourceCascade,
                        status: .lineageUnavailable,
                        affectedEventCount: 0
                    )
                }
                recomputedEventCount += 1
            }
        }
        let timestamp = Self.iso8601(requestedAt)
        for dayIndex in coverageDays.indices {
            for candidateIndex in coverageDays[dayIndex].candidates.indices where
                coverageDays[dayIndex].candidates[candidateIndex].state == .open &&
                coverageDays[dayIndex].candidates[candidateIndex].candidate.sourceObjectIDs.contains(sourceObjectID) {
                coverageDays[dayIndex].candidates[candidateIndex].state = .deleted
                coverageDays[dayIndex].candidates[candidateIndex].updatedAt = timestamp
            }
        }
        let sourceBeforeDeletion = sourceObjects[sourceIndex]
        sourceObjects[sourceIndex].state = .deleted
        sourceObjects[sourceIndex].deletedAtEpochMilliseconds = Self.epochMilliseconds(requestedAt)
        if sourceObjects[sourceIndex].rawOwnership == .appOwnedEncrypted &&
            sourceObjects[sourceIndex].rawState != .deleted {
            sourceObjects[sourceIndex].rawState = .pendingCleanup
        }
        let sourceTombstone = DeletionTombstone.create(
            spaceID: SourceObject.personalSpaceID,
            objectType: DeletionTombstone.sourceObjectType,
            objectID: sourceObjectID,
            terminalRevision: 1,
            deletedAtEpochMilliseconds: Self.epochMilliseconds(requestedAt),
            reason: "source_cascade_delete"
        )
        guard upsertDeletionTombstone(sourceTombstone) else {
            events = previousEvents
            coverageDays = previousCoverageDays
            coverageEventLinks = previousCoverageLinks
            longTermMemories = previousMemories
            deletionTombstones = previousTombstones
            sourceObjects = previousSources
            eventSourceLinks = previousSourceLinks
            eventFieldEvidence = previousFieldEvidence
            eventUserConfirmations = previousUserConfirmations
            sourceDeletionRecords = previousRecords
            return SourceDeletionResult(
                sourceObjectID: sourceObjectID,
                operation: .sourceCascade,
                status: .persistenceFailed,
                affectedEventCount: 0
            )
        }
        let needsCleanup = sourceBeforeDeletion.rawOwnership == .appOwnedEncrypted &&
            sourceBeforeDeletion.rawState != .deleted
        let terminalStatus: SourceDeletionStatus =
            sourceBeforeDeletion.rawOwnership == .externalNotOwned
            ? .completedLocalOnly
            : .completed
        appendSourceDeletionRecord(
            sourceObjectID: sourceObjectID,
            operation: .sourceCascade,
            status: needsCleanup ? .pendingCleanup : terminalStatus,
            affectedEventCount: linkedEventIDs.count,
            recomputedEventCount: recomputedEventCount,
            deletedEventCount: deletedEventCount,
            rawDigestOnly: sourceBeforeDeletion.rawCiphertextSHA256,
            at: requestedAt
        )
        guard persist() else {
            events = previousEvents
            coverageDays = previousCoverageDays
            coverageEventLinks = previousCoverageLinks
            longTermMemories = previousMemories
            deletionTombstones = previousTombstones
            sourceObjects = previousSources
            eventSourceLinks = previousSourceLinks
            eventFieldEvidence = previousFieldEvidence
            eventUserConfirmations = previousUserConfirmations
            sourceDeletionRecords = previousRecords
            return SourceDeletionResult(
                sourceObjectID: sourceObjectID,
                operation: .sourceCascade,
                status: .persistenceFailed,
                affectedEventCount: 0
            )
        }
        guard needsCleanup else {
            return SourceDeletionResult(
                sourceObjectID: sourceObjectID,
                operation: .sourceCascade,
                status: terminalStatus,
                affectedEventCount: linkedEventIDs.count,
                recomputedEventCount: recomputedEventCount,
                deletedEventCount: deletedEventCount
            )
        }
        guard removeOwnedRaw(at: sourceBeforeDeletion.sourceLocator) else {
            return SourceDeletionResult(
                sourceObjectID: sourceObjectID,
                operation: .sourceCascade,
                status: .pendingCleanup,
                affectedEventCount: linkedEventIDs.count,
                recomputedEventCount: recomputedEventCount,
                deletedEventCount: deletedEventCount
            )
        }
        return finalizeRawCleanup(
            sourceObjectID: sourceObjectID,
            operation: .sourceCascade,
            affectedEventCount: linkedEventIDs.count,
            recomputedEventCount: recomputedEventCount,
            deletedEventCount: deletedEventCount,
            completedAt: requestedAt
        )
    }

    /// Deletes and freezes only this encrypted local Personal space. Account/Grant revocation,
    /// peer acknowledgement, provider-original deletion and physical-purge proof remain separate.
    @discardableResult
    public func deleteLocalSpace(requestedAt: Date = .now) -> LocalSpaceDeletionResult {
        let deletedAtEpochMilliseconds = Self.epochMilliseconds(requestedAt)
        if let existing = deletionTombstones.first(where: {
            $0.objectType == DeletionTombstone.spaceObjectType &&
                $0.objectID == SourceObject.personalSpaceID
        }) {
            return LocalSpaceDeletionResult(
                status: .alreadyDeleted,
                affectedEventCount: 0,
                affectedSourceCount: 0,
                pendingRawCleanupCount: sourceObjects.filter {
                    $0.rawState == .pendingCleanup
                }.count,
                deletedAtEpochMilliseconds: existing.deletedAtEpochMilliseconds,
                externalOriginalsRetained: true
            )
        }
        guard !isDemoMode else {
            return LocalSpaceDeletionResult(
                status: .persistenceFailed,
                affectedEventCount: 0,
                affectedSourceCount: 0,
                pendingRawCleanupCount: 0,
                deletedAtEpochMilliseconds: deletedAtEpochMilliseconds,
                externalOriginalsRetained: false
            )
        }

        let previousEvents = events
        let previousCoverageDays = coverageDays
        let previousCoverageLinks = coverageEventLinks
        let previousMemories = longTermMemories
        let previousTombstones = deletionTombstones
        let previousAttempts = reuseAttempts
        let previousOutcomes = reuseOutcomes
        let previousSources = sourceObjects
        let previousSourceLinks = eventSourceLinks
        let previousFieldEvidence = eventFieldEvidence
        let previousUserConfirmations = eventUserConfirmations
        let previousRecords = sourceDeletionRecords
        let rollback = {
            self.events = previousEvents
            self.coverageDays = previousCoverageDays
            self.coverageEventLinks = previousCoverageLinks
            self.longTermMemories = previousMemories
            self.deletionTombstones = previousTombstones
            self.reuseAttempts = previousAttempts
            self.reuseOutcomes = previousOutcomes
            self.sourceObjects = previousSources
            self.eventSourceLinks = previousSourceLinks
            self.eventFieldEvidence = previousFieldEvidence
            self.eventUserConfirmations = previousUserConfirmations
            self.sourceDeletionRecords = previousRecords
        }
        let eventIDs = events.map(\.id)
        let activeSourceIndexes = sourceObjects.indices.filter {
            sourceObjects[$0].state == .active
        }
        let retainedExternalOriginal = activeSourceIndexes.contains {
            sourceObjects[$0].rawOwnership == .externalNotOwned
        }

        for eventID in eventIDs {
            guard applyEventDeletion(
                id: eventID,
                reason: "local_space_delete",
                deletedAt: requestedAt
            ) else {
                rollback()
                return LocalSpaceDeletionResult(
                    status: .persistenceFailed,
                    affectedEventCount: 0,
                    affectedSourceCount: 0,
                    pendingRawCleanupCount: 0,
                    deletedAtEpochMilliseconds: deletedAtEpochMilliseconds,
                    externalOriginalsRetained: retainedExternalOriginal
                )
            }
        }
        coverageDays = []
        coverageEventLinks = []
        longTermMemories = []
        reuseAttempts = []
        reuseOutcomes = []
        for index in activeSourceIndexes {
            sourceObjects[index].state = .deleted
            sourceObjects[index].deletedAtEpochMilliseconds = deletedAtEpochMilliseconds
            switch sourceObjects[index].rawOwnership {
            case .appOwnedEncrypted:
                if sourceObjects[index].rawState != .deleted {
                    sourceObjects[index].rawState = .pendingCleanup
                }
            case .externalNotOwned, .noRaw:
                sourceObjects[index].sourceLocator = nil
            }
            guard upsertDeletionTombstone(
                DeletionTombstone.create(
                    spaceID: SourceObject.personalSpaceID,
                    objectType: DeletionTombstone.sourceObjectType,
                    objectID: sourceObjects[index].sourceObjectID,
                    terminalRevision: 1,
                    deletedAtEpochMilliseconds: deletedAtEpochMilliseconds,
                    reason: "local_space_delete"
                )
            ) else {
                rollback()
                return LocalSpaceDeletionResult(
                    status: .persistenceFailed,
                    affectedEventCount: 0,
                    affectedSourceCount: 0,
                    pendingRawCleanupCount: 0,
                    deletedAtEpochMilliseconds: deletedAtEpochMilliseconds,
                    externalOriginalsRetained: retainedExternalOriginal
                )
            }
        }
        for index in eventSourceLinks.indices where eventSourceLinks[index].state == .active {
            eventSourceLinks[index].state = .deleted
            eventSourceLinks[index].deletedAtEpochMilliseconds = deletedAtEpochMilliseconds
        }
        guard upsertDeletionTombstone(.localSpace(deletedAt: requestedAt)) else {
            rollback()
            return LocalSpaceDeletionResult(
                status: .persistenceFailed,
                affectedEventCount: 0,
                affectedSourceCount: 0,
                pendingRawCleanupCount: 0,
                deletedAtEpochMilliseconds: deletedAtEpochMilliseconds,
                externalOriginalsRetained: retainedExternalOriginal
            )
        }

        allowsDeletedSpacePersistence = true
        let committed = persist()
        allowsDeletedSpacePersistence = false
        guard committed else {
            rollback()
            return LocalSpaceDeletionResult(
                status: .persistenceFailed,
                affectedEventCount: 0,
                affectedSourceCount: 0,
                pendingRawCleanupCount: 0,
                deletedAtEpochMilliseconds: deletedAtEpochMilliseconds,
                externalOriginalsRetained: retainedExternalOriginal
            )
        }
        retryPendingRawCleanup()
        let pendingRawCleanupCount = sourceObjects.filter {
            $0.rawState == .pendingCleanup
        }.count
        storageState = .deleted
        return LocalSpaceDeletionResult(
            status: pendingRawCleanupCount == 0 ? .completedLocalOnly : .pendingRawCleanup,
            affectedEventCount: eventIDs.count,
            affectedSourceCount: activeSourceIndexes.count,
            pendingRawCleanupCount: pendingRawCleanupCount,
            deletedAtEpochMilliseconds: deletedAtEpochMilliseconds,
            externalOriginalsRetained: retainedExternalOriginal
        )
    }

    public func summary(for date: Date) -> DaySummary {
        let eligible = events(on: date).filter {
            $0.sensitivity != .restricted &&
            [.confirmed, .userAsserted, .planned].contains($0.factStatus)
        }
        guard eligible.count >= 2 else {
            return DaySummary(
                state: .insufficient,
                text: "当前可用于小结的事件不足 2 条；继续记录即可，不会生成空泛模板。",
                basedOnRevision: eligible.reduce(0) { $0 + $1.revision }
            )
        }
        let titles = eligible.prefix(3).map(\.title).joined(separator: "、")
        let countText = eligible.count > 3 ? "等 \(eligible.count) 件事" : "共 \(eligible.count) 件事"
        return DaySummary(
            state: .ready,
            text: "今天记录了：\(titles)。\(countText)。小结只基于已保存且未受限的事件。",
            basedOnRevision: eligible.reduce(0) { $0 + $1.revision }
        )
    }

    public func exportData(exportedAt: Date = .now) throws -> Data {
        let exportable = events.filter { $0.sensitivity != .restricted }
        let envelope = LocalExportEnvelope(
            schemaVersion: 1,
            space: "Personal",
            exportedAt: exportedAt,
            eventCount: exportable.count,
            events: exportable.map(LocalExportEvent.init),
        )
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .iso8601
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        return try encoder.encode(envelope)
    }

    /// Creates a fully encrypted, authenticated same-install recovery artifact. The artifact
    /// intentionally excludes Keychain material and therefore cannot claim cross-device or
    /// full-device-loss recovery.
    public func createLocalRecoveryBackup(
        at destinationDirectory: URL,
        createdAt: Date = .now
    ) throws -> LocalBackupManifest {
        guard !isDemoMode,
              !isLocalSpaceDeleted,
              storageState == .ready,
              !sourceObjects.contains(where: { $0.rawState == .pendingCleanup }),
              let key else {
            throw LocalBackupError.sourceStoreUnavailable
        }
        return try LocalBackupStore.create(
            destinationDirectory: destinationDirectory,
            encryptedEnvelope: Data(contentsOf: fileURL),
            events: events,
            sourceObjects: sourceObjects,
            sourceMediaDirectory: mediaDirectory,
            deletionTombstones: deletionTombstones,
            localStoreSchemaVersion: LocalStoreEnvelope.currentSchemaVersion,
            key: key,
            createdAt: createdAt,
            fileManager: fileManager
        )
    }

    @discardableResult
    public func verifyLocalRecoveryBackup(at backupDirectory: URL) throws -> LocalBackupManifest {
        guard !isDemoMode,
              storageState == .ready || storageState == .deleted,
              let key else {
            throw LocalBackupError.sourceStoreUnavailable
        }
        return try LocalBackupStore.verify(
            backupDirectory: backupDirectory,
            key: key,
            fileManager: fileManager
        ).manifest
    }

    /// Restores only into a new candidate root. It never merges with or overwrites the live store,
    /// and it rejects snapshots older than any authoritative deletion tombstone supplied by the
    /// caller. Switching the candidate into production remains a separate user-confirmed action.
    public func restoreLocalRecoveryCandidate(
        from backupDirectory: URL,
        to destinationDirectory: URL,
        authoritativeTombstones: [DeletionTombstone]? = nil
    ) throws -> LocalRecoveryCandidate {
        guard !isDemoMode,
              storageState == .ready || storageState == .deleted,
              let key else {
            throw LocalBackupError.sourceStoreUnavailable
        }
        var effectiveTombstones = Dictionary(
            uniqueKeysWithValues: deletionTombstones.map {
                ($0.canonicalKey, $0)
            }
        )
        for tombstone in authoritativeTombstones ?? [] {
            let existing = effectiveTombstones[tombstone.canonicalKey]
            if existing == nil ||
                tombstone.terminalRevision > existing!.terminalRevision {
                effectiveTombstones[tombstone.canonicalKey] = tombstone
            }
        }
        return try LocalBackupStore.restoreCandidate(
            backupDirectory: backupDirectory,
            destinationDirectory: destinationDirectory,
            authoritativeTombstones: effectiveTombstones.values.sorted {
                $0.canonicalKey < $1.canonicalKey
            },
            key: key,
            fileManager: fileManager
        )
    }

    /// Activates a verified same-install candidate under an exact, short-lived confirmation.
    ///
    /// The store is synchronously unavailable during the swap. A PREPARED journal restores the
    /// old root after failure or restart; only a fully re-verified candidate reaches COMMITTED.
    /// This method does not provide cross-device key recovery and always returns
    /// `productionRecoveryClaim == false`.
    public func activateLocalRecoveryCandidate(
        _ candidate: LocalRecoveryCandidate,
        authorization: LocalRecoveryActivationAuthorization,
        activatedAt: Date = .now
    ) throws -> LocalRecoveryActivationReceipt {
        guard !isDemoMode,
              storageState == .ready || storageState == .deleted,
              let key else {
            throw LocalBackupError.sourceStoreUnavailable
        }
        storageState = .loading
        do {
            let receipt = try LocalBackupStore.activateCandidate(
                candidate,
                liveDirectory: rootDirectory,
                authorization: authorization,
                activatedAt: activatedAt,
                authoritativeTombstones: deletionTombstones,
                key: key,
                fileManager: fileManager,
                failureInjector: recoveryActivationFailureInjector
            )
            load()
            guard storageState == .ready || storageState == .deleted else {
                throw LocalBackupError.restoredEnvelopeInvalid
            }
            return receipt
        } catch {
            load()
            throw error
        }
    }

    public func clearAndReload() {
        isDemoMode = false
        load()
    }

    /// Loads deterministic, non-persistent content so the complete UI can be exercised
    /// without changing the user's encrypted event store.
    public func enterDemoMode() {
        isDemoMode = true
        events = Self.demoEvents(relativeTo: .now)
        storageState = .ready
    }

    /// Leaves the demo session and restores the user's encrypted local event store.
    public func exitDemoMode() {
        guard isDemoMode else { return }
        isDemoMode = false
        load()
    }

    public func retryLoad() {
        guard storageState == .recoverableError else { return }
        load()
    }

    private func load() {
        eventUserConfirmations = []
        var migratedUserConfirmationEnvelope = false
        do {
            key = try keyStore.loadOrCreateKey()
            _ = try LocalBackupStore.recoverInterruptedActivation(
                liveDirectory: rootDirectory,
                key: key!,
                fileManager: fileManager
            )
            try fileManager.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
            try fileManager.createDirectory(at: mediaDirectory, withIntermediateDirectories: true)
            guard fileManager.fileExists(atPath: fileURL.path) else {
                events = []
                coverageDays = []
                coverageEventLinks = []
                longTermMemories = []
                deletionTombstones = []
                reuseAttempts = []
                reuseOutcomes = []
                sourceObjects = []
                eventSourceLinks = []
                eventFieldEvidence = []
                sourceDeletionRecords = []
                storageState = .ready
                return
            }
            let encrypted = try Data(contentsOf: fileURL)
            let box = try AES.GCM.SealedBox(combined: encrypted)
            let clear = try AES.GCM.open(box, using: key!)
            let decoder = JSONDecoder()
            if let envelope = try? decoder.decode(LocalStoreEnvelope.self, from: clear) {
                guard envelope.schemaVersion == LocalStoreEnvelope.currentSchemaVersion,
                      envelope.coverageDays.allSatisfy({
                          $0.schemaVersion == PersistedCoverageDay.currentSchemaVersion
                      }),
                      envelope.isInternallyConsistent,
                      sourceMediaInventoryIsValid(envelope.sourceObjects) else {
                    throw CoveragePersistenceError.invalid("unsupported local store schema")
                }
                events = envelope.events
                coverageDays = envelope.coverageDays
                coverageEventLinks = envelope.coverageEventLinks
                longTermMemories = envelope.longTermMemories
                deletionTombstones = envelope.deletionTombstones
                reuseAttempts = envelope.reuseAttempts
                reuseOutcomes = envelope.reuseOutcomes
                sourceObjects = envelope.sourceObjects
                eventSourceLinks = envelope.eventSourceLinks
                eventFieldEvidence = envelope.eventFieldEvidence
                eventUserConfirmations = envelope.eventUserConfirmations
                sourceDeletionRecords = envelope.sourceDeletionRecords
            } else if let legacyEnvelope = try? decoder.decode(
                LegacyLocalStoreEnvelopeV7.self,
                from: clear
            ) {
                let envelope = LocalStoreEnvelope(
                    events: legacyEnvelope.events,
                    coverageDays: legacyEnvelope.coverageDays,
                    coverageEventLinks: legacyEnvelope.coverageEventLinks,
                    longTermMemories: legacyEnvelope.longTermMemories,
                    deletionTombstones: legacyEnvelope.deletionTombstones,
                    reuseAttempts: legacyEnvelope.reuseAttempts,
                    reuseOutcomes: legacyEnvelope.reuseOutcomes,
                    sourceObjects: legacyEnvelope.sourceObjects,
                    eventSourceLinks: legacyEnvelope.eventSourceLinks,
                    eventFieldEvidence: legacyEnvelope.eventFieldEvidence,
                    sourceDeletionRecords: legacyEnvelope.sourceDeletionRecords
                )
                guard legacyEnvelope.schemaVersion == 7,
                      legacyEnvelope.coverageDays.allSatisfy({
                          $0.schemaVersion == PersistedCoverageDay.currentSchemaVersion
                      }),
                      envelope.isInternallyConsistent,
                      sourceMediaInventoryIsValid(legacyEnvelope.sourceObjects) else {
                    throw CoveragePersistenceError.invalid("unsupported local store schema")
                }
                events = legacyEnvelope.events
                coverageDays = legacyEnvelope.coverageDays
                coverageEventLinks = legacyEnvelope.coverageEventLinks
                longTermMemories = legacyEnvelope.longTermMemories
                deletionTombstones = legacyEnvelope.deletionTombstones
                reuseAttempts = legacyEnvelope.reuseAttempts
                reuseOutcomes = legacyEnvelope.reuseOutcomes
                sourceObjects = legacyEnvelope.sourceObjects
                eventSourceLinks = legacyEnvelope.eventSourceLinks
                eventFieldEvidence = legacyEnvelope.eventFieldEvidence
                sourceDeletionRecords = legacyEnvelope.sourceDeletionRecords
                migratedUserConfirmationEnvelope = true
            } else if let legacyEnvelope = try? decoder.decode(
                LegacyLocalStoreEnvelopeV6.self,
                from: clear
            ) {
                let envelope = LocalStoreEnvelope(
                    events: legacyEnvelope.events,
                    coverageDays: legacyEnvelope.coverageDays,
                    coverageEventLinks: legacyEnvelope.coverageEventLinks,
                    longTermMemories: legacyEnvelope.longTermMemories,
                    deletionTombstones: legacyEnvelope.deletionTombstones,
                    reuseAttempts: legacyEnvelope.reuseAttempts,
                    reuseOutcomes: legacyEnvelope.reuseOutcomes,
                    sourceObjects: legacyEnvelope.sourceObjects,
                    eventSourceLinks: legacyEnvelope.eventSourceLinks,
                    eventFieldEvidence: [],
                    sourceDeletionRecords: legacyEnvelope.sourceDeletionRecords
                )
                guard legacyEnvelope.schemaVersion == 6,
                      legacyEnvelope.coverageDays.allSatisfy({
                          $0.schemaVersion == PersistedCoverageDay.currentSchemaVersion
                      }),
                      envelope.isInternallyConsistent,
                      sourceMediaInventoryIsValid(legacyEnvelope.sourceObjects) else {
                    throw CoveragePersistenceError.invalid("unsupported local store schema")
                }
                events = legacyEnvelope.events
                coverageDays = legacyEnvelope.coverageDays
                coverageEventLinks = legacyEnvelope.coverageEventLinks
                longTermMemories = legacyEnvelope.longTermMemories
                deletionTombstones = legacyEnvelope.deletionTombstones
                reuseAttempts = legacyEnvelope.reuseAttempts
                reuseOutcomes = legacyEnvelope.reuseOutcomes
                sourceObjects = legacyEnvelope.sourceObjects
                eventSourceLinks = legacyEnvelope.eventSourceLinks
                eventFieldEvidence = []
                sourceDeletionRecords = legacyEnvelope.sourceDeletionRecords
            } else if let legacyEnvelope = try? decoder.decode(LegacyLocalStoreEnvelopeV5.self, from: clear) {
                let lineage = Self.migratedCoverageLineage(legacyEnvelope.coverageEventLinks)
                let envelope = LocalStoreEnvelope(
                    events: legacyEnvelope.events,
                    coverageDays: legacyEnvelope.coverageDays,
                    coverageEventLinks: legacyEnvelope.coverageEventLinks,
                    longTermMemories: legacyEnvelope.longTermMemories,
                    deletionTombstones: legacyEnvelope.deletionTombstones,
                    reuseAttempts: legacyEnvelope.reuseAttempts,
                    reuseOutcomes: legacyEnvelope.reuseOutcomes,
                    sourceObjects: lineage.sources,
                    eventSourceLinks: lineage.links,
                    eventFieldEvidence: [],
                    sourceDeletionRecords: []
                )
                guard legacyEnvelope.schemaVersion == 5,
                      legacyEnvelope.coverageDays.allSatisfy({
                          $0.schemaVersion == PersistedCoverageDay.currentSchemaVersion
                      }),
                      envelope.isInternallyConsistent else {
                    throw CoveragePersistenceError.invalid("unsupported local store schema")
                }
                events = legacyEnvelope.events
                coverageDays = legacyEnvelope.coverageDays
                coverageEventLinks = legacyEnvelope.coverageEventLinks
                longTermMemories = legacyEnvelope.longTermMemories
                deletionTombstones = legacyEnvelope.deletionTombstones
                reuseAttempts = legacyEnvelope.reuseAttempts
                reuseOutcomes = legacyEnvelope.reuseOutcomes
                sourceObjects = lineage.sources
                eventSourceLinks = lineage.links
                eventFieldEvidence = []
                sourceDeletionRecords = []
            } else if let legacyEnvelope = try? decoder.decode(LegacyLocalStoreEnvelopeV4.self, from: clear) {
                let lineage = Self.migratedCoverageLineage(legacyEnvelope.coverageEventLinks)
                let envelope = LocalStoreEnvelope(
                    events: legacyEnvelope.events,
                    coverageDays: legacyEnvelope.coverageDays,
                    coverageEventLinks: legacyEnvelope.coverageEventLinks,
                    longTermMemories: legacyEnvelope.longTermMemories,
                    deletionTombstones: legacyEnvelope.deletionTombstones,
                    reuseAttempts: [],
                    reuseOutcomes: [],
                    sourceObjects: lineage.sources,
                    eventSourceLinks: lineage.links,
                    eventFieldEvidence: [],
                    sourceDeletionRecords: []
                )
                guard legacyEnvelope.schemaVersion == 4,
                      legacyEnvelope.coverageDays.allSatisfy({
                          $0.schemaVersion == PersistedCoverageDay.currentSchemaVersion
                      }),
                      envelope.isInternallyConsistent else {
                    throw CoveragePersistenceError.invalid("unsupported local store schema")
                }
                events = legacyEnvelope.events
                coverageDays = legacyEnvelope.coverageDays
                coverageEventLinks = legacyEnvelope.coverageEventLinks
                longTermMemories = legacyEnvelope.longTermMemories
                deletionTombstones = legacyEnvelope.deletionTombstones
                reuseAttempts = []
                reuseOutcomes = []
                sourceObjects = lineage.sources
                eventSourceLinks = lineage.links
                eventFieldEvidence = []
                sourceDeletionRecords = []
            } else if let legacyEnvelope = try? decoder.decode(LegacyLocalStoreEnvelopeV3.self, from: clear) {
                let lineage = Self.migratedCoverageLineage(legacyEnvelope.coverageEventLinks)
                let envelope = LocalStoreEnvelope(
                    events: legacyEnvelope.events,
                    coverageDays: legacyEnvelope.coverageDays,
                    coverageEventLinks: legacyEnvelope.coverageEventLinks,
                    longTermMemories: legacyEnvelope.longTermMemories,
                    deletionTombstones: [],
                    reuseAttempts: [],
                    reuseOutcomes: [],
                    sourceObjects: lineage.sources,
                    eventSourceLinks: lineage.links,
                    eventFieldEvidence: [],
                    sourceDeletionRecords: []
                )
                guard legacyEnvelope.schemaVersion == 3,
                      legacyEnvelope.coverageDays.allSatisfy({
                          $0.schemaVersion == PersistedCoverageDay.currentSchemaVersion
                      }),
                      envelope.isInternallyConsistent else {
                    throw CoveragePersistenceError.invalid("unsupported local store schema")
                }
                events = legacyEnvelope.events
                coverageDays = legacyEnvelope.coverageDays
                coverageEventLinks = legacyEnvelope.coverageEventLinks
                longTermMemories = legacyEnvelope.longTermMemories
                deletionTombstones = []
                reuseAttempts = []
                reuseOutcomes = []
                sourceObjects = lineage.sources
                eventSourceLinks = lineage.links
                eventFieldEvidence = []
                sourceDeletionRecords = []
            } else if let legacyEnvelope = try? decoder.decode(LegacyLocalStoreEnvelopeV2.self, from: clear) {
                let lineage = Self.migratedCoverageLineage(legacyEnvelope.coverageEventLinks)
                let envelope = LocalStoreEnvelope(
                    events: legacyEnvelope.events,
                    coverageDays: legacyEnvelope.coverageDays,
                    coverageEventLinks: legacyEnvelope.coverageEventLinks,
                    longTermMemories: [],
                    deletionTombstones: [],
                    reuseAttempts: [],
                    reuseOutcomes: [],
                    sourceObjects: lineage.sources,
                    eventSourceLinks: lineage.links,
                    eventFieldEvidence: [],
                    sourceDeletionRecords: []
                )
                guard legacyEnvelope.schemaVersion == 2,
                      legacyEnvelope.coverageDays.allSatisfy({
                          $0.schemaVersion == PersistedCoverageDay.currentSchemaVersion
                      }),
                      envelope.isInternallyConsistent else {
                    throw CoveragePersistenceError.invalid("unsupported local store schema")
                }
                events = legacyEnvelope.events
                coverageDays = legacyEnvelope.coverageDays
                coverageEventLinks = legacyEnvelope.coverageEventLinks
                longTermMemories = []
                deletionTombstones = []
                reuseAttempts = []
                reuseOutcomes = []
                sourceObjects = lineage.sources
                eventSourceLinks = lineage.links
                eventFieldEvidence = []
                sourceDeletionRecords = []
            } else {
                events = try decoder.decode([MemoryEvent].self, from: clear)
                coverageDays = []
                coverageEventLinks = []
                longTermMemories = []
                deletionTombstones = []
                reuseAttempts = []
                reuseOutcomes = []
                sourceObjects = []
                eventSourceLinks = []
                eventFieldEvidence = []
                sourceDeletionRecords = []
            }
            sortEvents()
            if migratedUserConfirmationEnvelope && !isLocalSpaceDeleted && !persist() {
                throw CoveragePersistenceError.persistenceFailed
            }
            storageState = .ready
            retryPendingRawCleanup()
            if isLocalSpaceDeleted {
                storageState = .deleted
            }
        } catch {
            events = []
            coverageDays = []
            coverageEventLinks = []
            longTermMemories = []
            deletionTombstones = []
            reuseAttempts = []
            reuseOutcomes = []
            sourceObjects = []
            eventSourceLinks = []
            eventFieldEvidence = []
            sourceDeletionRecords = []
            key = nil
            storageState = .recoverableError
        }
    }

    @discardableResult
    private func persist() -> Bool {
        guard !isDemoMode else {
            storageState = .ready
            return true
        }
        guard !isLocalSpaceDeleted || allowsDeletedSpacePersistence else {
            return false
        }
        if persistenceFailureInjector?() == true {
            storageState = .recoverableError
            return false
        }
        guard let key else {
            storageState = .recoverableError
            return false
        }
        do {
            try fileManager.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
            let envelope = LocalStoreEnvelope(
                events: events,
                coverageDays: coverageDays,
                coverageEventLinks: coverageEventLinks,
                longTermMemories: longTermMemories,
                deletionTombstones: deletionTombstones,
                reuseAttempts: reuseAttempts,
                reuseOutcomes: reuseOutcomes,
                sourceObjects: sourceObjects,
                eventSourceLinks: eventSourceLinks,
                eventFieldEvidence: eventFieldEvidence,
                eventUserConfirmations: eventUserConfirmations,
                sourceDeletionRecords: sourceDeletionRecords
            )
            guard envelope.isInternallyConsistent else {
                storageState = .recoverableError
                return false
            }
            let clear = try JSONEncoder().encode(envelope)
            let box = try AES.GCM.seal(clear, using: key)
            try box.combined!.write(to: fileURL, options: .atomic)
            storageState = .ready
            return true
        } catch {
            storageState = .recoverableError
            return false
        }
    }

    private func persistMedia(_ data: Data, fileExtension: String) -> String? {
        guard !isDemoMode else { return nil }
        guard let key else {
            storageState = .recoverableError
            return nil
        }
        do {
            try fileManager.createDirectory(at: mediaDirectory, withIntermediateDirectories: true)
            let url = mediaDirectory
                .appendingPathComponent(UUID().uuidString)
                .appendingPathExtension("enc")
            let sealed = try AES.GCM.seal(data, using: key)
            guard let combined = sealed.combined else { return nil }
            try combined.write(to: url, options: .atomic)
            return url.path
        } catch {
            return nil
        }
    }

    private func sortEvents() {
        events.sort {
            if $0.localDate != $1.localDate { return $0.localDate > $1.localDate }
            return ($0.time ?? .distantPast) > ($1.time ?? .distantPast)
        }
    }

    private func invalidateLongTermMemories(
        for eventID: UUID,
        currentRevision: Int?,
        reason: LongTermMemoryInvalidationReason,
        changedAt: Date
    ) {
        for index in longTermMemories.indices where
            longTermMemories[index].sourceEventID == eventID &&
            [.eligibleForMemoryCompiler, .candidateUserConfirmationRequired, .active]
                .contains(longTermMemories[index].state) &&
            (currentRevision == nil || longTermMemories[index].sourceEventRevision != currentRevision) {
            longTermMemories[index].state = .invalidated
            longTermMemories[index].invalidatedAt = changedAt
            longTermMemories[index].invalidationReason = reason
            longTermMemories[index].revision += 1
            longTermMemories[index].updatedAt = changedAt
            longTermMemories[index].revisions.append(
                LongTermMemoryRevision(
                    revision: longTermMemories[index].revision,
                    reason: reason == .eventDeleted ? .eventDeleted : .eventRevisionChanged,
                    state: .invalidated,
                    changedAt: changedAt
                )
            )
        }
    }

    private static func eventType(for type: CoverageEventType) -> EventType {
        switch type {
        case .activity: return .activity
        case .communication: return .communication
        case .decision: return .decision
        case .result: return .result
        case .stateChange: return .stateChange
        case .milestone: return .milestone
        case .experience: return .experience
        }
    }

    private static func eventStatuses(
        for status: CoverageFactStatus,
        mode: CoverageAcceptanceMode
    ) -> (factStatus: FactStatus, evidenceState: EvidenceState) {
        if mode == .userConfirmed {
            return (.confirmed, .userAsserted)
        }
        switch status {
        case .observed: return (.needsReview, .observed)
        case .userAsserted: return (.userAsserted, .userAsserted)
        case .planned: return (.planned, .observed)
        case .inferred: return (.inferred, .inferred)
        }
    }

    private static func title(for text: String) -> String {
        let firstLine = text.split(whereSeparator: \.isNewline).first.map(String.init) ?? text
        return String(firstLine.prefix(28))
    }

    /// Mirrors Android's bounded AND query contract: every whitespace-separated
    /// term must match one of the same event fields, with a bounded query budget.
    private static func searchTerms(_ value: String) -> [String] {
        value
            .split(whereSeparator: { $0.isWhitespace })
            .prefix(16)
            .map(String.init)
    }

    private func removeOwnedMedia(for event: MemoryEvent) {
        guard let sourceLocator = event.sourceLocator else { return }
        let mediaRoot = mediaDirectory.standardizedFileURL.path.hasSuffix("/")
            ? mediaDirectory.standardizedFileURL.path
            : mediaDirectory.standardizedFileURL.path + "/"
        let candidate = URL(fileURLWithPath: sourceLocator).standardizedFileURL
        guard candidate.path.hasPrefix(mediaRoot) else { return }
        try? fileManager.removeItem(at: candidate)
    }

    private func registerCapturedSource(for event: MemoryEvent) {
        guard let locator = event.sourceLocator else { return }
        let createdAt = Self.epochMilliseconds(.now)
        let candidate = URL(fileURLWithPath: locator).standardizedFileURL
        let mediaRoot = mediaDirectory.standardizedFileURL
        let isOwned = locator.hasPrefix("/") &&
            candidate.deletingLastPathComponent() == mediaRoot &&
            candidate.pathExtension == "enc" &&
            UUID(uuidString: candidate.deletingPathExtension().lastPathComponent) != nil
        let digest = isOwned
            ? (try? Data(contentsOf: candidate)).map(Self.sha256Hex)
            : nil
        let ownsRaw = isOwned && digest != nil
        let sourceObject = SourceObject(
            sourceObjectID: "source_\(UUID().uuidString.lowercased())",
            kind: .capturedLocator,
            rawOwnership: ownsRaw ? .appOwnedEncrypted : .externalNotOwned,
            rawState: ownsRaw ? .available : .externalNotOwned,
            sourceLocator: locator,
            rawCiphertextSHA256: digest,
            createdAtEpochMilliseconds: createdAt
        )
        sourceObjects.append(sourceObject)
        eventSourceLinks.append(
            EventSourceLink(
                eventID: event.id,
                sourceObjectID: sourceObject.sourceObjectID,
                createdRevision: event.revision,
                createdAtEpochMilliseconds: createdAt
            )
        )
    }

    private func isCompatibleCoverageSource(_ sourceObjectID: String) -> Bool {
        guard let source = sourceObjects.first(where: {
            $0.sourceObjectID == sourceObjectID
        }) else {
            return true
        }
        return source.kind == .coverageEvidence &&
            source.rawOwnership == .noRaw &&
            source.rawState == .unavailable &&
            source.sourceLocator == nil &&
            source.state == .active
    }

    @discardableResult
    private func registerCoverageSource(
        sourceObjectID: String,
        eventID: UUID?,
        createdRevision: Int?,
        createdAtEpochMilliseconds: Int64
    ) -> Bool {
        guard isCompatibleCoverageSource(sourceObjectID) else { return false }
        if !sourceObjects.contains(where: { $0.sourceObjectID == sourceObjectID }) {
            sourceObjects.append(
                SourceObject(
                    sourceObjectID: sourceObjectID,
                    kind: .coverageEvidence,
                    rawOwnership: .noRaw,
                    rawState: .unavailable,
                    sourceLocator: nil,
                    rawCiphertextSHA256: nil,
                    createdAtEpochMilliseconds: createdAtEpochMilliseconds
                )
            )
        }
        guard let eventID, let createdRevision else { return true }
        let exists = eventSourceLinks.contains {
            $0.eventID == eventID && $0.sourceObjectID == sourceObjectID
        }
        if !exists {
            eventSourceLinks.append(
                EventSourceLink(
                    eventID: eventID,
                    sourceObjectID: sourceObjectID,
                    createdRevision: createdRevision,
                    createdAtEpochMilliseconds: createdAtEpochMilliseconds
                )
            )
        }
        return true
    }

    private func validatedFieldProvenance(
        candidate: CandidateEvent,
        acceptance: CoverageCandidateAcceptance
    ) throws -> [EvidenceField: Set<String>] {
        let candidateSources = Set(candidate.sourceObjectIDs)
        let candidateFields = Set(candidate.observedFields)
        if acceptance.fieldSourceObjectIDs.isEmpty {
            guard candidateSources.count == 1 else { return [:] }
            return Dictionary(
                uniqueKeysWithValues: candidateFields.map { ($0, candidateSources) }
            )
        }
        guard Set(acceptance.fieldSourceObjectIDs.keys) == candidateFields,
              acceptance.fieldSourceObjectIDs.values.allSatisfy({
                  !$0.isEmpty && $0.isSubset(of: candidateSources)
              }),
              Set(acceptance.fieldSourceObjectIDs.values.flatMap { $0 }) == candidateSources else {
            throw CoveragePersistenceError.invalid(
                "field provenance must cover every field and linked source"
            )
        }
        return acceptance.fieldSourceObjectIDs
    }

    private func sourceCascadeAction(
        eventID: UUID,
        deleting sourceObjectID: String
    ) -> SourceCascadeAction? {
        let activeSources = Set(eventSourceLinks.filter {
            $0.eventID == eventID && $0.state == .active
        }.map(\.sourceObjectID))
        guard let event = events.first(where: { $0.id == eventID }) else { return nil }
        let evidence = eventFieldEvidence.filter {
            $0.eventID == eventID &&
                $0.eventRevision == event.revision &&
                $0.state == .active
        }
        let userConfirmedFields = completeUserConfirmationFields(
            eventID: eventID,
            eventRevision: event.revision
        )
        if activeSources.count == 1 && userConfirmedFields == nil {
            return .delete
        }
        let sourceFields = completeSourceEvidenceFields(
            eventID: eventID,
            eventRevision: event.revision
        )
        guard userConfirmedFields != nil || sourceFields != nil else {
            return nil
        }
        if let userConfirmedFields,
           !evidence.allSatisfy({
               activeSources.contains($0.sourceObjectID) &&
                   userConfirmedFields.contains($0.field)
           }) {
            return nil
        }
        let fields = userConfirmedFields ?? sourceFields!
        let remaining = evidence.filter { $0.sourceObjectID != sourceObjectID }
        let supportedFields = Set(remaining.map(\.field)).union(userConfirmedFields ?? [])
        return fields.isSubset(of: supportedFields) ? .recompute : .delete
    }

    private func appendUserConfirmation(
        eventID: UUID,
        eventRevision: Int,
        kind: UserConfirmationKind,
        confirmedFields: Set<EvidenceField>,
        completeFieldSet: Bool,
        confirmedAt: Date,
        confirmationID: String = "confirm_\(UUID().uuidString.lowercased())"
    ) {
        precondition(!confirmedFields.isEmpty)
        eventUserConfirmations.append(
            EventUserConfirmation(
                confirmationID: confirmationID,
                eventID: eventID,
                eventRevision: eventRevision,
                kind: kind,
                confirmedFields: confirmedFields,
                completeFieldSet: completeFieldSet,
                confirmedAtEpochMilliseconds: Self.epochMilliseconds(confirmedAt)
            )
        )
    }

    private func activeUserConfirmations(
        eventID: UUID,
        eventRevision: Int
    ) -> [EventUserConfirmation] {
        eventUserConfirmations.filter {
            $0.eventID == eventID &&
                $0.eventRevision == eventRevision &&
                $0.state == .active
        }
    }

    private func completeUserConfirmationFields(
        eventID: UUID,
        eventRevision: Int
    ) -> Set<EvidenceField>? {
        let complete = activeUserConfirmations(
            eventID: eventID,
            eventRevision: eventRevision
        ).filter(\.completeFieldSet)
        guard !complete.isEmpty else { return nil }
        let sets = Set(complete.map { Set($0.confirmedFields) })
        return sets.count == 1 ? sets.first : nil
    }

    private func completeSourceEvidenceFields(
        eventID: UUID,
        eventRevision: Int
    ) -> Set<EvidenceField>? {
        let activeSources = Set(eventSourceLinks.filter {
            $0.eventID == eventID && $0.state == .active
        }.map(\.sourceObjectID))
        let evidence = eventFieldEvidence.filter {
            $0.eventID == eventID &&
                $0.eventRevision == eventRevision &&
                $0.state == .active
        }
        guard !activeSources.isEmpty,
              !evidence.isEmpty,
              evidence.allSatisfy({ activeSources.contains($0.sourceObjectID) }),
              activeSources.allSatisfy({ source in
                  evidence.contains { $0.sourceObjectID == source }
              }) else {
            return nil
        }
        return Set(evidence.map(\.field))
    }

    private func carryUserConfirmations(
        _ confirmations: [EventUserConfirmation],
        to eventRevision: Int
    ) {
        for confirmation in confirmations {
            eventUserConfirmations.append(
                EventUserConfirmation(
                    confirmationID: confirmation.confirmationID,
                    eventID: confirmation.eventID,
                    eventRevision: eventRevision,
                    kind: confirmation.kind,
                    confirmedFields: Set(confirmation.confirmedFields),
                    completeFieldSet: confirmation.completeFieldSet,
                    confirmedAtEpochMilliseconds: confirmation.confirmedAtEpochMilliseconds
                )
            )
        }
    }

    private func terminalizeUserConfirmations(
        eventID: UUID,
        deletedAtEpochMilliseconds: Int64,
        eventRevision: Int? = nil
    ) {
        for index in eventUserConfirmations.indices where
            eventUserConfirmations[index].eventID == eventID &&
            eventUserConfirmations[index].state == .active &&
            (eventRevision == nil ||
                eventUserConfirmations[index].eventRevision == eventRevision!) {
            eventUserConfirmations[index].state = .deleted
            eventUserConfirmations[index].deletedAtEpochMilliseconds =
                deletedAtEpochMilliseconds
        }
    }

    private func recomputeEventAfterSourceDeletion(
        eventID: UUID,
        sourceObjectID: String,
        requestedAt: Date
    ) -> Bool {
        guard let eventIndex = events.firstIndex(where: { $0.id == eventID }) else {
            return false
        }
        let current = events[eventIndex]
        let activeSourceCount = eventSourceLinks.filter {
            $0.eventID == eventID && $0.state == .active
        }.count
        let confirmations = activeUserConfirmations(
            eventID: eventID,
            eventRevision: current.revision
        )
        let currentEvidence = eventFieldEvidence.filter {
            $0.eventID == eventID &&
                $0.eventRevision == current.revision &&
                $0.state == .active
        }
        let remainingEvidence = currentEvidence.filter {
            $0.sourceObjectID != sourceObjectID
        }
        let deletedAt = Self.epochMilliseconds(requestedAt)
        terminalizeEventFieldEvidence(
            eventID: eventID,
            deletedAtEpochMilliseconds: deletedAt,
            eventRevision: current.revision
        )
        terminalizeUserConfirmations(
            eventID: eventID,
            deletedAtEpochMilliseconds: deletedAt,
            eventRevision: current.revision
        )
        events[eventIndex] = MemoryEvent(
            id: current.id,
            localDate: current.localDate,
            time: current.time,
            title: current.title,
            detail: current.detail,
            factStatus: current.factStatus,
            sourceLabel: activeSourceCount == 1
                ? Self.userConfirmedSourceDeletedLabel
                : Self.recomputedSourceLabel,
            sourceLocator: nil,
            captureKind: current.captureKind,
            isLocalOnly: current.isLocalOnly,
            userWords: current.userWords,
            revision: current.revision + 1,
            eventType: current.eventType,
            evidenceState: current.evidenceState,
            sensitivity: current.sensitivity,
            importance: current.importance
        )
        for evidence in remainingEvidence {
            eventFieldEvidence.append(
                EventFieldEvidence(
                    eventID: eventID,
                    eventRevision: current.revision + 1,
                    field: evidence.field,
                    sourceObjectID: evidence.sourceObjectID,
                    createdAtEpochMilliseconds: deletedAt
                )
            )
        }
        carryUserConfirmations(confirmations, to: current.revision + 1)
        for index in eventSourceLinks.indices where
            eventSourceLinks[index].eventID == eventID &&
            eventSourceLinks[index].sourceObjectID == sourceObjectID &&
            eventSourceLinks[index].state == .active {
            eventSourceLinks[index].state = .deleted
            eventSourceLinks[index].deletedAtEpochMilliseconds = deletedAt
        }
        for index in coverageEventLinks.indices where
            coverageEventLinks[index].eventID == eventID &&
            coverageEventLinks[index].state == .active {
            coverageEventLinks[index].state = .detached
        }
        invalidateLongTermMemories(
            for: eventID,
            currentRevision: current.revision + 1,
            reason: .eventRevisionChanged,
            changedAt: requestedAt
        )
        return true
    }

    private func terminalizeEventFieldEvidence(
        eventID: UUID,
        deletedAtEpochMilliseconds: Int64,
        eventRevision: Int? = nil
    ) {
        for index in eventFieldEvidence.indices where
            eventFieldEvidence[index].eventID == eventID &&
            eventFieldEvidence[index].state == .active &&
            (eventRevision == nil ||
                eventFieldEvidence[index].eventRevision == eventRevision!) {
            eventFieldEvidence[index].state = .deleted
            eventFieldEvidence[index].deletedAtEpochMilliseconds = deletedAtEpochMilliseconds
        }
    }

    private func applyEventDeletion(id: UUID, reason: String, deletedAt: Date) -> Bool {
        guard let deletedEvent = events.first(where: { $0.id == id }) else { return false }
        events.removeAll { $0.id == id }
        for index in coverageEventLinks.indices where
            coverageEventLinks[index].eventID == id &&
            coverageEventLinks[index].state == .active {
            coverageEventLinks[index].state = .detached
        }
        invalidateLongTermMemories(
            for: id,
            currentRevision: nil,
            reason: .eventDeleted,
            changedAt: deletedAt
        )
        let deletedAtEpoch = Self.epochMilliseconds(deletedAt)
        for index in eventSourceLinks.indices where
            eventSourceLinks[index].eventID == id &&
            eventSourceLinks[index].state == .active {
            eventSourceLinks[index].state = .deleted
            eventSourceLinks[index].deletedAtEpochMilliseconds = deletedAtEpoch
        }
        terminalizeEventFieldEvidence(
            eventID: id,
            deletedAtEpochMilliseconds: deletedAtEpoch
        )
        terminalizeUserConfirmations(
            eventID: id,
            deletedAtEpochMilliseconds: deletedAtEpoch
        )
        return upsertDeletionTombstone(
            .event(
                eventID: id,
                terminalRevision: deletedEvent.revision + 1,
                deletedAt: deletedAt,
                reason: reason
            )
        )
    }

    private func upsertDeletionTombstone(_ tombstone: DeletionTombstone) -> Bool {
        if let index = deletionTombstones.firstIndex(where: {
            $0.canonicalKey == tombstone.canonicalKey
        }) {
            guard deletionTombstones[index].terminalRevision < tombstone.terminalRevision else {
                return false
            }
            deletionTombstones[index] = tombstone
        } else {
            deletionTombstones.append(tombstone)
        }
        deletionTombstones.sort(by: DeletionTombstone.canonicalOrder)
        return true
    }

    private func appendSourceDeletionRecord(
        sourceObjectID: String,
        operation: SourceDeletionOperation,
        status: SourceDeletionStatus,
        affectedEventCount: Int,
        recomputedEventCount: Int = 0,
        deletedEventCount: Int = 0,
        rawDigestOnly: String?,
        at date: Date
    ) {
        sourceDeletionRecords.append(
            SourceDeletionRecord(
                sourceObjectID: sourceObjectID,
                operation: operation,
                status: status,
                affectedEventCount: affectedEventCount,
                recomputedEventCount: recomputedEventCount,
                deletedEventCount: deletedEventCount,
                createdAtEpochMilliseconds: Self.epochMilliseconds(date),
                rawDigestOnly: rawDigestOnly
            )
        )
    }

    private func persistTerminalDeletionAttempt(
        sourceObjectID: String,
        operation: SourceDeletionOperation,
        status: SourceDeletionStatus,
        affectedEventCount: Int,
        rawDigestOnly: String?,
        requestedAt: Date
    ) -> SourceDeletionResult {
        let previousRecords = sourceDeletionRecords
        appendSourceDeletionRecord(
            sourceObjectID: sourceObjectID,
            operation: operation,
            status: status,
            affectedEventCount: affectedEventCount,
            rawDigestOnly: rawDigestOnly,
            at: requestedAt
        )
        guard persist() else {
            sourceDeletionRecords = previousRecords
            return SourceDeletionResult(
                sourceObjectID: sourceObjectID,
                operation: operation,
                status: .persistenceFailed,
                affectedEventCount: 0
            )
        }
        return SourceDeletionResult(
            sourceObjectID: sourceObjectID,
            operation: operation,
            status: status,
            affectedEventCount: affectedEventCount
        )
    }

    private func removeOwnedRaw(at locator: String?) -> Bool {
        guard let locator, locator.hasPrefix("/") else { return false }
        let candidate = URL(fileURLWithPath: locator).standardizedFileURL
        guard candidate.deletingLastPathComponent() == mediaDirectory.standardizedFileURL,
              candidate.pathExtension == "enc",
              UUID(uuidString: candidate.deletingPathExtension().lastPathComponent) != nil else {
            return false
        }
        guard fileManager.fileExists(atPath: candidate.path) else { return true }
        do {
            try fileManager.removeItem(at: candidate)
            return !fileManager.fileExists(atPath: candidate.path)
        } catch {
            return false
        }
    }

    private func sourceMediaInventoryIsValid(_ sources: [SourceObject]) -> Bool {
        for source in sources where source.rawOwnership == .appOwnedEncrypted {
            if source.rawState == .deleted {
                guard source.sourceLocator == nil else { return false }
                continue
            }
            guard source.rawState == .available || source.rawState == .pendingCleanup,
                  let locator = source.sourceLocator,
                  locator.hasPrefix("/") else {
                return false
            }
            let candidate = URL(fileURLWithPath: locator).standardizedFileURL
            guard candidate.deletingLastPathComponent() == mediaDirectory.standardizedFileURL,
                  candidate.pathExtension == "enc",
                  UUID(uuidString: candidate.deletingPathExtension().lastPathComponent) != nil else {
                return false
            }
            guard fileManager.fileExists(atPath: candidate.path) else {
                if source.rawState == .pendingCleanup { continue }
                return false
            }
            guard let values = try? candidate.resourceValues(forKeys: [
                .isRegularFileKey,
                .isSymbolicLinkKey,
            ]),
            values.isRegularFile == true,
            values.isSymbolicLink != true,
            let data = try? Data(contentsOf: candidate),
            Self.sha256Hex(data) == source.rawCiphertextSHA256 else {
                return false
            }
        }
        return true
    }

    private func finalizeRawCleanup(
        sourceObjectID: String,
        operation: SourceDeletionOperation,
        affectedEventCount: Int,
        recomputedEventCount: Int = 0,
        deletedEventCount: Int = 0,
        completedAt: Date
    ) -> SourceDeletionResult {
        guard let index = sourceObjects.firstIndex(where: {
            $0.sourceObjectID == sourceObjectID && $0.rawState == .pendingCleanup
        }) else {
            return SourceDeletionResult(
                sourceObjectID: sourceObjectID,
                operation: operation,
                status: .completed,
                affectedEventCount: affectedEventCount,
                recomputedEventCount: recomputedEventCount,
                deletedEventCount: deletedEventCount
            )
        }
        let previousSources = sourceObjects
        let previousRecords = sourceDeletionRecords
        sourceObjects[index].rawState = .deleted
        sourceObjects[index].sourceLocator = nil
        appendSourceDeletionRecord(
            sourceObjectID: sourceObjectID,
            operation: operation,
            status: .completed,
            affectedEventCount: affectedEventCount,
            recomputedEventCount: recomputedEventCount,
            deletedEventCount: deletedEventCount,
            rawDigestOnly: sourceObjects[index].rawCiphertextSHA256,
            at: completedAt
        )
        guard persist() else {
            sourceObjects = previousSources
            sourceDeletionRecords = previousRecords
            return SourceDeletionResult(
                sourceObjectID: sourceObjectID,
                operation: operation,
                status: .pendingCleanup,
                affectedEventCount: affectedEventCount,
                recomputedEventCount: recomputedEventCount,
                deletedEventCount: deletedEventCount
            )
        }
        return SourceDeletionResult(
            sourceObjectID: sourceObjectID,
            operation: operation,
            status: .completed,
            affectedEventCount: affectedEventCount,
            recomputedEventCount: recomputedEventCount,
            deletedEventCount: deletedEventCount
        )
    }

    private func retryPendingRawCleanup() {
        let pending = sourceObjects.filter { $0.rawState == .pendingCleanup }
        guard !pending.isEmpty else { return }
        let previousSources = sourceObjects
        let previousRecords = sourceDeletionRecords
        var changed = false
        for source in pending where removeOwnedRaw(at: source.sourceLocator) {
            guard let index = sourceObjects.firstIndex(where: {
                $0.sourceObjectID == source.sourceObjectID
            }) else { continue }
            sourceObjects[index].rawState = .deleted
            sourceObjects[index].sourceLocator = nil
            appendSourceDeletionRecord(
                sourceObjectID: source.sourceObjectID,
                operation: source.state == .deleted ? .sourceCascade : .rawOnly,
                status: .completed,
                affectedEventCount: 0,
                rawDigestOnly: source.rawCiphertextSHA256,
                at: .now
            )
            changed = true
        }
        let previousAllowance = allowsDeletedSpacePersistence
        allowsDeletedSpacePersistence = allowsDeletedSpacePersistence || isLocalSpaceDeleted
        let persisted = !changed || persist()
        allowsDeletedSpacePersistence = previousAllowance
        if !persisted {
            sourceObjects = previousSources
            sourceDeletionRecords = previousRecords
        }
    }

    private static func epochMilliseconds(_ date: Date) -> Int64 {
        Int64((date.timeIntervalSince1970 * 1_000).rounded())
    }

    private static let recomputedSourceLabel = "多来源（已重算）"
    private static let userConfirmedSourceDeletedLabel = "用户确认（来源已删除）"

    private static func iso8601(_ date: Date) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter.string(from: date)
    }

    private static func sha256Hex(_ data: Data) -> String {
        SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
    }

    private static func migratedCoverageLineage(
        _ coverageLinks: [CoverageEventLink]
    ) -> (sources: [SourceObject], links: [EventSourceLink]) {
        var createdAtBySource: [String: Int64] = [:]
        var links: [EventSourceLink] = []
        var linkKeys: Set<String> = []
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        for link in coverageLinks {
            let linkedDate = formatter.date(from: link.linkedAt) ?? Date(timeIntervalSince1970: 0)
            let linkedAt = epochMilliseconds(linkedDate)
            for sourceObjectID in link.sourceObjectIDs {
                createdAtBySource[sourceObjectID] = min(
                    createdAtBySource[sourceObjectID] ?? linkedAt,
                    linkedAt
                )
                let linkKey = "\(link.eventID.uuidString.lowercased())\u{1f}\(sourceObjectID)"
                guard linkKeys.insert(linkKey).inserted else { continue }
                links.append(
                    EventSourceLink(
                        eventID: link.eventID,
                        sourceObjectID: sourceObjectID,
                        createdRevision: link.createdRevision,
                        createdAtEpochMilliseconds: linkedAt,
                        state: link.state == .active ? .active : .deleted,
                        deletedAtEpochMilliseconds: link.state == .active ? nil : linkedAt
                    )
                )
            }
        }
        let sources = createdAtBySource.map { sourceObjectID, createdAt in
            SourceObject(
                sourceObjectID: sourceObjectID,
                kind: .coverageEvidence,
                rawOwnership: .noRaw,
                rawState: .unavailable,
                sourceLocator: nil,
                rawCiphertextSHA256: nil,
                createdAtEpochMilliseconds: createdAt
            )
        }.sorted { $0.sourceObjectID < $1.sourceObjectID }
        return (sources, links)
    }

    private static func demoEvents(relativeTo date: Date) -> [MemoryEvent] {
        let calendar = Calendar.current
        let today = calendar.startOfDay(for: date)
        let yesterday = calendar.date(byAdding: .day, value: -1, to: today) ?? today
        func id(_ value: String) -> UUID {
            UUID(uuidString: value)!
        }
        return [
            MemoryEvent(
                id: id("00000000-0000-4000-8000-000000000001"),
                localDate: today,
                time: calendar.date(byAdding: .minute, value: 9 * 60 + 10, to: today),
                title: "整理今天的产品问题",
                detail: "把跨端体验拆成可以逐项验证的闭环。",
                factStatus: .confirmed,
                sourceLabel: "演示文字来源",
                captureKind: .text,
                userWords: "今天先把最影响真实体验的缺口找出来。",
                eventType: .decision,
                evidenceState: .userAsserted,
                importance: 80
            ),
            MemoryEvent(
                id: id("00000000-0000-4000-8000-000000000002"),
                localDate: today,
                time: calendar.date(byAdding: .minute, value: 12 * 60 + 30, to: today),
                title: "午间散步计划",
                detail: "日历计划只说明原定安排，是否实际发生仍需确认。",
                factStatus: .planned,
                sourceLabel: "演示日历来源",
                captureKind: .importFile,
                eventType: .activity,
                evidenceState: .observed,
                sensitivity: .confidential,
                importance: 45
            ),
            MemoryEvent(
                id: id("00000000-0000-4000-8000-000000000003"),
                localDate: today,
                time: calendar.date(byAdding: .minute, value: 15 * 60 + 5, to: today),
                title: "收到一条重要反馈",
                detail: "用户陈述已保存，等待后续补充来源或核验。",
                factStatus: .needsReview,
                sourceLabel: "演示分享来源",
                captureKind: .importFile,
                eventType: .communication,
                evidenceState: .userAsserted,
                importance: 70
            ),
            MemoryEvent(
                id: id("00000000-0000-4000-8000-000000000004"),
                localDate: today,
                time: calendar.date(byAdding: .minute, value: 18 * 60 + 40, to: today),
                title: "选择了一张照片",
                detail: "演示数据：照片来源已模拟；不会读取或保存真实照片。",
                factStatus: .confirmed,
                sourceLabel: "演示照片来源",
                captureKind: .photo,
                eventType: .experience,
                evidenceState: .observed,
                sensitivity: .confidential,
                importance: 55
            ),
            MemoryEvent(
                id: id("00000000-0000-4000-8000-000000000005"),
                localDate: yesterday,
                time: calendar.date(byAdding: .minute, value: 20 * 60 + 15, to: yesterday),
                title: "记录一段晚间想法",
                detail: "离线记录先保存在本机，等待后续整理。",
                factStatus: .userAsserted,
                sourceLabel: "演示离线来源",
                captureKind: .voice,
                userWords: "复杂问题可以拆成几个能验证的小步骤。",
                eventType: .experience,
                evidenceState: .userAsserted,
                sensitivity: .confidential,
                importance: 60
            ),
            MemoryEvent(
                id: id("00000000-0000-4000-8000-000000000006"),
                localDate: yesterday,
                time: calendar.date(byAdding: .minute, value: 22 * 60, to: yesterday),
                title: "受限来源示例",
                detail: "这条记录用于确认受限事件不会进入今日小结。",
                factStatus: .confirmed,
                sourceLabel: "演示受限来源",
                captureKind: .importFile,
                sensitivity: .restricted,
                importance: 90
            ),
        ]
    }
}

private struct ReuseAggregateKey: Hashable {
    let intent: ReuseIntent
    let outcome: ReuseOutcome?
    let userAction: ReuseUserAction?
    let resultCountBucket: ReuseResultCountBucket
}

private enum SourceCascadeAction {
    case delete
    case recompute
}

struct LocalStoreEnvelope: Codable {
    static let currentSchemaVersion = 8

    let schemaVersion: Int
    let events: [MemoryEvent]
    let coverageDays: [PersistedCoverageDay]
    let coverageEventLinks: [CoverageEventLink]
    let longTermMemories: [LongTermMemoryRecord]
    let deletionTombstones: [DeletionTombstone]
    let reuseAttempts: [ReuseAttemptRecord]
    let reuseOutcomes: [ReuseOutcomeRecord]
    let sourceObjects: [SourceObject]
    let eventSourceLinks: [EventSourceLink]
    let eventFieldEvidence: [EventFieldEvidence]
    let eventUserConfirmations: [EventUserConfirmation]
    let sourceDeletionRecords: [SourceDeletionRecord]

    init(
        schemaVersion: Int = LocalStoreEnvelope.currentSchemaVersion,
        events: [MemoryEvent],
        coverageDays: [PersistedCoverageDay],
        coverageEventLinks: [CoverageEventLink],
        longTermMemories: [LongTermMemoryRecord],
        deletionTombstones: [DeletionTombstone],
        reuseAttempts: [ReuseAttemptRecord],
        reuseOutcomes: [ReuseOutcomeRecord],
        sourceObjects: [SourceObject],
        eventSourceLinks: [EventSourceLink],
        eventFieldEvidence: [EventFieldEvidence],
        eventUserConfirmations: [EventUserConfirmation] = [],
        sourceDeletionRecords: [SourceDeletionRecord]
    ) {
        self.schemaVersion = schemaVersion
        self.events = events
        self.coverageDays = coverageDays
        self.coverageEventLinks = coverageEventLinks
        self.longTermMemories = longTermMemories
        self.deletionTombstones = deletionTombstones
        self.reuseAttempts = reuseAttempts
        self.reuseOutcomes = reuseOutcomes
        self.sourceObjects = sourceObjects
        self.eventSourceLinks = eventSourceLinks
        self.eventFieldEvidence = eventFieldEvidence
        self.eventUserConfirmations = eventUserConfirmations
        self.sourceDeletionRecords = sourceDeletionRecords
    }

    var isInternallyConsistent: Bool {
        guard events.map(\.id).count == Set(events.map(\.id)).count,
              coverageDays.map(\.dayID).count == Set(coverageDays.map(\.dayID)).count,
              deletionTombstones.allSatisfy(\.isInternallyValid),
              reuseAttempts.allSatisfy(\.isInternallyValid),
              sourceObjects.allSatisfy(\.isInternallyValid),
              eventSourceLinks.allSatisfy(\.isInternallyValid),
              eventFieldEvidence.allSatisfy(\.isInternallyValid),
              eventUserConfirmations.allSatisfy(\.isInternallyValid),
              sourceDeletionRecords.allSatisfy(\.isInternallyValid) else {
            return false
        }
        let eventIDs = Set(events.map(\.id))
        let tombstoneKeys = deletionTombstones.map(\.canonicalKey)
        guard tombstoneKeys.count == Set(tombstoneKeys).count,
              deletionTombstones.allSatisfy({
                  $0.objectType != DeletionTombstone.eventObjectType ||
                  !eventIDs.contains(UUID(uuidString: $0.objectID)!)
              }) else {
            return false
        }
        let sourceIDs = sourceObjects.map(\.sourceObjectID)
        let sourceByID = Dictionary(uniqueKeysWithValues: sourceObjects.map {
            ($0.sourceObjectID, $0)
        })
        let sourceLinkKeys = eventSourceLinks.map {
            "\($0.eventID.uuidString.lowercased())\u{1f}\($0.sourceObjectID)"
        }
        let fieldEvidenceKeys = eventFieldEvidence.map {
            "\($0.eventID.uuidString.lowercased())\u{1f}\($0.eventRevision)\u{1f}" +
                "\($0.field.rawValue)\u{1f}\($0.sourceObjectID)"
        }
        let userConfirmationKeys = eventUserConfirmations.map {
            "\($0.eventID.uuidString.lowercased())\u{1f}\($0.eventRevision)\u{1f}" +
                $0.confirmationID
        }
        let deletionRecordIDs = sourceDeletionRecords.map(\.deletionID)
        guard sourceIDs.count == Set(sourceIDs).count,
              sourceLinkKeys.count == Set(sourceLinkKeys).count,
              fieldEvidenceKeys.count == Set(fieldEvidenceKeys).count,
              userConfirmationKeys.count == Set(userConfirmationKeys).count,
              deletionRecordIDs.count == Set(deletionRecordIDs).count else {
            return false
        }
        for link in eventSourceLinks {
            guard let source = sourceByID[link.sourceObjectID] else { return false }
            if link.state == .active {
                guard eventIDs.contains(link.eventID), source.state == .active else { return false }
            }
        }
        for evidence in eventFieldEvidence {
            guard let source = sourceByID[evidence.sourceObjectID] else { return false }
            if evidence.state == .active {
                guard source.state == .active,
                      events.contains(where: {
                          $0.id == evidence.eventID &&
                              $0.revision == evidence.eventRevision
                      }),
                      eventSourceLinks.contains(where: {
                          $0.eventID == evidence.eventID &&
                              $0.sourceObjectID == evidence.sourceObjectID &&
                              $0.state == .active
                      }) else {
                    return false
                }
            }
        }
        let activeCompleteConfirmationGroups = Dictionary(grouping:
            eventUserConfirmations.filter {
                $0.state == .active && $0.completeFieldSet
            },
            by: {
                "\($0.eventID.uuidString.lowercased())\u{1f}\($0.eventRevision)"
            }
        )
        guard activeCompleteConfirmationGroups.values.allSatisfy({ confirmations in
            Set(confirmations.map {
                $0.confirmedFields.map(\.rawValue).sorted().joined(separator: "\u{1f}")
            }).count == 1
        }) else {
            return false
        }
        for confirmation in eventUserConfirmations where confirmation.state == .active {
            guard events.contains(where: {
                $0.id == confirmation.eventID &&
                    $0.revision == confirmation.eventRevision
            }) else {
                return false
            }
        }
        for source in sourceObjects {
            let hasTombstone = deletionTombstones.contains {
                $0.objectType == DeletionTombstone.sourceObjectType &&
                    $0.objectID == source.sourceObjectID
            }
            guard (source.state == .deleted) == hasTombstone else { return false }
            if source.state == .deleted,
               eventSourceLinks.contains(where: {
                   $0.sourceObjectID == source.sourceObjectID && $0.state == .active
               }) {
                return false
            }
        }
        var candidatesByDay: [String: [String: CoverageCandidateState]] = [:]
        var spacesByDay: [String: String] = [:]
        for day in coverageDays {
            guard !day.dayID.isEmpty, !day.ownerID.isEmpty, !day.spaceID.isEmpty,
                  !day.localDate.isEmpty, !day.timezone.isEmpty,
                  !day.registryVersion.isEmpty, !day.compiledAt.isEmpty else {
                return false
            }
            let candidates = day.candidates
            guard candidates.map(\.candidate.candidateID).count ==
                    Set(candidates.map(\.candidate.candidateID)).count else {
                return false
            }
            candidatesByDay[day.dayID] = Dictionary(
                uniqueKeysWithValues: candidates.map { ($0.candidate.candidateID, $0.state) }
            )
            spacesByDay[day.dayID] = day.spaceID
        }
        let linkKeys = coverageEventLinks.map { "\($0.dayID)\u{1f}\($0.candidateID)" }
        guard linkKeys.count == Set(linkKeys).count else { return false }
        for link in coverageEventLinks {
            guard link.createdRevision >= 1,
                  link.sourceObjectIDs.allSatisfy({ !$0.isEmpty }),
                  link.sourceObjectIDs.count == Set(link.sourceObjectIDs).count,
                  spacesByDay[link.dayID] == link.spaceID,
                  let candidateState = candidatesByDay[link.dayID]?[link.candidateID],
                  candidateState == .consumed || candidateState == .deleted else {
                return false
            }
            if link.state == .active && !eventIDs.contains(link.eventID) {
                return false
            }
        }
        guard longTermMemories.map(\.memoryID).count ==
                Set(longTermMemories.map(\.memoryID)).count else {
            return false
        }
        let memoriesByID = Dictionary(
            uniqueKeysWithValues: longTermMemories.map { ($0.memoryID, $0) }
        )
        let proposalKeys = longTermMemories.map {
            "\($0.spaceID)\u{1f}\($0.sourceEventID.uuidString)\u{1f}\($0.sourceEventRevision)\u{1f}\($0.type.rawValue)"
        }
        guard proposalKeys.count == Set(proposalKeys).count else { return false }
        let eventsByID = Dictionary(uniqueKeysWithValues: events.map { ($0.id, $0) })
        for memory in longTermMemories {
            guard memory.isInternallyValid else { return false }
            if memory.state == .active ||
                memory.state == .eligibleForMemoryCompiler ||
                memory.state == .candidateUserConfirmationRequired {
                guard let event = eventsByID[memory.sourceEventID],
                      event.revision == memory.sourceEventRevision else {
                    return false
                }
            }
            if let previousID = memory.supersedesMemoryID {
                guard let previous = memoriesByID[previousID],
                      previous.type == memory.type,
                      previous.supersededByMemoryID == memory.memoryID else {
                    return false
                }
            }
            if let nextID = memory.supersededByMemoryID {
                guard let next = memoriesByID[nextID],
                      next.type == memory.type,
                      next.supersedesMemoryID == memory.memoryID else {
                    return false
                }
            }
            var visited: Set<UUID> = [memory.memoryID]
            var cursor = memory.supersedesMemoryID
            while let current = cursor {
                guard visited.insert(current).inserted,
                      let prior = memoriesByID[current] else {
                    return false
                }
                cursor = prior.supersedesMemoryID
            }
        }
        let attemptIDs = reuseAttempts.map(\.attemptID)
        let outcomeIDs = reuseOutcomes.map(\.attemptID)
        guard attemptIDs.count == Set(attemptIDs).count,
              outcomeIDs.count == Set(outcomeIDs).count,
              Set(outcomeIDs).isSubset(of: Set(attemptIDs)) else {
            return false
        }
        let attemptsByID = Dictionary(
            uniqueKeysWithValues: reuseAttempts.map { ($0.attemptID, $0) }
        )
        guard reuseOutcomes.allSatisfy({
            guard let attempt = attemptsByID[$0.attemptID] else { return false }
            return $0.submittedAt >= attempt.createdAt
        }) else {
            return false
        }
        return true
    }
}

private struct LegacyLocalStoreEnvelopeV7: Codable {
    let schemaVersion: Int
    let events: [MemoryEvent]
    let coverageDays: [PersistedCoverageDay]
    let coverageEventLinks: [CoverageEventLink]
    let longTermMemories: [LongTermMemoryRecord]
    let deletionTombstones: [DeletionTombstone]
    let reuseAttempts: [ReuseAttemptRecord]
    let reuseOutcomes: [ReuseOutcomeRecord]
    let sourceObjects: [SourceObject]
    let eventSourceLinks: [EventSourceLink]
    let eventFieldEvidence: [EventFieldEvidence]
    let sourceDeletionRecords: [SourceDeletionRecord]
}

private struct LegacyLocalStoreEnvelopeV6: Codable {
    let schemaVersion: Int
    let events: [MemoryEvent]
    let coverageDays: [PersistedCoverageDay]
    let coverageEventLinks: [CoverageEventLink]
    let longTermMemories: [LongTermMemoryRecord]
    let deletionTombstones: [DeletionTombstone]
    let reuseAttempts: [ReuseAttemptRecord]
    let reuseOutcomes: [ReuseOutcomeRecord]
    let sourceObjects: [SourceObject]
    let eventSourceLinks: [EventSourceLink]
    let sourceDeletionRecords: [SourceDeletionRecord]
}

private struct LegacyLocalStoreEnvelopeV5: Codable {
    let schemaVersion: Int
    let events: [MemoryEvent]
    let coverageDays: [PersistedCoverageDay]
    let coverageEventLinks: [CoverageEventLink]
    let longTermMemories: [LongTermMemoryRecord]
    let deletionTombstones: [DeletionTombstone]
    let reuseAttempts: [ReuseAttemptRecord]
    let reuseOutcomes: [ReuseOutcomeRecord]
}

private struct LegacyLocalStoreEnvelopeV4: Codable {
    let schemaVersion: Int
    let events: [MemoryEvent]
    let coverageDays: [PersistedCoverageDay]
    let coverageEventLinks: [CoverageEventLink]
    let longTermMemories: [LongTermMemoryRecord]
    let deletionTombstones: [DeletionTombstone]
}

private struct LegacyLocalStoreEnvelopeV3: Codable {
    let schemaVersion: Int
    let events: [MemoryEvent]
    let coverageDays: [PersistedCoverageDay]
    let coverageEventLinks: [CoverageEventLink]
    let longTermMemories: [LongTermMemoryRecord]
}

private struct LegacyLocalStoreEnvelopeV2: Codable {
    let schemaVersion: Int
    let events: [MemoryEvent]
    let coverageDays: [PersistedCoverageDay]
    let coverageEventLinks: [CoverageEventLink]
}

public struct KeyStore: KeyMaterialStore, Sendable {
    private let service = "com.ameme.ios.local-event-key"
    private let account = "default"

    public init() {}

    public func loadOrCreateKey() throws -> SymmetricKey {
        if let data = try read() { return SymmetricKey(data: data) }
        let key = SymmetricKey(size: .bits256)
        let rawData = key.withUnsafeBytes { Data($0) }
        try write(rawData)
        return key
    }

    private func read() throws -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else { throw StorageError.keychain(status) }
        return result as? Data
    }

    private func write(_ data: Data) throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
        ]
        let status = SecItemAdd(query as CFDictionary, nil)
        guard status == errSecSuccess || status == errSecDuplicateItem else {
            throw StorageError.keychain(status)
        }
    }
}

public enum StorageError: Error {
    case keychain(OSStatus)
}

private struct LocalExportEnvelope: Codable {
    let schemaVersion: Int
    let space: String
    let exportedAt: Date
    let eventCount: Int
    let events: [LocalExportEvent]
}

private struct LocalExportEvent: Codable {
    let id: UUID
    let localDate: Date
    let time: Date?
    let title: String
    let detail: String
    let factStatus: String
    let sourceLabel: String
    let captureKind: CaptureKind
    let isLocalOnly: Bool
    let userWords: String?
    let revision: Int
    let eventType: String
    let evidenceState: String
    let sensitivity: String
    let importance: Int

    init(_ event: MemoryEvent) {
        id = event.id
        localDate = event.localDate
        time = event.time
        title = event.title
        detail = event.detail
        factStatus = event.factStatus.wireValue
        sourceLabel = event.sourceLabel
        captureKind = event.captureKind
        isLocalOnly = event.isLocalOnly
        userWords = event.userWords
        revision = event.revision
        eventType = event.eventType.wireValue
        evidenceState = event.evidenceState.wireValue
        sensitivity = event.sensitivity.wireValue
        importance = event.importance
    }
}
