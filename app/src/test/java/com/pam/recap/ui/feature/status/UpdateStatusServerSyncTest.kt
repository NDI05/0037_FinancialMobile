package com.pam.recap.ui.feature.status

import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.AppErrorRules
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.time.ClockProvider
import com.pam.recap.domain.model.GmailLinkStatus
import com.pam.recap.domain.model.GmailSyncProgress
import com.pam.recap.domain.model.GmailSyncRequest
import com.pam.recap.domain.model.GmailSyncState
import com.pam.recap.domain.model.ImportJob
import com.pam.recap.domain.model.Session
import com.pam.recap.domain.repository.GmailLinkRepository
import com.pam.recap.domain.repository.NetworkMonitor
import com.pam.recap.domain.usecase.CategoryStatusHolder
import com.pam.recap.domain.usecase.ResolveInitialMonthUseCase
import com.pam.recap.domain.usecase.SyncSessionCoordinator
import com.pam.recap.domain.usecase.SyncSessionRunner
import com.pam.recap.domain.usecase.SyncStatusHolder
import com.pam.recap.testing.FakeImportRepository
import com.pam.recap.testing.FakeSessionRepository
import com.pam.recap.testing.FakeTransactionRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Server-sync projection of the status sheet (contract §9): state copy inputs, enqueue outcomes,
 * cooldown handling, reauth, and the polling lifetime. No test here starts a real import.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateStatusServerSyncTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val clock = MutableClock(Instant.parse("2026-11-24T05:00:00Z"))
    private lateinit var sessions: FakeSessionRepository
    private lateinit var gmail: FakeServerGmail
    private lateinit var coordinator: SyncSessionCoordinator

    private val owner = Session(
        ownerId = "own_status_sync",
        displayName = "PM Status",
        mailboxLabel = null,
        signedInAt = Instant.parse("2026-11-01T00:00:00Z"),
        isDemo = false,
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        sessions = FakeSessionRepository(owner)
        gmail = FakeServerGmail()
        coordinator = SyncSessionCoordinator(
            runner = object : SyncSessionRunner {
                override suspend fun run(month: com.pam.recap.core.time.JakartaMonth) =
                    Outcome.Failure(AppError.Unknown("not_used"))
                override suspend fun resumeUpload(month: com.pam.recap.core.time.JakartaMonth) =
                    Outcome.Failure(AppError.Unknown("not_used"))
            },
            imports = FakeImportRepository(),
            sessions = sessions,
            status = SyncStatusHolder(),
            clock = clock,
            scope = CoroutineScope(dispatcher),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Opens the sheet inside a test body and always cancels its poll loop afterwards: the loop is a
     * `while (isActive) delay(...)` on the test scheduler, so leaving it running would make
     * `runTest` advance time forever waiting for an idle scheduler.
     */
    private fun sheetTest(block: suspend kotlinx.coroutines.test.TestScope.(UpdateStatusViewModel) -> Unit) =
        runTest(dispatcher) {
            val vm = newViewModel()
            try {
                block(vm)
            } finally {
                vm.onSheetClosed()
            }
        }

    private fun newViewModel(coordinator: SyncSessionCoordinator = this.coordinator) = UpdateStatusViewModel(
        syncStatus = SyncStatusHolder(),
        categoryStatus = CategoryStatusHolder(),
        network = object : NetworkMonitor {
            override fun isOnline(): Boolean = true
            override fun observe(): Flow<Boolean> = flowOf(true)
        },
        clock = clock,
        sessions = sessions,
        resolveInitialMonth = ResolveInitialMonthUseCase(FakeTransactionRepository(), clock),
        coordinator = coordinator,
        gmail = gmail,
    )

    @Test
    fun `opening the sheet reads status once and posts nothing`() = sheetTest { vm ->
        vm.onSheetOpened()

        assertEquals(1, gmail.statusCalls)
        assertEquals(0, gmail.requestCalls)
        assertTrue(vm.serverSync.value.available)
    }

    @Test
    fun `not linked keeps the legacy device-sync sheet`() = sheetTest { vm ->
        gmail.statusResult = Outcome.Success(GmailLinkStatus.NotConnected)

        vm.onSheetOpened()

        assertFalse(vm.serverSync.value.available)
        assertEquals(GmailServerSyncUi.Hidden, vm.serverSync.value)
    }

    @Test
    fun `backfill exposes the running month and page progress`() = sheetTest { vm ->
        gmail.statusResult = Outcome.Success(
            connectedStatus(
                syncState = GmailSyncState.BACKFILL,
                progress = GmailSyncProgress(
                    phase = "backfill",
                    month = com.pam.recap.core.time.JakartaMonth.parse("2026-08"),
                    monthsDone = 2,
                    monthsTotal = 12,
                    pagesDone = 7,
                ),
            ),
        )

        vm.onSheetOpened()

        val state = vm.serverSync.value
        assertTrue(state.running)
        assertEquals(2, state.progress?.monthsDone)
        assertEquals(7, state.progress?.pagesDone)
    }

    @Test
    fun `idle status without counters says waiting instead of showing zeroes`() = sheetTest { vm ->
        vm.onSheetOpened()

        val state = vm.serverSync.value
        assertFalse(state.running)
        assertNull(state.progress)
        assertFalse(state.needsReauth)
    }

    @Test
    fun `sync tap enqueues a server job and shows the queued notice`() = sheetTest { vm ->
        vm.onSheetOpened()

        vm.syncServerNow()

        assertEquals(1, gmail.requestCalls)
        assertEquals(GmailSyncNotice.QUEUED, vm.serverSync.value.notice)
        assertEquals(2, gmail.statusCalls)
    }

    @Test
    fun `a stale sync_requested_at marker never pins the connecting label`() = sheetTest { vm ->
        // Older backend: the request drained, but the marker is still in the projection.
        gmail.statusResult = Outcome.Success(
            connectedStatus().copy(syncRequestedAt = Instant.parse("2026-11-24T04:00:00Z")),
        )

        vm.onSheetOpened()

        val opened = vm.serverSync.value
        assertEquals(GmailSyncState.IDLE, opened.syncState)
        assertFalse("the server marker alone must not fake an active request", opened.connecting(clock.now()))
    }

    @Test
    fun `after a 202 an idle status with the marker still present settles on waiting`() = sheetTest { vm ->
        vm.onSheetOpened()
        vm.syncServerNow()
        assertEquals(1, gmail.requestCalls)

        // The worker is done (idle) yet the old server never cleared sync_requested_at.
        gmail.statusResult = Outcome.Success(
            connectedStatus().copy(syncRequestedAt = clock.now()),
        )
        vm.refreshServerSync()
        runCurrent()

        val settled = vm.serverSync.value
        assertEquals(GmailSyncState.IDLE, settled.syncState)
        assertFalse(settled.connecting(clock.now()))
        assertEquals(GmailSyncNotice.QUEUED, settled.notice)

        // A later read where the marker is finally gone must behave identically.
        clock.instant += 61.seconds
        gmail.statusResult = Outcome.Success(connectedStatus().copy(syncRequestedAt = null))
        vm.refreshServerSync()
        runCurrent()
        assertFalse(vm.serverSync.value.connecting(clock.now()))
        assertEquals(GmailSyncState.IDLE, vm.serverSync.value.syncState)
    }

    @Test
    fun `a fresh request hint renders connecting only until the status supersedes it`() = sheetTest { vm ->
        gmail.statusResult = Outcome.Success(connectedStatus())
        vm.onSheetOpened()

        vm.syncServerNow()

        val afterAck = vm.serverSync.value
        // The ack-triggered refresh already answered, so the hint must be gone in the same tick.
        assertFalse(afterAck.connecting(clock.now()))
        assertEquals(GmailSyncState.IDLE, afterAck.syncState)
    }

    @Test
    fun `a failed status read after the ack does not pin the connecting label`() = sheetTest { vm ->
        gmail.statusResult = Outcome.Success(connectedStatus())
        vm.onSheetOpened()

        // The 202 arrives, but the follow-up read fails (flaky network).
        gmail.statusResult = Outcome.Failure(AppError.Network("backend_unreachable"))
        vm.syncServerNow()

        val failed = vm.serverSync.value
        assertFalse(
            "a failed read must drop the local hint instead of pinning Menghubungkan",
            failed.connecting(clock.now()),
        )
    }

    @Test
    fun `a coalesced ack is reported as coalesced, not queued`() = sheetTest { vm ->
        gmail.syncResult = Outcome.Success(
            GmailSyncRequest(queued = true, coalesced = true),
        )
        vm.onSheetOpened()

        vm.syncServerNow()

        assertEquals(GmailSyncNotice.COALESCED, vm.serverSync.value.notice)
    }

    @Test
    fun `cooldown maps retryAfterSeconds and blocks the second tap`() = sheetTest { vm ->
        gmail.syncResult = Outcome.Failure(
            AppError.RateLimited(AppErrorRules.SYNC_COOLDOWN, retryAfterSeconds = 60),
        )
        vm.onSheetOpened()

        vm.syncServerNow()

        val cooling = vm.serverSync.value
        assertEquals(GmailSyncNotice.COOLDOWN, cooling.notice)
        assertEquals(60, cooling.cooldownSeconds(clock.now()))
        assertTrue(cooling.coolingDown(clock.now()))

        vm.syncServerNow()
        assertEquals(1, gmail.requestCalls)

        clock.instant += 61.seconds
        assertFalse(vm.serverSync.value.coolingDown(clock.now()))
    }

    @Test
    fun `needs reauth is surfaced for the reconnect CTA and blocks the tap`() = sheetTest { vm ->
        gmail.statusResult = Outcome.Success(connectedStatus(needsReauth = true))
        vm.onSheetOpened()

        assertTrue(vm.serverSync.value.needsReauth)

        vm.syncServerNow()
        assertEquals(0, gmail.requestCalls)
    }

    @Test
    fun `409 not connected hides the section after the status refresh`() = sheetTest { vm ->
        gmail.syncResult = Outcome.Failure(
            AppError.Validation(AppErrorRules.GMAIL_NOT_CONNECTED),
        )
        vm.onSheetOpened()
        assertTrue(vm.serverSync.value.available)

        gmail.statusResult = Outcome.Success(GmailLinkStatus.NotConnected)
        vm.syncServerNow()

        // The next status read says "not linked": the section hides and the legacy sheet returns.
        assertEquals(1, gmail.requestCalls)
        assertFalse(vm.serverSync.value.available)
    }

    @Test
    fun `503 disabled is a feature-off notice, not the generic failure`() = sheetTest { vm ->
        gmail.syncResult = Outcome.Failure(
            AppError.Validation(AppErrorRules.GMAIL_DISABLED),
        )
        vm.onSheetOpened()

        vm.syncServerNow()

        assertEquals(GmailSyncNotice.DISABLED, vm.serverSync.value.notice)
        assertNull(vm.serverSync.value.error)
    }

    @Test
    fun `polling refreshes while the sheet stays open and stops after close`() = sheetTest { vm ->
        vm.onSheetOpened()
        assertEquals(1, gmail.statusCalls)

        advanceTimeBy(UpdateStatusViewModel.SERVER_POLL_INTERVAL_MS)
        runCurrent()
        assertEquals(2, gmail.statusCalls)

        vm.onSheetClosed()
        advanceTimeBy(UpdateStatusViewModel.SERVER_POLL_INTERVAL_MS * 3)
        runCurrent()
        assertEquals(2, gmail.statusCalls)
        assertEquals(0, gmail.requestCalls)
    }

    @Test
    fun `status read failure keeps the reauth flag off and reports the error`() = sheetTest { vm ->
        gmail.statusResult = Outcome.Failure(AppError.Network("backend_unreachable"))

        vm.onSheetOpened()

        assertFalse(vm.serverSync.value.available)
        assertEquals(AppError.Network("backend_unreachable"), vm.serverSync.value.error)
    }

    @Test
    fun `closing the sheet while the enqueue request is in flight does not cancel the ack`() =
        sheetTest { vm ->
            val gate = CompletableDeferred<Unit>()
            gmail.requestGate = gate
            vm.onSheetOpened()

            vm.syncServerNow()
            vm.onSheetClosed()
            assertEquals(GmailSyncNotice.NONE, vm.serverSync.value.notice)
            assertEquals(1, gmail.requestCalls)

            gate.complete(Unit)
            runCurrent()

            // The request survived the dismiss: the ack is still applied and displayed.
            assertEquals(GmailSyncNotice.QUEUED, vm.serverSync.value.notice)
        }

    @Test
    fun `closing the sheet does not cancel a manual sync that is already running`() = runTest(dispatcher) {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val gated = SyncSessionCoordinator(
            runner = object : SyncSessionRunner {
                override suspend fun run(month: com.pam.recap.core.time.JakartaMonth): Outcome<ImportJob> {
                    started.complete(Unit)
                    gate.await()
                    return Outcome.Failure(AppError.Network("pipeline_stopped"))
                }

                override suspend fun resumeUpload(month: com.pam.recap.core.time.JakartaMonth): Outcome<ImportJob> =
                    Outcome.Failure(AppError.Unknown("not_used"))
            },
            imports = FakeImportRepository(),
            sessions = sessions,
            status = SyncStatusHolder(),
            clock = clock,
            scope = CoroutineScope(dispatcher),
        )
        val vm = newViewModel(gated)

        vm.syncNow()
        started.await()
        vm.onSheetClosed()

        // Dismissing the sheet leaves the application-scoped job running.
        assertTrue(gated.state.value.running)

        gate.complete(Unit)
        runCurrent()

        assertFalse(gated.state.value.running)
    }

    private fun connectedStatus(
        syncState: GmailSyncState = GmailSyncState.IDLE,
        needsReauth: Boolean = false,
        progress: GmailSyncProgress? = null,
    ) = GmailLinkStatus(
        connected = true,
        accountEmailMasked = "p***@gmail.com",
        syncState = syncState,
        connectedAt = Instant.parse("2026-11-01T00:00:00Z"),
        lastAttemptAt = Instant.parse("2026-11-24T04:00:00Z"),
        lastSuccessAt = Instant.parse("2026-11-24T03:00:00Z"),
        nextSyncAt = Instant.parse("2026-11-24T06:00:00Z"),
        needsReauth = needsReauth,
        progress = progress,
    )
}

private class MutableClock(var instant: Instant) : ClockProvider {
    override fun now(): Instant = instant
}

/** Records server calls; every response is swapped per test so no network is involved. */
private class FakeServerGmail : GmailLinkRepository {
    override val isEnabled: Boolean = true
    var statusCalls: Int = 0
    var requestCalls: Int = 0
    var statusResult: Outcome<GmailLinkStatus> =
        Outcome.Success(GmailLinkStatus(connected = true, accountEmailMasked = "p***@gmail.com"))
    var syncResult: Outcome<GmailSyncRequest> =
        Outcome.Success(GmailSyncRequest(queued = true, coalesced = false))
    /** When set, [requestSync] suspends until the test releases it (dismiss mid-flight). */
    var requestGate: CompletableDeferred<Unit>? = null

    override suspend fun status(ownerId: String): Outcome<GmailLinkStatus> {
        statusCalls++
        return statusResult
    }

    override suspend fun startConnect(
        ownerId: String,
        reconnect: Boolean,
    ): Outcome<com.pam.recap.domain.model.GmailAuthorization> =
        Outcome.Failure(AppError.Unknown("not_used"))

    override suspend fun disconnect(ownerId: String): Outcome<Unit> =
        Outcome.Failure(AppError.Unknown("not_used"))

    override suspend fun requestSync(ownerId: String): Outcome<GmailSyncRequest> {
        requestCalls++
        requestGate?.await()
        return syncResult
    }
}
