package com.pam.recap.data.backend

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.ErrorKind
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.security.WrappedSecretStore
import com.pam.recap.core.time.ClockProvider
import com.pam.recap.core.util.PamJson
import com.pam.recap.domain.model.Session
import com.pam.recap.domain.repository.IdentityTokenProvider
import com.pam.recap.domain.repository.SessionRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
 * E2: the session is exchanged once, cached as ciphertext, refreshed five minutes before expiry,
 * re-acquired for another owner, and revoked on sign-out (contract §2.1/§2.2).
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
    fun `exchanges once and reuses the cached token`() = runTest {
        server.enqueue(sessionResponse("token-1"))
        val identity = StubIdentity(Outcome.Success("google-id-token"))
        val repository = repository(identity)

        val first = repository.token(OWNER, isDemo = false) as Outcome.Success
        nowMillis += 60_000
        val second = repository.token(OWNER, isDemo = false) as Outcome.Success

        assertEquals("token-1", first.value)
        assertEquals("token-1", second.value)
        assertEquals(1, server.requestCount)
        assertEquals(1, identity.calls)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/sessions", request.path)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("google-id-token", body["idToken"]?.jsonPrimitive?.content)
    }

    @Test
    fun `re-exchanges when less than five minutes remain`() = runTest {
        server.enqueue(sessionResponse("token-1", expiresInMinutes = 60))
        server.enqueue(sessionResponse("token-2", expiresInMinutes = 60))
        val repository = repository(StubIdentity(Outcome.Success("google-id-token")))

        repository.token(OWNER, isDemo = false)
        // Move to four minutes before expiry: the cache is no longer usable.
        nowMillis += 56 * 60_000
        val refreshed = repository.token(OWNER, isDemo = false) as Outcome.Success

        assertEquals("token-2", refreshed.value)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `re-exchanges for a different owner`() = runTest {
        server.enqueue(sessionResponse("token-a"))
        server.enqueue(sessionResponse("token-b"))
        val repository = repository(StubIdentity(Outcome.Success("google-id-token")))

        repository.token("u_alice", isDemo = false)
        val other = repository.token("u_bob", isDemo = false) as Outcome.Success

        assertEquals("token-b", other.value)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `sign out revokes on the server and drops the cache`() = runTest {
        server.enqueue(sessionResponse("token-1"))
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(sessionResponse("token-2"))
        val repository = repository(StubIdentity(Outcome.Success("google-id-token")))

        repository.token(OWNER, isDemo = false)
        repository.revoke()

        val sessionRequest = server.takeRequest()
        assertEquals("POST", sessionRequest.method)
        val delete = server.takeRequest()
        assertEquals("DELETE", delete.method)
        assertEquals("/v1/sessions", delete.path)
        assertEquals("Bearer token-1", delete.getHeader("authorization"))

        val reacquired = repository.token(OWNER, isDemo = false) as Outcome.Success
        assertEquals("token-2", reacquired.value)
    }

    @Test
    fun `a demo session sends the development token, never a Google token`() = runTest {
        server.enqueue(sessionResponse("token-demo"))
        val identity = StubIdentity(Outcome.Failure(AppError.Configuration("no_google_token")))
        val repository = repository(identity, isDemo = true)

        val result = repository.token(OWNER, isDemo = true) as Outcome.Success

        assertEquals("token-demo", result.value)
        assertEquals(0, identity.calls)
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        val idToken = body["idToken"]!!.jsonPrimitive.content
        assertTrue(idToken.startsWith("dev:$OWNER:"))
    }

    @Test
    fun `a Google identity failure is surfaced, not retried blindly`() = runTest {
        val repository = repository(StubIdentity(Outcome.Failure(AppError.PermissionRequired("no_credential"))))

        val result = repository.token(OWNER, isDemo = false)

        assertTrue(result is Outcome.Failure)
        assertEquals(ErrorKind.PERMISSION_REQUIRED, (result as Outcome.Failure).error.kind)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `the token is stored wrapped, not in clear`() = runTest {
        server.enqueue(sessionResponse("super-secret-access-token"))
        val repository = repository(StubIdentity(Outcome.Success("google-id-token")))

        repository.token(OWNER, isDemo = false)

        val prefs = context.getSharedPreferences("pam_backend_session", Context.MODE_PRIVATE)
        val stored = prefs.all.values.filterIsInstance<String>().joinToString("\n")
        assertTrue(stored.isNotEmpty())
        assertFalse(stored.contains("super-secret-access-token"))
        assertFalse(stored.contains("google-id-token"))
        // But the round-trip still works.
        nowMillis += 1_000
        val again = repository.token(OWNER, isDemo = false) as Outcome.Success
        assertEquals("super-secret-access-token", again.value)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `the development token is allowed only without a Google client id`() {
        assertTrue(demoTokenAllowed(""))
        assertFalse(demoTokenAllowed("123.apps.googleusercontent.com"))
    }

    // ---- helpers -------------------------------------------------------------------------

    private fun repository(
        identity: IdentityTokenProvider,
        isDemo: Boolean = false,
    ) = BackendSessionRepositoryImpl(
        client = OkHttpClient(),
        json = PamJson,
        config = endpoints,
        identityTokens = identity,
        sessions = StubSessions(isDemo),
        keystoreSecretStore = XorSecretStore(),
        context = context,
        clock = clock,
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    private fun sessionResponse(token: String, expiresInMinutes: Long = 60): MockResponse {
        val expiresAt = Instant.fromEpochMilliseconds(nowMillis + expiresInMinutes * 60_000)
        return MockResponse()
            .setResponseCode(201)
            .setHeader("content-type", "application/json")
            .setBody("""{"accessToken":"$token","expiresAt":"$expiresAt","displayName":"Alice"}""")
    }

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

    private class StubIdentity(private val result: Outcome<String>) : IdentityTokenProvider {
        var calls = 0
        override suspend fun idToken(): Outcome<String> {
            calls++
            return result
        }
    }

    private class StubSessions(private val isDemo: Boolean) : SessionRepository {
        private val flow = MutableStateFlow(
            Session(
                ownerId = "u_owner",
                displayName = "Andhika",
                mailboxLabel = null,
                signedInAt = Instant.parse("2026-03-01T00:00:00Z"),
                isDemo = isDemo,
            ),
        )
        override fun observe(): Flow<Session?> = flow
        override suspend fun current(): Session? = flow.value
        override suspend fun signIn(session: Session) = Unit
        override suspend fun updateMailbox(ownerId: String, mailboxLabel: String?) = Unit
        override suspend fun signOut() = Unit
        override suspend fun isDemoSeeded(ownerId: String): Boolean = true
        override suspend fun markDemoSeeded(ownerId: String) = Unit
    }

    private companion object {
        const val OWNER = "u_owner_hash"
    }
}
