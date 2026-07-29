package com.ameme.android.data.local

import android.content.Context
import com.ameme.android.data.FakeMemoryRepository
import com.ameme.android.data.MemoryRepository
import com.ameme.android.data.AgentCaptureUndoResult
import com.ameme.android.data.AgentCaptureUndoTarget
import com.ameme.android.data.AgentRevisionAppendResult
import com.ameme.android.data.AgentVisibleEventsReadResult
import com.ameme.android.data.CoverageCandidateAcceptance
import com.ameme.android.data.CoverageEventLink
import com.ameme.android.data.CoverageRepository
import com.ameme.android.data.PersistedCoverageDay
import com.ameme.android.data.LongTermMemoryConfirmation
import com.ameme.android.data.LongTermMemoryProposal
import com.ameme.android.data.LongTermMemoryRecord
import com.ameme.android.data.LongTermMemoryRepository
import com.ameme.android.data.DeletionWatermark
import com.ameme.android.data.RecoveryBackupManifest
import com.ameme.android.data.RecoveryBackupRepository
import com.ameme.android.data.RecoveryCandidate
import com.ameme.android.data.ReuseContext
import com.ameme.android.data.ReuseOutcomeSubmission
import com.ameme.android.data.ReuseRepository
import com.ameme.android.data.ReuseRequest
import com.ameme.android.data.ReuseTelemetryAggregate
import com.ameme.android.data.ResolvedReuseContext
import com.ameme.android.data.LocalEventUserConfirmation
import com.ameme.android.data.LocalSourceObject
import com.ameme.android.data.SourceDeletionRepository
import com.ameme.android.data.SourceDeletionResult
import com.ameme.android.data.LocalSpaceDeletionRepository
import com.ameme.android.data.LocalSpaceDeletionResult
import com.ameme.android.domain.CaptureKind
import com.ameme.android.domain.DayGroup
import com.ameme.android.domain.DaySummarySnapshot
import com.ameme.android.domain.EvidenceState
import com.ameme.android.domain.EventType
import com.ameme.android.domain.FactStatus
import com.ameme.android.domain.MemoryEvent
import com.ameme.android.domain.MemoryPage
import com.ameme.android.domain.SourceCaptureRequest
import com.ameme.android.domain.SourceLocator
import com.ameme.android.domain.Sensitivity
import com.ameme.android.data.transport.AgentLocalNodeIdempotencyRegistry
import com.ameme.android.domain.PendingSourceLocatorRelease
import java.net.URI
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

class LocalMemoryRepository(
    private val database: LocalEventDatabase,
    private val clock: Clock = Clock.systemDefaultZone(),
) : MemoryRepository,
    CoverageRepository,
    LongTermMemoryRepository,
    RecoveryBackupRepository,
    ReuseRepository,
    SourceDeletionRepository,
    LocalSpaceDeletionRepository {
    override fun loadActiveEvents(): List<MemoryEvent> = database.readActive()

    override fun loadDaySummary(localDate: LocalDate): DaySummarySnapshot = database.readDaySummary(localDate)

    override fun beginDaySummary(localDate: LocalDate, expectedLedgerRevision: Int): DaySummarySnapshot =
        database.beginDaySummary(localDate, expectedLedgerRevision)

    override fun completeDaySummary(
        localDate: LocalDate,
        expectedLedgerRevision: Int,
        text: String,
        modelOrRuleVersion: String,
    ): DaySummarySnapshot = database.completeDaySummary(
        localDate,
        expectedLedgerRevision,
        text,
        modelOrRuleVersion,
    )

    override fun failDaySummary(localDate: LocalDate, expectedLedgerRevision: Int): DaySummarySnapshot =
        database.failDaySummary(localDate, expectedLedgerRevision)

    override fun capture(kind: CaptureKind, text: String): MemoryEvent {
        val userDescription = text.trim()
        require(kind == CaptureKind.Text) { "Only explicit text capture is available" }
        require(userDescription.isNotEmpty()) { "Text capture requires non-empty user input" }
        val event = MemoryEvent(
            id = "evt_${UUID.randomUUID()}",
            localDate = LocalDate.now(clock),
            time = LocalTime.now(clock).withSecond(0).withNano(0),
            title = userDescription.take(24),
            detail = "用户原话已先保存在加密本机节点；整理尚未完成。",
            factStatus = FactStatus.UserAsserted,
            sourceLabel = "用户文字",
            isLocalOnly = true,
            userWords = userDescription.ifEmpty { null },
            eventType = EventType.Experience,
            evidenceState = EvidenceState.UserAsserted,
            sensitivity = Sensitivity.Personal,
            importance = 50,
        )
        return database.insertCaptured(event)
    }

    override fun captureSource(request: SourceCaptureRequest): MemoryEvent {
        val event = eventFromSource(request)
        return database.insertCaptured(event, request)
    }

    override fun captureSources(requests: List<SourceCaptureRequest>): List<MemoryEvent> {
        require(requests.isNotEmpty()) { "Source capture batch must not be empty" }
        val entries = requests.map { request -> eventFromSource(request) to request }
        return database.insertCapturedSourceBatch(entries)
    }

    private fun eventFromSource(request: SourceCaptureRequest): MemoryEvent {
        require(request.title.isNotBlank()) { "Source capture title must not be blank" }
        require(request.detail.isNotBlank()) { "Source capture detail must not be blank" }
        request.locatorUri?.let { locator ->
            require(locator.length <= 4_096) { "Source locator is too long" }
            require(URI.create(locator).scheme == "content") { "Only content URI locators are accepted" }
        }
        request.mimeType?.let { require(it.length <= 128) { "MIME type is too long" } }
        request.sourceInstanceKey?.let { key ->
            require(key.isNotBlank() && key.length <= 256) { "Source instance key is invalid" }
        }
        return MemoryEvent(
            id = "evt_${UUID.randomUUID()}",
            localDate = request.localDate,
            time = request.time,
            title = request.title.trim().take(256),
            detail = request.detail.trim().take(4_096),
            factStatus = request.factStatus,
            sourceLabel = request.sourceKind.name,
            isLocalOnly = true,
            userWords = request.userWords?.trim()?.take(16_384)?.ifEmpty { null },
            eventType = request.eventType,
            evidenceState = request.evidenceState,
            sensitivity = request.sensitivity,
            importance = request.importance.coerceIn(0, 100),
        )
    }

    override fun search(query: String, date: LocalDate?): List<DayGroup> = database
        .readPage(query, date, cursor = null, pageSize = 100).events
        .groupBy(MemoryEvent::localDate)
        .toSortedMap(compareByDescending { it })
        .map { (groupDate, events) -> DayGroup(groupDate, events) }

    override fun searchPage(query: String, date: LocalDate?, cursor: String?, pageSize: Int): MemoryPage =
        database.readPage(query, date, cursor, pageSize)

    override fun searchPage(
        query: String,
        startDate: LocalDate?,
        endDate: LocalDate?,
        cursor: String?,
        pageSize: Int,
    ): MemoryPage = database.readPage(query, startDate, endDate, cursor, pageSize)

    override fun deleteEvent(eventId: String): Boolean = database.deleteEvent(eventId)

    override fun persist(day: PersistedCoverageDay): PersistedCoverageDay =
        database.persistCoverageDay(day)

    override fun load(localDate: LocalDate): PersistedCoverageDay? =
        database.readCoverageDay(localDate)

    override fun acceptCandidate(acceptance: CoverageCandidateAcceptance): CoverageEventLink =
        database.acceptCoverageCandidate(acceptance)

    override fun link(dayId: String, candidateId: String): CoverageEventLink? =
        database.readCoverageEventLink(dayId, candidateId)

    override fun dismissCandidate(
        dayId: String,
        candidateId: String,
        updatedAt: java.time.Instant,
    ): Boolean = database.dismissCoverageCandidate(dayId, candidateId, updatedAt)

    override fun proposeLongTermMemory(proposal: LongTermMemoryProposal): LongTermMemoryRecord =
        database.proposeLongTermMemory(proposal)

    override fun confirmLongTermMemory(confirmation: LongTermMemoryConfirmation): LongTermMemoryRecord =
        database.confirmLongTermMemory(confirmation)

    override fun longTermMemory(memoryId: String): LongTermMemoryRecord? =
        database.readLongTermMemory(memoryId)

    override fun visibleLongTermMemories(at: java.time.Instant): List<LongTermMemoryRecord> =
        database.readVisibleLongTermMemories(at)

    override fun buildReuseContext(request: ReuseRequest): ReuseContext =
        database.buildReuseContext(request)

    override fun revalidateReuseContext(context: ReuseContext, at: Instant): ReuseContext =
        database.revalidateReuseContext(context, at)

    override fun resolveReuseContext(context: ReuseContext, at: Instant): ResolvedReuseContext =
        database.resolveReuseContext(context, at)

    override fun recordReuseOutcome(submission: ReuseOutcomeSubmission): Boolean =
        database.recordReuseOutcome(submission)

    override fun reuseTelemetryAggregates(since: Instant): List<ReuseTelemetryAggregate> =
        database.reuseTelemetryAggregates(since)

    override fun helpfulReuseCount(since: Instant): Int =
        database.helpfulReuseCount(since)

    override fun deletionWatermarks(): List<DeletionWatermark> =
        database.deletionWatermarks()

    override fun sourceObjectsForEvent(eventId: String): List<LocalSourceObject> =
        database.sourceObjectsForEvent(eventId)

    override fun userConfirmationsForEvent(eventId: String): List<LocalEventUserConfirmation> =
        database.userConfirmationsForEvent(eventId)

    override fun deleteRawOnly(sourceObjectId: String, requestedAt: Instant): SourceDeletionResult =
        database.deleteRawOnly(sourceObjectId, requestedAt)

    override fun deleteSourceCascade(
        sourceObjectId: String,
        requestedAt: Instant,
    ): SourceDeletionResult =
        database.deleteSourceCascade(sourceObjectId, requestedAt)

    override fun isLocalSpaceDeleted(): Boolean =
        database.isLocalSpaceDeleted()

    override fun deleteLocalSpace(requestedAt: Instant): LocalSpaceDeletionResult =
        database.deleteLocalSpace(requestedAt)

    override fun createLocalRecoveryBackup(
        destinationDirectory: File,
        createdAt: Instant,
    ): RecoveryBackupManifest =
        database.createLocalRecoveryBackup(destinationDirectory, createdAt)

    override fun verifyLocalRecoveryBackup(backupDirectory: File): RecoveryBackupManifest =
        database.verifyLocalRecoveryBackup(backupDirectory)

    override fun restoreLocalRecoveryCandidate(
        backupDirectory: File,
        destinationDirectory: File,
    ): RecoveryCandidate =
        database.restoreLocalRecoveryCandidate(backupDirectory, destinationDirectory)

    override fun sourceLocator(eventId: String): SourceLocator? = database.sourceLocator(eventId)

    override fun pendingSourceLocatorReleases(): List<PendingSourceLocatorRelease> =
        database.pendingSourceLocatorReleases()

    override fun markSourceLocatorReleased(eventId: String): Boolean =
        database.markSourceLocatorReleased(eventId)

    override fun updateEvent(
        eventId: String,
        factStatus: FactStatus?,
        userWords: String?,
    ): MemoryEvent? = database.updateEvent(eventId, factStatus, userWords)

    override fun appendAgentRevision(
        eventId: String,
        content: String,
        evidenceState: EvidenceState,
        factStatus: FactStatus,
        allowedSensitivities: Set<Sensitivity>,
    ): AgentRevisionAppendResult? =
        database.appendAgentRevision(
            eventId,
            content,
            evidenceState,
            factStatus,
            allowedSensitivities,
        )

    override fun undoAgentCapture(
        target: AgentCaptureUndoTarget,
        allowedSensitivities: Set<Sensitivity>,
        undoneAt: Instant,
    ): AgentCaptureUndoResult? =
        database.undoAgentCapture(target, allowedSensitivities, undoneAt)

    override fun readAgentVisibleEvents(
        query: String,
        startAt: Instant?,
        endAt: Instant?,
        timeZone: ZoneId,
        allowedSensitivities: Set<Sensitivity>,
        allowHighRisk: Boolean,
        limit: Int,
    ): AgentVisibleEventsReadResult =
        database.readAgentVisibleEvents(
            query,
            startAt,
            endAt,
            timeZone,
            allowedSensitivities,
            allowHighRisk,
            limit,
        )

    override fun close() = database.close()

    internal fun durableAgentIdempotencyRegistry(): AgentLocalNodeIdempotencyRegistry =
        object : AgentLocalNodeIdempotencyRegistry {
            override fun resolveOrCapture(
                binding: com.ameme.android.data.transport.AgentLocalNodeIdempotencyBinding,
                payloadDigest: String,
                capture: () -> com.ameme.android.data.transport.AgentLocalNodeCaptureOutcome,
            ): com.ameme.android.data.transport.AgentLocalNodeIdempotencyResult =
                database.resolveAgentIdempotency(binding, payloadDigest, capture)

            override fun resolveOrUndo(
                binding: com.ameme.android.data.transport.AgentLocalNodeIdempotencyBinding,
                payloadDigest: String,
                undoToken: String,
                at: Instant,
                capture: (AgentCaptureUndoTarget) -> AgentCaptureUndoResult?,
            ): com.ameme.android.data.transport.AgentLocalNodeUndoIdempotencyResult =
                database.resolveAgentUndoIdempotency(
                    binding,
                    payloadDigest,
                    undoToken,
                    at,
                    capture,
                )
        }

    companion object {
        const val DATABASE_NAME = "ameme-local-events.db"

        fun open(
            context: Context,
            spaceId: String,
            keyProvider: DatabaseKeyProvider? = null,
            databaseFile: File = context.getDatabasePath(DATABASE_NAME),
            clock: Clock = Clock.systemDefaultZone(),
            seedSyntheticEvents: Boolean = false,
            enableFts: Boolean = true,
        ): LocalMemoryRepository {
            val provider = keyProvider ?: AndroidKeystoreDatabaseKeyProvider(context, databaseFile)
            LocalRecoveryActivationCoordinator.recoverInterruptedActivation(databaseFile, provider)
            val database = LocalEventDatabase.open(databaseFile, provider, spaceId, enableFts)
            if (seedSyntheticEvents) database.seedIfEmpty(FakeMemoryRepository(clock).seedEvents())
            return LocalMemoryRepository(database, clock)
        }
    }
}
