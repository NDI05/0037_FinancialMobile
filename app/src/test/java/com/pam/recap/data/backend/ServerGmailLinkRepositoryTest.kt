package com.pam.recap.data.backend

import com.pam.recap.core.result.Outcome
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.core.util.PamJson
import com.pam.recap.domain.model.GmailSyncState
import com.pam.recap.domain.repository.BackendSessionRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Instant

/**
 * Domain projection of `GET /v1/gmail/status` (contract §9.2): the wire fields become typed model
 * values, unknown phase stays [GmailSyncState.UNKNOWN], and malformed counters degrade instead of
 * failing the whole sheet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerGmailLinkRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: ServerGmailLinkRepositoryImpl

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val api = PamApiClient(
            client = OkHttpClient(),
            json = PamJson,
            config = object : BackendEndpoints {
                override val isEnabled = true
                override val analysesUrl: String? = null
                override fun endpoint(path: String) = server.url(path).toString()
            },
            sessions = object : BackendSessionRepository {
                override val isEnabled = true
                override suspend fun register(
                    email: String,
                    password: String,
                    displayName: String?,
                ) = Outcome.Success(Unit)
                override suspend fun login(email: String, password: String) = Outcome.Success(Unit)
                override suspend fun token(ownerId: String): Outcome<String> =
                    Outcome.Success("access-1")
                override suspend fun renew(ownerId: String, rejectedToken: String?): Outcome<String> =
                    Outcome.Success("access-1")
                override suspend fun revoke() = Unit
                override fun clear() = Unit
            },
            ioDispatcher = UnconfinedTestDispatcher(),
        )
        repository = ServerGmailLinkRepositoryImpl(api, apiConfig())
    }

    private fun apiConfig() = object : BackendEndpoints {
        override val isEnabled = true
        override val analysesUrl: String? = null
        override fun endpoint(path: String) = server.url(path).toString()
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `v2 fields map to the domain projection`() = runTest {
        server.enqueue(
            json(
                200,
                """{"connected":true,"account_email_masked":"p***@gmail.com","sync_state":"backfill",
                   "last_attempt_at":"2026-11-24T04:00:00Z","last_success_at":"2026-11-24T03:00:00Z",
                   "next_sync_at":"2026-11-24T06:00:00Z","sync_requested_at":"2026-11-24T04:30:00Z",
                   "needs_reauth":true,
                   "progress":{"phase":"backfill","month":"2026-08","months_done":2,
                               "months_total":12,"pages_done":7}}""",
            ),
        )
        val status = (repository.status("u_1") as Outcome.Success).value
        assertTrue(status.connected)
        assertTrue(status.needsReauth)
        assertEquals(GmailSyncState.BACKFILL, status.syncState)
        assertEquals(Instant.parse("2026-11-24T04:00:00Z"), status.lastAttemptAt)
        assertEquals(Instant.parse("2026-11-24T03:00:00Z"), status.lastSuccessAt)
        assertEquals(Instant.parse("2026-11-24T06:00:00Z"), status.nextSyncAt)
        val progress = status.progress ?: error("progress missing")
        assertEquals(JakartaMonth.parse("2026-08"), progress.month)
        assertEquals(2, progress.monthsDone)
        assertEquals(12, progress.monthsTotal)
        assertEquals(7, progress.pagesDone)
        assertTrue(progress.hasMonths)
    }

    @Test
    fun `malformed optional values degrade instead of failing the read`() = runTest {
        server.enqueue(
            json(
                200,
                """{"connected":true,"needs_reauth":false,
                   "last_attempt_at":"not-a-date","next_sync_at":null,
                   "progress":{"phase":"backfill","month":"2026-13",
                               "months_done":-2,"months_total":0,"pages_done":-1}}""",
            ),
        )
        val status = (repository.status("u_1") as Outcome.Success).value
        assertNull(status.lastAttemptAt)
        assertNull(status.nextSyncAt)
        assertFalse(status.needsReauth)
        val progress = status.progress ?: error("progress missing")
        assertNull(progress.month)
        assertEquals(0, progress.monthsDone)
        assertEquals(0, progress.pagesDone)
        assertFalse(progress.hasMonths)
    }

    @Test
    fun `sync request ack maps both timestamps`() = runTest {
        server.enqueue(
            json(
                202,
                """{"queued":true,"coalesced":true,
                   "requested_at":"2026-11-24T05:00:00Z","next_allowed_at":"2026-11-24T05:01:00Z"}""",
            ),
        )
        val ack = (repository.requestSync("u_1") as Outcome.Success).value
        assertTrue(ack.queued)
        assertTrue(ack.coalesced)
        assertEquals(Instant.parse("2026-11-24T05:00:00Z"), ack.requestedAt)
        assertEquals(Instant.parse("2026-11-24T05:01:00Z"), ack.nextAllowedAt)
        assertEquals("/v1/gmail/sync", server.takeRequest().path)
    }

    @Test
    fun `not linked status has no server projection`() = runTest {
        server.enqueue(json(200, """{"connected":false}"""))
        val status = (repository.status("u_1") as Outcome.Success).value
        assertFalse(status.connected)
        assertNull(status.progress)
    }

    private fun json(code: Int, body: String) = MockResponse()
        .setResponseCode(code)
        .setHeader("content-type", "application/json")
        .setBody(body)
}
