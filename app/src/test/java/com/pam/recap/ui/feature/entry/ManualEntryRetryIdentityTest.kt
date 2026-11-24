package com.pam.recap.ui.feature.entry

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.security.FingerprintFactory
import com.pam.recap.core.security.WrappedSecretStore
import com.pam.recap.data.security.ManualPendingActionStore
import com.pam.recap.data.security.ManualPendingActionStore.Pending
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.Session
import com.pam.recap.domain.model.Transaction
import com.pam.recap.domain.repository.ManualTransactionDraft
import com.pam.recap.domain.usecase.AddManualEntryUseCase
import com.pam.recap.testing.FakeAnalysisRepository
import com.pam.recap.testing.FakeSessionRepository
import com.pam.recap.testing.FakeSummaryRepository
import com.pam.recap.testing.FakeTransactionRepository
import com.pam.recap.testing.SequentialIdGenerator
import com.pam.recap.testing.fixedClock
import com.pam.recap.ui.feature.transactions.RecordingServerFinanceRepository
import com.pam.recap.ui.feature.transactions.testTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Instant

/**
 * SRS v0.7 P2-2: the retry key for an in-flight manual save must survive the note upgrade. A
 * payload written before notes existed has a five-field identity and no note; retrying the same
 * draft has to reuse its UUID instead of minting a new one and risking a duplicate server row.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManualEntryRetryIdentityTest {

    // 24 November 2026, 22:00 Asia/Jakarta.
    private val now: Instant = Instant.parse("2026-11-24T15:00:00Z")
    private val ownerId = "u_owner"

    private lateinit var context: Context

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("pam_backend_session", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun store() = ManualPendingActionStore(context, PassthroughStore())

    private fun viewModel(pending: ManualPendingActionStore?, server: RecordingCreateServer): ManualEntryViewModel {
        val clock = fixedClock(now)
        return ManualEntryViewModel(
            savedStateHandle = SavedStateHandle(),
            sessions = FakeSessionRepository(
                Session(
                    ownerId = ownerId,
                    displayName = "Pemilik",
                    mailboxLabel = "owner@example.test",
                    signedInAt = now,
                    isDemo = false,
                ),
            ),
            addManualEntry = AddManualEntryUseCase(
                transactions = FakeTransactionRepository(),
                summaries = FakeSummaryRepository(),
                analyses = FakeAnalysisRepository(),
                fingerprints = FingerprintFactory { ByteArray(32) { 1 } },
                clock = clock,
                idGenerator = SequentialIdGenerator("manual"),
                server = server,
            ),
            clock = clock,
            pendingActions = pending,
        )
    }

    /** Writes the pre-note payload exactly as the old build encoded it: seven fields, no note. */
    private fun seedLegacyPayload(key: String = "legacy-key") {
        val encoded = listOf(
            key,
            "DEBIT|16000|Makan siang|2026-11-24|food",
            "16000",
            "Makan siang",
            "2026-11-24",
            "DEBIT",
            "food",
        ).joinToString("\u001f") {
            java.util.Base64.getUrlEncoder().encodeToString(it.encodeToByteArray())
        }
        context.getSharedPreferences("pam_backend_session", Context.MODE_PRIVATE)
            .edit()
            .putString("manual_pending_$ownerId", PassthroughStore().wrap(encoded.encodeToByteArray()))
            .commit()
    }

    @Test
    fun `legacy five field payload is restored and retried with its original key`() {
        seedLegacyPayload()
        val server = RecordingCreateServer()

        val viewModel = viewModel(store(), server)
        // The init block restored the legacy draft; saving it must reuse the stored key.
        viewModel.save()

        assertEquals(listOf("legacy-key"), server.manualKeys)
        assertNull(store().read(ownerId))
    }

    @Test
    fun `a new action with a different note keeps a unique key per content`() {
        val pending = store()
        check(pending.save(ownerId, Pending(
            key = "note-a", identity = "DEBIT|16000|Makan siang|catatan A|2026-11-24|food",
            amountText = "16000", description = "Makan siang", note = "catatan A",
            dateIso = "2026-11-24", direction = "DEBIT", categoryCode = "food",
        ))) { "pending action was not persisted" }
        val server = RecordingCreateServer()

        val viewModel = viewModel(pending, server)
        // The restored draft carries "catatan A"; changing the note changes the action content.
        viewModel.setNote("catatan B")
        viewModel.save()

        assertEquals(1, server.manualKeys.size)
        assertNotEquals("note-a", server.manualKeys.single())
    }

    @Test
    fun `retrying the same failed action reuses its key instead of creating a second row`() {
        val pending = store()
        val server = RecordingCreateServer()
        server.createOutcome = Outcome.Failure(AppError.NotFound("lost response"))

        val viewModel = viewModel(pending, server)
        viewModel.setAmount("16000")
        viewModel.setDescription("Makan siang")
        viewModel.setCategory(Category.FOOD)
        viewModel.save()
        // The POST failed but may have reached the server: the recovery row must keep its UUID.
        assertNotNull(pending.read(ownerId))

        server.createOutcome = null
        viewModel.save()

        assertEquals(2, server.manualKeys.size)
        assertEquals(server.manualKeys[0], server.manualKeys[1])
        assertNull(pending.read(ownerId))
    }

    private class RecordingCreateServer : RecordingServerFinanceRepository() {
        val manualKeys = mutableListOf<String>()
        var createOutcome: Outcome<Transaction>? = null

        override suspend fun createManual(
            ownerId: String,
            draft: ManualTransactionDraft,
            idempotencyKey: String,
        ): Outcome<Transaction> {
            manualKeys += idempotencyKey
            return createOutcome ?: Outcome.Success(testTransaction("tx_${manualKeys.size}"))
        }
    }

    private class PassthroughStore : WrappedSecretStore {
        override fun wrap(plainSecret: ByteArray): String =
            java.util.Base64.getUrlEncoder().encodeToString(plainSecret)

        override fun unwrap(wrapped: String): ByteArray =
            java.util.Base64.getUrlDecoder().decode(wrapped)
    }
}
