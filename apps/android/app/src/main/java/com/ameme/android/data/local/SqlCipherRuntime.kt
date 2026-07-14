package com.ameme.android.data.local

import net.zetetic.database.Logger
import net.zetetic.database.NoopTarget

object SqlCipherRuntime {
    @Volatile
    private var initialized = false

    fun initialize() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            System.loadLibrary("sqlcipher")
            Logger.setTarget(NoopTarget())
            initialized = true
        }
    }
}
