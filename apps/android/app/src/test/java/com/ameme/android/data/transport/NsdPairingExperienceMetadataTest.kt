package com.ameme.android.data.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NsdPairingExperienceMetadataTest {
    @Test
    fun serviceMetadataBecomesNonSensitiveRealCandidate() {
        val candidate = PairingExperienceServiceMetadata.candidate(
            serviceName = "Desktop._ameme-agent._tcp.",
            attributes = mapOf(
                "device_id" to "desktop-001",
                "device_name" to "Ameme Desktop",
                "agent_name" to "Codex",
                "capabilities" to "写入结构化工作记录,读取今日范围",
                "secret" to "must-not-be-used",
            ),
        )

        assertEquals("desktop-001", candidate.id)
        assertEquals("Ameme Desktop", candidate.deviceName)
        assertEquals(listOf("写入结构化工作记录", "读取今日范围"), candidate.capabilities)
        assertFalse(candidate.simulated)
    }

    @Test
    fun missingCapabilityDoesNotInventPermission() {
        val candidate = PairingExperienceServiceMetadata.candidate(
            serviceName = "Desktop",
            attributes = emptyMap(),
        )

        assertEquals(listOf("能力待授权确认"), candidate.capabilities)
        assertFalse(candidate.simulated)
    }
}
