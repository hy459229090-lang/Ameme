import Foundation
import AmemeShared
import Darwin

@main
struct AmemeLocalNodeSmoke {
    static func main() async {
        do {
            guard
                let pairingPayload = ProcessInfo.processInfo.environment["AMEME_ANDROID_PAIRING_ENVELOPE"],
                !pairingPayload.isEmpty else {
                throw SmokeError.configurationMissing
            }
            let now = Date()
            let connector = BonjourAgentExperienceConnector()
            let candidate = try await connector.resolve(pairingPayload: pairingPayload)
            let connection = try await connector.connect(candidate: candidate)
            guard connection.method == .qrCode, !connection.simulated else {
                throw SmokeError.applicationRejected
            }
            await connector.disconnect(connection: connection)
            try await Task.sleep(nanoseconds: 500_000_000)

            let envelope = try AgentPairingEnvelope.parse(pairingPayload)
            let grant = AgentAccessGrantPolicy.default(
                createdAt: now,
                expiresAt: envelope.pairingExpiresAt
            ).bind(
                callerID: "agent_ios_network_smoke",
                grantID: "grant_ios_network_smoke"
            )
            let client = try AgentLocalNodeNetworkClient(
                pairing: envelope.pairing,
                secret: envelope.secret,
                accessGrant: grant
            )
            try await client.connect()
            let nowText = ISO8601DateFormatter().string(from: now)
            let response = try await client.exchangeCreateEvent(
                draft: AgentLocalNodeCreateEventDraft(
                    requestID: "req_ios_network_smoke",
                    idempotencyKey: "ios-network-smoke-idempotency",
                    content: "iOS Local Node 网络闭环合成事件",
                    eventType: "activity",
                    evidenceState: "observed",
                    factStatus: "confirmed",
                    sensitivity: "personal",
                    now: nowText,
                    eventTime: nowText
                ),
                authorizationDate: now
            )
            let responseObject = try JSONSerialization.jsonObject(with: response.applicationLine) as? [String: Any]
            guard responseObject?["status"] as? String == "ok" else {
                throw SmokeError.applicationRejected
            }
            await client.close()
            print("{\"status\":\"passed\",\"transport\":\"ios-qr-envelope-to-android-local-node\",\"qr_user_path_connected\":true,\"grant_bound\":true,\"content_logged\":false}")
        } catch {
            fputs("ameme_local_node_smoke_failed:\(error)\n", stderr)
            exit(1)
        }
    }
}

private enum SmokeError: Error {
    case configurationMissing
    case applicationRejected
}
