package com.pam.recap.data.backend

import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.AppErrorRules
import com.pam.recap.core.result.ErrorKind
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.time.ClockProvider
import com.pam.recap.core.util.PamJson
import com.pam.recap.domain.repository.BackendSessionRepository
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
class PamApiClientTest {
    private lateinit var server: MockWebServer
    private lateinit var sessions: FakeSessions
    private lateinit var client: PamApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        sessions = FakeSessions()
        client = PamApiClient(
            client = OkHttpClient(),
            json = PamJson,
            config = object : BackendEndpoints {
                override val isEnabled = true
                override val analysesUrl: String? = server.url("/v1/analyses").toString()
                override fun endpoint(path: String) = server.url(path).toString()
            },
            sessions = sessions,
            ioDispatcher = UnconfinedTestDispatcher(),
        )
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `summary parses integer money and category identity`() = runTest {
        server.enqueue(json(200, SUMMARY))
        val result = client.summary("u_1", "2026-11") as Outcome.Success
        assertEquals(17_500_000L, result.value.grossOutflowMinor)
        assertEquals("food", result.value.categoryTotals.single().categoryCode)
        assertEquals("Bearer access-1", server.takeRequest().getHeader("authorization"))
    }

    @Test
    fun `manual create sends one idempotency key and parses revision`() = runTest {
        server.enqueue(json(201, TRANSACTION))
        val result = client.createTransaction(
            "u_1", "manual-key-1",
            RemoteTransactionCreate(
                direction = "DEBIT", amountMinor = 17_500_000,
                eventAt = "2026-11-19T04:10:00Z", categoryId = "cat_food", description = "Synthetic",
            ),
        ) as Outcome.Success
        val request = server.takeRequest()
        assertEquals("manual-key-1", request.getHeader("Idempotency-Key"))
        assertEquals(3, result.value.revision)
        assertTrue(request.body.readUtf8().contains("\"amountMinor\":17500000"))
    }

    @Test
    fun `a 401 is renewed once and replayed`() = runTest {
        server.enqueue(json(401, """{"code":"session_stale"}"""))
        server.enqueue(json(200, SUMMARY))
        val result = client.summary("u_1", "2026-11") as Outcome.Success
        assertEquals(17_500_000L, result.value.grossOutflowMinor)
        assertEquals(1, sessions.renewals)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a failed refresh stops without a retry loop`() = runTest {
        sessions.renewFailure = AppError.Unauthorized("refresh_rejected")
        server.enqueue(json(401, """{"code":"session_stale"}"""))
        val result = client.summary("u_1", "2026-11")
        assertTrue(result is Outcome.Failure)
        assertEquals(ErrorKind.UNAUTHORIZED, (result as Outcome.Failure).error.kind)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `deletion conflict maps to the deletion rule`() = runTest {
        server.enqueue(json(409, """{"code":"deletion_in_progress"}"""))
        val result = client.createTransaction(
            "u_1", "key-1",
            RemoteTransactionCreate("DEBIT", 1_000, eventAt = "2026-11-19T04:10:00Z"),
        )
        val error = (result as Outcome.Failure).error
        assertEquals(ErrorKind.VALIDATION, error.kind)
        assertEquals(AppErrorRules.DELETION_IN_PROGRESS, (error as AppError.Validation).rule)
    }

    @Test
    fun `deletion requires password and returns a receipt`() = runTest {
        server.enqueue(json(202, """{"id":"del_1","state":"PENDING","slaDays":7,"receiptToken":"r_1"}"""))
        val result = client.requestDeletion("u_1", "synthetic-password") as Outcome.Success
        assertEquals("r_1", result.value.receiptToken)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("DELETE_WORKSPACE"))
        assertTrue(body.contains("synthetic-password"))
    }

    @Test
    fun `category search sends the owner-scoped query and reads the envelope`() = runTest {
        server.enqueue(
            json(
                200,
                """{"categories":[{"id":"cat_food","code":"food","label":"Makanan","kind":"EXPENSE","revision":2}],
                   "search":{"scanned":3,"truncated":false},"nextCursor":"cat:next"}""",
            ),
        )
        val result = client.categories("u_1", query = "mak", kind = "EXPENSE", limit = 25) as Outcome.Success
        val request = server.takeRequest()
        assertEquals("/v1/categories?limit=25&q=mak&kind=EXPENSE", request.path)
        assertEquals(3, result.value.search?.scanned)
        assertEquals("cat:next", result.value.nextCursor)
        assertEquals("food", result.value.categories.single().code)
    }

    @Test
    fun `budget period search sends the inclusive window and parses plans`() = runTest {
        server.enqueue(
            json(
                200,
                """{"plans":[{"monthId":"2026-11","expectedIncomeMinor":1000,
                   "plannedSavingsMinor":0,"limits":[],"revision":2}]}""",
            ),
        )
        val result = client.budgetPlans("u_1", "2025-12", "2026-11", limit = 12) as Outcome.Success
        val request = server.takeRequest()
        assertEquals("/v1/budget-plans?from=2025-12&to=2026-11&limit=12", request.path)
        assertEquals("2026-11", result.value.plans.single().monthId)
    }

    private fun json(code: Int, body: String) = MockResponse()
        .setResponseCode(code)
        .setHeader("content-type", "application/json")
        .setBody(body)

    private class FakeSessions : BackendSessionRepository {
        override val isEnabled = true
        var renewals = 0
        var renewFailure: AppError? = null
        override suspend fun register(email: String, password: String, displayName: String?, inviteCode: String) = Outcome.Success(Unit)
        override suspend fun login(email: String, password: String) = Outcome.Success(Unit)
        override suspend fun token(ownerId: String): Outcome<String> = Outcome.Success("access-1")
        override suspend fun renew(ownerId: String, rejectedToken: String?): Outcome<String> {
            renewals++
            return renewFailure?.let { Outcome.Failure(it) } ?: Outcome.Success("access-2")
        }
        override suspend fun revoke() = Unit
        override fun clear() = Unit
    }

    private companion object {
        const val SUMMARY = """{"monthId":"2026-11","inputRevision":2,"coverage":"PARTIAL",
            "grossInflowMinor":500000000,"grossOutflowMinor":17500000,"netMinor":482500000,
            "classifiedIncomeMinor":500000000,"classifiedExpenseMinor":17500000,
            "transactionCount":1,"manualEntryCount":0,"uncategorizedCount":0,
            "categoryTotals":[{"categoryCode":"food","label":"Makan","amountMinor":17500000,"count":1}],
            "generatedAt":"2026-11-22T00:00:00Z","emailDerived":true}"""
        const val TRANSACTION = """{"id":"tx_1","categoryId":"cat_food","categoryCode":"food",
            "categoryKind":"EXPENSE","direction":"DEBIT","source":"MANUAL",
            "amountMinor":17500000,"currency":"IDR","eventAt":"2026-11-19T04:10:00Z",
            "monthId":"2026-11","fingerprint":"abc","categoryOrigin":"USER",
            "isInternalTransfer":false,"revision":3,
            "createdAt":"2026-11-22T00:00:00Z","updatedAt":"2026-11-22T00:00:00Z"}"""
    }
}
