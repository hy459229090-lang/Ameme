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
    @Published public private(set) var storageState: StorageState = .loading
    @Published public private(set) var isDemoMode = false

    public enum StorageState: Equatable {
        case loading
        case ready
        case recoverableError
    }

    private let fileManager: FileManager
    private let fileURL: URL
    private let mediaDirectory: URL
    private let keyStore: any KeyMaterialStore
    private var key: SymmetricKey?

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
        self.fileURL = root.appendingPathComponent("events.enc")
        self.mediaDirectory = root.appendingPathComponent("media", isDirectory: true)
        load()
    }

    public var mode: ExperienceMode {
        switch storageState {
        case .loading: return .loading
        case .recoverableError: return .recoverableError
        case .ready:
            return ExperienceMode.ready.resolvedFor(
                eventCount: events.count,
                deriveFromEvents: true
            )
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
        sortEvents()
        if !persist() { events = previousEvents }
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
        sortEvents()
        guard persist() else {
            events = previousEvents
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

    @discardableResult
    public func addUserWords(_ text: String, to id: UUID) -> Bool {
        guard let index = events.firstIndex(where: { $0.id == id }) else { return false }
        let cleaned = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleaned.isEmpty else { return false }
        let previous = events[index]
        events[index].userWords = cleaned
        events[index].revision += 1
        guard persist() else {
            events[index] = previous
            return false
        }
        return true
    }

    @discardableResult
    public func updateFactStatus(_ status: FactStatus, to id: UUID) -> Bool {
        guard let index = events.firstIndex(where: { $0.id == id }) else { return false }
        let previous = events[index]
        guard events[index].factStatus != status else { return true }
        events[index].factStatus = status
        events[index].revision += 1
        guard persist() else {
            events[index] = previous
            return false
        }
        return true
    }

    @discardableResult
    public func delete(id: UUID) -> Bool {
        let oldCount = events.count
        let deletedEvent = events.first { $0.id == id }
        events.removeAll { $0.id == id }
        guard events.count != oldCount else { return false }
        guard persist() else {
            if let deletedEvent { events.append(deletedEvent); sortEvents() }
            return false
        }
        if let deletedEvent { removeOwnedMedia(for: deletedEvent) }
        return true
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
        do {
            key = try keyStore.loadOrCreateKey()
            try fileManager.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
            try fileManager.createDirectory(at: mediaDirectory, withIntermediateDirectories: true)
            guard fileManager.fileExists(atPath: fileURL.path) else {
                events = []
                storageState = .ready
                return
            }
            let encrypted = try Data(contentsOf: fileURL)
            let box = try AES.GCM.SealedBox(combined: encrypted)
            let clear = try AES.GCM.open(box, using: key!)
            events = try JSONDecoder().decode([MemoryEvent].self, from: clear)
            sortEvents()
            storageState = .ready
        } catch {
            events = []
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
        guard let key else {
            storageState = .recoverableError
            return false
        }
        do {
            try fileManager.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
            let clear = try JSONEncoder().encode(events)
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
