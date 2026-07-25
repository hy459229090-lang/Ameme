package com.ameme.android.data.transport

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit debug E2E seam: provisions channel credentials, never Event or memory content. */
@RunWith(AndroidJUnit4::class)
class AgentPairingProvisioningInstrumentedTest {
    @Test
    fun provisionLocalLoopbackPairing() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString(ARGUMENT) == "true")
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val manager = AgentPairingManager(context)
        manager.revoke()
        val created = manager.create(host = "127.0.0.1", port = AgentPairingManager.DEFAULT_PORT)
        val outputDirectory = File(context.cacheDir, OUTPUT_DIRECTORY).apply { mkdirs() }
        val envelopeFile = File(outputDirectory, ENVELOPE_FILE)
        envelopeFile.writeText(created.pairingQrPayload(), Charsets.UTF_8)
        assertTrue(manager.pairingFile().isFile)
        assertTrue(envelopeFile.isFile)
    }

    @Test
    fun revokeLocalLoopbackPairing() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString(REVOKE_ARGUMENT) == "true")
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        AgentPairingManager(context).revoke()
        File(File(context.cacheDir, OUTPUT_DIRECTORY), ENVELOPE_FILE).delete()
        assertTrue(!AgentPairingManager(context).pairingFile().exists())
    }

    companion object {
        const val ARGUMENT = "amemeProvisionPairing"
        const val REVOKE_ARGUMENT = "amemeRevokePairing"
        const val OUTPUT_DIRECTORY = "agent-pairing-e2e"
        const val ENVELOPE_FILE = "pairing-envelope.txt"
    }
}
