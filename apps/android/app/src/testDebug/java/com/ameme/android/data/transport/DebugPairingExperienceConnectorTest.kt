package com.ameme.android.data.transport

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugPairingExperienceConnectorTest {
    @Test
    fun everyEntry_resolvesToTheSameAuthorizedDebugExperience() = runBlocking {
        val fixedInstant = Instant.parse("2026-07-15T01:02:03Z")
        val connector = DebugPairingExperienceConnector(
            clock = Clock.fixed(fixedInstant, ZoneOffset.UTC),
            delayMillis = 0,
        )

        PairingExperienceMethod.entries.forEach { method ->
            val candidate = connector.resolve(method)
            val connection = connector.connect(candidate)

            assertEquals(method, candidate.method)
            assertEquals(method, connection.method)
            assertEquals("Ameme Desktop", connection.deviceName)
            assertEquals("Codex", connection.agentName)
            assertEquals(listOf("写入结构化工作记录"), connection.capabilities)
            assertEquals(fixedInstant, connection.connectedAt)
            assertTrue(connection.simulated)
        }
    }
}
