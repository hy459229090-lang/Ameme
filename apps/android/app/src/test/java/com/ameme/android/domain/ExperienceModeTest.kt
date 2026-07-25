package com.ameme.android.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ExperienceModeTest {
    @Test
    fun localContentResolvesEmptySparseAndReadyWithoutHidingSystemStates() {
        assertEquals(ExperienceMode.Empty, ExperienceMode.Ready.resolvedFor(0, deriveFromEvents = true))
        assertEquals(ExperienceMode.Sparse, ExperienceMode.Empty.resolvedFor(1, deriveFromEvents = true))
        assertEquals(ExperienceMode.Ready, ExperienceMode.Sparse.resolvedFor(2, deriveFromEvents = true))
        assertEquals(
            ExperienceMode.RecoverableError,
            ExperienceMode.RecoverableError.resolvedFor(0, deriveFromEvents = true),
        )
    }

    @Test
    fun syntheticExperienceControlsRemainDeterministic() {
        assertEquals(ExperienceMode.Empty, ExperienceMode.Empty.resolvedFor(12, deriveFromEvents = false))
        assertEquals(ExperienceMode.Sparse, ExperienceMode.Sparse.resolvedFor(0, deriveFromEvents = false))
    }
}
