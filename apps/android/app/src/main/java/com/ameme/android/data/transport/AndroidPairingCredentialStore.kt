package com.ameme.android.data.transport

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.ameme.android.data.transport.channel.AndroidLocalNodeChannelCodec
import com.ameme.android.data.transport.channel.AndroidLocalNodePairingMaterial
import com.ameme.android.data.transport.channel.AndroidPairingClientKey
import com.ameme.android.data.transport.channel.AndroidPairingIssuedCredential
import com.ameme.android.data.transport.channel.AgentPairingEnvelope
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.time.Clock
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class AndroidPendingPairingCredential(
    val pairingId: String,
    val pairingPayload: String,
    val clientKeyThumbprint: String,
)

class AndroidStoredPairingCredential(
    val pairing: AndroidLocalNodePairingMaterial,
    val credentialId: String,
    channelSecret: ByteArray,
    val clientKeyThumbprint: String,
    val expiresAt: Instant,
) : AutoCloseable {
    private val secret = channelSecret.copyOf()

    fun channelSecretCopy(): ByteArray = secret.copyOf()

    override fun close() = secret.fill(0)

    override fun toString(): String =
        "AndroidStoredPairingCredential(pairing=<redacted>, credential=<redacted>)"
}

interface AndroidPairingCredentialStoring {
    fun savePending(
        pairingPayload: String,
        now: Instant = Instant.now(),
    ): AndroidPendingPairingCredential

    fun loadPending(
        pairingId: String? = null,
        now: Instant = Instant.now(),
    ): AndroidPendingPairingCredential?

    fun clientKey(
        pending: AndroidPendingPairingCredential,
    ): AndroidPairingClientKey

    fun saveActive(issued: AndroidPairingIssuedCredential)

    fun loadActive(now: Instant = Instant.now()): AndroidStoredPairingCredential?

    fun clearAndVerify()
}

enum class AndroidPairingCredentialStoreFailure {
    Unavailable,
    Corrupt,
    InvalidState,
}

class AndroidPairingCredentialStoreException(
    val failure: AndroidPairingCredentialStoreFailure,
    cause: Throwable? = null,
) : IllegalStateException("android_pairing_credential_${failure.name.lowercase()}", cause)

/**
 * Holds exactly one current-install Android QR client credential.
 *
 * The P-256 private key and AES wrapping key are non-exportable Android Keystore entries. The
 * short-lived bootstrap or issued application secret exists on disk only inside an AES-GCM record
 * under noBackupFilesDir. The record contains no Event, query, locator, Grant, or access audit.
 */
class AndroidKeystorePairingCredentialStore(
    context: Context,
    private val clock: Clock = Clock.systemUTC(),
) : AndroidPairingCredentialStoring {
    private val atomicFile = AtomicFile(
        java.io.File(
            context.applicationContext.noBackupFilesDir,
            RECORD_FILE_NAME,
        ),
    )
    private val lock = Any()

    override fun savePending(
        pairingPayload: String,
        now: Instant,
    ): AndroidPendingPairingCredential = synchronized(lock) {
        val parsed = try {
            AgentPairingEnvelope.parse(pairingPayload, now)
        } catch (failure: Throwable) {
            throw AndroidPairingCredentialStoreException(
                AndroidPairingCredentialStoreFailure.InvalidState,
                failure,
            )
        }
        parsed.use { envelope ->
            clearInternal(deleteWrappingKey = true)
            val key = try {
                createSigningKey()
            } catch (failure: Throwable) {
                clearInternal(deleteWrappingKey = true)
                throw AndroidPairingCredentialStoreException(
                    AndroidPairingCredentialStoreFailure.Unavailable,
                    failure,
                )
            }
            val publicKey = x963(key.public as ECPublicKey)
            val thumbprint = digest(publicKey)
            publicKey.fill(0)
            val state = StoredState(
                schemaVersion = SCHEMA_VERSION,
                state = STATE_PENDING,
                pairingId = envelope.pairing.pairingId,
                pairingPayload = pairingPayload,
                pairingCanonicalBase64 = null,
                credentialId = null,
                channelSecretBase64 = null,
                clientKeyThumbprint = thumbprint,
                expiresAtMilliseconds = envelope.expiresAt.toEpochMilli(),
            )
            try {
                writeState(state)
            } catch (failure: Throwable) {
                clearInternal(deleteWrappingKey = true)
                throw failure
            }
            AndroidPendingPairingCredential(
                pairingId = envelope.pairing.pairingId,
                pairingPayload = pairingPayload,
                clientKeyThumbprint = thumbprint,
            )
        }
    }

    override fun loadPending(
        pairingId: String?,
        now: Instant,
    ): AndroidPendingPairingCredential? = synchronized(lock) {
        val state = readStateOrClear() ?: return@synchronized null
        if (state.state != STATE_PENDING) return@synchronized null
        val payload = state.pairingPayload ?: return@synchronized corrupt()
        if (
            state.pairingCanonicalBase64 != null ||
            state.credentialId != null ||
            state.channelSecretBase64 != null
        ) {
            return@synchronized corrupt()
        }
        val envelope = try {
            AgentPairingEnvelope.parse(payload, now)
        } catch (_: Throwable) {
            clearInternal(deleteWrappingKey = true)
            return@synchronized null
        }
        envelope.use {
            if (
                state.pairingId != it.pairing.pairingId ||
                state.expiresAtMilliseconds != it.expiresAt.toEpochMilli() ||
                pairingId != null && pairingId != it.pairing.pairingId
            ) {
                return@synchronized if (pairingId != null && pairingId != it.pairing.pairingId) {
                    null
                } else {
                    corrupt()
                }
            }
            val key = validatedClientKey(state.clientKeyThumbprint)
            check(key.thumbprint() == state.clientKeyThumbprint)
            AndroidPendingPairingCredential(
                pairingId = it.pairing.pairingId,
                pairingPayload = payload,
                clientKeyThumbprint = state.clientKeyThumbprint,
            )
        }
    }

    override fun clientKey(
        pending: AndroidPendingPairingCredential,
    ): AndroidPairingClientKey = synchronized(lock) {
        val stored = loadPending(pending.pairingId, clock.instant())
            ?: throw AndroidPairingCredentialStoreException(
                AndroidPairingCredentialStoreFailure.InvalidState,
            )
        if (
            stored.pairingPayload != pending.pairingPayload ||
            stored.clientKeyThumbprint != pending.clientKeyThumbprint
        ) {
            throw AndroidPairingCredentialStoreException(
                AndroidPairingCredentialStoreFailure.InvalidState,
            )
        }
        validatedClientKey(stored.clientKeyThumbprint)
    }

    override fun saveActive(issued: AndroidPairingIssuedCredential) = synchronized(lock) {
        val pending = loadPending(issued.pairing.pairingId, clock.instant())
            ?: throw AndroidPairingCredentialStoreException(
                AndroidPairingCredentialStoreFailure.InvalidState,
            )
        if (
            pending.clientKeyThumbprint != issued.clientKeyThumbprint ||
            !issued.expiresAt.isAfter(clock.instant())
        ) {
            throw AndroidPairingCredentialStoreException(
                AndroidPairingCredentialStoreFailure.InvalidState,
            )
        }
        val secret = issued.channelSecretCopy()
        try {
            requireChannelSecret(secret)
            writeState(
                StoredState(
                    schemaVersion = SCHEMA_VERSION,
                    state = STATE_ACTIVE,
                    pairingId = issued.pairing.pairingId,
                    pairingPayload = null,
                    pairingCanonicalBase64 = Base64.getEncoder().encodeToString(
                        issued.pairing.canonicalBytes(),
                    ),
                    credentialId = issued.credentialId,
                    channelSecretBase64 = Base64.getEncoder().encodeToString(secret),
                    clientKeyThumbprint = issued.clientKeyThumbprint,
                    expiresAtMilliseconds = issued.expiresAt.toEpochMilli(),
                ),
            )
        } catch (failure: Throwable) {
            throw if (failure is AndroidPairingCredentialStoreException) {
                failure
            } else {
                AndroidPairingCredentialStoreException(
                    AndroidPairingCredentialStoreFailure.Unavailable,
                    failure,
                )
            }
        } finally {
            secret.fill(0)
        }
    }

    override fun loadActive(now: Instant): AndroidStoredPairingCredential? = synchronized(lock) {
        val state = readStateOrClear() ?: return@synchronized null
        if (state.state != STATE_ACTIVE) return@synchronized null
        if (
            state.pairingPayload != null ||
            state.expiresAtMilliseconds <= now.toEpochMilli()
        ) {
            clearInternal(deleteWrappingKey = true)
            return@synchronized null
        }
        val pairingBytes = state.pairingCanonicalBase64
            ?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
            ?: return@synchronized corrupt()
        val secret = state.channelSecretBase64
            ?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
            ?: run {
                pairingBytes.fill(0)
                return@synchronized corrupt()
            }
        try {
            val pairing = AndroidLocalNodeChannelCodec.parsePairingMaterial(pairingBytes)
            val credentialId = state.credentialId
                ?.takeIf(IDENTIFIER::matches)
                ?: return@synchronized corrupt()
            if (pairing.pairingId != state.pairingId) return@synchronized corrupt()
            requireChannelSecret(secret)
            validatedClientKey(state.clientKeyThumbprint)
            AndroidStoredPairingCredential(
                pairing = pairing,
                credentialId = credentialId,
                channelSecret = secret,
                clientKeyThumbprint = state.clientKeyThumbprint,
                expiresAt = Instant.ofEpochMilli(state.expiresAtMilliseconds),
            )
        } catch (failure: Throwable) {
            if (failure is AndroidPairingCredentialStoreException) throw failure
            corrupt()
        } finally {
            pairingBytes.fill(0)
            secret.fill(0)
        }
    }

    override fun clearAndVerify() = synchronized(lock) {
        clearInternal(deleteWrappingKey = true)
        val keyStore = loadKeyStore()
        if (
            atomicFile.baseFile.exists() ||
            keyStore.containsAlias(SIGNING_KEY_ALIAS) ||
            keyStore.containsAlias(WRAPPING_KEY_ALIAS)
        ) {
            throw AndroidPairingCredentialStoreException(
                AndroidPairingCredentialStoreFailure.Unavailable,
            )
        }
    }

    private fun readStateOrClear(): StoredState? {
        if (!atomicFile.baseFile.exists()) return null
        val encoded = try {
            atomicFile.openRead().use { it.readBytes() }
        } catch (failure: Throwable) {
            clearInternal(deleteWrappingKey = true)
            throw AndroidPairingCredentialStoreException(
                AndroidPairingCredentialStoreFailure.Unavailable,
                failure,
            )
        }
        val encrypted = try {
            json.decodeFromString<EncryptedRecord>(encoded.decodeToString())
        } catch (failure: Throwable) {
            encoded.fill(0)
            clearInternal(deleteWrappingKey = true)
            throw AndroidPairingCredentialStoreException(
                AndroidPairingCredentialStoreFailure.Corrupt,
                failure,
            )
        }
        encoded.fill(0)
        if (encrypted.schemaVersion != SCHEMA_VERSION) return corrupt()
        val iv = runCatching { Base64.getDecoder().decode(encrypted.ivBase64) }.getOrNull()
            ?: return corrupt()
        val ciphertext = runCatching {
            Base64.getDecoder().decode(encrypted.ciphertextBase64)
        }.getOrNull() ?: run {
            iv.fill(0)
            return corrupt()
        }
        val cleartext = try {
            decrypt(iv, ciphertext)
        } catch (failure: Throwable) {
            clearInternal(deleteWrappingKey = true)
            throw AndroidPairingCredentialStoreException(
                AndroidPairingCredentialStoreFailure.Corrupt,
                failure,
            )
        } finally {
            iv.fill(0)
            ciphertext.fill(0)
        }
        return try {
            json.decodeFromString<StoredState>(cleartext.decodeToString()).also {
                if (
                    it.schemaVersion != SCHEMA_VERSION ||
                    it.state !in setOf(STATE_PENDING, STATE_ACTIVE) ||
                    !IDENTIFIER.matches(it.pairingId) ||
                    !DIGEST.matches(it.clientKeyThumbprint) ||
                    it.expiresAtMilliseconds <= 0
                ) {
                    corrupt()
                }
            }
        } catch (failure: Throwable) {
            clearInternal(deleteWrappingKey = true)
            if (failure is AndroidPairingCredentialStoreException) throw failure
            throw AndroidPairingCredentialStoreException(
                AndroidPairingCredentialStoreFailure.Corrupt,
                failure,
            )
        } finally {
            cleartext.fill(0)
        }
    }

    private fun writeState(state: StoredState) {
        val cleartext = json.encodeToString(state).encodeToByteArray()
        val encrypted = try {
            encrypt(cleartext)
        } finally {
            cleartext.fill(0)
        }
        val encoded = json.encodeToString(encrypted).encodeToByteArray()
        val output = try {
            atomicFile.startWrite()
        } catch (failure: Throwable) {
            encoded.fill(0)
            throw AndroidPairingCredentialStoreException(
                AndroidPairingCredentialStoreFailure.Unavailable,
                failure,
            )
        }
        try {
            output.write(encoded)
            atomicFile.finishWrite(output)
        } catch (failure: Throwable) {
            atomicFile.failWrite(output)
            throw AndroidPairingCredentialStoreException(
                AndroidPairingCredentialStoreFailure.Unavailable,
                failure,
            )
        } finally {
            encoded.fill(0)
        }
    }

    private fun encrypt(cleartext: ByteArray): EncryptedRecord {
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey(create = true))
        cipher.updateAAD(AAD)
        val ciphertext = cipher.doFinal(cleartext)
        return try {
            EncryptedRecord(
                schemaVersion = SCHEMA_VERSION,
                ivBase64 = Base64.getEncoder().encodeToString(cipher.iv),
                ciphertextBase64 = Base64.getEncoder().encodeToString(ciphertext),
            )
        } finally {
            ciphertext.fill(0)
        }
    }

    private fun decrypt(iv: ByteArray, ciphertext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            wrappingKey(create = false),
            GCMParameterSpec(128, iv),
        )
        cipher.updateAAD(AAD)
        return cipher.doFinal(ciphertext)
    }

    private fun validatedClientKey(expectedThumbprint: String): AndroidPairingClientKey {
        val keyStore = loadKeyStore()
        val certificate = keyStore.getCertificate(SIGNING_KEY_ALIAS)
            ?: return corrupt()
        val publicKey = certificate.publicKey as? ECPublicKey ?: return corrupt()
        val x963 = x963(publicKey)
        val actualThumbprint = digest(x963)
        x963.fill(0)
        if (
            !MessageDigest.isEqual(
                expectedThumbprint.encodeToByteArray(),
                actualThumbprint.encodeToByteArray(),
            )
        ) {
            return corrupt()
        }
        return KeystoreClientKey(expectedThumbprint)
    }

    private inner class KeystoreClientKey(
        private val expectedThumbprint: String,
    ) : AndroidPairingClientKey {
        override fun publicKeyX963(): ByteArray {
            val key = loadKeyStore().getCertificate(SIGNING_KEY_ALIAS)?.publicKey as? ECPublicKey
                ?: corrupt()
            return x963(key)
        }

        override fun thumbprint(): String = expectedThumbprint

        override fun signPossession(payload: ByteArray): ByteArray {
            val privateKey = loadKeyStore().getKey(SIGNING_KEY_ALIAS, null) as? PrivateKey
                ?: corrupt()
            return try {
                Signature.getInstance("SHA256withECDSA").run {
                    initSign(privateKey)
                    update(payload)
                    sign()
                }
            } catch (failure: Throwable) {
                throw AndroidPairingCredentialStoreException(
                    AndroidPairingCredentialStoreFailure.Unavailable,
                    failure,
                )
            }
        }
    }

    private fun createSigningKey() = try {
        KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEYSTORE,
        ).run {
            initialize(
                KeyGenParameterSpec.Builder(
                    SIGNING_KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                )
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build(),
            )
            generateKeyPair()
        }
    } catch (failure: Throwable) {
        throw AndroidPairingCredentialStoreException(
            AndroidPairingCredentialStoreFailure.Unavailable,
            failure,
        )
    }

    private fun wrappingKey(create: Boolean): SecretKey {
        val keyStore = loadKeyStore()
        (keyStore.getKey(WRAPPING_KEY_ALIAS, null) as? SecretKey)?.let { return it }
        if (!create) {
            throw AndroidPairingCredentialStoreException(
                AndroidPairingCredentialStoreFailure.Unavailable,
            )
        }
        return try {
            KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE,
            ).run {
                init(
                    KeyGenParameterSpec.Builder(
                        WRAPPING_KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setRandomizedEncryptionRequired(true)
                        .build(),
                )
                generateKey()
            }
        } catch (failure: Throwable) {
            throw AndroidPairingCredentialStoreException(
                AndroidPairingCredentialStoreFailure.Unavailable,
                failure,
            )
        }
    }

    private fun loadKeyStore(): KeyStore = try {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    } catch (failure: Throwable) {
        throw AndroidPairingCredentialStoreException(
            AndroidPairingCredentialStoreFailure.Unavailable,
            failure,
        )
    }

    private fun clearInternal(deleteWrappingKey: Boolean) {
        atomicFile.delete()
        val keyStore = loadKeyStore()
        runCatching {
            if (keyStore.containsAlias(SIGNING_KEY_ALIAS)) {
                keyStore.deleteEntry(SIGNING_KEY_ALIAS)
            }
            if (deleteWrappingKey && keyStore.containsAlias(WRAPPING_KEY_ALIAS)) {
                keyStore.deleteEntry(WRAPPING_KEY_ALIAS)
            }
        }.getOrElse { failure ->
            throw AndroidPairingCredentialStoreException(
                AndroidPairingCredentialStoreFailure.Unavailable,
                failure,
            )
        }
    }

    private fun requireChannelSecret(secret: ByteArray) {
        if (secret.size !in 43..44) {
            throw AndroidPairingCredentialStoreException(
                AndroidPairingCredentialStoreFailure.InvalidState,
            )
        }
        val decoded = runCatching {
            Base64.getUrlDecoder().decode(secret.decodeToString())
        }.getOrNull() ?: throw AndroidPairingCredentialStoreException(
            AndroidPairingCredentialStoreFailure.InvalidState,
        )
        try {
            if (decoded.size != 32) {
                throw AndroidPairingCredentialStoreException(
                    AndroidPairingCredentialStoreFailure.InvalidState,
                )
            }
        } finally {
            decoded.fill(0)
        }
    }

    private fun corrupt(): Nothing {
        clearInternal(deleteWrappingKey = true)
        throw AndroidPairingCredentialStoreException(
            AndroidPairingCredentialStoreFailure.Corrupt,
        )
    }

    private fun x963(key: ECPublicKey): ByteArray =
        byteArrayOf(0x04) +
            fixed32(key.w.affineX.toByteArray()) +
            fixed32(key.w.affineY.toByteArray())

    private fun fixed32(value: ByteArray): ByteArray {
        val unsigned = value.dropWhile { it == 0.toByte() }.toByteArray()
        if (unsigned.size > 32) corrupt()
        return ByteArray(32 - unsigned.size) + unsigned
    }

    private fun digest(value: ByteArray): String =
        "sha256_" + MessageDigest.getInstance("SHA-256")
            .digest(value)
            .joinToString("") { "%02x".format(it) }

    @Serializable
    private data class EncryptedRecord(
        val schemaVersion: Int,
        val ivBase64: String,
        val ciphertextBase64: String,
    )

    @Serializable
    private data class StoredState(
        val schemaVersion: Int,
        val state: String,
        val pairingId: String,
        val pairingPayload: String?,
        val pairingCanonicalBase64: String?,
        val credentialId: String?,
        val channelSecretBase64: String?,
        val clientKeyThumbprint: String,
        val expiresAtMilliseconds: Long,
    )

    companion object {
        internal const val RECORD_FILE_NAME = "ameme-agent-pairing-client-v2.enc"
        private const val SCHEMA_VERSION = 2
        private const val STATE_PENDING = "pending"
        private const val STATE_ACTIVE = "active"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val SIGNING_KEY_ALIAS = "com.ameme.android.agent-pairing-client.p256.v2"
        private const val WRAPPING_KEY_ALIAS = "com.ameme.android.agent-pairing-client.wrap.v2"
        private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
        private val AAD = "ameme-agent-pairing-client-v2".encodeToByteArray()
        private val IDENTIFIER = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
        private val DIGEST = Regex("sha256_[0-9a-f]{64}")
        private val json = Json {
            encodeDefaults = true
            explicitNulls = true
            ignoreUnknownKeys = false
        }
    }
}
