package com.pam.recap.data.account

import com.pam.recap.domain.repository.AppPasswordVault
import com.pam.recap.testing.FakeAppPasswordVault
import com.pam.recap.testing.FakeDemoDataPort
import com.pam.recap.testing.FakeTransactionRepository
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
import com.pam.recap.data.backend.BackendEndpoints
import com.pam.recap.data.backend.BackendSessionRepositoryImpl
import com.pam.recap.domain.repository.BackendSessionRepository
import com.pam.recap.domain.usecase.SignInUseCase
import com.pam.recap.testing.FakeDemoDataMaintenance
import com.pam.recap.testing.FakeSessionRepository
import com.pam.recap.testing.NoopBackendSessionRepository
import com.pam.recap.testing.fixedClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
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
 * FR-51/FR-52: the account source in both modes. With a backend it registers and logs in; without
 * one it creates a local profile and never opens a socket.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalAccountSourceTest {

    private lateinit var server: MockWebServer
    private lateinit var context: Context
    private lateinit var sessions: FakeSessionRepository
    private lateinit var demoData: FakeDemoDataMaintenance
    private val now = Instant.parse("2026-03-01T02:00:00Z")
    private val EmptyVault = FakeAppPasswordVault()
    private val clock = object : ClockProvider {
        override fun now(): Instant = now
    }
    private val demoDataPort = FakeDemoDataPort()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("pam_backend_session", Context.MODE_PRIVATE).edit().clear().commit()
        sessions = FakeSessionRepository()
        demoData = FakeDemoDataMaintenance()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ---- FR-52: offline local profile ------------------------------------------------------
    @Test
    fun `offline mode creates a local profile without a password or a socket`() = runTest {
        val source = offlineSource()

        val result = source.loginOffline("Andhika@Example.test ", "Andhika")

        assertTrue(result is Outcome.Success)
        val session = (result as Outcome.Success).value
        assertEquals("andhika@example.test", session.mailboxLabel?.lowercase())
        assertEquals("Andhika", session.displayName)
        assertEquals(OwnerIdFactory.ownerIdFor("andhika@example.test"), session.ownerId)
        assertTrue(session.isDemo)
        assertEquals(0, server.requestCount)
        assertEquals(false, source.isBackendEnabled)
    }

    @Test
    fun `offline registration also works and rejects an invalid email`() = runTest {
        val source = offlineSource()
        assertTrue(source.register("budi@example.test", "ignored", "Budi", "ignored") is Outcome.Success)

        val invalid = source.loginOffline("not-an-email", null)
        assertTrue(invalid is Outcome.Failure)
        assertEquals(ErrorKind.VALIDATION, (invalid as Outcome.Failure).error.kind)
        assertEquals(
            AppErrorRules.INVALID_EMAIL,
            (invalid.error as AppError.Validation).rule,
        )
    }

    // ---- FR-51: backend account ------------------------------------------------------------
    @Test
    fun `registering with a backend stores the session and calls accounts then sessions`() = runTest {
        server.enqueue(accountCreated())
        server.enqueue(sessionCreated("token-1"))
        val source = backendSource()

        val result = source.register("andi@example.test", "correct-horse-battery", "Andi", "invite-123")

        assertTrue(result is Outcome.Success)
        assertEquals("Andi", sessions.current()?.displayName)
        assertEquals("andi@example.test", sessions.current()?.mailboxLabel)

        val create = server.takeRequest()
        assertEquals("/v1/accounts", create.path)
        val login = server.takeRequest()
        assertEquals("/v1/sessions", login.path)
    }

    @Test
    fun `a bad invite fails before any session is created`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(403).setHeader("content-type", "application/json")
                .setBody("""{"code":"invalid_invite","message":"teknisi","retryable":false,"correlationId":"c"}"""),
        )
        val source = backendSource()

        val result = source.register("andi@example.test", "correct-horse-battery", "Andi", "wrong")

        assertTrue(result is Outcome.Failure)
        assertEquals(
            AppErrorRules.INVALID_INVITE,
            ((result as Outcome.Failure).error as AppError.Validation).rule,
        )
        assertNull(sessions.current())
    }

    @Test
    fun `a short password never reaches the network`() = runTest {
        val source = backendSource()
        val result = source.register("andi@example.test", "short", "Andi", "invite-123")

        assertTrue(result is Outcome.Failure)
        assertEquals(
            AppErrorRules.PASSWORD_TOO_SHORT,
            ((result as Outcome.Failure).error as AppError.Validation).rule,
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a wrong password surfaces invalid credentials and keeps the user signed out`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(401).setHeader("content-type", "application/json")
                .setBody("""{"code":"unauthorized","message":"teknisi","retryable":false,"correlationId":"c"}"""),
        )
        val source = backendSource()

        val result = source.login("andi@example.test", "wrong-password")

        assertTrue(result is Outcome.Failure)
        assertEquals(
            AppErrorRules.INVALID_CREDENTIALS,
            ((result as Outcome.Failure).error as AppError.Validation).rule,
        )
        assertNull(sessions.current())
    }

    // ---- F1: demo data belongs to the offline demo only -------------------------------------

    @Test
    fun `a server account never seeds demo rows, even with no mailbox connected`() = runTest {
        server.enqueue(accountCreated())
        server.enqueue(sessionCreated("token-1"))
        val source = backendSource()

        val result = source.register("andi@example.test", "correct-horse-battery", "Andi", "invite-123")

        assertTrue(result is Outcome.Success)
        assertEquals(emptyList<String>(), demoDataPort.seededOwners)
        assertEquals(listOf(OwnerIdFactory.ownerIdFor("andi@example.test")), demoData.purgedOwners)
        assertEquals(false, (result as Outcome.Success).value.isDemo)
    }

    @Test
    fun `offline mode with a stored credential seeds nothing and purges leftovers`() = runTest {
        val ownerId = OwnerIdFactory.ownerIdFor("andhika@example.test")
        val seed = com.pam.recap.data.seed.DemoDataset.build(
            ownerId = ownerId,
            now = now,
            parser = com.pam.recap.data.gmail.LivinQrisParser(),
            rules = com.pam.recap.domain.usecase.MerchantCategoryRules(),
            fingerprints = com.pam.recap.core.security.FingerprintFactory { id -> "k::$id".toByteArray() },
        ).first()
        val transactions = FakeTransactionRepository(listOf(seed.copy(id = "demo-leftover", fingerprint = "fp")))
        demoData = FakeDemoDataMaintenance(transactions = transactions)
        val source = LocalAccountSource(
            sessions = sessions,
            signIn = SignInUseCase(sessions, demoDataPort, clock),
            backendSessions = NoopBackendSessionRepository(),
            demoData = demoData,
            appPasswordVault = FakeAppPasswordVault(
                com.pam.recap.domain.model.GmailCredential("andhika@example.test", "abcdefghijklmnop"),
            ),
        )

        val result = source.loginOffline("andhika@example.test", "Andhika")

        assertTrue(result is Outcome.Success)
        assertEquals(emptyList<String>(), demoDataPort.seededOwners)
        assertEquals(0, demoData.countDemoRows(ownerId))
        assertEquals(listOf(ownerId), demoData.purgedOwners)
    }

    @Test
    fun `offline mode seeds the demo dataset and labels the session`() = runTest {
        val source = offlineSource()

        val result = source.loginOffline("andhika@example.test", "Andhika")

        assertTrue(result is Outcome.Success)
        assertEquals(listOf(OwnerIdFactory.ownerIdFor("andhika@example.test")), demoDataPort.seededOwners)
        assertTrue((result as Outcome.Success).value.isDemo)
        assertEquals(emptyList<String>(), demoData.purgedOwners)
    }

    @Test
    fun `a server sign-in purges demo rows left by an earlier build`() = runTest {
        val ownerId = OwnerIdFactory.ownerIdFor("andi@example.test")
        val seed = com.pam.recap.data.seed.DemoDataset.build(
            ownerId = ownerId,
            now = now,
            parser = com.pam.recap.data.gmail.LivinQrisParser(),
            rules = com.pam.recap.domain.usecase.MerchantCategoryRules(),
            fingerprints = com.pam.recap.core.security.FingerprintFactory { id -> "k::$id".toByteArray() },
        ).first()
        val transactions = FakeTransactionRepository(
            listOf(
                seed.copy(id = com.pam.recap.data.seed.DemoDataset.seedId("leftover"), fingerprint = "fp-demo"),
                seed.copy(id = "email-1", fingerprint = "fp-email"),
            ),
        )
        demoData = FakeDemoDataMaintenance(transactions = transactions)
        server.enqueue(sessionCreated("token-1"))
        val source = LocalAccountSource(
            sessions = sessions,
            signIn = SignInUseCase(sessions, demoDataPort, clock),
            backendSessions = backendSessions(),
            demoData = demoData,
            appPasswordVault = EmptyVault,
        )

        val result = source.login("andi@example.test", "correct-horse-battery")

        assertTrue(result is Outcome.Success)
        assertEquals(listOf("email-1"), transactions.all().map { it.id })
        assertEquals(0, demoData.countDemoRows(OwnerIdFactory.ownerIdFor("andi@example.test")))
    }

    // ---- helpers -------------------------------------------------------------------------

    private fun offlineSource(vault: AppPasswordVault = EmptyVault) = LocalAccountSource(
        sessions = sessions,
        signIn = SignInUseCase(sessions, demoDataPort, clock),
        backendSessions = NoopBackendSessionRepository(),
        demoData = demoData,
        appPasswordVault = vault,
    )

    private fun backendSource(): LocalAccountSource = LocalAccountSource(
        sessions = sessions,
        signIn = SignInUseCase(sessions, demoDataPort, clock),
        backendSessions = backendSessions(),
        demoData = demoData,
        appPasswordVault = EmptyVault,
    )

    private fun backendSessions(): BackendSessionRepository {
        val endpoints = object : BackendEndpoints {
            override val isEnabled: Boolean = true
            override val analysesUrl: String = server.url("/v1/analyses").toString()
            override fun endpoint(path: String): String = server.url(path).toString()
        }
        return BackendSessionRepositoryImpl(
            client = OkHttpClient(),
            json = PamJson,
            config = endpoints,
            keystoreSecretStore = PassthroughSecretStore(),
            context = context,
            clock = clock,
            ioDispatcher = kotlinx.coroutines.test.UnconfinedTestDispatcher(),
        )
    }

    private fun accountCreated() = MockResponse()
        .setResponseCode(201)
        .setHeader("content-type", "application/json")
        .setBody("""{"ownerId":"u_x","email":"andi@example.test","displayName":"Andi"}""")

    private fun sessionCreated(token: String) = MockResponse()
        .setResponseCode(201)
        .setHeader("content-type", "application/json")
        .setBody("""{"accessToken":"$token","expiresAt":"2026-04-01T00:00:00Z","displayName":"Andi"}""")

    private class PassthroughSecretStore : WrappedSecretStore {
        override fun wrap(plainSecret: ByteArray): String =
            Base64.encodeToString(plainSecret.reversedArray(), Base64.NO_WRAP)

        override fun unwrap(wrapped: String): ByteArray =
            Base64.decode(wrapped, Base64.NO_WRAP).reversedArray()
    }
}
