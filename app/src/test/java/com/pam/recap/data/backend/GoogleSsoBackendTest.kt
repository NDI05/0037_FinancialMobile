package com.pam.recap.data.backend

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.AppErrorRules
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.security.OwnerIdFactory
import com.pam.recap.core.security.WrappedSecretStore
import com.pam.recap.core.time.ClockProvider
import com.pam.recap.core.util.PamJson
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Instant

/**
 * Google SSO contract on the PAM backend (`/v1/auth/google*` + `/v1/me`): the nonce request is
 * anonymous, the ID token exchange stores the session exactly like email/password login, linking
 * uses the existing bearer and never rotates it, and each refusal keeps its machine code so the UI
 * can give a specific next step.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GoogleSsoBackendTest {

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
        context.getSharedPreferences("pam_backend_session", Context.MODE_PRIVATE).edit().clear()
            .commit()
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

    private fun repository() = BackendSessionRepositoryImpl(
        client = OkHttpClient(),
        json = PamJson,
        config = endpoints,
        keystoreSecretStore = XorSecretStore(),
        context = context,
        clock = clock,
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    private fun googleSessionResponse(
        token: String = "token-google",
        googleLinked: Boolean = true,
    ): MockResponse {
        val expiresAt = Instant.fromEpochMilliseconds(nowMillis + 60 * 60_000)
        return MockResponse()
            .setResponseCode(201)
            .setHeader("content-type", "application/json")
            .setBody(
                """{"accessToken":"$token","expiresAt":"$expiresAt","displayName":"Andhika",""" +
                    """"googleLinked":$googleLinked,"user":{"ownerId":"$OWNER","email":"$EMAIL",""" +
                    """"role":"USER"}}""",
            )
    }

    private fun errorResponse(code: Int, errorCode: String) = MockResponse()
        .setResponseCode(code)
        .setHeader("content-type", "application/json")
        .setBody("""{"code":"$errorCode","message":"nope","retryable":false}""")

    @Test
    fun `nonce request is anonymous and parses the challenge`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("content-type", "application/json")
                .setBody("""{"nonce":"n-123","expiresAt":"2026-03-01T02:05:00Z","expiresInSeconds":300}"""),
        )
        val repository = repository()

        val result = repository.googleNonce()
        val request = server.takeRequest()

        assertTrue(result is Outcome.Success)
        val nonce = (result as Outcome.Success<com.pam.recap.domain.model.GoogleNonce>).value
        assertEquals("n-123", nonce.nonce)
        assertEquals(300L, nonce.expiresInSeconds)
        assertEquals("GET", request.method)
        assertEquals("/v1/auth/google/nonce", request.path)
        // Anonymous: a stale PAM session must not block a first Google sign-in.
        assertNull(request.getHeader("Authorization"))
    }

    @Test
    fun `disabled server hides google sso`() = runTest {
        server.enqueue(errorResponse(503, "google_sso_disabled"))
        val result = repository().googleNonce()

        assertTrue(result is Outcome.Failure)
        val error = (result as Outcome.Failure).error
        assertEquals(
            AppErrorRules.GOOGLE_SSO_DISABLED,
            (error as AppError.Configuration).debugDetail,
        )
    }

    @Test
    fun `id token is exchanged for a session and cached like a password login`() = runTest {
        server.enqueue(googleSessionResponse())
        val repository = repository()

        val result = repository.googleSignIn("id-token-abc", "n-123")
        val token = repository.token(OWNER) as Outcome.Success
        val request = server.takeRequest()

        assertTrue(result is Outcome.Success)
        val session = (result as Outcome.Success<com.pam.recap.domain.model.GoogleSignInSession>).value
        assertEquals("andhika@example.test", session.email)
        assertEquals(true, session.googleLinked)
        assertEquals("token-google", token.value)
        assertEquals("POST", request.method)
        assertEquals("/v1/auth/google", request.path)
        assertNull(request.getHeader("Authorization"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("id-token-abc", body["idToken"]?.jsonPrimitive?.content)
        assertEquals("n-123", body["nonce"]?.jsonPrimitive?.content)
    }

    @Test
    fun `invalid google token maps to its own retry guidance`() = runTest {
        server.enqueue(errorResponse(401, "google_invalid_token"))
        val result = repository().googleSignIn("stale", "n-1")

        val error = (result as Outcome.Failure).error
        assertEquals(AppErrorRules.GOOGLE_INVALID_TOKEN, (error as AppError.Validation).rule)
    }

    @Test
    fun `unlinked identity is reported as a 409`() = runTest {
        server.enqueue(errorResponse(409, "google_identity_not_linked"))
        val result = repository().googleSignIn("id-token", "n-1")

        val error = (result as Outcome.Failure).error
        assertEquals(
            AppErrorRules.GOOGLE_IDENTITY_NOT_LINKED,
            (error as AppError.Validation).rule,
        )
    }

    @Test
    fun `inactive account is refused with 403`() = runTest {
        server.enqueue(errorResponse(403, "account_inactive"))
        val result = repository().googleSignIn("id-token", "n-1")

        val error = (result as Outcome.Failure).error
        assertEquals(AppErrorRules.ACCOUNT_INACTIVE, (error as AppError.Validation).rule)
    }

    @Test
    fun `link uses the current bearer and never rotates the session`() = runTest {
        server.enqueue(googleSessionResponse(token = "token-before"))
        val repository = repository()
        repository.googleSignIn("id-token", "n-1")
        server.takeRequest()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("content-type", "application/json")
                .setBody("""{"linked":true,"googleLinked":true}"""),
        )
        val linked = repository.googleLink(OWNER, "id-token-2", "n-2")
        val request = server.takeRequest()

        assertTrue(linked is Outcome.Success)
        assertEquals("POST", request.method)
        assertEquals("/v1/auth/google/link", request.path)
        assertEquals("Bearer token-before", request.getHeader("Authorization"))
        val token = repository.token(OWNER) as Outcome.Success
        assertEquals("token-before", token.value)
    }

    @Test
    fun `link conflict keeps its machine code`() = runTest {
        server.enqueue(googleSessionResponse())
        val repository = repository()
        repository.googleSignIn("id-token", "n-1")
        server.takeRequest()

        server.enqueue(errorResponse(409, "google_identity_conflict"))
        val result = repository.googleLink(OWNER, "id-token-2", "n-2")

        val error = (result as Outcome.Failure).error
        assertEquals(
            AppErrorRules.GOOGLE_IDENTITY_CONFLICT,
            (error as AppError.Validation).rule,
        )
    }

    @Test
    fun `linked flag comes from v1-me`() = runTest {
        server.enqueue(googleSessionResponse())
        val repository = repository()
        repository.googleSignIn("id-token", "n-1")
        server.takeRequest()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("content-type", "application/json")
                .setBody("""{"ownerId":"$OWNER","googleLinked":true}"""),
        )
        val linked = repository.googleLinked(OWNER) as Outcome.Success
        val request = server.takeRequest()

        assertEquals(true, linked.value)
        assertEquals("GET", request.method)
        assertEquals("/v1/me", request.path)
        assertEquals("Bearer token-google", request.getHeader("Authorization"))
    }

    @Test
    fun `linked flag defaults to false when the server omits it`() = runTest {
        server.enqueue(googleSessionResponse())
        val repository = repository()
        repository.googleSignIn("id-token", "n-1")
        server.takeRequest()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("content-type", "application/json")
                .setBody("""{"ownerId":"$OWNER"}"""),
        )
        val linked = repository.googleLinked(OWNER) as Outcome.Success

        assertEquals(false, linked.value)
    }

    @Test
    fun `unexpected nonce failure reports the real status code`() = runTest {
        // Audit P2-2: the fallback used to yield the literal text `backend_${call.code}`.
        server.enqueue(errorResponse(500, "internal_error"))
        val error = (repository().googleNonce() as Outcome.Failure).error

        assertEquals("backend_500", (error as AppError.Network).debugDetail)
        assertTrue("detail must not be the literal template", !error.debugDetail!!.contains("$"))
    }

    @Test
    fun `unexpected linked-flag failure reports the real status code`() = runTest {
        server.enqueue(googleSessionResponse())
        val repository = repository()
        repository.googleSignIn("id-token", "n-1")
        server.takeRequest()

        server.enqueue(errorResponse(502, "bad_gateway"))
        val error = (repository.googleLinked(OWNER) as Outcome.Failure).error

        assertEquals("backend_502", (error as AppError.Network).debugDetail)
        assertTrue("detail must not be the literal template", !error.debugDetail!!.contains("$"))
    }

    private class XorSecretStore : WrappedSecretStore {
        override fun wrap(plainSecret: ByteArray): String =
            Base64.encodeToString(
                plainSecret.map { (it.toInt() xor 0x5A).toByte() }.toByteArray(),
                Base64.NO_WRAP,
            )

        override fun unwrap(wrapped: String): ByteArray =
            Base64.decode(wrapped, Base64.NO_WRAP)
                .map { (it.toInt() xor 0x5A).toByte() }
                .toByteArray()
    }

    private companion object {
        const val EMAIL = "andhika@example.test"
        val OWNER = OwnerIdFactory.ownerIdFor(EMAIL)
    }
}
