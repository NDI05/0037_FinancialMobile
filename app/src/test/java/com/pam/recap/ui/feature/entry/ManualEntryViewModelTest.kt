package com.pam.recap.ui.feature.entry

import androidx.lifecycle.SavedStateHandle
import com.pam.recap.core.security.FingerprintFactory
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.Direction
import com.pam.recap.domain.model.Session
import com.pam.recap.domain.model.TransactionSource
import com.pam.recap.domain.usecase.AddManualEntryUseCase
import com.pam.recap.testing.FakeAnalysisRepository
import com.pam.recap.testing.FakeSessionRepository
import com.pam.recap.testing.FakeSummaryRepository
import com.pam.recap.testing.FakeTransactionRepository
import com.pam.recap.testing.SequentialIdGenerator
import com.pam.recap.testing.fixedClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import kotlin.time.Instant

/**
 * FR-26 manual entry: an optional note rides along with the draft and survives the save, while an
 * empty note stays absent. NFR-15 dates: the stepper stops at today and at the rolling-window floor
 * and an out-of-range date blocks the save.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ManualEntryViewModelTest {

    // 24 November 2026, 22:00 Asia/Jakarta.
    private val now: Instant = Instant.parse("2026-11-24T15:00:00Z")
    private val today: LocalDate = LocalDate.of(2026, 11, 24)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(
        monthId: String? = null,
        currentInstant: Instant = now,
    ): Pair<ManualEntryViewModel, FakeTransactionRepository> {
        val transactions = FakeTransactionRepository()
        val clock = fixedClock(currentInstant)
        val viewModel = ManualEntryViewModel(
            savedStateHandle = SavedStateHandle(buildMap { if (monthId != null) put("monthId", monthId) }),
            sessions = FakeSessionRepository(
                Session(
                    ownerId = "u_owner",
                    displayName = "Pemilik",
                    mailboxLabel = "owner@example.test",
                    signedInAt = now,
                    isDemo = false,
                ),
            ),
            addManualEntry = AddManualEntryUseCase(
                transactions = transactions,
                summaries = FakeSummaryRepository(),
                analyses = FakeAnalysisRepository(),
                fingerprints = FingerprintFactory { ByteArray(32) { 1 } },
                clock = clock,
                idGenerator = SequentialIdGenerator("manual"),
            ),
            clock = clock,
        )
        return viewModel to transactions
    }

    private fun ManualEntryViewModel.fillDraft() {
        setAmount("16000")
        setDescription("Makan siang")
        setCategory(Category.FOOD)
    }

    @Test
    fun `manual entry today before noon represents the start of the WIB day`() {
        val (viewModel, transactions) = viewModel(currentInstant = Instant.parse("2026-11-24T01:30:00Z"))
        viewModel.fillDraft()

        viewModel.save()

        assertEquals(Instant.parse("2026-11-23T17:00:00Z"), transactions.all().single().eventAt)
    }

    @Test
    fun `optional note is saved on the manual row`() {
        val (viewModel, transactions) = viewModel()
        viewModel.fillDraft()
        viewModel.setNote("  Bayar pakai e-wallet  ")

        viewModel.save()

        val row = transactions.all().single()
        assertEquals(TransactionSource.MANUAL, row.source)
        assertEquals(Direction.DEBIT, row.direction)
        assertEquals(Category.FOOD, row.category)
        assertEquals("Bayar pakai e-wallet", row.note)
        assertTrue(viewModel.state.value.saved)
    }

    @Test
    fun `blank note remains optional and saves as absent`() {
        val (viewModel, transactions) = viewModel()
        viewModel.fillDraft()

        viewModel.save()

        assertNull(transactions.all().single().note)
        assertTrue(viewModel.state.value.saved)
    }

    @Test
    fun `note is capped at the shared edit limit`() {
        val (viewModel, _) = viewModel()
        viewModel.setNote("x".repeat(600))

        assertEquals(500, viewModel.state.value.note.length)
    }

    @Test
    fun `date stepper stops at today and cannot reach the future`() {
        val (viewModel, _) = viewModel()
        assertEquals(today, viewModel.state.value.date)

        viewModel.shiftDate(1)

        assertEquals(today, viewModel.state.value.date)
        assertFalse(viewModel.state.value.dateRange.canGoLater(viewModel.state.value.date))
    }

    @Test
    fun `date stepper stops at the one-year window floor`() {
        val (viewModel, _) = viewModel()

        viewModel.shiftDate(-400)
        val floor = LocalDate.of(2025, 11, 24)
        assertEquals(floor, viewModel.state.value.date)
        assertFalse(viewModel.state.value.dateRange.canGoEarlier(viewModel.state.value.date))

        viewModel.shiftDate(-1)
        assertEquals(floor, viewModel.state.value.date)
    }

    @Test
    fun `a requested month before the floor clamps to the first allowed day`() {
        val (viewModel, _) = viewModel(monthId = "2025-11")

        assertEquals(LocalDate.of(2025, 11, 24), viewModel.state.value.date)
    }

    @Test
    fun `a requested month inside the window keeps its first day`() {
        val (viewModel, _) = viewModel(monthId = "2026-11")

        assertEquals(LocalDate.of(2026, 11, 1), viewModel.state.value.date)
    }

    @Test
    fun `out-of-window date disables saving`() {
        val range = com.pam.recap.core.time.ManualDateRange.of(today)
        val state = ManualEntryUiState(
            amountText = "16000",
            description = "Makan siang",
            date = today.plusDays(1),
            today = today,
            dateRange = range,
        )

        assertTrue(state.dateOutOfWindow)
        assertFalse(state.canSave)
    }

}
