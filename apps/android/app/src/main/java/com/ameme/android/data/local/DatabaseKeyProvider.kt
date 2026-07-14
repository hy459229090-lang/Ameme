package com.ameme.android.data.local

/**
 * Supplies a fresh mutable copy of the raw SQLCipher key for each call.
 * The database opener clears the returned byte array immediately after open.
 */
fun interface DatabaseKeyProvider {
    fun getOrCreateKey(): ByteArray
}

class DatabaseKeyUnavailableException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)
