package com.pam.recap.data.backend

import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
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

/**
 * S4.2 (C21–C25) admin HTTP contract: exact paths/query/If-Match/body, one-refresh replay on 401
 * and the machine-code mapping the UI relies on (`admin_required`, `revision_conflict`,
 * `last_admin`, `invalid_cursor`, `invitation_expired`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AdminApiClientTest {

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
    fun `accounts list sends the filter and keyset parameters`() = runTest {
        server.enqueue(json(200, PAGE))
        val result = client.adminAccounts(
            ownerId = "own_admin",
            q = "  ani@example.id ",
            role = "user",
            status = "active",
            limit = 2_000,
            cursor = "cur_1",
        ) as Outcome.Success

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals(
            "/v1/admin/accounts?limit=100&q=ani%40example.id&role=USER&status=ACTIVE&cursor=cur_1",
            request.path,
        )
        assertEquals("Bearer access-1", request.getHeader("authorization"))
        assertEquals(2, result.value.items.size)
        assertEquals("own_1", result.value.items.first().ownerId)
        assertEquals("cur_2", result.value.nextCursor)
    }

    @Test
    fun `account detail uses the owner scoped path`() = runTest {
        server.enqueue(json(200, ACCOUNT))
        val result = client.adminAccount("own_admin", "own_1") as Outcome.Success
        assertEquals("/v1/admin/accounts/own_1", server.takeRequest().path)
        assertEquals(2, result.value.revision)
        assertEquals("ADMIN", result.value.role)
    }

    @Test
    fun `invitation posts email role and expiry and returns the one-time token`() = runTest {
        server.enqueue(json(201, INVITATION))
        val result = client.adminInvite("own_admin", " baru@example.id ", "user", 12) as Outcome.Success

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/admin/invitations", request.path)
        assertEquals(
            """{"email":"baru@example.id","role":"USER","expiresInHours":12}""",
            request.body.readUtf8(),
        )
        assertEquals("tok_once_123", result.value.invitationToken)
        assertEquals("inv_1", result.value.invitation.id)
        assertEquals("USER", result.value.invitation.role)
    }

    @Test
    fun `update patches display name role status and revision`() = runTest {
        server.enqueue(json(200, UPDATE_RESULT))
        val result = client.adminUpdateAccount(
            "own_admin",
            "own_1",
            RemoteAdminAccountPatch(
                displayName = "Ani Baru",
                role = "USER",
                status = "SUSPENDED",
                revision = 2,
            ),
        ) as Outcome.Success

        val request = server.takeRequest()
        assertEquals("PATCH", request.method)
        assertEquals("/v1/admin/accounts/own_1", request.path)
        assertEquals(
            """{"displayName":"Ani Baru","role":"USER","status":"SUSPENDED","revision":2}""",
            request.body.readUtf8(),
        )
        assertEquals(3, result.value.revision)
    }

    @Test
    fun `deactivate sends the revision both as query and If-Match`() = runTest {
        server.enqueue(json(200, DEACTIVATE_RESULT))
        val result = client.adminDeactivateAccount("own_admin", "own_1", 2) as Outcome.Success

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/v1/admin/accounts/own_1?revision=2", request.path)
        assertEquals("\"2\"", request.getHeader("If-Match"))
        assertEquals("SUSPENDED", result.value.status)
    }

    @Test
    fun `audit list forwards action and target filters`() = runTest {
        server.enqueue(json(200, AUDIT_PAGE))
        val result = client.adminAudit(
            ownerId = "own_admin",
            targetOwnerId = "own_9",
            action = "admin.account.update",
            limit = 10,
            cursor = null,
        ) as Outcome.Success

        assertEquals(
            "/v1/admin/audit?limit=10&targetOwnerId=own_9&action=admin.account.update",
            server.takeRequest().path,
        )
        assertEquals("actor_1", result.value.items.single().actorOwnerId)
        assertEquals("next_a", result.value.nextCursor)
    }

    @Test
    fun `current role reads the me endpoint`() = runTest {
        server.enqueue(json(200, """{"role":"ADMIN","displayName":"Admin"}"""))
        val result = client.currentRole("own_admin") as Outcome.Success
        assertEquals("/v1/me", server.takeRequest().path)
        assertEquals("ADMIN", result.value.role)
    }

    @Test
    fun `admin 401 is renewed once and replayed`() = runTest {
        server.enqueue(json(401, """{"code":"session_stale"}"""))
        server.enqueue(json(200, PAGE))
        val result = client.adminAccounts("own_admin") as Outcome.Success
        assertEquals(1, sessions.renewals)
        assertEquals("Bearer access-1", server.takeRequest().getHeader("authorization"))
        assertEquals("Bearer access-2", server.takeRequest().getHeader("authorization"))
        assertEquals(2, result.value.items.size)
    }

    @Test
    fun `403 forbidden becomes admin_required`() = runTest {
        server.enqueue(json(403, """{"code":"forbidden","message":"Admin role required"}"""))
        val failure = client.adminAccounts("own_admin") as Outcome.Failure
        val error = failure.error as AppError.Unauthorized
        assertEquals("admin_required", error.debugDetail)
    }

    @Test
    fun `409 last_admin keeps the rule code`() = runTest {
        server.enqueue(json(409, """{"code":"last_admin","message":"last active admin"}"""))
        val failure = client.adminDeactivateAccount("own_admin", "own_1", 2) as Outcome.Failure
        val error = failure.error as AppError.Conflict
        assertEquals("last_admin", error.debugDetail)
    }

    @Test
    fun `400 invalid_cursor stays distinguishable from other validation`() = runTest {
        server.enqueue(json(400, """{"code":"invalid_cursor"}"""))
        val failure = client.adminAccounts("own_admin", cursor = "stale") as Outcome.Failure
        val error = failure.error as AppError.Validation
        assertEquals("invalid_cursor", error.debugDetail)
    }

    @Test
    fun `410 invitation_expired maps to conflict`() = runTest {
        server.enqueue(json(410, """{"code":"invitation_expired"}"""))
        val failure = client.adminInvite("own_admin", "a@b.id", "USER", 72) as Outcome.Failure
        val error = failure.error as AppError.Conflict
        assertEquals("invitation_expired", error.debugDetail)
    }

    private fun json(code: Int, body: String) = MockResponse()
        .setResponseCode(code)
        .setHeader("content-type", "application/json")
        .setBody(body)

    private class FakeSessions : BackendSessionRepository {
        override val isEnabled = true
        var renewals = 0
        override suspend fun register(email: String, password: String, displayName: String?, inviteCode: String) = Outcome.Success(Unit)
        override suspend fun login(email: String, password: String) = Outcome.Success(Unit)
        override suspend fun token(ownerId: String): Outcome<String> = Outcome.Success("access-1")
        override suspend fun renew(ownerId: String, rejectedToken: String?): Outcome<String> {
            renewals++
            return Outcome.Success("access-2")
        }
        override suspend fun revoke() = Unit
        override fun clear() = Unit
    }

    private companion object {
        const val ACCOUNT = """{"ownerId":"own_1","userId":"usr_1","email":"ani@example.id",
            "displayName":"Ani","role":"ADMIN","status":"ACTIVE","revision":2,
            "createdAt":"2026-11-01T00:00:00Z","updatedAt":"2026-11-20T00:00:00Z"}"""

        const val UPDATE_RESULT = """{"ownerId":"own_1","email":"ani@example.id",
            "displayName":"Ani Baru","role":"USER","status":"SUSPENDED","revision":3}"""

        const val DEACTIVATE_RESULT = """{"ownerId":"own_1","email":"ani@example.id",
            "displayName":"Ani","role":"ADMIN","status":"SUSPENDED","revision":3}"""

        const val PAGE = """{"items":[${ACCOUNT},${ACCOUNT}],"nextCursor":"cur_2"}"""

        const val INVITATION = """{"invitation":{"id":"inv_1","email":"baru@example.id",
            "role":"USER","expiresAt":"2026-11-26T00:00:00Z","createdAt":"2026-11-23T00:00:00Z"},
            "invitationToken":"tok_once_123"}"""

        const val AUDIT_PAGE = """{"items":[{"id":"aud_1","actorOwnerId":"actor_1",
            "targetOwnerId":"own_9","action":"admin.account.update",
            "metadata":{"role":"USER"},"createdAt":"2026-11-23T01:00:00Z"}],"nextCursor":"next_a"}"""
    }
}
