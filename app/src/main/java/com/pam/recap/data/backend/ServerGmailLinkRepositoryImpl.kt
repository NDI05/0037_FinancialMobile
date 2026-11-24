package com.pam.recap.data.backend

import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.result.flatMap
import com.pam.recap.core.result.map
import com.pam.recap.domain.model.GmailAuthorization
import com.pam.recap.domain.model.GmailLinkStatus
import com.pam.recap.domain.model.GmailSyncProgress
import com.pam.recap.domain.model.GmailSyncRequest
import com.pam.recap.domain.model.GmailSyncState
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.domain.repository.GmailLinkRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant

/**
 * Thin projection of the Gmail OAuth endpoints; all policy (state, PKCE, token encryption,
 * backfill, watch) stays on the Go backend. No token or full address is kept on this device.
 */
@Singleton
class ServerGmailLinkRepositoryImpl @Inject constructor(
    private val api: PamApiClient,
    private val endpoints: BackendEndpoints,
) : GmailLinkRepository {

    override val isEnabled: Boolean get() = endpoints.isEnabled

    override suspend fun status(ownerId: String): Outcome<GmailLinkStatus> =
        api.gmailStatus(ownerId).map(RemoteGmailStatus::toDomain)

    override suspend fun startConnect(
        ownerId: String,
        reconnect: Boolean,
    ): Outcome<GmailAuthorization> =
        api.gmailConnectStart(ownerId, reconnect).map { remote ->
            GmailAuthorization(
                authorizationUrl = remote.authorizationUrl,
                expiresAt = remote.expiresAt.asInstantOrNull(),
            )
        }

    override suspend fun disconnect(ownerId: String): Outcome<Unit> =
        api.gmailDisconnect(ownerId).flatMap { remote ->
            if (remote.disconnected) {
                Outcome.Success(Unit)
            } else {
                Outcome.Failure(AppError.Unknown("gmail_disconnect_not_acknowledged"))
            }
        }

    override suspend fun requestSync(ownerId: String): Outcome<GmailSyncRequest> =
        api.gmailSync(ownerId).map { remote ->
            GmailSyncRequest(
                queued = remote.queued,
                coalesced = remote.coalesced,
                requestedAt = remote.requestedAt.asInstantOrNull(),
                nextAllowedAt = remote.nextAllowedAt.asInstantOrNull(),
            )
        }
}

/** Timestamps are optional metadata; a malformed one must not fail the whole status read. */
private fun String?.asInstantOrNull(): Instant? =
    this?.takeIf(String::isNotBlank)?.let { raw -> runCatching { Instant.parse(raw) }.getOrNull() }

private fun RemoteGmailStatus.toDomain(): GmailLinkStatus = GmailLinkStatus(
    connected = connected,
    accountEmailMasked = accountEmailMasked?.takeIf(String::isNotBlank),
    scope = scope?.takeIf(String::isNotBlank),
    syncState = GmailSyncState.fromWire(syncState),
    connectedAt = connectedAt.asInstantOrNull(),
    lastSyncAt = lastSyncAt.asInstantOrNull(),
    watchExpiresAt = watchExpiresAt.asInstantOrNull(),
    lastErrorCode = lastErrorCode?.takeIf(String::isNotBlank),
    needsReauth = needsReauth,
    lastAttemptAt = lastAttemptAt.asInstantOrNull(),
    lastSuccessAt = lastSuccessAt.asInstantOrNull(),
    nextSyncAt = nextSyncAt.asInstantOrNull(),
    syncRequestedAt = syncRequestedAt.asInstantOrNull(),
    progress = progress?.toDomain(),
)

private fun RemoteGmailProgress.toDomain(): GmailSyncProgress = GmailSyncProgress(
    phase = phase?.takeIf(String::isNotBlank),
    // A malformed month must not fail the status read; the UI then just omits the month label.
    month = month?.let { raw -> runCatching { JakartaMonth.parse(raw) }.getOrNull() },
    monthsDone = monthsDone.coerceAtLeast(0),
    monthsTotal = monthsTotal.coerceAtLeast(0),
    pagesDone = pagesDone.coerceAtLeast(0),
)
