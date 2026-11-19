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
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.Direction
import com.pam.recap.domain.repository.SessionRepository
import com.pam.recap.domain.usecase.AddManualEntryUseCase
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
    val category: Category = Category.UNCATEGORIZED,
    val date: LocalDate,
    val isSaving: Boolean = false,
    val error: AppError? = null,
    val saved: Boolean = false,
) {
    val parsedAmount: Money? get() = MoneyInput.parse(amountText)

    val canSave: Boolean
        get() = !isSaving && parsedAmount?.isPositive == true && description.isNotBlank()
}

/**
 * FR-26: manual rows save with `source = MANUAL`, which every surface renders with the Manual
 * badge so an entry is never mistaken for an imported one. Amount parsing is exact (Long minor
 * units); a malformed amount blocks the save instead of becoming zero.
 */
@HiltViewModel
class ManualEntryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sessions: SessionRepository,
    private val addManualEntry: AddManualEntryUseCase,
    clock: ClockProvider,
) : ViewModel() {

    private val zone: ZoneId = ZoneId.of(JakartaMonth.ZONE.id)
    private val requestedMonth = savedStateHandle.get<String>("monthId")?.let(JakartaMonth::parse)

    private val _state = MutableStateFlow(
        ManualEntryUiState(
            date = requestedMonth?.let { LocalDate.of(it.year, it.month, 1) } ?: LocalDate.now(zone),
        ),
    )
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

    fun setCategory(category: Category) {
        _state.update { it.copy(category = category) }
    }

    fun shiftDate(days: Long) {
        _state.update { it.copy(date = it.date.plusDays(days)) }
    }

    fun save() {
        val current = _state.value
        val amount = current.parsedAmount ?: return
        if (!current.canSave) return
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
            when (
                val result = addManualEntry(
                    ownerId = ownerId,
                    direction = current.direction,
                    amount = amount,
                    eventAt = eventAt,
                    description = current.description,
                    category = current.category,
                )
            ) {
                is Outcome.Failure -> _state.update { it.copy(isSaving = false, error = result.error) }
                is Outcome.Success -> _state.update { it.copy(isSaving = false, saved = true) }
            }
        }
    }
}
