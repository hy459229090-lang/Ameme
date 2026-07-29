import Foundation
import CryptoKit
import AmemeShared

private final class SmokeKeyStore: @unchecked Sendable, KeyMaterialStore {
    private let key = SymmetricKey(data: Data(repeating: 0x2A, count: 32))

    func loadOrCreateKey() throws -> SymmetricKey { key }
}

private final class RecoveringKeyStore: @unchecked Sendable, KeyMaterialStore {
    private let key = SymmetricKey(data: Data(repeating: 0x3B, count: 32))
    var shouldFail = true

    func loadOrCreateKey() throws -> SymmetricKey {
        if shouldFail {
            throw NSError(domain: "AmemeSharedSmoke", code: 1)
        }
        return key
    }
}

@main
@MainActor
struct AmemeSharedSmoke {
    static func main() async {
        let fileManager = FileManager()
        let recoveryKeyStore = RecoveringKeyStore()
        let recoveryRoot = fileManager.temporaryDirectory
            .appendingPathComponent("AmemeSharedSmoke-Recovery-\(UUID().uuidString)", isDirectory: true)
        defer { try? fileManager.removeItem(at: recoveryRoot) }
        let recoveryStore = LocalMemoryStore(
            fileManager: fileManager,
            keyStore: recoveryKeyStore,
            rootDirectory: recoveryRoot
        )
        precondition(recoveryStore.storageState == .recoverableError, "storage failure did not surface as recoverable")
        recoveryKeyStore.shouldFail = false
        recoveryStore.retryLoad()
        precondition(recoveryStore.storageState == .ready, "storage retry did not recover")

        let keyStore = SmokeKeyStore()
        let root = fileManager.temporaryDirectory
            .appendingPathComponent("AmemeSharedSmoke-\(UUID().uuidString)", isDirectory: true)
        defer { try? fileManager.removeItem(at: root) }

        let store = LocalMemoryStore(
            fileManager: fileManager,
            keyStore: keyStore,
            rootDirectory: root
        )
        precondition(store.storageState == .ready, "local store did not become ready")
        precondition(store.mode == .empty, "empty local state did not resolve consistently")
        precondition(store.addText("完成共享核心闭环") != nil, "text capture failed")
        precondition(store.mode == .sparse, "sparse local state did not resolve consistently")
        precondition(store.search(query: "主动输入").count == 1, "source label search was not preserved")
        precondition(store.search(query: "完成 主动输入").count == 1, "multi-term AND search was not preserved")
        precondition(store.search(query: "完成 不存在").isEmpty, "multi-term search incorrectly matched an incomplete query")
        let mediaPayload = Data("sensitive-audio-payload".utf8)
        let mediaEvent = store.addMedia(mediaPayload, kind: .voice, fileExtension: "m4a")
        precondition(mediaEvent.sensitivity == .confidential, "media sensitivity boundary was not preserved")
        guard let mediaLocator = mediaEvent.sourceLocator else {
            preconditionFailure("encrypted media locator missing")
        }
        let encryptedMedia = try! Data(contentsOf: URL(fileURLWithPath: mediaLocator))
        precondition(encryptedMedia != mediaPayload, "media was written as plaintext")
        precondition(!String(decoding: encryptedMedia, as: UTF8.self).contains("sensitive-audio-payload"), "media payload leaked on disk")
        let photoEvent = store.addPhotoReference("photos-asset-demo")
        precondition(photoEvent?.sourceLocator == "photos://photos-asset-demo", "photo reference was not preserved")
        precondition(photoEvent?.sensitivity == .confidential, "photo sensitivity boundary was not preserved")
        let rawOnlyEvent = store.addMedia(
            Data("raw-only-owned-ciphertext".utf8),
            kind: .voice,
            fileExtension: "m4a"
        )
        let rawOnlySource = store.sourceObjects(for: rawOnlyEvent.id).first!
        let rawOnlyLocator = rawOnlyEvent.sourceLocator!
        let rawOnlyResult = store.deleteRawOnly(sourceObjectID: rawOnlySource.sourceObjectID)
        precondition(
            rawOnlyResult.status == .completed &&
                store.event(id: rawOnlyEvent.id)?.sourceLocator == nil &&
                store.event(id: rawOnlyEvent.id)?.revision == 2 &&
                !fileManager.fileExists(atPath: rawOnlyLocator),
            "app-owned Raw-only delete did not preserve structure and remove ciphertext"
        )
        let photoSource = store.sourceObjects(for: photoEvent!.id).first!
        let photoRawResult = store.deleteRawOnly(sourceObjectID: photoSource.sourceObjectID)
        precondition(
            photoRawResult.status == .externalNotOwned &&
                store.event(id: photoEvent!.id)?.sourceLocator == "photos://photos-asset-demo",
            "external photo original was incorrectly claimed or mutated by Raw-only delete"
        )
        let photoCascadeResult = store.deleteSourceCascade(
            sourceObjectID: photoSource.sourceObjectID
        )
        precondition(
            photoCascadeResult.status == .completedLocalOnly &&
                store.event(id: photoEvent!.id) == nil &&
                store.sourceObjects.first(where: {
                    $0.sourceObjectID == photoSource.sourceObjectID
                })?.sourceLocator == "photos://photos-asset-demo",
            "external source cascade did not report local-only completion"
        )
        guard let second = store.addText("验证日期范围和导出") else {
            preconditionFailure("second text capture failed")
        }
        precondition(store.mode == .ready, "ready local state did not resolve consistently")
        precondition(store.search(query: "日期").contains(where: { $0.id == second.id }), "keyword search failed")
        let yesterday = Calendar.current.date(byAdding: .day, value: -1, to: .now)!
        let older = store.add(
            title: "昨天的范围事件",
            detail: "用于验证开始和结束日期筛选。",
            kind: .text,
            sourceLabel: "Smoke",
            localDate: yesterday
        )
        let yesterdayResults = store.search(query: "", startDate: yesterday, endDate: yesterday)
        precondition(yesterdayResults.contains(where: { $0.id == older.id }), "date range search failed")
        precondition(!yesterdayResults.contains(where: { $0.id == second.id }), "date range leaked today's event")
        precondition(store.summary(for: .now).state == .ready, "summary should be ready")

        precondition(store.addUserWords("补充一条用户说明", to: second.id), "revision update failed")
        precondition(store.event(id: second.id)?.revision == 2, "revision was not appended")

        _ = store.add(
            title: "受限内容",
            detail: "不应进入导出或小结",
            kind: .text,
            sourceLabel: "Smoke",
            factStatus: .confirmed,
            sensitivity: .restricted
        )
        let export = try! store.exportData()
        let exportText = String(decoding: export, as: UTF8.self)
        precondition(exportText.contains("schemaVersion"), "export envelope missing")
        precondition(exportText.contains("user_asserted"), "export status wire value was not normalized")
        precondition(!exportText.contains("受限内容"), "restricted event leaked into export")

        let pendingExportRoot = fileManager.temporaryDirectory
            .appendingPathComponent("AmemeSharedSmoke-PendingExport-\(UUID().uuidString)", isDirectory: true)
        defer { try? fileManager.removeItem(at: pendingExportRoot) }
        let pendingExportStore = PendingExportStore(
            fileManager: fileManager,
            keyStore: keyStore,
            rootDirectory: pendingExportRoot
        )
        try! pendingExportStore.save(export)
        let pendingExportFile = pendingExportRoot.appendingPathComponent("pending-export-v1.json")
        let pendingExportBytes = try! Data(contentsOf: pendingExportFile)
        precondition(
            !String(decoding: pendingExportBytes, as: UTF8.self).contains("受限内容"),
            "pending export snapshot leaked restricted content"
        )
        precondition(try! pendingExportStore.load() == export, "pending export did not round-trip")
        try! pendingExportStore.clear()
        precondition(try! pendingExportStore.load() == nil, "pending export was not cleared")

        precondition(store.delete(id: second.id), "local delete failed")
        precondition(store.event(id: second.id) == nil, "deleted event remained visible")
        precondition(store.delete(id: mediaEvent.id), "media event delete failed")
        precondition(!fileManager.fileExists(atPath: mediaLocator), "deleted media file remained on disk")

        let reloaded = LocalMemoryStore(
            fileManager: fileManager,
            keyStore: keyStore,
            rootDirectory: root
        )
        precondition(reloaded.events.contains(where: { $0.id == older.id }), "encrypted reload failed")

        let recoverySandbox = fileManager.temporaryDirectory
            .appendingPathComponent("AmemeSharedSmoke-LocalRecovery-\(UUID().uuidString)", isDirectory: true)
        defer { try? fileManager.removeItem(at: recoverySandbox) }
        try! fileManager.createDirectory(at: recoverySandbox, withIntermediateDirectories: true)
        let recoveryDeletionEvent = reloaded.addText("本机恢复不得复活该事件")!
        let oldRecoveryBackup = recoverySandbox.appendingPathComponent("old-backup", isDirectory: true)
        let oldRecoveryManifest = try! reloaded.createLocalRecoveryBackup(
            at: oldRecoveryBackup,
            createdAt: Date(timeIntervalSince1970: 1_759_100_000)
        )
        precondition(!oldRecoveryManifest.productionRecoveryClaim, "local backup claimed production disaster recovery")
        precondition(reloaded.delete(id: recoveryDeletionEvent.id), "recovery deletion did not commit")
        precondition(!reloaded.deletionTombstones.isEmpty, "event deletion did not persist a tombstone")
        let staleCandidate = recoverySandbox.appendingPathComponent("stale-candidate", isDirectory: true)
        precondition(
            (try? reloaded.restoreLocalRecoveryCandidate(
                from: oldRecoveryBackup,
                to: staleCandidate
            )) == nil,
            "old recovery snapshot bypassed the authoritative deletion watermark"
        )
        precondition(
            !fileManager.fileExists(atPath: staleCandidate.path),
            "rejected stale recovery left a partial destination"
        )
        let recoveryMediaEvent = reloaded.addMedia(
            Data("same-install-recovery-media".utf8),
            kind: .voice,
            fileExtension: "m4a"
        )
        let currentRecoveryBackup = recoverySandbox.appendingPathComponent("current-backup", isDirectory: true)
        let currentRecoveryManifest = try! reloaded.createLocalRecoveryBackup(at: currentRecoveryBackup)
        precondition(
            try! reloaded.verifyLocalRecoveryBackup(at: currentRecoveryBackup) == currentRecoveryManifest,
            "local recovery artifact did not verify"
        )
        let recoveryCandidateRoot = recoverySandbox.appendingPathComponent("candidate", isDirectory: true)
        let recoveryCandidate = try! reloaded.restoreLocalRecoveryCandidate(
            from: currentRecoveryBackup,
            to: recoveryCandidateRoot
        )
        precondition(!recoveryCandidate.productionRecoveryClaim, "recovery candidate claimed production recovery")
        let recoveredCandidateStore = LocalMemoryStore(
            fileManager: fileManager,
            keyStore: keyStore,
            rootDirectory: recoveryCandidateRoot
        )
        precondition(
            recoveredCandidateStore.event(id: recoveryDeletionEvent.id) == nil,
            "recovery candidate resurrected a deleted Event"
        )
        precondition(
            recoveredCandidateStore.deletionTombstones.contains(where: {
                $0.objectID == recoveryDeletionEvent.id.uuidString.lowercased()
            }),
            "recovery candidate lost the deletion tombstone"
        )
        let recoveredMediaLocator = recoveredCandidateStore.event(id: recoveryMediaEvent.id)?
            .sourceLocator
        precondition(
            recoveredCandidateStore.storageState == .ready &&
                recoveredMediaLocator?.hasPrefix(recoveryCandidateRoot.path + "/media/") == true &&
                fileManager.fileExists(atPath: recoveredMediaLocator!),
            "recovery candidate retained a transient staging media locator"
        )
        let nonemptyRecoveryTarget = recoverySandbox.appendingPathComponent("nonempty", isDirectory: true)
        try! fileManager.createDirectory(at: nonemptyRecoveryTarget, withIntermediateDirectories: true)
        let recoverySentinel = nonemptyRecoveryTarget.appendingPathComponent("keep.txt")
        try! Data("keep".utf8).write(to: recoverySentinel)
        precondition(
            (try? reloaded.restoreLocalRecoveryCandidate(
                from: currentRecoveryBackup,
                to: nonemptyRecoveryTarget
            )) == nil,
            "local recovery overwrote a nonempty target"
        )
        precondition(
            try! String(contentsOf: recoverySentinel, encoding: .utf8) == "keep",
            "rejected recovery changed the nonempty target"
        )
        let activationLiveOnly = reloaded.addText("恢复切换后不应保留的新 live 事件")!
        let activationTime = Date()
        let recoveryConfirmationID =
            LocalRecoveryActivationAuthorization.confirmationPrefix +
            UUID().uuidString.lowercased()
        precondition(
            (try? reloaded.activateLocalRecoveryCandidate(
                recoveryCandidate,
                authorization: LocalRecoveryActivationAuthorization(
                    confirmationID: recoveryConfirmationID,
                    candidateBackupID: currentRecoveryManifest.backupID,
                    confirmedAt: activationTime.addingTimeInterval(-120),
                    expiresAt: activationTime.addingTimeInterval(-1)
                ),
                activatedAt: activationTime
            )) == nil,
            "expired recovery activation authorization was accepted"
        )
        precondition(
            reloaded.event(id: activationLiveOnly.id) != nil,
            "rejected recovery activation mutated the live projection"
        )
        let recoveryReceipt = try! reloaded.activateLocalRecoveryCandidate(
            recoveryCandidate,
            authorization: LocalRecoveryActivationAuthorization(
                confirmationID: recoveryConfirmationID,
                candidateBackupID: currentRecoveryManifest.backupID,
                confirmedAt: activationTime.addingTimeInterval(-30),
                expiresAt: activationTime.addingTimeInterval(300)
            ),
            activatedAt: activationTime
        )
        precondition(
            recoveryReceipt.confirmationID == recoveryConfirmationID &&
                recoveryReceipt.candidateBackupID == currentRecoveryManifest.backupID &&
                !recoveryReceipt.cleanupPending &&
                !recoveryReceipt.productionRecoveryClaim,
            "same-install recovery activation receipt was invalid"
        )
        precondition(
            reloaded.event(id: activationLiveOnly.id) == nil,
            "activated recovery candidate did not replace the prior live projection"
        )
        precondition(
            reloaded.event(id: recoveryDeletionEvent.id) == nil &&
                reloaded.deletionTombstones.contains(where: {
                    $0.objectID == recoveryDeletionEvent.id.uuidString.lowercased()
                }),
            "activated recovery candidate resurrected deleted data"
        )
        let activatedMediaLocator = reloaded.event(id: recoveryMediaEvent.id)?.sourceLocator
        precondition(
            activatedMediaLocator?.hasPrefix(root.path + "/media/") == true &&
                fileManager.fileExists(atPath: activatedMediaLocator!),
            "activated recovery did not rewrite owned media to the live root"
        )
        precondition(
            fileManager.fileExists(atPath: recoveryCandidate.directory.path),
            "recovery activation consumed the isolated candidate"
        )
        let managedRecoveryRoot = recoverySandbox.appendingPathComponent(
            "managed-recovery-point",
            isDirectory: true
        )
        let recoveryPointManager = LocalRecoveryPointManager(
            rootDirectory: managedRecoveryRoot,
            fileManager: fileManager
        )
        let managedBackedUp = reloaded.addText("普通用户恢复点内事件")!
        let managedCreatedAt = Date(timeIntervalSince1970: 1_759_100_300)
        let managedStatus = try! recoveryPointManager.create(
            using: reloaded,
            createdAt: managedCreatedAt
        )
        precondition(
            managedStatus.availability == .verified &&
                managedStatus.createdAt == managedCreatedAt &&
                (managedStatus.snapshotBytes ?? 0) > 0,
            "managed recovery point did not complete an isolated restore verification"
        )
        let managedLiveOnly = reloaded.addText("普通用户恢复后应消失")!
        let managedActivatedAt = Date(timeIntervalSince1970: 1_759_100_360)
        let managedActivated = try! recoveryPointManager.activate(
            using: reloaded,
            confirmedAt: managedActivatedAt
        )
        precondition(
            managedActivated.availability == .verified &&
                managedActivated.lastActivatedAt == managedActivatedAt &&
                reloaded.event(id: managedBackedUp.id) != nil &&
                reloaded.event(id: managedLiveOnly.id) == nil,
            "managed recovery activation did not replace and re-verify the live store"
        )
        let reopenedRecoveryPointManager = LocalRecoveryPointManager(
            rootDirectory: managedRecoveryRoot,
            fileManager: fileManager
        )
        precondition(
            reopenedRecoveryPointManager.refresh(using: reloaded).lastActivatedAt ==
                managedActivatedAt,
            "managed recovery activation receipt did not survive manager restart"
        )

        let coverageRoot = fileManager.temporaryDirectory
            .appendingPathComponent("AmemeSharedSmoke-Coverage-\(UUID().uuidString)", isDirectory: true)
        defer { try? fileManager.removeItem(at: coverageRoot) }
        let coverageStore = LocalMemoryStore(
            fileManager: fileManager,
            keyStore: keyStore,
            rootDirectory: coverageRoot
        )
        let coverageRegistry = try! MobileSourceCapabilities.makeRegistry()
        let calendarSignal = CoverageSignal(
            signalID: "signal_smoke_calendar_001",
            capabilityID: "cap_calendar",
            sourceState: .available,
            contextTypes: [.timeSchedule],
            observedFields: [.time, .action],
            factStatus: .planned,
            importance: .high,
            confidence: 1,
            sourceObjectIDs: ["source_smoke_calendar_001"],
            observedAt: "2026-07-26T08:00:00Z",
            timeRange: CoverageTimeRange(
                start: "2026-07-26T10:00:00Z",
                end: "2026-07-26T11:00:00Z",
                timezone: "Asia/Shanghai",
                precision: .range
            ),
            eventHint: CoverageEventHint(eventType: .activity, title: "Smoke 日历计划"),
            gapHints: [
                CoverageGapHint(
                    contextType: .activityResult,
                    reason: .actualityUnconfirmed,
                    valueLevel: .high
                ),
            ]
        )
        let coverageCompilation = try! CoverageCompiler(registry: coverageRegistry).compileDay(
            dayID: "day_smoke_coverage_001",
            ownerID: "owner_smoke",
            spaceID: "space_personal",
            localDate: "2026-07-26",
            timezone: "Asia/Shanghai",
            signals: [calendarSignal]
        )
        precondition(
            coverageStore.persistCoverageCompilation(
                coverageCompilation,
                registryVersion: "mobile-v1",
                compiledAt: "2026-07-26T08:00:01Z"
            ),
            "coverage compilation did not persist"
        )
        precondition(coverageStore.events.isEmpty, "coverage persistence created an Event")
        let coverageReloaded = LocalMemoryStore(
            fileManager: fileManager,
            keyStore: keyStore,
            rootDirectory: coverageRoot
        )
        precondition(
            coverageReloaded.coverageDay(id: "day_smoke_coverage_001")?.candidates.first?.state == .open,
            "coverage candidate did not survive encrypted reload"
        )
        let acceptedCoverageEvent = try! coverageReloaded.acceptCoverageCandidate(
            CoverageCandidateAcceptance(
                dayID: "day_smoke_coverage_001",
                candidateID: "candidate_signal_smoke_calendar_001",
                detail: "用户显式选择保存该计划。",
                sourceLabel: "用户选择的日历",
                captureKind: .importFile,
                localDate: .now,
                time: .now,
                sensitivity: .confidential,
                mode: .preserveEvidence,
                linkedAt: "2026-07-26T08:01:00Z"
            )
        )
        precondition(acceptedCoverageEvent.factStatus == .planned, "calendar candidate was upgraded from planned")
        precondition(
            coverageReloaded.coverageDay(id: "day_smoke_coverage_001")?.candidates.first?.state == .consumed,
            "accepted candidate did not become consumed"
        )
        precondition(
            coverageReloaded.persistCoverageCompilation(
                coverageCompilation,
                registryVersion: "mobile-v1",
                compiledAt: "2026-07-26T08:02:00Z"
            ),
            "coverage recompile did not persist"
        )
        precondition(
            coverageReloaded.coverageDay(id: "day_smoke_coverage_001")?.candidates.first?.state == .consumed,
            "recompile reopened a consumed candidate"
        )
        let coverageSource = coverageReloaded.sourceObjects(for: acceptedCoverageEvent.id).first!
        let cascadeResult = coverageReloaded.deleteSourceCascade(
            sourceObjectID: coverageSource.sourceObjectID
        )
        precondition(
            cascadeResult.status == .completed && cascadeResult.affectedEventCount == 1,
            "single-source local cascade did not complete"
        )
        precondition(
            coverageReloaded.coverageEventLinks.first?.state == .detached,
            "source cascade did not detach the coverage link"
        )
        precondition(
            !coverageReloaded.persistCoverageCompilation(
                coverageCompilation,
                registryVersion: "mobile-v1",
                compiledAt: "2026-07-26T08:03:00Z"
            ),
            "terminal source watermark allowed coverage evidence to resurrect"
        )
        let coverageAfterDelete = LocalMemoryStore(
            fileManager: fileManager,
            keyStore: keyStore,
            rootDirectory: coverageRoot
        )
        precondition(
            coverageAfterDelete.event(id: acceptedCoverageEvent.id) == nil &&
            coverageAfterDelete.coverageDay(id: "day_smoke_coverage_001")?.candidates.first?.state == .consumed &&
            coverageAfterDelete.coverageEventLinks.first?.state == .detached &&
            coverageAfterDelete.deletionTombstones.contains(where: {
                $0.objectType == DeletionTombstone.sourceObjectType &&
                    $0.objectID == coverageSource.sourceObjectID
            }),
            "source cascade or its watermark was lost after encrypted reload"
        )

        let multiSourceRoot = fileManager.temporaryDirectory
            .appendingPathComponent(
                "AmemeSharedSmoke-MultiSource-\(UUID().uuidString)",
                isDirectory: true
            )
        defer { try? fileManager.removeItem(at: multiSourceRoot) }
        let multiSourceStore = LocalMemoryStore(
            fileManager: fileManager,
            keyStore: keyStore,
            rootDirectory: multiSourceRoot
        )
        let multiSourceSignal = CoverageSignal(
            signalID: "signal_smoke_multi_source_001",
            capabilityID: "cap_calendar",
            sourceState: .available,
            contextTypes: [.timeSchedule],
            observedFields: [.time, .action],
            factStatus: .planned,
            importance: .high,
            confidence: 1,
            sourceObjectIDs: ["source_smoke_multi_a", "source_smoke_multi_b"],
            observedAt: "2026-07-26T08:10:00Z",
            timeRange: CoverageTimeRange(
                start: "2026-07-26T12:00:00Z",
                end: "2026-07-26T13:00:00Z",
                timezone: "Asia/Shanghai",
                precision: .range
            ),
            eventHint: CoverageEventHint(eventType: .activity, title: "Smoke 多来源计划"),
            gapHints: []
        )
        let multiSourceCompilation = try! CoverageCompiler(
            registry: coverageRegistry
        ).compileDay(
            dayID: "day_smoke_multi_source_001",
            ownerID: "owner_smoke",
            spaceID: "space_personal",
            localDate: "2026-07-26",
            timezone: "Asia/Shanghai",
            signals: [multiSourceSignal]
        )
        precondition(
            multiSourceStore.persistCoverageCompilation(
                multiSourceCompilation,
                registryVersion: "mobile-v1",
                compiledAt: "2026-07-26T08:10:01Z"
            ),
            "multi-source coverage compilation did not persist"
        )
        let multiSourceEvent = try! multiSourceStore.acceptCoverageCandidate(
            CoverageCandidateAcceptance(
                dayID: "day_smoke_multi_source_001",
                candidateID: "candidate_signal_smoke_multi_source_001",
                detail: "每个保留字段都由两个来源独立支持。",
                sourceLabel: "Smoke 多来源",
                captureKind: .importFile,
                localDate: .now,
                time: .now,
                fieldSourceObjectIDs: [
                    .time: ["source_smoke_multi_a", "source_smoke_multi_b"],
                    .action: ["source_smoke_multi_a", "source_smoke_multi_b"],
                ],
                linkedAt: "2026-07-26T08:11:00Z"
            )
        )
        let multiSourceCascade = multiSourceStore.deleteSourceCascade(
            sourceObjectID: "source_smoke_multi_a"
        )
        precondition(
            multiSourceCascade.status == .completed &&
                multiSourceCascade.recomputedEventCount == 1 &&
                multiSourceCascade.deletedEventCount == 0 &&
                multiSourceStore.event(id: multiSourceEvent.id)?.revision == 2 &&
                Set(multiSourceStore.eventFieldEvidence.filter {
                    $0.eventID == multiSourceEvent.id && $0.state == .active
                }.map(\.sourceObjectID)) == ["source_smoke_multi_b"],
            "complete exact-revision field evidence did not support bounded recomputation"
        )
        let multiSourceReloaded = LocalMemoryStore(
            fileManager: fileManager,
            keyStore: keyStore,
            rootDirectory: multiSourceRoot
        )
        precondition(
            multiSourceReloaded.event(id: multiSourceEvent.id)?.revision == 2 &&
                multiSourceReloaded.event(id: multiSourceEvent.id)?.sourceLabel ==
                    "多来源（已重算）" &&
                multiSourceReloaded.sourceObjects.first(where: {
                    $0.sourceObjectID == "source_smoke_multi_a"
                })?.state == .deleted,
            "multi-source recomputation or source watermark was lost after reload"
        )

        let userConfirmationRoot = fileManager.temporaryDirectory
            .appendingPathComponent(
                "AmemeSharedSmoke-UserConfirmation-\(UUID().uuidString)",
                isDirectory: true
            )
        defer { try? fileManager.removeItem(at: userConfirmationRoot) }
        let userConfirmationStore = LocalMemoryStore(
            fileManager: fileManager,
            keyStore: keyStore,
            rootDirectory: userConfirmationRoot
        )
        let userConfirmationSignal = CoverageSignal(
            signalID: "signal_smoke_user_confirmation_001",
            capabilityID: "cap_calendar",
            sourceState: .available,
            contextTypes: [.timeSchedule],
            observedFields: [.time, .action],
            factStatus: .planned,
            importance: .high,
            confidence: 1,
            sourceObjectIDs: ["source_smoke_user_confirmation"],
            observedAt: "2026-07-26T08:20:00Z",
            timeRange: CoverageTimeRange(
                start: "2026-07-26T14:00:00Z",
                end: "2026-07-26T15:00:00Z",
                timezone: "Asia/Shanghai",
                precision: .range
            ),
            eventHint: CoverageEventHint(
                eventType: .activity,
                title: "Smoke 用户确认计划"
            ),
            gapHints: []
        )
        let userConfirmationCompilation = try! CoverageCompiler(
            registry: coverageRegistry
        ).compileDay(
            dayID: "day_smoke_user_confirmation_001",
            ownerID: "owner_smoke",
            spaceID: "space_personal",
            localDate: "2026-07-26",
            timezone: "Asia/Shanghai",
            signals: [userConfirmationSignal]
        )
        precondition(
            userConfirmationStore.persistCoverageCompilation(
                userConfirmationCompilation,
                registryVersion: "mobile-v1",
                compiledAt: "2026-07-26T08:20:01Z"
            ),
            "user-confirmed coverage compilation did not persist"
        )
        let userConfirmedEvent = try! userConfirmationStore.acceptCoverageCandidate(
            CoverageCandidateAcceptance(
                dayID: "day_smoke_user_confirmation_001",
                candidateID: "candidate_signal_smoke_user_confirmation_001",
                detail: "用户显式确认了完整候选字段集。",
                sourceLabel: "Smoke 用户确认来源",
                captureKind: .importFile,
                localDate: .now,
                time: .now,
                mode: .userConfirmed,
                linkedAt: "2026-07-26T08:21:00Z"
            )
        )
        let originalConfirmation = userConfirmationStore.eventUserConfirmations.first!
        precondition(
            originalConfirmation.completeFieldSet &&
                Set(originalConfirmation.confirmedFields) == [.time, .action],
            "explicit acceptance did not create bounded complete user confirmation"
        )
        let userConfirmationCascade = userConfirmationStore.deleteSourceCascade(
            sourceObjectID: "source_smoke_user_confirmation"
        )
        precondition(
            userConfirmationCascade.status == .completed &&
                userConfirmationCascade.recomputedEventCount == 1 &&
                userConfirmationCascade.deletedEventCount == 0 &&
                userConfirmationStore.event(id: userConfirmedEvent.id)?.revision == 2 &&
                userConfirmationStore.event(id: userConfirmedEvent.id)?.sourceLabel ==
                    "用户确认（来源已删除）" &&
                userConfirmationStore.eventUserConfirmations.contains(where: {
                    $0.confirmationID == originalConfirmation.confirmationID &&
                        $0.eventRevision == 1 &&
                        $0.state == .deleted
                }) &&
                userConfirmationStore.eventUserConfirmations.contains(where: {
                    $0.confirmationID == originalConfirmation.confirmationID &&
                        $0.eventRevision == 2 &&
                        $0.state == .active
                }),
            "complete user confirmation did not preserve the event without a source claim"
        )
        let partialConfirmationEvent = userConfirmationStore.addPhotoReference(
            "smoke-partial-confirmation"
        )!
        precondition(
            userConfirmationStore.updateFactStatus(
                .processing,
                to: partialConfirmationEvent.id
            ) &&
                userConfirmationStore.updateFactStatus(
                    .confirmed,
                    to: partialConfirmationEvent.id
                ),
            "partial fact confirmation fixture failed"
        )
        let partialConfirmation = userConfirmationStore.eventUserConfirmations.first {
            $0.eventID == partialConfirmationEvent.id && $0.state == .active
        }!
        let partialSource = userConfirmationStore.sourceObjects(
            for: partialConfirmationEvent.id
        ).first!
        let partialCascade = userConfirmationStore.deleteSourceCascade(
            sourceObjectID: partialSource.sourceObjectID
        )
        precondition(
            !partialConfirmation.completeFieldSet &&
                partialCascade.status == .completedLocalOnly &&
                partialCascade.recomputedEventCount == 0 &&
                partialCascade.deletedEventCount == 1 &&
                userConfirmationStore.event(id: partialConfirmationEvent.id) == nil,
            "partial confirmation incorrectly preserved unsupported event content"
        )
        let userConfirmationReloaded = LocalMemoryStore(
            fileManager: fileManager,
            keyStore: keyStore,
            rootDirectory: userConfirmationRoot
        )
        precondition(
            userConfirmationReloaded.event(id: userConfirmedEvent.id)?.revision == 2 &&
                userConfirmationReloaded.event(id: partialConfirmationEvent.id) == nil &&
                userConfirmationReloaded.eventUserConfirmations.contains(where: {
                    $0.confirmationID == originalConfirmation.confirmationID &&
                        $0.eventRevision == 2 &&
                        $0.state == .active
                }),
            "user confirmation state or terminal partial deletion was lost after reload"
        )

        let memoryRoot = fileManager.temporaryDirectory
            .appendingPathComponent("AmemeSharedSmoke-LongTermMemory-\(UUID().uuidString)", isDirectory: true)
        defer { try? fileManager.removeItem(at: memoryRoot) }
        let memoryStore = LocalMemoryStore(
            fileManager: fileManager,
            keyStore: keyStore,
            rootDirectory: memoryRoot
        )
        let memoryEvent = memoryStore.add(
            title: "完成长期 Memory 生产切片",
            detail: "本机生产 smoke 已获得直接构建证据。",
            kind: .text,
            sourceLabel: "Shared Smoke",
            factStatus: .confirmed,
            eventType: .milestone,
            evidenceState: .observed
        )
        let memoryTime = Date(timeIntervalSince1970: 1_759_000_000)
        let memoryCandidate = try! memoryStore.proposeLongTermMemory(
            LongTermMemoryProposal(
                ownerID: "local_owner",
                spaceID: "space_personal",
                sourceEventID: memoryEvent.id,
                expectedEventRevision: memoryEvent.revision,
                type: .fact,
                valueSummary: "长期 Memory 生产切片已完成",
                validFrom: memoryTime,
                proposedAt: memoryTime
            )
        )
        precondition(
            memoryCandidate.state == .eligibleForMemoryCompiler &&
            memoryStore.visibleLongTermMemories(at: memoryTime).isEmpty,
            "compiler candidate was treated as confirmed long-term Memory"
        )
        let activeMemory = try! memoryStore.confirmLongTermMemory(
            LongTermMemoryConfirmation(
                memoryID: memoryCandidate.memoryID,
                confirmedAt: memoryTime.addingTimeInterval(1)
            )
        )
        precondition(
            memoryStore.visibleLongTermMemories(at: memoryTime.addingTimeInterval(2)).map(\.memoryID) ==
                [activeMemory.memoryID],
            "explicitly confirmed long-term Memory was not visible"
        )
        precondition(
            memoryStore.addUserWords("上游 Event 修订后应失效", to: memoryEvent.id),
            "upstream Event revision failed"
        )
        precondition(
            memoryStore.longTermMemory(id: activeMemory.memoryID)?.state == .invalidated &&
            memoryStore.visibleLongTermMemories(at: memoryTime.addingTimeInterval(2)).isEmpty,
            "Event revision did not invalidate long-term Memory"
        )
        let memoryReloaded = LocalMemoryStore(
            fileManager: fileManager,
            keyStore: keyStore,
            rootDirectory: memoryRoot
        )
        let replayedMemory = try! memoryReloaded.proposeLongTermMemory(
            LongTermMemoryProposal(
                ownerID: "local_owner",
                spaceID: "space_personal",
                sourceEventID: memoryEvent.id,
                expectedEventRevision: memoryEvent.revision,
                type: .fact,
                valueSummary: "不得复活",
                validFrom: memoryTime,
                proposedAt: memoryTime.addingTimeInterval(3)
            )
        )
        precondition(
            replayedMemory.memoryID == activeMemory.memoryID &&
            replayedMemory.state == .invalidated,
            "recompile or encrypted reload resurrected invalidated long-term Memory"
        )

        let reuseRoot = fileManager.temporaryDirectory
            .appendingPathComponent("AmemeSharedSmoke-Reuse-\(UUID().uuidString)", isDirectory: true)
        defer { try? fileManager.removeItem(at: reuseRoot) }
        let reuseStore = LocalMemoryStore(
            fileManager: fileManager,
            keyStore: keyStore,
            rootDirectory: reuseRoot
        )
        let reuseCanary = "REUSE_CANARY_NEVER_TELEMETRY"
        let reuseTime = Date(timeIntervalSince1970: 1_759_100_000)
        _ = reuseStore.add(
            title: "\(reuseCanary) 历史进展",
            detail: "复用生产切片进入封闭验证。",
            kind: .text,
            sourceLabel: "Shared Smoke",
            localDate: reuseTime,
            eventType: .milestone
        )
        let reuseDecisionEvent = reuseStore.add(
            title: "\(reuseCanary) 决定",
            detail: "按恢复门禁推进。",
            kind: .text,
            sourceLabel: "Shared Smoke",
            localDate: reuseTime,
            eventType: .decision
        )
        _ = reuseStore.add(
            title: "\(reuseCanary) 受限",
            detail: "不得进入复用上下文。",
            kind: .text,
            sourceLabel: "Shared Smoke",
            localDate: reuseTime,
            eventType: .decision,
            sensitivity: .restricted
        )
        let reuseMemoryCandidate = try! reuseStore.proposeLongTermMemory(
            LongTermMemoryProposal(
                ownerID: "local_owner",
                spaceID: "space_personal",
                sourceEventID: reuseDecisionEvent.id,
                expectedEventRevision: reuseDecisionEvent.revision,
                type: .decision,
                valueSummary: "\(reuseCanary) 恢复门禁决定",
                validFrom: reuseTime,
                proposedAt: reuseTime
            )
        )
        let reuseMemory = try! reuseStore.confirmLongTermMemory(
            LongTermMemoryConfirmation(
                memoryID: reuseMemoryCandidate.memoryID,
                confirmedAt: reuseTime.addingTimeInterval(1)
            )
        )
        let reuseRequests = [
            ReuseRequest(
                spaceID: "space_personal",
                intent: .historicalSearch,
                query: reuseCanary,
                requestedAt: reuseTime.addingTimeInterval(2)
            ),
            ReuseRequest(
                spaceID: "space_personal",
                intent: .projectResume,
                query: reuseCanary,
                requestedAt: reuseTime.addingTimeInterval(2)
            ),
            ReuseRequest(
                spaceID: "space_personal",
                intent: .preMeetingContext,
                query: reuseCanary,
                meetingAnchorDate: reuseDecisionEvent.localDate,
                requestedAt: reuseTime.addingTimeInterval(2)
            ),
            ReuseRequest(
                spaceID: "space_personal",
                intent: .decisionCommitmentRecall,
                requestedAt: reuseTime.addingTimeInterval(2)
            ),
        ]
        let resolvedReuseContexts = reuseRequests.map { request in
            let context = try! reuseStore.buildReuseContext(request)
            return try! reuseStore.resolveReuseContext(
                context,
                at: reuseTime.addingTimeInterval(3)
            )
        }
        let reuseContexts = resolvedReuseContexts.map(\.context)
        precondition(
            Set(reuseContexts.map(\.intent)) == Set(ReuseIntent.allCases) &&
            reuseContexts.allSatisfy({ !$0.references.isEmpty }) &&
            resolvedReuseContexts.allSatisfy({
                !$0.items.isEmpty && $0.items.map(\.reference) == $0.context.references
            }),
            "one of the four bounded reuse journeys did not produce a context"
        )
        precondition(
            reuseContexts.allSatisfy({ $0.exclusions.contains(.restricted) }) &&
            reuseContexts.flatMap(\.references).allSatisfy({ $0.sensitivity != .restricted }),
            "Restricted content crossed the reuse policy boundary"
        )
        precondition(
            reuseContexts.last!.references.contains(where: {
                $0.objectType == .longTermMemory
            }),
            "decision/commitment recall did not use active long-term Memory"
        )
        precondition(
            resolvedReuseContexts.flatMap(\.items).allSatisfy({
                $0.sourceEvent.sensitivity != .restricted
            }) &&
            resolvedReuseContexts.last!.items.contains(where: {
                $0.memorySummary == "\(reuseCanary) 恢复门禁决定"
            }),
            "exact-revision reuse resolution exposed invalid or missing content"
        )
        for (index, context) in reuseContexts.enumerated() {
            _ = try! reuseStore.recordReuseOutcome(
                ReuseOutcomeSubmission(
                    attemptID: context.attemptID,
                    outcome: index == 0 ? .useful : .notUseful,
                    userAction: index == 1 ? .hidden : .none,
                    submittedAt: reuseTime.addingTimeInterval(Double(10 + index))
                )
            )
        }
        precondition(
            reuseStore.helpfulReuseCount(since: reuseTime) == 1 &&
            reuseStore.reuseTelemetryAggregates(since: reuseTime).reduce(0, {
                $0 + $1.attemptCount
            }) == 4,
            "content-free weekly reuse telemetry did not aggregate"
        )
        let reuseTelemetryJSON = String(
            decoding: try! JSONEncoder().encode(reuseStore.reuseAttempts),
            as: UTF8.self
        )
        precondition(
            !reuseTelemetryJSON.contains(reuseCanary) &&
            !reuseTelemetryJSON.contains(reuseDecisionEvent.id.uuidString) &&
            !reuseTelemetryJSON.contains(reuseMemory.memoryID.uuidString),
            "reuse telemetry persisted query, content, or raw lineage IDs"
        )
        precondition(
            reuseStore.addUserWords("用户修订后旧复用引用必须失效", to: reuseDecisionEvent.id),
            "reuse fixture revision failed"
        )
        let staleReuse = reuseStore.revalidateReuseContext(
            reuseContexts.last!,
            at: reuseTime.addingTimeInterval(60)
        )
        precondition(
            staleReuse.references.allSatisfy({
                $0.objectID != reuseDecisionEvent.id &&
                $0.sourceEventID != reuseDecisionEvent.id
            }) &&
            staleReuse.exclusions.contains(.invalidated),
            "reuse context survived an upstream Event revision"
        )
        let expiredReuse = reuseStore.revalidateReuseContext(
            reuseContexts.first!,
            at: reuseTime.addingTimeInterval(903)
        )
        precondition(
            expiredReuse.references.isEmpty && expiredReuse.exclusions.contains(.expired),
            "expired reuse context remained resolvable"
        )
        let reuseReloaded = LocalMemoryStore(
            fileManager: fileManager,
            keyStore: keyStore,
            rootDirectory: reuseRoot
        )
        precondition(
            reuseReloaded.reuseAttempts.count == 4 &&
            reuseReloaded.reuseOutcomes.count == 4 &&
            reuseReloaded.helpfulReuseCount(since: reuseTime) == 1,
            "content-free reuse telemetry did not survive encrypted reload"
        )

        let batchRoot = fileManager.temporaryDirectory
            .appendingPathComponent("AmemeSharedSmoke-Batch-\(UUID().uuidString)", isDirectory: true)
        defer { try? fileManager.removeItem(at: batchRoot) }
        let batchStore = LocalMemoryStore(
            fileManager: fileManager,
            keyStore: keyStore,
            rootDirectory: batchRoot
        )
        precondition(batchStore.addText("保留在批量导入之前") != nil, "batch fixture seed failed")
        let batchDrafts = [
            MemoryEventDraft(
                title: "批量日历一",
                detail: "计划一",
                factStatus: .planned,
                sourceLabel: "Smoke 日历",
                sourceLocator: "eventkit://smoke-1",
                captureKind: .importFile,
                eventType: .activity,
                evidenceState: .observed,
                sensitivity: .confidential
            ),
            MemoryEventDraft(
                title: "批量日历二",
                detail: "计划二",
                factStatus: .planned,
                sourceLabel: "Smoke 日历",
                sourceLocator: "eventkit://smoke-2",
                captureKind: .importFile,
                eventType: .activity,
                evidenceState: .observed,
                sensitivity: .confidential
            ),
        ]
        precondition(batchStore.addBatch(batchDrafts)?.count == 2, "atomic batch import did not commit")
        precondition(batchStore.events.filter { $0.sourceLabel == "Smoke 日历" }.count == 2, "batch events missing")
        precondition(batchStore.addBatch(batchDrafts)?.isEmpty == true, "calendar batch was not idempotent")

        let failureRoot = fileManager.temporaryDirectory
            .appendingPathComponent("AmemeSharedSmoke-BatchFailure-\(UUID().uuidString)", isDirectory: true)
        defer { try? fileManager.removeItem(at: failureRoot) }
        let failureStore = LocalMemoryStore(
            fileManager: fileManager,
            keyStore: keyStore,
            rootDirectory: failureRoot
        )
        precondition(failureStore.addText("失败前的本机记录") != nil, "failure fixture seed failed")
        let eventsFile = failureRoot.appendingPathComponent("events.enc")
        try? fileManager.removeItem(at: eventsFile)
        try! fileManager.createDirectory(at: eventsFile, withIntermediateDirectories: true)
        let failedDrafts = batchDrafts.map { draft in
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
        precondition(failureStore.addBatch(failedDrafts) == nil, "failed batch unexpectedly committed")
        precondition(failureStore.events.count == 1, "failed batch left partial events visible")
        precondition(failureStore.storageState == .recoverableError, "failed batch did not expose recovery state")

        reloaded.enterDemoMode()
        precondition(reloaded.isDemoMode, "demo mode did not open")
        precondition(reloaded.events.count >= 5, "demo data is too sparse for full-flow testing")
        precondition(!reloaded.search(query: "整理").isEmpty, "demo search fixture missing")
        reloaded.exitDemoMode()
        precondition(!reloaded.isDemoMode, "demo mode did not close")
        precondition(reloaded.events.allSatisfy { $0.id != second.id }, "deleted data reappeared after demo exit")

        let shareRoot = fileManager.temporaryDirectory
            .appendingPathComponent("AmemeSharedSmoke-IncomingShare-\(UUID().uuidString)", isDirectory: true)
        defer { try? fileManager.removeItem(at: shareRoot) }
        let shareStore = IncomingShareHandoffStore(rootDirectory: shareRoot)
        let shareURL = try! shareStore.writeText("来自其他 App 的合成分享", displayName: "Smoke 摘录")
        guard let shareID = shareStore.id(from: shareURL) else {
            preconditionFailure("incoming share URL was not opaque and parseable")
        }
        let shareRead = try! shareStore.read(id: shareID)
        precondition(shareRead.payload.text == "来自其他 App 的合成分享", "incoming share text did not round-trip")
        precondition(!shareURL.absoluteString.contains("来自其他 App"), "incoming share URL leaked content")
        precondition(shareStore.pendingIDs() == [shareID], "pending incoming share was not discoverable after restart")
        shareStore.remove(id: shareID)
        precondition((try? shareStore.read(id: shareID)) == nil, "incoming share was not removed")
        precondition(
            (try? shareStore.writeText(String(repeating: "x", count: IncomingSharePayload.maxTextLength + 1))) == nil,
            "oversized incoming share text was accepted"
        )
        precondition(
            (try? shareStore.writeFile(
                Data(repeating: 0x01, count: IncomingSharePayload.maxFileBytes + 1),
                kind: .pdf,
                displayName: "too-large.pdf"
            )) == nil,
            "oversized incoming share file was accepted"
        )

        let discoveredCandidate = AgentExperienceCandidate(
            id: "desktop-001",
            deviceName: "Ameme Desktop",
            agentName: "Codex",
            method: .lanDiscovery,
            capabilities: ["能力待授权确认"],
            simulated: false
        )
        precondition(!discoveredCandidate.simulated)
        precondition(AgentExperienceServiceContract.bonjourServiceType == "_ameme-agent._tcp")

        let grantNow = Date(timeIntervalSince1970: 1_800_000_000)
        let grant = AgentAccessGrant(
            grantID: "grt_synthetic_001",
            ownerID: "user_synthetic_001",
            callerID: "agent_synthetic_001",
            purposes: ["autonomous_memory"],
            spaces: ["space_personal"],
            dataTypes: ["event"],
            notBefore: grantNow,
            expiresAt: grantNow.addingTimeInterval(3_600),
            status: .active,
            createdAt: grantNow
        )
        let grantRequest = AgentAccessGrantRequest(
            callerID: "agent_synthetic_001",
            grantID: "grt_synthetic_001",
            purpose: "autonomous_memory",
            space: "space_personal",
            dataType: "event",
            operation: "create_event"
        )
        try! grant.authorize(grantRequest, at: grantNow.addingTimeInterval(1))
        let expandedGrantRequest = AgentAccessGrantRequest(
            callerID: grantRequest.callerID,
            grantID: grantRequest.grantID,
            purpose: grantRequest.purpose,
            space: "space_other",
            dataType: grantRequest.dataType,
            operation: grantRequest.operation
        )
        precondition((try? grant.authorize(expandedGrantRequest, at: grantNow.addingTimeInterval(1))) == nil)

        let policy = AgentAccessGrantPolicy.default(
            createdAt: grantNow,
            expiresAt: grantNow.addingTimeInterval(3_600)
        )
        let policyGrant = policy.bind(callerID: "agent_synthetic_001", grantID: "grt_from_host_001")
        try! policyGrant.authorize(
            AgentAccessGrantRequest(
                callerID: grantRequest.callerID,
                grantID: "grt_from_host_001",
                purpose: grantRequest.purpose,
                space: grantRequest.space,
                dataType: grantRequest.dataType,
                operation: grantRequest.operation
            ),
            at: grantNow.addingTimeInterval(1)
        )

        let simulatedConnector = SimulatedAgentExperienceConnector()
        let simulatedCandidate = try! await simulatedConnector.resolve(method: .accountDevice)
        let simulatedConnection = try! await simulatedConnector.connect(candidate: simulatedCandidate)
        precondition(simulatedConnector.simulated && simulatedCandidate.simulated)
        precondition(simulatedConnection.simulated && simulatedConnection.id == simulatedCandidate.id)

        let realConnector = BonjourAgentExperienceConnector()
        do {
            _ = try await realConnector.connect(candidate: discoveredCandidate)
            preconditionFailure("Bonjour discovery candidate was promoted without authorization")
        } catch let error as AgentExperienceConnectorError {
            precondition(error == .authorizationRequired)
        } catch {
            preconditionFailure("unexpected real connector error: \(error)")
        }

        let agentDefaultsSuite = "AmemeSharedSmoke-AgentExperience-\(UUID().uuidString)"
        let agentDefaults = UserDefaults(suiteName: agentDefaultsSuite)!
        defer { agentDefaults.removePersistentDomain(forName: agentDefaultsSuite) }
        let connectionDate = Date(timeIntervalSince1970: 1_800_000_000)
        let agentStore = AgentExperienceStore(
            defaults: agentDefaults,
            now: { connectionDate }
        )
        for method in AgentConnectionMethod.allCases {
            let connection = AgentExperienceConnection.simulatedDemo(
                method: method,
                connectedAt: connectionDate
            )
            try! agentStore.save(connection)
            precondition(try! agentStore.load() == connection, "agent experience state did not round-trip")
        }
        let expiredStore = AgentExperienceStore(
            defaults: agentDefaults,
            now: { connectionDate.addingTimeInterval(AgentExperienceConnection.defaultLifetime + 1) }
        )
        precondition(try! expiredStore.load() == nil, "expired agent experience state remained connected")
        precondition(
            agentDefaults.data(forKey: AgentExperienceStore.userDefaultsKey) == nil,
            "expired agent experience state was not cleared"
        )

        let channelSecret = Data("synthetic-pairing-secret-32-bytes-minimum-value".utf8)
        let channelPairing = AgentLocalNodePairingMaterial(
            endpointRef: "endpoint-ref:synthetic/android",
            credentialRef: "credential-ref:env/SYNTHETIC_SECRET",
            expectedDeviceID: "device_synthetic_001",
            sessionBindingRef: "session-binding-ref:synthetic/session",
            pairingID: "pair_synthetic_001",
            host: "127.0.0.1",
            port: 44_321,
            tlsCertificateSHA256: "sha256_" + String(repeating: "a", count: 64)
        )
        precondition(try! AgentLocalNodePairingMaterial.parse(jsonData: channelPairing.canonicalData()) == channelPairing, "iOS pairing material did not round-trip through the bounded wire parser")
        let duplicatePairing = Data("{\"channel_protocol\":\"duplicate\",\(String(decoding: try! channelPairing.canonicalData(), as: UTF8.self).dropFirst())".utf8)
        precondition((try? AgentLocalNodePairingMaterial.parse(jsonData: duplicatePairing)) == nil, "iOS pairing parser accepted duplicate JSON keys")
        let envelopeNow = Date(timeIntervalSince1970: 1_800_000_000)
        let envelope = try! AgentPairingEnvelope(
            pairing: channelPairing,
            bootstrapID: "boot_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            bootstrapSecret: Data(repeating: 0x42, count: 32),
            expiresAt: envelopeNow.addingTimeInterval(300),
            pairingExpiresAt: envelopeNow.addingTimeInterval(30 * 24 * 60 * 60)
        )
        let envelopePayload = try! envelope.encodedPayload()
        let envelopeGolden = "ameme-pairing-v2:eyJib290c3RyYXBfaWQiOiJib290X2JiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiIiwiYm9vdHN0cmFwX3NlY3JldCI6IlFrSkNRa0pDUWtKQ1FrSkNRa0pDUWtKQ1FrSkNRa0pDUWtKQ1FrSkNRa0kiLCJlbnZlbG9wZV92ZXJzaW9uIjoyLCJleHBpcmVzX2F0X21zIjoiMTgwMDAwMDMwMDAwMCIsInBhaXJpbmciOnsiY2hhbm5lbF9wcm90b2NvbCI6ImFtZW1lLmFnZW50LWxvY2FsLW5vZGUuY2hhbm5lbC52MSIsImNyZWRlbnRpYWxfcmVmIjoiY3JlZGVudGlhbC1yZWY6ZW52L1NZTlRIRVRJQ19TRUNSRVQiLCJlbmRwb2ludF9yZWYiOiJlbmRwb2ludC1yZWY6c3ludGhldGljL2FuZHJvaWQiLCJleHBlY3RlZF9kZXZpY2VfaWQiOiJkZXZpY2Vfc3ludGhldGljXzAwMSIsImhvc3QiOiIxMjcuMC4wLjEiLCJwYWlyaW5nX2lkIjoicGFpcl9zeW50aGV0aWNfMDAxIiwicG9ydCI6NDQzMjEsInNlc3Npb25fYmluZGluZ19yZWYiOiJzZXNzaW9uLWJpbmRpbmctcmVmOnN5bnRoZXRpYy9zZXNzaW9uIiwidGxzX2NlcnRpZmljYXRlX3NoYTI1NiI6InNoYTI1Nl9hYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhIn0sInBhaXJpbmdfZXhwaXJlc19hdF9tcyI6IjE4MDI1OTIwMDAwMDAifQ"
        precondition(envelopePayload == envelopeGolden, "iOS pairing envelope diverged from the Android golden")
        let parsedEnvelope = try! AgentPairingEnvelope.parse(envelopePayload, now: envelopeNow)
        precondition(
            parsedEnvelope.pairing == channelPairing
                && parsedEnvelope.bootstrapID == envelope.bootstrapID
                && parsedEnvelope.bootstrapSecret == envelope.bootstrapSecret,
            "iOS pairing envelope did not round-trip"
        )
        precondition((try? AgentPairingEnvelope.parse(envelopePayload, now: envelopeNow.addingTimeInterval(301))) == nil, "iOS pairing envelope accepted an expired secret")
        let oversizedEnvelope = try! AgentPairingEnvelope(
            pairing: channelPairing,
            bootstrapID: envelope.bootstrapID,
            bootstrapSecret: envelope.bootstrapSecret,
            expiresAt: envelopeNow.addingTimeInterval(601),
            pairingExpiresAt: envelope.pairingExpiresAt
        ).encodedPayload()
        precondition((try? AgentPairingEnvelope.parse(oversizedEnvelope, now: envelopeNow)) == nil, "iOS pairing envelope accepted an oversized lifetime")
        precondition((try? AgentPairingEnvelope.parse(String(envelopePayload.dropFirst(6)), now: envelopeNow)) == nil, "iOS pairing envelope accepted the wrong prefix")
        var envelopeBase64 = String(envelopePayload.dropFirst(AgentPairingEnvelope.prefix.count))
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        if envelopeBase64.count % 4 != 0 {
            envelopeBase64 += String(repeating: "=", count: 4 - envelopeBase64.count % 4)
        }
        let envelopeJSON = Data(base64Encoded: envelopeBase64)!
        func rawEnvelope(_ json: String) -> String {
            AgentPairingEnvelope.prefix + Data(json.utf8).base64EncodedString()
                .replacingOccurrences(of: "+", with: "-")
                .replacingOccurrences(of: "/", with: "_")
                .replacingOccurrences(of: "=", with: "")
        }
        let envelopeJSONString = String(decoding: envelopeJSON, as: UTF8.self)
        precondition((try? AgentPairingEnvelope.parse(rawEnvelope(" " + envelopeJSONString), now: envelopeNow)) == nil, "iOS pairing envelope accepted non-canonical JSON")
        precondition((try? AgentPairingEnvelope.parse(rawEnvelope(envelopeJSONString.replacingOccurrences(of: "{", with: "{\"envelope_version\":1,", options: [], range: envelopeJSONString.startIndex..<envelopeJSONString.index(after: envelopeJSONString.startIndex))), now: envelopeNow)) == nil, "iOS pairing envelope accepted duplicate fields")
        let bootstrapPrivateKey = P256.Signing.PrivateKey()
        let bootstrapHello = try! AgentPairingBootstrapV2Codec.buildClientHello(
            envelope: envelope,
            clientPrivateKey: bootstrapPrivateKey,
            clientNonce: "nonce_" + String(repeating: "7", count: 64)
        )
        let verifiedBootstrapHello = try! AgentPairingBootstrapV2Codec.verifyClientHello(
            bootstrapHello.line,
            envelope: envelope
        )
        precondition(
            verifiedBootstrapHello.clientKeyThumbprint ==
                AgentLocalNodeChannelCodec.digest(
                    bootstrapPrivateKey.publicKey.x963Representation
                ),
            "iOS bootstrap did not bind possession of the P-256 client key"
        )
        var tamperedBootstrapHello = bootstrapHello.line
        tamperedBootstrapHello[tamperedBootstrapHello.index(before: tamperedBootstrapHello.endIndex)] ^= 1
        precondition(
            (try? AgentPairingBootstrapV2Codec.verifyClientHello(
                tamperedBootstrapHello,
                envelope: envelope
            )) == nil,
            "iOS bootstrap accepted a tampered signed hello"
        )
        let credentialStore = AgentPairingCredentialStore(
            service: "com.ameme.ios.smoke.agent-pairing.\(UUID().uuidString)",
            account: "current"
        )
        try! credentialStore.clearAndVerify()
        let bootstrapThumbprint = AgentLocalNodeChannelCodec.digest(
            bootstrapPrivateKey.publicKey.x963Representation
        )
        try! credentialStore.savePending(
            envelope: envelope,
            clientPrivateKeyRaw: bootstrapPrivateKey.rawRepresentation,
            clientKeyThumbprint: bootstrapThumbprint
        )
        let pendingBootstrap = try! credentialStore.loadPending(
            pairingID: envelope.pairing.pairingID,
            now: Date()
        )
        let restartPendingBootstrap = try! credentialStore.loadPending(
            now: Date()
        )
        precondition(
            pendingBootstrap?.envelope == envelope &&
                pendingBootstrap?.clientKeyThumbprint == bootstrapThumbprint &&
                restartPendingBootstrap == pendingBootstrap,
            "iOS device-only credential store did not restore pending bootstrap state"
        )
        let storedCredential = AgentPairingIssuedCredential(
            pairing: envelope.pairing,
            bootstrapID: envelope.bootstrapID,
            credentialID: "cred_qr_" + String(repeating: "d", count: 32),
            channelSecret: channelSecret,
            clientKeyThumbprint: bootstrapThumbprint,
            expiresAt: envelope.pairingExpiresAt
        )
        try! credentialStore.saveActive(
            issued: storedCredential,
            clientPrivateKeyRaw: bootstrapPrivateKey.rawRepresentation
        )
        let restoredCredential = try! credentialStore.loadActive(now: Date())
        precondition(
            restoredCredential?.credentialID == storedCredential.credentialID &&
                restoredCredential?.channelSecret == storedCredential.channelSecret &&
                restoredCredential?.clientKeyThumbprint == bootstrapThumbprint,
            "iOS device-only credential store did not atomically activate the issued credential"
        )
        precondition(
            (try! credentialStore.loadActive(
                now: envelope.pairingExpiresAt.addingTimeInterval(1)
            )) == nil,
            "iOS device-only credential store retained an expired credential"
        )
        try! credentialStore.clearAndVerify()
        let channelClientLine = try! AgentLocalNodeChannelCodec.buildClientHello(
            pairing: channelPairing,
            secret: channelSecret,
            clientNonce: "nonce_" + String(repeating: "1", count: 64)
        )
        precondition((try? AgentLocalNodeChannelCodec.makeClientHelloView(
            Data("{\"a\":1,\"a\":2}".utf8),
            pairing: channelPairing,
            secret: channelSecret
        )) == nil, "iOS channel parser accepted duplicate JSON keys")
        let channelClient = try! AgentLocalNodeChannelCodec.makeClientHelloView(
            channelClientLine,
            pairing: channelPairing,
            secret: channelSecret
        )
        let channelServerLine = Data("""
        {"channel_protocol":"ameme.agent-local-node.channel.v1","client_nonce":"nonce_1111111111111111111111111111111111111111111111111111111111111111","device_id":"device_synthetic_001","message_type":"server_hello","pairing_id":"pair_synthetic_001","proof":"hmac_4559f4cdb682ecb378e23efe2bb709defff24b591e345d4306ebd0f3d4f2bb31","sequence":0,"server_nonce":"nonce_2222222222222222222222222222222222222222222222222222222222222222","session_binding_ref":"session-binding-ref:synthetic/session","session_id":"sess_synthetic_001","supported_operations":["create_event"],"tls_certificate_sha256":"sha256_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
        """.trimmingCharacters(in: .whitespacesAndNewlines).utf8)
        let channelServer = try! AgentLocalNodeChannelCodec.verifyServerHello(
            channelServerLine,
            clientHello: channelClient,
            pairing: channelPairing,
            secret: channelSecret
        )
        let sessionKey = try! AgentLocalNodeChannelCodec.deriveSessionKey(
            clientHello: channelClient,
            serverHello: channelServer,
            secret: channelSecret
        )
        precondition(sessionKey == Data(hex: "f89a4dac0778e42dfff501dc0b4fdc9cac1a10e5758be1819408a34e0245d5f6"), "iOS session key diverged from Android/Python golden")
        let applicationRequest = Data("""
        {"control":{"caller_id":"agent_synthetic","grant_id":"grant_synthetic","idempotency_slot":"idem_79185eefaaf7ad016a965b118d7a40bad51a79f01e4eebb323c2b1b089328f13","memory_types":["event"],"operation":"create_event","payload_digest":"sha256_81d4aac0b01c90e3ea84ce7a5c912ed26adb797ffc34e70ffa0b6641b3015d48","purpose":"autonomous_memory","spaces":["space_work"]},"payload":{"content":"Synthetic milestone was verified by a tool.","data_class":"structured","event_time":"2026-07-14T04:00:00Z","event_type":"result","evidence_state":"observed","fact_status":"confirmed","memory_type":"event","now":"2026-07-14T04:00:00Z","sensitivity":"personal","space":"space_work"},"protocol_version":"ameme.agent-local-node.v1","request_id":"req_create_event"}
        """.trimmingCharacters(in: .whitespacesAndNewlines).utf8)
        let requestResult = try! AgentLocalNodeChannelCodec.buildRequestFrame(
            applicationLine: applicationRequest,
            sessionKey: sessionKey,
            sessionID: channelServer.sessionID,
            sequence: 1,
            nonce: "nonce_" + String(repeating: "4", count: 64)
        )
        let responseLine = Data("""
        {"application_b64":"eyJlcnJvciI6bnVsbCwicHJvdG9jb2xfdmVyc2lvbiI6ImFtZW1lLmFnZW50LWxvY2FsLW5vZGUudjEiLCJyZXF1ZXN0X2lkIjoicmVxX2NyZWF0ZV9ldmVudCIsInJlc3VsdCI6eyJldmVudF9pZCI6ImV2dF9zeW50aGV0aWNfMDAxIiwib2JqZWN0X3R5cGUiOiJldmVudCIsInJldmlzaW9uIjoxfSwicmVzdWx0X2RpZ2VzdCI6InNoYTI1Nl9iNDViNjVhZmY5OTFmNmZiNTIzYjA1NGI5ZTczODI3MTBiNDUwYWM4NDFjZjE2OTJiN2Y1MGFjZGJhNWM1MDFjIiwic3RhdHVzIjoib2sifQ==","application_digest":"sha256_9dc47be5cdc975fedb916b4e2a09581fa05db5dc1fa9ef27dd35d55391258be6","application_protocol":"ameme.agent-local-node.v1","channel_protocol":"ameme.agent-local-node.channel.v1","idempotency_ref":"idem_79185eefaaf7ad016a965b118d7a40bad51a79f01e4eebb323c2b1b089328f13","message_type":"response","nonce":"nonce_5555555555555555555555555555555555555555555555555555555555555555","proof":"hmac_343f9287c87f2297eb7271b6145858fdcea9f071e51eadb05f93c0c02042a97e","request_nonce":"nonce_4444444444444444444444444444444444444444444444444444444444444444","sequence":1,"session_id":"sess_synthetic_001"}
        """.trimmingCharacters(in: .whitespacesAndNewlines).utf8)
        var seenResponseNonces = Set<String>()
        let parsedResponse = try! AgentLocalNodeChannelCodec.parseResponseFrame(
            responseLine,
            sessionKey: sessionKey,
            requestFrame: requestResult.frame,
            seenNonces: &seenResponseNonces
        )
        precondition(parsedResponse.applicationLine.starts(with: Data("{".utf8)), "iOS response frame did not expose the canonical application response")
        precondition(seenResponseNonces.count == 1, "iOS response replay ledger did not advance")
        let parsedCreate = try! AgentLocalNodeChannelCodec.parseCreateEventResponse(
            parsedResponse.applicationLine,
            expectedRequestID: "req_create_event"
        )
        precondition(parsedCreate.eventID == "evt_synthetic_001" && parsedCreate.revision == 1, "iOS typed create result parser diverged from the golden")

        let transportGrant = AgentAccessGrant(
            grantID: "grant_synthetic",
            ownerID: "owner_synthetic",
            callerID: "agent_synthetic",
            purposes: ["autonomous_memory"],
            spaces: ["space_work"],
            dataTypes: ["event", "revision"],
            notBefore: grantNow,
            expiresAt: grantNow.addingTimeInterval(3_600),
            status: .active,
            createdAt: grantNow
        )
        let generatedApplication = try! AgentLocalNodeChannelCodec.buildCreateEventRequest(
            draft: AgentLocalNodeCreateEventDraft(
                requestID: "req_ios_create_event",
                idempotencyKey: "ios-synthetic-idempotency",
                content: "来自 iOS 的授权结构化记录",
                eventType: "activity",
                evidenceState: "user_asserted",
                factStatus: "user_asserted",
                sensitivity: "personal",
                now: "2027-01-15T04:00:00Z"
            ),
            grant: transportGrant,
            authorizationDate: grantNow.addingTimeInterval(1)
        )
        let generatedObject = try! JSONSerialization.jsonObject(with: generatedApplication) as! [String: Any]
        precondition(generatedObject["protocol_version"] as? String == AgentLocalNodeChannelCodec.applicationProtocol, "iOS application request protocol version drifted")
        precondition((generatedObject["control"] as? [String: Any])?["grant_id"] as? String == transportGrant.grantID, "iOS application request lost Grant binding")
        precondition((generatedObject["payload"] as? [String: Any])?["data_class"] as? String == "structured", "iOS application request lost structured data class")

        let generatedRevision = try! AgentLocalNodeChannelCodec.buildAppendRevisionRequest(
            draft: AgentLocalNodeAppendRevisionDraft(
                requestID: "req_ios_append_revision",
                idempotencyKey: "ios-revision-idempotency",
                eventID: "evt_synthetic_001",
                space: "space_work",
                content: "来自 iOS 的精确修订",
                evidenceState: "user_asserted",
                factStatus: "user_asserted",
                now: "2027-01-15T04:00:00Z"
            ),
            grant: transportGrant,
            authorizationDate: grantNow.addingTimeInterval(1)
        )
        let generatedRevisionObject = try! JSONSerialization.jsonObject(with: generatedRevision) as! [String: Any]
        precondition(
            (generatedRevisionObject["control"] as? [String: Any])?["operation"] as? String == "append_revision"
                && (generatedRevisionObject["control"] as? [String: Any])?["memory_types"] as? [String] == ["revision"],
            "iOS append_revision request did not preserve minimal revision scope"
        )
        let revisionUndoToken = AgentLocalNodeChannelCodec.deriveIdempotencySlot(
            rawKey: "ios-revision-idempotency",
            operation: AgentLocalNodeChannelCodec.operationAppendRevision
        )
        let generatedUndo = try! AgentLocalNodeChannelCodec.buildUndoCaptureRequest(
            draft: AgentLocalNodeUndoCaptureDraft(
                requestID: "req_ios_undo_revision",
                idempotencyKey: "ios-undo-idempotency",
                undoToken: revisionUndoToken,
                space: "space_work",
                memoryType: "revision",
                now: "2027-01-15T04:00:01Z"
            ),
            grant: transportGrant,
            authorizationDate: grantNow.addingTimeInterval(2)
        )
        let generatedUndoObject = try! JSONSerialization.jsonObject(with: generatedUndo) as! [String: Any]
        precondition(
            (generatedUndoObject["payload"] as? [String: Any])?["undo_token"] as? String == revisionUndoToken,
            "iOS undo_capture request lost the exact opaque token"
        )
        let generatedRead = try! AgentLocalNodeChannelCodec.buildVisibleEventsRequest(
            draft: AgentLocalNodeVisibleEventsDraft(
                requestID: "req_ios_visible_events",
                idempotencyKey: "ios-visible-idempotency",
                spaces: ["space_work"],
                query: "synthetic",
                limit: 20,
                startAt: "2027-01-01T00:00:00Z",
                endAt: "2027-01-31T23:59:59Z"
            ),
            grant: transportGrant,
            authorizationDate: grantNow.addingTimeInterval(3)
        )
        let generatedReadObject = try! JSONSerialization.jsonObject(with: generatedRead) as! [String: Any]
        precondition(
            (generatedReadObject["control"] as? [String: Any])?["operation"] as? String == "visible_events"
                && (generatedReadObject["payload"] as? [String: Any])?["allow_high_risk"] as? Bool == false,
            "iOS visible_events request did not preserve bounded risk scope"
        )

        func applicationResponse(requestID: String, result: [String: Any]) -> Data {
            let resultData = try! AgentLocalNodeChannelCodec.canonicalJSONData(result)
            return try! AgentLocalNodeChannelCodec.canonicalJSONData([
                "protocol_version": AgentLocalNodeChannelCodec.applicationProtocol,
                "request_id": requestID,
                "status": "ok",
                "result": result,
                "result_digest": AgentLocalNodeChannelCodec.digest(resultData),
                "error": NSNull(),
            ])
        }
        let appendResult = try! AgentLocalNodeChannelCodec.parseAppendRevisionResponse(
            applicationResponse(
                requestID: "req_ios_append_revision",
                result: [
                    "event_revision_id": "rev_synthetic_002",
                    "object_type": "revision",
                    "revision": 2,
                    "target_event_id": "evt_synthetic_001",
                ]
            ),
            expectedRequestID: "req_ios_append_revision"
        )
        precondition(
            appendResult.eventRevisionID == "rev_synthetic_002"
                && appendResult.targetEventID == "evt_synthetic_001",
            "iOS typed append result lost exact identities"
        )
        let undoResult = try! AgentLocalNodeChannelCodec.parseUndoCaptureResponse(
            applicationResponse(
                requestID: "req_ios_undo_revision",
                result: [
                    "state": "undone",
                    "target_event_id": "evt_synthetic_001",
                    "undone_object_type": "revision",
                    "undone_object_id": "rev_synthetic_002",
                    "activity_visible": true,
                    "compensation_revision_id": "rev_synthetic_003",
                ]
            ),
            expectedRequestID: "req_ios_undo_revision"
        )
        precondition(
            undoResult.undoneObjectID == "rev_synthetic_002"
                && undoResult.compensationRevisionID == "rev_synthetic_003",
            "iOS typed undo result lost compensation identity"
        )
        let visibleResult = try! AgentLocalNodeChannelCodec.parseVisibleEventsResponse(
            applicationResponse(
                requestID: "req_ios_visible_events",
                result: [
                    "events": [[
                        "event_id": "evt_synthetic_001",
                        "space_id": "space_work",
                        "memory_type": "event",
                        "revision": 3,
                        "event_type": "activity",
                        "title": "Synthetic title",
                        "description": "Synthetic bounded description",
                        "fact_status": "confirmed",
                        "evidence_state": "observed",
                        "sensitivity": "personal",
                        "data_class": "structured",
                        "content_truncated": false,
                    ]],
                    "risk_filtered": true,
                ]
            ),
            expectedRequestID: "req_ios_visible_events"
        )
        precondition(
            visibleResult.events.map(\.eventID) == ["evt_synthetic_001"]
                && visibleResult.riskFiltered,
            "iOS typed visible_events result lost bounded projection metadata"
        )

        let remoteErrorLine = try! AgentLocalNodeChannelCodec.canonicalJSONData([
            "protocol_version": AgentLocalNodeChannelCodec.applicationProtocol,
            "request_id": "req_ios_visible_events",
            "status": "error",
            "result": NSNull(),
            "result_digest": NSNull(),
            "error": ["code": "NOT_VISIBLE", "retryable": false],
        ])
        do {
            _ = try AgentLocalNodeChannelCodec.parseVisibleEventsResponse(
                remoteErrorLine,
                expectedRequestID: "req_ios_visible_events"
            )
            preconditionFailure("iOS response parser accepted NOT_VISIBLE as success")
        } catch let error as AgentLocalNodeRemoteError {
            precondition(error.code == .notVisible && !error.retryable, "iOS remote error mapping drifted")
        } catch {
            preconditionFailure("iOS response parser lost the content-free remote error")
        }
        let wrongDigestLine = try! AgentLocalNodeChannelCodec.canonicalJSONData([
            "protocol_version": AgentLocalNodeChannelCodec.applicationProtocol,
            "request_id": "req_ios_append_revision",
            "status": "ok",
            "result": [
                "event_revision_id": "rev_synthetic_002",
                "object_type": "revision",
                "revision": 2,
                "target_event_id": "evt_synthetic_001",
            ],
            "result_digest": "sha256_" + String(repeating: "0", count: 64),
            "error": NSNull(),
        ])
        precondition(
            (try? AgentLocalNodeChannelCodec.parseAppendRevisionResponse(
                wrongDigestLine,
                expectedRequestID: "req_ios_append_revision"
            )) == nil,
            "iOS response parser accepted a mismatched result digest"
        )
        let eventOnlyGrant = AgentAccessGrantPolicy.default(
            createdAt: grantNow,
            expiresAt: grantNow.addingTimeInterval(3_600)
        ).bind(callerID: "agent_synthetic", grantID: "grant_event_only")
        precondition(
            (try? AgentLocalNodeChannelCodec.buildAppendRevisionRequest(
                draft: AgentLocalNodeAppendRevisionDraft(
                    requestID: "req_ios_scope_expansion",
                    idempotencyKey: "ios-scope-expansion",
                    eventID: "evt_synthetic_001",
                    space: "space_personal",
                    content: "不应越权",
                    evidenceState: "user_asserted",
                    factStatus: "user_asserted",
                    now: "2027-01-15T04:00:00Z"
                ),
                grant: eventOnlyGrant,
                authorizationDate: grantNow.addingTimeInterval(1)
            )) == nil,
            "iOS event-only ordinary-user Grant unexpectedly expanded to revision"
        )
        let expiredTransportGrant = AgentAccessGrant(
            grantID: "grant_expired",
            ownerID: "owner_synthetic",
            callerID: "agent_synthetic",
            purposes: ["autonomous_memory"],
            spaces: ["space_work"],
            dataTypes: ["event"],
            notBefore: grantNow,
            expiresAt: grantNow.addingTimeInterval(1),
            status: .active,
            createdAt: grantNow
        )
        precondition((try? AgentLocalNodeChannelCodec.buildCreateEventRequest(
            draft: AgentLocalNodeCreateEventDraft(
                requestID: "req_ios_expired",
                idempotencyKey: "ios-expired-idempotency",
                content: "不应写入",
                eventType: "activity",
                evidenceState: "user_asserted",
                factStatus: "user_asserted",
                sensitivity: "personal",
                now: "2027-01-15T04:00:00Z"
            ),
            grant: expiredTransportGrant,
            authorizationDate: grantNow.addingTimeInterval(2)
        )) == nil, "expired iOS Grant unexpectedly produced a write request")

        let spaceDeletionRoot = fileManager.temporaryDirectory
            .appendingPathComponent("AmemeSharedSmoke-SpaceDeletion-\(UUID().uuidString)", isDirectory: true)
        let spaceDeletionBackup = fileManager.temporaryDirectory
            .appendingPathComponent("AmemeSharedSmoke-SpaceDeletionBackup-\(UUID().uuidString)", isDirectory: true)
        let spaceDeletionPendingExportRoot = fileManager.temporaryDirectory
            .appendingPathComponent("AmemeSharedSmoke-SpaceDeletionExport-\(UUID().uuidString)", isDirectory: true)
        let spaceDeletionIncomingShareRoot = fileManager.temporaryDirectory
            .appendingPathComponent("AmemeSharedSmoke-SpaceDeletionShare-\(UUID().uuidString)", isDirectory: true)
        let spaceDeletionDefaultsName = "AmemeSharedSmoke-SpaceDeletion-\(UUID().uuidString)"
        let spaceDeletionDefaults = UserDefaults(suiteName: spaceDeletionDefaultsName)!
        defer {
            try? fileManager.removeItem(at: spaceDeletionRoot)
            try? fileManager.removeItem(at: spaceDeletionBackup)
            try? fileManager.removeItem(at: spaceDeletionPendingExportRoot)
            try? fileManager.removeItem(at: spaceDeletionIncomingShareRoot)
            spaceDeletionDefaults.removePersistentDomain(forName: spaceDeletionDefaultsName)
        }
        let spaceDeletionStore = LocalMemoryStore(
            fileManager: fileManager,
            keyStore: keyStore,
            rootDirectory: spaceDeletionRoot
        )
        let spaceOwnedEvent = spaceDeletionStore.addMedia(
            Data("space-delete-owned-ciphertext".utf8),
            kind: .voice,
            fileExtension: "m4a"
        )
        let spaceOwnedLocator = spaceOwnedEvent.sourceLocator!
        precondition(
            spaceDeletionStore.addPhotoReference("space-delete-provider-original") != nil,
            "space deletion external-source fixture failed"
        )
        _ = try! spaceDeletionStore.createLocalRecoveryBackup(
            at: spaceDeletionBackup,
            createdAt: Date(timeIntervalSince1970: 1_759_100_400)
        )
        let spaceDeletionPendingExportStore = PendingExportStore(
            fileManager: fileManager,
            keyStore: keyStore,
            rootDirectory: spaceDeletionPendingExportRoot
        )
        try! spaceDeletionPendingExportStore.save(Data("pending export to clear".utf8))
        try! Data("orphaned pending export".utf8).write(
            to: spaceDeletionPendingExportRoot.appendingPathComponent(".orphan-export.tmp")
        )
        let spaceDeletionIncomingShareStore = IncomingShareHandoffStore(
            rootDirectory: spaceDeletionIncomingShareRoot,
            fileManager: fileManager
        )
        _ = try! spaceDeletionIncomingShareStore.writeText("pending share to clear")
        try! Data("orphaned incoming share".utf8).write(
            to: spaceDeletionIncomingShareRoot.appendingPathComponent(".orphan-share.tmp")
        )
        let spaceDeletionAgentStore = AgentExperienceStore(
            defaults: spaceDeletionDefaults,
            now: { Date(timeIntervalSince1970: 1_759_100_450) }
        )
        try! spaceDeletionAgentStore.save(.simulatedDemo(
            method: .qrCode,
            connectedAt: Date(timeIntervalSince1970: 1_759_100_450)
        ))
        var activeAgentTransportClosed = false
        let spaceDeletionCoordinator = LocalSpaceDeletionConvergenceCoordinator(
            closeActiveAgentTransport: {
                activeAgentTransportClosed = true
            },
            deleteLocalSpace: { requestedAt in
                spaceDeletionStore.deleteLocalSpace(requestedAt: requestedAt)
            },
            clearStoredConnectionMetadata: {
                try spaceDeletionAgentStore.clearAndVerify()
            },
            freezePendingExportResurrection: {
                try spaceDeletionPendingExportStore.freezeForDeletedSpaceAndClear()
            },
            clearPendingExportSnapshot: {
                try spaceDeletionPendingExportStore.clear()
            },
            freezeIncomingShareResurrection: {
                try spaceDeletionIncomingShareStore.freezeForDeletedSpaceAndClear()
            },
            clearIncomingShareHandoffs: {
                try spaceDeletionIncomingShareStore.clearPendingHandoffs()
            }
        )
        let spaceDeletionConvergence = await spaceDeletionCoordinator.delete(
            requestedAt: Date(timeIntervalSince1970: 1_759_100_500)
        )
        let spaceDeletionResult = spaceDeletionConvergence.spaceDeletion!
        precondition(
            spaceDeletionConvergence.status == .completedLocalOnly &&
                spaceDeletionConvergence.completedSteps ==
                    Set(LocalSpaceDeletionConvergenceStep.allCases) &&
                spaceDeletionConvergence.pendingRetrySteps.isEmpty &&
                spaceDeletionConvergence.localSpaceFrozen &&
                spaceDeletionConvergence.localAgentAccessClosed &&
                spaceDeletionConvergence.pendingUserPayloadsCleared &&
                spaceDeletionConvergence.pendingUserPayloadResurrectionFrozen &&
                activeAgentTransportClosed &&
                spaceDeletionResult.status == .completedLocalOnly &&
                spaceDeletionResult.affectedEventCount == 2 &&
                spaceDeletionResult.affectedSourceCount == 2 &&
                spaceDeletionResult.externalOriginalsRetained &&
                !spaceDeletionConvergence.accountDeletionClaim &&
                !spaceDeletionConvergence.peerDeletionProofClaim &&
                !spaceDeletionResult.accountDeletionClaim &&
                !spaceDeletionResult.peerDeletionProofClaim,
            "local-space deletion convergence overclaimed or left a local checkpoint pending"
        )
        precondition(
            !spaceDeletionPendingExportStore.exists &&
                spaceDeletionPendingExportStore.isFrozenForDeletedSpace &&
                (try! spaceDeletionPendingExportStore.load()) == nil &&
                spaceDeletionIncomingShareStore.isFrozenForDeletedSpace &&
                spaceDeletionIncomingShareStore.pendingIDs().isEmpty &&
                spaceDeletionDefaults.object(
                    forKey: AgentExperienceStore.userDefaultsKey
                ) == nil,
            "local-space deletion retained pending payload or connection metadata"
        )
        do {
            try spaceDeletionPendingExportStore.save(Data("must stay frozen".utf8))
            preconditionFailure("Pending export reopened after local-space deletion")
        } catch PendingExportStoreError.localSpaceDeleted {
            // Expected: the marker prevents post-delete export resurrection.
        } catch {
            preconditionFailure("post-delete export failed for the wrong reason: \(error)")
        }
        do {
            _ = try spaceDeletionIncomingShareStore.writeText("must stay frozen")
            preconditionFailure("Share Extension handoff reopened after local-space deletion")
        } catch IncomingSharePayloadError.localSpaceDeleted {
            // Expected: the App Group marker prevents post-delete handoff resurrection.
        } catch {
            preconditionFailure("post-delete handoff failed for the wrong reason: \(error)")
        }
        precondition(
            spaceDeletionStore.isLocalSpaceDeleted &&
                spaceDeletionStore.storageState == .deleted &&
                spaceDeletionStore.events.isEmpty &&
                spaceDeletionStore.coverageDays.isEmpty &&
                spaceDeletionStore.longTermMemories.isEmpty &&
                spaceDeletionStore.reuseAttempts.isEmpty,
            "local-space root watermark did not freeze readable projections"
        )
        precondition(
            !fileManager.fileExists(atPath: spaceOwnedLocator),
            "local-space deletion retained app-owned Raw ciphertext"
        )
        precondition(
            spaceDeletionStore.addText("space root must freeze post-delete writes") == nil,
            "local-space deletion did not freeze writes"
        )
        do {
            _ = try spaceDeletionStore.restoreLocalRecoveryCandidate(
                from: spaceDeletionBackup,
                to: fileManager.temporaryDirectory
                    .appendingPathComponent("AmemeSharedSmoke-StaleSpaceCandidate-\(UUID().uuidString)")
            )
            preconditionFailure("old recovery snapshot bypassed the local-space root watermark")
        } catch LocalBackupError.snapshotPredatesDeletion {
            // Expected: the live root watermark is authoritative over the old snapshot.
        } catch {
            preconditionFailure("old space snapshot failed for the wrong reason: \(error)")
        }
        let spaceDeletionReloaded = LocalMemoryStore(
            fileManager: fileManager,
            keyStore: keyStore,
            rootDirectory: spaceDeletionRoot
        )
        precondition(
            spaceDeletionReloaded.isLocalSpaceDeleted &&
                spaceDeletionReloaded.storageState == .deleted &&
                spaceDeletionReloaded.events.isEmpty &&
                spaceDeletionReloaded.addText("space root must survive reload") == nil &&
                spaceDeletionReloaded.deleteLocalSpace().status == .alreadyDeleted,
            "local-space deletion watermark did not survive encrypted reload"
        )

        print("AmemeSharedSmoke passed: encrypted local capture/reload, atomic batch import rollback, range/query surface, revision, summary, app-owned Raw-only/external-original boundary/source cascade watermark, encrypted Coverage persistence/explicit candidate acceptance/delete non-resurrection, exact-revision multi-source field evidence/recompute/reload, complete user-confirmation retention/partial-confirmation fail-closed/reload, explicit long-term Memory confirmation/revision invalidation/no resurrection, four bounded reuse journeys/content-free telemetry/revision revalidation, authenticated same-install recovery candidate/deletion-watermark/nonempty-target fail-closed/exact-confirmation activation, local-space root freeze/app-owned Raw cleanup/old-backup rejection/local Agent and pending-payload convergence, encrypted export recovery, structured agent experience state, QR v2 signed bootstrap/device-only pending-to-active credential/expiry cleanup, delete, demo isolation, opaque incoming-share handoff, and Android/Python Local Node channel golden")
    }
}

private extension Data {
    init(hex: String) {
        self.init((0..<hex.count / 2).map { index in
            let start = hex.index(hex.startIndex, offsetBy: index * 2)
            let end = hex.index(start, offsetBy: 2)
            return UInt8(hex[start..<end], radix: 16)!
        })
    }
}
