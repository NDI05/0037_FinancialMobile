package com.pam.recap.data.notification

/**
 * Pure CR-010 N-XX planner: decides which persisted transactions are NEW for one sync cycle and
 * advances the per-account watermark. No Android types are used so the dedupe/backfill rules are
 * unit-testable without Robolectric.
 *
 * Rules:
 *  - the first run only establishes a baseline (never notifies);
 *  - MANUAL entries and backfilled rows older than the watermark are ignored;
 *  - a transaction id that was already seen is a replay and is ignored;
 *  - duplicates inside one cycle collapse to one id;
 *  - the watermark advances even when notifications are disabled/denied, so enabling later does
 *    not replay history.
 */
data class TransactionNotificationCandidate(
    val id: String,
    val source: String,
    val eventAtEpochMs: Long,
)

data class TransactionNotificationWatermark(
    val established: Boolean,
    val lastEventAtEpochMs: Long,
    val seenIds: List<String>,
    /** Guards the month rollover: a new month re-baselines instead of announcing its history. */
    val monthId: String? = null,
)

data class TransactionNotificationPlan(
    val newTransactions: List<TransactionNotificationCandidate>,
    val watermark: TransactionNotificationWatermark,
) {
    val shouldNotify: Boolean get() = newTransactions.isNotEmpty()
}

object TransactionNotificationPlanner {

    private const val MAX_SEEN_IDS = 500
    private const val FUTURE_SKEW_MS = 5 * 60 * 1000L

    fun plan(
        previous: TransactionNotificationWatermark?,
        candidates: List<TransactionNotificationCandidate>,
        nowEpochMs: Long,
        monthId: String? = null,
    ): TransactionNotificationPlan {
        val eligible = candidates.asSequence()
            .filter { !it.source.equals(SOURCE_MANUAL, ignoreCase = true) }
            .filter { it.eventAtEpochMs <= nowEpochMs + FUTURE_SKEW_MS }
            .sortedBy { it.eventAtEpochMs }
            .distinctBy { it.id }
            .toList()

        val monthChanged = previous != null &&
            previous.monthId != null &&
            monthId != null &&
            previous.monthId != monthId
        if (previous == null || !previous.established || monthChanged) {
            return TransactionNotificationPlan(
                newTransactions = emptyList(),
                watermark = baselineFrom(eligible, nowEpochMs, monthId),
            )
        }

        val seen = previous.seenIds.toHashSet()
        val fresh = eligible.filter { candidate ->
            candidate.id !in seen && candidate.eventAtEpochMs >= previous.lastEventAtEpochMs
        }
        val latestEventAt = maxOf(
            previous.lastEventAtEpochMs,
            eligible.maxOfOrNull { it.eventAtEpochMs } ?: previous.lastEventAtEpochMs,
        )
        val nextSeen = (previous.seenIds + eligible.map { it.id })
            .distinct()
            .takeLast(MAX_SEEN_IDS)
        return TransactionNotificationPlan(
            newTransactions = fresh,
            watermark = TransactionNotificationWatermark(
                established = true,
                lastEventAtEpochMs = latestEventAt,
                seenIds = nextSeen,
                monthId = previous.monthId ?: monthId,
            ),
        )
    }

    private fun baselineFrom(
        eligible: List<TransactionNotificationCandidate>,
        nowEpochMs: Long,
        monthId: String?,
    ): TransactionNotificationWatermark = TransactionNotificationWatermark(
        established = true,
        // A brand-new account with no rows starts at the wall clock so a later historical import
        // (backfill) cannot masquerade as a new Livin notification.
        lastEventAtEpochMs = eligible.maxOfOrNull { it.eventAtEpochMs } ?: nowEpochMs,
        seenIds = eligible.map { it.id }.distinct().takeLast(MAX_SEEN_IDS),
        monthId = monthId,
    )

    private const val SOURCE_MANUAL = "MANUAL"
}
