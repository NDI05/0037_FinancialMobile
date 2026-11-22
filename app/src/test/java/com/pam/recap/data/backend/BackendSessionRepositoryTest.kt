package com.pam.recap.data.backend

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.AppErrorRules
import com.pam.recap.core.result.ErrorKind
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.security.OwnerIdFactory
import com.pam.recap.core.security.WrappedSecretStore
import com.pam.recap.core.time.ClockProvider
import com.pam.recap.core.util.PamJson
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Instant

/**
 * FR-51/FR-53 and CR-001: the account session is exchanged once with email + password, cached as
 * ciphertext, re-acquired per owner, and revoked on sign-out. Failures map to the right AppError.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackendSessionRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var context: Context
    private lateinit var endpoints: BackendEndpoints
    private var nowMillis: Long = Instant.parse("2026-03-01T02:00:00Z").toEpochMilliseconds()
    private val clock = object : ClockProvider {
        override fun now(): Instant = Instant.fromEpochMilliseconds(nowMillis)
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("pam_backend_session", Context.MODE_PRIVATE).edit().clear().commit()
        endpoints = object : BackendEndpoints {
            override val isEnabled: Boolean = true
            override val analysesUrl: String = server.url("/v1/analyses").toString()
            override fun endpoint(path: String): String = server.url(path).toString()
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `login exchanges the credentials once and caches the token`() = runTest {
        server.enqueue(sessionResponse("token-1"))
        val repository = repository()

        val loggedIn = repository.login(EMAIL, "correct-horse-battery")
        val first = repository.token(OWNER) as Outcome.Success
        nowMillis += 60_000
        val second = repository.token(OWNER) as Outcome.Success

        assertTrue(loggedIn is Outcome.Success)
        assertEquals("token-1", first.value)
        assertEquals("token-1", second.value)
        assertEquals(1, server.requestCount)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/sessions", request.path)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(EMAIL, body["email"]?.jsonPrimitive?.content)
        assertEquals("correct-horse-battery", body["password"]?.jsonPrimitive?.content)
    }

    @Test
    fun `an expired token without a refresh token is dropped`() = runTest {
        server.enqueue(sessionResponse("token-1", expiresInMinutes = 60))
        val repository = repository()
        repository.login(EMAIL, "correct-horse-battery")

        nowMillis += 120 * 60_000
        val expired = repository.token(OWNER)

        assertTrue(expired is Outcome.Failure)
        assertEquals(ErrorKind.UNAUTHORIZED, (expired as Outcome.Failure).error.kind)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `refresh rotates the access and refresh token once`() = runTest {
        server.enqueue(sessionResponse("token-1", expiresInMinutes = 1, refreshToken = "refresh-1"))
        val repository = repository()
        repository.login(EMAIL, "correct-horse-battery")
        server.takeRequest()

        nowMillis += 2 * 60_000
        server.enqueue(refreshResponse("token-2", "refresh-2"))
        val refreshed = repository.token(OWNER) as Outcome.Success
        assertEquals("token-2", refreshed.value)
        val request = server.takeRequest()
        assertEquals("/v1/sessions/refresh", request.path)
        assertTrue(request.body.readUtf8().contains("refresh-1"))

        // The rotated access token is valid and the old refresh token is not replayed.
        val again = repository.token(OWNER) as Outcome.Success
        assertEquals("token-2", again.value)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `parallel expired callers share one refresh`() = runTest {
        server.enqueue(sessionResponse("token-1", expiresInMinutes = 1, refreshToken = "refresh-1"))
        val repository = repository()
        repository.login(EMAIL, "correct-horse-battery")
        server.takeRequest()
        nowMillis += 2 * 60_000
        server.enqueue(refreshResponse("token-2", "refresh-2"))

        val results = listOf(
            async { repository.token(OWNER) },
            async { repository.token(OWNER) },
        ).awaitAll()
        assertTrue(results.all { it is Outcome.Success && it.value == "token-2" })
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a rejected refresh clears the session and is not retried`() = runTest {
        server.enqueue(sessionResponse("token-1", expiresInMinutes = 1, refreshToken = "refresh-1"))
        val repository = repository()
        repository.login(EMAIL, "correct-horse-battery")
        server.takeRequest()
        nowMillis += 2 * 60_000
        server.enqueue(jsonResponse(401, "refresh_invalid"))

        assertTrue(repository.token(OWNER) is Outcome.Failure)
        assertTrue(repository.token(OWNER) is Outcome.Failure)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a restarted repository can refresh from wrapped storage`() = runTest {
        server.enqueue(sessionResponse("token-1", expiresInMinutes = 1, refreshToken = "refresh-1"))
        repository().login(EMAIL, "correct-horse-battery")
        nowMillis += 2 * 60_000
        server.enqueue(refreshResponse("token-2", "refresh-2"))

        val restarted = repository()
        val result = restarted.token(OWNER) as Outcome.Success
        assertEquals("token-2", result.value)
    }

    @Test
    fun `a cached token for another owner is refused`() = runTest {
        server.enqueue(sessionResponse("token-a"))
        val repository = repository()
        repository.login(EMAIL, "correct-horse-battery")

        val other = repository.token(OwnerIdFactory.ownerIdFor("other@example.test"))

        assertTrue(other is Outcome.Failure)
        assertEquals(ErrorKind.UNAUTHORIZED, (other as Outcome.Failure).error.kind)
    }

    @Test
    fun `sign out revokes on the server and drops the cache`() = runTest {
        server.enqueue(sessionResponse("token-1"))
        server.enqueue(MockResponse().setResponseCode(204))
        val repository = repository()
        repository.login(EMAIL, "correct-horse-battery")

        repository.revoke()

        assertEquals("POST", server.takeRequest().method)
        val delete = server.takeRequest()
        assertEquals("DELETE", delete.method)
        assertEquals("/v1/sessions", delete.path)
        assertEquals("Bearer token-1", delete.getHeader("authorization"))

        val afterwards = repository.token(OWNER)
        assertTrue(afterwards is Outcome.Failure)
    }

    @Test
    fun `the token is stored wrapped, not in clear`() = runTest {
        server.enqueue(sessionResponse("super-secret-access-token"))
        val repository = repository()
        repository.login(EMAIL, "correct-horse-battery")

        val prefs = context.getSharedPreferences("pam_backend_session", Context.MODE_PRIVATE)
        val stored = prefs.all.values.filterIsInstance<String>().joinToString("\n")
        assertTrue(stored.isNotEmpty())
        assertFalse(stored.contains("super-secret-access-token"))
        assertFalse(stored.contains("correct-horse-battery"))

        val again = repository.token(OWNER) as Outcome.Success
        assertEquals("super-secret-access-token", again.value)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a wrong password maps to invalid credentials`() = runTest {
        server.enqueue(jsonResponse(401, "unauthorized"))
        val result = repository().login(EMAIL, "wrong")
        assertValidation(result, AppErrorRules.INVALID_CREDENTIALS)
    }

    @Test
    fun `a throttled login maps to RateLimited`() = runTest {
        server.enqueue(jsonResponse(429, "rate_limited"))
        val result = repository().login(EMAIL, "correct-horse-battery")
        assertTrue(result is Outcome.Failure)
        assertEquals(ErrorKind.RATE_LIMITED, (result as Outcome.Failure).error.kind)
    }

    @Test
    fun `registration maps invite and duplicate-email failures`() = runTest {
        server.enqueue(jsonResponse(403, "invalid_invite"))
        assertValidation(repository().register(EMAIL, "correct-horse-battery", "A", "bad"), AppErrorRules.INVALID_INVITE)

        server.enqueue(jsonResponse(409, "conflict"))
        assertValidation(repository().register(EMAIL, "correct-horse-battery", "A", "invite"), AppErrorRules.EMAIL_TAKEN)
    }

    @Test
    fun `a successful registration does not cache a token`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(201)
                .setHeader("content-type", "application/json")
                .setBody("""{"ownerId":"u_x","email":"$EMAIL","displayName":"A"}"""),
        )
        val repository = repository()
        val registered = repository.register(EMAIL, "correct-horse-battery", "A", "invite")
        assertTrue(registered is Outcome.Success)
        assertTrue(repository.token(OWNER) is Outcome.Failure)
    }

    @Test
    fun `a blank base URL never opens a socket`() = runTest {
        // Explicit blanks: the blank-URL contract must not depend on how this build was invoked.
        val blank = BackendConfig(rawBaseUrl = "", rawLegacyAnalysisUrl = "")
        assertFalse(blank.isEnabled)
        val repository = BackendSessionRepositoryImpl(
            client = OkHttpClient(),
            json = PamJson,
            config = blank,
            keystoreSecretStore = XorSecretStore(),
            context = context,
            clock = clock,
            ioDispatcher = UnconfinedTestDispatcher(),
        )
        assertFalse(repository.isEnabled)
        assertTrue(repository.login(EMAIL, "x") is Outcome.Failure)
        assertEquals(0, server.requestCount)
    }

    // ---- helpers -------------------------------------------------------------------------

    private fun assertValidation(result: Outcome<*>, rule: String) {
        assertTrue(result is Outcome.Failure)
        val error = (result as Outcome.Failure).error
        assertEquals(ErrorKind.VALIDATION, error.kind)
        assertEquals(rule, (error as AppError.Validation).rule)
    }

    private fun repository() = BackendSessionRepositoryImpl(
        client = OkHttpClient(),
        json = PamJson,
        config = endpoints,
        keystoreSecretStore = XorSecretStore(),
        context = context,
        clock = clock,
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    private fun sessionResponse(
        token: String,
        expiresInMinutes: Long = 60,
        refreshToken: String? = null,
    ): MockResponse {
        val expiresAt = Instant.fromEpochMilliseconds(nowMillis + expiresInMinutes * 60_000)
        val refresh = refreshToken?.let { ""","refreshToken":"$it"""" } ?: ""
        return MockResponse()
            .setResponseCode(201)
            .setHeader("content-type", "application/json")
            .setBody(
                """{"accessToken":"$token","expiresAt":"$expiresAt","displayName":"Andhika",""" +
                    """"user":{"ownerId":"$OWNER","email":"$EMAIL","role":"USER"}$refresh}""",
            )
    }

    private fun refreshResponse(token: String, refreshToken: String, expiresInMinutes: Long = 60): MockResponse {
        val expiresAt = Instant.fromEpochMilliseconds(nowMillis + expiresInMinutes * 60_000)
        return MockResponse()
            .setResponseCode(200)
            .setHeader("content-type", "application/json")
            .setBody("""{"accessToken":"$token","expiresAt":"$expiresAt","refreshToken":"$refreshToken"}""")
    }

    private fun jsonResponse(code: Int, errorCode: String) = MockResponse()
        .setResponseCode(code)
        .setHeader("content-type", "application/json")
        .setBody("""{"code":"$errorCode","message":"teknisi","retryable":false,"correlationId":"c-1"}""")

    /** Reversible, but not the plaintext: proves the repository only stores wrapped bytes. */
    private class XorSecretStore : WrappedSecretStore {
        override fun wrap(plainSecret: ByteArray): String =
            Base64.encodeToString(plainSecret.map { (it.toInt() xor 0x5A).toByte() }.toByteArray(), Base64.NO_WRAP)

        override fun unwrap(wrapped: String): ByteArray {
            val decoded = Base64.decode(wrapped, Base64.NO_WRAP)
            assertNotNull(decoded)
            return decoded.map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        }
    }

    private companion object {
        const val EMAIL = "alice@example.test"
        val OWNER = OwnerIdFactory.ownerIdFor(EMAIL)
    }
}
