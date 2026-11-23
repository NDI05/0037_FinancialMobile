package com.pam.recap.data.gmail

import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.domain.model.GmailConnectionState
import com.pam.recap.domain.model.MailboxWatermark
import com.pam.recap.data.backend.BackendEndpoints
import com.pam.recap.domain.repository.AppPasswordVault
import com.pam.recap.domain.repository.GmailMailboxLabelProvider
import com.pam.recap.domain.repository.GmailPage
import com.pam.recap.domain.repository.GmailSource
import com.pam.recap.domain.repository.LivinQuery
import com.pam.recap.domain.repository.RawMessage
import com.pam.recap.domain.repository.SessionRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CR-001/Fase 3: one source, two backends. With a stored app password every call goes to
 * [ImapGmailSource]; without one the deterministic fixture mailbox stays bound, so the demo and the
 * offline path keep working exactly as before.
 *
 * The check is per call (not per app start) because a user can paste a password while the app runs.
 *
 * A server-backed account without a credential gets **no** mailbox at all: the fixture is demo data
 * for the offline demo, and serving it here would let a production account import sample emails.
 */
@Singleton
class RoutingGmailSource @Inject constructor(
    private val vault: AppPasswordVault,
    private val imap: ImapGmailSource,
    private val fixture: FixtureGmailSource,
    private val backend: BackendEndpoints,
    private val sessions: SessionRepository,
) : GmailSource, GmailMailboxLabelProvider {

    /** Null means "this account has no mailbox": server mode without a credential. */
    @Volatile
    private var activeOwnerId: String? = null

    private suspend fun ownerId(): String? = sessions.current()?.ownerId

    private suspend fun active(): GmailSource? {
        val owner = ownerId() ?: run {
            activeOwnerId = null
            return null
        }
        activeOwnerId = owner
        return when {
            vault.hasCredential(owner) -> imap
            backend.isEnabled -> null
            else -> fixture
        }
    }

    override suspend fun authorize(): Outcome<Unit> =
        active()?.authorize() ?: Outcome.Failure(AppError.Configuration(NO_MAILBOX))

    override fun connectionState(): Flow<GmailConnectionState> = fixture.connectionState()

    override suspend fun search(query: LivinQuery, pageToken: String?): Outcome<GmailPage> =
        active()?.search(query, pageToken) ?: Outcome.Failure(AppError.Configuration(NO_MAILBOX))

    override suspend fun fetchMessage(id: String): Outcome<RawMessage> =
        active()?.fetchMessage(id) ?: Outcome.Failure(AppError.Configuration(NO_MAILBOX))

    override suspend fun watermark(): Outcome<MailboxWatermark> =
        active()?.watermark() ?: Outcome.Failure(AppError.Configuration(NO_MAILBOX))

    override suspend fun searchDelta(
        query: LivinQuery,
        since: MailboxWatermark?,
        upTo: MailboxWatermark?,
        pageToken: String?,
    ): Outcome<GmailPage> =
        active()?.searchDelta(query, since, upTo, pageToken)
            ?: Outcome.Failure(AppError.Configuration(NO_MAILBOX))

    override suspend fun disconnect(): Outcome<Unit> {
        val owner = ownerId()
        activeOwnerId = null
        return when {
            owner != null && vault.hasCredential(owner) -> imap.disconnect()
            backend.isEnabled -> Outcome.Success(Unit)
            else -> fixture.disconnect()
        }
    }

    override fun mailboxLabel(): String? =
        // The IMAP label is cleared on logout; a signed-out or different owner never inherits it.
        if (activeOwnerId == null) null else imap.mailboxLabel() ?: if (backend.isEnabled) null else fixture.mailboxLabel()

    private companion object {
        const val NO_MAILBOX = "mailbox_not_configured"
    }
}
