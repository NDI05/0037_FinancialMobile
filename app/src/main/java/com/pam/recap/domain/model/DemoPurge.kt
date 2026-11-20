package com.pam.recap.domain.model

/**
 * FR-60/F1: what a demo purge actually removed, so the UI can report it truthfully.
 *
 * Counts are per owner and only cover rows that carry the demo seed ids; imported (EMAIL) and
 * manual rows are never part of the set.
 *
 * One field per table the purger touches — `transactions`, `import_jobs`, `monthly_summaries`
 * (whose category split cascades) — so [total] can never drift from what was actually deleted.
 * When a future bundle touches another table, it must add a field here.
 */
data class DemoPurge(
    val transactions: Int = 0,
    val importJobs: Int = 0,
    val summaries: Int = 0,
) {
    val total: Int get() = transactions + importJobs + summaries

    val isEmpty: Boolean get() = total == 0
}
