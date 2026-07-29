package com.ameme.android.data.transport

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.math.BigInteger
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory
import javax.security.auth.x500.X500Principal
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.ameme.android.data.transport.channel.AgentPairingEnvelope
import com.ameme.android.data.transport.channel.AndroidLocalNodePairingMaterial

@Serializable
data class AgentPairingMaterial(
    @SerialName("channel_protocol") val channelProtocol: String,
    @SerialName("endpoint_ref") val endpointRef: String,
    @SerialName("credential_ref") val credentialRef: String,
    @SerialName("expected_device_id") val expectedDeviceId: String,
    @SerialName("session_binding_ref") val sessionBindingRef: String,
    @SerialName("pairing_id") val pairingId: String,
    val host: String,
    val port: Int,
    @SerialName("tls_certificate_sha256") val tlsCertificateSha256: String,
)

data class CreatedAgentPairing(
    val material: AgentPairingMaterial,
    val oneTimeSecret: String,
    val expiresAt: Instant,
) {
    fun pairingJson(): String = AgentPairingManager.json.encodeToString(material)

    fun pairingQrPayload(now: Instant = Instant.now()): String = AgentPairingEnvelope.encode(
        pairing = AndroidLocalNodePairingMaterial(
            channelProtocol = material.channelProtocol,
            endpointRef = material.endpointRef,
            credentialRef = material.credentialRef,
            expectedDeviceId = material.expectedDeviceId,
            sessionBindingRef = material.sessionBindingRef,
            pairingId = material.pairingId,
            host = material.host,
            port = material.port,
            tlsCertificateSha256 = material.tlsCertificateSha256,
        ),
        oneTimeSecret = oneTimeSecret,
        expiresAt = minOf(expiresAt, now.plus(QR_ENVELOPE_DURATION)),
        pairingExpiresAt = expiresAt,
    )

    private companion object {
        val QR_ENVELOPE_DURATION: Duration = Duration.ofMinutes(5)
    }
}

data class ActiveAgentPairing(
    val material: AgentPairingMaterial,
    val secret: ByteArray,
    val expiresAt: Instant,
    val accessGrantPolicy: AgentAccessGrantPolicy,
) : AutoCloseable {
    override fun close() = secret.fill(0)
}

class AgentPairingManager(
    private val context: Context,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val identity by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { AndroidAgentTlsIdentity() }

    fun create(host: String = defaultHost(), port: Int = DEFAULT_PORT): CreatedAgentPairing {
        require(host.isNotBlank() && host.length <= 253 && port in 1..65_535)
        val deviceId = preferences.getString(KEY_DEVICE_ID, null)
            ?: "device_${UUID.randomUUID().toString().replace("-", "")}".also { created ->
                check(preferences.edit().putString(KEY_DEVICE_ID, created).commit())
            }
        val pairingId = "pair_${UUID.randomUUID().toString().replace("-", "")}"
        val expiresAt = clock.instant().plus(PAIRING_DURATION)
        val createdAt = clock.instant()
        val accessGrantPolicy = AgentAccessGrantPolicy.default(
            ownerId = "owner_$deviceId",
            createdAt = createdAt,
            expiresAt = expiresAt,
        )
        val material = AgentPairingMaterial(
            channelProtocol = CHANNEL_PROTOCOL,
            endpointRef = "endpoint-ref:android/$deviceId",
            credentialRef = "credential-ref:env/$HOST_SECRET_ENV",
            expectedDeviceId = deviceId,
            sessionBindingRef = "session-binding-ref:android/${UUID.randomUUID().toString().replace("-", "")}",
            pairingId = pairingId,
            host = host,
            port = port,
            tlsCertificateSha256 = identity.certificatePin(),
        )
        val secretBytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val oneTimeSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes)
        secretBytes.fill(0)
        val encrypted = encrypt(oneTimeSecret.encodeToByteArray(), pairingId)
        val materialJson = json.encodeToString(material)
        val committed = preferences.edit()
            .putString(KEY_MATERIAL, materialJson)
            .putString(KEY_SECRET_IV, Base64.getEncoder().encodeToString(encrypted.iv))
            .putString(KEY_SECRET_CIPHERTEXT, Base64.getEncoder().encodeToString(encrypted.ciphertext))
            .putLong(KEY_EXPIRES_AT, expiresAt.toEpochMilli())
            .putString(KEY_GRANT_OWNER, accessGrantPolicy.ownerId)
            .putString(KEY_GRANT_PURPOSES, accessGrantPolicy.purposes.joinToString(SCOPE_SEPARATOR))
            .putString(KEY_GRANT_SPACES, accessGrantPolicy.spaces.joinToString(SCOPE_SEPARATOR))
            .putString(KEY_GRANT_DATA_TYPES, accessGrantPolicy.dataTypes.joinToString(SCOPE_SEPARATOR))
            .putString(KEY_GRANT_OPERATIONS, accessGrantPolicy.operations.joinToString(SCOPE_SEPARATOR))
            .putLong(KEY_GRANT_NOT_BEFORE, accessGrantPolicy.notBefore.toEpochMilli())
            .putLong(KEY_GRANT_CREATED_AT, accessGrantPolicy.createdAt.toEpochMilli())
            .commit()
        encrypted.clear()
        check(committed) { "Could not persist Agent pairing" }
        writePairingMaterial(materialJson)
        return CreatedAgentPairing(material, oneTimeSecret, expiresAt)
    }

    fun loadActive(): ActiveAgentPairing? {
        val materialJson = preferences.getString(KEY_MATERIAL, null) ?: return null
        val expiresAtMillis = preferences.getLong(KEY_EXPIRES_AT, 0L)
        val expiresAt = runCatching { Instant.ofEpochMilli(expiresAtMillis) }.getOrNull() ?: return null
        if (!expiresAt.isAfter(clock.instant())) {
            revoke()
            return null
        }
        val material = runCatching { json.decodeFromString<AgentPairingMaterial>(materialJson) }.getOrNull()
            ?: return null
        val accessGrantPolicy = loadAccessGrantPolicy(expiresAt) ?: run {
            revoke()
            return null
        }
        val iv = preferences.getString(KEY_SECRET_IV, null)?.let(Base64.getDecoder()::decode) ?: return null
        val ciphertext = preferences.getString(KEY_SECRET_CIPHERTEXT, null)?.let(Base64.getDecoder()::decode)
            ?: return null
        val secret = try {
            decrypt(iv, ciphertext, material.pairingId)
        } finally {
            iv.fill(0)
            ciphertext.fill(0)
        }
        if (secret.size !in 32..256) {
            secret.fill(0)
            return null
        }
        return ActiveAgentPairing(material, secret, expiresAt, accessGrantPolicy)
    }

    fun revoke() {
        val committed = preferences.edit()
            .remove(KEY_MATERIAL)
            .remove(KEY_SECRET_IV)
            .remove(KEY_SECRET_CIPHERTEXT)
            .remove(KEY_EXPIRES_AT)
            .remove(KEY_GRANT_OWNER)
            .remove(KEY_GRANT_PURPOSES)
            .remove(KEY_GRANT_SPACES)
            .remove(KEY_GRANT_DATA_TYPES)
            .remove(KEY_GRANT_OPERATIONS)
            .remove(KEY_GRANT_NOT_BEFORE)
            .remove(KEY_GRANT_CREATED_AT)
            .commit()
        check(committed) { "Could not revoke persisted Agent pairing" }
        check(PAIRING_STATE_KEYS.none(preferences::contains)) {
            "Persisted Agent pairing remained after revocation"
        }
        val pairingFile = pairingFile()
        val temporaryFile = File(pairingFile.parentFile, "${pairingFile.name}.tmp")
        check(!pairingFile.exists() || pairingFile.delete()) {
            "Could not remove Agent pairing material"
        }
        check(!temporaryFile.exists() || temporaryFile.delete()) {
            "Could not remove temporary Agent pairing material"
        }
    }

    fun sslServerSocketFactory(): SSLServerSocketFactory = identity.sslServerSocketFactory()

    fun pairingFile(): File = File(context.filesDir, PAIRING_FILE)

    private fun loadAccessGrantPolicy(expiresAt: Instant): AgentAccessGrantPolicy? {
        if (
            !preferences.contains(KEY_GRANT_OWNER) ||
            !preferences.contains(KEY_GRANT_PURPOSES) ||
            !preferences.contains(KEY_GRANT_SPACES) ||
            !preferences.contains(KEY_GRANT_DATA_TYPES) ||
            !preferences.contains(KEY_GRANT_OPERATIONS) ||
            !preferences.contains(KEY_GRANT_NOT_BEFORE) ||
            !preferences.contains(KEY_GRANT_CREATED_AT)
        ) {
            return null
        }
        val ownerId = preferences.getString(KEY_GRANT_OWNER, null) ?: return null
        val purposes = preferences.getString(KEY_GRANT_PURPOSES, null)
            ?.split(SCOPE_SEPARATOR)?.filter(String::isNotBlank)?.toSet() ?: return null
        val spaces = preferences.getString(KEY_GRANT_SPACES, null)
            ?.split(SCOPE_SEPARATOR)?.filter(String::isNotBlank)?.toSet() ?: return null
        val dataTypes = preferences.getString(KEY_GRANT_DATA_TYPES, null)
            ?.split(SCOPE_SEPARATOR)?.filter(String::isNotBlank)?.toSet() ?: return null
        val operations = preferences.getString(KEY_GRANT_OPERATIONS, null)
            ?.split(SCOPE_SEPARATOR)?.filter(String::isNotBlank)?.toSet() ?: return null
        val notBefore = runCatching {
            Instant.ofEpochMilli(preferences.getLong(KEY_GRANT_NOT_BEFORE, Long.MIN_VALUE))
        }.getOrNull() ?: return null
        val createdAt = runCatching {
            Instant.ofEpochMilli(preferences.getLong(KEY_GRANT_CREATED_AT, Long.MIN_VALUE))
        }.getOrNull() ?: return null
        return runCatching {
            AgentAccessGrantPolicy(
                schemaVersion = AgentAccessGrant.CURRENT_SCHEMA_VERSION,
                ownerId = ownerId,
                purposes = purposes,
                spaces = spaces,
                dataTypes = dataTypes,
                operations = operations,
                notBefore = notBefore,
                expiresAt = expiresAt,
                status = AgentAccessGrantStatus.Active,
                createdAt = createdAt,
            )
        }.getOrNull()?.takeIf { it.isActive(clock.instant()) }
    }

    private fun writePairingMaterial(value: String) {
        val target = pairingFile()
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeText(value, Charsets.UTF_8)
        check(temporary.renameTo(target) || run {
            target.delete()
            temporary.renameTo(target)
        }) { "Could not persist pairing material" }
    }

    private fun encrypt(cleartext: ByteArray, pairingId: String): EncryptedValue {
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, pairingEncryptionKey())
        cipher.updateAAD(pairingId.encodeToByteArray())
        val ciphertext = try {
            cipher.doFinal(cleartext)
        } finally {
            cleartext.fill(0)
        }
        return EncryptedValue(cipher.iv, ciphertext)
    }

    private fun decrypt(iv: ByteArray, ciphertext: ByteArray, pairingId: String): ByteArray {
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, pairingEncryptionKey(), GCMParameterSpec(128, iv))
        cipher.updateAAD(pairingId.encodeToByteArray())
        return cipher.doFinal(ciphertext)
    }

    private fun pairingEncryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(PAIRING_KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    PAIRING_KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private fun defaultHost(): String {
        if (isProbablyEmulator()) return "127.0.0.1"
        val addresses = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
        return addresses.filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress }
            ?.hostAddress
            ?: "127.0.0.1"
    }

    private fun isProbablyEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
            Build.MODEL.contains("sdk_gphone", ignoreCase = true)

    private data class EncryptedValue(val iv: ByteArray, val ciphertext: ByteArray) {
        fun clear() {
            iv.fill(0)
            ciphertext.fill(0)
        }
    }

    companion object {
        internal val json = Json {
            ignoreUnknownKeys = false
            encodeDefaults = true
            explicitNulls = false
        }
        const val HOST_SECRET_ENV = "AMEME_ANDROID_PAIRING_SECRET"
        const val DEFAULT_PORT = 43_821
        private const val CHANNEL_PROTOCOL = "ameme.agent-local-node.channel.v1"
        private const val PREFERENCES = "ameme_agent_pairing"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_MATERIAL = "material"
        private const val KEY_SECRET_IV = "secret_iv"
        private const val KEY_SECRET_CIPHERTEXT = "secret_ciphertext"
        private const val KEY_EXPIRES_AT = "expires_at"
        private const val KEY_GRANT_OWNER = "grant_owner"
        private const val KEY_GRANT_PURPOSES = "grant_purposes"
        private const val KEY_GRANT_SPACES = "grant_spaces"
        private const val KEY_GRANT_DATA_TYPES = "grant_data_types"
        private const val KEY_GRANT_OPERATIONS = "grant_operations"
        private const val KEY_GRANT_NOT_BEFORE = "grant_not_before"
        private const val KEY_GRANT_CREATED_AT = "grant_created_at"
        private const val SCOPE_SEPARATOR = "\u001f"
        private const val PAIRING_FILE = "agent-pairing/pairing.json"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val PAIRING_KEY_ALIAS = "ameme-agent-pairing-wrap-v1"
        private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
        private val PAIRING_DURATION: Duration = Duration.ofDays(30)
        private val PAIRING_STATE_KEYS = setOf(
            KEY_MATERIAL,
            KEY_SECRET_IV,
            KEY_SECRET_CIPHERTEXT,
            KEY_EXPIRES_AT,
            KEY_GRANT_OWNER,
            KEY_GRANT_PURPOSES,
            KEY_GRANT_SPACES,
            KEY_GRANT_DATA_TYPES,
            KEY_GRANT_OPERATIONS,
            KEY_GRANT_NOT_BEFORE,
            KEY_GRANT_CREATED_AT,
        )
    }
}

private class AndroidAgentTlsIdentity {
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    init {
        if (!keyStore.containsAlias(TLS_KEY_ALIAS)) {
            val now = Instant.now()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE).run {
                initialize(
                    KeyGenParameterSpec.Builder(
                        TLS_KEY_ALIAS,
                        KeyProperties.PURPOSE_SIGN or
                            KeyProperties.PURPOSE_VERIFY or
                            KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setKeySize(2048)
                        .setDigests(
                            KeyProperties.DIGEST_NONE,
                            KeyProperties.DIGEST_SHA256,
                            KeyProperties.DIGEST_SHA384,
                            KeyProperties.DIGEST_SHA512,
                        )
                        .setSignaturePaddings(
                            KeyProperties.SIGNATURE_PADDING_RSA_PSS,
                            KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,
                        )
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setCertificateSubject(X500Principal("CN=Ameme Local Node"))
                        .setCertificateSerialNumber(BigInteger(128, SecureRandom()).abs().add(BigInteger.ONE))
                        .setCertificateNotBefore(Date.from(now.minus(Duration.ofDays(1))))
                        .setCertificateNotAfter(Date.from(now.plus(Duration.ofDays(3650))))
                        .build(),
                )
                generateKeyPair()
            }
        }
    }

    fun certificatePin(): String {
        val certificate = requireNotNull(keyStore.getCertificate(TLS_KEY_ALIAS))
        return "sha256_" + MessageDigest.getInstance("SHA-256").digest(certificate.encoded).toHex()
    }

    fun sslServerSocketFactory(): SSLServerSocketFactory {
        val keyManager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManager.init(keyStore, null)
        return SSLContext.getInstance("TLSv1.3").apply {
            init(keyManager.keyManagers, null, SecureRandom())
        }.serverSocketFactory
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TLS_KEY_ALIAS = "ameme-agent-tls-v4"
    }
}
