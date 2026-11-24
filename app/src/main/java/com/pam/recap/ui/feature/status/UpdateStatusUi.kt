package com.pam.recap.ui.feature.status

import com.pam.recap.core.result.AppError
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.domain.model.AutoSyncConfig
import com.pam.recap.domain.model.CategoryQueuePhase
import com.pam.recap.domain.model.CategoryQueueSnapshot
import com.pam.recap.domain.model.ImportJob
import com.pam.recap.domain.model.SyncPhase
import com.pam.recap.domain.model.SyncSnapshot
import com.pam.recap.domain.usecase.SyncSessionState
import kotlin.time.Instant

/**
 * One honest sentence for the top bar. The order of the enum is the plan's severity order:
 * attention > active work > queued > idle. Anything not derivable from a durable snapshot is not
 * represented here — no timer, no optimistic "done".
 */
enum class UpdateHeadline {
    NEEDS_ATTENTION,
    CHECKING,
    FETCHING,
    SAVING,
    CATEGORIZING,
    OFFLINE,
    WAITING,
    SUCCEEDED,
    IDLE,
    ;

    val isActive: Boolean get() = this == CHECKING || this == FETCHING || this == SAVING || this == CATEGORIZING
}

/** A single honest line for the "Perlu perhatian" section. Codes are mapped to copy, never shown raw. */
data class AttentionItem(val code: String, val scope: Scope) {
    enum class Scope { TRANSACTIONS, CATEGORIES }
}

/**
 * Presentation model of the update status. Read-only: building it never triggers a sync or a
 * provider call, which is the plan's hard requirement for opening the panel.
 */
data class UpdateStatusUi(
    val headline: UpdateHeadline = UpdateHeadline.IDLE,
    val online: Boolean = true,
    val lastAttemptAt: Instant? = null,
    val lastSuccessAt: Instant? = null,
    /** True when rows are committed locally but the server has not acknowledged them yet. */
    val pendingUpload: Boolean = false,
    /** The last run stopped before the end of the range; "Terbaru" would be a lie. */
    val partial: Boolean = false,
    val storageFailure: Boolean = false,
    val newTransactionsLastRun: Int = 0,
    val savedCount: Int = 0,
    val duplicateCount: Int = 0,
    val skippedCount: Int = 0,
    val unresolvedCount: Int = 0,
    val categoryPhase: CategoryQueuePhase = CategoryQueuePhase.IDLE,
    val categoryPending: Int = 0,
    val categoryNextDueAt: Instant? = null,
    val categoryLastDrained: Int = 0,
    val attention: List<AttentionItem> = emptyList(),
    /** Calendar day of the last successful check, used for "Terakhir berhasil …" wording. */
    val hasEverSucceeded: Boolean = false,
    /** Period the manual "Sinkronkan sekarang" trigger would target, and that it last targeted. */
    val month: JakartaMonth? = null,
    val syncRunning: Boolean = false,
    /** Monotonic attempt token; animations key on this instead of recomposition. */
    val syncAttempt: Long = 0,
    val syncError: AppError? = null,
    /** Another pipeline run already owns the single-flight gate. */
    val syncConflict: Boolean = false,
    val pagesProcessed: Int = 0,
    val pagesExpected: Int? = null,
    /** Last local import job, the source of the per-run matched/saved/… metrics. */
    val lastJob: ImportJob? = null,
) {
    val isActive: Boolean get() = headline.isActive
    val hasBacklog: Boolean get() = categoryPending > 0
    val needsAttention: Boolean get() = attention.isNotEmpty()

    /**
     * Rows the user still has to look at, as opposed to a failed run. Only review rows make the
     * short status read "Perlu tinjauan"; a revoked permission or a broken cursor is a different
     * problem and must not be disguised as a review task.
     */
    val needsReview: Boolean
        get() = attention.any { it.code in REVIEW_CODES }

    /** Any per-run count worth a line in the panel; zero everywhere is a clean run. */
    val hasRunCounts: Boolean get() = savedCount > 0 || duplicateCount > 0 || skippedCount > 0 || unresolvedCount > 0
    val hasDefiniteProgress: Boolean get() = (pagesExpected ?: 0) > 0
    /** True whenever the trigger should be disabled: this attempt or a concurrent pipeline run. */
    val syncBusy: Boolean get() = syncRunning || syncConflict || isActive

    private companion object {
        /** Codes that mean "a human must decide", not "something broke". */
        val REVIEW_CODES = setOf("unresolved_items")
    }
}

/**
 * Pure mapping from the two durable snapshots to the panel model.
 *
 * Kept free of Android types so the honest-status rules are unit-testable: last attempt and last
 * successful sync are different facts, an upload that is not acknowledged is never a success, and a
 * category problem is never reported as a Gmail problem.
 */
object UpdateStatusMapper {

    fun map(
        sync: SyncSnapshot,
        category: CategoryQueueSnapshot,
        online: Boolean,
        config: AutoSyncConfig = AutoSyncConfig(),
        session: SyncSessionState = SyncSessionState(),
    ): UpdateStatusUi {
        val attention = buildList {
            sync.lastErrorCode?.let { add(AttentionItem(it, AttentionItem.Scope.TRANSACTIONS)) }
            // These are separate facts from lastErrorCode: a partial run or an unwritable cursor must
            // stay visible even after a later attempt cleared the error code.
            if (sync.storageFailure) add(AttentionItem("storage_unavailable", AttentionItem.Scope.TRANSACTIONS))
            if (sync.partial) add(AttentionItem("partial_import", AttentionItem.Scope.TRANSACTIONS))
            if (sync.unresolvedCount > 0) add(AttentionItem("unresolved_items", AttentionItem.Scope.TRANSACTIONS))
            category.errorCode?.let { add(AttentionItem(it, AttentionItem.Scope.CATEGORIES)) }
        }.distinctBy { it.scope to it.code }
        val headline = headline(sync, category, online, attention, session)
        return UpdateStatusUi(
            headline = headline,
            online = online,
            lastAttemptAt = sync.lastAttemptAt,
            lastSuccessAt = sync.lastSuccessAt,
            pendingUpload = sync.pendingUpload,
            partial = sync.partial,
            storageFailure = sync.storageFailure,
            newTransactionsLastRun = sync.newTransactionsLastRun,
            savedCount = sync.savedCount,
            duplicateCount = sync.duplicateCount,
            skippedCount = sync.skippedCount,
            unresolvedCount = sync.unresolvedCount,
            categoryPhase = category.phase,
            categoryPending = category.pending,
            categoryNextDueAt = category.nextDueAt,
            categoryLastDrained = category.lastDrained,
            attention = attention,
            hasEverSucceeded = sync.lastSuccessAt != null,
            month = session.month,
            syncRunning = session.running,
            syncAttempt = session.attempt,
            syncError = session.error,
            syncConflict = session.conflict,
            pagesProcessed = session.pagesProcessed,
            pagesExpected = session.pagesExpected,
            lastJob = session.lastJob,
        )
    }

    private fun headline(
        sync: SyncSnapshot,
        category: CategoryQueueSnapshot,
        online: Boolean,
        attention: List<AttentionItem>,
        session: SyncSessionState,
    ): UpdateHeadline = when {
        attention.isNotEmpty() -> UpdateHeadline.NEEDS_ATTENTION
        !online -> UpdateHeadline.OFFLINE
        sync.pendingUpload -> UpdateHeadline.SAVING
        // A real import run is "taking history", not merely "checking".
        sync.phase == SyncPhase.SYNCING -> UpdateHeadline.FETCHING
        // The user's tap was accepted but the pipeline has not reported its fetch phase yet.
        session.running -> UpdateHeadline.CHECKING
        category.phase == CategoryQueuePhase.RUNNING -> UpdateHeadline.CATEGORIZING
        // A queued transaction is "waiting", never "done": the row exists but has no category yet.
        category.pending > 0 -> UpdateHeadline.WAITING
        sync.lastSuccessAt != null -> UpdateHeadline.SUCCEEDED
        else -> UpdateHeadline.IDLE
    }
}
