package com.ameme.android.data.transport

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PairingExperienceStoreInstrumentedTest {
    private lateinit var context: Context
    private lateinit var store: PairingExperienceStore

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences(PairingExperienceStore.PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        store = PairingExperienceStore(context)
    }

    @After
    fun tearDown() {
        context.getSharedPreferences(PairingExperienceStore.PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun saveLoadAndClear_roundTripsOnlyNonSensitiveConnectionMetadata() {
        val connection = PairingExperienceConnection(
            id = "debug-connection-lan_discovery",
            deviceName = "Ameme Desktop",
            agentName = "Codex",
            method = PairingExperienceMethod.LanDiscovery,
            capabilities = listOf("写入结构化工作记录"),
            connectedAt = Instant.parse("2026-07-15T01:02:03Z"),
            simulated = true,
        )

        store.save(connection)

        assertEquals(connection, store.load())
        val keys = context.getSharedPreferences(PairingExperienceStore.PREFERENCES, Context.MODE_PRIVATE).all.keys
        assertEquals(
            setOf("connection_id", "device_name", "agent_name", "method", "capabilities", "connected_at", "simulated"),
            keys,
        )
        store.clear()
        assertNull(store.load())
    }

    @Test
    fun malformedState_failsClosedAndIsRemoved() {
        val preferences = context.getSharedPreferences(PairingExperienceStore.PREFERENCES, Context.MODE_PRIVATE)
        preferences.edit()
            .putString("method", PairingExperienceMethod.LanDiscovery.wireValue)
            .putString("secret", "must-not-survive")
            .commit()

        assertNull(store.load())
        assertEquals(emptyMap<String, Any>(), preferences.all)
    }
}
