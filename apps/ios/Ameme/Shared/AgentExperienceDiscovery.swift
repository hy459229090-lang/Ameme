import Foundation
import Network

/// Bonjour discovery is deliberately only a candidate source. A visible service is not an
/// authorization, a Grant, or a data channel; the App must complete the later authenticated
/// session before it can persist an AgentExperienceConnection with `simulated == false`.
public final class BonjourAgentExperienceDiscovery: @unchecked Sendable {
    private let queue = DispatchQueue(label: "com.ameme.agent-experience-discovery")

    public init() {}

    public func start(
        timeout: TimeInterval = 5,
        onCandidate: @escaping @Sendable (AgentExperienceCandidate) -> Void,
        onError: @escaping @Sendable (AgentExperienceDiscoveryError) -> Void = { _ in }
    ) -> AgentExperienceDiscoverySession {
        let browser = NWBrowser(
            for: .bonjour(type: AgentExperienceServiceContract.bonjourServiceType, domain: nil),
            using: .tcp
        )
        let state = DiscoveryState()
        let timer = DispatchWorkItem {
            guard state.completeOnce() else { return }
            browser.cancel()
            onError(.noDeviceFound)
        }
        let timerBox = WorkItemBox(timer)
        browser.stateUpdateHandler = { newState in
            if case .failed = newState, state.completeOnce() {
                timerBox.cancel()
                browser.cancel()
                onError(.unavailable)
            }
        }
        browser.browseResultsChangedHandler = { results, _ in
            guard let result = results.first, state.completeOnce() else { return }
            timerBox.cancel()
            browser.cancel()
            onCandidate(Self.candidate(from: result))
        }
        browser.start(queue: queue)
        queue.asyncAfter(deadline: .now() + timeout, execute: timer)
        return AgentExperienceDiscoverySession { browser.cancel(); timerBox.cancel() }
    }

    private static func candidate(from result: NWBrowser.Result) -> AgentExperienceCandidate {
        let endpointDescription = String(describing: result.endpoint)
        let serviceName = endpointDescription
            .replacingOccurrences(of: "service(name: ", with: "")
            .split(separator: ",", maxSplits: 1)
            .first
            .map(String.init)
            .map { $0.trimmingCharacters(in: CharacterSet(charactersIn: "\\\"")) }
            .flatMap { $0.isEmpty ? nil : $0 }
            ?? "局域网 Agent"
        let identifier = sanitizedIdentifier(serviceName)
        return AgentExperienceCandidate(
            id: identifier,
            deviceName: String(serviceName.prefix(80)),
            agentName: "Ameme Agent",
            method: .lanDiscovery,
            capabilities: ["能力待授权确认"],
            simulated: false
        )
    }

    private static func sanitizedIdentifier(_ value: String) -> String {
        let allowed = value.filter { $0.isLetter || $0.isNumber || "._:-".contains($0) }
        let normalized = String(allowed.prefix(127))
        return normalized.first?.isLetter == true || normalized.first?.isNumber == true
            ? normalized
            : "bonjour-agent"
    }

    private final class DiscoveryState: @unchecked Sendable {
        private let lock = NSLock()
        private var completed = false

        func completeOnce() -> Bool {
            lock.lock()
            defer { lock.unlock() }
            guard !completed else { return false }
            completed = true
            return true
        }
    }

    private final class WorkItemBox: @unchecked Sendable {
        private let lock = NSLock()
        private let workItem: DispatchWorkItem

        init(_ workItem: DispatchWorkItem) {
            self.workItem = workItem
        }

        func cancel() {
            lock.lock()
            defer { lock.unlock() }
            workItem.cancel()
        }
    }
}

public final class AgentExperienceDiscoverySession: @unchecked Sendable {
    private let cancellation: () -> Void
    private var cancelled = false
    private let lock = NSLock()

    fileprivate init(cancellation: @escaping () -> Void) {
        self.cancellation = cancellation
    }

    public func cancel() {
        lock.lock()
        defer { lock.unlock() }
        guard !cancelled else { return }
        cancelled = true
        cancellation()
    }
}
