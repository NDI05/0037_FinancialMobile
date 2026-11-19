package com.pam.recap.data.seed

import com.pam.recap.core.money.Money
import com.pam.recap.core.security.FingerprintFactory
import com.pam.recap.core.security.FingerprintInput
import com.pam.recap.core.time.ClockProvider
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.core.util.IdGenerator
import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.CategoryOrigin
import com.pam.recap.domain.model.CoverageStatus
import com.pam.recap.domain.model.Direction
import com.pam.recap.domain.model.ImportJob
import com.pam.recap.domain.model.ImportState
import com.pam.recap.domain.model.ParseResult
import com.pam.recap.domain.model.ParseStatus
import com.pam.recap.domain.model.Transaction
import com.pam.recap.domain.model.TransactionSource
import com.pam.recap.domain.repository.DemoDataPort
import com.pam.recap.domain.repository.ImportRepository
import com.pam.recap.domain.repository.LivinNotificationParser
import com.pam.recap.domain.repository.SessionRepository
import com.pam.recap.domain.repository.TransactionRepository
import com.pam.recap.domain.usecase.ComputeMonthlySummaryUseCase
import com.pam.recap.domain.usecase.MerchantCategoryRules
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Instant

/**
 * A9 demo dataset for the reference month **February 2026, Asia/Jakarta**.
 *
 * Contents (18 IDR rows + 1 non-IDR row):
 * - the exact SRS B5 rows: salary 5.000.000 CREDIT income (manual), housing 1.000.000 DEBIT,
 *   own-account transfer 200.000 DEBIT, linked housing refund 100.000 CREDIT;
 * - one manual income row (freelance) besides the salary;
 * - twelve more email-derived rows covering every remaining category;
 * - one low-confidence uncategorized row;
 * - one non-IDR row that must be excluded from IDR totals;
 * - four January 2026 rows so the prior-month delta is meaningful (FR-31).
 *
 * Seed ids are deterministic, so re-seeding after a lost marker is a no-op on top of the
 * fingerprint unique index. The manual and non-fixture rows exist because no Livin template
 * produces them: they are the edge cases the UI must render.
 */
@Singleton
class DemoDataSeeder @Inject constructor(
    private val sessions: SessionRepository,
    private val transactions: TransactionRepository,
    private val imports: ImportRepository,
    private val parser: LivinNotificationParser,
    private val categoryRules: MerchantCategoryRules,
    private val fingerprints: FingerprintFactory,
    private val computeSummary: ComputeMonthlySummaryUseCase,
    private val clock: ClockProvider,
    private val idGenerator: IdGenerator,
) : DemoDataPort {

    override suspend fun seedIfNeeded(ownerId: String): Boolean {
        if (sessions.isDemoSeeded(ownerId)) return false
        if (transactions.monthTotals(ownerId, REFERENCE_MONTH).transactionCount > 0) {
            sessions.markDemoSeeded(ownerId)
            return false
        }

        val now = clock.now()
        val rows = buildList {
            addAll(fixtureRows(ownerId, now))
            addAll(manualRows(ownerId, now))
            add(uncategorizedRow(ownerId, now))
            add(nonIdrRow(ownerId, now))
            addAll(previousMonthRows(ownerId, now))
        }

        transactions.insertIgnoringDuplicates(ownerId, rows)

        // A COMPLETE job makes coverage read "Complete" for the reference month (FR-30/BR-05).
        imports.create(
            ImportJob(
                id = DEMO_JOB_ID,
                ownerId = ownerId,
                month = REFERENCE_MONTH,
                state = ImportState.COMPLETE,
                pagesProcessed = 1,
                pagesExpected = 1,
                matchedCount = LivinFixtures.demoSeedFixtures().size,
                importedCount = LivinFixtures.demoSeedFixtures().size,
                duplicateCount = 0,
                skippedCount = 0,
                unresolvedCount = 0,
                templateVersion = "v1",
                startedAt = now,
                finishedAt = now,
            ),
        )
        imports.create(
            ImportJob(
                id = PREVIOUS_JOB_ID,
                ownerId = ownerId,
                month = REFERENCE_MONTH.previous(),
                state = ImportState.COMPLETE,
                pagesProcessed = 1,
                pagesExpected = 1,
                matchedCount = 0,
                importedCount = 0,
                duplicateCount = 0,
                skippedCount = 0,
                unresolvedCount = 0,
                templateVersion = "v1",
                startedAt = now,
                finishedAt = now,
            ),
        )

        // Precompute the overview so the first frame has a summary to show (NFR-06).
        computeSummary(ownerId, REFERENCE_MONTH, persist = true)
        computeSummary(ownerId, REFERENCE_MONTH.previous(), persist = true)

        sessions.markDemoSeeded(ownerId)
        return true
    }

    /** Email-derived rows: parsed with the real parser so the stored provenance is truthful. */
    private fun fixtureRows(ownerId: String, now: Instant): List<Transaction> =
        LivinFixtures.demoSeedFixtures().mapNotNull { fixture ->
            val parsed = parser.parse(fixture.toRawMessage())
            if (parsed !is ParseResult.Parsed) return@mapNotNull null
            val assignment = categoryRules.classify(parsed.description, parsed.merchantLabel)
            val id = seedId(fixture.id)
            Transaction(
                id = id,
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
                fingerprint = fingerprintFor(ownerId, fixture.id, parsed.amount, parsed.eventAt, parsed.direction, parsed.merchantLabel),
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

    /** Two manual incomes: the SRS salary row plus one freelance payment (FR-26). */
    private fun manualRows(ownerId: String, now: Instant): List<Transaction> = listOf(
        manualRow(
            ownerId = ownerId,
            id = seedId("manual-salary"),
            direction = Direction.CREDIT,
            major = 5_000_000L,
            category = Category.INCOME,
            description = "Gaji bulanan",
            eventAtLocal = "2026-02-01 08:00:00",
            now = now,
        ),
        manualRow(
            ownerId = ownerId,
            id = seedId("manual-freelance"),
            direction = Direction.CREDIT,
            major = 750_000L,
            category = Category.INCOME,
            description = "Pembayaran proyek lepas",
            eventAtLocal = "2026-02-18 16:30:00",
            now = now,
        ),
    )

    private fun manualRow(
        ownerId: String,
        id: String,
        direction: Direction,
        major: Long,
        category: Category,
        description: String,
        eventAtLocal: String,
        now: Instant,
    ): Transaction {
        val eventAt = instantAt(eventAtLocal)
        val amount = Money.ofMajor(major)
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
            fingerprint = fingerprintFor(ownerId, id, amount, eventAt, direction, description),
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

    /** A row the rules could not classify: it must stay reviewable (FR-24). */
    private fun uncategorizedRow(ownerId: String, now: Instant): Transaction {
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
            fingerprint = fingerprintFor(ownerId, "seed-uncategorized", amount, eventAt, Direction.DEBIT, null),
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

    /** BR-04: a foreign-currency row that is persisted but excluded from IDR totals. */
    private fun nonIdrRow(ownerId: String, now: Instant): Transaction {
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
            fingerprint = fingerprintFor(ownerId, "seed-non-idr", amount, eventAt, Direction.DEBIT, "AWS Ireland"),
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

    /** January 2026 rows so FR-31 has a real denominator. */
    private fun previousMonthRows(ownerId: String, now: Instant): List<Transaction> = listOf(
        emailRow(ownerId, "jan-rent", "Sewa tempat tinggal", 1_000_000L, Category.HOUSING, "2026-01-05 09:00:00", now),
        emailRow(ownerId, "jan-food", "Belanja bulanan", 650_000L, Category.FOOD, "2026-01-12 18:20:00", now),
        emailRow(ownerId, "jan-transport", "Perjalanan kantor", 150_000L, Category.TRANSPORT, "2026-01-20 08:15:00", now),
        emailRow(ownerId, "jan-utilities", "Listrik dan air", 400_000L, Category.UTILITIES, "2026-01-25 10:40:00", now),
    )

    private fun emailRow(
        ownerId: String,
        key: String,
        description: String,
        major: Long,
        category: Category,
        eventAtLocal: String,
        now: Instant,
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
            fingerprint = fingerprintFor(ownerId, key, amount, eventAt, Direction.DEBIT, description),
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

    private fun seedId(key: String): String = "demo-$key"

    private fun instantAt(local: String): Instant = Instant.fromEpochMilliseconds(
        LocalDateTime.parse(local, LOCAL_FORMAT).atZone(jakarta).toInstant().toEpochMilli(),
    )

    companion object {
        /** The reference month the Lead reviews on the emulator. */
        val REFERENCE_MONTH: JakartaMonth = JakartaMonth(2026, 2)

        /** Coverage baseline for the reference and previous month. */
        val SEEDED_COVERAGE: CoverageStatus = CoverageStatus.COMPLETE

        const val DEMO_JOB_ID: String = "demo-job-2026-02"
        const val PREVIOUS_JOB_ID: String = "demo-job-2026-01"

        private const val HOUSING_FIXTURE_ID = "seed-apartemen"
        private const val REFUND_FIXTURE_ID = "seed-refund-apartemen"
        private const val CURRENCY_IDR = "IDR"
        private const val CURRENCY_USD = "USD"

        private val jakarta: ZoneId = ZoneId.of(JakartaMonth.ZONE.id)
        private val LOCAL_FORMAT = java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
    }
}
