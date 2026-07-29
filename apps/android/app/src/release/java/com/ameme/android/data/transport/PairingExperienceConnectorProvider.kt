package com.ameme.android.data.transport

import android.content.Context

object PairingExperienceConnectorProvider {
    fun create(context: Context): PairingExperienceConnector =
        ProductionPairingExperienceConnector(context)
}
