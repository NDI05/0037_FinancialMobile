package com.pam.recap.data.gmail

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.pam.recap.core.security.WrappedSecretStore
import com.pam.recap.core.util.PamJson
import com.pam.recap.data.gmail.FixtureGmailSource
import com.pam.recap.data.gmail.LivinTemplateRegistry
import com.pam.recap.domain.model.GmailConnectionState
import com.pam.recap.domain.repository.AppPasswordVault
import com.pam.recap.testing.FakeAnalysisRepository
import com.pam.recap.testing.FakeBudgetRepository
import com.pam.recap.testing.FakeGmailConnectionRepository
import com.pam.recap.testing.FakeImportRepository
import com.pam.recap.testing.FakeSessionRepository
import com.pam.recap.testing.FakeSummaryRepository
import com.pam.recap.testing.FakeTransactionRepository
import com.pam.recap.testing.fixedClock
import com.pam.recap.core.time.ClockProvider
import com.pam.recap.domain.model.Session
import com.pam.recap.domain.usecase.DisconnectGmailUseCase
import kotlinx.coroutines.test.runTest
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
 * FR-54/NFR-14: the app password round-trips, exists on disk only as ciphertext, and is cleared on
 * disconnect. (Sign-out clearing is covered by `SignOutUseCaseTest`.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppPasswordVaultTest {
    private val OWNER = "u_owner"

    private lateinit var context: Context
    private lateinit var vault: AppPasswordVault

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("pam_app_password_vault", Context.MODE_PRIVATE).edit().clear().commit()
        vault = AppPasswordVaultImpl(
            context = context,
            secretStore = ReversingSecretStore(),
            json = PamJson,
        )
    }

    @Test
    fun `the credential round-trips`() = runTest {
        vault.save(OWNER, "andhika@example.test", "abcd efgh ijkl mnop")

        val stored = vault.read(OWNER)

        assertEquals("andhika@example.test", stored?.email)
        assertEquals("abcd efgh ijkl mnop", stored?.appPassword)
        assertTrue(vault.hasCredential(OWNER))
    }

    @Test
    fun `the plaintext is never written to preferences`() = runTest {
        vault.save(OWNER, "andhika@example.test", "abcd efgh ijkl mnop")

        val prefs = context.getSharedPreferences("pam_app_password_vault", Context.MODE_PRIVATE)
        val raw = prefs.all.values.filterIsInstance<String>().joinToString("\n")

        assertTrue(raw.isNotEmpty())
        assertFalse(raw.contains("abcd efgh ijkl mnop"))
        assertFalse(raw.contains("andhika@example.test"))
    }

    @Test
    fun `clear removes the credential`() = runTest {
        vault.save(OWNER, "andhika@example.test", "abcd efgh ijkl mnop")
        vault.clear(OWNER)

        assertNull(vault.read(OWNER))
        assertFalse(vault.hasCredential(OWNER))
        assertTrue(
            context.getSharedPreferences("pam_app_password_vault", Context.MODE_PRIVATE).all.isEmpty(),
        )
    }

    @Test
    fun `disconnecting clears the vault`() = runTest {
        val sessions = FakeSessionRepository(
            Session(
                ownerId = "u_owner",
                displayName = "Andhika",
                mailboxLabel = "andhika@example.test",
                signedInAt = Instant.parse("2026-03-01T02:00:00Z"),
                isDemo = false,
            ),
        )
        val connections = FakeGmailConnectionRepository()
        val useCase = DisconnectGmailUseCase(
            gmailSource = FixtureGmailSource(LivinTemplateRegistry()),
            connections = connections,
            sessions = sessions,
            appPasswordVault = vault,
            clock = fixedClock(Instant.parse("2026-03-01T02:00:00Z")),
        )

        vault.save(OWNER, "andhika@example.test", "abcd efgh ijkl mnop")
        val result = useCase("u_owner")

        assertTrue(result is com.pam.recap.core.result.Outcome.Success)
        assertNull(vault.read(OWNER))
        assertEquals(GmailConnectionState.DISCONNECTED, connections.get("u_owner")?.state)
    }

    @Test
    fun `credentials are scoped per owner and clearing B keeps A`() = runTest {
        vault.save("u_a", "a@example.test", "aaaa aaaa aaaa aaaa")
        vault.save("u_b", "b@example.test", "bbbb bbbb bbbb bbbb")

        assertEquals("a@example.test", vault.read("u_a")?.email)
        assertEquals("b@example.test", vault.read("u_b")?.email)
        vault.clear("u_b")
        assertNull(vault.read("u_b"))
        assertEquals("a@example.test", vault.read("u_a")?.email)
    }

    /** Reversible transform, but not the plaintext: a stand-in for the Keystore wrapping. */
    private class ReversingSecretStore : WrappedSecretStore {
        override fun wrap(plainSecret: ByteArray): String =
            Base64.encodeToString(plainSecret.reversedArray(), Base64.NO_WRAP)

        override fun unwrap(wrapped: String): ByteArray =
            Base64.decode(wrapped, Base64.NO_WRAP).reversedArray()
    }
}
