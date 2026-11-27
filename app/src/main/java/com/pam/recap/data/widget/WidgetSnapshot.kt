package com.pam.recap.data.widget

/**
 * CR-010 / W-01..W-03: the minimal aggregate a home-screen widget may render.
 *
 * One bank per widget. Mandiri is the only bank with real data today; Jago/BRI stay preview-only
 * until the backend exposes a bank dimension. No amount, merchant, account number or email is
 * stored here.
 */
enum class WidgetBank(val id: String, val displayName: String) {
    MANDIRI("mandiri", "Mandiri"),
    JAGO("jago", "Jago"),
    BRI("bri", "BRI"),
    ;

    /** Only Mandiri has a confirmed data source in this release (CR-010 W-01). */
    val isAvailable: Boolean get() = this == MANDIRI

    companion object {
        val available: List<WidgetBank> get() = entries.filter { it.isAvailable }

        fun fromId(value: String?): WidgetBank =
            entries.firstOrNull { it.id == value } ?: MANDIRI
    }
}

/** Snapshot lifecycle, kept explicit so a stale count is never presented as current. */
enum class WidgetSnapshotStatus {
    LOADING,
    AVAILABLE,
    EMPTY,
    OFFLINE,
    ERROR,
}

/**
 * Owner-scoped, month-scoped widget aggregate in private storage.
 *
 * [confirmed] is only true after the server (or the local database) actually answered; that is what
 * separates "0 transaksi tercatat" from "Ringkasan belum tersedia".
 */
data class WidgetSnapshot(
    val ownerId: String,
    val bank: WidgetBank,
    val monthId: String,
    val transactionCount: Int?,
    val status: WidgetSnapshotStatus,
    val updatedAtEpochMs: Long?,
    val confirmed: Boolean,
) {
    val hasCount: Boolean get() = transactionCount != null

    fun asLoading(): WidgetSnapshot = copy(
        transactionCount = null,
        status = WidgetSnapshotStatus.LOADING,
        confirmed = false,
    )
}
