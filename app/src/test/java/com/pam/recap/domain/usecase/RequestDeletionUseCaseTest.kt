package com.pam.recap.domain.usecase

import com.pam.recap.core.result.ErrorKind
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.time.ClockProvider
import com.pam.recap.domain.model.Session
import com.pam.recap.domain.repository.BackendDeletionAck
import com.pam.recap.domain.repository.BackendDeletionSync
import com.pam.recap.testing.FakeAnalysisRepository
import com.pam.recap.testing.FakeAppPasswordVault
import com.pam.recap.testing.FakeBudgetRepository
import com.pam.recap.testing.FakeConsentRepository
import com.pam.recap.testing.FakeDeletionRepository
import com.pam.recap.testing.FakeGmailConnectionRepository
import com.pam.recap.testing.FakeImportRepository
import com.pam.recap.testing.FakeSessionRepository
import com.pam.recap.testing.FakeSummaryRepository
import com.pam.recap.testing.FakeTransactionRepository
import com.pam.recap.testing.SequentialIdGenerator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

class RequestDeletionUseCaseTest {
    private val now = Instant.parse("2026-11-22T02:00:00Z")
    private val clock = object : ClockProvider {
        override fun now(): Instant = now
    }
    private val owner = "u_owner"
    private val session = Session(owner, "Owner", null, now, false)

    @Test
    fun `backend rejection is recoverable and does not wipe or sign out`() = runTest {
        val sessions = FakeSessionRepository(session)
        val deletion = FakeDeletionRepository()
        val backend = FakeBackend(isEnabled = true, result = Outcome.Failure(
            com.pam.recap.core.result.AppError.Unauthorized("invalid_password"),
        ))
        val result = useCase(sessions, deletion, backend)(owner, now, "wrong-password")

        assertTrue(result is Outcome.Failure)
        assertEquals(ErrorKind.UNAUTHORIZED, (result as Outcome.Failure).error.kind)
        assertNull(deletion.activeRequest)
        assertTrue(sessions.current() != null)
        assertEquals(1, backend.calls)
    }

    @Test
    fun `server acceptance keeps the receipt and records server state`() = runTest {
        val sessions = FakeSessionRepository(session)
        val deletion = FakeDeletionRepository()
        val backend = FakeBackend(isEnabled = true, result = Outcome.Success(
            BackendDeletionAck("del_server", "PENDING", 7, "receipt-secret"),
        ))
        val result = useCase(sessions, deletion, backend)(owner, now, "correct-password")
            as Outcome.Success

        assertFalse(result.value.deviceOnly)
        assertEquals("PENDING", result.value.serverState)
        assertEquals("receipt-secret", result.value.serverReceipt)
        assertEquals("del_server", result.value.request.id)
        assertEquals(com.pam.recap.domain.model.DeletionState.COMPLETED, deletion.activeRequest?.state)
        assertNull(sessions.current())
        assertEquals(1, backend.calls)
    }

    @Test
    fun `offline deletion is explicitly device only`() = runTest {
        val sessions = FakeSessionRepository(session)
        val deletion = FakeDeletionRepository()
        val backend = FakeBackend(isEnabled = false, result = Outcome.Failure(
            com.pam.recap.core.result.AppError.Configuration("backend_not_configured"),
        ))
        val result = useCase(sessions, deletion, backend)(owner, now, null) as Outcome.Success
        assertTrue(result.value.deviceOnly)
        assertNull(result.value.serverReceipt)
        assertEquals(0, backend.calls)
    }

    private fun useCase(
        sessions: FakeSessionRepository,
        deletion: FakeDeletionRepository,
        backend: FakeBackend,
    ) = RequestDeletionUseCase(
        deletion = deletion,
        transactions = FakeTransactionRepository(),
        summaries = FakeSummaryRepository(),
        analyses = FakeAnalysisRepository(),
        budgets = FakeBudgetRepository(),
        imports = FakeImportRepository(),
        consents = FakeConsentRepository(),
        gmailConnection = FakeGmailConnectionRepository(),
        sessions = sessions,
        clock = clock,
        idGenerator = SequentialIdGenerator(),
        backendDeletion = backend,
        appPasswordVault = FakeAppPasswordVault(),
    )

    private class FakeBackend(
        override val isEnabled: Boolean,
        private val result: Outcome<BackendDeletionAck>,
    ) : BackendDeletionSync {
        var calls = 0
        override suspend fun request(ownerId: String): Outcome<Int> =
            Outcome.Failure(com.pam.recap.core.result.AppError.Configuration("unsupported"))
        override suspend fun request(
            ownerId: String,
            password: String,
            confirmation: String,
        ): Outcome<BackendDeletionAck> {
            calls++
            return result
        }
    }
}
