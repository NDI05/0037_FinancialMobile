package com.pam.recap.ui.feature.transactions

import androidx.lifecycle.SavedStateHandle
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.domain.repository.FinanceRefreshBus
import com.pam.recap.domain.repository.FinanceRefreshEvent
import com.pam.recap.domain.usecase.LinkRefundUseCase
import com.pam.recap.domain.usecase.MarkInternalTransferUseCase
import com.pam.recap.domain.usecase.ResolveInitialMonthUseCase
import com.pam.recap.domain.usecase.UpdateTransactionCategoryUseCase
import com.pam.recap.domain.usecase.UpdateTransactionNoteUseCase
import com.pam.recap.testing.FakeAnalysisRepository
import com.pam.recap.testing.FakeSessionRepository
import com.pam.recap.testing.FakeSummaryRepository
import com.pam.recap.testing.FakeTransactionRepository
import com.pam.recap.testing.fixedClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * S2.4 mutation UI: metadata edits are revision-checked, a 409 never overwrites or retries blindly,
 * and delete only happens after an explicit confirmation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TransactionMutationTest {

    private val row = testTransaction("tx_1", description = "QRIS", merchantLabel = "BAKSO UJI")

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(server: RecordingServerFinanceRepository?): TransactionDetailViewModel {
        val transactions = FakeTransactionRepository(listOf(row))
        return TransactionDetailViewModel(
            savedStateHandle = SavedStateHandle(mapOf("transactionId" to "tx_1")),
            sessions = FakeSessionRepository(testSession()),
            transactions = transactions,
            updateCategory = UpdateTransactionCategoryUseCase(
                transactions = transactions,
                summaries = FakeSummaryRepository(),
                analyses = FakeAnalysisRepository(),
                clock = fixedClock(testNow),
            ),
            markTransfer = MarkInternalTransferUseCase(
                transactions = transactions,
                summaries = FakeSummaryRepository(),
                analyses = FakeAnalysisRepository(),
                clock = fixedClock(testNow),
            ),
            linkRefund = LinkRefundUseCase(
                transactions = transactions,
                summaries = FakeSummaryRepository(),
                analyses = FakeAnalysisRepository(),
                clock = fixedClock(testNow),
            ),
            updateNote = UpdateTransactionNoteUseCase(transactions, fixedClock(testNow)),
            server = server,
        )
    }

    private fun server(): RecordingServerFinanceRepository = RecordingServerFinanceRepository().apply {
        transactionRow = row
    }

    private fun TestScope.recordEvents() = mutableListOf<FinanceRefreshEvent>().also { sink ->
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            FinanceRefreshBus.events.collect { sink += it }
        }
    }

    @Test
    fun `editing description, merchant and note sends one patch with the loaded revision`() = runTest {
        val remote = server()
        val vm = viewModel(remote)
        val events = recordEvents()
        vm.state.first { it.transaction != null }

        vm.setDescription("Kopi pagi")
        vm.setMerchantLabel("Kopi Kita")
        vm.setNote("dengan teman")
        assertTrue(vm.state.value.hasUnsavedChanges)

        vm.saveMetadata()
        remote.awaitWrites()
        vm.state.first { it.savedAt != null }

        val patch = remote.updateCalls.single()
        assertEquals(3, patch.revision)
        assertEquals("Kopi pagi", patch.description)
        assertEquals("Kopi Kita", patch.merchantLabel)
        assertEquals("dengan teman", patch.note)
        assertEquals("Kopi pagi", vm.state.value.transaction?.description)
        assertFalse(vm.state.value.hasUnsavedChanges)
        assertEquals(1, events.size)
        assertEquals(testMonth.id(), events.single().monthId)
    }

    @Test
    fun `a 409 keeps the draft, reloads the row and publishes nothing`() = runTest {
        val remote = server()
        remote.updateOutcome = Outcome.Failure(AppError.Conflict("revision_conflict"))
        val vm = viewModel(remote)
        val events = recordEvents()
        vm.state.first { it.transaction != null }
        val getsBefore = remote.getCalls

        vm.setDescription("Draft saya")
        vm.saveMetadata()
        val conflicted = vm.state.first { it.conflict }

        assertTrue(conflicted.conflict)
        // The typed value survives, so the user can copy it after seeing the new server copy.
        assertEquals("Draft saya", conflicted.descriptionDraft)
        assertTrue(conflicted.hasUnsavedChanges)
        // The conflict gets its own copy on screen; the reload that follows may clear the generic error.
        assertTrue(conflicted.conflict)
        // The row was reloaded from the server instead of retried with the stale revision.
        assertTrue(remote.getCalls > getsBefore)
        assertEquals("QRIS", conflicted.transaction?.description)
        assertEquals(1, remote.updateCalls.size)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a network failure keeps the draft and offers a retry`() = runTest {
        val remote = server()
        remote.updateOutcome = Outcome.Failure(AppError.Network("offline"))
        val vm = viewModel(remote)
        val events = recordEvents()
        vm.state.first { it.transaction != null }

        vm.setNote("catatan penting")
        vm.saveMetadata()
        val failed = vm.state.first { it.error != null }

        assertTrue(failed.error is AppError.Network)
        assertTrue(failed.canRetry)
        assertEquals("catatan penting", failed.noteDraft)
        assertTrue(failed.hasUnsavedChanges)
        assertTrue(events.isEmpty())

        // Retrying reuses the preserved draft once the connection is back.
        remote.updateOutcome = null
        vm.retrySave()
        val retried = vm.state.first { it.savedAt != null }
        assertEquals("catatan penting", retried.transaction?.note)
        assertEquals(2, remote.updateCalls.size)
        assertEquals(1, events.size)
    }

    @Test
    fun `an empty description is rejected before any request is sent`() = runTest {
        val remote = server()
        val vm = viewModel(remote)
        vm.state.first { it.transaction != null }

        vm.setDescription("   ")
        vm.saveMetadata()
        val invalid = vm.state.first { it.fieldError != null }

        assertEquals(TransactionFieldError.DESCRIPTION_REQUIRED, invalid.fieldError)
        assertTrue(remote.updateCalls.isEmpty())
        assertNull(invalid.savedAt)
    }

    @Test
    fun `over-long metadata is rejected with the limit that was broken`() = runTest {
        val remote = server()
        val vm = viewModel(remote)
        vm.state.first { it.transaction != null }

        vm.setMerchantLabel("M".repeat(TransactionEditLimits.MERCHANT_LABEL + 1))
        vm.saveMetadata()

        assertEquals(
            TransactionFieldError.MERCHANT_TOO_LONG,
            vm.state.first { it.fieldError != null }.fieldError,
        )
        assertTrue(remote.updateCalls.isEmpty())
    }

    @Test
    fun `delete does nothing until the confirmation is open`() = runTest {
        val remote = server()
        val vm = viewModel(remote)
        vm.state.first { it.transaction != null }

        vm.confirmDelete()

        assertTrue(remote.deleteCalls.isEmpty())
        assertFalse(vm.state.value.isDeleted)
        assertFalse(vm.state.value.deleteConfirmation)
    }

    @Test
    fun `confirmed delete removes the row and notifies the list`() = runTest {
        val remote = server()
        val vm = viewModel(remote)
        val events = recordEvents()
        vm.state.first { it.transaction != null }

        vm.requestDelete()
        assertTrue(vm.state.value.deleteConfirmation)
        vm.confirmDelete()
        val deleted = vm.state.first { it.isDeleted }

        assertTrue(deleted.isDeleted)
        assertEquals(listOf("tx_1"), remote.deleteCalls)
        // DELETE is revision-guarded: the loaded revision is the compare-and-swap precondition.
        assertEquals(listOf(3), remote.deleteRevisions)
        assertEquals(1, events.size)
        assertEquals(testMonth.id(), events.single().monthId)
    }

    @Test
    fun `a delete 409 keeps the row, reloads it and publishes nothing`() = runTest {
        val remote = server()
        remote.deleteOutcome = Outcome.Failure(AppError.Conflict("revision_conflict"))
        val vm = viewModel(remote)
        val events = recordEvents()
        vm.state.first { it.transaction != null }
        val getsBefore = remote.getCalls

        vm.requestDelete()
        vm.confirmDelete()
        val conflicted = vm.state.first { it.conflict }

        assertFalse(conflicted.isDeleted)
        assertNotNull(conflicted.transaction)
        // A conflict stays observable as a typed error while its dedicated copy is shown.
        assertTrue(conflicted.error is AppError.Conflict)
        // The server copy was reloaded so the user sees the newer revision before trying again.
        assertTrue(remote.getCalls > getsBefore)
        // No silent overwrite and no success signal: the list/summary are not invalidated.
        assertEquals(1, remote.deleteCalls.size)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `a failed delete keeps the row and reports the error`() = runTest {
        val remote = server()
        remote.deleteOutcome = Outcome.Failure(AppError.Network("offline"))
        val vm = viewModel(remote)
        val events = recordEvents()
        vm.state.first { it.transaction != null }

        vm.requestDelete()
        vm.confirmDelete()
        val failed = vm.state.first { it.error != null }

        assertTrue(failed.error is AppError.Network)
        assertFalse(failed.isDeleted)
        assertNotNull(failed.transaction)
        // The sheet stays open so the user can retry or cancel deliberately.
        assertTrue(failed.deleteConfirmation)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `cancelling the confirmation keeps the row`() = runTest {
        val remote = server()
        val vm = viewModel(remote)
        vm.state.first { it.transaction != null }

        vm.requestDelete()
        vm.cancelDelete()

        assertFalse(vm.state.value.deleteConfirmation)
        assertFalse(vm.state.value.isDeleted)
        assertTrue(remote.deleteCalls.isEmpty())
    }
}
