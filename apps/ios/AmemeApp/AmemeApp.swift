 #if os(iOS)
@preconcurrency import AVFoundation
import Combine
import EventKit
import Photos
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

import AmemeShared


@main
struct AmemeApp: App {
    @StateObject private var model = AppModel()

    init() {
        if ProcessInfo.processInfo.environment["AMEME_UI_TEST_RESET_ONBOARDING"] == "1" {
            UserDefaults.standard.removeObject(forKey: "ameme.onboarding.completed")
        }
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .onOpenURL { url in
                    model.handleIncomingShareURL(url)
                }
        }
    }
}

@MainActor
final class AppModel: ObservableObject {
    @Published var store: LocalMemoryStore
    @Published var notice: String?
    @Published var pendingIncomingShare: PendingIncomingShare?
    @Published private(set) var pendingExportAvailable = false
    @Published private(set) var agentExperienceConnection: AgentExperienceConnection?
    private var storeCancellable: AnyCancellable?
    private let incomingShareStore: IncomingShareHandoffStore
    private let pendingExportStore: PendingExportStore
    private let agentExperienceStore: AgentExperienceStore
    private let agentExperienceConnector: any AgentExperienceConnector

    init(agentExperienceConnector: any AgentExperienceConnector = BonjourAgentExperienceConnector()) {
        let initialStore = LocalMemoryStore()
        store = initialStore
        pendingIncomingShare = nil
        incomingShareStore = IncomingShareHandoffStore(rootDirectory: Self.incomingShareRoot())
        pendingExportStore = PendingExportStore(rootDirectory: Self.pendingExportRoot())
        agentExperienceStore = AgentExperienceStore()
        self.agentExperienceConnector = agentExperienceConnector
        do {
            agentExperienceConnection = try agentExperienceStore.load()
        } catch {
            agentExperienceConnection = nil
            notice = "设备连接状态不可用；已安全断开体验连接。"
        }
        storeCancellable = initialStore.objectWillChange.sink { [weak self] _ in
            self?.objectWillChange.send()
        }
        restorePendingExport()
        Task { @MainActor [weak self] in
            self?.restorePendingIncomingShare()
        }
    }

    private func restorePendingExport() {
        do {
            pendingExportAvailable = try pendingExportStore.load() != nil
            if pendingExportAvailable {
                notice = "已恢复上次未完成的导出；可以重新分享或保存。"
            }
        } catch {
            // Keep a corrupt or unavailable snapshot fail-closed. The source
            // Event Node remains untouched and the user can generate a new one.
            pendingExportAvailable = pendingExportStore.exists
            notice = "上次导出恢复快照不可用；本机原始记录未改变，请重新生成。"
        }
    }

    private func restorePendingIncomingShare() {
        guard pendingIncomingShare == nil else { return }
        for id in incomingShareStore.pendingIDs() {
            do {
                let handoff = try incomingShareStore.read(id: id)
                pendingIncomingShare = PendingIncomingShare(
                    id: id,
                    payload: handoff.payload,
                    fileData: handoff.fileData,
                )
                notice = "已恢复上次未完成的分享；确认前不会创建事件。"
                return
            } catch {
                // A malformed or truncated handoff cannot be safely presented;
                // discard only that bounded handoff and keep the local event node.
                incomingShareStore.remove(id: id)
            }
        }
    }

    func handleIncomingShareURL(_ url: URL) {
        guard let id = incomingShareStore.id(from: url) else { return }
        // Share extensions and scene activation can deliver the same URL more than
        // once. Keep one review sheet and one handoff record for that UUID.
        if pendingIncomingShare?.id == id { return }
        do {
            let handoff = try incomingShareStore.read(id: id)
            pendingIncomingShare = PendingIncomingShare(
                id: id,
                payload: handoff.payload,
                fileData: handoff.fileData,
            )
        } catch {
            incomingShareStore.remove(id: id)
            notice = "分享内容不可用；本机没有创建事件，请重新分享。"
        }
    }

    @discardableResult
    func savePendingIncomingShare() -> Bool {
        guard let pending = pendingIncomingShare else { return false }
        let saved: Bool
        switch pending.payload.kind {
        case .text:
            saved = addText(pending.payload.text ?? "")
        case .image:
            saved = addMedia(
                pending.fileData ?? Data(),
                kind: .photo,
                fileExtension: pending.payload.kind.suggestedFileExtension,
            )
        case .pdf:
            saved = addMedia(
                pending.fileData ?? Data(),
                kind: .importFile,
                fileExtension: pending.payload.kind.suggestedFileExtension,
            )
        }
        guard saved else { return false }
        incomingShareStore.remove(id: pending.id)
        pendingIncomingShare = nil
        notice = store.isDemoMode
            ? "已加入演示数据；不会写入真实本机记录。"
            : "分享内容已保存到本机。"
        return true
    }

    func discardPendingIncomingShare() {
        guard let pending = pendingIncomingShare else { return }
        incomingShareStore.remove(id: pending.id)
        pendingIncomingShare = nil
        notice = "已取消分享；没有创建事件。"
    }

    private static func incomingShareRoot() -> URL {
        let fileManager = FileManager.default
        #if os(iOS)
        if let groupRoot = IncomingShareHandoffStore.appGroupRoot(fileManager: fileManager) {
            return groupRoot
        }
        #endif
        let support = (try? fileManager.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )) ?? fileManager.temporaryDirectory
        return support.appendingPathComponent("Ameme/IncomingShares", isDirectory: true)
    }

    private static func pendingExportRoot() -> URL {
        let fileManager = FileManager.default
        let support = (try? fileManager.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        )) ?? fileManager.temporaryDirectory
        return support.appendingPathComponent("Ameme/PendingActions", isDirectory: true)
    }

    func addText(_ text: String) -> Bool {
        guard store.addText(text) != nil else {
            notice = store.storageState == .ready ? "请输入一条记录后再保存。" : "本机加密写入失败；请重试。"
            return false
        }
        notice = store.isDemoMode ? "已加入演示数据；不会写入真实本机记录。" : "已保存到本机"
        return true
    }

    @discardableResult
    func addMedia(_ data: Data, kind: CaptureKind, fileExtension: String) -> Bool {
        let event = store.addMedia(data, kind: kind, fileExtension: fileExtension)
        guard store.storageState == .ready else {
            notice = "本机加密写入失败；媒体事件没有确认保存，请重试。"
            return false
        }
        if store.isDemoMode {
            notice = "已加入演示数据；不会写入真实本机媒体。"
        } else if event.sourceLocator == nil {
            notice = "事件已保存，但媒体文件尚未写入本机；可以稍后重试导入。"
        } else {
            notice = "已保存到本机，后续处理将在后台进行。"
        }
        return true
    }

    @discardableResult
    func addPhotoReference(_ identifier: String?) -> Bool {
        guard store.addPhotoReference(identifier) != nil else {
            notice = store.storageState == .ready
                ? "照片没有提供稳定引用；请从系统照片库重新选择。"
                : "本机加密写入失败；照片引用没有保存，请重试。"
            return false
        }
        notice = store.isDemoMode
            ? "已加入演示数据；不会读取或保存真实照片。"
            : "已保存照片引用；不会复制系统照片原图。"
        return true
    }

    @discardableResult
    func addImportedText(_ text: String) -> Bool {
        guard store.addImportedText(text) != nil else {
            notice = store.storageState == .ready ? "文件中没有可保存的文字。" : "本机加密写入失败；请重试。"
            return false
        }
        notice = store.isDemoMode ? "已加入演示数据；不会写入真实本机记录。" : "已保存到本机"
        return true
    }

    func calendarOptionsForImport() async -> [CalendarImportOption] {
        let eventStore = EKEventStore()
        do {
            guard try await requestCalendarAccess(eventStore) else {
                notice = "日历只读权限未授予；没有读取或保存任何日历内容。"
                return []
            }
            return eventStore.calendars(for: .event)
                .compactMap { calendar in
                    let title = calendar.title.trimmingCharacters(in: .whitespacesAndNewlines)
                    let identifier = calendar.calendarIdentifier.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !title.isEmpty, !identifier.isEmpty else { return nil }
                    return CalendarImportOption(id: identifier, title: title)
                }
                .sorted { $0.title.localizedStandardCompare($1.title) == .orderedAscending }
        } catch {
            notice = "日历列表暂时无法读取；没有保存任何日历内容。"
            return []
        }
    }

    @discardableResult
    func importCalendar(calendarIDs: Set<String>, rangeDays: Int) async -> Bool {
        guard !calendarIDs.isEmpty, [1, 7, 31].contains(rangeDays) else {
            notice = "请选择至少一个日历和有效的日期范围。"
            return false
        }
        let eventStore = EKEventStore()
        do {
            guard try await requestCalendarAccess(eventStore) else {
                notice = "日历只读权限未授予；没有读取或保存任何日历内容。"
                return false
            }
            let calendars = eventStore.calendars(for: .event).filter {
                calendarIDs.contains($0.calendarIdentifier)
            }
            guard !calendars.isEmpty else {
                notice = "所选日历已不可用；请重新选择后重试。"
                return false
            }
            let start = Calendar.current.startOfDay(for: .now)
            let end = Calendar.current.date(byAdding: .day, value: rangeDays, to: start) ?? start
            let predicate = eventStore.predicateForEvents(withStart: start, end: end, calendars: calendars)
            let drafts = eventStore.events(matching: predicate).compactMap { calendarEvent -> MemoryEventDraft? in
                let title = calendarEvent.title?.trimmingCharacters(in: .whitespacesAndNewlines)
                let identifier = calendarEvent.eventIdentifier?.trimmingCharacters(in: .whitespacesAndNewlines)
                guard let title, !title.isEmpty, let identifier, !identifier.isEmpty else { return nil }
                return MemoryEventDraft(
                    localDate: calendarEvent.startDate,
                    time: calendarEvent.isAllDay ? nil : calendarEvent.startDate,
                    title: title,
                    detail: calendarEvent.notes?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false
                        ? calendarEvent.notes!
                        : "来自你主动选择的日历计划。",
                    factStatus: .planned,
                    sourceLabel: "系统日历（只读）",
                    sourceLocator: "eventkit://\(identifier)",
                    captureKind: .importFile,
                    eventType: .activity,
                    evidenceState: .observed,
                    sensitivity: .confidential
                )
            }
            guard !drafts.isEmpty else {
                notice = "当前选择的日历和日期范围内没有可导入的计划。"
                return false
            }
            guard let saved = store.addBatch(Array(drafts.prefix(200))), store.storageState == .ready else {
                notice = "本机加密写入失败；日历计划整批没有确认保存，请重试。"
                return false
            }
            let savedCount = saved.count
            guard savedCount > 0 else {
                notice = "当前范围内没有新的日历计划需要保存。"
                return false
            }
            notice = store.isDemoMode
                ? "已加入演示数据 \(savedCount) 条日历计划；不会写入真实本机记录。"
                : "已保存 \(savedCount) 条日历计划；它们仍是“计划，未确认发生”。"
            return true
        } catch {
            notice = "日历列表暂时无法读取；没有保存任何日历内容。"
            return false
        }
    }

    private func requestCalendarAccess(_ eventStore: EKEventStore) async throws -> Bool {
        try await withCheckedThrowingContinuation { continuation in
            eventStore.requestFullAccessToEvents { granted, error in
                if let error {
                    continuation.resume(throwing: error)
                } else {
                    continuation.resume(returning: granted)
                }
            }
        }
    }

    func saveAddendum(_ text: String, to eventID: UUID) {
        if store.addUserWords(text, to: eventID) {
        notice = store.isDemoMode ? "演示数据已更新；不会写入真实本机记录。" : "补充已保存，小结需要更新。"
        } else {
            notice = store.storageState == .ready ? "补充内容不能为空。" : "本机加密写入失败；补充没有保存，请重试。"
        }
    }

    func updateFactStatus(_ status: FactStatus, for eventID: UUID) {
        if store.updateFactStatus(status, to: eventID) {
            notice = store.isDemoMode ? "演示数据状态已更新；不会写入真实本机记录。" : "状态已更新；小结需要重新计算。"
        } else {
            notice = "状态尚未更新；请重试。"
        }
    }

    func createExportFile() -> URL? {
        let data: Data
        do {
            let recovered: Data?
            do {
                recovered = try pendingExportStore.load()
            } catch {
                // A corrupt snapshot is never presented as a valid export. It
                // can be replaced from the unchanged Event Node instead.
                recovered = nil
            }
            if let recovered {
                data = recovered
                pendingExportAvailable = true
                notice = "已恢复上次未完成的导出；不读取或修改本机事件。"
            } else {
                data = try store.exportData()
                try pendingExportStore.save(data)
                pendingExportAvailable = true
                notice = store.isDemoMode
                    ? "已生成演示数据导出；不会修改真实本机记录。"
                    : "已生成结构化导出；不包含受限事件或原始媒体文件。"
            }
            let url = FileManager.default.temporaryDirectory
                .appendingPathComponent("ameme-export-\(UUID().uuidString).json")
            try data.write(to: url, options: .atomic)
            return url
        } catch {
            notice = "导出尚未生成；本机数据保持不变，请重试。"
            return nil
        }
    }

    func clearPendingExportSnapshot() {
        do {
            try pendingExportStore.clear()
            pendingExportAvailable = false
            notice = "已清除导出恢复快照；本机事件没有改变。"
        } catch {
            notice = "导出恢复快照尚未清除，请稍后重试。"
        }
    }

    func connectAgentExperience(method: AgentConnectionMethod) {
        let connection = AgentExperienceConnection.simulatedDemo(method: method)
        do {
            try agentExperienceStore.save(connection)
            agentExperienceConnection = connection
            notice = "体验连接已保存；不会建立真实网络连接或访问事件。"
        } catch {
            notice = "体验连接状态没有保存；本机事件没有改变，请重试。"
        }
    }

    func resolveAgentExperienceCandidate(
        method: AgentConnectionMethod
    ) async throws -> AgentExperienceCandidate {
        try await agentExperienceConnector.resolve(method: method)
    }

    func resolveAgentExperienceCandidate(
        pairingPayload: String
    ) async throws -> AgentExperienceCandidate {
        try await agentExperienceConnector.resolve(pairingPayload: pairingPayload)
    }

    @discardableResult
    func connectAgentExperience(
        candidate: AgentExperienceCandidate
    ) async throws -> AgentExperienceConnection {
        let connection = try await agentExperienceConnector.connect(candidate: candidate)
        do {
            try agentExperienceStore.save(connection)
        } catch {
            await agentExperienceConnector.disconnect(connection: connection)
            throw error
        }
        agentExperienceConnection = connection
        notice = "已建立授权连接；仅按显示的能力范围工作。"
        return connection
    }

    func disconnectAgentExperience() async {
        if let connection = agentExperienceConnection {
            await agentExperienceConnector.disconnect(connection: connection)
        }
        agentExperienceStore.clear()
        agentExperienceConnection = nil
        notice = "已断开体验连接；本机事件没有改变。"
    }

    func enterDemoMode() {
        store.enterDemoMode()
        clearPendingExportSnapshot()
        notice = "已载入演示数据；不会修改真实本机记录。"
    }

    func exitDemoMode() {
        store.exitDemoMode()
        clearPendingExportSnapshot()
        notice = "已恢复真实本机记录。"
    }
}

struct CalendarImportOption: Identifiable, Hashable {
    let id: String
    let title: String
}

struct PendingIncomingShare: Identifiable {
    let id: UUID
    let payload: IncomingSharePayload
    let fileData: Data?
}

private struct PendingImport {
    let kind: CaptureKind
    let data: Data
    let fileExtension: String
    let fileName: String

    var textPreview: String? {
        guard kind == .importFile else { return nil }
        return String(data: data, encoding: .utf8)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .prefix(800)
            .description
    }
}

enum AppDestination: Hashable {
    case search
    case settings
    case event(UUID)
    case delete(UUID)
}

struct RootView: View {
    @EnvironmentObject private var model: AppModel
    @AppStorage("ameme.onboarding.completed") private var onboardingCompleted = false
    @State private var path: [AppDestination] = []

    var body: some View {
        NavigationStack(path: $path) {
            Group {
                if onboardingCompleted {
                    TodayView(
                        onSearch: { path.append(.search) },
                        onSettings: { path.append(.settings) },
                        onEvent: { path.append(.event($0)) }
                    )
                } else {
                    OnboardingView {
                        onboardingCompleted = true
                    }
                }
            }
            .navigationDestination(for: AppDestination.self) { destination in
                switch destination {
                case .search:
                    SearchView(
                        onSettings: { path.append(.settings) },
                        onEvent: { path.append(.event($0)) }
                    )
                case .settings:
                    SettingsView()
                case let .event(id):
                    EventDetailView(eventID: id) {
                        path.append(.delete(id))
                    }
                case let .delete(id):
                    DeleteView(eventID: id) {
                        path.removeAll { $0 == .delete(id) || $0 == .event(id) }
                    }
                }
            }
        }
        .tint(AmemeStyle.teal)
        .alert("提示", isPresented: Binding(
            get: { model.notice != nil },
            set: { if !$0 { model.notice = nil } }
        )) {
            Button("知道了") { model.notice = nil }
        } message: {
            Text(model.notice ?? "")
        }
        .sheet(item: $model.pendingIncomingShare) { pending in
            IncomingShareReviewSheet(pending: pending)
                .environmentObject(model)
        }
    }
}

private struct IncomingShareReviewSheet: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    let pending: PendingIncomingShare

    private var kindLabel: String {
        switch pending.payload.kind {
        case .text: "文字"
        case .image: "图片"
        case .pdf: "PDF 文档"
        }
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("确认分享内容") {
                    Label(kindLabel, systemImage: "square.and.arrow.down")
                    if let displayName = pending.payload.displayName, !displayName.isEmpty {
                        Text(displayName)
                            .font(.subheadline)
                            .foregroundStyle(AmemeStyle.secondaryText)
                    }
                    if let text = pending.payload.text {
                        Text(text)
                            .textSelection(.enabled)
                    } else {
                        Text("确认后会把文件保存为本机加密来源；不会在确认前创建事件或生成转写。")
                            .font(.footnote)
                            .foregroundStyle(AmemeStyle.secondaryText)
                    }
                }
                Section {
                    Button("保存到本机") {
                        if model.savePendingIncomingShare() {
                            dismiss()
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .frame(maxWidth: .infinity)
                    Button("取消分享") {
                        model.discardPendingIncomingShare()
                        dismiss()
                    }
                    .frame(maxWidth: .infinity)
                }
            }
            .navigationTitle("分享预览")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") {
                        model.discardPendingIncomingShare()
                        dismiss()
                    }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }
}

enum AmemeStyle {
    static let teal = Color(red: 0.051, green: 0.420, blue: 0.357)
    static let secondaryText = Color.secondary
}

struct OnboardingView: View {
    let onContinue: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Spacer(minLength: 24)
                Text("自动整理你的一天")
                    .font(.largeTitle.weight(.semibold))
                Text("从你主动记录、选择或导入的内容开始。事件优先保存在本机加密空间，你可以随时查看和删除。")
                    .font(.body)
                    .foregroundStyle(AmemeStyle.secondaryText)
                OnboardingSourceRow(icon: "square.and.pencil", title: "输入一句话", detail: "无需权限，先保存为本机记录")
                OnboardingSourceRow(icon: "photo.on.rectangle", title: "用照片开始", detail: "系统照片选择器按次授权，不读取整个照片库")
                OnboardingSourceRow(icon: "mic", title: "说一句", detail: "点击录音时才请求麦克风；也可以选择已有音频")
                OnboardingSourceRow(icon: "calendar", title: "导入计划", detail: "只在你主动导入时读取选定范围的日历")
                Button("查看今天", action: onContinue)
                    .buttonStyle(.borderedProminent)
                    .controlSize(.large)
                    .frame(maxWidth: .infinity)
                    .padding(.top, 8)
                Text("拒绝任何来源都不阻止文字记录，也不要求注册云账户才能进入本机“今天”。")
                    .font(.footnote)
                    .foregroundStyle(AmemeStyle.secondaryText)
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 28)
        }
        .navigationTitle("开始使用")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct OnboardingSourceRow: View {
    let icon: String
    let title: String
    let detail: String

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: icon)
                .font(.title3)
                .foregroundStyle(AmemeStyle.teal)
                .frame(width: 28)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 4) {
                Text(title).font(.headline)
                Text(detail).font(.subheadline).foregroundStyle(AmemeStyle.secondaryText)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 16))
    }
}

struct TodayView: View {
    @EnvironmentObject private var model: AppModel
    let onSearch: () -> Void
    let onSettings: () -> Void
    let onEvent: (UUID) -> Void
    @State private var showingCapture = false
    @State private var summaryExpanded = true

    private var today: Date { .now }
    private var todayEvents: [MemoryEvent] { model.store.events(on: today) }
    private var summary: DaySummary { model.store.summary(for: today) }

    var body: some View {
        let canCapture = model.store.storageState == .ready
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                Text(Date.now.formatted(.dateTime.year().month().day().weekday(.wide)))
                    .font(.subheadline)
                    .foregroundStyle(AmemeStyle.secondaryText)
                    .padding(.bottom, 14)
                StateNoticeView(mode: model.store.mode, onAction: model.store.retryLoad)
                if model.store.isDemoMode {
                    DemoModeNotice()
                        .padding(.bottom, 12)
                }
                if todayEvents.isEmpty {
                    EmptyMessageView(
                        title: "从一件真实的事开始",
                        detail: "点击右下角“记录”，文字、语音、照片和导入都会先保存到本机。"
                    )
                } else {
                    let readyCount = todayEvents.filter { $0.factStatus != .processing }.count
                    Text("已整理 \(readyCount) 件事")
                        .font(.subheadline)
                        .foregroundStyle(AmemeStyle.secondaryText)
                        .padding(.bottom, 8)
                    ForEach(todayEvents) { event in
                        EventRowView(event: event) { onEvent(event.id) }
                    }
                }
                DaySummaryView(summary: summary, isExpanded: $summaryExpanded)
                    .padding(.top, 22)
            }
            .padding(.horizontal, 20)
            .padding(.top, 8)
            .padding(.bottom, 96)
        }
        .navigationTitle("今天")
        .navigationBarTitleDisplayMode(.large)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button(action: onSearch) {
                    Image(systemName: "magnifyingglass")
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .accessibilityLabel("搜索历史记录")
                .accessibilityIdentifier("today.search")
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button(action: onSettings) {
                    Image(systemName: "gearshape")
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .accessibilityLabel("打开设置")
                .accessibilityIdentifier("today.settings")
            }
        }
        .safeAreaInset(edge: .bottom, spacing: 0) {
            HStack {
                Spacer()
                Button { showingCapture = true } label: {
                    Label("记录", systemImage: "plus")
                        .labelStyle(.titleAndIcon)
                        .font(.headline)
                        .padding(.horizontal, 18)
                        .padding(.vertical, 13)
                }
                .buttonStyle(.borderedProminent)
                .clipShape(Capsule())
                .accessibilityLabel("记录一件事")
                .accessibilityHint(canCapture ? "打开记录方式" : "本机存储恢复后可用")
                .disabled(!canCapture)
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
            .background(.ultraThinMaterial)
        }
        .sheet(isPresented: $showingCapture) {
            CaptureSheet()
                .environmentObject(model)
        }
    }
}

struct SearchView: View {
    @EnvironmentObject private var model: AppModel
    let onSettings: () -> Void
    let onEvent: (UUID) -> Void
    @State private var query = ""
    @State private var startDate: Date?
    @State private var endDate: Date?
    @State private var showingDateFilter = false

    private var results: [MemoryEvent] {
        model.store.search(query: query, startDate: startDate, endDate: endDate)
    }

    private var groups: [(Date, [MemoryEvent])] {
        Dictionary(grouping: results) { Calendar.current.startOfDay(for: $0.localDate) }
            .map { ($0.key, $0.value) }
            .sorted { $0.0 > $1.0 }
    }

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                Text(rangeDescription)
                    .font(.footnote)
                    .foregroundStyle(AmemeStyle.secondaryText)
                    .padding(.bottom, 12)
                StateNoticeView(mode: model.store.mode, onAction: model.store.retryLoad)
                if model.store.isDemoMode {
                    DemoModeNotice()
                        .padding(.bottom, 12)
                }
                if groups.isEmpty {
                    EmptyMessageView(
                        title: query.isEmpty && startDate == nil && endDate == nil ? "当前可见范围内没有记录" : "当前条件没有结果",
                        detail: query.isEmpty
                            ? "这只说明当前本机与获准范围没有可见事件。"
                            : "未找到“\(query)”；这不代表这件事从未发生。"
                    )
                } else {
                    ForEach(groups, id: \.0) { date, events in
                        Text(date.formatted(.dateTime.year().month().day().weekday(.wide)))
                            .font(.headline)
                            .padding(.top, 14)
                            .padding(.bottom, 4)
                        ForEach(events) { event in
                            EventRowView(event: event) { onEvent(event.id) }
                        }
                    }
                }
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 24)
        }
        .navigationTitle("搜索")
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "搜索历史记录")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { showingDateFilter = true } label: {
                    Image(systemName: startDate != nil || endDate != nil ? "calendar.badge.checkmark" : "calendar")
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .accessibilityLabel("选择日期")
            }
            ToolbarItem(placement: .topBarTrailing) {
                Button(action: onSettings) {
                    Image(systemName: "gearshape")
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .accessibilityLabel("打开设置")
                .accessibilityIdentifier("search.settings")
            }
        }
        .sheet(isPresented: $showingDateFilter) {
            NavigationStack {
                Form {
                    Section("日期范围") {
                        DatePicker(
                            "开始日期",
                            selection: Binding(
                                get: { startDate ?? .now },
                                set: { newValue in
                                    startDate = newValue
                                    if let endDate, endDate < newValue { self.endDate = newValue }
                                }
                            ),
                            displayedComponents: .date
                        )
                        DatePicker(
                            "结束日期",
                            selection: Binding(
                                get: { endDate ?? startDate ?? .now },
                                set: { newValue in
                                    if let startDate, newValue < startDate {
                                        self.startDate = newValue
                                    }
                                    endDate = newValue
                                }
                            ),
                            displayedComponents: .date
                        )
                    }
                    if startDate != nil || endDate != nil {
                        Button("清除日期") {
                            startDate = nil
                            endDate = nil
                            showingDateFilter = false
                        }
                    }
                }
                .navigationTitle("日期范围")
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("完成") { showingDateFilter = false }
                    }
                }
            }
            .presentationDetents([.medium])
        }
    }

    private var rangeDescription: String {
        if let startDate, let endDate {
            if Calendar.current.isDate(startDate, inSameDayAs: endDate) {
                return "Personal 空间 · 仅本机结果 · \(startDate.formatted(.dateTime.year().month().day()))"
            }
            return "Personal 空间 · 仅本机结果 · \(startDate.formatted(.dateTime.year().month().day())) 至 \(endDate.formatted(.dateTime.year().month().day()))"
        }
        if let startDate {
            return "Personal 空间 · 仅本机结果 · 从 \(startDate.formatted(.dateTime.year().month().day())) 起"
        }
        if let endDate {
            return "Personal 空间 · 仅本机结果 · 截至 \(endDate.formatted(.dateTime.year().month().day()))"
        }
        return "Personal 空间 · 仅本机结果 · 按日期从新到旧浏览"
    }
}

struct CaptureSheet: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var selectedKind: CaptureKind?
    @State private var text = ""
    @State private var photoItem: PhotosPickerItem?
    @State private var showingFileImporter = false
    @State private var showingCalendarImport = false
    @State private var fileImportMode: FileImportMode = .text
    @State private var pendingImport: PendingImport?
    @StateObject private var recorder = VoiceRecorder()

    private enum FileImportMode {
        case text
        case audio
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text("记录一件事")
                        .font(.title2.weight(.semibold))
                    Text("内容会先写入本机加密空间；转写、整理和同步不会阻塞保存反馈。")
                        .font(.footnote)
                        .foregroundStyle(AmemeStyle.secondaryText)
                    if let selectedKind {
                        selectedContent(for: selectedKind)
                    } else {
                        CaptureChoiceButton(icon: "square.and.pencil", title: "文字", detail: "输入一句话，立即保存") {
                            self.selectedKind = .text
                        }
                        CaptureChoiceButton(icon: "mic", title: "语音", detail: "录制或选择音频；完成后保存来源引用") {
                            self.selectedKind = .voice
                        }
                        PhotosPicker(selection: $photoItem, matching: .images) {
                            CaptureChoiceLabel(icon: "photo", title: "照片", detail: "使用系统照片选择器选择当前对象")
                        }
                        .buttonStyle(.plain)
                        CaptureChoiceButton(icon: "doc.badge.plus", title: "导入", detail: "文本文件、音频或你主动选择的日历计划") {
                            self.selectedKind = .importFile
                        }
                    }
                }
                .padding(24)
            }
            .navigationTitle("补充记录")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
        .onChange(of: photoItem) { _, item in
            guard let item else { return }
            if model.addPhotoReference(item.itemIdentifier) { dismiss() }
        }
        .fileImporter(
            isPresented: $showingFileImporter,
            allowedContentTypes: fileImportMode == .audio ? [.audio] : [.plainText, .json],
            allowsMultipleSelection: false
        ) { result in
            handleImport(result)
        }
        .sheet(isPresented: $showingCalendarImport) {
            CalendarImportSheet()
                .environmentObject(model)
        }
    }

    @ViewBuilder
    private func selectedContent(for kind: CaptureKind) -> some View {
        HStack {
            Image(systemName: kind.systemImage)
            Text(kind.label).font(.headline)
            Spacer()
            Button("返回方式") { selectedKind = nil }
                .font(.subheadline)
        }
        .foregroundStyle(AmemeStyle.teal)
        if kind == .text {
            TextEditor(text: $text)
                .frame(minHeight: 120)
                .padding(8)
                .overlay(RoundedRectangle(cornerRadius: 12).stroke(.quaternary))
                .accessibilityLabel("写下一句话")
            Button("保存到本机") {
                if model.addText(text) { dismiss() }
            }
            .buttonStyle(.borderedProminent)
            .frame(maxWidth: .infinity)
            .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        } else if kind == .voice {
            VoiceCaptureView(
                recorder: recorder,
                onSaved: { data in
                    if model.addMedia(data, kind: .voice, fileExtension: "m4a") { dismiss() }
                },
                onFailure: { message in model.notice = message },
            )
        } else {
            VStack(alignment: .leading, spacing: 12) {
                if let pendingImport {
                    Text("确认导入")
                        .font(.headline)
                    Text(pendingImport.fileName)
                        .font(.subheadline)
                        .foregroundStyle(AmemeStyle.secondaryText)
                    if let textPreview = pendingImport.textPreview {
                        Text(textPreview)
                            .lineLimit(8)
                            .font(.body)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(12)
                            .background(.quaternary.opacity(0.35), in: RoundedRectangle(cornerRadius: 10))
                    } else {
                        Text("音频只会保存为本机来源引用；不会在确认前写入事件或生成转写。")
                            .font(.footnote)
                            .foregroundStyle(AmemeStyle.secondaryText)
                    }
                    Button("确认保存到本机") {
                        let saved: Bool
                        if pendingImport.kind == .voice {
                            saved = model.addMedia(
                                pendingImport.data,
                                kind: .voice,
                                fileExtension: pendingImport.fileExtension,
                            )
                        } else {
                            saved = model.addImportedText(String(data: pendingImport.data, encoding: .utf8) ?? "")
                        }
                        if saved {
                            self.pendingImport = nil
                            dismiss()
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .frame(maxWidth: .infinity)
                    Button("取消并重新选择") { self.pendingImport = nil }
                        .buttonStyle(.bordered)
                        .frame(maxWidth: .infinity)
                } else {
                    Button("选择文本文件") {
                        fileImportMode = .text
                        showingFileImporter = true
                    }
                    .buttonStyle(.borderedProminent)
                    Button("选择已有音频") {
                        fileImportMode = .audio
                        showingFileImporter = true
                    }
                    .buttonStyle(.bordered)
                    Button("导入日历计划") {
                        showingCalendarImport = true
                    }
                    .buttonStyle(.bordered)
                    Text("只读取你本次选择的日历和日期范围；日历只表示计划，未确认发生。")
                        .font(.footnote)
                        .foregroundStyle(AmemeStyle.secondaryText)
                }
            }
        }
    }

    private func handleImport(_ result: Result<[URL], Error>) {
        do {
            guard let url = try result.get().first else { return }
            let secured = url.startAccessingSecurityScopedResource()
            defer { if secured { url.stopAccessingSecurityScopedResource() } }
            let data = try Data(contentsOf: url)
            pendingImport = PendingImport(
                kind: fileImportMode == .audio ? .voice : .importFile,
                data: data,
                fileExtension: url.pathExtension.isEmpty ? "m4a" : url.pathExtension,
                fileName: url.lastPathComponent,
            )
        } catch {
            model.notice = "导入尚未保存；请检查文件类型后重试。"
        }
    }
}

private struct CalendarImportSheet: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var calendars: [CalendarImportOption] = []
    @State private var selectedCalendarIDs = Set<String>()
    @State private var rangeDays = 1
    @State private var isLoading = true
    @State private var isImporting = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("只读取你本次选择的日历和日期范围；不会后台全量扫描。导入内容只表示计划。")
                        .font(.footnote)
                        .foregroundStyle(AmemeStyle.secondaryText)
                }
                Section("选择日历") {
                    if isLoading {
                        ProgressView("正在读取日历列表…")
                    } else if calendars.isEmpty {
                        Text("当前权限范围内没有可读取的日历。")
                            .foregroundStyle(AmemeStyle.secondaryText)
                    } else {
                        ForEach(calendars) { calendar in
                            Toggle(
                                calendar.title,
                                isOn: Binding(
                                    get: { selectedCalendarIDs.contains(calendar.id) },
                                    set: { isSelected in
                                        if isSelected {
                                            selectedCalendarIDs.insert(calendar.id)
                                        } else {
                                            selectedCalendarIDs.remove(calendar.id)
                                        }
                                    }
                                )
                            )
                            .disabled(isImporting)
                        }
                    }
                }
                Section("日期范围") {
                    Picker("从今天开始", selection: $rangeDays) {
                        Text("今天").tag(1)
                        Text("7 天").tag(7)
                        Text("31 天").tag(31)
                    }
                    .pickerStyle(.segmented)
                    .disabled(isImporting)
                    Text("单次最多导入 200 条；重复的日历引用会自动跳过。")
                        .font(.footnote)
                        .foregroundStyle(AmemeStyle.secondaryText)
                }
            }
            .navigationTitle("导入日历计划")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                        .disabled(isImporting)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(isImporting ? "导入中…" : "确认导入") {
                        Task {
                            isImporting = true
                            let saved = await model.importCalendar(
                                calendarIDs: selectedCalendarIDs,
                                rangeDays: rangeDays
                            )
                            isImporting = false
                            if saved { dismiss() }
                        }
                    }
                    .disabled(isLoading || isImporting || selectedCalendarIDs.isEmpty)
                }
            }
        }
        .presentationDetents([.medium, .large])
        .task {
            calendars = await model.calendarOptionsForImport()
            isLoading = false
        }
    }
}

private struct CaptureChoiceButton: View {
    let icon: String
    let title: String
    let detail: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            CaptureChoiceLabel(icon: icon, title: title, detail: detail)
        }
        .buttonStyle(.plain)
    }
}

private struct CaptureChoiceLabel: View {
    let icon: String
    let title: String
    let detail: String

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: icon)
                .font(.title3)
                .foregroundStyle(AmemeStyle.teal)
                .frame(width: 28)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 4) {
                Text(title).font(.headline)
                Text(detail).font(.subheadline).foregroundStyle(AmemeStyle.secondaryText)
            }
            Spacer()
            Image(systemName: "chevron.right")
                .foregroundStyle(AmemeStyle.secondaryText)
                .accessibilityHidden(true)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 14))
        .contentShape(Rectangle())
    }
}

@MainActor
final class VoiceRecorder: NSObject, ObservableObject {
    @Published private(set) var isRecording = false
    private var recorder: AVAudioRecorder?
    private var url: URL?

    func start() async -> Bool {
        #if os(iOS)
        do {
            let session = AVAudioSession.sharedInstance()
            let permissionGranted: Bool
            if AVAudioApplication.shared.recordPermission == .granted {
                permissionGranted = true
            } else {
                permissionGranted = await withCheckedContinuation { continuation in
                    AVAudioApplication.requestRecordPermission { granted in
                        continuation.resume(returning: granted)
                    }
                }
            }
            guard permissionGranted else { return false }
            try session.setCategory(.record, mode: .default)
            try session.setActive(true)
            let target = FileManager.default.temporaryDirectory.appendingPathComponent("ameme-\(UUID().uuidString).m4a")
            recorder = try AVAudioRecorder(url: target, settings: [
                AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
                AVSampleRateKey: 44_100,
                AVNumberOfChannelsKey: 1,
                AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue,
            ])
            url = target
            guard recorder?.record() == true else {
                reset()
                return false
            }
            isRecording = true
            return true
        } catch {
            return false
        }
        #else
        return false
        #endif
    }

    func stop() -> Data? {
        recorder?.stop()
        isRecording = false
        guard let url else {
            reset()
            return nil
        }
        let data = try? Data(contentsOf: url)
        reset()
        guard let data, !data.isEmpty else { return nil }
        return data
    }

    func cancel() {
        recorder?.stop()
        isRecording = false
        reset()
    }

    private func reset() {
        if let url { try? FileManager.default.removeItem(at: url) }
        recorder = nil
        self.url = nil
        #if os(iOS)
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        #endif
    }
}

private struct VoiceCaptureView: View {
    @ObservedObject var recorder: VoiceRecorder
    let onSaved: (Data) -> Void
    let onFailure: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(recorder.isRecording ? "正在录音；点击结束后才会保存。" : "点击开始后才请求麦克风权限。")
                .foregroundStyle(AmemeStyle.secondaryText)
            HStack {
                Button(recorder.isRecording ? "结束录音" : "开始录音") {
                    if recorder.isRecording {
                        if let data = recorder.stop() {
                            onSaved(data)
                        } else {
                            onFailure("录音没有产生可保存内容；请重试或选择已有音频。")
                        }
                    } else {
                        Task {
                            if !(await recorder.start()) {
                                onFailure("麦克风权限未授予或录音暂不可用；也可以选择已有音频。")
                            }
                        }
                    }
                }
                .buttonStyle(.borderedProminent)
                Button("取消") { recorder.cancel() }
                    .buttonStyle(.bordered)
                    .disabled(!recorder.isRecording)
            }
            if !recorder.isRecording {
                Text("也可以从“导入”中选择已有音频；不会生成虚构转写。")
                    .font(.footnote)
                    .foregroundStyle(AmemeStyle.secondaryText)
            }
        }
        .onDisappear { recorder.cancel() }
    }
}

struct EventDetailView: View {
    @EnvironmentObject private var model: AppModel
    let eventID: UUID
    let onDelete: () -> Void
    @State private var addendum = ""
    @State private var editingAddendum = false

    private var event: MemoryEvent? { model.store.event(id: eventID) }

    var body: some View {
        ScrollView {
            if let event {
                VStack(alignment: .leading, spacing: 18) {
                    DetailSection(title: "发生了什么") {
                        Text(event.title).font(.title2.weight(.semibold))
                        Text(event.time?.formatted(date: .omitted, time: .shortened) ?? "时间待确认")
                            .foregroundStyle(AmemeStyle.secondaryText)
                        Text(event.detail)
                    }
                    DetailSection(title: "我的补充") {
                        if let words = event.userWords, !words.isEmpty, !editingAddendum {
                            Text(words)
                            Button("继续补充") { addendum = words; editingAddendum = true }
                                .font(.subheadline)
                        } else {
                            TextField("补充一句原话或说明", text: $addendum, axis: .vertical)
                                .textFieldStyle(.roundedBorder)
                            Button("保存补充") {
                                model.saveAddendum(addendum, to: event.id)
                                editingAddendum = false
                            }
                            .buttonStyle(.borderedProminent)
                            .disabled(addendum.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        }
                    }
                    DetailSection(title: "当前状态") {
                        Label(event.factStatus.label, systemImage: statusIcon(event.factStatus))
                        if event.isLocalOnly { Text("仅本机；不代表其他设备没有记录。") }
                    }
                    if event.factStatus == .needsReview || event.factStatus == .planned {
                        DetailSection(title: "核验这件事") {
                            Text(
                                event.factStatus == .planned
                                    ? "日历或来源只说明计划；请选择它是否实际发生。"
                                    : "当前来源不足以确认事实；你的选择会形成一条新的 Revision。"
                            )
                            Button("确认已发生") {
                                model.updateFactStatus(.confirmed, for: event.id)
                            }
                            .buttonStyle(.borderedProminent)
                            .frame(maxWidth: .infinity)
                            Button("仍是计划") {
                                model.updateFactStatus(.planned, for: event.id)
                            }
                            .buttonStyle(.bordered)
                            .frame(maxWidth: .infinity)
                        }
                    }
                    DetailSection(title: "为什么这样记录") {
                        Text("来源：\(event.sourceLabel)")
                        Text("来源只支持它明确提供的字段；状态不会提高事实置信度。")
                            .foregroundStyle(AmemeStyle.secondaryText)
                    }
                    DetailSection(title: "使用范围与修改历史") {
                        Text("Personal 空间 · Revision \(event.revision)")
                        Text("当前未接入跨设备同步或 Agent 网络访问。")
                            .foregroundStyle(AmemeStyle.secondaryText)
                    }
                    Button("查看删除影响", action: onDelete)
                        .buttonStyle(.borderedProminent)
                        .tint(.red)
                        .frame(maxWidth: .infinity)
                }
                .padding(20)
            } else {
                EmptyMessageView(title: "事件已不存在", detail: "它可能已被删除或不在当前可见范围。")
                    .padding(20)
            }
        }
        .navigationTitle("事件详情")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            if let words = event?.userWords { addendum = words }
        }
    }

    private func statusIcon(_ status: FactStatus) -> String {
        switch status {
        case .confirmed: "checkmark.circle"
        case .userAsserted: "person.crop.circle"
        case .planned: "calendar"
        case .inferred: "questionmark.circle"
        case .needsReview: "exclamationmark.circle"
        case .conflict: "exclamationmark.triangle"
        case .processing: "hourglass"
        }
    }
}

struct DeleteView: View {
    @EnvironmentObject private var model: AppModel
    let eventID: UUID
    let onDeleted: () -> Void
    @State private var step: DeleteStep = .queued
    @State private var isDeleting = false

    private var event: MemoryEvent? { model.store.event(id: eventID) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Text(event?.title ?? "事件已不存在")
                    .font(.title2.weight(.semibold))
                DetailSection(title: "影响范围") {
                    Text("• 当前 Event 与今天/历史日流条目")
                    Text("• 本机小结与搜索索引会重新计算")
                    Text("• 当前版本未接入其他设备，不会虚构同步确认")
                    Text("• 不会删除系统照片、文件或其他来源原件")
                }
                DeleteProgressView(step: step)
                switch step {
                case .queued:
                    Button("确认删除") { delete() }
                        .buttonStyle(.borderedProminent)
                        .tint(.red)
                        .disabled(event == nil || isDeleting)
                        .frame(maxWidth: .infinity)
                case .partialFailed:
                    Button("重试删除") { delete() }
                        .buttonStyle(.borderedProminent)
                        .frame(maxWidth: .infinity)
                case .completed:
                    Button("返回今天", action: onDeleted)
                        .buttonStyle(.borderedProminent)
                        .frame(maxWidth: .infinity)
                default:
                    ProgressView("正在更新本机状态…")
                        .frame(maxWidth: .infinity)
                }
                Text("离开此页后，已创建的删除任务仍会保留本机状态。")
                    .font(.footnote)
                    .foregroundStyle(AmemeStyle.secondaryText)
            }
            .padding(20)
        }
        .navigationTitle("删除影响与进度")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func delete() {
        guard !isDeleting else { return }
        isDeleting = true
        step = .localDeleting
        let deleted = model.store.delete(id: eventID)
        if deleted {
            step = .recomputing
            step = .completed
        } else {
            step = .partialFailed
            model.notice = "删除尚未持久化；事件仍保持可见。"
        }
        isDeleting = false
    }
}

struct SettingsView: View {
    @EnvironmentObject private var model: AppModel
    @State private var showingAgentSheet = false
    @State private var exportURL: URL?
    @State private var disconnectingAgent = false

    var body: some View {
        Form {
            Section("来源与权限") {
                SettingRowView(title: "照片", detail: photoPermissionDetail)
                SettingRowView(title: "语音", detail: voicePermissionDetail)
                SettingRowView(title: "日历", detail: calendarPermissionDetail)
                SettingRowView(title: "位置与健康", detail: "尚未接入公开 MVP")
            }
            Section("体验数据") {
                if model.store.isDemoMode {
                    Label("当前正在查看演示数据", systemImage: "rectangle.on.rectangle")
                    Text("演示数据不会写入本机加密库；退出后会恢复你的真实本机记录。")
                        .font(.footnote)
                        .foregroundStyle(AmemeStyle.secondaryText)
                    Button("退出演示数据") {
                        model.exitDemoMode()
                    }
                } else {
                    Button("载入演示数据") {
                        model.enterDemoMode()
                    }
                    Text("用固定示例覆盖今天、搜索、详情、小结和删除流程，便于体验与验收。")
                        .font(.footnote)
                        .foregroundStyle(AmemeStyle.secondaryText)
                }
            }
            Section("空间、设备与 Agent") {
                SettingRowView(title: "Personal 空间", detail: "本机加密存储")
                SettingRowView(title: "设备同步", detail: "云端未启用 · Agent 局域网发现已接入，授权与数据传输待后续版本")
                if let connection = model.agentExperienceConnection {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("体验连接").font(.headline)
                        Text("\(connection.deviceName) · \(connection.agentName)")
                        Text("方式：\(connection.method.label) · 允许：Personal 空间 · \(connection.capabilities.joined(separator: "、"))")
                            .font(.footnote)
                            .foregroundStyle(AmemeStyle.secondaryText)
                        Text("\(connection.simulated ? "体验期限至" : "有效期至") \(connection.expiresAt.formatted(date: .abbreviated, time: .omitted))")
                            .font(.footnote)
                            .foregroundStyle(AmemeStyle.secondaryText)
                        if connection.simulated {
                            Text("体验模式 · 不建立真实网络连接")
                                .font(.footnote.weight(.medium))
                                .foregroundStyle(AmemeStyle.teal)
                        } else {
                            Text("已建立授权连接 · 仅按显示的能力范围工作")
                                .font(.footnote.weight(.medium))
                                .foregroundStyle(AmemeStyle.teal)
                        }
                        Button(disconnectingAgent ? "正在断开…" : "断开连接") {
                            disconnectingAgent = true
                            Task { @MainActor in
                                await model.disconnectAgentExperience()
                                disconnectingAgent = false
                            }
                        }
                        .disabled(disconnectingAgent)
                    }
                } else {
                    Button("连接设备") { showingAgentSheet = true }
                    Text("自动发现电脑、扫描二维码、账户设备三种入口共用同一授权模型。")
                        .font(.footnote)
                        .foregroundStyle(AmemeStyle.secondaryText)
                }
            }
            Section("AI 小结") {
                SettingRowView(title: "发送范围", detail: "仅当天结构化事件；不发送照片、音频原文件、来源定位或搜索记录")
                SettingRowView(title: "生成方式", detail: "当前 iOS 网关尚未接入；不会用模板冒充 AI 结果")
            }
            Section("隐私、导出与删除") {
                Button(
                    exportURL == nil && model.pendingExportAvailable
                        ? "恢复上次结构化导出"
                        : exportURL == nil ? "生成结构化导出" : "重新生成结构化导出"
                ) {
                    exportURL = model.createExportFile()
                }
                if model.pendingExportAvailable {
                    Text("导出恢复快照已使用本机加密保存；系统分享中断后可以重新分享，不会修改原始事件。")
                        .font(.footnote)
                        .foregroundStyle(AmemeStyle.secondaryText)
                    Button("清除导出恢复快照", role: .destructive) {
                        model.clearPendingExportSnapshot()
                        exportURL = nil
                    }
                }
                Text("固定 Personal 空间和当前可见事件范围；不包含受限事件、原始照片或音频文件。")
                    .font(.footnote)
                    .foregroundStyle(AmemeStyle.secondaryText)
                Text("分享或保存导出失败时，可以重新生成；本机原始记录不会被修改。")
                    .font(.footnote)
                    .foregroundStyle(AmemeStyle.secondaryText)
                if let exportURL {
                    ShareLink(item: exportURL) {
                        Label("分享或保存导出文件", systemImage: "square.and.arrow.up")
                    }
                }
                SettingRowView(title: "删除", detail: "删除事件时重新计算本机小结和搜索索引")
                SettingRowView(title: "诊断", detail: "不记录正文、搜索词或配对密钥")
            }
        }
        .navigationTitle("设置")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $showingAgentSheet) {
            AgentConnectionSheet(
                onResolve: { method in
                    try await model.resolveAgentExperienceCandidate(method: method)
                },
                onResolvePairingPayload: { payload in
                    try await model.resolveAgentExperienceCandidate(pairingPayload: payload)
                },
                onConnect: { candidate in
                    try await model.connectAgentExperience(candidate: candidate)
                },
                onDemoConnected: { method in
                    model.connectAgentExperience(method: method)
                }
            )
        }
    }

    private var photoPermissionDetail: String {
        switch PHPhotoLibrary.authorizationStatus(for: .readWrite) {
        case .authorized:
            "已允许 · 仍只读取你每次选择的照片"
        case .limited:
            "有限访问 · 仍只读取你每次选择的照片"
        case .notDetermined:
            "尚未请求 · 选择照片时按次申请"
        case .denied, .restricted:
            "未允许 · 可继续使用文字和文件导入"
        @unknown default:
            "状态未知 · 选择照片时按次确认"
        }
    }

    private var voicePermissionDetail: String {
        switch AVAudioApplication.shared.recordPermission {
        case .granted:
            "已允许 · 点击录音时使用，不常驻监听"
        case .denied:
            "未允许 · 可继续选择已有音频"
        case .undetermined:
            "尚未请求 · 点击录音时按次申请"
        @unknown default:
            "状态未知 · 可选择已有音频"
        }
    }

    private var calendarPermissionDetail: String {
        let status = EKEventStore.authorizationStatus(for: .event)
        switch status {
        case .fullAccess, .authorized:
            return "只读权限已允许 · 仍须选择日历和日期范围"
        case .writeOnly:
            return "仅有写入权限 · 无法读取计划"
        case .notDetermined:
            return "尚未请求 · 主动导入时按次申请"
        case .denied, .restricted:
            return "未允许 · 可继续使用文字和其他来源"
        @unknown default:
            return "状态未知 · 主动导入时重新确认"
        }
    }
}

private struct AgentConnectionSheet: View {
    @Environment(\.dismiss) private var dismiss
    let onResolve: (AgentConnectionMethod) async throws -> AgentExperienceCandidate
    let onResolvePairingPayload: (String) async throws -> AgentExperienceCandidate
    let onConnect: (AgentExperienceCandidate) async throws -> AgentExperienceConnection
    let onDemoConnected: (AgentConnectionMethod) -> Void
    @State private var selectedMethod: AgentConnectionMethod?
    @State private var candidate: AgentExperienceCandidate?
    @State private var isBusy = false
    @State private var errorMessage: String?
    @State private var showingQRCodeScanner = false

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 16) {
                Text("连接设备")
                    .font(.title2.weight(.semibold))
                Text("三种入口只负责发现方式；设备可见不等于已授权，连接前会明确显示设备、Agent、用途、空间和有效期。")
                    .font(.subheadline)
                    .foregroundStyle(AmemeStyle.secondaryText)
                if let candidate {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("允许 Agent 连接？").font(.headline)
                        Text(candidate.deviceName)
                        Text("Agent：\(candidate.agentName)")
                        Text("连接方式：\(candidate.method.label)")
                        if let expiresAt = candidate.authorizationExpiresAt {
                            Text("拟授权范围：Personal 空间 · autonomous_memory · 结构化 event")
                            Text("授权有效至：\(expiresAt.formatted(date: .abbreviated, time: .shortened))")
                        } else {
                            Text("拟授权范围：Personal 空间 · autonomous_memory · 结构化 event · 最长 30 天")
                        }
                        Text("允许：Personal 空间 · \(candidate.capabilities.joined(separator: "、"))")
                        Text(
                            candidate.simulated
                                ? "体验模式 · 不建立真实网络连接，不写入事件或访问审计。"
                                : "已发现设备；授权通道完成前不会建立连接或访问事件。"
                        )
                            .font(.footnote.weight(.medium))
                            .foregroundStyle(AmemeStyle.teal)
                        Button(isBusy ? "正在连接…" : "允许并连接") {
                            isBusy = true
                            errorMessage = nil
                            Task { @MainActor in
                                do {
                                    _ = try await onConnect(candidate)
                                    dismiss()
                                } catch {
                                    errorMessage = agentExperienceMessage(error)
                                }
                                isBusy = false
                            }
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(isBusy)
                        if let errorMessage {
                            Text(errorMessage)
                                .font(.footnote)
                                .foregroundStyle(.red)
                        }
                        Button("返回连接方式") {
                            self.candidate = nil
                            self.selectedMethod = nil
                            self.errorMessage = nil
                        }
                        .disabled(isBusy)
                    }
                    .padding(16)
                    .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 16))
                } else {
                    ForEach(AgentConnectionMethod.allCases) { method in
                        Button {
                            selectedMethod = method
                            if method == .qrCode {
                                showingQRCodeScanner = true
                            } else {
                                resolve(method)
                            }
                        } label: {
                            HStack {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(method.label).font(.headline)
                                    Text(method.detail).font(.subheadline).foregroundStyle(AmemeStyle.secondaryText)
                                }
                                Spacer()
                                Image(systemName: "chevron.right")
                            }
                        }
                        .buttonStyle(.plain)
                        .padding(.vertical, 8)
                        .disabled(isBusy)
                    }
                    if isBusy {
                        ProgressView(selectedMethod == .qrCode ? "正在验证配对码…" : "正在查找设备…")
                    }
                    if let errorMessage {
                        Text(errorMessage)
                            .font(.footnote)
                            .foregroundStyle(.red)
                    }
                    Button("试用演示连接（不联网）") {
                        onDemoConnected(.lanDiscovery)
                        dismiss()
                    }
                    .buttonStyle(.bordered)
                    .disabled(isBusy)
                }
                Spacer()
            }
            .padding(24)
            .navigationTitle("设备连接")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
            }
        }
        .presentationDetents([.medium, .large])
        .fullScreenCover(isPresented: $showingQRCodeScanner) {
            AgentQRCodeScannerSheet { payload in
                showingQRCodeScanner = false
                resolve(pairingPayload: payload)
            }
        }
    }

    private func resolve(_ method: AgentConnectionMethod) {
        isBusy = true
        errorMessage = nil
        candidate = nil
        Task { @MainActor in
            do {
                candidate = try await onResolve(method)
            } catch {
                errorMessage = agentExperienceMessage(error)
            }
            isBusy = false
        }
    }

    private func resolve(pairingPayload: String) {
        selectedMethod = .qrCode
        isBusy = true
        errorMessage = nil
        candidate = nil
        Task { @MainActor in
            do {
                candidate = try await onResolvePairingPayload(pairingPayload)
            } catch {
                errorMessage = agentExperienceMessage(error)
            }
            isBusy = false
        }
    }
}

private struct AgentQRCodeScannerSheet: View {
    @Environment(\.dismiss) private var dismiss
    let onPayload: (String) -> Void
    @State private var manualPayload = ""
    @State private var scannerError: String?
    @State private var delivered = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    Text("扫描 Android 上显示的 Ameme 配对二维码。配对码只在短时间内有效，扫描后仍需确认授权。")
                        .font(.subheadline)
                        .foregroundStyle(AmemeStyle.secondaryText)
                    AgentQRCodeScannerPreview(
                        onPayload: accept,
                        onError: { scannerError = $0 }
                    )
                    .frame(maxWidth: .infinity)
                    .frame(height: 340)
                    .background(Color.black, in: RoundedRectangle(cornerRadius: 18))
                    .clipShape(RoundedRectangle(cornerRadius: 18))
                    .accessibilityHidden(true)
                    if let scannerError {
                        Label(scannerError, systemImage: "camera.fill")
                            .font(.footnote)
                            .foregroundStyle(.red)
                    }
                    Text("无法使用相机时，也可以粘贴完整配对码。")
                        .font(.footnote)
                        .foregroundStyle(AmemeStyle.secondaryText)
                    TextField("粘贴 ameme-pairing-v1 配对码", text: $manualPayload, axis: .vertical)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .lineLimit(2...5)
                        .textFieldStyle(.roundedBorder)
                        .accessibilityLabel("配对码")
                    Button("验证配对码") {
                        accept(manualPayload)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(manualPayload.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
                .padding(24)
            }
            .navigationTitle("扫描配对码")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("取消") { dismiss() }
                }
            }
        }
    }

    private func accept(_ value: String) {
        let payload = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !delivered, !payload.isEmpty else { return }
        delivered = true
        onPayload(payload)
        dismiss()
    }
}

private struct AgentQRCodeScannerPreview: UIViewControllerRepresentable {
    let onPayload: (String) -> Void
    let onError: (String) -> Void

    func makeUIViewController(context: Context) -> AgentQRCodeScannerViewController {
        AgentQRCodeScannerViewController(onPayload: onPayload, onError: onError)
    }

    func updateUIViewController(
        _ uiViewController: AgentQRCodeScannerViewController,
        context: Context
    ) {}
}

private final class AgentQRCodeScannerViewController:
    UIViewController,
    AVCaptureMetadataOutputObjectsDelegate,
    @unchecked Sendable
{
    private let onPayload: (String) -> Void
    private let onError: (String) -> Void
    private let captureSession = AVCaptureSession()
    private let captureQueue = DispatchQueue(label: "com.ameme.qr-scanner")
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private var configured = false
    private var delivered = false

    init(onPayload: @escaping (String) -> Void, onError: @escaping (String) -> Void) {
        self.onPayload = onPayload
        self.onError = onError
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        requestCameraAndStart()
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        captureQueue.async { [captureSession] in
            if captureSession.isRunning {
                captureSession.stopRunning()
            }
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
    }

    private func requestCameraAndStart() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            configureAndStart()
        case .notDetermined:
            Task { @MainActor [weak self] in
                guard let self else { return }
                if await AVCaptureDevice.requestAccess(for: .video) {
                    configureAndStart()
                } else {
                    onError("相机权限未允许；可以在下方粘贴配对码。")
                }
            }
        case .denied, .restricted:
            onError("相机权限未允许；可以在下方粘贴配对码。")
        @unknown default:
            onError("相机暂时不可用；可以在下方粘贴配对码。")
        }
    }

    private func configureAndStart() {
        guard !configured else {
            startCapture()
            return
        }
        guard
            let camera = AVCaptureDevice.default(for: .video),
            let input = try? AVCaptureDeviceInput(device: camera),
            captureSession.canAddInput(input) else {
            onError("没有可用相机；可以在下方粘贴配对码。")
            return
        }
        let output = AVCaptureMetadataOutput()
        guard captureSession.canAddOutput(output) else {
            onError("相机无法读取二维码；可以在下方粘贴配对码。")
            return
        }
        captureSession.beginConfiguration()
        captureSession.addInput(input)
        captureSession.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: .main)
        output.metadataObjectTypes = [.qr]
        captureSession.commitConfiguration()

        let previewLayer = AVCaptureVideoPreviewLayer(session: captureSession)
        previewLayer.videoGravity = .resizeAspectFill
        previewLayer.frame = view.bounds
        view.layer.addSublayer(previewLayer)
        self.previewLayer = previewLayer
        configured = true
        startCapture()
    }

    private func startCapture() {
        captureQueue.async { [captureSession] in
            if !captureSession.isRunning {
                captureSession.startRunning()
            }
        }
    }

    nonisolated func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        guard let code = metadataObjects
                .compactMap({ $0 as? AVMetadataMachineReadableCodeObject })
                .first(where: { $0.type == .qr })?
                .stringValue else { return }
        Task { @MainActor [weak self] in
            self?.acceptScannedCode(code)
        }
    }

    private func acceptScannedCode(_ code: String) {
        guard !delivered else { return }
        delivered = true
        captureQueue.async { [captureSession] in
            if captureSession.isRunning {
                captureSession.stopRunning()
            }
        }
        onPayload(code)
    }
}

private func agentExperienceMessage(_ error: Error) -> String {
    switch error {
    case AgentExperienceConnectorError.noDeviceFound:
        "没有发现可用电脑；请确认 Agent 已启动并与本机处于同一局域网。"
    case AgentExperienceConnectorError.discoveryUnavailable:
        "局域网发现暂时不可用；请检查本地网络权限后重试。"
    case AgentExperienceConnectorError.authorizationRequired:
        "已发现设备，但授权通道尚未完成；本机没有建立连接。"
    case AgentExperienceConnectorError.qrScannerUnavailable:
        "二维码入口尚未接入扫描器；本机没有建立连接。"
    case AgentExperienceConnectorError.accountSignInRequired:
        "账户设备需要先完成账户授权；本机没有建立连接。"
    case AgentExperienceConnectorError.candidateUnavailable:
        "设备候选已失效；请重新发现后重试。"
    case AgentExperienceConnectorError.connectionFailed:
        "配对信息已验证，但安全连接没有建立；请确认两台设备在同一网络并重新扫描。"
    case AgentExperienceConnectorError.invalidPairingPayload:
        "配对码无效、已过期或超出允许期限；请在 Android 上重新生成。"
    default:
        "连接体验暂时不可用；本机事件没有改变，请重试。"
    }
}

private struct DetailSection<Content: View>: View {
    let title: String
    @ViewBuilder let content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(.headline)
            content()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private struct SettingRowView: View {
    let title: String
    let detail: String

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(title)
            Text(detail).font(.footnote).foregroundStyle(AmemeStyle.secondaryText)
        }
        .padding(.vertical, 3)
    }
}

private struct EventRowView: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let event: MemoryEvent
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            Group {
                if dynamicTypeSize.isAccessibilitySize {
                    accessibilityLayout
                } else {
                    compactLayout
                }
            }
            .padding(.vertical, 14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .overlay(alignment: .bottom) { Divider() }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(
            "\(event.time?.formatted(date: .omitted, time: .shortened) ?? "时间待确认")，\(event.title)，\(event.factStatus.label)\(event.isLocalOnly ? "，仅本机" : "")"
        )
    }

    private var compactLayout: some View {
        HStack(alignment: .top, spacing: 14) {
            Text(eventTime)
                .font(.subheadline.monospacedDigit())
                .foregroundStyle(AmemeStyle.secondaryText)
                .frame(width: 58, alignment: .leading)
            VStack(alignment: .leading, spacing: 6) {
                HStack(alignment: .firstTextBaseline) {
                    Text(event.title).font(.headline).foregroundStyle(.primary)
                    Spacer(minLength: 8)
                    statusText
                }
                Text(event.detail)
                    .font(.body)
                    .foregroundStyle(AmemeStyle.secondaryText)
                    .multilineTextAlignment(.leading)
                HStack(spacing: 8) {
                    Text(event.sourceLabel)
                    if event.isLocalOnly { Text("仅本机") }
                }
                .font(.caption)
                .foregroundStyle(AmemeStyle.secondaryText)
            }
        }
    }

    private var accessibilityLayout: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline, spacing: 12) {
                Text(event.time?.formatted(date: .omitted, time: .shortened) ?? "待定")
                    .font(.subheadline.monospacedDigit())
                    .foregroundStyle(AmemeStyle.secondaryText)
                Spacer(minLength: 8)
                statusText
            }
            Text(event.title)
                .font(.headline)
                .foregroundStyle(.primary)
                .fixedSize(horizontal: false, vertical: true)
            Text(event.detail)
                .font(.body)
                .foregroundStyle(AmemeStyle.secondaryText)
                .multilineTextAlignment(.leading)
                .fixedSize(horizontal: false, vertical: true)
            Text(event.isLocalOnly ? "\(event.sourceLabel) · 仅本机" : event.sourceLabel)
                .font(.caption)
                .foregroundStyle(AmemeStyle.secondaryText)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var statusText: some View {
        Text(event.factStatus.label)
            .font(.caption)
            .foregroundStyle(event.factStatus == .needsReview ? AmemeStyle.teal : AmemeStyle.secondaryText)
    }

    private var eventTime: String {
        event.time?.formatted(date: .omitted, time: .shortened) ?? "待定"
    }
}

private struct DaySummaryView: View {
    let summary: DaySummary
    @Binding var isExpanded: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Divider()
            if summary.state == .ready {
                DisclosureGroup(isExpanded: $isExpanded) {
                    Text(summary.text).font(.body).padding(.top, 4)
                } label: {
                    Label("今日小结", systemImage: "doc.text")
                        .font(.headline)
                }
            } else {
                Label("今日小结", systemImage: "doc.text")
                    .font(.headline)
                Text(summary.text)
                    .font(.subheadline)
                    .foregroundStyle(AmemeStyle.secondaryText)
            }
        }
        .padding(.top, 2)
        .accessibilityElement(children: .contain)
    }
}

private struct StateNoticeView: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let mode: ExperienceMode
    let onAction: (() -> Void)?

    init(mode: ExperienceMode, onAction: (() -> Void)? = nil) {
        self.mode = mode
        self.onAction = onAction
    }

    var body: some View {
        if ![.ready, .empty, .sparse].contains(mode) {
            Group {
                if dynamicTypeSize.isAccessibilitySize {
                    VStack(alignment: .leading, spacing: 12) {
                        statusLabel
                        retryButton
                    }
                } else {
                    HStack(alignment: .top, spacing: 12) {
                        statusLabel
                            .layoutPriority(1)
                        Spacer(minLength: 8)
                        retryButton
                    }
                }
            }
            .padding(12)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 14))
            .padding(.bottom, 12)
            // Keep the retry control as a separate VoiceOver action. Combining
            // this container would flatten the status and hide the button trait.
            .accessibilityElement(children: .contain)
        }
    }

    private var statusLabel: some View {
        Label {
            VStack(alignment: .leading, spacing: 3) {
                Text(mode.label).font(.headline)
                Text(mode.detail).font(.footnote)
            }
            .fixedSize(horizontal: false, vertical: true)
        } icon: {
            Image(systemName: iconName)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(mode.label)：\(mode.detail)")
    }

    @ViewBuilder
    private var retryButton: some View {
        if mode == .recoverableError, let onAction {
            Button("重试", action: onAction)
                .buttonStyle(.bordered)
                .frame(minWidth: 44, minHeight: 44)
        }
    }

    private var iconName: String {
        switch mode {
        case .loading: "hourglass"
        case .partial: "arrow.triangle.2.circlepath"
        case .offline: "icloud.slash"
        case .recoverableError: "exclamationmark.triangle"
        case .permissionLimited: "lock"
        default: "info.circle"
        }
    }
}

private struct DemoModeNotice: View {
    var body: some View {
        Label {
            VStack(alignment: .leading, spacing: 2) {
                Text("演示数据")
                    .font(.headline)
                Text("固定示例仅用于体验，不会写入真实本机记录。")
                    .font(.footnote)
            }
        } icon: {
            Image(systemName: "play.rectangle")
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(AmemeStyle.teal.opacity(0.12), in: RoundedRectangle(cornerRadius: 14))
        .accessibilityElement(children: .combine)
        .accessibilityLabel("演示数据。固定示例仅用于体验，不会写入真实本机记录。")
    }
}

private struct EmptyMessageView: View {
    let title: String
    let detail: String

    var body: some View {
        VStack(spacing: 8) {
            Text(title).font(.headline)
            Text(detail).font(.body).foregroundStyle(AmemeStyle.secondaryText).multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 44)
    }
}

private struct DeleteProgressView: View {
    let step: DeleteStep

    var body: some View {
        DetailSection(title: "删除进度") {
            ForEach(DeleteStep.allCases) { item in
                HStack(spacing: 10) {
                    Image(systemName: item.rawValue == step.rawValue ? "circle.inset.filled" : "circle")
                        .foregroundStyle(item.rawValue == step.rawValue ? AmemeStyle.teal : .secondary)
                    Text(item.label)
                        .foregroundStyle(item.rawValue == step.rawValue ? .primary : .secondary)
                    if item == .syncPropagating {
                        Text("当前版本跳过")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                .accessibilityElement(children: .combine)
                .accessibilityLabel(item.label)
                .accessibilityValue(
                    item.rawValue == step.rawValue
                        ? "当前步骤"
                        : item == .syncPropagating ? "当前版本跳过" : "未完成"
                )
            }
        }
        .padding(16)
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 16))
    }
}

#endif
