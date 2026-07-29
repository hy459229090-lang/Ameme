package com.ameme.android.data.local

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ameme.android.domain.EvidenceState
import com.ameme.android.domain.EventType
import com.ameme.android.domain.FactStatus
import com.ameme.android.domain.LocatorPermissionState
import com.ameme.android.domain.Sensitivity
import com.ameme.android.domain.SourceCaptureRequest
import com.ameme.android.domain.SourceKind
import android.content.Context
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.time.LocalTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidKeystorePendingActionStoreInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = AndroidKeystorePendingActionStore(context)
    private val snapshotFile = File(context.noBackupFilesDir, "pending-actions/snapshot-v1.bin")
    private val temporaryFile = File(context.noBackupFilesDir, "pending-actions/snapshot-v1.bin.tmp")
    private val deletionMarkerFile = File(context.noBackupFilesDir, "pending-actions/space-deleted-v1")

    @After
    fun cleanUp() {
        runCatching { store.clear() }
        snapshotFile.delete()
        temporaryFile.delete()
        deletionMarkerFile.delete()
    }

    @Test
    fun pendingShareAndExport_roundTripEncryptedAndClearIndependently() {
        store.clear()
        val request = syntheticRequest()
        val export = "{\"synthetic\":\"export-private\"}"

        store.save(PendingActionSnapshot(incomingShare = request, exportContent = export))
        assertTrue(snapshotFile.isFile)
        val onDisk = String(snapshotFile.readBytes(), StandardCharsets.UTF_8)
        assertFalse("pending content must not be stored as plaintext", onDisk.contains("export-private"))
        assertFalse("pending content must not be stored as plaintext", onDisk.contains("恢复分享"))

        val restored = AndroidKeystorePendingActionStore(context).load()
        assertEquals(request, restored?.incomingShare)
        assertEquals(export, restored?.exportContent)

        store.clearIncomingShare()
        assertEquals(null, store.load()?.incomingShare)
        assertEquals(export, store.load()?.exportContent)
        store.clearExport()
        assertFalse(snapshotFile.exists())
    }

    @Test
    fun corruptedSnapshot_failsClosedWithoutInventingPendingAction() {
        store.clear()
        snapshotFile.parentFile?.mkdirs()
        snapshotFile.writeBytes(byteArrayOf(1, 1, 0))

        assertTrue(runCatching { store.load() }.isFailure)
        assertTrue("corrupted pending data is retained for explicit recovery handling", snapshotFile.exists())
    }

    @Test
    fun deletedSpaceFreezeClearsArtifactsAndRejectsPendingActionResurrection() {
        val isolatedRoot = File(context.cacheDir, "pending-actions-freeze-${System.nanoTime()}")
        val isolatedStore = AndroidKeystorePendingActionStore(context, isolatedRoot)
        try {
            isolatedStore.save(PendingActionSnapshot(exportContent = "private export"))
            File(isolatedRoot, ".orphan-atomic.tmp").writeText("orphaned private payload")

            isolatedStore.freezeForDeletedSpace()

            assertTrue(isolatedStore.isFrozenForDeletedSpace())
            assertNull(isolatedStore.load())
            assertEquals(listOf("space-deleted-v1"), isolatedRoot.list()?.sorted())
            assertThrows(IllegalStateException::class.java) {
                isolatedStore.save(PendingActionSnapshot(exportContent = "must not return"))
            }
        } finally {
            isolatedRoot.deleteRecursively()
        }
    }

    private fun syntheticRequest() = SourceCaptureRequest(
        sourceKind = SourceKind.SharedText,
        title = "恢复分享",
        detail = "通过系统分享入口保存的用户文字。",
        factStatus = FactStatus.UserAsserted,
        localDate = LocalDate.of(2026, 7, 18),
        time = LocalTime.of(12, 34),
        userWords = "恢复分享正文",
        locatorPermissionState = LocatorPermissionState.NoLocator,
        sourceInstanceKey = "synthetic-pending-share",
        eventType = EventType.Experience,
        evidenceState = EvidenceState.UserAsserted,
        sensitivity = Sensitivity.Personal,
        importance = 50,
    )
}
