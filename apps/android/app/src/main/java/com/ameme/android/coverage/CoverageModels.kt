package com.ameme.android.coverage

import java.time.Instant
import java.time.LocalDate

/** Pure contract models for coverage planning; these are not persisted Memory objects. */
enum class ContextType {
    TimeSchedule, ActivityResult, ContentConsumption, ContentCreation, CommunicationRelationship,
    DecisionCommitment, PlaceMobility, BodyState, ConsumptionEntertainment, IntentFeeling;

    val wireValue get() = when (this) {
        TimeSchedule -> "time_schedule"; ActivityResult -> "activity_result"; ContentConsumption -> "content_consumption"
        ContentCreation -> "content_creation"; CommunicationRelationship -> "communication_relationship"; DecisionCommitment -> "decision_commitment"
        PlaceMobility -> "place_mobility"; BodyState -> "body_state"; ConsumptionEntertainment -> "consumption_entertainment"; IntentFeeling -> "intent_feeling"
    }
}

enum class EvidenceField(val wireValue: String) { Time("time"), Place("place"), People("people"), Action("action"), Result("result"), Intent("intent"), Emotion("emotion"), Relationship("relationship"), Description("description") }
enum class SourceState(val wireValue: String) { Available("available"), NotAuthorized("not_authorized"), Failed("failed"), Unavailable("unavailable") }
enum class CoverageFactStatus(val wireValue: String) { Observed("observed"), UserAsserted("user_asserted"), Planned("planned"), Inferred("inferred") }
enum class GapReason(val wireValue: String) { ActualityUnconfirmed("actuality_unconfirmed"), ResultMissing("result_missing"), MeaningMissing("meaning_missing"), IdentityAmbiguous("identity_ambiguous"), SourceNotAuthorized("source_not_authorized"), SourceFailed("source_failed"), CapabilityUnavailable("capability_unavailable") }
enum class PromptPolicy(val wireValue: String) { DoNotPrompt("do_not_prompt"), Optional("optional"), PromptOnce("prompt_once"), SourceStatusOnly("source_status_only") }
enum class AcquisitionMode(val wireValue: String) { Automatic("automatic"), SceneTriggered("scene_triggered"), OneAction("one_action"), ImportOrForward("import_or_forward"), GapPrompt("gap_prompt"), ContinuousOptIn("continuous_opt_in") }
enum class CapabilityPriority(val wireValue: String) { P0("P0"), P1("P1"), P2("P2"), P3("P3") }
enum class SourceType(val wireValue: String) { Text("text"), Audio("audio"), Photo("photo"), Share("share"), Calendar("calendar"), Agent("agent"), Browser("browser"), File("file"), Meeting("meeting"), Email("email"), Location("location"), Health("health"), LifeService("life_service"), ContinuousScreen("continuous_screen"), ContinuousAudio("continuous_audio") }
enum class PermissionTier(val wireValue: String) { None("none"), Low("low"), Medium("medium"), High("high"), VeryHigh("very_high") }
enum class BystanderRisk(val wireValue: String) { None("none"), Low("low"), Medium("medium"), High("high") }
enum class ValidationStatus(val wireValue: String) { ImplementedPartial("implemented_partial"), OfficialPathOnly("official_path_only"), SyntheticOnly("synthetic_only"), ResearchNeeded("research_needed"), Deferred("deferred") }
enum class HardGate(val wireValue: String) { SourceExplanation("source_explanation"), ExplicitPermission("explicit_permission"), PauseAndRevoke("pause_and_revoke"), Lineage("lineage"), CascadeDelete("cascade_delete"), RetentionLimit("retention_limit"), BystanderNotice("bystander_notice"), PlatformPolicy("platform_policy"), Recovery("recovery"), RealDevice("real_device"), RealUser("real_user") }
enum class FieldAuthority(val wireValue: String) { Strong("strong"), Supporting("supporting"), Weak("weak"), None("none") }
enum class TimePrecision(val wireValue: String) { Exact("exact"), Minute("minute"), Hour("hour"), PartOfDay("part_of_day"), Day("day"), Range("range"), Unknown("unknown") }
enum class ObservationState(val wireValue: String) { Observed("observed"), Partial("partial"), NotAuthorized("not_authorized"), Failed("failed"), Unavailable("unavailable") }
enum class ContextGapState(val wireValue: String) { Open("open"), Dismissed("dismissed"), Resolved("resolved"), Expired("expired") }
enum class CoverageState(val wireValue: String) { Partial("partial"), EvidenceAvailable("evidence_available"), Sparse("sparse") }

data class TimeRange(
    val start: Instant,
    val end: Instant? = null,
    val timezone: String? = null,
    val precision: TimePrecision,
)

data class FieldClaim(val field: EvidenceField, val authority: FieldAuthority, val limitation: String)

data class SourceCapability(
    val schemaVersion: Int = 1,
    val capabilityId: String,
    val sourceType: SourceType,
    val acquisitionMode: AcquisitionMode,
    val contextTypes: Set<ContextType>,
    val fieldClaims: List<FieldClaim>,
    val eligibleSegmentIds: Set<String>,
    val priority: CapabilityPriority,
    val permissionTier: PermissionTier,
    val bystanderRisk: BystanderRisk,
    val validationStatus: ValidationStatus,
    val hardGates: Set<HardGate>,
)

data class GapHint(val contextType: ContextType, val reason: GapReason, val valueLevel: ValueLevel)
enum class ValueLevel(val wireValue: String) { Low("low"), Medium("medium"), High("high") }
enum class CandidateEventType(val wireValue: String) { Activity("activity"), Communication("communication"), Decision("decision"), Result("result"), StateChange("state_change"), Milestone("milestone"), Experience("experience") }
data class EventHint(val eventType: CandidateEventType, val title: String) {
    init {
        require(title.isNotBlank() && title.length <= 200) { "event_hint.title must be 1..200 characters" }
    }
}

data class CoverageSignal(
    val signalId: String,
    val capabilityId: String,
    val sourceState: SourceState,
    val contextTypes: Set<ContextType>,
    val observedFields: Set<EvidenceField>,
    val factStatus: CoverageFactStatus,
    val confidence: Double,
    val importance: ValueLevel,
    val sourceObjectIds: Set<String>,
    val observedAt: Instant,
    val timeRange: TimeRange? = null,
    val eventHint: EventHint? = null,
    val gapHints: List<GapHint>,
)

data class CoverageObservation(
    val schemaVersion: Int = 1,
    val coverageObservationId: String,
    val ownerId: String,
    val spaceId: String,
    val localDate: LocalDate,
    val capabilityId: String,
    val sourceObjectIds: Set<String>,
    val contextTypes: Set<ContextType>,
    val observedFields: Set<EvidenceField>,
    val factStatus: CoverageFactStatus,
    val state: ObservationState,
    val observedAt: Instant,
    val timeRange: TimeRange? = null,
)

data class CandidateEvent(
    val candidateId: String,
    val sourceObjectIds: Set<String>,
    val capabilityId: String,
    val eventType: CandidateEventType,
    val title: String,
    val timeRange: TimeRange?,
    val factStatus: CoverageFactStatus,
    val confidence: Double,
    val observedFields: Set<EvidenceField>,
)

data class ContextGap(
    val schemaVersion: Int = 1,
    val contextGapId: String,
    val ownerId: String,
    val spaceId: String,
    val localDate: LocalDate,
    val contextType: ContextType,
    val reason: GapReason,
    val valueLevel: ValueLevel,
    val promptPolicy: PromptPolicy,
    val capabilityIds: Set<String>,
    val state: ContextGapState,
    val createdAt: Instant,
    val timeRange: TimeRange? = null,
)

data class CoverageCompileRequest(
    val dayId: String,
    val ownerId: String,
    val spaceId: String,
    val localDate: LocalDate,
    val timezone: String,
    val signals: List<CoverageSignal>,
)

data class CoverageCompileResult(
    val schemaVersion: Int = 1,
    val dayId: String,
    val ownerId: String,
    val spaceId: String,
    val localDate: LocalDate,
    val timezone: String,
    val coverageState: CoverageState,
    val coveredContextTypes: Set<ContextType>,
    val unknownContextTypes: Set<ContextType>,
    val observations: List<CoverageObservation>,
    val candidateEvents: List<CandidateEvent>,
    val contextGaps: List<ContextGap>,
)
