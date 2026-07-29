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
            guard candidate.method == .qrCode, !candidate.simulated else {
                throw SmokeError.applicationRejected
            }

            let envelope = try AgentPairingEnvelope.parse(pairingPayload)
            let grant = AgentAccessGrant(
                grantID: "grant_ios_network_smoke",
                ownerID: "owner_local",
                callerID: "agent_ios_network_smoke",
                purposes: ["autonomous_memory"],
                spaces: ["space_personal"],
                dataTypes: ["event", "revision"],
                notBefore: now,
                expiresAt: envelope.pairingExpiresAt,
                status: .active,
                createdAt: now,
                revokedAt: nil
            )
            let client = try AgentLocalNodeNetworkClient(
                pairing: envelope.pairing,
                secret: envelope.channelSecret,
                accessGrant: grant
            )
            do {
                try await client.connect()
            } catch {
                throw SmokeError.channelConnect(error)
            }
            let dateFormatter = ISO8601DateFormatter()
            dateFormatter.timeZone = .current
            dateFormatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            let nowText = dateFormatter.string(from: now)
            let primaryIdempotencyKey = "ios-network-smoke-idempotency"
            let revisionIdempotencyKey = "ios-network-smoke-revision-idempotency"
            let disposableIdempotencyKey = "ios-network-smoke-disposable-idempotency"
            do {
                let created = try await client.exchangeCreateEvent(
                    draft: AgentLocalNodeCreateEventDraft(
                        requestID: "req_ios_network_smoke",
                        idempotencyKey: primaryIdempotencyKey,
                        content: "iOS Local Node 网络闭环合成事件",
                        eventType: "activity",
                        evidenceState: "observed",
                        factStatus: "confirmed",
                        sensitivity: "personal",
                        now: nowText,
                        eventTime: nowText
                    )
                )
                let firstRead = try await client.exchangeVisibleEvents(
                    draft: AgentLocalNodeVisibleEventsDraft(
                        requestID: "req_ios_network_read",
                        idempotencyKey: "ios-network-smoke-read-idempotency",
                        spaces: ["space_personal"],
                        query: "iOS Local Node 网络闭环合成事件",
                        limit: 20
                    )
                )
                guard firstRead.events.contains(where: { $0.eventID == created.eventID }) else {
                    throw SmokeError.applicationRejected
                }

                let appended = try await client.exchangeAppendRevision(
                    draft: AgentLocalNodeAppendRevisionDraft(
                        requestID: "req_ios_network_revision",
                        idempotencyKey: revisionIdempotencyKey,
                        eventID: created.eventID,
                        space: "space_personal",
                        content: "iOS Local Node 网络闭环合成事件（已修订）",
                        evidenceState: "observed",
                        factStatus: "confirmed",
                        now: nowText
                    )
                )
                guard appended.targetEventID == created.eventID else {
                    throw SmokeError.applicationRejected
                }
                let revisionUndo = try await client.exchangeUndoCapture(
                    draft: AgentLocalNodeUndoCaptureDraft(
                        requestID: "req_ios_network_revision_undo",
                        idempotencyKey: "ios-network-smoke-revision-undo",
                        undoToken: AgentLocalNodeChannelCodec.deriveIdempotencySlot(
                            rawKey: revisionIdempotencyKey,
                            operation: AgentLocalNodeChannelCodec.operationAppendRevision
                        ),
                        space: "space_personal",
                        memoryType: "revision",
                        now: nowText
                    )
                )
                guard revisionUndo.targetEventID == created.eventID,
                      revisionUndo.undoneObjectID == appended.eventRevisionID,
                      revisionUndo.compensationRevisionID != nil else {
                    throw SmokeError.applicationRejected
                }

                let disposable = try await client.exchangeCreateEvent(
                    draft: AgentLocalNodeCreateEventDraft(
                        requestID: "req_ios_network_disposable",
                        idempotencyKey: disposableIdempotencyKey,
                        content: "iOS Local Node 撤销路径合成事件",
                        eventType: "activity",
                        evidenceState: "observed",
                        factStatus: "confirmed",
                        sensitivity: "personal",
                        now: nowText,
                        eventTime: nowText
                    )
                )
                let eventUndo = try await client.exchangeUndoCapture(
                    draft: AgentLocalNodeUndoCaptureDraft(
                        requestID: "req_ios_network_event_undo",
                        idempotencyKey: "ios-network-smoke-event-undo",
                        undoToken: AgentLocalNodeChannelCodec.deriveIdempotencySlot(
                            rawKey: disposableIdempotencyKey,
                            operation: AgentLocalNodeChannelCodec.operationCreateEvent
                        ),
                        space: "space_personal",
                        memoryType: "event",
                        now: nowText
                    )
                )
                guard eventUndo.targetEventID == disposable.eventID,
                      eventUndo.undoneObjectID == disposable.eventID,
                      eventUndo.compensationRevisionID == nil else {
                    throw SmokeError.applicationRejected
                }

                let finalRead = try await client.exchangeVisibleEvents(
                    draft: AgentLocalNodeVisibleEventsDraft(
                        requestID: "req_ios_network_final_read",
                        idempotencyKey: "ios-network-smoke-final-read",
                        spaces: ["space_personal"],
                        limit: 100
                    )
                )
                guard finalRead.events.contains(where: { $0.eventID == created.eventID }),
                      !finalRead.events.contains(where: { $0.eventID == disposable.eventID }) else {
                    throw SmokeError.applicationRejected
                }
            } catch {
                throw SmokeError.channelExchange(error)
            }
            await client.close()
            print("{\"status\":\"passed\",\"transport\":\"ios-qr-envelope-to-android-local-node\",\"qr_user_path_connected\":true,\"grant_bound\":true,\"bounded_visible_events\":true,\"append_revision\":true,\"exact_event_and_revision_undo\":true,\"content_logged\":false}")
        } catch {
            fputs("ameme_local_node_smoke_failed:\(error)\n", stderr)
            exit(1)
        }
    }
}

private enum SmokeError: Error {
    case configurationMissing
    case applicationRejected
    case channelConnect(Error)
    case channelExchange(Error)
}
