package com.ameme.android.data

import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MemoryIoExecutorTest {
    @Test
    fun defaultExecutorsSerializeRepositoryIoAcrossLifecycleInstances() {
        val active = AtomicInteger(0)
        val maximumActive = AtomicInteger(0)

        runBlocking {
            coroutineScope {
                List(3) {
                    async {
                        MemoryIoExecutor().runSourceIo {
                            val nowActive = active.incrementAndGet()
                            maximumActive.updateAndGet { current -> maxOf(current, nowActive) }
                            try {
                                Thread.sleep(30)
                            } finally {
                                active.decrementAndGet()
                            }
                        }
                    }
                }.awaitAll()
            }
        }

        assertEquals(1, maximumActive.get())
    }

    @Test
    fun repositoryWorkRunsOnInjectedIoDispatcher() {
        val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "ameme-test-io") }
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val callerThread = Thread.currentThread().name
            val ioThread = runBlocking {
                MemoryIoExecutor(dispatcher).runSourceIo { Thread.currentThread().name }
            }

            assertNotEquals(callerThread, ioThread)
            assertTrue(ioThread.startsWith("ameme-test-io"))
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun cancellationDuringOpenClosesRepositoryBeforeOwnershipCanBeLost() {
        val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "ameme-cancel-io") }
        val dispatcher = worker.asCoroutineDispatcher()
        val opened = CountDownLatch(1)
        val releaseFactory = CountDownLatch(1)
        val closeCount = AtomicInteger(0)
        val ownershipTransferred = AtomicBoolean(false)
        val repository = CloseTrackingRepository(closeCount)
        try {
            runBlocking {
                val job = launch(Dispatchers.Default) {
                    MemoryIoExecutor(dispatcher).open {
                        opened.countDown()
                        check(releaseFactory.await(5, TimeUnit.SECONDS))
                        repository
                    }
                    ownershipTransferred.set(true)
                }
                assertTrue(opened.await(5, TimeUnit.SECONDS))
                job.cancel()
                releaseFactory.countDown()
                job.join()
            }
            assertFalse(ownershipTransferred.get())
            assertEquals(1, closeCount.get())
        } finally {
            dispatcher.close()
            worker.shutdownNow()
        }
    }

    @Test
    fun cancellationIsRethrownWithoutEnteringFailureUiPath() {
        var failureUiHandled = false
        runBlocking {
            try {
                runCatchingCancellable<Unit> { throw CancellationException("synthetic cancellation") }
                    .onFailure { failureUiHandled = true }
                fail("CancellationException must be rethrown")
            } catch (_: CancellationException) {
                Unit
            }
        }
        assertFalse(failureUiHandled)
    }

    private class CloseTrackingRepository(
        private val closeCount: AtomicInteger,
    ) : MemoryRepository by FakeMemoryRepository() {
        override fun close() {
            closeCount.incrementAndGet()
        }
    }
}
