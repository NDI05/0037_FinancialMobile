package com.pam.recap.ui.feature.transactions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.Transaction
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
    val actionsEnabled: Boolean = true,
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
                    ),
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransactionDetailUiState())

    fun setCategory(category: Category) = run { perform { ownerId -> updateCategory(ownerId, transactionId, category) } }

    fun setInternalTransfer(isInternalTransfer: Boolean) =
        perform { ownerId -> markTransfer(ownerId, transactionId, isInternalTransfer) }

    fun setRefundLink(expenseId: String?) =
        perform { ownerId -> linkRefund(ownerId, transactionId, expenseId) }

    fun saveNote(note: String) = perform { ownerId -> updateNote(ownerId, transactionId, note) }

    private fun perform(block: suspend (ownerId: String) -> Outcome<Unit>) {
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
                    refresh.update { it + 1 }
                }
            }
        }
    }
}
