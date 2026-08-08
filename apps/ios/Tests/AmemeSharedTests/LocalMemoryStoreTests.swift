import Foundation
import CryptoKit
import XCTest
@testable import AmemeShared

private final class TestKeyStore: @unchecked Sendable, KeyMaterialStore {
    private let key = SymmetricKey(data: Data(repeating: 0x31, count: 32))

    func loadOrCreateKey() throws -> SymmetricKey { key }
}

@MainActor
final class LocalMemoryStoreTests: XCTestCase {
    func testExperienceModeMatchesAndroidContentContract() {
        XCTAssertEqual(ExperienceMode.ready.resolvedFor(eventCount: 0, deriveFromEvents: true), .empty)
        XCTAssertEqual(ExperienceMode.empty.resolvedFor(eventCount: 1, deriveFromEvents: true), .sparse)
        XCTAssertEqual(ExperienceMode.sparse.resolvedFor(eventCount: 2, deriveFromEvents: true), .ready)
        XCTAssertEqual(
            ExperienceMode.recoverableError.resolvedFor(eventCount: 0, deriveFromEvents: true),
            .recoverableError
        )
        XCTAssertEqual(ExperienceMode.empty.resolvedFor(eventCount: 12, deriveFromEvents: false), .empty)
    }

    func testTextCaptureSearchAndDelete() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("AmemeTests-\(UUID().uuidString)", isDirectory: true)
        let fileManager = FileManager()
        let store = LocalMemoryStore(fileManager: fileManager, keyStore: TestKeyStore(), rootDirectory: directory)

        let event = try XCTUnwrap(store.addText("完成 iOS 本地事件闭环"))
        XCTAssertEqual(store.events.count, 1)
        XCTAssertEqual(store.search(query: "本地", date: Date()).first?.id, event.id)
        XCTAssertEqual(store.search(query: "主动输入").first?.id, event.id)
        XCTAssertEqual(store.search(query: "完成 本地").first?.id, event.id)
        XCTAssertTrue(store.search(query: "完成 不存在").isEmpty)
        XCTAssertEqual(store.summary(for: Date()).state, .insufficient)
        _ = store.addText("验证第二条本机事件")
        XCTAssertEqual(store.summary(for: Date()).state, .ready)
        XCTAssertTrue(store.delete(id: event.id))
        XCTAssertEqual(store.events.count, 1)
        XCTAssertEqual(store.summary(for: Date()).state, .insufficient)

        let reloaded = LocalMemoryStore(fileManager: fileManager, keyStore: TestKeyStore(), rootDirectory: directory)
        XCTAssertEqual(reloaded.events.count, 1)
        try? fileManager.removeItem(at: directory)
    }

    func testSensitiveEventsAreExcludedFromSummary() {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("AmemeTests-\(UUID().uuidString)", isDirectory: true)
        let store = LocalMemoryStore(keyStore: TestKeyStore(), rootDirectory: directory)
        _ = store.add(
            title: "受限事件",
            detail: "不会进入小结",
            kind: .text,
            sourceLabel: "测试",
            factStatus: .confirmed,
            sensitivity: .restricted
        )
        _ = store.addText("第一件可用于验证的事情")
        _ = store.addText("第二件可用于验证的事情")
        let summary = store.summary(for: Date())
        XCTAssertEqual(summary.state, .ready)
        XCTAssertFalse(summary.text.contains("受限事件"))
        let export = try? String(decoding: store.exportData(), as: UTF8.self)
        XCTAssertTrue(export?.contains("schemaVersion") == true)
        XCTAssertTrue(export?.contains("user_asserted") == true)
        XCTAssertFalse(export?.contains("受限事件") == true)
        try? FileManager.default.removeItem(at: directory)
    }

    func testBatchImportCommitsAllOrNothing() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("AmemeBatchTests-\(UUID().uuidString)", isDirectory: true)
        let fileManager = FileManager.default
        let store = LocalMemoryStore(fileManager: fileManager, keyStore: TestKeyStore(), rootDirectory: directory)
        _ = store.addText("已有本机记录")
        let drafts = [
            MemoryEventDraft(
                title: "日历计划一",
                detail: "计划一",
                factStatus: .planned,
                sourceLabel: "测试日历",
                sourceLocator: "eventkit://test-1",
                captureKind: .importFile,
                eventType: .activity,
                evidenceState: .observed,
                sensitivity: .confidential
            ),
            MemoryEventDraft(
                title: "日历计划二",
                detail: "计划二",
                factStatus: .planned,
                sourceLabel: "测试日历",
                sourceLocator: "eventkit://test-2",
                captureKind: .importFile,
                eventType: .activity,
                evidenceState: .observed,
                sensitivity: .confidential
            ),
        ]
        XCTAssertEqual(store.addBatch(drafts)?.count, 2)
        XCTAssertEqual(store.events.filter { $0.sourceLabel == "测试日历" }.count, 2)
        XCTAssertTrue(store.addBatch(drafts)?.isEmpty == true)

        let eventsFile = directory.appendingPathComponent("events.enc")
        try fileManager.removeItem(at: eventsFile)
        try fileManager.createDirectory(at: eventsFile, withIntermediateDirectories: true)
        let failedDrafts = drafts.map { draft in
            MemoryEventDraft(
                title: draft.title,
                detail: draft.detail,
                factStatus: draft.factStatus,
                sourceLabel: draft.sourceLabel,
                sourceLocator: "eventkit://failure-\(draft.title)",
                captureKind: draft.captureKind,
                userWords: draft.userWords,
                eventType: draft.eventType,
                evidenceState: draft.evidenceState,
                sensitivity: draft.sensitivity,
                importance: draft.importance
            )
        }
        XCTAssertNil(store.addBatch(failedDrafts))
        XCTAssertEqual(store.events.filter { $0.sourceLabel == "测试日历" }.count, 2)
        XCTAssertEqual(store.storageState, .recoverableError)
        try? fileManager.removeItem(at: directory)
    }

    func testDemoModeIsUsefulAndDoesNotPersist() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("AmemeTests-\(UUID().uuidString)", isDirectory: true)
        let store = LocalMemoryStore(keyStore: TestKeyStore(), rootDirectory: directory)
        XCTAssertTrue(store.events.isEmpty)

        store.enterDemoMode()
        XCTAssertTrue(store.isDemoMode)
        XCTAssertGreaterThanOrEqual(store.events.count, 5)
        XCTAssertEqual(store.summary(for: Date()).state, .ready)
        XCTAssertTrue(store.search(query: "整理").isEmpty == false)

        store.exitDemoMode()
        XCTAssertFalse(store.isDemoMode)
        XCTAssertTrue(store.events.isEmpty)
        try? FileManager.default.removeItem(at: directory)
    }

    func testMediaRecoveryIsEncryptedAndPhotoUsesReferenceOnly() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("AmemeTests-\(UUID().uuidString)", isDirectory: true)
        let fileManager = FileManager()
        let store = LocalMemoryStore(fileManager: fileManager, keyStore: TestKeyStore(), rootDirectory: directory)
        let payload = Data("private-audio-payload".utf8)
        let media = store.addMedia(payload, kind: .voice, fileExtension: "m4a")
        let mediaURL = try XCTUnwrap(media.sourceLocator).asFileURL
        let onDisk = try Data(contentsOf: mediaURL)
        XCTAssertNotEqual(onDisk, payload)
        XCTAssertFalse(String(decoding: onDisk, as: UTF8.self).contains("private-audio-payload"))

        let photo = try XCTUnwrap(store.addPhotoReference("photos-asset-test"))
        XCTAssertEqual(photo.sourceLocator, "photos://photos-asset-test")
        XCTAssertEqual(photo.sensitivity, .confidential)
        XCTAssertEqual(media.sensitivity, .confidential)
        XCTAssertEqual(try fileManager.contentsOfDirectory(at: directory.appendingPathComponent("media"), includingPropertiesForKeys: nil).count, 1)

        XCTAssertTrue(store.delete(id: media.id))
        XCTAssertFalse(fileManager.fileExists(atPath: mediaURL.path))
        try? fileManager.removeItem(at: directory)
    }
}

private extension String {
    var asFileURL: URL { URL(fileURLWithPath: self) }
}
