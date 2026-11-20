package com.pam.recap.domain.repository

import com.pam.recap.domain.model.DemoPurge

/**
 * F1/F2: demo rows belong to the pure offline demo only. A server-backed account must never keep
 * bundled sample data, no matter whether a mailbox is connected yet.
 *
 * The implementation is owner-scoped and idempotent: it deletes only rows whose id carries the
 * demo prefix plus the demo import jobs and the summaries derived from them.
 */
interface DemoDataMaintenance {

    /** How many demo transactions [ownerId] still has; 0 means the purge row stays hidden. */
    suspend fun countDemoRows(ownerId: String): Int

    suspend fun hasDemoRows(ownerId: String): Boolean = countDemoRows(ownerId) > 0

    /** Removes the demo rows for [ownerId]; a second call removes nothing. */
    suspend fun purge(ownerId: String): DemoPurge
}
