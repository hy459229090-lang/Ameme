package com.ameme.android.data.transport

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleasePairingExperienceConnectorProviderTest {
    @Test
    fun releaseRealConnectorUsesTheFrozenLocalServiceType() {
        assertEquals("_ameme-agent._tcp.", NsdPairingExperienceConnector.SERVICE_TYPE)
    }
}
