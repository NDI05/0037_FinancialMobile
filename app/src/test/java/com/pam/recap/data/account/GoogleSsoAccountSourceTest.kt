package com.pam.recap.data.account

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.pam.recap.BuildConfig
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.AppErrorRules
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.security.OwnerIdFactory
import com.pam.recap.core.security.WrappedSecretStore
import com.pam.recap.core.time.ClockProvider
import com.pam.recap.core.util.PamJson
import com.pam.recap.data.backend.BackendEndpoints
import com.pam.recap.data.backend.BackendSessionRepositoryImpl
import com.pam.recap.domain.repository.BackendSessionRepository
import com.pam.recap.domain.usecase.SignInUseCase
import com.pam.recap.testing.FakeAppPasswordVault
import com.pam.recap.testing.FakeDemoDataMaintenance
import com.pam.recap.testing.FakeDemoDataPort
import com.pam.recap.testing.FakeSessionRepository
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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Instant

/**
 * Google SSO through the account repository: a successful exchange creates the same local profile
 * email/password login does (with the linked flag), and linking attaches the identity to the
 * *current* owner without touching the session, role or Gmail state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GoogleSsoAccountSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var context: Context
    private lateinit var sessions: FakeSessionRepository
    private val now = Instant.parse("2026-11-24T07:00:00Z")
    private val clock = object : ClockProvider {
        override fun now(): Instant = now
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("pam_backend_session", Context.MODE_PRIVATE).edit().clear()
            .commit()
        sessions = FakeSessionRepository()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun source(backendSessions: BackendSessionRepository? = null) = LocalAccountSource(
        sessions = sessions,
        signIn = SignInUseCase(sessions, FakeDemoDataPort(), clock),
        backendSessions = backendSessions ?: backendSessions(),
        demoData = FakeDemoDataMaintenance(),
        appPasswordVault = FakeAppPasswordVault(),
    )

    private fun backendSessions(): BackendSessionRepository = BackendSessionRepositoryImpl(
        client = OkHttpClient(),
        json = PamJson,
        config = object : BackendEndpoints {
            override val isEnabled: Boolean = true
            override val analysesUrl: String = server.url("/v1/analyses").toString()
            override fun endpoint(path: String): String = server.url(path).toString()
        },
        keystoreSecretStore = PassthroughSecretStore(),
        context = context,
        clock = clock,
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    private fun googleSession(email: String = EMAIL, token: String = "token-google") =
        MockResponse()
            .setResponseCode(201)
            .setHeader("content-type", "application/json")
            .setBody(
                """{"accessToken":"$token","expiresAt":"2026-11-24T08:00:00Z",""" +
                    """"displayName":"Andhika","googleLinked":true,"user":{"ownerId":"${OWNER}",""" +
                    """"email":"$email","role":"USER"}}""",
            )

    private fun error(code: Int, code_: String) = MockResponse()
        .setResponseCode(code)
        .setHeader("content-type", "application/json")
        .setBody("""{"code":"$code_","message":"nope","retryable":false}""")

    @Test
    fun `google sign in creates the same profile as password login`() = runTest {
        server.enqueue(googleSession())
        val source = source()

        val result = source.googleSignIn("id-token", "n-1")
        val request = server.takeRequest()

        assertTrue(result is Outcome.Success)
        assertEquals("/v1/auth/google", request.path)
        val stored = sessions.current()
        assertEquals(OWNER, stored?.ownerId)
        assertEquals(EMAIL, stored?.mailboxLabel)
        assertEquals("Andhika", stored?.displayName)
        assertEquals(true, stored?.googleLinked)
        assertEquals(false, stored?.isDemo)
    }

    @Test
    fun `refused google sign in stores no session`() = runTest {
        server.enqueue(error(409, "google_identity_not_linked"))
        val result = source().googleSignIn("id-token", "n-1")

        assertTrue(result is Outcome.Failure)
        assertEquals(
            AppErrorRules.GOOGLE_IDENTITY_NOT_LINKED,
            ((result as Outcome.Failure).error as AppError.Validation).rule,
        )
        assertNull(sessions.current())
    }

    @Test
    fun `linking sets the flag on the current owner and keeps the session`() = runTest {
        server.enqueue(googleSession())
        val source = source()
        source.googleSignIn("id-token", "n-1")
        server.takeRequest()
        val before = sessions.current()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("content-type", "application/json")
                .setBody("""{"linked":true,"googleLinked":true}"""),
        )
        val linked = source.googleLink("id-token", "n-2")
        val request = server.takeRequest()

        assertTrue(linked is Outcome.Success)
        assertEquals("/v1/auth/google/link", request.path)
        assertEquals("Bearer token-google", request.getHeader("Authorization"))
        assertEquals(before?.ownerId, sessions.current()?.ownerId)
        assertEquals(true, sessions.current()?.googleLinked)
        assertTrue(sessions.googleLinkedOwners.contains(OWNER))
    }

    @Test
    fun `linking without a session fails before any network call`() = runTest {
        val result = source().googleLink("id-token", "n-1")

        assertTrue(result is Outcome.Failure)
        assertEquals(
            AppErrorRules.NOT_SIGNED_IN,
            ((result as Outcome.Failure).error as AppError.Unauthorized).debugDetail,
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `sso is advertised only when the build configured a client id`() {
        // local.properties is owner-specific: a UAT machine sets the Web OAuth client ID while a
        // clean CI checkout does not. The rule under test is the mapping, not this machine's value.
        val configured = BuildConfig.PAM_GOOGLE_SSO_CLIENT_ID.isNotBlank()
        assertEquals(configured, BuildConfig.PAM_GOOGLE_SSO_ENABLED)
        assertEquals(configured, source().isGoogleSsoEnabled)
        if (!configured) assertFalse(source().isGoogleSsoEnabled)
    }

    private class PassthroughSecretStore : WrappedSecretStore {
        override fun wrap(plainSecret: ByteArray): String =
            Base64.encodeToString(plainSecret, Base64.NO_WRAP)

        override fun unwrap(wrapped: String): ByteArray =
            Base64.decode(wrapped, Base64.NO_WRAP)
    }

    private companion object {
        const val EMAIL = "andhika@example.test"
        val OWNER = OwnerIdFactory.ownerIdFor(EMAIL)
    }
}
