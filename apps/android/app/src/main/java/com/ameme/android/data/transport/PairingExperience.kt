package com.ameme.android.data.transport

import android.content.Context
import java.time.Instant

private val PAIRING_EXPERIENCE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")

enum class PairingExperienceMethod(val wireValue: String) {
    LanDiscovery("lan_discovery"),
    QrCode("qr_code"),
    AccountDevice("account_device"),
}

data class PairingExperienceCandidate(
    val id: String,
    val deviceName: String,
    val agentName: String,
    val method: PairingExperienceMethod,
    val capabilities: List<String>,
    val simulated: Boolean,
) {
    init {
        require(PAIRING_EXPERIENCE_ID.matches(id))
        require(deviceName.isNotBlank() && deviceName.length <= 80)
        require(agentName.isNotBlank() && agentName.length <= 80)
        require(capabilities.isNotEmpty() && capabilities.size <= 8)
        require(capabilities.all { it.isNotBlank() && it.length <= 80 })
    }
}

data class PairingExperienceConnection(
    val id: String,
    val deviceName: String,
    val agentName: String,
    val method: PairingExperienceMethod,
    val capabilities: List<String>,
    val connectedAt: Instant,
    val expiresAt: Instant,
    val simulated: Boolean,
) {
    init {
        require(PAIRING_EXPERIENCE_ID.matches(id))
        require(deviceName.isNotBlank() && deviceName.length <= 80)
        require(agentName.isNotBlank() && agentName.length <= 80)
        require(capabilities.isNotEmpty() && capabilities.size <= 8)
        require(capabilities.all { it.isNotBlank() && it.length <= 80 })
        require(expiresAt > connectedAt)
    }

    fun isValid(at: Instant = Instant.now()): Boolean = expiresAt > at

    companion object {
        const val DEFAULT_LIFETIME_SECONDS: Long = 30L * 24L * 60L * 60L
    }
}

interface PairingExperienceConnector {
    val simulated: Boolean

    suspend fun resolve(method: PairingExperienceMethod): PairingExperienceCandidate

    suspend fun connect(candidate: PairingExperienceCandidate): PairingExperienceConnection

    suspend fun disconnect(connection: PairingExperienceConnection)
}

enum class PairingExperienceFailure(val wireValue: String) {
    DiscoveryUnavailable("discovery_unavailable"),
    NoDeviceFound("no_device_found"),
    QrScannerUnavailable("qr_scanner_unavailable"),
    AccountSignInRequired("account_sign_in_required"),
    AuthorizationRequired("authorization_required"),
}

class PairingExperienceException(
    val failure: PairingExperienceFailure,
    cause: Throwable? = null,
) : IllegalStateException(failure.wireValue, cause)

class PairingExperienceStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): PairingExperienceConnection? {
        val storedKeys = preferences.all.keys
        if (storedKeys.isEmpty()) return null
        if (storedKeys != EXPECTED_KEYS) return clearInvalid()
        val storedMethod = preferences.getString(KEY_METHOD, null) ?: return null
        val method = PairingExperienceMethod.entries.firstOrNull { it.wireValue == storedMethod }
            ?: return clearInvalid()
        val capabilities = preferences.getString(KEY_CAPABILITIES, null)
            ?.split(CAPABILITY_SEPARATOR)
            ?.filter(String::isNotBlank)
            ?: return clearInvalid()
        return runCatching {
            PairingExperienceConnection(
                id = requireNotNull(preferences.getString(KEY_ID, null)),
                deviceName = requireNotNull(preferences.getString(KEY_DEVICE_NAME, null)),
                agentName = requireNotNull(preferences.getString(KEY_AGENT_NAME, null)),
                method = method,
                capabilities = capabilities,
                connectedAt = Instant.ofEpochMilli(preferences.getLong(KEY_CONNECTED_AT, 0L)),
                expiresAt = Instant.ofEpochMilli(preferences.getLong(KEY_EXPIRES_AT, 0L)),
                simulated = preferences.getBoolean(KEY_SIMULATED, false),
            )
        }.getOrNull()?.takeIf { it.isValid() && it.simulated } ?: clearInvalid()
    }

    fun save(connection: PairingExperienceConnection) {
        check(connection.isValid()) { "Cannot persist an expired pairing experience" }
        check(
            preferences.edit()
                .putString(KEY_ID, connection.id)
                .putString(KEY_DEVICE_NAME, connection.deviceName)
                .putString(KEY_AGENT_NAME, connection.agentName)
                .putString(KEY_METHOD, connection.method.wireValue)
                .putString(KEY_CAPABILITIES, connection.capabilities.joinToString(CAPABILITY_SEPARATOR))
                .putLong(KEY_CONNECTED_AT, connection.connectedAt.toEpochMilli())
                .putLong(KEY_EXPIRES_AT, connection.expiresAt.toEpochMilli())
                .putBoolean(KEY_SIMULATED, connection.simulated)
                .commit(),
        ) { "Could not persist pairing experience state" }
    }

    fun clear() {
        check(preferences.edit().clear().commit()) { "Could not clear pairing experience state" }
    }

    private fun clearInvalid(): PairingExperienceConnection? {
        preferences.edit().clear().commit()
        return null
    }

    companion object {
        const val PREFERENCES = "ameme_pairing_experience"
        private const val KEY_ID = "connection_id"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_AGENT_NAME = "agent_name"
        private const val KEY_METHOD = "method"
        private const val KEY_CAPABILITIES = "capabilities"
        private const val KEY_CONNECTED_AT = "connected_at"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_SIMULATED = "simulated"
        private const val CAPABILITY_SEPARATOR = "\u001f"
        private val EXPECTED_KEYS = setOf(
            KEY_ID,
            KEY_DEVICE_NAME,
            KEY_AGENT_NAME,
            KEY_METHOD,
            KEY_CAPABILITIES,
            KEY_CONNECTED_AT,
            KEY_EXPIRES_AT,
            KEY_SIMULATED,
        )
    }
}
