package com.ameme.android.data.summary

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ameme.android.BuildConfig
import com.ameme.android.domain.DaySummarySnapshot
import com.ameme.android.domain.DaySummaryState
import com.ameme.android.domain.EvidenceState
import com.ameme.android.domain.FactStatus
import com.ameme.android.domain.MemoryEvent
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DaySummaryGatewayInstrumentedTest {
    @Test
    fun explicitLiveGate_reachesConfiguredGatewayWithSyntheticStructuredEvents() {
        assumeTrue(
            "Run only with an explicitly started synthetic/local gateway",
            InstrumentationRegistry.getArguments().getString("amemeGatewayLive") == "true",
        )
        val date = LocalDate.of(2026, 7, 14)
        val snapshot = DaySummarySnapshot(
            localDate = date,
            ledgerRevision = 2,
            events = listOf(
                MemoryEvent(
                    id = "evt_android_gateway_1",
                    localDate = date,
                    time = LocalTime.of(10, 0),
                    title = "Synthetic Android gateway event one",
                    detail = "Synthetic structured data only.",
                    factStatus = FactStatus.Confirmed,
                    sourceLabel = "synthetic",
                    evidenceState = EvidenceState.Observed,
                ),
                MemoryEvent(
                    id = "evt_android_gateway_2",
                    localDate = date,
                    time = LocalTime.of(11, 0),
                    title = "Synthetic Android gateway event two",
                    detail = "Synthetic structured data only.",
                    factStatus = FactStatus.UserAsserted,
                    sourceLabel = "synthetic",
                    evidenceState = EvidenceState.UserAsserted,
                ),
            ),
            state = DaySummaryState.Absent,
        )

        val generated = HttpDaySummaryClient(BuildConfig.AMEME_INFERENCE_BASE_URL).generate(
            snapshot = snapshot,
            subjectRef = "install_synthetic_android_gateway",
            zoneId = ZoneId.of("Asia/Shanghai"),
        )

        assertTrue(generated.text.contains("2026-07-14 · 2 events"))
        assertTrue(generated.text.contains("structured ledger contains 2 events"))
        assertTrue(generated.modelOrRuleVersion.contains("deterministic_fake"))
    }
}
