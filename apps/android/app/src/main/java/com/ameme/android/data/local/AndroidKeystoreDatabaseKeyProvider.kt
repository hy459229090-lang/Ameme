package com.ameme.android.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AndroidKeystoreDatabaseKeyProvider(
    context: Context,
    private val databaseFile: File,
) : DatabaseKeyProvider {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val storageId = MessageDigest.getInstance("SHA-256")
        .digest("${appContext.packageName}:${databaseFile.name}".encodeToByteArray())
        .take(12)
        .joinToString("") { "%02x".format(it) }
    private val alias = "${appContext.packageName}.local-event-node.$storageId.wrap.v1"
    private val wrappedKeyPreference = "${WRAPPED_KEY}_$storageId"
    private val aad = "${appContext.packageName}:${databaseFile.name}:v1".encodeToByteArray()

    override fun getOrCreateKey(): ByteArray {
        val encodedBlob = preferences.getString(wrappedKeyPreference, null)
        val keyStore = loadKeyStore()
        val wrappingKey = when {
            keyStore.containsAlias(alias) -> keyStore.getKey(alias, null) as? SecretKey
                ?: throw DatabaseKeyUnavailableException("Android Keystore alias is not a secret key")
            encodedBlob != null -> throw DatabaseKeyUnavailableException(
                "Wrapped database key exists but its Android Keystore key is unavailable",
            )
            databaseFile.exists() -> throw DatabaseKeyUnavailableException(
                "Encrypted database exists but no wrapped database key is available",
            )
            else -> createWrappingKey()
        }

        if (encodedBlob != null) {
            return decryptBlob(wrappingKey, encodedBlob)
        }
        if (databaseFile.exists()) {
            throw DatabaseKeyUnavailableException(
                "Encrypted database exists but no wrapped database key is available",
            )
        }

        val databaseKey = ByteArray(DATABASE_KEY_BYTES).also(SecureRandom()::nextBytes)
        val blob = encryptBlob(wrappingKey, databaseKey)
        val persisted = preferences.edit().putString(wrappedKeyPreference, blob).commit()
        if (!persisted) {
            databaseKey.fill(0)
            throw DatabaseKeyUnavailableException("Failed to persist the wrapped database key")
        }
        return databaseKey
    }

    private fun loadKeyStore(): KeyStore = try {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    } catch (error: Exception) {
        throw DatabaseKeyUnavailableException("Android Keystore is unavailable", error)
    }

    private fun createWrappingKey(): SecretKey = try {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        generator.generateKey()
    } catch (error: Exception) {
        throw DatabaseKeyUnavailableException("Failed to create Android Keystore wrapping key", error)
    }

    private fun encryptBlob(wrappingKey: SecretKey, databaseKey: ByteArray): String = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey)
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(databaseKey)
        val payload = ByteBuffer.allocate(2 + cipher.iv.size + ciphertext.size)
            .put(BLOB_VERSION)
            .put(cipher.iv.size.toByte())
            .put(cipher.iv)
            .put(ciphertext)
            .array()
        Base64.encodeToString(payload, Base64.NO_WRAP)
    } catch (error: Exception) {
        throw DatabaseKeyUnavailableException("Failed to wrap database key", error)
    }

    private fun decryptBlob(wrappingKey: SecretKey, encodedBlob: String): ByteArray = try {
        val payload = ByteBuffer.wrap(Base64.decode(encodedBlob, Base64.NO_WRAP))
        require(payload.get() == BLOB_VERSION) { "Unsupported wrapped-key version" }
        val ivSize = payload.get().toInt() and 0xff
        require(ivSize in 12..32 && payload.remaining() > ivSize) { "Invalid wrapped-key payload" }
        val iv = ByteArray(ivSize).also(payload::get)
        val ciphertext = ByteArray(payload.remaining()).also(payload::get)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad)
        cipher.doFinal(ciphertext).also {
            require(it.size == DATABASE_KEY_BYTES) { "Invalid database-key length" }
        }
    } catch (error: Exception) {
        throw DatabaseKeyUnavailableException("Wrapped database key cannot be recovered", error)
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREFERENCES_NAME = "ameme_database_key_v1"
        const val WRAPPED_KEY = "wrapped_key"
        const val DATABASE_KEY_BYTES = 32
        const val GCM_TAG_BITS = 128
        const val BLOB_VERSION: Byte = 1
    }
}
