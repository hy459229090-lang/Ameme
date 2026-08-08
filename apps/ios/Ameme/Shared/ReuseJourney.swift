import Combine
import Foundation

public enum ReuseJourneyStatus: String, Equatable, Sendable {
    case idle
    case ready
    case feedbackRecorded = "feedback_recorded"
    case invalidScope = "invalid_scope"
    case unavailable
    case failed
}

/// UI-ready Shared flow for all four explicit reuse journeys. The controller contains no visual
/// decisions; the protected SwiftUI shell only needs to bind inputs, results, and feedback.
@MainActor
public final class ReuseJourneyController: ObservableObject {
    @Published public private(set) var status: ReuseJourneyStatus = .idle
    @Published public private(set) var resolved: ResolvedReuseContext?

    public init() {}

    public func start(
        store: LocalMemoryStore,
        intent: ReuseIntent,
        query: String = "",
        startDate: Date? = nil,
        endDate: Date? = nil,
        meetingAnchorDate: Date? = nil,
        limit: Int = 20,
        requestedAt: Date = .now
    ) {
        do {
            let request = ReuseRequest(
                spaceID: SourceObject.personalSpaceID,
                intent: intent,
                query: query,
                startDate: startDate,
                endDate: endDate,
                meetingAnchorDate: meetingAnchorDate,
                limit: limit,
                requestedAt: requestedAt
            )
            let context = try store.buildReuseContext(request)
            resolved = try store.resolveReuseContext(context, at: requestedAt)
            status = .ready
        } catch ReuseError.invalidRequest {
            resolved = nil
            status = .invalidScope
        } catch ReuseError.unavailable {
            resolved = nil
            status = .unavailable
        } catch {
            resolved = nil
            status = .failed
        }
    }

    /// A non-none user action must be supplied only after the corresponding revise/hide/delete
    /// mutation has committed. Feedback never performs that mutation implicitly.
    @discardableResult
    public func submitFeedback(
        store: LocalMemoryStore,
        outcome: ReuseOutcome,
        userAction: ReuseUserAction = .none,
        submittedAt: Date = .now
    ) -> Bool {
        guard let resolved else {
            status = .failed
            return false
        }
        do {
            let recorded = try store.recordReuseOutcome(
                ReuseOutcomeSubmission(
                    attemptID: resolved.context.attemptID,
                    outcome: outcome,
                    userAction: userAction,
                    submittedAt: submittedAt
                )
            )
            status = recorded ? .feedbackRecorded : .failed
            return recorded
        } catch {
            status = .failed
            return false
        }
    }

    public func reset() {
        resolved = nil
        status = .idle
    }
}
