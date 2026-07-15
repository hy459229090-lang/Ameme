package com.ameme.android.data.transport

import org.junit.Assert.assertNull
import org.junit.Test

class ReleasePairingExperienceConnectorProviderTest {
    @Test
    fun releaseDoesNotProvideSyntheticConnectionExperience() {
        assertNull(PairingExperienceConnectorProvider.create())
    }
}
