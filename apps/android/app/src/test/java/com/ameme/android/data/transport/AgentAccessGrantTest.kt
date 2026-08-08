package com.ameme.android.data.transport

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AgentAccessGrantTest {
    private val createdAt = Instant.parse("2026-07-18T00:00:00Z")
    private val grant = AgentAccessGrant(
        schemaVersion = 1,
        grantId = "grt_synthetic_001",
        ownerId = "user_synthetic_001",
        callerId = "agent_synthetic_001",
        purposes = setOf("autonomous_memory"),
        spaces = setOf("space_personal"),
        dataTypes = setOf("event"),
        notBefore = createdAt,
        expiresAt = createdAt.plusSeconds(3_600),
        status = AgentAccessGrantStatus.Active,
        createdAt = createdAt,
    )

    @Test
    fun minimalSubsetIsAllowedAndExpansionIsDenied() {
        grant.authorize(
            AgentAccessGrantRequest(
                callerId = "agent_synthetic_001",
                grantId = "grt_synthetic_001",
                purpose = "autonomous_memory",
                space = "space_personal",
                dataType = "event",
                operation = "create_event",
            ),
            at = createdAt.plusSeconds(1),
        )

        val denied = assertThrows(AgentAccessGrantException::class.java) {
            grant.authorize(
                AgentAccessGrantRequest(
                    callerId = "agent_synthetic_001",
                    grantId = "grt_synthetic_001",
                    purpose = "autonomous_memory",
                    space = "space_other",
                    dataType = "event",
                    operation = "create_event",
                ),
                at = createdAt.plusSeconds(1),
            )
        }
        assertEquals(AgentAccessGrantFailure.SpaceDenied, denied.failure)
    }

    @Test
    fun expiryAndRevocationFailClosed() {
        val expired = assertThrows(AgentAccessGrantException::class.java) {
            grant.authorize(
                request(),
                at = createdAt.plusSeconds(3_600),
            )
        }
        assertEquals(AgentAccessGrantFailure.GrantExpired, expired.failure)

        val revoked = grant.copy(
            status = AgentAccessGrantStatus.Revoked,
            revokedAt = createdAt.plusSeconds(10),
        )
        val revokedError = assertThrows(AgentAccessGrantException::class.java) {
            revoked.authorize(request(), at = createdAt.plusSeconds(11))
        }
        assertEquals(AgentAccessGrantFailure.GrantRevoked, revokedError.failure)
    }

    @Test
    fun pairingPolicyBindsIncomingGrantToApprovedScope() {
        val policy = AgentAccessGrantPolicy.default(
            ownerId = "owner_synthetic_001",
            createdAt = createdAt,
            expiresAt = createdAt.plusSeconds(3_600),
        )
        assertEquals(setOf("event", "revision"), policy.dataTypes)
        assertEquals(
            setOf("create_event", "append_revision", "undo_capture", "visible_events"),
            policy.operations,
        )
        val bound = policy.bind("agent_synthetic_001", "grt_from_host_001")
        bound.authorize(request().copy(grantId = "grt_from_host_001"), createdAt.plusSeconds(1))
        val denied = assertThrows(AgentAccessGrantException::class.java) {
            bound.authorize(
                request().copy(grantId = "grt_from_host_001", dataType = "summary"),
                createdAt.plusSeconds(1),
            )
        }
        assertEquals(AgentAccessGrantFailure.DataTypeDenied, denied.failure)
    }

    private fun request() = AgentAccessGrantRequest(
        callerId = "agent_synthetic_001",
        grantId = "grt_synthetic_001",
        purpose = "autonomous_memory",
        space = "space_personal",
        dataType = "event",
        operation = "create_event",
    )
}
