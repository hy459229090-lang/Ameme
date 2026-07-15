package com.ameme.android.data.transport

import java.time.Clock
import kotlinx.coroutines.delay

object PairingExperienceConnectorProvider {
    fun create(): PairingExperienceConnector = DebugPairingExperienceConnector()
}

class DebugPairingExperienceConnector(
    private val clock: Clock = Clock.systemUTC(),
    private val delayMillis: Long = 250L,
) : PairingExperienceConnector {
    override val simulated: Boolean = true

    override suspend fun resolve(method: PairingExperienceMethod): PairingExperienceCandidate {
        delay(delayMillis)
        return PairingExperienceCandidate(
            id = "debug-${method.wireValue}",
            deviceName = "Ameme Desktop",
            agentName = "Codex",
            method = method,
            capabilities = listOf("写入结构化工作记录"),
            simulated = true,
        )
    }

    override suspend fun connect(candidate: PairingExperienceCandidate): PairingExperienceConnection {
        require(candidate.simulated)
        delay(delayMillis)
        return PairingExperienceConnection(
            id = "debug-connection-${candidate.method.wireValue}",
            deviceName = candidate.deviceName,
            agentName = candidate.agentName,
            method = candidate.method,
            capabilities = candidate.capabilities,
            connectedAt = clock.instant(),
            simulated = true,
        )
    }
}
