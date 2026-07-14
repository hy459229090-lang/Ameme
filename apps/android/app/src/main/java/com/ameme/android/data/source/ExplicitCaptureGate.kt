package com.ameme.android.data.source

import java.util.concurrent.atomic.AtomicInteger

enum class CaptureResultDecision {
    Ignore,
    Cancelled,
    Process,
}

class ExplicitCaptureGate {
    private val state = AtomicInteger(STATE_IDLE)

    val isBusy: Boolean get() = state.get() != STATE_IDLE

    fun begin(): Boolean = state.compareAndSet(STATE_IDLE, STATE_WAITING_FOR_RESULT)

    fun acceptResult(hasSource: Boolean): CaptureResultDecision {
        val next = if (hasSource) STATE_PROCESSING else STATE_IDLE
        if (!state.compareAndSet(STATE_WAITING_FOR_RESULT, next)) return CaptureResultDecision.Ignore
        return if (hasSource) CaptureResultDecision.Process else CaptureResultDecision.Cancelled
    }

    fun launchFailed(): Boolean = state.compareAndSet(STATE_WAITING_FOR_RESULT, STATE_IDLE)

    fun complete(): Boolean = state.compareAndSet(STATE_PROCESSING, STATE_IDLE)

    private companion object {
        const val STATE_IDLE = 0
        const val STATE_WAITING_FOR_RESULT = 1
        const val STATE_PROCESSING = 2
    }
}
