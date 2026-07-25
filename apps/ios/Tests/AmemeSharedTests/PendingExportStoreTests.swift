import CryptoKit
import Foundation
import XCTest
@testable import AmemeShared

private final class PendingExportTestKeyStore: @unchecked Sendable, KeyMaterialStore {
    private let key = SymmetricKey(data: Data(repeating: 0x52, count: 32))

    func loadOrCreateKey() throws -> SymmetricKey { key }
}

@MainActor
final class PendingExportStoreTests: XCTestCase {
    func testEncryptedRoundTripAndClear() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("AmemePendingExport-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let fileManager = FileManager.default
        let store = PendingExportStore(
            fileManager: fileManager,
            keyStore: PendingExportTestKeyStore(),
            rootDirectory: root
        )
        let payload = Data("private structured export".utf8)

        try store.save(payload)
        let snapshot = try XCTUnwrap(
            fileManager.contentsOfDirectory(at: root, includingPropertiesForKeys: nil).first
        )
        let onDisk = try Data(contentsOf: snapshot)
        XCTAssertFalse(String(decoding: onDisk, as: UTF8.self).contains("private structured export"))
        XCTAssertEqual(try store.load(), payload)

        try store.clear()
        XCTAssertNil(try store.load())
    }

    func testCorruptSnapshotFailsClosed() throws {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("AmemePendingExportCorrupt-\(UUID().uuidString)", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let fileManager = FileManager.default
        let store = PendingExportStore(
            fileManager: fileManager,
            keyStore: PendingExportTestKeyStore(),
            rootDirectory: root
        )
        try store.save(Data("private structured export".utf8))
        let file = root.appendingPathComponent("pending-export-v1.json")
        try Data("not a valid envelope".utf8).write(to: file, options: .atomic)

        XCTAssertTrue(store.exists)
        XCTAssertThrowsError(try store.load())
        try store.clear()
        XCTAssertFalse(store.exists)
    }
}
