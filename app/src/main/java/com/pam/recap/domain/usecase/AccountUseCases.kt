package com.pam.recap.domain.usecase

import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.AppErrorRules
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.security.OwnerIdFactory
import com.pam.recap.core.time.ClockProvider
import com.pam.recap.core.util.IdGenerator
import com.pam.recap.domain.model.ConsentPurpose
import com.pam.recap.domain.model.DeletionRequest
import com.pam.recap.domain.model.DeletionResult
import com.pam.recap.domain.model.DeletionState
import com.pam.recap.domain.model.GmailConnection
import com.pam.recap.domain.model.GmailConnectionState
import com.pam.recap.domain.model.Session
import com.pam.recap.domain.repository.AnalysisRepository
import com.pam.recap.domain.repository.BackendDeletionSync
import com.pam.recap.domain.repository.AppPasswordVault
import com.pam.recap.domain.repository.BackendSessionRepository
import com.pam.recap.domain.repository.BudgetRepository
import com.pam.recap.domain.repository.ConsentRepository
import com.pam.recap.domain.repository.DeletionRepository
import com.pam.recap.domain.repository.DemoDataPort
import com.pam.recap.domain.repository.GmailConnectionRepository
import com.pam.recap.domain.repository.GmailMailboxLabelProvider
import com.pam.recap.domain.repository.GmailSource
import com.pam.recap.domain.repository.ImportRepository
import com.pam.recap.domain.repository.SessionRepository
import com.pam.recap.domain.repository.SummaryRepository
import com.pam.recap.domain.repository.TransactionRepository
import javax.inject.Inject
import kotlin.time.Instant

/**
 * FR-01/FR-02 sign-in. The account id is hashed into an opaque owner id so the encrypted database
 * never stores the raw mailbox address (only the single `mailboxLabel` the settings screen shows).
 *
 * OI-01: when the app runs in demo mode the signed-in session seeds the February-2026 dataset
 * once (A9), through the [DemoDataPort] instead of touching the data layer directly.
 */
class SignInUseCase @Inject constructor(
    private val sessions: SessionRepository,
    private val demoData: DemoDataPort,
    private val clock: ClockProvider,
) {

    /**
     * [isDemo] is a *display* label only ("this session runs on the offline demo"). [seedDemo]
     * decides whether the bundled dataset is written, and only the offline mode sets it (F1): a
     * server-backed account must never receive sample rows, connected mailbox or not.
     */
    suspend operator fun invoke(
        accountId: String,
        displayName: String? = null,
        mailboxLabel: String? = null,
        isDemo: Boolean = false,
        seedDemo: Boolean = false,
    ): Outcome<Session> {
        if (accountId.isBlank()) {
            return Outcome.Failure(AppError.Validation(AppErrorRules.NOT_SIGNED_IN))
        }
        val session = Session(
            ownerId = OwnerIdFactory.ownerIdFor(accountId),
            displayName = displayName,
            mailboxLabel = mailboxLabel,
            signedInAt = clock.now(),
            isDemo = isDemo,
        )
        sessions.signIn(session)
        if (seedDemo) {
            runCatching { demoData.seedIfNeeded(session.ownerId) }
        }
        return Outcome.Success(session)
    }
}

/**
 * FR-05 sign-out: disconnect the mailbox and drop the owner's financial cache so nothing can leak
 * into the next session. The consent ledger is an audit record and is kept.
 */
class SignOutUseCase @Inject constructor(
    private val sessions: SessionRepository,
    private val gmailSource: GmailSource,
    private val gmailConnection: GmailConnectionRepository,
    private val transactions: TransactionRepository,
    private val summaries: SummaryRepository,
    private val analyses: AnalysisRepository,
    private val budgets: BudgetRepository,
    private val imports: ImportRepository,
    private val backendSessions: BackendSessionRepository,
    private val appPasswordVault: AppPasswordVault,
) {

    suspend operator fun invoke(ownerId: String): Outcome<Unit> {
        // FR-05: invalidate the server session before the local token cache is dropped.
        runCatching { backendSessions.revoke() }
        // FR-54: signing out also forgets the mailbox app password.
        runCatching { appPasswordVault.clear() }
        runCatching { gmailSource.disconnect() }
        transactions.deleteAll(ownerId)
        summaries.deleteAll(ownerId)
        analyses.deleteAll(ownerId)
        budgets.deleteAll(ownerId)
        imports.deleteAll(ownerId)
        gmailConnection.deleteAll(ownerId)
        sessions.signOut()
        return Outcome.Success(Unit)
    }
}

/**
 * FR-06/FR-07/FR-10 connect: authorization must succeed before the mailbox is usable, and the
 * recorded state always mirrors what actually happened.
 */
class ConnectGmailUseCase @Inject constructor(
    private val gmailSource: GmailSource,
    private val mailboxLabelProvider: GmailMailboxLabelProvider,
    private val consents: ConsentRepository,
    private val connections: GmailConnectionRepository,
    private val sessions: SessionRepository,
    private val clock: ClockProvider,
) {

    suspend operator fun invoke(ownerId: String): Outcome<GmailConnection> {
        val consent = consents.latest(ownerId, ConsentPurpose.GMAIL_READ)
        if (consent?.isGranted != true) {
            return Outcome.Failure(AppError.Validation(AppErrorRules.CONSENT_REQUIRED))
        }

        val authorization = gmailSource.authorize()
        val now = clock.now()
        if (authorization is Outcome.Failure) {
            val state = when (authorization.error) {
                is AppError.Configuration -> GmailConnectionState.UNAVAILABLE
                is AppError.PermissionRequired -> GmailConnectionState.REAUTHORIZATION_REQUIRED
                else -> GmailConnectionState.UNAVAILABLE
            }
            val existing = connections.get(ownerId)
            connections.save(
                (existing ?: GmailConnection.disconnected(ownerId, now)).copy(
                    state = state,
                    updatedAt = now,
                ),
            )
            return Outcome.Failure(authorization.error)
        }

        val label = mailboxLabelProvider.mailboxLabel()
        val connection = (connections.get(ownerId) ?: GmailConnection.disconnected(ownerId, now)).copy(
            state = GmailConnectionState.CONNECTED,
            mailboxLabel = label,
            scope = GmailConnection.IMAP_SCOPE,
            connectedAt = now,
            updatedAt = now,
        )
        connections.save(connection)
        sessions.updateMailbox(ownerId, label)
        return Outcome.Success(connection)
    }
}

/**
 * FR-46 disconnect: the local state and token are cleared first, then the grant is revoked where
 * the provider supports it. A revoke failure is surfaced so the UI can tell the user the
 * provider-side grant may still exist.
 */
class DisconnectGmailUseCase @Inject constructor(
    private val gmailSource: GmailSource,
    private val connections: GmailConnectionRepository,
    private val sessions: SessionRepository,
    private val appPasswordVault: AppPasswordVault,
    private val clock: ClockProvider,
) {

    suspend operator fun invoke(ownerId: String): Outcome<Unit> {
        val revoke = gmailSource.disconnect()
        // FR-54: disconnecting removes the app password from the device as well.
        runCatching { appPasswordVault.clear() }
        val now = clock.now()
        val existing = connections.get(ownerId)
        connections.save(
            (existing ?: GmailConnection.disconnected(ownerId, now)).copy(
                state = GmailConnectionState.DISCONNECTED,
                mailboxLabel = null,
                connectedAt = null,
                updatedAt = now,
            ),
        )
        sessions.updateMailbox(ownerId, null)
        return revoke
    }
}

/**
 * FR-47/FR-48 deletion: requires a recent re-authentication, blocks sync while in flight, clears
 * every owner-scoped store and records the completion. The retention explanation is UI copy, so
 * the domain only exposes the state.
 */
class RequestDeletionUseCase @Inject constructor(
    private val deletion: DeletionRepository,
    private val transactions: TransactionRepository,
    private val summaries: SummaryRepository,
    private val analyses: AnalysisRepository,
    private val budgets: BudgetRepository,
    private val imports: ImportRepository,
    private val consents: ConsentRepository,
    private val gmailConnection: GmailConnectionRepository,
    private val sessions: SessionRepository,
    private val clock: ClockProvider,
    private val idGenerator: IdGenerator,
    private val backendDeletion: BackendDeletionSync,
    private val appPasswordVault: AppPasswordVault,
) {

    suspend operator fun invoke(
        ownerId: String,
        reauthenticatedAt: Instant?,
        serverPassword: String? = null,
    ): Outcome<DeletionResult> {
        val now = clock.now()
        if (reauthenticatedAt == null || now.toEpochMilliseconds() - reauthenticatedAt.toEpochMilliseconds() > REAUTH_WINDOW_MILLIS) {
            // FR-47: recent re-authentication is a precondition, not a hint.
            return Outcome.Failure(AppError.Validation(AppErrorRules.REAUTH_REQUIRED))
        }

        val requestId = idGenerator.newId()
        var request = DeletionRequest(
            id = requestId,
            ownerId = ownerId,
            state = DeletionState.REQUESTED,
            requestedAt = now,
            reauthenticatedAt = reauthenticatedAt,
            completedAt = null,
        )
        deletion.save(request)

        // FR-48/contract §4.3: tell the backend so it blocks sync and sweeps its aggregates. The
        // returned SLA is surfaced in the UI; a failure never stops the local deletion.
        val serverSlaDays = if (serverPassword.isNullOrBlank()) {
            (runCatching { backendDeletion.request(ownerId) }.getOrNull() as? Outcome.Success)?.value
        } else {
            (runCatching { backendDeletion.request(ownerId, serverPassword) }.getOrNull()
                as? Outcome.Success)?.value?.slaDays
        }

        request = request.copy(state = DeletionState.IN_PROGRESS)
        deletion.save(request)

        return runCatching {
            transactions.deleteAll(ownerId)
            summaries.deleteAll(ownerId)
            analyses.deleteAll(ownerId)
            budgets.deleteAll(ownerId)
            imports.deleteAll(ownerId)
            gmailConnection.deleteAll(ownerId)
            consents.deleteAll(ownerId)
            appPasswordVault.clear()
            sessions.signOut()

            val completed = request.copy(state = DeletionState.COMPLETED, completedAt = clock.now())
            deletion.save(completed)
            Outcome.Success(DeletionResult(request = completed, serverSlaDays = serverSlaDays))
        }.getOrElse { error ->
            val failed = request.copy(state = DeletionState.FAILED, failureCode = error::class.simpleName)
            deletion.save(failed)
            Outcome.Failure(AppError.Storage("deletion_failed"))
        }
    }

    private companion object {
        const val REAUTH_WINDOW_MILLIS = 5 * 60 * 1000L
    }
}
