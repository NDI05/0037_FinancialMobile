package com.pam.recap.domain.usecase

import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.domain.model.ImportJob
import com.pam.recap.domain.model.ImportState
import com.pam.recap.domain.model.Session
import com.pam.recap.testing.FakeImportRepository
import com.pam.recap.testing.FakeSessionRepository
import com.pam.recap.testing.fixedClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

/**
 * The coordinator is the contract between the status sheet and the import pipeline. These tests pin
 * the properties the owner asked for: repeated taps share one flight, the app-level state outlives
 * the sheet, progress comes from the pipeline, and a conflict is not a failure.
 */
class SyncSessionCoordinatorTest {

    private val now: Instant = Instant.parse("2026-11-23T04:00:00Z")
    private val month = JakartaMonth.of(now)
    private val clock = fixedClock(now)

    private fun coordinator(
        runner: SyncSessionRunner,
        imports: FakeImportRepository = FakeImportRepository(),
        status: SyncStatusHolder = SyncStatusHolder(),
        sessions: FakeSessionRepository = FakeSessionRepository(
            Session("u_owner", "Synthetic", null, now, isDemo = false),
        ),
        scope: kotlinx.coroutines.CoroutineScope,
    ) = SyncSessionCoordinator(
        runner = runner,
        imports = imports,
        sessions = sessions,
        status = status,
        clock = clock,
        scope = scope,
    )

    private fun job(
        state: ImportState = ImportState.RUNNING,
        processed: Int = 0,
        expected: Int? = null,
        matched: Int = 0,
        startedAt: Instant = now,
        finishedAt: Instant? = null,
    ) = ImportJob(
        id = "job-1",
        ownerId = "u_owner",
        month = month,
        state = state,
        pagesProcessed = processed,
        pagesExpected = expected,
        matchedCount = matched,
        importedCount = matched,
        duplicateCount = 0,
        skippedCount = 0,
        unresolvedCount = 0,
        templateVersion = "v1",
        startedAt = startedAt,
        finishedAt = finishedAt,
    )

    @Test
    fun `repeated taps while a run is active never start a second import`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var runs = 0
        val runner = object : SyncSessionRunner {
            override suspend fun run(month: JakartaMonth): Outcome<ImportJob> {
                runs += 1
                gate.await()
                return Outcome.Success(job(state = ImportState.COMPLETE, matched = 7))
            }

            override suspend fun resumeUpload(month: JakartaMonth): Outcome<ImportJob> =
                error("manual run must not resume an upload without a pending snapshot")
        }
        val coordinator = coordinator(runner, scope = this)

        val first = launch { coordinator.runNow(month) }
        // Let the first attempt take the gate, then tap again twice from "the sheet".
        testScheduler.advanceUntilIdle()
        coordinator.requestSync(month)
        coordinator.requestSync(month)
        testScheduler.advanceUntilIdle()
        assertEquals(1, runs)
        assertTrue(coordinator.state.value.running)
        assertEquals(1L, coordinator.state.value.attempt)

        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        first.join()
        assertFalse(coordinator.state.value.running)
        assertEquals(1, runs)
        assertEquals(1L, coordinator.state.value.attempt)
    }

    @Test
    fun `state outlives the caller so reopening the sheet shows the same result`() = runTest {
        val runner = object : SyncSessionRunner {
            override suspend fun run(month: JakartaMonth): Outcome<ImportJob> =
                Outcome.Success(job(state = ImportState.COMPLETE, matched = 4))

            override suspend fun resumeUpload(month: JakartaMonth): Outcome<ImportJob> =
                Outcome.Failure(AppError.NotFound("no_pending_upload"))
        }
        val imports = FakeImportRepository()
        val coordinator = coordinator(runner, imports = imports, scope = this)

        coordinator.runNow(month)

        assertEquals(false, coordinator.state.value.running)
        assertNull(coordinator.state.value.error)
        assertNotNull(coordinator.state.value.lastJob)
        assertEquals(month, coordinator.state.value.month)
    }

    @Test
    fun `pending upload snapshot routes the tap to upload recovery instead of a new import`() = runTest {
        var resumed = 0
        var imported = 0
        val runner = object : SyncSessionRunner {
            override suspend fun run(month: JakartaMonth): Outcome<ImportJob> {
                imported += 1
                return Outcome.Success(job(state = ImportState.COMPLETE))
            }

            override suspend fun resumeUpload(month: JakartaMonth): Outcome<ImportJob> {
                resumed += 1
                return Outcome.Success(job(state = ImportState.COMPLETE))
            }
        }
        val status = SyncStatusHolder().apply { update { it.copy(pendingUpload = true) } }
        val coordinator = coordinator(runner, status = status, scope = this)

        coordinator.runNow(month)

        assertEquals(0, imported)
        assertEquals(1, resumed)
    }

    @Test
    fun `an already running pipeline is reported as conflict, not as a failed sync`() = runTest {
        val runner = object : SyncSessionRunner {
            override suspend fun run(month: JakartaMonth): Outcome<ImportJob> =
                Outcome.Failure(AppError.Conflict("sync_in_progress"))

            override suspend fun resumeUpload(month: JakartaMonth): Outcome<ImportJob> =
                Outcome.Failure(AppError.Conflict("sync_in_progress"))
        }
        val coordinator = coordinator(runner, scope = this)

        coordinator.runNow(month)

        assertNull(coordinator.state.value.error)
        assertTrue(coordinator.state.value.conflict)
        assertFalse(coordinator.state.value.running)
    }

    @Test
    fun `progress reflects the pipeline page counters, not a timer`() = runTest {
        val imports = FakeImportRepository()
        val runner = object : SyncSessionRunner {
            override suspend fun run(month: JakartaMonth): Outcome<ImportJob> {
                imports.create(job(state = ImportState.RUNNING, processed = 2, expected = 6))
                awaitCancellation()
            }

            override suspend fun resumeUpload(month: JakartaMonth): Outcome<ImportJob> =
                Outcome.Failure(AppError.NotFound("no_pending_upload"))
        }
        val scope = kotlinx.coroutines.CoroutineScope(
            UnconfinedTestDispatcher(testScheduler) + kotlinx.coroutines.Job(),
        )
        val coordinator = coordinator(runner, imports = imports, scope = scope)
        coordinator.requestSync(month)
        testScheduler.advanceUntilIdle()
        try {
            assertTrue(coordinator.state.value.running)
        assertEquals(2, coordinator.state.value.pagesProcessed)
        assertEquals(6, coordinator.state.value.pagesExpected)
        assertTrue(coordinator.state.value.hasDefiniteProgress)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a missing session fails honestly without touching the pipeline`() = runTest {
        var runs = 0
        val runner = object : SyncSessionRunner {
            override suspend fun run(month: JakartaMonth): Outcome<ImportJob> {
                runs += 1
                return Outcome.Success(job(state = ImportState.COMPLETE))
            }

            override suspend fun resumeUpload(month: JakartaMonth): Outcome<ImportJob> =
                Outcome.Success(job(state = ImportState.COMPLETE))
        }
        val coordinator = coordinator(
            runner = runner,
            sessions = FakeSessionRepository(null),
            scope = this,
        )

        coordinator.runNow(month)

        assertEquals(0, runs)
        assertEquals(AppError.Configuration("not_signed_in"), coordinator.state.value.error)
        assertFalse(coordinator.state.value.running)
    }
}
