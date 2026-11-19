package com.pam.recap.domain.usecase

import com.pam.recap.core.time.ClockProvider
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.domain.repository.TransactionRepository
import javax.inject.Inject

/**
 * Picks the month a screen should open on.
 *
 * The demo dataset is February 2026 while the device clock may be any month, so opening on
 * "today" would show an empty recap (FR-20 misread as "no spending"). The newest month that
 * actually has imported data wins; a brand-new account falls back to the current month.
 */
class ResolveInitialMonthUseCase @Inject constructor(
    private val transactions: TransactionRepository,
    private val clock: ClockProvider,
) {

    suspend operator fun invoke(ownerId: String): JakartaMonth {
        val latest = transactions.latestEventAt(ownerId)
            ?: return clock.currentMonth()
        return JakartaMonth.of(latest)
    }
}
