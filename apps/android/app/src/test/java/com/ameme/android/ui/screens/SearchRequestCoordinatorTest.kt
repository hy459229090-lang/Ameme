package com.ameme.android.ui.screens

import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRequestCoordinatorTest {
    @Test
    fun oldLoadMoreReturningAfterNewQueryCannotOverwriteNewFirstPage() = runBlocking {
        val repository = Any()
        val coordinator = SearchRequestCoordinator()
        val oldIdentity = SearchRequestIdentity(repository, "old", null)
        val oldToken = coordinator.begin(oldIdentity)
        val releaseOldPage = CompletableDeferred<Unit>()
        var rendered = "old-first-page"
        var currentIdentity = oldIdentity

        val oldLoadMore = launch {
            releaseOldPage.await()
            coordinator.commitIfCurrent(oldToken, currentIdentity) { rendered = "old-late-page" }
        }

        val newIdentity = SearchRequestIdentity(repository, "new", LocalDate.of(2026, 7, 14))
        currentIdentity = newIdentity
        val newToken = coordinator.begin(newIdentity)
        assertTrue(coordinator.commitIfCurrent(newToken, newIdentity) { rendered = "new-first-page" })
        releaseOldPage.complete(Unit)
        oldLoadMore.join()

        assertEquals("new-first-page", rendered)
    }

    @Test
    fun obsoleteLoadMoreCannotClearNewQueryLoadingState() {
        val repository = Any()
        val coordinator = SearchRequestCoordinator()
        val oldIdentity = SearchRequestIdentity(repository, "old", null)
        val oldToken = coordinator.begin(oldIdentity)
        val newIdentity = SearchRequestIdentity(repository, "new", null)
        coordinator.begin(newIdentity)
        var newQueryInFlight = true

        if (coordinator.isCurrent(oldToken, newIdentity)) newQueryInFlight = false

        assertTrue(newQueryInFlight)
        assertFalse(coordinator.isCurrent(oldToken, newIdentity))
    }
}
