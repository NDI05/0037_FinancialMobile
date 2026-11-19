package com.pam.recap.data.ai

import com.pam.recap.domain.model.AiAggregatePayload
import com.pam.recap.domain.repository.AnalysisRequest
import kotlinx.serialization.Serializable
import java.security.MessageDigest

/**
 * The exact body of `POST /v1/analyses` (contract §4.1) — `{monthId, inputRevision, payload}` with
 * the payload **nested**, not flattened. One class so the engine and the contract-bridge test
 * cannot drift apart.
 */
@Serializable
data class AnalysisRequestBody(
    val monthId: String,
    val inputRevision: Int,
    val payload: AiAggregatePayload,
)

fun AnalysisRequest.toBody(): AnalysisRequestBody = AnalysisRequestBody(
    monthId = monthId,
    inputRevision = inputRevision,
    payload = payload,
)

/**
 * Stable per owner + month + revision (contract §4.1), so a retry of the same frozen input is an
 * idempotent replay instead of a second provider call. The owner id is hashed: the raw id never
 * leaves the device in a header.
 */
fun AnalysisRequest.idempotencyKey(): String =
    "analysis:${ownerHash()}:$monthId:$inputRevision"

private fun AnalysisRequest.ownerHash(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(ownerId.toByteArray())
    return digest.take(8).joinToString("") { "%02x".format(it) }
}
