package com.pam.recap.data.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionNotificationPlannerTest {

    private val now = 1_800_000_000_000L
    private val baseline = TransactionNotificationWatermark(
        established = true,
        lastEventAtEpochMs = now - 60_000,
        seenIds = listOf("old-1"),
    )

    @Test
    fun firstRunEstablishesBaselineWithoutNotifying() {
        val plan = TransactionNotificationPlanner.plan(
            previous = null,
            candidates = listOf(candidate("old-1", now - 120_000), candidate("old-2", now - 90_000)),
            nowEpochMs = now,
        )

        assertTrue(plan.newTransactions.isEmpty())
        assertTrue(plan.watermark.established)
        assertEquals(listOf("old-1", "old-2"), plan.watermark.seenIds)
        assertEquals(now - 90_000, plan.watermark.lastEventAtEpochMs)
    }

    @Test
    fun onlyTransactionsNewerThanWatermarkNotify() {
        val plan = TransactionNotificationPlanner.plan(
            previous = baseline,
            candidates = listOf(
                candidate("old-1", now - 120_000),
                candidate("new-1", now - 30_000),
            ),
            nowEpochMs = now,
        )

        assertEquals(listOf("new-1"), plan.newTransactions.map { it.id })
        assertTrue(plan.shouldNotify)
    }

    @Test
    fun newTransactionWithSameEventTimestampAsWatermarkStillNotifies() {
        val plan = TransactionNotificationPlanner.plan(
            previous = baseline,
            candidates = listOf(
                candidate("old-1", now - 60_000),
                candidate("new-same-ms", now - 60_000),
            ),
            nowEpochMs = now,
        )

        assertEquals(listOf("new-same-ms"), plan.newTransactions.map { it.id })
    }

    @Test
    fun groupCycleReportsEveryNewIdOnce() {
        val plan = TransactionNotificationPlanner.plan(
            previous = baseline,
            candidates = listOf(
                candidate("new-1", now - 20_000),
                candidate("new-2", now - 10_000),
                candidate("new-1", now - 20_000),
            ),
            nowEpochMs = now,
        )

        assertEquals(listOf("new-1", "new-2"), plan.newTransactions.map { it.id })
    }

    @Test
    fun replayingTheSameCycleDoesNotNotifyAgain() {
        val first = TransactionNotificationPlanner.plan(
            previous = baseline,
            candidates = listOf(candidate("new-1", now - 20_000)),
            nowEpochMs = now,
        )
        val replay = TransactionNotificationPlanner.plan(
            previous = first.watermark,
            candidates = listOf(candidate("new-1", now - 20_000)),
            nowEpochMs = now + 1_000,
        )

        assertTrue(first.shouldNotify)
        assertFalse(replay.shouldNotify)
        assertTrue(replay.newTransactions.isEmpty())
    }

    @Test
    fun manualRowsAndOlderBackfillAreExcluded() {
        val plan = TransactionNotificationPlanner.plan(
            previous = baseline,
            candidates = listOf(
                candidate("new-manual", now - 5_000, source = "MANUAL"),
                candidate("backfill-old", now - 600_000),
                candidate("new-email", now - 5_000),
            ),
            nowEpochMs = now,
        )

        assertEquals(listOf("new-email"), plan.newTransactions.map { it.id })
    }

    @Test
    fun emptyBaselineDoesNotAnnounceAFutureBackfill() {
        val emptyBaseline = TransactionNotificationPlanner.plan(
            previous = null,
            candidates = emptyList(),
            nowEpochMs = now,
        )
        val backfill = TransactionNotificationPlanner.plan(
            previous = emptyBaseline.watermark,
            candidates = listOf(candidate("backfill-1", now - 30_000)),
            nowEpochMs = now + 1_000,
        )

        assertEquals(now, emptyBaseline.watermark.lastEventAtEpochMs)
        assertFalse(backfill.shouldNotify)
    }

    @Test
    fun monthRolloverRebaselinesInsteadOfAnnouncingHistory() {
        val previousMonth = baseline.copy(monthId = "2026-10")
        val plan = TransactionNotificationPlanner.plan(
            previous = previousMonth,
            candidates = listOf(candidate("nov-1", now - 30_000)),
            nowEpochMs = now,
            monthId = "2026-11",
        )

        assertFalse(plan.shouldNotify)
        assertEquals("2026-11", plan.watermark.monthId)
    }

    @Test
    fun futureDatedRowsAreDropped() {
        val plan = TransactionNotificationPlanner.plan(
            previous = baseline,
            candidates = listOf(candidate("future-1", now + 60 * 60 * 1000)),
            nowEpochMs = now,
        )

        assertFalse(plan.shouldNotify)
    }

    private fun candidate(
        id: String,
        eventAtEpochMs: Long,
        source: String = "EMAIL",
    ) = TransactionNotificationCandidate(
        id = id,
        source = source,
        eventAtEpochMs = eventAtEpochMs,
    )
}
