package com.ameme.android.data

import com.ameme.android.coverage.CoverageCompileResult
import com.ameme.android.coverage.EvidenceField
import com.ameme.android.domain.CaptureKind
import com.ameme.android.domain.Sensitivity
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** Lifecycle of a compiler candidate; none of these states makes it a Memory Event by itself. */
enum class CoverageCandidateLifecycle {
    Open,
    Consumed,
    Dismissed,
    Deleted,
}

/** A detached link records that its Event was tombstoned and must not be recreated implicitly. */
enum class CoverageEventLinkLifecycle {
    Active,
    Detached,
}

data class CoverageEventLink(
    val dayId: String,
    val spaceId: String,
    val candidateId: String,
    val eventId: String,
    val createdRevision: Int,
    val sourceObjectIds: Set<String>,
    val lifecycle: CoverageEventLinkLifecycle,
    val acceptedAt: Instant,
) {
    init {
        require(dayId.isNotBlank() && spaceId.isNotBlank() && candidateId.isNotBlank() && eventId.isNotBlank())
        require(createdRevision >= 1)
        require(sourceObjectIds.isNotEmpty() && sourceObjectIds.none(String::isBlank))
    }
}

data class PersistedCoverageDay(
    val compilation: CoverageCompileResult,
    val registryVersion: String,
    val compiledAt: Instant,
    val candidateStates: Map<String, CoverageCandidateLifecycle>,
) {
    init {
        require(registryVersion.isNotBlank()) { "registryVersion must not be blank" }
        val candidateIds = compilation.candidateEvents.map { it.candidateId }.toSet()
        require(candidateIds.size == compilation.candidateEvents.size) { "candidate ids must be unique" }
        require(candidateStates.keys.containsAll(candidateIds)) {
            "candidateStates must describe every compiled candidate"
        }
        require(candidateStates.filterKeys { it !in candidateIds }.values.none {
            it == CoverageCandidateLifecycle.Open
        }) { "a removed candidate cannot remain open" }
    }

    companion object {
        fun compiled(
            compilation: CoverageCompileResult,
            registryVersion: String,
            compiledAt: Instant,
        ) = PersistedCoverageDay(
            compilation = compilation,
            registryVersion = registryVersion,
            compiledAt = compiledAt,
            candidateStates = compilation.candidateEvents.associate {
                it.candidateId to CoverageCandidateLifecycle.Open
            },
        )
    }
}

enum class CoverageAcceptanceMode {
    PreserveEvidence,
    UserConfirmed,
}

/**
 * Explicit user confirmation input. Implementations derive the Event from the persisted candidate;
 * callers cannot inject an arbitrary MemoryEvent through this boundary.
 */
data class CoverageCandidateAcceptance(
    val dayId: String,
    val candidateId: String,
    val acceptedAt: Instant,
    val localDate: LocalDate,
    val time: LocalTime?,
    val detail: String,
    val sourceLabel: String,
    val captureKind: CaptureKind,
    val userWords: String? = null,
    val sensitivity: Sensitivity = Sensitivity.Personal,
    val importance: Int = 50,
    val mode: CoverageAcceptanceMode = CoverageAcceptanceMode.PreserveEvidence,
    /**
     * Explicit exact-revision field provenance. An empty map preserves legacy behavior; a
     * multi-source Event with no complete map remains fail-closed during source deletion.
     */
    val fieldSourceObjectIds: Map<EvidenceField, Set<String>> = emptyMap(),
) {
    init {
        require(dayId.isNotBlank() && candidateId.isNotBlank()) { "coverage identity must not be blank" }
        require(detail.isNotBlank() && detail.length <= 4_096) { "detail must be 1..4096 characters" }
        require(sourceLabel.isNotBlank() && sourceLabel.length <= 256) { "sourceLabel must be 1..256 characters" }
        require(userWords == null || userWords.length <= 16_384) { "userWords is too long" }
        require(importance in 0..100) { "importance must be in 0..100" }
        require(fieldSourceObjectIds.values.none(Set<String>::isEmpty)) {
            "field provenance cannot contain an empty source set"
        }
        require(fieldSourceObjectIds.values.flatten().none(String::isBlank)) {
            "field provenance contains a blank source id"
        }
    }
}

/**
 * A repository is bound to one space. Persisting or loading coverage never writes Events, FTS, or
 * DayLedger. Only [acceptCandidate] may atomically create an Event and its candidate link; a later
 * Event tombstone must detach that link rather than allow compiler replay to recreate the Event.
 */
interface CoverageRepository {
    fun persist(day: PersistedCoverageDay): PersistedCoverageDay

    fun load(localDate: LocalDate): PersistedCoverageDay?

    fun acceptCandidate(acceptance: CoverageCandidateAcceptance): CoverageEventLink

    fun link(dayId: String, candidateId: String): CoverageEventLink?

    fun dismissCandidate(dayId: String, candidateId: String, updatedAt: Instant): Boolean
}
