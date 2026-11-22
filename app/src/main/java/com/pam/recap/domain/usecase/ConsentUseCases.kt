package com.pam.recap.domain.usecase

import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.time.ClockProvider
import com.pam.recap.core.util.IdGenerator
import com.pam.recap.domain.model.Consent
import com.pam.recap.domain.model.ConsentDecision
import com.pam.recap.domain.model.ConsentPurpose
import com.pam.recap.domain.repository.AnalysisRepository
import com.pam.recap.domain.repository.BackendConsentSync
import com.pam.recap.domain.repository.ConsentRepository
import javax.inject.Inject

/**
 * FR-03: records one consent decision for one purpose. Gmail and AI are recorded separately and
 * each decision is appended to the ledger, never overwritten — the history screen needs the trail.
 */
class RecordConsentUseCase @Inject constructor(
    private val consents: ConsentRepository,
    private val analyses: AnalysisRepository,
    private val clock: ClockProvider,
    private val idGenerator: IdGenerator,
    private val backendConsent: BackendConsentSync,
) {

    suspend operator fun invoke(
        ownerId: String,
        purpose: ConsentPurpose,
        noticeVersion: String,
        decision: ConsentDecision,
    ): Outcome<Consent> {
        val consent = Consent(
            id = idGenerator.newId(),
            ownerId = ownerId,
            purpose = purpose,
            noticeVersion = noticeVersion,
            decision = decision,
            decidedAt = clock.now(),
        )
        consents.append(consent)

        // FR-03/FR-34: v0.4 must not present a local GRANTED as server-confirmed. The local ledger
        // is appended for recovery, but a failed server acknowledgement is returned to the caller.
        val serverAck = if (!backendConsent.isEnabled) {
            // Legacy/offline: keep the old fire-and-forget behavior; no server gate exists.
            runCatching { backendConsent.record(ownerId, purpose, decision, noticeVersion) }
            Outcome.Success(Unit)
        } else {
            runCatching {
                backendConsent.recordConfirmed(ownerId, purpose, decision, noticeVersion)
            }.getOrElse { Outcome.Failure(AppError.Network("consent_push_failed")) }
        }

        if (purpose == ConsentPurpose.AI_PROCESSING && decision == ConsentDecision.WITHDRAWN) {
            // FR-04: existing reports become historical instead of staying "available".
            analyses.markAllHistorical(ownerId)
        }
        return when (serverAck) {
            is Outcome.Success -> Outcome.Success(consent)
            is Outcome.Failure -> Outcome.Failure(serverAck.error)
        }
    }
}

/**
 * FR-04/FR-41: withdrawing AI consent blocks new requests and re-classifies stored reports as
 * historical. It does **not** delete them, and summaries stay usable.
 */
class WithdrawAiConsentUseCase @Inject constructor(
    private val recordConsent: RecordConsentUseCase,
) {

    suspend operator fun invoke(ownerId: String, noticeVersion: String): Outcome<Consent> =
        recordConsent(
            ownerId = ownerId,
            purpose = ConsentPurpose.AI_PROCESSING,
            noticeVersion = noticeVersion,
            decision = ConsentDecision.WITHDRAWN,
        )
}
