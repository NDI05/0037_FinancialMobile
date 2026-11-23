package com.pam.recap.ui.feature.status

import com.pam.recap.domain.model.CategoryQueuePhase
import com.pam.recap.domain.model.CategoryQueueSnapshot
import com.pam.recap.domain.model.SyncPhase
import com.pam.recap.domain.model.SyncSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class UpdateStatusMapperTest {

    private val now = Instant.parse("2026-11-23T02:00:00Z")
    private val success = now - 12.minutes

    private fun map(
        sync: SyncSnapshot = SyncSnapshot(),
        category: CategoryQueueSnapshot = CategoryQueueSnapshot(),
        online: Boolean = true,
    ) = UpdateStatusMapper.map(sync = sync, category = category, online = online)

    @Test
    fun `a finished healthy run is reported as succeeded with its own timestamp`() {
        val ui = map(sync = SyncSnapshot(phase = SyncPhase.SUCCEEDED, lastSuccessAt = success, lastAttemptAt = success))

        assertEquals(UpdateHeadline.SUCCEEDED, ui.headline)
        assertEquals(success, ui.lastSuccessAt)
        assertFalse(ui.isActive)
        assertTrue(ui.hasEverSucceeded)
    }

    @Test
    fun `an unacknowledged upload is never shown as a completed sync`() {
        val ui = map(
            sync = SyncSnapshot(phase = SyncPhase.SUCCEEDED, lastSuccessAt = success, pendingUpload = true),
        )

        assertEquals(UpdateHeadline.SAVING, ui.headline)
        assertTrue(ui.isActive)
        assertTrue(ui.pendingUpload)
    }

    @Test
    fun `needs attention outranks running work`() {
        val ui = map(
            sync = SyncSnapshot(phase = SyncPhase.SYNCING, lastErrorCode = "permission_required"),
        )

        assertEquals(UpdateHeadline.NEEDS_ATTENTION, ui.headline)
        assertEquals(1, ui.attention.size)
        assertEquals(AttentionItem.Scope.TRANSACTIONS, ui.attention.single().scope)
    }

    @Test
    fun `a partial range never claims to be up to date`() {
        val ui = map(
            sync = SyncSnapshot(
                phase = SyncPhase.SUCCEEDED,
                lastSuccessAt = success,
                lastErrorCode = "partial_import",
                partial = true,
                savedCount = 2,
                unresolvedCount = 0,
            ),
        )

        assertEquals(UpdateHeadline.NEEDS_ATTENTION, ui.headline)
        assertTrue(ui.partial)
        assertTrue(ui.needsAttention)
        assertEquals(listOf("partial_import"), ui.attention.map { it.code })
        assertEquals(2, ui.savedCount)
    }

    @Test
    fun `an unwritable cursor and unresolved mail stay visible in the panel`() {
        val ui = map(
            sync = SyncSnapshot(
                phase = SyncPhase.PAUSED,
                storageFailure = true,
                skippedCount = 3,
                unresolvedCount = 5,
            ),
        )

        assertEquals(UpdateHeadline.NEEDS_ATTENTION, ui.headline)
        assertEquals(
            setOf("storage_unavailable", "unresolved_items"),
            ui.attention.map { it.code }.toSet(),
        )
        assertEquals(5, ui.unresolvedCount)
        assertEquals(3, ui.skippedCount)
    }

    @Test
    fun `a category failure is attributed to categories, not to gmail`() {
        val ui = map(category = CategoryQueueSnapshot(phase = CategoryQueuePhase.NEEDS_ATTENTION, errorCode = "budget_exhausted"))

        assertEquals(UpdateHeadline.NEEDS_ATTENTION, ui.headline)
        assertEquals(AttentionItem.Scope.CATEGORIES, ui.attention.single().scope)
        assertEquals("budget_exhausted", ui.attention.single().code)
    }

    @Test
    fun `offline wins over idle but not over an action item`() {
        assertEquals(UpdateHeadline.OFFLINE, map(online = false).headline)
        assertEquals(
            UpdateHeadline.NEEDS_ATTENTION,
            map(sync = SyncSnapshot(lastErrorCode = "unauthorized"), online = false).headline,
        )
    }

    @Test
    fun `a queued category is waiting without a spinner`() {
        val ui = map(category = CategoryQueueSnapshot(phase = CategoryQueuePhase.WAITING, pending = 6))

        assertEquals(UpdateHeadline.WAITING, ui.headline)
        assertEquals(6, ui.categoryPending)
        assertTrue(ui.hasBacklog)
        assertFalse(ui.isActive)
    }

    @Test
    fun `running category work is the active state when the mailbox has nothing queued`() {
        val ui = map(category = CategoryQueueSnapshot(phase = CategoryQueuePhase.RUNNING, pending = 3))

        assertEquals(UpdateHeadline.CATEGORIZING, ui.headline)
        assertTrue(ui.isActive)
    }

    @Test
    fun `a fresh install reports idle and no fake success`() {
        val ui = map()

        assertEquals(UpdateHeadline.IDLE, ui.headline)
        assertFalse(ui.hasEverSucceeded)
        assertEquals(null, ui.lastSuccessAt)
        assertFalse(ui.needsAttention)
    }

    @Test
    fun `relative ages stay coarse and never invent a countdown`() {
        assertEquals(RelativeTime.Never, RelativeTime.of(null, now))
        assertEquals(RelativeTime.JustNow, RelativeTime.of(now, now))
        assertEquals(RelativeTime.Minutes(12), RelativeTime.of(now - 12.minutes, now))
        assertEquals(RelativeTime.Hours(3), RelativeTime.of(now - 3.hours, now))
        assertEquals(RelativeTime.Days(2), RelativeTime.of(now - 2.days, now))
        assertTrue(RelativeTime.of(now - 30.days, now) is RelativeTime.On)
    }
}
