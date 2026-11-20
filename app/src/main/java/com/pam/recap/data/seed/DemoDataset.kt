package com.pam.recap.data.seed

import com.pam.recap.core.money.Money
import com.pam.recap.core.security.FingerprintFactory
import com.pam.recap.core.security.FingerprintInput
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.CategoryOrigin
import com.pam.recap.domain.model.Direction
import com.pam.recap.domain.model.ParseResult
import com.pam.recap.domain.model.ParseStatus
import com.pam.recap.domain.model.Transaction
import com.pam.recap.domain.model.TransactionSource
import com.pam.recap.domain.repository.LivinNotificationParser
import com.pam.recap.domain.usecase.MerchantCategoryRules
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.time.Instant

/**
 * The A9 demo dataset as *data*: a pure builder so the numbers can be asserted on the JVM
 * without Room, Hilt or an emulator.
 *
 * Reference month: **February 2026, Asia/Jakarta** — 14 email-derived rows (the seeded fixtures),
 * 2 manual incomes, 1 low-confidence uncategorized row, 1 non-IDR row and 4 January 2026 rows so
 * FR-31 has a real denominator.
 */
object DemoDataset {

    val REFERENCE_MONTH: JakartaMonth = JakartaMonth(2026, 2)

    /** Every seeded row id starts with this prefix; the purge deletes by it (F2). */
    const val ID_PREFIX: String = "demo-"

    const val DEMO_JOB_ID: String = "demo-job-2026-02"
    const val PREVIOUS_JOB_ID: String = "demo-job-2026-01"

    /** The seeded jobs, in one place so the seeder and the purger cannot drift apart. */
    val DEMO_JOB_IDS: List<String> = listOf(DEMO_JOB_ID, PREVIOUS_JOB_ID)

    /** The months the seeder writes summaries for. */
    val DEMO_MONTHS: List<JakartaMonth> = listOf(REFERENCE_MONTH, REFERENCE_MONTH.previous())

    private const val HOUSING_FIXTURE_ID = "seed-apartemen"
    private const val REFUND_FIXTURE_ID = "seed-refund-apartemen"
    private const val CURRENCY_IDR = "IDR"
    private const val CURRENCY_USD = "USD"

    private val jakarta: ZoneId = ZoneId.of(JakartaMonth.ZONE.id)
    private val LOCAL_FORMAT = java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")

    /** Every seeded row, oldest concern first; insertion order does not matter. */
    fun build(
        ownerId: String,
        now: Instant,
        parser: LivinNotificationParser,
        rules: MerchantCategoryRules,
        fingerprints: FingerprintFactory,
    ): List<Transaction> = buildList {
        addAll(fixtureRows(ownerId, now, parser, rules, fingerprints))
        addAll(manualRows(ownerId, now, fingerprints))
        add(uncategorizedRow(ownerId, now, fingerprints))
        add(nonIdrRow(ownerId, now, fingerprints))
        addAll(previousMonthRows(ownerId, now, fingerprints))
    }

    fun seedId(key: String): String = "$ID_PREFIX$key"

    fun instantAt(local: String): Instant = Instant.fromEpochMilliseconds(
        LocalDateTime.parse(local, LOCAL_FORMAT).atZone(jakarta).toInstant().toEpochMilli(),
    )

    private fun fixtureRows(
        ownerId: String,
        now: Instant,
        parser: LivinNotificationParser,
        rules: MerchantCategoryRules,
        fingerprints: FingerprintFactory,
    ): List<Transaction> = LivinFixtures.demoSeedFixtures().mapNotNull { fixture ->
        val parsed = parser.parse(fixture.toRawMessage())
        if (parsed !is ParseResult.Parsed) return@mapNotNull null
        val assignment = rules.classify(parsed.description, parsed.merchantLabel)
        Transaction(
            id = seedId(fixture.id),
            ownerId = ownerId,
            direction = parsed.direction,
            source = TransactionSource.EMAIL,
            amount = parsed.amount,
            currency = parsed.currency,
            eventAt = parsed.eventAt,
            receivedAt = parsed.receivedAt,
            description = parsed.description,
            merchantLabel = parsed.merchantLabel,
            acquirer = parsed.acquirer,
            status = ParseStatus.POSTED,
            fingerprint = fingerprintFor(ownerId, fixture.id, parsed.amount, parsed.eventAt, parsed.direction, parsed.merchantLabel, fingerprints),
            parserVersion = parsed.parserVersion,
            parserConfidence = parsed.confidence,
            category = assignment.category,
            categoryOrigin = CategoryOrigin.RULE,
            categoryConfidence = assignment.confidence,
            categoryUpdatedAt = null,
            isInternalTransfer = assignment.category.isTransfer,
            linkedRefundOf = if (fixture.id == REFUND_FIXTURE_ID) seedId(HOUSING_FIXTURE_ID) else null,
            note = null,
            importJobId = DEMO_JOB_ID,
            revision = 0,
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun manualRows(ownerId: String, now: Instant, fingerprints: FingerprintFactory): List<Transaction> =
        listOf(
            manualRow(ownerId, "manual-salary", Direction.CREDIT, 5_000_000L, Category.INCOME, "Gaji bulanan", "2026-02-01 08:00:00", now, fingerprints),
            manualRow(ownerId, "manual-freelance", Direction.CREDIT, 750_000L, Category.INCOME, "Pembayaran proyek lepas", "2026-02-18 16:30:00", now, fingerprints),
        )

    private fun manualRow(
        ownerId: String,
        key: String,
        direction: Direction,
        major: Long,
        category: Category,
        description: String,
        eventAtLocal: String,
        now: Instant,
        fingerprints: FingerprintFactory,
    ): Transaction {
        val eventAt = instantAt(eventAtLocal)
        val amount = Money.ofMajor(major)
        val id = seedId(key)
        return Transaction(
            id = id,
            ownerId = ownerId,
            direction = direction,
            source = TransactionSource.MANUAL,
            amount = amount,
            currency = CURRENCY_IDR,
            eventAt = eventAt,
            receivedAt = null,
            description = description,
            merchantLabel = null,
            acquirer = null,
            status = ParseStatus.POSTED,
            fingerprint = fingerprintFor(ownerId, key, amount, eventAt, direction, description, fingerprints),
            parserVersion = "manual-v1",
            parserConfidence = 1f,
            category = category,
            categoryOrigin = CategoryOrigin.USER,
            categoryConfidence = 1f,
            categoryUpdatedAt = now,
            isInternalTransfer = false,
            linkedRefundOf = null,
            note = null,
            importJobId = null,
            revision = 0,
            createdAt = now,
            updatedAt = now,
        )
    }

    /**
     * A row the rules could not classify (FR-24): low parser and category confidence keep it
     * reviewable instead of pretending the category is known.
     */
    private fun uncategorizedRow(ownerId: String, now: Instant, fingerprints: FingerprintFactory): Transaction {
        val eventAt = instantAt("2026-02-19 11:10:00")
        val amount = Money.ofMajor(175_000L)
        return Transaction(
            id = seedId("uncategorized"),
            ownerId = ownerId,
            direction = Direction.DEBIT,
            source = TransactionSource.EMAIL,
            amount = amount,
            currency = CURRENCY_IDR,
            eventAt = eventAt,
            receivedAt = eventAt,
            description = "Transaksi tanpa keterangan",
            merchantLabel = null,
            acquirer = null,
            status = ParseStatus.POSTED,
            fingerprint = fingerprintFor(ownerId, "seed-uncategorized", amount, eventAt, Direction.DEBIT, null, fingerprints),
            parserVersion = "livin-qris-payment/v1",
            parserConfidence = 0.55f,
            category = Category.UNCATEGORIZED,
            categoryOrigin = CategoryOrigin.RULE,
            categoryConfidence = 0.30f,
            categoryUpdatedAt = null,
            isInternalTransfer = false,
            linkedRefundOf = null,
            note = null,
            importJobId = DEMO_JOB_ID,
            revision = 0,
            createdAt = now,
            updatedAt = now,
        )
    }

    /** BR-04: persisted, flagged, and excluded from every IDR total. */
    private fun nonIdrRow(ownerId: String, now: Instant, fingerprints: FingerprintFactory): Transaction {
        val eventAt = instantAt("2026-02-21 07:45:00")
        val amount = Money.ofMajor(12L)
        return Transaction(
            id = seedId("non-idr"),
            ownerId = ownerId,
            direction = Direction.DEBIT,
            source = TransactionSource.EMAIL,
            amount = amount,
            currency = CURRENCY_USD,
            eventAt = eventAt,
            receivedAt = eventAt,
            description = "Langganan layanan cloud",
            merchantLabel = "AWS Ireland",
            acquirer = "VISA",
            status = ParseStatus.POSTED,
            fingerprint = fingerprintFor(ownerId, "seed-non-idr", amount, eventAt, Direction.DEBIT, "AWS Ireland", fingerprints),
            parserVersion = "livin-bill-payment/v1",
            parserConfidence = 0.7f,
            category = Category.UTILITIES,
            categoryOrigin = CategoryOrigin.RULE,
            categoryConfidence = 0.7f,
            categoryUpdatedAt = null,
            isInternalTransfer = false,
            linkedRefundOf = null,
            note = null,
            importJobId = DEMO_JOB_ID,
            revision = 0,
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun previousMonthRows(ownerId: String, now: Instant, fingerprints: FingerprintFactory): List<Transaction> =
        listOf(
            emailRow(ownerId, "jan-rent", "Sewa tempat tinggal", 1_000_000L, Category.HOUSING, "2026-01-05 09:00:00", now, fingerprints),
            emailRow(ownerId, "jan-food", "Belanja bulanan", 650_000L, Category.FOOD, "2026-01-12 18:20:00", now, fingerprints),
            emailRow(ownerId, "jan-transport", "Perjalanan kantor", 150_000L, Category.TRANSPORT, "2026-01-20 08:15:00", now, fingerprints),
            emailRow(ownerId, "jan-utilities", "Listrik dan air", 400_000L, Category.UTILITIES, "2026-01-25 10:40:00", now, fingerprints),
        )

    private fun emailRow(
        ownerId: String,
        key: String,
        description: String,
        major: Long,
        category: Category,
        eventAtLocal: String,
        now: Instant,
        fingerprints: FingerprintFactory,
    ): Transaction {
        val eventAt = instantAt(eventAtLocal)
        val amount = Money.ofMajor(major)
        return Transaction(
            id = seedId(key),
            ownerId = ownerId,
            direction = Direction.DEBIT,
            source = TransactionSource.EMAIL,
            amount = amount,
            currency = CURRENCY_IDR,
            eventAt = eventAt,
            receivedAt = eventAt,
            description = description,
            merchantLabel = null,
            acquirer = null,
            status = ParseStatus.POSTED,
            fingerprint = fingerprintFor(ownerId, key, amount, eventAt, Direction.DEBIT, description, fingerprints),
            parserVersion = "livin-qris-payment/v1",
            parserConfidence = 1f,
            category = category,
            categoryOrigin = CategoryOrigin.RULE,
            categoryConfidence = 0.9f,
            categoryUpdatedAt = null,
            isInternalTransfer = false,
            linkedRefundOf = null,
            note = null,
            importJobId = PREVIOUS_JOB_ID,
            revision = 0,
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun fingerprintFor(
        ownerId: String,
        messageKey: String,
        amount: Money,
        eventAt: Instant,
        direction: Direction,
        recipient: String?,
        fingerprints: FingerprintFactory,
    ): String = fingerprints.fingerprint(
        ownerId = ownerId,
        input = FingerprintInput(
            messageId = "seed:$messageKey",
            amountMinor = amount.minor,
            eventAtEpochMilliseconds = eventAt.toEpochMilliseconds(),
            directionCode = direction.name,
            recipientLabel = recipient,
        ),
    )
}
