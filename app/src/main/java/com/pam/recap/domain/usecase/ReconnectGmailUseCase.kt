package com.pam.recap.domain.usecase

import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.domain.repository.AppPasswordVault
import com.pam.recap.domain.repository.SessionRepository
import javax.inject.Inject

/**
 * CR-003: after login/restart, reconnect a live mailbox using the owner's stored credential, with
 * no App Password prompt. Does nothing when the owner has no stored link or an explicit disconnect
 * cleared it. ConnectGmailUseCase still checks server consent and revalidates IMAP.
 */
class ReconnectGmailUseCase @Inject constructor(
    private val vault: AppPasswordVault,
    private val sessions: SessionRepository,
    private val connectGmail: ConnectGmailUseCase,
) {
    suspend operator fun invoke(ownerId: String): Outcome<Unit> {
        if (sessions.current()?.ownerId != ownerId) {
            return Outcome.Failure(AppError.Conflict("owner_changed"))
        }
        if (!vault.hasCredential(ownerId)) return Outcome.Success(Unit)
        val result = connectGmail(ownerId)
        return when (result) {
            is Outcome.Success -> Outcome.Success(Unit)
            is Outcome.Failure -> result
        }
    }
}
