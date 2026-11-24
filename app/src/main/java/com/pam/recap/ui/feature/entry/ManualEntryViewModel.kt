package com.pam.recap.ui.feature.entry

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pam.recap.core.money.Money
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.AppErrorRules
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.time.ClockProvider
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.core.time.ManualDateRange
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.Direction
import com.pam.recap.domain.repository.SessionRepository
import com.pam.recap.data.security.ManualPendingActionStore
import com.pam.recap.domain.repository.FinanceRefreshBus
import com.pam.recap.domain.usecase.AddManualEntryUseCase
import com.pam.recap.ui.feature.transactions.TransactionEditLimits
import com.pam.recap.ui.util.MoneyInput
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

/** FR-26 manual entry form state. */
data class ManualEntryUiState(
    val direction: Direction = Direction.DEBIT,
    val amountText: String = "",
    val description: String = "",
    /** Optional private note (SRS v0.7 FR-26); blank saves as no note. */
    val note: String = "",
    val category: Category = Category.UNCATEGORIZED,
    val date: LocalDate,
    /** Jakarta "today" resolved from the clock, so the range stays testable. */
    val today: LocalDate,
    val dateRange: ManualDateRange,
    val isSaving: Boolean = false,
    val error: AppError? = null,
    val saved: Boolean = false,
) {
    val parsedAmount: Money? get() = MoneyInput.parse(amountText)

    /** NFR-15: a row may sit in the rolling window, never in the future or before the floor. */
    val dateOutOfWindow: Boolean get() = !dateRange.contains(date)

    val canSave: Boolean
        get() = !isSaving &&
            parsedAmount?.isPositive == true &&
            description.isNotBlank() &&
            !dateOutOfWindow
}

/**
 * FR-26: manual rows save with `source = MANUAL`, which every surface renders with the Manual
 * badge so an entry is never mistaken for an imported one. Amount parsing is exact (Long minor
 * units); a malformed amount blocks the save instead of becoming zero.
 *
 * NFR-15: the date stepper cannot reach the future or the day before the rolling-window floor,
 * and the save is refused if a restored draft somehow lands outside that range.
 */
@HiltViewModel
class ManualEntryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sessions: SessionRepository,
    private val addManualEntry: AddManualEntryUseCase,
    clock: ClockProvider,
    private val pendingActions: ManualPendingActionStore? = null,
) : ViewModel() {

    private val zone: ZoneId = ZoneId.of(JakartaMonth.ZONE.id)
    private val today: LocalDate = java.time.Instant
        .ofEpochMilli(clock.now().toEpochMilliseconds())
        .atZone(zone)
        .toLocalDate()
    private val dateRange: ManualDateRange = ManualDateRange.of(today)
    private val requestedMonth = savedStateHandle.get<String>("monthId")?.let(JakartaMonth::parse)

    private val _state = MutableStateFlow(
        ManualEntryUiState(
            date = requestedMonth
                ?.let { LocalDate.of(it.year, it.month, 1) }
                ?.let(dateRange::clamp)
                ?: today,
            today = today,
            dateRange = dateRange,
        ),
    )

    init {
        viewModelScope.launch {
            val ownerId = sessions.current()?.ownerId ?: return@launch
            val pending = pendingActions?.read(ownerId) ?: return@launch
            _state.update {
                it.copy(
                    amountText = pending.amountText,
                    description = pending.description,
                    note = pending.note.take(TransactionEditLimits.NOTE),
                    date = dateRange.clamp(
                        runCatching { LocalDate.parse(pending.dateIso) }.getOrDefault(it.date),
                    ),
                    direction = runCatching { Direction.valueOf(pending.direction) }.getOrDefault(it.direction),
                    category = Category.fromCode(pending.categoryCode) ?: it.category,
                )
            }
        }
    }
    val state: StateFlow<ManualEntryUiState> = _state.asStateFlow()

    fun setDirection(direction: Direction) {
        _state.update {
            it.copy(
                direction = direction,
                category = if (direction == Direction.CREDIT) Category.INCOME else Category.UNCATEGORIZED,
            )
        }
    }

    fun setAmount(text: String) {
        _state.update { it.copy(amountText = MoneyInput.sanitize(text)) }
    }

    fun setDescription(text: String) {
        _state.update { it.copy(description = text) }
    }

    /** Optional note, capped at the shared edit limit so the field can never exceed the column. */
    fun setNote(text: String) {
        _state.update { it.copy(note = text.take(TransactionEditLimits.NOTE)) }
    }

    fun setCategory(category: Category) {
        _state.update { it.copy(category = category) }
    }

    /** NFR-15: stepping is clamped to the window, so the arrows stop at the floor and today. */
    fun shiftDate(days: Long) {
        _state.update { it.copy(date = dateRange.clamp(it.date.plusDays(days))) }
    }

    fun save() {

        val current = _state.value
        val amount = current.parsedAmount ?: return
        if (!current.canSave) {
            // Only the date range gets an explicit explanation; other disabled reasons keep the
            // previous quiet behaviour (the button itself is the feedback).
            if (current.dateOutOfWindow) {
                _state.update { it.copy(error = AppError.Validation(AppErrorRules.INVALID_DATE)) }
            }
            return
        }
        if (current.isSaving) return
        _state.update { it.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            val ownerId = sessions.current()?.ownerId
            if (ownerId == null) {
                _state.update { it.copy(isSaving = false, error = AppError.Validation(AppErrorRules.NOT_SIGNED_IN)) }
                return@launch
            }
            val eventAt = kotlin.time.Instant.fromEpochMilliseconds(
                current.date.atTime(LocalTime.NOON).atZone(zone).toInstant().toEpochMilli(),
            )
            val identity = listOf(
                current.direction.name, current.amountText, current.description, current.note,
                current.date.toString(), current.category.code,
            ).joinToString("|")
            val existing = pendingActions?.read(ownerId)
            val key = if (existing?.identity == identity) {
                existing.key
            } else {
                val fresh = java.util.UUID.randomUUID().toString()
                if (pendingActions != null && !pendingActions.save(
                        ownerId,
                        ManualPendingActionStore.Pending(
                            key = fresh, identity = identity, amountText = current.amountText,
                            description = current.description, note = current.note,
                            dateIso = current.date.toString(),
                            direction = current.direction.name, categoryCode = current.category.code,
                        ),
                    )
                ) {
                    _state.update {
                        it.copy(isSaving = false, error = AppError.Storage("pending_action_not_saved"))
                    }
                    return@launch
                }
                fresh
            }
            when (
                val result = addManualEntry(
                    ownerId = ownerId,
                    direction = current.direction,
                    amount = amount,
                    eventAt = eventAt,
                    description = current.description,
                    category = current.category,
                    note = current.note,
                    idempotencyKey = key,
                )
            ) {
                is Outcome.Failure -> _state.update { it.copy(isSaving = false, error = result.error) }
                is Outcome.Success -> {
                    pendingActions?.clear(ownerId)
                    FinanceRefreshBus.notify(ownerId, JakartaMonth.of(eventAt).id())
                    _state.update { it.copy(isSaving = false, saved = true) }
                }
            }
        }
    }
}
