package com.pam.recap.domain.repository

import com.pam.recap.domain.model.SyncCursor

/**
 * Durable, owner-scoped incremental progress.
 *
 * Retained across restarts so a run never re-downloads what it already committed. Purged on
 * logout/owner switch/disconnect and on account deletion, because the cursor only means anything
 * for the mailbox and owner that produced it.
 */
interface SyncCursorStore {
    fun read(ownerId: String): SyncCursor?

    /** False when the platform store refused the write; the caller must not claim progress. */
    fun save(cursor: SyncCursor): Boolean

    fun clear(ownerId: String)
}
