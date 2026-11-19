package com.pam.recap.contract

import com.pam.recap.core.security.FingerprintFactory
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.core.util.PamJson
import com.pam.recap.data.ai.AiPayloadBuilder
import com.pam.recap.data.ai.AnalysisRequestBody
import com.pam.recap.data.ai.toBody
import com.pam.recap.data.gmail.LivinQrisParser
import com.pam.recap.data.gmail.LivinTemplateRegistry
import com.pam.recap.data.seed.DemoDataset
import com.pam.recap.domain.model.AiAggregatePayload
import com.pam.recap.domain.repository.AnalysisRequest
import com.pam.recap.domain.usecase.ComputeMonthlySummaryUseCase
import com.pam.recap.domain.usecase.MerchantCategoryRules
import com.pam.recap.testing.FakeGmailConnectionRepository
import com.pam.recap.testing.FakeImportRepository
import com.pam.recap.testing.FakeSummaryRepository
import com.pam.recap.testing.FakeTransactionRepository
import com.pam.recap.testing.completeJob
import com.pam.recap.testing.fixedClock
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.time.Instant

/**
 * E6: builds the *real* February-2026 demo payload through the production builder and writes the
 * request exactly as [com.pam.recap.data.ai.RemoteAnalysisEngine] sends it, so the backend team can
 * replay one deterministic file with curl instead of a device.
 */
class ContractBridgeTest {

    private val owner = "u_demo"
    private val now = Instant.parse("2026-03-01T02:00:00Z")
    private val registry = LivinTemplateRegistry()
    private val fingerprints = FingerprintFactory { id -> "contract-key::$id".toByteArray() }

    private fun request(): AnalysisRequest {
        lateinit var built: AnalysisRequest
        kotlinx.coroutines.runBlocking {
            val rows = DemoDataset.build(
                ownerId = owner,
                now = now,
                parser = LivinQrisParser(registry),
                rules = MerchantCategoryRules(),
                fingerprints = fingerprints,
            )
            val transactions = FakeTransactionRepository(rows)
            // The real repository bumps a per-month revision on every write; the fake starts at 0.
            // Seven writes make the replay fixture match the smoke script's revision.
            repeat(7) { transactions.bumpMonthRevision(owner, JakartaMonth(2026, 2)) }
            val compute = ComputeMonthlySummaryUseCase(
                transactions = transactions,
                summaries = FakeSummaryRepository(),
                imports = FakeImportRepository(latest = completeJob(owner, DemoDataset.REFERENCE_MONTH, now)),
                gmailConnection = FakeGmailConnectionRepository(),
                clock = fixedClock(now),
            )
            val comparison = compute.withComparison(owner, JakartaMonth(2026, 2))
            val payload = AiPayloadBuilder().build(comparison.summary, comparison.previousMonth, plan = null)
            built = AnalysisRequest(
                ownerId = owner,
                monthId = "2026-02",
                inputRevision = comparison.summary.inputRevision,
                payload = payload,
            )
        }
        return built
    }

    @Test
    fun `writes the exact analyses body for the February demo dataset`() = runTest {
        val built = request()
        val body = built.toBody()
        val pretty = Json {
            prettyPrint = true
            prettyPrintIndent = "  "
            encodeDefaults = true
            explicitNulls = false
        }.encodeToString(AnalysisRequestBody.serializer(), body)

        val output = File("build/contract/analysis-request.json")
        output.parentFile.mkdirs()
        output.writeText(pretty + "\n")

        assertTrue(output.exists())
        assertTrue(output.length() > 200)

        val parsed = Json.parseToJsonElement(output.readText()).jsonObject
        assertEquals(setOf("monthId", "inputRevision", "payload"), parsed.keys)
        assertEquals("2026-02", parsed["monthId"]!!.toString().trim('"'))
        assertEquals(built.inputRevision.toString(), parsed["inputRevision"].toString())

        val payload = parsed["payload"]!!.jsonObject
        assertEquals("2026-02", payload["monthId"].toString().trim('"'))
        assertEquals(AiAggregatePayload.SCHEMA_VERSION.toString(), payload["schemaVersion"].toString())
        // The demo month's headline numbers, in minor units.
        assertEquals("585000000", payload["grossInflowMinor"].toString())
        assertEquals("287065000", payload["grossOutflowMinor"].toString())
        assertEquals("297935000", payload["netCashFlowMinor"].toString())

        // Nothing that may never leave the device.
        val forbidden = listOf("subject", "body", "messageId", "token", "pan", "merchant", "note")
        val serialized = output.readText().lowercase()
        forbidden.forEach { key -> assertFalse("payload must not contain $key", serialized.contains("\"$key\"")) }

        // Stable across builds: the replay must mean something.
        val second = request().toBody()
        assertEquals(
            Json.encodeToString(AnalysisRequestBody.serializer(), second),
            Json.encodeToString(AnalysisRequestBody.serializer(), body),
        )
        // And the pretty file is byte-for-byte the same document the engine serializes.
        assertEquals(
            Json.parseToJsonElement(PamJson.encodeToString(AnalysisRequestBody.serializer(), body)),
            Json.parseToJsonElement(pretty),
        )
    }

    @Test
    fun `the request body keeps the payload nested`() {
        val body = request().toBody()
        val element = Json.parseToJsonElement(
            PamJson.encodeToString(AnalysisRequestBody.serializer(), body),
        ).jsonObject as JsonObject
        assertTrue(element["payload"] is JsonObject)
        assertFalse(element.containsKey("grossInflowMinor"))
    }
}
