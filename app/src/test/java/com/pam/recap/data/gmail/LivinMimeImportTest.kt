package com.pam.recap.data.gmail

import com.pam.recap.data.seed.LivinFixtures

import com.pam.recap.core.money.Money
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.security.FingerprintFactory
import com.pam.recap.core.time.ClockProvider
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.core.util.IdGenerator
import com.pam.recap.domain.model.CoverageStatus
import com.pam.recap.domain.model.Direction
import com.pam.recap.domain.model.GmailConnectionState
import com.pam.recap.domain.model.ImportState
import com.pam.recap.domain.model.ParseResult
import com.pam.recap.domain.model.RedactionReason
import com.pam.recap.domain.repository.GmailMessageRef
import com.pam.recap.domain.repository.GmailPage
import com.pam.recap.domain.repository.GmailSource
import com.pam.recap.domain.repository.LivinQuery
import com.pam.recap.domain.repository.RawMessage
import com.pam.recap.domain.usecase.ComputeMonthlySummaryUseCase
import com.pam.recap.domain.usecase.ImportMonthUseCase
import com.pam.recap.domain.usecase.MerchantCategoryRules
import com.pam.recap.testing.FakeAnalysisRepository
import com.pam.recap.testing.FakeDeletionRepository
import com.pam.recap.testing.FakeGmailConnectionRepository
import com.pam.recap.testing.FakeImportRepository
import com.pam.recap.testing.FakeSummaryRepository
import com.pam.recap.testing.FakeTransactionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.Properties
import javax.mail.Session
import javax.mail.internet.MimeMessage
import kotlin.time.Instant

/**
 * The import path a real notification takes: raw MIME (untyped root, as the IMAP provider hands it
 * back) → HTML → text → allow-listed template → parser → stored row → monthly total.
 *
 * The fixture is synthetic but mimics the measured Livin layout: the label has no colon and the
 * value sits in the **next table cell**, which after tag stripping is the same rendered line. A
 * parser-only fixture could not catch that, because it skips the MIME/HTML step that joins them.
 */
class LivinMimeImportTest {

    private val session = Session.getInstance(Properties())
    private val month = JakartaMonth(2026, 2)
    private val now = Instant.parse("2026-03-02T03:00:00Z")
    private val clock = object : ClockProvider {
        override fun now(): Instant = now
    }
    private val registry = LivinTemplateRegistry()
    private val parser = LivinQrisParser(registry)

    private var nextId = 0

    private fun mime(
        entity: String,
        from: String = "Livin by Mandiri <noreply.livin@bankmandiri.co.id>",
        subject: String = "Pembayaran Berhasil!",
    ): MimeMessage {
        // Built line by line on purpose: interpolating an indented multi-line entity into a
        // trimIndent() template would disable dedenting and indent the headers, which javax.mail
        // then treats as continuation lines (the fixture silently loses its Subject).
        val raw = buildString {
            appendLine("From: $from")
            appendLine("Subject: $subject")
            appendLine("Date: Thu, 19 Feb 2026 11:10:00 +0700")
            appendLine("MIME-Version: 1.0")
            appendLine("Content-Type: application/octet-stream")
            appendLine()
            appendLine(entity)
        }
        return MimeMessage(session, ByteArrayInputStream(raw.encodeToByteArray()))
    }

    /**
     * The device shape: the root part is untyped and its bytes are themselves a MIME entity whose
     * single part is an HTML table. Each `<tr>` is on one source line (minified email HTML), so
     * after tag stripping a label cell and its value cell share one rendered line unless the cell
     * boundary is preserved.
     */
    private fun livinTableMime(
        amount: String = "Rp175.000",
        date: String = "19 Februari 2026 11:10:00",
        merchant: String = "Indomaret",
    ): MimeMessage = mime(
        """
        Content-Type: text/html; charset=UTF-8

        <html><body>
        <p>Pembayaran Berhasil!</p>
        <table>
        <tr><td>Nominal Pembayaran</td><td>$amount</td></tr>
        <tr><td>Tanggal Transaksi</td><td>$date</td></tr>
        <tr><td>Merchant</td><td>$merchant</td></tr>
        </table>
        </body></html>
        """.trimIndent(),
    )

    private fun livinSplitDateTimeMime(): MimeMessage = mime(
        """
        Content-Type: text/html; charset=UTF-8

        <html><body>
        <p>Pembayaran Berhasil!</p>
        <table>
        <tr><td>Nominal Pembayaran</td><td>Rp175.000</td></tr>
        <tr><td>Tanggal Transaksi</td><td>19 Nov 2026</td></tr>
        <tr><td>Waktu Transaksi</td><td>09:15:20 WIB</td></tr>
        <tr><td>Merchant</td><td>Indomaret</td></tr>
        </table>
        </body></html>
        """.trimIndent(),
    )

    @Test
    fun `separate date and time cells produce the Jakarta instant`() = runTest {
        val raw = extract(livinSplitDateTimeMime())
        assertTrue(raw.textParts.single().contains("Tanggal Transaksi"))
        assertTrue(raw.textParts.single().contains("09:15:20 WIB"))
        val result = parser.parse(raw)
        assertTrue("parser should accept split date/time: $result", result is ParseResult.Parsed)
        assertEquals(Instant.parse("2026-11-19T02:15:20Z"), (result as ParseResult.Parsed).eventAt)
    }

    private fun splitRaw(date: String, time: String): RawMessage = RawMessage(
        messageId = "strict-date-time",
        sender = LivinFixtures.SENDER,
        subject = "Pembayaran Berhasil!",
        internalDate = null,
        textParts = listOf(
            """
            Pembayaran Berhasil!
            Nominal: Rp175.000
            Tanggal Transaksi
            $date
            Waktu Transaksi
            $time
            Merchant: Indomaret
            """.trimIndent(),
        ),
    )

    @Test
    fun `strict split date and time reject impossible or ambiguous values`() {
        val invalid = listOf(
            "31 Nov 2026" to "09:15:20 WIB",
            "29 Feb 2023" to "09:15:20 WIB",
            "19 Nov 2026" to "24:00 WIB",
            "19 Nov 2026" to "09:15:20 WITA",
            "19 Nov 2026" to "09:15:20",
            "19 Nov 2026" to "",
        )
        for ((date, time) in invalid) {
            val result = parser.parse(splitRaw(date, time))
            assertTrue("accepted invalid $date/$time", result is ParseResult.Unresolved)
        }
        val leap = parser.parse(splitRaw("29 Feb 2024", "09:15:20 WIB"))
        assertTrue("leap day rejected", leap is ParseResult.Parsed)
        assertEquals(Instant.parse("2024-02-29T02:15:20Z"), (leap as ParseResult.Parsed).eventAt)
        val nbsp = parser.parse(splitRaw("19\u00A0Nov\u00A02026", "09:15:20\u00A0WIB"))
        assertTrue("NBSP rejected", nbsp is ParseResult.Parsed)
        assertEquals(Instant.parse("2026-11-19T02:15:20Z"), (nbsp as ParseResult.Parsed).eventAt)
    }

    private fun extract(message: MimeMessage): RawMessage =
        message.extractIncoming("12345:42", session).raw

    private fun importFor(raw: RawMessage): Pair<ImportMonthUseCase, ImportFixture> {
        val fixture = ImportFixture(raw)
        val useCase = ImportMonthUseCase(
            gmailSource = fixture.source,
            queryBuilder = LivinQueryBuilder(registry),
            parser = parser,
            fingerprints = FingerprintFactory { ownerId -> "test-key::$ownerId".toByteArray() },
            categoryRules = MerchantCategoryRules(),
            transactions = fixture.transactions,
            imports = fixture.imports,
            summaries = FakeSummaryRepository(),
            analyses = FakeAnalysisRepository(),
            gmailConnection = FakeGmailConnectionRepository(),
            deletion = FakeDeletionRepository(),
            clock = clock,
            idGenerator = IdGenerator { "id-${nextId++}" },
        )
        return useCase to fixture
    }

    @Test
    fun `a real-shaped table notification becomes one stored row and moves the monthly total`() = runTest {
        val raw = extract(livinTableMime())

        // The MIME/HTML step is what the device actually feeds the parser.
        assertTrue(raw.textParts.single().contains("Nominal"))
        assertTrue(raw.textParts.single().contains("Rp175.000"))

        val (useCase, fixture) = importFor(raw)
        val job = (useCase("u_demo", month) as Outcome.Success).value

        assertEquals(ImportState.COMPLETE, job.state)
        assertEquals(1, job.matchedCount)
        assertEquals(1, job.importedCount)
        assertEquals(0, job.skippedCount)

        val row = fixture.transactions.all().single { it.ownerId == "u_demo" }
        assertEquals(Money.ofMajor(175_000), row.amount)
        assertEquals(Direction.DEBIT, row.direction)
        assertEquals("Indomaret", row.merchantLabel)
        // 19 Feb 2026 11:10 Asia/Jakarta == 04:10 UTC, never the device's local zone.
        assertEquals(Instant.parse("2026-02-19T04:10:00Z"), row.eventAt)

        val summary = ComputeMonthlySummaryUseCase(
            transactions = fixture.transactions,
            summaries = FakeSummaryRepository(),
            imports = fixture.imports,
            gmailConnection = FakeGmailConnectionRepository(),
            clock = clock,
        )("u_demo", month)

        assertEquals(Money.ofMajor(175_000), summary.grossOutflow)
        assertEquals(Money.ZERO, summary.grossInflow)
        assertEquals(1, summary.transactionCount)
        assertEquals(CoverageStatus.COMPLETE, summary.coverage)

        // Re-import: the fingerprint makes it idempotent, and the total does not double.
        val again = (useCase("u_demo", month) as Outcome.Success).value
        assertEquals(0, again.importedCount)
        assertEquals(1, again.duplicateCount)
        assertEquals(1, fixture.transactions.all().count { it.ownerId == "u_demo" })
        val unchanged = ComputeMonthlySummaryUseCase(
            transactions = fixture.transactions,
            summaries = FakeSummaryRepository(),
            imports = fixture.imports,
            gmailConnection = FakeGmailConnectionRepository(),
            clock = clock,
        )("u_demo", month)
        assertEquals(Money.ofMajor(175_000), unchanged.grossOutflow)
    }

    @Test
    fun `two owners importing the same message each get their own row`() = runTest {
        val (useCase, fixture) = importFor(extract(livinTableMime()))

        val first = (useCase("u_a", month) as Outcome.Success).value
        val second = (useCase("u_b", month) as Outcome.Success).value

        assertEquals(1, first.importedCount)
        assertEquals(1, second.importedCount)
        assertEquals(2, fixture.transactions.all().size)
        assertEquals(1, fixture.transactions.all().count { it.ownerId == "u_a" })
        assertEquals(1, fixture.transactions.all().count { it.ownerId == "u_b" })
    }

    @Test
    fun `a non-numeric amount in the table becomes a skip, not a zero transaction`() = runTest {
        val raw = extract(livinTableMime(amount = "-"))

        val parsed = parser.parse(raw)

        assertEquals(RedactionReason.MALFORMED_AMOUNT, (parsed as ParseResult.Unresolved).reason)
        val (useCase, fixture) = importFor(raw)
        val job = (useCase("u_demo", month) as Outcome.Success).value
        assertEquals(ImportState.COMPLETE, job.state)
        assertEquals(0, job.importedCount)
        assertEquals(1, job.skippedCount)
        assertTrue(fixture.transactions.all().isEmpty())
    }

    @Test
    fun `a failed payment in the table never becomes a transaction`() = runTest {
        // The QRIS template is selected by subject; the failure marker lives in the headline and
        // the status cell, and must win over the amount.
        val raw = extract(
            MimeMessage(
                session,
                ByteArrayInputStream(
                    """
                    From: Livin <notifikasi@bankmandiri.co.id>
                    Subject: Notifikasi Transaksi QRIS
                    MIME-Version: 1.0
                    Content-Type: application/octet-stream

                    Content-Type: text/html; charset=UTF-8

                    <html><body>
                    <p>Transaksi QRIS Gagal</p>
                    <table>
                    <tr><td>Nominal</td><td>Rp45.000</td></tr>
                    <tr><td>Tanggal</td><td>19 Februari 2026 11:10:00</td></tr>
                    <tr><td>Merchant</td><td>Kopi Uji</td></tr>
                    <tr><td>Status</td><td>Gagal</td></tr>
                    </table>
                    </body></html>
                    """.trimIndent().encodeToByteArray(),
                ),
            ),
        )

        val (useCase, fixture) = importFor(raw)
        val job = (useCase("u_demo", month) as Outcome.Success).value

        assertEquals(0, job.importedCount)
        assertEquals(1, job.skippedCount)
        assertTrue(fixture.transactions.all().isEmpty())
        assertEquals(
            RedactionReason.FAILED_PAYMENT,
            (parser.parse(raw) as ParseResult.Unresolved).reason,
        )
    }

    @Test
    fun `an attachment-only email adds no transaction`() = runTest {
        val raw = extract(
            mime(
                """
                Content-Type: multipart/mixed; boundary="B"

                --B
                Content-Type: application/pdf; name="nota.pdf"
                Content-Disposition: attachment; filename="nota.pdf"
                Content-Transfer-Encoding: base64

                JVBERi0xLjQK
                --B--
                """.trimIndent(),
            ),
        )

        assertEquals(1, raw.attachmentCount)
        assertEquals(RedactionReason.ATTACHMENT_ONLY, (parser.parse(raw) as ParseResult.Unresolved).reason)
        val (useCase, fixture) = importFor(raw)
        val job = (useCase("u_demo", month) as Outcome.Success).value
        assertEquals(0, job.importedCount)
        assertTrue(fixture.transactions.all().isEmpty())
    }

    // ---------------------------------------------------------------------------------------

    private class ImportFixture(val raw: RawMessage) {
        val transactions = FakeTransactionRepository()
        val imports = FakeImportRepository()
        val source = OneMessageSource(raw)
    }

    private class OneMessageSource(private val raw: RawMessage) : GmailSource {
        private val connection = MutableStateFlow(GmailConnectionState.CONNECTED)

        override suspend fun authorize(): Outcome<Unit> = Outcome.Success(Unit)

        override fun connectionState(): Flow<GmailConnectionState> = connection

        override suspend fun search(query: LivinQuery, pageToken: String?): Outcome<GmailPage> =
            Outcome.Success(
                GmailPage(
                    messages = listOf(GmailMessageRef(id = raw.messageId, internalDate = raw.internalDate)),
                    nextPageToken = null,
                    resultSizeEstimate = 1,
                ),
            )

        override suspend fun fetchMessage(id: String): Outcome<RawMessage> = Outcome.Success(raw)

        override suspend fun disconnect(): Outcome<Unit> = Outcome.Success(Unit)
    }
}
