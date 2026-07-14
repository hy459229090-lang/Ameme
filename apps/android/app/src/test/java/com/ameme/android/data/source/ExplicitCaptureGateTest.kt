package com.ameme.android.data.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplicitCaptureGateTest {
    @Test
    fun cancelledOrEmptyResultReturnsToIdle() {
        val gate = ExplicitCaptureGate()
        assertTrue(gate.begin())
        assertEquals(CaptureResultDecision.Cancelled, gate.acceptResult(hasSource = false))
        assertFalse(gate.isBusy)
        assertTrue(gate.begin())
    }

    @Test
    fun launchFailureReturnsToIdle() {
        val gate = ExplicitCaptureGate()
        assertTrue(gate.begin())
        assertTrue(gate.launchFailed())
        assertFalse(gate.isBusy)
    }

    @Test
    fun duplicateResultIsIgnoredUntilIoProcessingCompletes() {
        val gate = ExplicitCaptureGate()
        assertTrue(gate.begin())
        assertEquals(CaptureResultDecision.Process, gate.acceptResult(hasSource = true))
        assertEquals(CaptureResultDecision.Ignore, gate.acceptResult(hasSource = true))
        assertFalse(gate.begin())
        assertTrue(gate.complete())
        assertFalse(gate.isBusy)
        assertTrue(gate.begin())
    }
}
