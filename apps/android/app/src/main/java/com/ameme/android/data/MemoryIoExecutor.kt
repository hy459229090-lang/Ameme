package com.ameme.android.data

import com.ameme.android.data.source.SourceGrantCleanupCoordinator
import com.ameme.android.data.source.SourceGrantCleanupResult
import com.ameme.android.domain.CaptureKind
import com.ameme.android.domain.MemoryEvent
import com.ameme.android.domain.MemoryPage
import com.ameme.android.domain.SourceCaptureRequest
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Single UI boundary for repository and platform-source I/O. Repository APIs stay synchronous for non-UI callers. */
class MemoryIoExecutor(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val closeScope = CoroutineScope(SupervisorJob() + dispatcher)

    suspend fun open(factory: () -> MemoryRepository): MemoryRepository {
        var unclaimed: MemoryRepository? = null
        try {
            val opened = withContext(dispatcher + NonCancellable) {
                factory().also { unclaimed = it }
            }
            currentCoroutineContext().ensureActive()
            unclaimed = null
            return opened
        } catch (failure: Throwable) {
            try {
                unclaimed?.let { close(it) }
            } catch (closeFailure: Throwable) {
                failure.addSuppressed(closeFailure)
            }
            throw failure
        }
    }

    suspend fun loadActiveEvents(repository: MemoryRepository): List<MemoryEvent> =
        onIo(repository::loadActiveEvents)

    suspend fun capture(repository: MemoryRepository, kind: CaptureKind, text: String): MemoryEvent =
        onIo { repository.capture(kind, text) }

    suspend fun captureSource(
        repository: MemoryRepository,
        request: SourceCaptureRequest,
    ): MemoryEvent = onIo { repository.captureSource(request) }

    suspend fun searchPage(
        repository: MemoryRepository,
        query: String,
        date: LocalDate?,
        cursor: String?,
        pageSize: Int,
    ): MemoryPage = onIo { repository.searchPage(query, date, cursor, pageSize) }

    suspend fun deleteEvent(repository: MemoryRepository, eventId: String): Boolean =
        onIo { repository.deleteEvent(eventId) }

    suspend fun retrySourceGrantCleanup(coordinator: SourceGrantCleanupCoordinator): SourceGrantCleanupResult =
        onIo(coordinator::retryPending)

    suspend fun <T> runSourceIo(block: () -> T): T = onIo(block)

    fun closeInBackground(repository: MemoryRepository) {
        closeScope.launch { repository.close() }
    }

    suspend fun close(repository: MemoryRepository) {
        withContext(dispatcher + NonCancellable) { repository.close() }
    }

    private suspend fun <T> onIo(block: () -> T): T = withContext(dispatcher) { block() }
}

internal suspend fun <T> runCatchingCancellable(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Throwable) {
    Result.failure(failure)
}
