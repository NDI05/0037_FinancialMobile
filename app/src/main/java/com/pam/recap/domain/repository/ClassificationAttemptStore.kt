package com.pam.recap.domain.repository

/**
 * Durable memory of rows the classifier already attempted.
 *
 * A row that the provider cannot decide stays `uncategorized` forever (the backend deliberately does
 * not fabricate a label). Without this ledger every new sweep would offer those rows again, so the
 * app would appear to "classify the same data twice" and could spend another provider call after a
 * row revision changes. Only terminal NEEDS_REVIEW attempts are recorded: transport/provider
 * failures stay retryable on purpose.
 *
 * The value is the transaction revision that was sent. A USER edit or a re-import bumps the
 * revision, which makes the row eligible again without a manual "reset" action.
 */
interface ClassificationAttemptStore {
    /** transactionId -> revision that already reached NEEDS_REVIEW. */
    suspend fun reviewed(ownerId: String): Map<String, Int>

    /** Merge [attempts] into the owner's ledger; later revisions replace earlier ones. */
    suspend fun recordReviewed(ownerId: String, attempts: Map<String, Int>)
}
