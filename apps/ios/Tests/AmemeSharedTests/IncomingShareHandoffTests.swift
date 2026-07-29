import Foundation
import XCTest
@testable import AmemeShared

final class IncomingShareHandoffTests: XCTestCase {
    func testTextHandoffUsesOpaqueURLAndRoundTrips() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("AmemeIncomingText-\(UUID().uuidString)", isDirectory: true)
        let store = IncomingShareHandoffStore(rootDirectory: root)

        let url = try store.writeText("来自其他 App 的文字", displayName: "摘录")
        let id = try XCTUnwrap(store.id(from: url))
        XCTAssertFalse(url.absoluteString.contains("来自其他 App"))
        let read = try store.read(id: id)
        XCTAssertEqual(read.payload.kind, .text)
        XCTAssertEqual(read.payload.text, "来自其他 App 的文字")
        XCTAssertNil(read.fileData)

        store.remove(id: id)
        XCTAssertThrowsError(try store.read(id: id))
        try? FileManager.default.removeItem(at: root)
    }

    func testPendingIDsListsDurableHandoffsForRestartRecovery() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("AmemeIncomingRecovery-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = IncomingShareHandoffStore(rootDirectory: root)
        let firstURL = try store.writeText("第一条待确认分享")
        let secondURL = try store.writeText("第二条待确认分享")
        let first = try XCTUnwrap(store.id(from: firstURL))
        let second = try XCTUnwrap(store.id(from: secondURL))

        XCTAssertEqual(Set(store.pendingIDs()), Set([first, second]))
        store.remove(id: first)
        XCTAssertEqual(store.pendingIDs(), [second])
    }

    func testFileHandoffRoundTripsBytesAndRejectsUnsupportedPayload() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("AmemeIncomingFile-\(UUID().uuidString)", isDirectory: true)
        let store = IncomingShareHandoffStore(rootDirectory: root)
        let bytes = Data(repeating: 0x42, count: 1_024)

        let url = try store.writeFile(
            bytes,
            kind: .pdf,
            displayName: "会议资料.pdf",
        )
        let id = try XCTUnwrap(store.id(from: url))
        let read = try store.read(id: id)
        XCTAssertEqual(read.payload.kind, .pdf)
        XCTAssertEqual(read.fileData, bytes)
        XCTAssertNil(store.id(from: URL(string: "ameme://incoming-share/not-a-uuid")!))

        XCTAssertThrowsError(try store.writeText("   ")) { error in
            XCTAssertEqual(error as? IncomingSharePayloadError, .missingText)
        }
        try? FileManager.default.removeItem(at: root)
    }

    func testHandoffEnforcesTextAndFileLimitsAndKeepsAppGroupContractStable() throws {
        XCTAssertEqual(IncomingShareHandoffStore.appGroupIdentifier, "group.com.ameme.ios")
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("AmemeIncomingLimits-\(UUID().uuidString)", isDirectory: true)
        let store = IncomingShareHandoffStore(rootDirectory: root)

        XCTAssertThrowsError(
            try store.writeText(String(repeating: "x", count: IncomingSharePayload.maxTextLength + 1))
        ) { error in
            XCTAssertEqual(error as? IncomingSharePayloadError, .textTooLong)
        }
        XCTAssertThrowsError(
            try store.writeFile(
                Data(repeating: 0x01, count: IncomingSharePayload.maxFileBytes + 1),
                kind: .pdf,
                displayName: "too-large.pdf"
            )
        ) { error in
            XCTAssertEqual(error as? IncomingSharePayloadError, .fileTooLarge)
        }
        try? FileManager.default.removeItem(at: root)
    }

    func testDeletedSpaceFreezeClearsHiddenArtifactsAndRejectsNewHandoffs() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("AmemeIncomingFreeze-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let fileManager = FileManager.default
        let store = IncomingShareHandoffStore(rootDirectory: root, fileManager: fileManager)
        let url = try store.writeText("must be cleared")
        let id = try XCTUnwrap(store.id(from: url))
        try Data("orphaned atomic payload".utf8).write(
            to: root.appendingPathComponent(".handoff-orphan.tmp")
        )

        try store.freezeForDeletedSpaceAndClear()

        XCTAssertTrue(store.isFrozenForDeletedSpace)
        XCTAssertTrue(store.pendingIDs().isEmpty)
        XCTAssertEqual(
            try fileManager.contentsOfDirectory(atPath: root.path),
            [".space-deleted-v1"]
        )
        XCTAssertThrowsError(try store.read(id: id)) { error in
            XCTAssertEqual(error as? IncomingSharePayloadError, .localSpaceDeleted)
        }
        XCTAssertThrowsError(try store.writeText("must not return")) { error in
            XCTAssertEqual(error as? IncomingSharePayloadError, .localSpaceDeleted)
        }
    }
}
