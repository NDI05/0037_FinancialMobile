package com.pam.recap.data.ai

import com.pam.recap.core.money.Money
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.core.util.PamJson
import com.pam.recap.domain.model.AiAggregatePayload
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.CategoryTotal
import com.pam.recap.domain.model.CoverageStatus
import com.pam.recap.domain.model.MonthlySummary
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

/**
 * §9 test 6 — the FR-35 forbidden-field scan. The payload that leaves the device may only carry
 * aggregate numbers: no body, subject, sender, message id, token, PAN, merchant, note or
 * description can appear, because no such property exists on the payload at all.
 */
class AiPayloadBuilderTest {

    private val builder = AiPayloadBuilder()
    private val generatedAt = Instant.parse("2026-03-01T02:00:00Z")

    private val summary = MonthlySummary(
        id = "u_demo:2026-02:3",
        ownerId = "u_demo",
        month = JakartaMonth(2026, 2),
        mailboxLabel = "sensitive.inbox@example.com",
        grossInflow = Money.ofMajor(5_850_000),
        grossOutflow = Money.ofMajor(2_870_650),
        netCashFlow = Money.ofMajor(2_979_350),
        classifiedIncome = Money.ofMajor(5_750_000),
        classifiedExpense = Money.ofMajor(2_495_650),
        categoryTotals = listOf(
            CategoryTotal(Category.INCOME, Money.ofMajor(5_750_000), 2),
            CategoryTotal(Category.HOUSING, Money.ofMajor(900_000), 2),
            CategoryTotal(Category.FOOD, Money.ofMajor(256_250), 3),
        ),
        uncategorizedCount = 1,
        manualEntryCount = 2,
        nonIdrExcludedCount = 1,
        transactionCount = 18,
        coverage = CoverageStatus.COMPLETE,
        inputRevision = 3,
        summaryVersion = MonthlySummary.SUMMARY_VERSION,
        generatedAt = generatedAt,
    )

    private fun payloadJson(): String {
        val payload = builder.build(summary, previousMonth = summary)
        return PamJson.encodeToString(AiAggregatePayload.serializer(), payload)
    }

    private fun collectKeys(element: JsonElement, into: MutableSet<String>) {
        when (element) {
            is JsonObject -> element.forEach { (key, value) ->
                into += key
                collectKeys(value, into)
            }

            is kotlinx.serialization.json.JsonArray -> element.forEach { collectKeys(it, into) }
            else -> Unit
        }
    }

    @Test
    fun `payload contains only allow listed fields`() {
        val json = PamJson.parseToJsonElement(payloadJson()).jsonObject
        val keys = mutableSetOf<String>()
        keys += json.keys
        json.values.forEach { collectKeys(it, keys) }

        val unexpected = keys - AiAggregatePayload.ALLOWED_FIELDS
        assertTrue("unexpected payload fields: $unexpected", unexpected.isEmpty())
        assertTrue(keys.isNotEmpty())
    }

    @Test
    fun `forbidden content never appears in the serialized payload`() {
        val json = payloadJson()
        val lower = json.lowercase()

        val forbiddenKeys = listOf(
            "subject",
            "body",
            "sender",
            "merchant",
            "messageid",
            "token",
            "password",
            "pan",
            "fingerprint",
            "description",
            "note",
            "mailbox",
            "account",
            "acquirer",
        )
        forbiddenKeys.forEach { key ->
            assertFalse("payload leaked a \"$key\" field: $json", lower.contains("\"$key\""))
        }

        // Values from the source data must not ride along either.
        assertFalse(lower.contains("sensitive.inbox@example.com"))
        assertFalse(lower.contains("kopi"))
        assertFalse(lower.contains("@"))
    }

    @Test
    fun `amounts stay exact minor units`() {
        val payload = builder.build(summary, previousMonth = null)

        assertEquals(585_000_000L, payload.grossInflowMinor)
        assertEquals(287_065_000L, payload.grossOutflowMinor)
        assertEquals(297_935_000L, payload.netCashFlowMinor)
        assertEquals("2026-02", payload.monthId)
        assertEquals("COMPLETE", payload.coverage)
        assertEquals(18, payload.transactionCount)
        assertEquals(1, payload.nonIdrExcludedCount)
    }

    @Test
    fun `category totals are projected by code and amount only`() {
        val payload = builder.build(summary, previousMonth = null)
        val housing = payload.categoryTotals.first { it.categoryCode == Category.HOUSING.code }

        assertEquals(90_000_000L, housing.amountMinor)
        assertEquals(2, housing.transactionCount)
        assertTrue(payload.categoryTotals.all { it.categoryCode in Category.entries.map(Category::code) })
    }

    @Test
    fun `an absent prior month stays null instead of being invented`() {
        val payload = builder.build(summary, previousMonth = null)

        assertEquals(null, payload.priorMonth)
    }
}
