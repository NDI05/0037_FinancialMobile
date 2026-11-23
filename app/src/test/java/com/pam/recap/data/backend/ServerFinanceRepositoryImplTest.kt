package com.pam.recap.data.backend

import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.core.util.PamJson
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.Direction
import com.pam.recap.domain.model.TransactionSource
import com.pam.recap.domain.repository.BackendSessionRepository
import com.pam.recap.domain.repository.TransactionPatch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerFinanceRepositoryImplTest {
    private lateinit var server: MockWebServer
    private lateinit var repo: ServerFinanceRepositoryImpl

    @Before
    fun setUp() {
        server = MockWebServer(); server.start()
        val endpoints = object : BackendEndpoints {
            override val isEnabled = true
            override val analysesUrl: String? = server.url("/v1/analyses").toString()
            override fun endpoint(path: String) = server.url(path).toString()
        }
        val sessions = object : BackendSessionRepository {
            override val isEnabled = true
            override suspend fun register(email: String, password: String, displayName: String?, inviteCode: String) = Outcome.Success(Unit)
            override suspend fun login(email: String, password: String) = Outcome.Success(Unit)
            override suspend fun token(ownerId: String) = Outcome.Success("token")
            override suspend fun revoke() = Unit
            override fun clear() = Unit
        }
        val api = PamApiClient(OkHttpClient(), PamJson, endpoints, sessions, UnconfinedTestDispatcher())
        repo = ServerFinanceRepositoryImpl(api, endpoints)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `categories and summary come from the server with integer money`() = runTest {
        server.enqueue(json("""{"categories":[{"id":"cat_food","code":"food","label":"Makan","kind":"EXPENSE","revision":2}]}"""))
        server.enqueue(json(SUMMARY))
        val categories = (repo.categories("u_1") as Outcome.Success).value
        assertEquals("cat_food", categories.single().id)
        val summary = (repo.summary("u_1", requireNotNull(JakartaMonth.parse("2026-11"))) as Outcome.Success).value
        assertEquals(17_500_000L, summary.grossOutflow.minor)
        assertEquals(Category.FOOD, summary.categoryTotals.single().category)
        assertEquals(1, summary.transactionCount)
    }

    @Test
    fun `transaction list and detail retain server identity`() = runTest {
        server.enqueue(json("""{"items":[${TRANSACTION}],"nextCursor":null}"""))
        server.enqueue(json(TRANSACTION))
        val page = (repo.listMonth("u_1", requireNotNull(JakartaMonth.parse("2026-11"))) as Outcome.Success).value
        val tx = page.items.single()
        assertEquals("tx_1", tx.id)
        assertEquals(3, tx.revision)
        assertEquals(Direction.DEBIT, tx.direction)
        assertEquals(TransactionSource.MANUAL, tx.source)
        assertEquals(Category.FOOD, tx.category)
        assertEquals("cat_food", tx.serverCategoryId)
        val detail = (repo.get("u_1", "tx_1") as Outcome.Success).value
        assertEquals("tx_1", detail.id)
    }

    @Test
    fun `server category correction sends the loaded revision`() = runTest {
        server.enqueue(json(TRANSACTION))
        val updated = repo.updateCategory("u_1", "tx_1", "cat_food", 3)
        assertTrue(updated is Outcome.Success)
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/v1/transactions/tx_1", request.path)
        val body = request.body.readUtf8()
        assertTrue(body, body.contains("\"categoryId\":\"cat_food\""))
        assertTrue(body, body.contains("\"revision\":3"))
        assertEquals(Category.FOOD, (updated as Outcome.Success).value.category)
    }

    @Test
    fun `a stale revision is reported as a conflict, not overwritten`() = runTest {
        server.enqueue(json("""{"code":"revision_conflict","message":"revision mismatch"}""", 409))
        val result = repo.updateCategory("u_1", "tx_1", "cat_food", 2)
        assertTrue(result is Outcome.Failure)
        assertTrue((result as Outcome.Failure).error is com.pam.recap.core.result.AppError.Conflict)
    }

    @Test
    fun `manual create sends category id and one idempotency key`() = runTest {
        server.enqueue(json(TRANSACTION, 201))
        val result = repo.createManual(
            "u_1",
            com.pam.recap.domain.repository.ManualTransactionDraft(
                direction = "DEBIT", amountMinor = 17_500_000,
                eventAtEpochMilliseconds = 1_763_524_200_000, categoryId = "cat_food",
                description = "Synthetic", note = "note",
            ),
            "manual-key-1",
        ) as Outcome.Success
        assertEquals("tx_1", result.value.id)
        val req = server.takeRequest()
        assertEquals("manual-key-1", req.getHeader("Idempotency-Key"))
        assertTrue(req.body.readUtf8().contains("\"categoryId\":\"cat_food\""))
    }

    @Test
    fun `custom category total and provenance are preserved`() = runTest {
        server.enqueue(json(SUMMARY.replace("\"food\"", "\"custom_coffee\"").replace("\"Makan\"", "\"Kopi khusus\"")))
        server.enqueue(json(TRANSACTION.replace("\"source\":\"MANUAL\"", "\"source\":\"EMAIL\"")
            .replace("\"categoryOrigin\":\"USER\"", "\"categoryOrigin\":\"IMPORTED\"")
            .dropLast(1) + ",\"parserVersion\":\"livin-bill/v2\",\"parserConfidence\":0.62,"
            + "\"note\":\"Synthetic note\",\"linkedRefundOf\":\"tx_original\"}"))
        val summary = (repo.summary("u_1", requireNotNull(JakartaMonth.parse("2026-11"))) as Outcome.Success).value
        assertEquals(1, summary.categoryTotals.size)
        assertEquals("custom_coffee", summary.categoryTotals.single().serverCode)
        assertEquals("Kopi khusus", summary.categoryTotals.single().serverLabel)
        val tx = (repo.get("u_1", "tx_1") as Outcome.Success).value
        assertEquals("livin-bill/v2", tx.parserVersion)
        assertEquals(0.62f, tx.parserConfidence)
        assertEquals(com.pam.recap.domain.model.CategoryOrigin.IMPORTED, tx.categoryOrigin)
        assertEquals("Synthetic note", tx.note)
        assertEquals("tx_original", tx.linkedRefundOf)
    }

    @Test
    fun `invalid wire values are protocol failures, not fabricated transactions`() = runTest {
        for (body in listOf(
            TRANSACTION.replace("2026-11-19T04:10:00Z", "not-a-time"),
            TRANSACTION.replace("\"direction\":\"DEBIT\"", "\"direction\":\"UNKNOWN\""),
            TRANSACTION.replace("\"source\":\"MANUAL\"", "\"source\":\"UNKNOWN\""),
            TRANSACTION.replace("17500000", "0"),
        )) {
            server.enqueue(json(body))
            assertTrue(repo.get("u_1", "tx_1") is Outcome.Failure)
        }
    }

    @Test
    fun `transaction patch sends attributes and parses the readback`() = runTest {
        server.enqueue(json(withAttributes(TRANSACTION, """{"sumber":"kamera","catatan":"QRIS"}""")))
        val result = repo.updateTransaction(
            "u_1",
            "tx_1",
            TransactionPatch(
                revision = 3,
                note = "note",
                attributes = mapOf("sumber" to "kamera", "catatan" to "QRIS"),
            ),
        ) as Outcome.Success
        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/v1/transactions/tx_1", request.path)
        val body = request.body.readUtf8()
        assertTrue(body, body.contains("\"attributes\":{\"sumber\":\"kamera\",\"catatan\":\"QRIS\"}"))
        assertEquals(mapOf("sumber" to "kamera", "catatan" to "QRIS"), result.value.attributes)
    }

    @Test
    fun `transaction readback treats an absent attributes map as empty`() = runTest {
        server.enqueue(json(TRANSACTION))
        val transaction = (repo.get("u_1", "tx_1") as Outcome.Success).value
        assertTrue(transaction.attributes.isEmpty())
    }

    @Test
    fun `a non-string transaction attribute is a protocol failure, not a silent drop`() = runTest {
        server.enqueue(json(withAttributes(TRANSACTION, """{"tags":["makan"]}""")))
        val result = repo.get("u_1", "tx_1")
        assertTrue(result is Outcome.Failure)
        assertTrue((result as Outcome.Failure).error is AppError.Parsing)
    }

    @Test
    fun `budget write sends attributes and parses the readback`() = runTest {
        server.enqueue(json(BUDGET_PLAN))
        val result = repo.saveBudgetPlan(
            ownerId = "u_1",
            month = requireNotNull(JakartaMonth.parse("2026-11")),
            expectedIncomeMinor = 500_000_000,
            plannedSavingsMinor = 100_000_000,
            limitsByCategoryId = mapOf("cat_food" to 17_500_000L),
            revision = 2,
            attributes = mapOf("sumber" to "kamera"),
        ) as Outcome.Success
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/v1/budget-plans/2026-11", request.path)
        val body = request.body.readUtf8()
        assertTrue(body, body.contains("\"attributes\":{\"sumber\":\"kamera\"}"))
        assertEquals(mapOf("sumber" to "kamera"), result.value.attributes)
        assertEquals(17_500_000L, result.value.limitsByCategoryId["cat_food"])
    }

    @Test
    fun `budget readback tolerates an absent attributes map`() = runTest {
        server.enqueue(json(BUDGET_PLAN.replace(",\"attributes\":{\"sumber\":\"kamera\"}", "")))
        val plan = (repo.budgetPlan("u_1", requireNotNull(JakartaMonth.parse("2026-11"))) as Outcome.Success).value
        assertTrue(plan.attributes.isEmpty())
    }

    @Test
    fun `a non-string budget attribute is a protocol failure, not a silent drop`() = runTest {
        server.enqueue(json(BUDGET_PLAN.replace("\"sumber\":\"kamera\"", "\"jumlah\":3")))
        val result = repo.budgetPlan("u_1", requireNotNull(JakartaMonth.parse("2026-11")))
        assertTrue(result is Outcome.Failure)
        assertTrue((result as Outcome.Failure).error is AppError.Parsing)
    }

    /** Appends an `attributes` object to one of the compact JSON fixtures above. */
    private fun withAttributes(body: String, attributes: String): String =
        body.dropLast(1) + ",\"attributes\":$attributes}"

    private fun json(body: String, code: Int = 200) = MockResponse()
        .setResponseCode(code)
        .setHeader("content-type", "application/json")
        .setBody(body)

    private companion object {
        const val SUMMARY = """{"monthId":"2026-11","inputRevision":2,"coverage":"PARTIAL",
            "grossInflowMinor":0,"grossOutflowMinor":17500000,"netMinor":-17500000,
            "classifiedIncomeMinor":0,"classifiedExpenseMinor":17500000,"transactionCount":1,
            "manualEntryCount":1,"uncategorizedCount":0,
            "categoryTotals":[{"categoryCode":"food","label":"Makan","amountMinor":17500000,"count":1}],
            "generatedAt":"2026-11-22T00:00:00Z"}"""
        const val TRANSACTION = """{"id":"tx_1","categoryId":"cat_food","categoryCode":"food",
            "categoryKind":"EXPENSE","direction":"DEBIT","source":"MANUAL",
            "amountMinor":17500000,"currency":"IDR","eventAt":"2026-11-19T04:10:00Z",
            "monthId":"2026-11","categoryOrigin":"USER","isInternalTransfer":false,
            "revision":3,"createdAt":"2026-11-22T00:00:00Z","updatedAt":"2026-11-22T00:00:00Z"}"""
        const val BUDGET_PLAN = """{"monthId":"2026-11","expectedIncomeMinor":500000000,
            "plannedSavingsMinor":100000000,
            "limits":[{"categoryId":"cat_food","amountMinor":17500000}],
            "revision":2,"gapMinor":0,"attributes":{"sumber":"kamera"}}"""
    }
}
