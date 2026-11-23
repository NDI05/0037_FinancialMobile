package com.pam.recap.domain.usecase

import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.security.WrappedSecretStore
import com.pam.recap.data.gmail.FixtureGmailSource
import com.pam.recap.data.gmail.LivinTemplateRegistry
import com.pam.recap.data.security.ImportUploadJournal
import com.pam.recap.domain.model.MailboxWatermark
import com.pam.recap.domain.model.PendingUploadMonth
import com.pam.recap.domain.model.PendingUploadRun
import com.pam.recap.domain.model.Session
import com.pam.recap.domain.repository.BackendSessionRepository
import com.pam.recap.testing.FakeAnalysisRepository
import com.pam.recap.testing.FakeAppPasswordVault
import com.pam.recap.testing.FakeBudgetRepository
import com.pam.recap.testing.FakeGmailConnectionRepository
import com.pam.recap.testing.FakeImportRepository
import com.pam.recap.testing.FakeSessionRepository
import com.pam.recap.testing.FakeSummaryRepository
import com.pam.recap.testing.FakeTransactionRepository
import com.pam.recap.testing.fixedClock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Instant

/**
 * P1 follow-up: a durable pending-upload snapshot is owner scoped and must not outlive the session
 * or mailbox it belongs to, otherwise a later re-sign-in could close an EMPTY job from nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PendingJournalCleanupTest {

    private lateinit var context: Context
    private lateinit var journal: ImportUploadJournal
    private val owner = "u_owner"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("pam_backend_session", Context.MODE_PRIVATE).edit().clear().commit()
        journal = ImportUploadJournal(context, XorStore())
    }

    private fun pending(localJobId: String) = PendingUploadRun(
        ownerId = owner,
        localJobId = localJobId,
        primaryMonthId = "2026-11",
        localState = "COMPLETE",
        templateVersion = "livin-v1",
        watermark = MailboxWatermark(uidValidity = 1L, highestUid = 77L),
        months = mapOf("2026-11" to PendingUploadMonth("2026-11", fingerprints = listOf("f".repeat(32)))),
    )

    @Test
    fun `sign out clears the pending upload snapshot for that owner`() = runTest {
        assertTrue(journal.savePending(pending("job_1")))
        assertNotNull(journal.readPending(owner))
        val sessions = FakeSessionRepository(Session(owner, "Andhika", null, Instant.parse("2026-11-01T00:00:00Z"), false))
        SignOutUseCase(
            sessions = sessions,
            gmailSource = FixtureGmailSource(LivinTemplateRegistry()),
            gmailConnection = FakeGmailConnectionRepository(),
            transactions = FakeTransactionRepository(),
            summaries = FakeSummaryRepository(),
            analyses = FakeAnalysisRepository(),
            budgets = FakeBudgetRepository(),
            imports = FakeImportRepository(),
            backendSessions = RecordingBackendSessions(),
            appPasswordVault = FakeAppPasswordVault(),
            clock = fixedClock(Instant.parse("2026-11-23T00:00:00Z")),
            importUploadJournal = journal,
        )(owner)
        assertNull(journal.readPending(owner))
    }

    @Test
    fun `disconnect clears the pending snapshot and leaves other owners alone`() = runTest {
        assertTrue(journal.savePending(pending("job_1")))
        assertTrue(
            journal.savePending(
                pending("job_2").copy(ownerId = "u_other"),
            ),
        )
        val sessions = FakeSessionRepository(Session(owner, "Andhika", null, Instant.parse("2026-11-01T00:00:00Z"), false))
        DisconnectGmailUseCase(
            gmailSource = FixtureGmailSource(LivinTemplateRegistry()),
            connections = FakeGmailConnectionRepository(),
            sessions = sessions,
            appPasswordVault = FakeAppPasswordVault(),
            clock = fixedClock(Instant.parse("2026-11-23T00:00:00Z")),
            importUploadJournal = journal,
        )(owner)
        assertNull(journal.readPending(owner))
        assertNotNull(journal.readPending("u_other"))
    }

    private class RecordingBackendSessions : BackendSessionRepository {
        override val isEnabled = true
        override suspend fun register(email: String, password: String, displayName: String?, inviteCode: String) = Outcome.Success(Unit)
        override suspend fun login(email: String, password: String) = Outcome.Success(Unit)
        override suspend fun token(ownerId: String) = Outcome.Success("token")
        override suspend fun revoke() = Unit
        override fun clear() = Unit
    }

    private class XorStore : WrappedSecretStore {
        override fun wrap(plainSecret: ByteArray): String =
            Base64.encodeToString(plainSecret.map { (it.toInt() xor 0x33).toByte() }.toByteArray(), Base64.NO_WRAP)

        override fun unwrap(wrapped: String): ByteArray =
            Base64.decode(wrapped, Base64.NO_WRAP).map { (it.toInt() xor 0x33).toByte() }.toByteArray()
    }
}
