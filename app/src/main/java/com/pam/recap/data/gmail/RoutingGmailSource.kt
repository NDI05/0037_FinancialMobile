package com.pam.recap.data.gmail

import com.pam.recap.core.result.Outcome
import com.pam.recap.domain.model.GmailConnectionState
import com.pam.recap.domain.repository.AppPasswordVault
import com.pam.recap.domain.repository.GmailMailboxLabelProvider
import com.pam.recap.domain.repository.GmailPage
import com.pam.recap.domain.repository.GmailSource
import com.pam.recap.domain.repository.LivinQuery
import com.pam.recap.domain.repository.RawMessage
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CR-001/Fase 3: one source, two backends. With a stored app password every call goes to
 * [ImapGmailSource]; without one the deterministic fixture mailbox stays bound, so the demo and the
 * offline path keep working exactly as before.
 *
 * The check is per call (not per app start) because a user can paste a password while the app runs.
 */
@Singleton
class RoutingGmailSource @Inject constructor(
    private val vault: AppPasswordVault,
    private val imap: ImapGmailSource,
    private val fixture: FixtureGmailSource,
) : GmailSource, GmailMailboxLabelProvider {

    private suspend fun active(): GmailSource =
        if (vault.hasCredential()) imap else fixture

    override suspend fun authorize(): Outcome<Unit> = active().authorize()

    override fun connectionState(): Flow<GmailConnectionState> = fixture.connectionState()

    override suspend fun search(query: LivinQuery, pageToken: String?): Outcome<GmailPage> =
        active().search(query, pageToken)

    override suspend fun fetchMessage(id: String): Outcome<RawMessage> =
        active().fetchMessage(id)

    override suspend fun disconnect(): Outcome<Unit> =
        if (vault.hasCredential()) imap.disconnect() else fixture.disconnect()

    override fun mailboxLabel(): String? =
        // The IMAP label is the saved address; the fixture label is its own constant.
        imap.mailboxLabel() ?: fixture.mailboxLabel()
}
