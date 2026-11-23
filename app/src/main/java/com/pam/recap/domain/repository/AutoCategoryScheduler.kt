package com.pam.recap.domain.repository

/**
 * Schedules the automatic category drain. The 2-hour window itself is enforced by the server; this
 * port only decides when the device asks, so a wake-up can never become a provider call.
 */
interface AutoCategoryScheduler {

    fun ensureScheduled(ownerId: String)

    fun cancelAll()
}
