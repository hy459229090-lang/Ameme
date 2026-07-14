package com.ameme.android.data.summary

import com.ameme.android.domain.DaySummarySnapshot
import com.ameme.android.domain.DaySummaryState
import com.ameme.android.domain.EvidenceState
import com.ameme.android.domain.FactStatus
import com.ameme.android.domain.MemoryEvent
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DaySummaryClientTest {
    @Test
    fun constructor_allowsOnlyHttpsOrKnownDebugHosts() {
        HttpDaySummaryClient("http://127.0.0.1:8787")
        HttpDaySummaryClient("http://10.0.2.2:8787")
        HttpDaySummaryClient("https://inference.synthetic.invalid")

        assertThrows(IllegalArgumentException::class.java) {
            HttpDaySummaryClient("http://192.0.2.10:8787")
        }
    }

    @Test
    fun generate_preservesDateOnlyPlanAndAcceptsBoundEvidence() {
        val requestBody = AtomicReference<String>()
        val server = server { body ->
            requestBody.set(body)
            successResponse(ledgerRevision = 2)
        }
        try {
            val generated = HttpDaySummaryClient("http://127.0.0.1:${server.port}").generate(
                snapshot(),
                "install_synthetic",
                ZoneId.of("Asia/Shanghai"),
            )

            assertTrue(requestBody.get().contains("\"event_time\":null"))
            assertTrue(requestBody.get().contains("\"fact_status\":\"planned\""))
            assertFalse(requestBody.get().contains("source_locator"))
            assertTrue(generated.text.contains("完成本地摘要存储"))
            assertTrue(generated.text.contains("计划复盘"))
            assertTrue(generated.modelOrRuleVersion.contains("deterministic_fake"))
        } finally {
            server.close()
        }
    }

    @Test
    fun generate_rejectsResponseForAnotherLedgerRevision() {
        val server = server { successResponse(ledgerRevision = 99) }
        try {
            val result = runCatching {
                HttpDaySummaryClient("http://127.0.0.1:${server.port}").generate(
                    snapshot(),
                    "install_synthetic",
                    ZoneId.of("Asia/Shanghai"),
                )
            }
            assertTrue(result.isFailure)
            assertEquals("RESPONSE_BINDING_MISMATCH", (result.exceptionOrNull() as DaySummaryClientException).code)
        } finally {
            server.close()
        }
    }

    private fun snapshot(): DaySummarySnapshot {
        val date = LocalDate.of(2026, 7, 14)
        return DaySummarySnapshot(
            localDate = date,
            ledgerRevision = 2,
            events = listOf(
                MemoryEvent(
                    id = "evt_completed",
                    localDate = date,
                    time = LocalTime.of(9, 30),
                    title = "完成本地摘要存储",
                    detail = "摘要绑定 DayLedger revision。",
                    factStatus = FactStatus.Confirmed,
                    sourceLabel = "synthetic",
                    evidenceState = EvidenceState.Observed,
                ),
                MemoryEvent(
                    id = "evt_planned",
                    localDate = date,
                    time = null,
                    title = "计划复盘",
                    detail = "这是计划，不代表已经发生。",
                    factStatus = FactStatus.Planned,
                    sourceLabel = "synthetic calendar",
                    evidenceState = EvidenceState.Observed,
                ),
            ),
            state = DaySummaryState.Absent,
        )
    }

    private fun server(response: (String) -> String) = LocalHttpServer(response)

    private fun successResponse(ledgerRevision: Int) = """
        {"summary":{"schema_version":"ameme.day-summary.v1","ledger_id":"day_20260714","ledger_revision":$ledgerRevision,"local_date":"2026-07-14","headline":"今日两件事","overview":"完成一项工作，并保留一项计划。","highlights":[{"text":"完成本地摘要存储","event_ids":["evt_completed"]}],"progress":[],"open_loops":[{"text":"计划复盘","event_ids":["evt_planned"]}],"rules_version":"ameme.day-summary-rules.v1","model":{"provider":"deterministic_fake","model_id":"ameme-fake-summary-v1","provider_version":"ameme.inference-provider.v1","response_id":"fake_synthetic"}}}
    """.trimIndent()

    private class LocalHttpServer(private val response: (String) -> String) : AutoCloseable {
        private val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port: Int = socket.localPort
        private val worker = Thread {
            socket.accept().use { client ->
                val input = DataInputStream(client.getInputStream())
                var contentLength = 0
                while (true) {
                    @Suppress("DEPRECATION")
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(':').trim().toInt()
                    }
                }
                val requestBody = input.readNBytes(contentLength).decodeToString()
                val responseBytes = response(requestBody).encodeToByteArray()
                val headers = (
                    "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: ${responseBytes.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    ).encodeToByteArray()
                client.getOutputStream().use { output ->
                    output.write(headers)
                    output.write(responseBytes)
                    output.flush()
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

        override fun close() {
            socket.close()
            worker.join(2_000)
        }
    }
}
