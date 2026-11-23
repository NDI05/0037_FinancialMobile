package com.pam.recap.data.local

/**
 * Raw SQL projections. DAOs speak primitives (`Long`, `String`) and these rows are converted to
 * domain models in `data/repository`, so Room never has to infer a converter for a computed
 * aggregate column.
 */
data class MonthTotalsRow(
    val grossInflowMinor: Long,
    val grossOutflowMinor: Long,
    val creditCount: Int,
    val debitCount: Int,
    val transactionCount: Int,
    val nonIdrCount: Int,
    val uncategorizedCount: Int,
    val manualCount: Int,
    val latestEventAt: Long?,
    val latestUpdatedAt: Long?,
)

data class ReasonCountRow(
    val reasonCode: String,
    val count: Int,
)
