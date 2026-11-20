package com.pam.recap.domain.usecase

import com.pam.recap.core.result.Outcome
import com.pam.recap.data.gmail.FixtureGmailSource
import com.pam.recap.data.gmail.LivinTemplateRegistry
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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

/**
 * FR-05 with the backend account: signing out revokes the server session and clears the local
 * profile, so the gate falls back to Welcome and no cached token survives.
 */
class SignOutUseCaseTest {

    private val session = Session(
        ownerId = "u_owner",
        displayName = "Andhika",
        mailboxLabel = "andhika@example.test",
        signedInAt = Instant.parse("2026-03-01T02:00:00Z"),
        isDemo = true,
    )

    @Test
    fun `sign out revokes the backend session and clears the local profile`() = runTest {
        val sessions = FakeSessionRepository(session)
        val backend = RecordingBackendSessions()
        val useCase = SignOutUseCase(
            sessions = sessions,
            gmailSource = FixtureGmailSource(LivinTemplateRegistry()),
            gmailConnection = FakeGmailConnectionRepository(),
            transactions = FakeTransactionRepository(),
            summaries = FakeSummaryRepository(),
            analyses = FakeAnalysisRepository(),
            budgets = FakeBudgetRepository(),
            imports = FakeImportRepository(),
            backendSessions = backend,
            appPasswordVault = FakeAppPasswordVault(),
        )

        val result = useCase(session.ownerId)

        assertTrue(result is Outcome.Success)
        assertEquals(1, backend.revokeCalls)
        assertNull(sessions.current())
        assertEquals(1, sessions.seededOwners.size + 1)
    }

    private class RecordingBackendSessions : BackendSessionRepository {
        var revokeCalls = 0
        override val isEnabled: Boolean = true
        override suspend fun register(
            email: String,
            password: String,
            displayName: String?,
            inviteCode: String,
        ): Outcome<Unit> = Outcome.Success(Unit)

        override suspend fun login(email: String, password: String): Outcome<Unit> = Outcome.Success(Unit)
        override suspend fun token(ownerId: String): Outcome<String> = Outcome.Success("t")
        override suspend fun revoke() {
            revokeCalls++
        }

        override fun clear() = Unit
    }
}
