package com.ameme.android.data.transport

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.decodeFromString
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentPairingManagerInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val now = Instant.parse("2026-07-14T00:00:00Z")
    private lateinit var manager: AgentPairingManager

    @Before
    fun setUp() {
        manager = AgentPairingManager(context, Clock.fixed(now, ZoneOffset.UTC))
        manager.revoke()
    }

    @After
    fun tearDown() {
        manager.revoke()
    }

    @Test
    fun create_load_and_revoke_keep_secret_out_of_pairing_file() {
        val created = manager.create(host = "127.0.0.1", port = 43_821)
        val pairingFileText = manager.pairingFile().readText()
        val persisted = AgentPairingManager.json.decodeFromString<AgentPairingMaterial>(pairingFileText)

        assertEquals(created.material, persisted)
        assertEquals("ameme.agent-local-node.channel.v1", persisted.channelProtocol)
        assertEquals("credential-ref:env/AMEME_ANDROID_PAIRING_SECRET", persisted.credentialRef)
        assertTrue(persisted.tlsCertificateSha256.matches(Regex("sha256_[0-9a-f]{64}")))
        assertFalse(pairingFileText.contains(created.oneTimeSecret))
        assertEquals(32, java.util.Base64.getUrlDecoder().decode(created.oneTimeSecret).size)

        val active = requireNotNull(manager.loadActive())
        try {
            assertEquals(created.material, active.material)
            assertEquals(now.plusSeconds(30L * 24 * 60 * 60), active.expiresAt)
            assertArrayEquals(created.oneTimeSecret.encodeToByteArray(), active.secret)
            assertEquals(
                setOf("create_event", "append_revision", "undo_capture", "visible_events"),
                active.accessGrantPolicy.operations,
            )
        } finally {
            active.close()
        }

        manager.revoke()
        assertNull(manager.loadActive())
        assertFalse(manager.pairingFile().exists())
    }

    @Test
    fun expired_pairing_is_not_loaded() {
        manager.create(host = "127.0.0.1", port = 43_821)
        val expiredView = AgentPairingManager(
            context,
            Clock.fixed(now.plusSeconds(31L * 24 * 60 * 60), ZoneOffset.UTC),
        )

        assertNull(expiredView.loadActive())
        assertFalse(expiredView.pairingFile().exists())
    }

    @Test
    fun legacy_pairing_without_explicit_operations_is_revoked_instead_of_expanded() {
        manager.create(host = "127.0.0.1", port = 43_821)
        val preferences = context.getSharedPreferences(
            "ameme_agent_pairing",
            android.content.Context.MODE_PRIVATE,
        )
        assertTrue(preferences.edit().remove("grant_operations").commit())

        assertNull(manager.loadActive())
        assertFalse(manager.pairingFile().exists())
    }
}
