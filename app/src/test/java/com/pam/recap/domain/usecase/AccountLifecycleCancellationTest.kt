package com.pam.recap.domain.usecase

import com.pam.recap.core.result.Outcome
import com.pam.recap.testing.fixedClock
import com.pam.recap.domain.model.GmailConnection
import com.pam.recap.domain.model.Session
import com.pam.recap.domain.model.SyncCursor
import com.pam.recap.domain.model.SyncPhase
import com.pam.recap.domain.model.SyncSnapshot
import com.pam.recap.core.result.AppError
import com.pam.recap.domain.repository.AutoCategoryScheduler
import com.pam.recap.domain.repository.BackendDeletionAck
import com.pam.recap.domain.repository.BackendDeletionSync
import com.pam.recap.domain.repository.AutoSyncScheduler
import com.pam.recap.domain.repository.SyncCursorStore
import com.pam.recap.testing.FakeAnalysisRepository
import com.pam.recap.testing.FakeAppPasswordVault
import com.pam.recap.testing.FakeDeletionRepository
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
 * Logging out, switching owner, disconnecting Gmail or deleting the account must leave no work
 * behind: a queued check that runs after the session ends would read a mailbox that is no longer
 * ours and could report a success for the wrong owner.
 */
class AccountLifecycleCancellationTest {

    private val owner = "u_owner"
    private val now = Instant.parse("2026-11-23T02:00:00Z")
    private val session = Session(owner, "Owner", "owner@example.test", now, false)

    private class RecordingScheduler : AutoSyncScheduler, AutoCategoryScheduler {
        var scheduled = 0
        var cancelled = 0
        override fun ensureScheduled(ownerId: String) {
            scheduled++
        }

        override fun cancelAll() {
            cancelled++
        }
    }

    private class RecordingCursorStore(var cursor: SyncCursor?) : SyncCursorStore {
        override fun read(ownerId: String): SyncCursor? = cursor

        override fun save(cursor: SyncCursor): Boolean {
            this.cursor = cursor
            return true
        }

        override fun clear(ownerId: String) {
            cursor = null
        }
    }

    private fun cursor() = SyncCursor(
        ownerId = owner,
        watermark = com.pam.recap.domain.model.MailboxWatermark(uidValidity = 42L, highestUid = 10L),
        lastAttemptAt = now,
        lastSuccessAt = now,
        uploadPending = false,
    )

    private fun seededStatus(): SyncStatusHolder = SyncStatusHolder().apply {
        update {
            it.copy(
                ownerId = owner,
                phase = SyncPhase.SUCCEEDED,
                lastAttemptAt = now,
                lastSuccessAt = now,
            )
        }
    }

    private fun seededCategoryStatus(): CategoryStatusHolder = CategoryStatusHolder().apply {
        update { it.copy(pending = 7) }
    }

    @Test
    fun `disconnecting gmail cancels both schedules and drops the durable cursor`() = runTest {
        val scheduler = RecordingScheduler()
        val cursors = RecordingCursorStore(cursor())
        val status = seededStatus()
        val categoryStatus = seededCategoryStatus()
        val connections = FakeGmailConnectionRepository()
        connections.save(GmailConnection.disconnected(owner, now))

        val result = DisconnectGmailUseCase(
            gmailSource = com.pam.recap.data.gmail.FixtureGmailSource(
                com.pam.recap.data.gmail.LivinTemplateRegistry(),
            ),
            connections = connections,
            sessions = FakeSessionRepository(session),
            appPasswordVault = FakeAppPasswordVault(),
            clock = fixedClock(now),
            autoSync = scheduler,
            autoCategory = scheduler,
            syncCursors = cursors,
            syncStatus = status,
            categoryStatus = categoryStatus,
        )(owner)

        assertTrue(result is Outcome.Success)
        assertEquals(2, scheduler.cancelled)
        assertNull("cursor must not survive the disconnect", cursors.cursor)
        assertEquals(SyncSnapshot(), status.snapshot.value)
        assertEquals(0, categoryStatus.snapshot.value.pending)
    }

    @Test
    fun `deleting the account cancels queued work before the rows disappear`() = runTest {
        val scheduler = RecordingScheduler()
        val cursors = RecordingCursorStore(cursor())
        val status = seededStatus()
        val categoryStatus = seededCategoryStatus()
        val transactions = FakeTransactionRepository()
        val sessions = FakeSessionRepository(session)

        val useCase = RequestDeletionUseCase(
            deletion = FakeDeletionRepository(),
            transactions = transactions,
            summaries = FakeSummaryRepository(),
            analyses = FakeAnalysisRepository(),
                        imports = FakeImportRepository(),
            consents = com.pam.recap.testing.FakeConsentRepository(),
            gmailConnection = FakeGmailConnectionRepository(),
            sessions = sessions,
            clock = fixedClock(now),
            idGenerator = com.pam.recap.testing.SequentialIdGenerator(),
            backendDeletion = OfflineBackendDeletion,
            appPasswordVault = FakeAppPasswordVault(),
            autoSync = scheduler,
            autoCategory = scheduler,
            syncStatus = status,
            categoryStatus = categoryStatus,
            syncCursors = cursors,
        )

        val result = useCase(owner, reauthenticatedAt = now)

        assertTrue(result is Outcome.Success)
        assertEquals("both schedulers must be cancelled", 2, scheduler.cancelled)
        assertNull(cursors.cursor)
        assertNull(sessions.current())
        assertEquals(SyncSnapshot(), status.snapshot.value)
        assertEquals(0, categoryStatus.snapshot.value.pending)
    }

    /** The backend deletion path is not what this test exercises; a disabled backend keeps it offline. */
    private object OfflineBackendDeletion : BackendDeletionSync {
        override val isEnabled: Boolean = false

        override suspend fun request(ownerId: String): Outcome<Int> =
            Outcome.Failure(AppError.Configuration("unsupported"))

        override suspend fun request(
            ownerId: String,
            password: String,
            confirmation: String,
        ): Outcome<BackendDeletionAck> = Outcome.Failure(AppError.Configuration("unsupported"))
    }
}
