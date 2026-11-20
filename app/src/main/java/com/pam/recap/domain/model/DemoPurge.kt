package com.pam.recap.domain.model

/**
 * FR-60/F1: what a demo purge actually removed, so the UI can report it truthfully.
 *
 * Counts are per owner and only cover rows that carry the demo seed ids; imported (EMAIL) and
 * manual rows are never part of the set.
 */
data class DemoPurge(
    val transactions: Int = 0,
    val importJobs: Int = 0,
    val summaries: Int = 0,
) {
    val total: Int get() = transactions + importJobs + summaries

    val isEmpty: Boolean get() = total == 0
}
