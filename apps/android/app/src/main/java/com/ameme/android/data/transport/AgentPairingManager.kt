package com.ameme.android.data.transport

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.ameme.android.BuildConfig
import java.io.File
import java.math.BigInteger
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
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
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory
import javax.net.ssl.X509ExtendedKeyManager
import javax.security.auth.x500.X500Principal
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.ameme.android.data.transport.channel.AgentPairingEnvelope
import com.ameme.android.data.transport.channel.AgentPairingBootstrapV2Codec
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
    val developerChannelSecret: String?,
    val bootstrapId: String,
    val bootstrapSecret: String,
    val bootstrapExpiresAt: Instant,
    val expiresAt: Instant,
) {
    fun pairingJson(): String = AgentPairingManager.json.encodeToString(material)

    fun pairingQrPayload(): String = AgentPairingEnvelope.encode(
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
        bootstrapId = bootstrapId,
        bootstrapSecret = bootstrapSecret,
        expiresAt = bootstrapExpiresAt,
        pairingExpiresAt = expiresAt,
    )
}

enum class AgentPairingCredentialKind {
    Developer,
    DeviceBootstrapV2,
}

class ActiveAgentPairingCredential(
    val credentialId: String,
    val kind: AgentPairingCredentialKind,
    secret: ByteArray,
) : AutoCloseable {
    private val secretBytes = secret.copyOf()

    fun secretCopy(): ByteArray = secretBytes.copyOf()

    override fun close() = secretBytes.fill(0)
}

class ActiveAgentPairing(
    val material: AgentPairingMaterial,
    val credentials: List<ActiveAgentPairingCredential>,
    val expiresAt: Instant,
    val accessGrantPolicy: AgentAccessGrantPolicy,
) : AutoCloseable {
    init {
        require(credentials.map { it.credentialId }.distinct().size == credentials.size)
    }

    override fun close() = credentials.forEach(ActiveAgentPairingCredential::close)
}

class AgentPairingManager(
    private val context: Context,
    private val clock: Clock = Clock.systemUTC(),
    private val enableDeveloperCredential: Boolean = BuildConfig.DEBUG,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val identity by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { AndroidAgentTlsIdentity() }

    fun create(host: String = defaultHost(), port: Int = DEFAULT_PORT): CreatedAgentPairing {
        require(host.isNotBlank() && host.length <= 253 && port in 1..65_535)
        revoke()
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
        val developerChannelSecret = if (enableDeveloperCredential) {
            val developerSecretBytes = ByteArray(32).also(SecureRandom()::nextBytes)
            try {
                Base64.getUrlEncoder().withoutPadding().encodeToString(developerSecretBytes)
            } finally {
                developerSecretBytes.fill(0)
            }
        } else {
            null
        }
        val encryptedDeveloperCredential = developerChannelSecret?.let { secret ->
            encrypt(
                secret.encodeToByteArray(),
                developerCredentialAad(pairingId),
            )
        }
        val bootstrapId = "boot_${randomHex(16)}"
        val bootstrapSecretBytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val bootstrapSecret = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(bootstrapSecretBytes)
        val encryptedBootstrap = encrypt(
            bootstrapSecretBytes.copyOf(),
            bootstrapAad(pairingId, bootstrapId),
        )
        bootstrapSecretBytes.fill(0)
        val bootstrapExpiresAt = createdAt.plus(QR_ENVELOPE_DURATION)
        val developerCredentialId = if (enableDeveloperCredential) {
            "cred_dev_${randomHex(16)}"
        } else {
            null
        }
        val materialJson = json.encodeToString(material)
        val editor = preferences.edit()
            .putInt(KEY_STATE_SCHEMA_VERSION, STATE_SCHEMA_VERSION)
            .putString(KEY_MATERIAL, materialJson)
            .putString(KEY_BOOTSTRAP_ID, bootstrapId)
            .putString(KEY_BOOTSTRAP_STATE, BOOTSTRAP_STATE_PENDING)
            .putString(KEY_BOOTSTRAP_SECRET_IV, Base64.getEncoder().encodeToString(encryptedBootstrap.iv))
            .putString(
                KEY_BOOTSTRAP_SECRET_CIPHERTEXT,
                Base64.getEncoder().encodeToString(encryptedBootstrap.ciphertext),
            )
            .putLong(KEY_BOOTSTRAP_EXPIRES_AT, bootstrapExpiresAt.toEpochMilli())
            .putLong(KEY_EXPIRES_AT, expiresAt.toEpochMilli())
            .putString(KEY_GRANT_OWNER, accessGrantPolicy.ownerId)
            .putString(KEY_GRANT_PURPOSES, accessGrantPolicy.purposes.joinToString(SCOPE_SEPARATOR))
            .putString(KEY_GRANT_SPACES, accessGrantPolicy.spaces.joinToString(SCOPE_SEPARATOR))
            .putString(KEY_GRANT_DATA_TYPES, accessGrantPolicy.dataTypes.joinToString(SCOPE_SEPARATOR))
            .putString(KEY_GRANT_OPERATIONS, accessGrantPolicy.operations.joinToString(SCOPE_SEPARATOR))
            .putLong(KEY_GRANT_NOT_BEFORE, accessGrantPolicy.notBefore.toEpochMilli())
            .putLong(KEY_GRANT_CREATED_AT, accessGrantPolicy.createdAt.toEpochMilli())
        if (developerCredentialId != null && encryptedDeveloperCredential != null) {
            editor
                .putString(KEY_DEVELOPER_CREDENTIAL_ID, developerCredentialId)
                .putString(
                    KEY_SECRET_IV,
                    Base64.getEncoder().encodeToString(encryptedDeveloperCredential.iv),
                )
                .putString(
                    KEY_SECRET_CIPHERTEXT,
                    Base64.getEncoder().encodeToString(encryptedDeveloperCredential.ciphertext),
                )
        }
        val committed = editor.commit()
        encryptedDeveloperCredential?.clear()
        encryptedBootstrap.clear()
        check(committed) { "Could not persist Agent pairing" }
        try {
            writePairingMaterial(materialJson)
        } catch (writeFailure: Throwable) {
            try {
                revoke()
            } catch (convergenceFailure: Throwable) {
                convergenceFailure.addSuppressed(writeFailure)
                throw convergenceFailure
            }
            throw writeFailure
        }
        return CreatedAgentPairing(
            material = material,
            developerChannelSecret = developerChannelSecret,
            bootstrapId = bootstrapId,
            bootstrapSecret = bootstrapSecret,
            bootstrapExpiresAt = bootstrapExpiresAt,
            expiresAt = expiresAt,
        )
    }

    fun loadActive(): ActiveAgentPairing? {
        if (preferences.getInt(KEY_STATE_SCHEMA_VERSION, 0) != STATE_SCHEMA_VERSION) {
            if (PAIRING_STATE_KEYS.any(preferences::contains)) revoke()
            return null
        }
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
        cleanupExpiredBootstrapReceipt()
        val credentials = mutableListOf<ActiveAgentPairingCredential>()
        val developerCredentialId = preferences.getString(KEY_DEVELOPER_CREDENTIAL_ID, null)
        if (developerCredentialId != null) {
            val iv = preferences.getString(KEY_SECRET_IV, null)
                ?.let(Base64.getDecoder()::decode)
            val ciphertext = preferences.getString(KEY_SECRET_CIPHERTEXT, null)
                ?.let(Base64.getDecoder()::decode)
            if (iv == null || ciphertext == null) {
                iv?.fill(0)
                ciphertext?.fill(0)
                revoke()
                return null
            }
            val developerSecret = try {
                decrypt(iv, ciphertext, developerCredentialAad(material.pairingId))
            } catch (_: Exception) {
                revoke()
                return null
            } finally {
                iv.fill(0)
                ciphertext.fill(0)
            }
            if (!isChannelSecret(developerSecret)) {
                developerSecret.fill(0)
                revoke()
                return null
            }
            credentials += ActiveAgentPairingCredential(
                credentialId = developerCredentialId,
                kind = AgentPairingCredentialKind.Developer,
                secret = developerSecret,
            )
            developerSecret.fill(0)
        } else if (
            preferences.contains(KEY_SECRET_IV) ||
            preferences.contains(KEY_SECRET_CIPHERTEXT)
        ) {
            revoke()
            return null
        }
        if (preferences.contains(KEY_QR_CREDENTIAL_ID)) {
            val qrCredentialId = preferences.getString(KEY_QR_CREDENTIAL_ID, null)
            val qrIv = preferences.getString(KEY_QR_CREDENTIAL_SECRET_IV, null)
                ?.let(Base64.getDecoder()::decode)
            val qrCiphertext = preferences.getString(KEY_QR_CREDENTIAL_SECRET_CIPHERTEXT, null)
                ?.let(Base64.getDecoder()::decode)
            if (qrCredentialId == null || qrIv == null || qrCiphertext == null) {
                credentials.forEach(ActiveAgentPairingCredential::close)
                qrIv?.fill(0)
                qrCiphertext?.fill(0)
                revoke()
                return null
            }
            val qrSecret = try {
                decrypt(
                    qrIv,
                    qrCiphertext,
                    qrCredentialAad(material.pairingId, qrCredentialId),
                )
            } catch (_: Exception) {
                credentials.forEach(ActiveAgentPairingCredential::close)
                revoke()
                return null
            } finally {
                qrIv.fill(0)
                qrCiphertext.fill(0)
            }
            if (!isChannelSecret(qrSecret)) {
                qrSecret.fill(0)
                credentials.forEach(ActiveAgentPairingCredential::close)
                revoke()
                return null
            }
            credentials += ActiveAgentPairingCredential(
                credentialId = qrCredentialId,
                kind = AgentPairingCredentialKind.DeviceBootstrapV2,
                secret = qrSecret,
            )
            qrSecret.fill(0)
        }
        return ActiveAgentPairing(material, credentials, expiresAt, accessGrantPolicy)
    }

    /**
     * Atomically consumes the short-lived QR bootstrap and issues one device-bound channel
     * credential. A bounded same-key receipt permits retry when the committed response is lost.
     */
    @Synchronized
    fun provisionBootstrap(clientHelloLine: ByteArray): ByteArray {
        check(preferences.getInt(KEY_STATE_SCHEMA_VERSION, 0) == STATE_SCHEMA_VERSION) {
            "pairing_bootstrap_unavailable"
        }
        val now = clock.instant()
        val materialJson = preferences.getString(KEY_MATERIAL, null)
            ?: error("pairing_bootstrap_unavailable")
        val material = runCatching {
            json.decodeFromString<AgentPairingMaterial>(materialJson)
        }.getOrElse {
            revoke()
            error("pairing_bootstrap_unavailable")
        }
        val pairingExpiresAt = Instant.ofEpochMilli(
            preferences.getLong(KEY_EXPIRES_AT, 0L),
        )
        check(pairingExpiresAt.isAfter(now)) { "pairing_bootstrap_expired" }
        val bootstrapId = preferences.getString(KEY_BOOTSTRAP_ID, null)
            ?: error("pairing_bootstrap_unavailable")
        val state = preferences.getString(KEY_BOOTSTRAP_STATE, null)
            ?: error("pairing_bootstrap_unavailable")
        val bootstrapExpiresAt = Instant.ofEpochMilli(
            preferences.getLong(KEY_BOOTSTRAP_EXPIRES_AT, 0L),
        )
        val receiptExpiresAt = Instant.ofEpochMilli(
            preferences.getLong(KEY_BOOTSTRAP_RECEIPT_EXPIRES_AT, 0L),
        )
        val allowedUntil = if (state == BOOTSTRAP_STATE_CONSUMED) {
            receiptExpiresAt
        } else {
            bootstrapExpiresAt
        }
        if (!allowedUntil.isAfter(now)) {
            cleanupBootstrapMaterial()
            error("pairing_bootstrap_expired")
        }
        val bootstrapIv = preferences.getString(KEY_BOOTSTRAP_SECRET_IV, null)
            ?.let(Base64.getDecoder()::decode)
            ?: error("pairing_bootstrap_unavailable")
        val bootstrapCiphertext = preferences.getString(KEY_BOOTSTRAP_SECRET_CIPHERTEXT, null)
            ?.let(Base64.getDecoder()::decode)
            ?: run {
                bootstrapIv.fill(0)
                error("pairing_bootstrap_unavailable")
            }
        val bootstrapSecret = try {
            decrypt(
                bootstrapIv,
                bootstrapCiphertext,
                bootstrapAad(material.pairingId, bootstrapId),
            )
        } finally {
            bootstrapIv.fill(0)
            bootstrapCiphertext.fill(0)
        }
        check(bootstrapSecret.size == 32) {
            bootstrapSecret.fill(0)
            "pairing_bootstrap_unavailable"
        }
        val channelPairing = material.toChannelPairing()
        val clientHello = try {
            AgentPairingBootstrapV2Codec.verifyClientHello(
                line = clientHelloLine,
                pairing = channelPairing,
                expectedBootstrapId = bootstrapId,
                bootstrapSecret = bootstrapSecret,
            )
        } catch (failure: Throwable) {
            bootstrapSecret.fill(0)
            throw failure
        }
        return try {
            clientHello.use { verified ->
                val clientPublicKey = verified.clientPublicKeyCopy()
                val clientPublicKeyText = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(clientPublicKey)
                clientPublicKey.fill(0)
                val credentialId: String
                val credentialSecret: String
                if (state == BOOTSTRAP_STATE_PENDING) {
                    credentialId = "cred_qr_${randomHex(16)}"
                    val credentialEntropy = ByteArray(32).also(SecureRandom()::nextBytes)
                    credentialSecret = Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(credentialEntropy)
                    credentialEntropy.fill(0)
                    val encryptedCredential = encrypt(
                        credentialSecret.encodeToByteArray(),
                        qrCredentialAad(material.pairingId, credentialId),
                    )
                    val committed = preferences.edit()
                        .putString(KEY_BOOTSTRAP_STATE, BOOTSTRAP_STATE_CONSUMED)
                        .putString(KEY_BOOTSTRAP_CLIENT_KEY_THUMBPRINT, verified.clientKeyThumbprint)
                        .putString(KEY_BOOTSTRAP_CLIENT_PUBLIC_KEY, clientPublicKeyText)
                        .putLong(
                            KEY_BOOTSTRAP_RECEIPT_EXPIRES_AT,
                            minOf(pairingExpiresAt, now.plus(BOOTSTRAP_RETRY_DURATION)).toEpochMilli(),
                        )
                        .putString(KEY_QR_CREDENTIAL_ID, credentialId)
                        .putString(
                            KEY_QR_CREDENTIAL_SECRET_IV,
                            Base64.getEncoder().encodeToString(encryptedCredential.iv),
                        )
                        .putString(
                            KEY_QR_CREDENTIAL_SECRET_CIPHERTEXT,
                            Base64.getEncoder().encodeToString(encryptedCredential.ciphertext),
                        )
                        .commit()
                    encryptedCredential.clear()
                    check(committed) { "pairing_bootstrap_commit_failed" }
                } else {
                    check(state == BOOTSTRAP_STATE_CONSUMED) {
                        "pairing_bootstrap_unavailable"
                    }
                    val expectedThumbprint =
                        preferences.getString(KEY_BOOTSTRAP_CLIENT_KEY_THUMBPRINT, null)
                    val expectedPublicKey =
                        preferences.getString(KEY_BOOTSTRAP_CLIENT_PUBLIC_KEY, null)
                    check(
                        expectedThumbprint != null &&
                            expectedPublicKey != null &&
                            MessageDigest.isEqual(
                                expectedThumbprint.encodeToByteArray(),
                                verified.clientKeyThumbprint.encodeToByteArray(),
                            ) &&
                            MessageDigest.isEqual(
                                expectedPublicKey.encodeToByteArray(),
                                clientPublicKeyText.encodeToByteArray(),
                            )
                    ) { "pairing_bootstrap_already_consumed" }
                    credentialId = preferences.getString(KEY_QR_CREDENTIAL_ID, null)
                        ?: error("pairing_bootstrap_unavailable")
                    val credentialIv =
                        preferences.getString(KEY_QR_CREDENTIAL_SECRET_IV, null)
                            ?.let(Base64.getDecoder()::decode)
                            ?: error("pairing_bootstrap_unavailable")
                    val credentialCiphertext =
                        preferences.getString(KEY_QR_CREDENTIAL_SECRET_CIPHERTEXT, null)
                            ?.let(Base64.getDecoder()::decode)
                            ?: run {
                                credentialIv.fill(0)
                                error("pairing_bootstrap_unavailable")
                            }
                    val credentialBytes = try {
                        decrypt(
                            credentialIv,
                            credentialCiphertext,
                            qrCredentialAad(material.pairingId, credentialId),
                        )
                    } finally {
                        credentialIv.fill(0)
                        credentialCiphertext.fill(0)
                    }
                    check(isChannelSecret(credentialBytes)) {
                        credentialBytes.fill(0)
                        "pairing_bootstrap_unavailable"
                    }
                    credentialSecret = credentialBytes.decodeToString()
                    credentialBytes.fill(0)
                }
                AgentPairingBootstrapV2Codec.buildServerHello(
                    clientHello = verified,
                    pairing = channelPairing,
                    bootstrapSecret = bootstrapSecret,
                    serverNonce = "nonce_${randomHex(32)}",
                    credentialId = credentialId,
                    credentialSecret = credentialSecret,
                    credentialExpiresAt = pairingExpiresAt,
                )
            }
        } finally {
            bootstrapSecret.fill(0)
        }
    }

    fun revoke() {
        val committed = preferences.edit()
            .remove(KEY_STATE_SCHEMA_VERSION)
            .remove(KEY_MATERIAL)
            .remove(KEY_DEVELOPER_CREDENTIAL_ID)
            .remove(KEY_SECRET_IV)
            .remove(KEY_SECRET_CIPHERTEXT)
            .remove(KEY_BOOTSTRAP_ID)
            .remove(KEY_BOOTSTRAP_STATE)
            .remove(KEY_BOOTSTRAP_SECRET_IV)
            .remove(KEY_BOOTSTRAP_SECRET_CIPHERTEXT)
            .remove(KEY_BOOTSTRAP_EXPIRES_AT)
            .remove(KEY_BOOTSTRAP_RECEIPT_EXPIRES_AT)
            .remove(KEY_BOOTSTRAP_CLIENT_KEY_THUMBPRINT)
            .remove(KEY_BOOTSTRAP_CLIENT_PUBLIC_KEY)
            .remove(KEY_QR_CREDENTIAL_ID)
            .remove(KEY_QR_CREDENTIAL_SECRET_IV)
            .remove(KEY_QR_CREDENTIAL_SECRET_CIPHERTEXT)
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

    private fun cleanupExpiredBootstrapReceipt() {
        val state = preferences.getString(KEY_BOOTSTRAP_STATE, null) ?: return
        val expiryKey = if (state == BOOTSTRAP_STATE_CONSUMED) {
            KEY_BOOTSTRAP_RECEIPT_EXPIRES_AT
        } else {
            KEY_BOOTSTRAP_EXPIRES_AT
        }
        val expiry = runCatching {
            Instant.ofEpochMilli(preferences.getLong(expiryKey, 0L))
        }.getOrNull()
        if (expiry == null || !expiry.isAfter(clock.instant())) {
            cleanupBootstrapMaterial()
        }
    }

    private fun cleanupBootstrapMaterial() {
        val committed = preferences.edit()
            .remove(KEY_BOOTSTRAP_ID)
            .remove(KEY_BOOTSTRAP_STATE)
            .remove(KEY_BOOTSTRAP_SECRET_IV)
            .remove(KEY_BOOTSTRAP_SECRET_CIPHERTEXT)
            .remove(KEY_BOOTSTRAP_EXPIRES_AT)
            .remove(KEY_BOOTSTRAP_RECEIPT_EXPIRES_AT)
            .remove(KEY_BOOTSTRAP_CLIENT_KEY_THUMBPRINT)
            .remove(KEY_BOOTSTRAP_CLIENT_PUBLIC_KEY)
            .commit()
        check(committed) { "Could not clear expired Agent bootstrap" }
    }

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

    private fun isChannelSecret(value: ByteArray): Boolean {
        if (value.size !in 43..44) return false
        val text = value.toString(Charsets.US_ASCII)
        if (
            text.any {
                it !in 'A'..'Z' &&
                    it !in 'a'..'z' &&
                    it !in '0'..'9' &&
                    it != '-' &&
                    it != '_'
            }
        ) {
            return false
        }
        val decoded = runCatching { Base64.getUrlDecoder().decode(text) }.getOrNull()
            ?: return false
        return try {
            decoded.size == 32
        } finally {
            decoded.fill(0)
        }
    }

    private fun AgentPairingMaterial.toChannelPairing(): AndroidLocalNodePairingMaterial =
        AndroidLocalNodePairingMaterial(
            channelProtocol = channelProtocol,
            endpointRef = endpointRef,
            credentialRef = credentialRef,
            expectedDeviceId = expectedDeviceId,
            sessionBindingRef = sessionBindingRef,
            pairingId = pairingId,
            host = host,
            port = port,
            tlsCertificateSha256 = tlsCertificateSha256,
        )

    private fun developerCredentialAad(pairingId: String): String =
        "developer-credential:$pairingId"

    private fun bootstrapAad(pairingId: String, bootstrapId: String): String =
        "bootstrap:$pairingId:$bootstrapId"

    private fun qrCredentialAad(pairingId: String, credentialId: String): String =
        "qr-credential:$pairingId:$credentialId"

    private fun randomHex(byteCount: Int): String {
        val bytes = ByteArray(byteCount).also(SecureRandom()::nextBytes)
        return try {
            bytes.joinToString("") { "%02x".format(it) }
        } finally {
            bytes.fill(0)
        }
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
        private const val STATE_SCHEMA_VERSION = 2
        private const val KEY_STATE_SCHEMA_VERSION = "state_schema_version"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_MATERIAL = "material"
        private const val KEY_DEVELOPER_CREDENTIAL_ID = "developer_credential_id"
        private const val KEY_SECRET_IV = "secret_iv"
        private const val KEY_SECRET_CIPHERTEXT = "secret_ciphertext"
        private const val KEY_BOOTSTRAP_ID = "bootstrap_id"
        private const val KEY_BOOTSTRAP_STATE = "bootstrap_state"
        private const val KEY_BOOTSTRAP_SECRET_IV = "bootstrap_secret_iv"
        private const val KEY_BOOTSTRAP_SECRET_CIPHERTEXT = "bootstrap_secret_ciphertext"
        private const val KEY_BOOTSTRAP_EXPIRES_AT = "bootstrap_expires_at"
        private const val KEY_BOOTSTRAP_RECEIPT_EXPIRES_AT = "bootstrap_receipt_expires_at"
        private const val KEY_BOOTSTRAP_CLIENT_KEY_THUMBPRINT = "bootstrap_client_key_thumbprint"
        private const val KEY_BOOTSTRAP_CLIENT_PUBLIC_KEY = "bootstrap_client_public_key"
        private const val KEY_QR_CREDENTIAL_ID = "qr_credential_id"
        private const val KEY_QR_CREDENTIAL_SECRET_IV = "qr_credential_secret_iv"
        private const val KEY_QR_CREDENTIAL_SECRET_CIPHERTEXT = "qr_credential_secret_ciphertext"
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
        private const val BOOTSTRAP_STATE_PENDING = "pending"
        private const val BOOTSTRAP_STATE_CONSUMED = "consumed"
        private val PAIRING_DURATION: Duration = Duration.ofDays(30)
        private val QR_ENVELOPE_DURATION: Duration = Duration.ofMinutes(5)
        private val BOOTSTRAP_RETRY_DURATION: Duration = Duration.ofMinutes(5)
        private val PAIRING_STATE_KEYS = setOf(
            KEY_STATE_SCHEMA_VERSION,
            KEY_MATERIAL,
            KEY_DEVELOPER_CREDENTIAL_ID,
            KEY_SECRET_IV,
            KEY_SECRET_CIPHERTEXT,
            KEY_BOOTSTRAP_ID,
            KEY_BOOTSTRAP_STATE,
            KEY_BOOTSTRAP_SECRET_IV,
            KEY_BOOTSTRAP_SECRET_CIPHERTEXT,
            KEY_BOOTSTRAP_EXPIRES_AT,
            KEY_BOOTSTRAP_RECEIPT_EXPIRES_AT,
            KEY_BOOTSTRAP_CLIENT_KEY_THUMBPRINT,
            KEY_BOOTSTRAP_CLIENT_PUBLIC_KEY,
            KEY_QR_CREDENTIAL_ID,
            KEY_QR_CREDENTIAL_SECRET_IV,
            KEY_QR_CREDENTIAL_SECRET_CIPHERTEXT,
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
        val keyManagerFactory =
            KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keyManagerFactory.init(keyStore, null)
        val delegate = keyManagerFactory.keyManagers
            .filterIsInstance<X509ExtendedKeyManager>()
            .singleOrNull()
            ?: error("android_agent_tls_key_manager_unavailable")
        val keyManager = AliasPinnedServerKeyManager(delegate, TLS_KEY_ALIAS)
        return SSLContext.getInstance("TLSv1.3").apply {
            init(arrayOf(keyManager), null, SecureRandom())
        }.serverSocketFactory
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TLS_KEY_ALIAS = "ameme-agent-tls-v4"
    }
}

/**
 * Android Keystore may contain unrelated EC client-possession keys. Restrict the Local Node
 * server to its dedicated RSA identity so TLS alias selection cannot cross credential domains.
 */
private class AliasPinnedServerKeyManager(
    private val delegate: X509ExtendedKeyManager,
    private val serverAlias: String,
) : X509ExtendedKeyManager() {
    override fun getClientAliases(
        keyType: String?,
        issuers: Array<out Principal>?,
    ): Array<String>? = null

    override fun chooseClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String? = null

    override fun chooseEngineClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        engine: SSLEngine?,
    ): String? = null

    override fun getServerAliases(
        keyType: String?,
        issuers: Array<out Principal>?,
    ): Array<String>? =
        serverAlias.takeIf { supports(keyType, issuers) }?.let { arrayOf(it) }

    override fun chooseServerAlias(
        keyType: String?,
        issuers: Array<out Principal>?,
        socket: Socket?,
    ): String? = serverAlias.takeIf { supports(keyType, issuers) }

    override fun chooseEngineServerAlias(
        keyType: String?,
        issuers: Array<out Principal>?,
        engine: SSLEngine?,
    ): String? = serverAlias.takeIf { supports(keyType, issuers) }

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
        alias.takeIf { it == serverAlias }?.let(delegate::getCertificateChain)

    override fun getPrivateKey(alias: String?): PrivateKey? =
        alias.takeIf { it == serverAlias }?.let(delegate::getPrivateKey)

    private fun supports(
        keyType: String?,
        issuers: Array<out Principal>?,
    ): Boolean =
        keyType != null &&
            delegate.getServerAliases(keyType, issuers)
                ?.contains(serverAlias) == true &&
            delegate.getPrivateKey(serverAlias) != null &&
            !delegate.getCertificateChain(serverAlias).isNullOrEmpty()
}
