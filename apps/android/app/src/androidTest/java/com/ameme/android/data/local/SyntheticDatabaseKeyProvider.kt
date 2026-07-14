package com.ameme.android.data.local

class SyntheticDatabaseKeyProvider(key: ByteArray) : DatabaseKeyProvider {
    private val storedKey = key.copyOf()

    override fun getOrCreateKey(): ByteArray = storedKey.copyOf()
}
