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
import com.pam.recap.domain.repository.TransactionPatch
import com.pam.recap.domain.repository.TransactionRepository
import com.pam.recap.domain.usecase.LinkRefundUseCase
import com.pam.recap.domain.usecase.MarkInternalTransferUseCase
import com.pam.recap.domain.usecase.UpdateTransactionCategoryUseCase
import com.pam.recap.domain.usecase.UpdateTransactionNoteUseCase
import com.pam.recap.ui.components.DynamicField
import com.pam.recap.ui.components.DynamicFieldRules
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Client-side bounds for the editable metadata. The Go handler does not publish limits yet (see
 * the delivery report): these are the UI guard, not a substitute for server validation.
 */
object TransactionEditLimits {
    const val DESCRIPTION = 200
    const val MERCHANT_LABEL = 120
    const val NOTE = 500
}

/** Field-level validation result, mapped to copy by the screen (no technical rule strings shown). */
enum class TransactionFieldError {
    DESCRIPTION_REQUIRED,
    DESCRIPTION_TOO_LONG,
    MERCHANT_TOO_LONG,
    NOTE_TOO_LONG,
}

/** FR-24/FR-25 detail state: read-only money facts plus the editable metadata. */
data class TransactionDetailUiState(
    val isLoading: Boolean = true,
    val transaction: Transaction? = null,
    val refundCandidates: List<Transaction> = emptyList(),
    val noteDraft: String = "",
    val descriptionDraft: String = "",
    val merchantDraft: String = "",
    /**
     * True when the stored merchant label is only a reference number (Livin bill-payment emails),
     * not a merchant name. The screen then explains the value instead of presenting it as a name.
     */
    val merchantIsReference: Boolean = false,
    val isBusy: Boolean = false,
    val error: AppError? = null,
    val savedAt: Long? = null,
    /** Transfer/refund metadata is still local-only; category, note and the edit form are not. */
    val actionsEnabled: Boolean = true,
    /** Category correction is wired through the API in server mode and locally otherwise. */
    val categoryEditable: Boolean = true,
    /** S2.4: description/merchant/note edit + delete are offered only against the server. */
    val metadataEditable: Boolean = false,
    /** 409: somebody else wrote first. The draft is kept and the row was reloaded. */
    val conflict: Boolean = false,
    /** Network failure: the draft is kept and the same save can be retried. */
    val canRetry: Boolean = false,
    val fieldError: TransactionFieldError? = null,
    /** S3 dynamic fields draft, prefilled from the row's stored attributes when the sheet opens. */
    val attributeFields: List<DynamicField> = listOf(DynamicField()),
    /** Rule id from [DynamicFieldRules.validate]; the screen maps it to localized copy. */
    val attributeRule: String? = null,
    val hasUnsavedChanges: Boolean = false,
    val deleteConfirmation: Boolean = false,
    val isDeleted: Boolean = false,
)

/**
 * Transaction detail actions (FR-17/FR-25): category, transfer flag, refund link, private note and
 * — in server mode — the validated description/merchant/note edit plus delete (S2.4).
 *
 * Amount, direction and event time are never written here — the use cases and the server patch only
 * accept the editable fields, so the UI cannot violate the read-only rule. No action reports
 * success before [Outcome.Success]; a 409 reloads the row and keeps the user's draft.
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

    private val _state = MutableStateFlow(TransactionDetailUiState())
    val state: StateFlow<TransactionDetailUiState> = _state.asStateFlow()

    /** Unsaved edit; survives a reload after a conflict or a network failure. */
    private data class Draft(
        val transactionId: String? = null,
        val description: String = "",
        val merchant: String = "",
        val note: String = "",
        val attributes: List<DynamicField> = listOf(DynamicField()),
        val dirty: Boolean = false,
    )

    private var draft = Draft()

    init {
        viewModelScope.launch {
            combine(sessions.observe().filterNotNull(), refresh) { session, _ -> session.ownerId }
                .collectLatest { ownerId -> load(ownerId) }
        }
    }

    private suspend fun load(ownerId: String) {
        val hadRow = _state.value.transaction != null
        // Keep an unresolved conflict visible while the newest row is fetched.
        _state.update { it.copy(isLoading = !hadRow, error = if (it.conflict) it.error else null) }
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
        val candidates = if (transaction != null &&
            transaction.direction == com.pam.recap.domain.model.Direction.CREDIT
        ) {
            if (server?.isEnabled == true) {
                (server.listMonth(ownerId, JakartaMonth.of(transaction.eventAt)) as? Outcome.Success)
                    ?.value?.items.orEmpty()
                    .filter {
                        it.direction == com.pam.recap.domain.model.Direction.DEBIT &&
                            !it.isInternalTransfer
                    }
            } else {
                transactions.listMonth(ownerId, JakartaMonth.of(transaction.eventAt))
                    .filter {
                        it.direction == com.pam.recap.domain.model.Direction.DEBIT &&
                            !it.isInternalTransfer
                    }
            }
        } else {
            emptyList()
        }

        if (transaction != null && (!draft.dirty || draft.transactionId != transaction.id)) {
            draft = Draft(
                transactionId = transaction.id,
                description = transaction.description,
                merchant = transaction.merchantLabel.orEmpty(),
                note = transaction.note.orEmpty(),
                attributes = DynamicFieldRules.fromMap(transaction.attributes),
                dirty = false,
            )
        }

        _state.update {
            it.copy(
                isLoading = false,
                transaction = transaction,
                refundCandidates = candidates,
                descriptionDraft = draft.description,
                merchantDraft = draft.merchant,
                merchantIsReference = transaction != null && isReferenceMerchantLabel(transaction.merchantLabel),
                noteDraft = draft.note,
                attributeFields = draft.attributes,
                // An unresolved conflict is still shown as the typed error until the user resolves it,
                // so reloading the newest row does not hide what happened.
                error = if (it.conflict) it.error ?: loadError else loadError,
                actionsEnabled = server?.isEnabled != true,
                categoryEditable = transaction != null,
                metadataEditable = server?.isEnabled == true && transaction != null,
                hasUnsavedChanges = draft.dirty,
            )
        }
    }

    fun setDescription(value: String) = editDraft { it.copy(description = value) }

    fun setMerchantLabel(value: String) = editDraft { it.copy(merchant = value) }

    fun setNote(value: String) = editDraft { it.copy(note = value) }

    /** S3: the dynamic-field rows are part of the same metadata draft as the text fields. */
    fun setAttributeFields(fields: List<DynamicField>) = editDraft { it.copy(attributes = fields) }

    private fun editDraft(transform: (Draft) -> Draft) {
        if (!_state.value.metadataEditable) return
        draft = transform(draft).copy(dirty = true)
        _state.update {
            it.copy(
                descriptionDraft = draft.description,
                merchantDraft = draft.merchant,
                noteDraft = draft.note,
                attributeFields = draft.attributes,
                hasUnsavedChanges = true,
                fieldError = null,
                attributeRule = null,
                conflict = false,
                canRetry = false,
            )
        }
    }

    /**
     * Saves the validated metadata. Server mode PATCHes description/merchant/note with the revision
     * the row was loaded with; local mode keeps the note-only use case.
     */
    fun saveMetadata() {
        val current = _state.value
        val transaction = current.transaction ?: return
        if (current.isBusy) return
        val description = current.descriptionDraft.trim()
        val merchant = current.merchantDraft.trim()
        val note = current.noteDraft.trim()

        val fieldError = when {
            description.isEmpty() -> TransactionFieldError.DESCRIPTION_REQUIRED
            description.length > TransactionEditLimits.DESCRIPTION ->
                TransactionFieldError.DESCRIPTION_TOO_LONG
            merchant.length > TransactionEditLimits.MERCHANT_LABEL ->
                TransactionFieldError.MERCHANT_TOO_LONG
            note.length > TransactionEditLimits.NOTE -> TransactionFieldError.NOTE_TOO_LONG
            else -> null
        }
        if (fieldError != null) {
            _state.update { it.copy(fieldError = fieldError, error = null) }
            return
        }
        // S3 dynamic fields share the S3 limits with categories; the rule id is surfaced so the
        // screen can show the matching localized copy and no PATCH leaves the device.
        val attributeRule = DynamicFieldRules.validate(current.attributeFields)
        if (attributeRule != null) {
            _state.update { it.copy(fieldError = null, attributeRule = attributeRule, error = null) }
            return
        }
        val attributes = DynamicFieldRules.toMap(current.attributeFields)

        val remote = server
        if (remote == null || !remote.isEnabled) {
            // Legacy path: only the private note is writable locally.
            saveNote(note)
            return
        }

        _state.update {
            it.copy(
                isBusy = true,
                error = null,
                conflict = false,
                canRetry = false,
                fieldError = null,
                attributeRule = null,
            )
        }
        viewModelScope.launch {
            val ownerId = sessions.current()?.ownerId
            if (ownerId == null) {
                _state.update {
                    it.copy(
                        isBusy = false,
                        error = AppError.Validation(AppErrorRules.NOT_SIGNED_IN),
                    )
                }
                return@launch
            }
            val patch = TransactionPatch(
                revision = transaction.revision,
                description = description,
                // An empty string clears the field; `null` would mean "keep the current value".
                merchantLabel = merchant,
                note = note,
                attributes = attributes,
            )
            when (val result = remote.updateTransaction(ownerId, transactionId, patch)) {
                is Outcome.Failure -> {
                    val conflict = result.error is AppError.Conflict
                    _state.update {
                        it.copy(
                            isBusy = false,
                            // The typed error stays observable (callers/tests key off it); the screen
                            // hides the generic banner when the dedicated conflict copy is shown.
                            error = result.error,
                            conflict = conflict,
                            canRetry = result.error is AppError.Network,
                        )
                    }
                    if (conflict) refresh.update { it + 1 }
                }

                is Outcome.Success -> {
                    draft = draft.copy(dirty = false)
                    _state.update {
                        it.copy(
                            isBusy = false,
                            savedAt = System.currentTimeMillis(),
                            conflict = false,
                            canRetry = false,
                            hasUnsavedChanges = false,
                        )
                    }
                    publishInvalidation(ownerId, transaction)
                    refresh.update { it + 1 }
                }
            }
        }
    }

    /** Re-runs the failed save with the draft still in the fields. */
    fun retrySave() = saveMetadata()

    fun requestDelete() {
        if (!_state.value.metadataEditable || _state.value.isDeleted) return
        _state.update { it.copy(deleteConfirmation = true) }
    }

    fun cancelDelete() {
        _state.update { it.copy(deleteConfirmation = false) }
    }

    /** Deletes only after the confirmation sheet was accepted; the row stays on failure. */
    fun confirmDelete() {
        val current = _state.value
        if (!current.deleteConfirmation || current.isBusy) return
        val transaction = current.transaction ?: return
        val remote = server
        if (remote == null || !remote.isEnabled) {
            _state.update {
                it.copy(
                    deleteConfirmation = false,
                    error = AppError.Configuration("server_delete_not_available"),
                )
            }
            return
        }
        _state.update {
            it.copy(isBusy = true, deleteConfirmation = false, error = null, conflict = false)
        }
        viewModelScope.launch {
            val ownerId = sessions.current()?.ownerId
            if (ownerId == null) {
                _state.update {
                    it.copy(isBusy = false, error = AppError.Validation(AppErrorRules.NOT_SIGNED_IN))
                }
                return@launch
            }
            when (val result = remote.deleteTransaction(ownerId, transactionId, transaction.revision)) {
                is Outcome.Failure -> {
                    val conflict = result.error is AppError.Conflict
                    _state.update {
                        it.copy(
                            isBusy = false,
                            error = result.error,
                            conflict = conflict,
                            canRetry = result.error is AppError.Network,
                            // A network failure leaves the row untouched: keep the confirmation sheet
                            // so the user can retry or cancel deliberately. A conflict reloads the
                            // row, so the sheet closes and the new state is shown first.
                            deleteConfirmation = !conflict,
                        )
                    }
                    if (conflict) refresh.update { it + 1 }
                }

                is Outcome.Success -> {
                    _state.update {
                        it.copy(
                            isBusy = false,
                            isDeleted = true,
                            deleteConfirmation = false,
                            savedAt = System.currentTimeMillis(),
                        )
                    }
                    publishInvalidation(ownerId, transaction)
                }
            }
        }
    }

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
        if (_state.value.isBusy) return
        val current = _state.value.transaction
        if (current == null) {
            _state.update { it.copy(error = AppError.NotFound("transaction_$transactionId")) }
            return
        }
        _state.update { it.copy(isBusy = true, error = null, conflict = false) }
        viewModelScope.launch {
            val ownerId = sessions.current()?.ownerId
            if (ownerId == null) {
                _state.update {
                    it.copy(
                        isBusy = false,
                        error = AppError.Validation(AppErrorRules.NOT_SIGNED_IN),
                    )
                }
                return@launch
            }
            val target = when (val categories = remote.categories(ownerId)) {
                is Outcome.Success -> categories.value.firstOrNull { it.code == category.code }
                is Outcome.Failure -> {
                    _state.update { it.copy(isBusy = false, error = categories.error) }
                    return@launch
                }
            }
            if (target == null) {
                _state.update {
                    it.copy(isBusy = false, error = AppError.Validation("category_not_found"))
                }
                return@launch
            }
            when (val result = remote.updateCategory(ownerId, transactionId, target.id, current.revision)) {
                is Outcome.Failure -> {
                    val conflict = result.error is AppError.Conflict
                    _state.update {
                        it.copy(
                            isBusy = false,
                            error = result.error,
                            conflict = conflict,
                        )
                    }
                    // 409: somebody else wrote first. Reload so the next tap uses the fresh revision
                    // instead of retrying a revision the server already rejected.
                    if (conflict) refresh.update { it + 1 }
                }

                is Outcome.Success -> {
                    _state.update {
                        it.copy(isBusy = false, savedAt = System.currentTimeMillis(), conflict = false)
                    }
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
        if (_state.value.isBusy) return
        if (server?.isEnabled == true) {
            _state.update {
                it.copy(error = AppError.Configuration("server_edit_not_available"))
            }
            return
        }
        _state.update { it.copy(isBusy = true, error = null) }
        viewModelScope.launch {
            val ownerId = sessions.current()?.ownerId
            if (ownerId == null) {
                _state.update {
                    it.copy(
                        isBusy = false,
                        error = AppError.Validation(AppErrorRules.NOT_SIGNED_IN),
                    )
                }
                return@launch
            }
            when (val result = block(ownerId)) {
                is Outcome.Failure -> _state.update { it.copy(isBusy = false, error = result.error) }
                is Outcome.Success -> {
                    _state.update {
                        it.copy(isBusy = false, savedAt = System.currentTimeMillis())
                    }
                    if (invalidateFinance) publishInvalidation(ownerId, _state.value.transaction)
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
