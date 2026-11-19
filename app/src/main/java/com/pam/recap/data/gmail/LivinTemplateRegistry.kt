package com.pam.recap.data.gmail

import com.pam.recap.domain.model.Direction
import com.pam.recap.domain.repository.LivinQuery
import kotlin.time.Instant

/**
 * Which body fields a template must expose (FR-12/FR-13). Kept as an enum so a template can
 * declare its contract declaratively instead of the parser hardcoding per-template logic.
 */
enum class LivinField {
    AMOUNT,
    EVENT_TIME,
    DIRECTION,
    RECIPIENT,
    STATUS,
}

/**
 * Localized field labels observed in the Livin notification body. Data-driven (OI-02): a new
 * template version can rename a label without touching the parser.
 */
data class LivinFieldLabels(
    val amount: List<String> = listOf("nominal", "jumlah"),
    val eventTime: List<String> = listOf("tanggal", "waktu transaksi"),
    val direction: List<String> = listOf("jenis transaksi"),
    val recipient: List<String> = listOf("merchant", "penerima", "nama merchant"),
    val acquirer: List<String> = listOf("acquirer", "penyelenggara"),
    val status: List<String> = listOf("status"),
)

/**
 * One allow-listed Livin notification template (FR-11/FR-14, OI-02).
 *
 * [senderAddresses] are mailbox addresses, never display names: a display name alone is
 * explicitly insufficient (FR-11). [subjectPatterns] are literal, case-insensitive fragments.
 */
data class LivinTemplate(
    val version: String,
    val id: String,
    val senderAddresses: List<String>,
    val subjectPatterns: List<String>,
    val direction: Direction,
    /** Normalized description stored on the transaction (email content, not UI copy). */
    val description: String,
    val requiredFields: Set<LivinField>,
    val successMarkers: List<String>,
    val failureMarkers: List<String>,
    val labels: LivinFieldLabels = LivinFieldLabels(),
    /** Base parser confidence for a complete match; anomalies lower it deterministically. */
    val baseConfidence: Float = 1.0f,
)

/**
 * Versioned, data-driven allow-list of Livin templates (FR-11, FR-14, OI-02).
 *
 * The registry is the single place where a sender, subject fragment, status marker or field
 * label appears. The parser and the query builder read from it, so supporting a new template
 * version is a data change, not a code change.
 */
class LivinTemplateRegistry(
    private val templates: List<LivinTemplate> = defaultTemplates(),
) {

    init {
        require(templates.isNotEmpty()) { "at least one template is required" }
        require(templates.distinctBy { it.id }.size == templates.size) { "duplicate template id" }
    }

    /** Matches a sender + subject pair; returns null when nothing on the allow-list applies. */
    fun match(sender: String?, subject: String?): LivinTemplate? {
        val address = extractAddress(sender) ?: return null
        val normalizedSubject = subject?.lowercase() ?: return null
        return templates.firstOrNull { template ->
            template.senderAddresses.any { it.equals(address, ignoreCase = true) } &&
                template.subjectPatterns.any { normalizedSubject.contains(it.lowercase()) }
        }
    }

    fun byId(id: String): LivinTemplate? = templates.firstOrNull { it.id == id }

    fun byVersion(version: String): LivinTemplate? = templates.firstOrNull { it.version == version }

    fun all(): List<LivinTemplate> = templates

    /** Union of allow-listed sender addresses; used to build the Gmail query (FR-11). */
    val senderAddresses: List<String> = templates.flatMap { it.senderAddresses }.distinct()

    /** Union of allow-listed subject fragments; used to build the Gmail query (FR-11). */
    val subjectPatterns: List<String> = templates.flatMap { it.subjectPatterns }.distinct()

    /** Newest version, used when a caller does not pin one. */
    val currentVersion: String = templates.last().version

    companion object {
        const val VERSION_V1: String = "v1"

        /**
         * `From:` header shapes are `"Display Name <address>"` or a bare address. A header with
         * only a display name yields no address and therefore never matches (FR-11).
         */
        fun extractAddress(sender: String?): String? {
            val raw = sender?.trim().orEmpty()
            if (raw.isEmpty()) return null
            val bracketStart = raw.lastIndexOf('<')
            if (bracketStart >= 0) {
                val bracketEnd = raw.indexOf('>', bracketStart)
                if (bracketEnd < 0) return null
                return raw.substring(bracketStart + 1, bracketEnd).trim().lowercase().ifEmpty { null }
            }
            // No angle brackets: accept only a value that actually looks like an address.
            return raw.lowercase().takeIf { it.contains('@') && !it.contains(' ') }
        }

        /**
         * Version 1 of the observed templates. QRIS payment/refund, outgoing transfer and bill
         * payment are allow-listed. An **incoming** transfer template is deliberately absent
         * (OI-03), so arriving money stays manual-entry only, exactly as the SRS mandates.
         */
        fun defaultTemplates(): List<LivinTemplate> = listOf(
            LivinTemplate(
                version = VERSION_V1,
                id = "qris-payment",
                senderAddresses = listOf("notifikasi@bankmandiri.co.id"),
                subjectPatterns = listOf("transaksi qris", "pembayaran qris"),
                direction = Direction.DEBIT,
                description = "QRIS",
                requiredFields = setOf(
                    LivinField.AMOUNT,
                    LivinField.EVENT_TIME,
                    LivinField.DIRECTION,
                    LivinField.RECIPIENT,
                    LivinField.STATUS,
                ),
                successMarkers = listOf("berhasil"),
                failureMarkers = listOf("gagal", "tidak berhasil", "dibatalkan", "ditolak"),
            ),
            LivinTemplate(
                version = VERSION_V1,
                id = "qris-refund",
                senderAddresses = listOf("notifikasi@bankmandiri.co.id"),
                subjectPatterns = listOf("pengembalian dana qris", "refund qris"),
                direction = Direction.CREDIT,
                description = "Pengembalian dana QRIS",
                requiredFields = setOf(
                    LivinField.AMOUNT,
                    LivinField.EVENT_TIME,
                    LivinField.DIRECTION,
                    LivinField.RECIPIENT,
                    LivinField.STATUS,
                ),
                successMarkers = listOf("berhasil"),
                failureMarkers = listOf("gagal", "ditolak"),
            ),
            LivinTemplate(
                version = VERSION_V1,
                id = "transfer-out",
                senderAddresses = listOf("notifikasi@bankmandiri.co.id"),
                subjectPatterns = listOf("transfer antar rekening berhasil", "transfer berhasil"),
                direction = Direction.DEBIT,
                description = "Transfer",
                requiredFields = setOf(
                    LivinField.AMOUNT,
                    LivinField.EVENT_TIME,
                    LivinField.DIRECTION,
                    LivinField.RECIPIENT,
                    LivinField.STATUS,
                ),
                successMarkers = listOf("berhasil"),
                failureMarkers = listOf("gagal", "tidak berhasil", "dibatalkan", "ditolak"),
            ),
            LivinTemplate(
                version = VERSION_V1,
                id = "bill-payment",
                senderAddresses = listOf("notifikasi@bankmandiri.co.id"),
                subjectPatterns = listOf("pembayaran tagihan", "pembayaran berhasil"),
                direction = Direction.DEBIT,
                description = "Pembayaran",
                requiredFields = setOf(
                    LivinField.AMOUNT,
                    LivinField.EVENT_TIME,
                    LivinField.DIRECTION,
                    LivinField.RECIPIENT,
                    LivinField.STATUS,
                ),
                successMarkers = listOf("berhasil"),
                failureMarkers = listOf("gagal", "tidak berhasil", "dibatalkan", "ditolak"),
            ),
        )
    }
}

/**
 * Builds the allow-listed query for one month (FR-11, BR-01). The Gmail query may only ever be
 * derived from the registry, so marketing mail, merchant payouts and other senders cannot be
 * fetched in the first place.
 */
class LivinQueryBuilder(private val registry: LivinTemplateRegistry) {

    fun build(
        fromInclusive: Instant,
        toExclusive: Instant,
        templateVersion: String? = null,
    ): LivinQuery {
        val templates = templateVersion?.let { version ->
            registry.all().filter { it.version == version }
        } ?: registry.all()

        return LivinQuery(
            fromInclusive = fromInclusive,
            toExclusive = toExclusive,
            senderPatterns = templates.flatMap { it.senderAddresses }.distinct(),
            subjectPatterns = templates.flatMap { it.subjectPatterns }.distinct(),
            templateVersion = templateVersion ?: registry.currentVersion,
        )
    }
}
