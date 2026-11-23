package com.pam.recap.ui.feature.transactions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.AppErrorRules
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.Transaction
import com.pam.recap.domain.repository.FinanceRefreshBus
import com.pam.recap.domain.repository.ServerFinanceRepository
import com.pam.recap.domain.repository.SessionRepository
import com.pam.recap.domain.repository.TransactionRepository
import com.pam.recap.domain.usecase.LinkRefundUseCase
import com.pam.recap.domain.usecase.MarkInternalTransferUseCase
import com.pam.recap.domain.usecase.UpdateTransactionCategoryUseCase
import com.pam.recap.domain.usecase.UpdateTransactionNoteUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** FR-24/FR-25 detail state: read-only money facts plus the editable metadata. */
data class TransactionDetailUiState(
    val isLoading: Boolean = true,
    val transaction: Transaction? = null,
    val refundCandidates: List<Transaction> = emptyList(),
    val noteDraft: String = "",
    val isBusy: Boolean = false,
    val error: AppError? = null,
    val savedAt: Long? = null,
    /** Server mode keeps non-category metadata read-only (no endpoint yet). */
    val actionsEnabled: Boolean = true,
    /** Category correction is wired through the API in server mode and locally otherwise. */
    val categoryEditable: Boolean = true,
)

/**
 * Transaction detail actions (FR-17/FR-25): category, transfer flag, refund link and private note.
 * Amount, direction and event time are never written here — the use cases only accept the editable
 * fields, so the UI cannot violate the read-only rule.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TransactionDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sessions: SessionRepository,
    private val transactions: TransactionRepository,
    private val updateCategory: UpdateTransactionCategoryUseCase,
    private val markTransfer: MarkInternalTransferUseCase,
    private val linkRefund: LinkRefundUseCase,
    private val updateNote: UpdateTransactionNoteUseCase,
    private val server: ServerFinanceRepository? = null,
) : ViewModel() {

    private val transactionId: String = savedStateHandle.get<String>("transactionId").orEmpty()
    private val refresh = MutableStateFlow(0L)
    private val action = MutableStateFlow(ActionState())

    private data class ActionState(
        val isBusy: Boolean = false,
        val error: AppError? = null,
        val savedAt: Long? = null,
    )

    val state: StateFlow<TransactionDetailUiState> = combine(
        sessions.observe().filterNotNull(),
        refresh,
        action,
    ) { session, _, currentAction -> session.ownerId to currentAction }
        .flatMapLatest { (ownerId, currentAction) ->
            flow {
                var loadError: AppError? = null
                val transaction = if (server?.isEnabled == true) {
                    when (val result = server.get(ownerId, transactionId)) {
                        is Outcome.Success -> result.value
                        is Outcome.Failure -> {
                            loadError = result.error
                            null
                        }
                    }
                } else {
                    transactions.get(ownerId, transactionId).also {
                        if (it == null) loadError = AppError.NotFound("transaction_$transactionId")
                    }
                }
                val candidates = if (transaction != null && transaction.direction == com.pam.recap.domain.model.Direction.CREDIT) {
                    if (server?.isEnabled == true) {
                        (server.listMonth(ownerId, JakartaMonth.of(transaction.eventAt)) as? Outcome.Success)
                            ?.value?.items.orEmpty()
                            .filter { it.direction == com.pam.recap.domain.model.Direction.DEBIT && !it.isInternalTransfer }
                    } else {
                        transactions.listMonth(ownerId, JakartaMonth.of(transaction.eventAt))
                            .filter { it.direction == com.pam.recap.domain.model.Direction.DEBIT && !it.isInternalTransfer }
                    }
                } else {
                    emptyList()
                }
                emit(
                    TransactionDetailUiState(
                        isLoading = false,
                        transaction = transaction,
                        refundCandidates = candidates,
                        noteDraft = transaction?.note.orEmpty(),
                        isBusy = currentAction.isBusy,
                        error = currentAction.error ?: loadError,
                        savedAt = currentAction.savedAt,
                        actionsEnabled = server?.isEnabled != true,
                        categoryEditable = transaction != null,
                    ),
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransactionDetailUiState())

    fun setCategory(category: Category) {
        val remote = server
        if (remote == null || !remote.isEnabled) {
            // A category change moves money between totals, so every page that reads those totals
            // has to be told. `perform` publishes the owner+month invalidation on success only.
            perform(invalidateFinance = true) { ownerId ->
                updateCategory(ownerId, transactionId, category)
            }
            return
        }
        if (action.value.isBusy) return
        val current = state.value.transaction
        if (current == null) {
            action.value = ActionState(error = AppError.NotFound("transaction_$transactionId"))
            return
        }
        action.value = action.value.copy(isBusy = true, error = null)
        viewModelScope.launch {
            val ownerId = sessions.current()?.ownerId
            if (ownerId == null) {
                action.value = ActionState(error = AppError.Validation(AppErrorRules.NOT_SIGNED_IN))
                return@launch
            }
            val target = when (val categories = remote.categories(ownerId)) {
                is Outcome.Success -> categories.value.firstOrNull { it.code == category.code }
                is Outcome.Failure -> {
                    action.value = ActionState(error = categories.error)
                    return@launch
                }
            }
            if (target == null) {
                action.value = ActionState(error = AppError.Validation("category_not_found"))
                return@launch
            }
            when (val result = remote.updateCategory(ownerId, transactionId, target.id, current.revision)) {
                is Outcome.Failure -> {
                    action.value = ActionState(error = result.error)
                    // 409: somebody else wrote first. Reload so the next tap uses the fresh revision
                    // instead of retrying a revision the server already rejected.
                    if (result.error is AppError.Conflict) refresh.update { it + 1 }
                }
                is Outcome.Success -> {
                    action.value = ActionState(savedAt = System.currentTimeMillis())
                    publishInvalidation(ownerId, current)
                    refresh.update { it + 1 }
                }
            }
        }
    }

    fun setInternalTransfer(isInternalTransfer: Boolean) =
        perform { ownerId -> markTransfer(ownerId, transactionId, isInternalTransfer) }

    fun setRefundLink(expenseId: String?) =
        perform { ownerId -> linkRefund(ownerId, transactionId, expenseId) }

    fun saveNote(note: String) = perform { ownerId -> updateNote(ownerId, transactionId, note) }

    private fun perform(
        invalidateFinance: Boolean = false,
        block: suspend (ownerId: String) -> Outcome<Unit>,
    ) {
        if (action.value.isBusy) return
        if (server?.isEnabled == true) {
            action.value = ActionState(error = AppError.Configuration("server_edit_not_available"))
            return
        }
        action.value = action.value.copy(isBusy = true, error = null)
        viewModelScope.launch {
            val ownerId = sessions.current()?.ownerId
            if (ownerId == null) {
                action.value = ActionState(error = AppError.Validation(com.pam.recap.core.result.AppErrorRules.NOT_SIGNED_IN))
                return@launch
            }
            when (val result = block(ownerId)) {
                is Outcome.Failure -> action.value = ActionState(error = result.error)
                is Outcome.Success -> {
                    action.value = ActionState(savedAt = System.currentTimeMillis())
                    if (invalidateFinance) publishInvalidation(ownerId, state.value.transaction)
                    refresh.update { it + 1 }
                }
            }
        }
    }

    /**
     * One owner+month invalidation for every screen that reads the month (list, overview,
     * analysis). It is emitted only after the server/local write succeeded: a failed or conflicting
     * write must not look like a successful one anywhere else in the app.
     */
    private fun publishInvalidation(ownerId: String, transaction: Transaction?) {
        val month = transaction?.eventAt?.let(JakartaMonth::of) ?: return
        FinanceRefreshBus.notify(ownerId, month.id())
    }
}
