package com.pam.recap.data.gmail

import com.pam.recap.core.money.Money
import com.pam.recap.data.seed.LivinFixtures
import com.pam.recap.domain.model.Direction
import com.pam.recap.domain.model.ParseResult
import com.pam.recap.domain.model.ParseStatus
import com.pam.recap.domain.model.RedactionReason
import com.pam.recap.domain.repository.RawMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

/**
 * §9 test 4 — the strict Livin parser. The interesting assertions are the rejections: a missing
 * amount must never become zero, a failed payment is not spending, attachments are never parsed,
 * an unknown template is excluded, and instructions embedded in the body are inert.
 */
class LivinQrisParserTest {

    private val parser = LivinQrisParser()

    private fun fixture(id: String): RawMessage {
        val fixture = requireNotNull(LivinFixtures.byId(id)) { "fixture $id missing" }
        return fixture.toRawMessage()
    }

    private fun parsed(id: String): ParseResult.Parsed =
        parser.parse(fixture(id)) as? ParseResult.Parsed
            ?: error("expected a parsed fixture for $id but was ${parser.parse(fixture(id))}")

    private fun unresolved(id: String): RedactionReason =
        (parser.parse(fixture(id)) as? ParseResult.Unresolved)?.reason
            ?: error("expected the fixture $id to be unresolved")

    @Test
    fun `parses a supported qris notification exactly`() {
        val result = parsed("seed-kopi-kenangan")

        assertEquals(Money.ofMajor(45_000), result.amount)
        assertEquals("IDR", result.currency)
        assertEquals(Direction.DEBIT, result.direction)
        assertEquals(ParseStatus.POSTED, result.status)
        assertEquals("Kopi Kenangan Senayan", result.merchantLabel)
        assertEquals("GPN", result.acquirer)
        assertEquals("livin-qris-payment/v1", result.parserVersion)
        // 03 Feb 2026 08:12:44 Jakarta == 2026-02-03T01:12:44Z
        assertEquals(Instant.parse("2026-02-03T01:12:44Z"), result.eventAt)
        assertTrue(result.confidence >= 0.9f)
    }

    @Test
    fun `parses a refund as a credit`() {
        val result = parsed("seed-refund-apartemen")

        assertEquals(Direction.CREDIT, result.direction)
        assertEquals(Money.ofMajor(100_000), result.amount)
        assertEquals("livin-qris-refund/v1", result.parserVersion)
    }

    @Test
    fun `failed payment notices are rejected`() {
        assertEquals(RedactionReason.FAILED_PAYMENT, unresolved("edge-failed-payment"))
    }

    @Test
    fun `a missing amount is unresolved and never becomes zero`() {
        val result = parser.parse(fixture("edge-missing-amount"))

        assertTrue(result is ParseResult.Unresolved)
        assertEquals(RedactionReason.MISSING_AMOUNT, (result as ParseResult.Unresolved).reason)
        assertFalse(result is ParseResult.Parsed)
    }

    @Test
    fun `a malformed amount is rejected instead of coerced`() {
        val body = """
            Transaksi QRIS Berhasil
            Tanggal: 25 Feb 2026 09:30:00
            Jenis Transaksi: QRIS
            Merchant: Kedai Kopi Salah Nominal
            Nominal: -
            Status: Berhasil
        """.trimIndent()
        val message = RawMessage(
            messageId = "malformed-amount",
            sender = LivinFixtures.SENDER,
            subject = "Notifikasi Transaksi QRIS",
            internalDate = null,
            textParts = listOf(body),
        )

        val result = parser.parse(message)

        assertEquals(RedactionReason.MALFORMED_AMOUNT, (result as ParseResult.Unresolved).reason)
    }

    @Test
    fun `an attachment only notification is excluded`() {
        assertEquals(RedactionReason.ATTACHMENT_ONLY, unresolved("edge-attachment-only"))
    }

    @Test
    fun `an unknown template is excluded`() {
        assertEquals(RedactionReason.UNKNOWN_TEMPLATE, unresolved("edge-unknown-template"))
    }

    @Test
    fun `instructions embedded in the body are ignored`() {
        val result = parsed("edge-injected-instruction")

        // The real first "Nominal" line wins; the injected duplicate is never read.
        assertEquals(Money.ofMajor(37_000), result.amount)
        assertNotEquals(Money.ofMajor(9_999_000), result.amount)
        assertEquals("Kopi Titik Temu", result.merchantLabel)
        assertFalse(result.description.contains("Abaikan"))
        assertFalse(result.merchantLabel.orEmpty().contains("Asing"))
    }

    @Test
    fun `a display name without an address never matches the allow list`() {
        val message = RawMessage(
            messageId = "display-name-only",
            sender = "Livin by Mandiri",
            subject = "Notifikasi Transaksi QRIS",
            internalDate = null,
            textParts = listOf("Transaksi QRIS Berhasil\nNominal: Rp10.000,00"),
        )

        assertEquals(
            RedactionReason.UNKNOWN_TEMPLATE,
            (parser.parse(message) as ParseResult.Unresolved).reason,
        )
    }

    @Test
    fun `missing event time is unresolved`() {
        val body = """
            Transaksi QRIS Berhasil
            Jenis Transaksi: QRIS
            Merchant: Kedai Tanpa Waktu
            Nominal: Rp20.000,00
            Status: Berhasil
        """.trimIndent()
        val message = RawMessage(
            messageId = "no-time",
            sender = LivinFixtures.SENDER,
            subject = "Notifikasi Transaksi QRIS",
            internalDate = null,
            textParts = listOf(body),
        )

        assertEquals(
            RedactionReason.MISSING_EVENT_TIME,
            (parser.parse(message) as ParseResult.Unresolved).reason,
        )
    }

    @Test
    fun `missing status is unresolved`() {
        val body = """
            Transaksi QRIS Berhasil
            Tanggal: 25 Feb 2026 09:30:00
            Jenis Transaksi: QRIS
            Merchant: Kedai Tanpa Status
            Nominal: Rp20.000,00
        """.trimIndent()
        val message = RawMessage(
            messageId = "no-status",
            sender = LivinFixtures.SENDER,
            subject = "Notifikasi Transaksi QRIS",
            internalDate = null,
            textParts = listOf(body),
        )

        assertEquals(
            RedactionReason.MISSING_STATUS,
            (parser.parse(message) as ParseResult.Unresolved).reason,
        )
    }

    @Test
    fun `a bill payment without a status label parses when the subject confirms success`() {
        // Measured on a real Gmail message: Nominal/Tanggal/Merchant only, the subject says
        // "Pembayaran Berhasil!", and there is no "Status:"/"Jenis Transaksi:" line at all.
        val body = """
            Pembayaran Berhasil!
            Nominal: Rp175.000
            Tanggal: 19 Februari 2026 11:10:00
            Merchant: Indomaret
        """.trimIndent()
        val message = RawMessage(
            messageId = "bill-1",
            sender = "Livin <noreply.livin@bankmandiri.co.id>",
            subject = "Pembayaran Berhasil!",
            internalDate = null,
            textParts = listOf(body),
        )

        val parsed = parser.parse(message) as ParseResult.Parsed
        assertEquals(Money.ofMinor(17_500_000), parsed.amount)
        assertEquals(Direction.DEBIT, parsed.direction)
        assertEquals("Indomaret", parsed.merchantLabel)
    }

    @Test
    fun `a table layout with the value on the next line still parses`() {
        val body = """
            Pembayaran Berhasil!
            Nominal :
            Rp175.000
            Tanggal :
            19 Februari 2026 11:10:00
            Merchant :
            Indomaret
        """.trimIndent()
        val message = RawMessage(
            messageId = "bill-table",
            sender = "Livin <noreply.livin@bankmandiri.co.id>",
            subject = "Pembayaran Berhasil!",
            internalDate = null,
            textParts = listOf(body),
        )

        val parsed = parser.parse(message) as ParseResult.Parsed
        assertEquals(Money.ofMinor(17_500_000), parsed.amount)
        assertEquals("Indomaret", parsed.merchantLabel)
    }

    @Test
    fun `qualified labels from the real HTML layout still parse`() {
        val body = """
            Pembayaran Berhasil!
            Nominal Pembayaran
            Rp175.000
            Tanggal Transaksi
            19 Februari 2026 11:10:00
            Merchant
            Indomaret
        """.trimIndent()
        val message = RawMessage(
            messageId = "bill-qualified",
            sender = "Livin <noreply.livin@bankmandiri.co.id>",
            subject = "Pembayaran Berhasil!",
            internalDate = null,
            textParts = listOf(body),
        )

        val parsed = parser.parse(message) as ParseResult.Parsed
        assertEquals(Money.ofMinor(17_500_000), parsed.amount)
        assertEquals("Indomaret", parsed.merchantLabel)
    }

    @Test
    fun `a qris body inside a bill-payment notification uses the penerima business name`() {
        // Owner-reported P0: the real "Pembayaran Berhasil!" body carries a QR section with a
        // readable Penerima *and* Merchant/Customer PAN identifiers. The 19-digit PAN must never
        // become the merchant name, and the location line after the name is not part of it.
        val body = """
            Pembayaran Berhasil!
            Nominal
            Rp175.000
            Tanggal
            19 Februari 2026 11:10:00
            Penerima
            Kopi Kenangan Senayan
            Jl. Asia Afrika No. 8, Jakarta
            Merchant PAN
            9360012345678901234
            Customer PAN
            9360098765432109876
            No. Referensi
            20260219ABC123
            No. Ref. QRIS
            QRISREF998877
        """.trimIndent()
        val message = RawMessage(
            messageId = "qris-bill-penerima",
            sender = "Livin <noreply.livin@bankmandiri.co.id>",
            subject = "Pembayaran Berhasil!",
            internalDate = null,
            textParts = listOf(body),
        )

        val parsed = parser.parse(message) as ParseResult.Parsed
        assertEquals(Money.ofMinor(17_500_000), parsed.amount)
        assertEquals(Direction.DEBIT, parsed.direction)
        assertEquals("Kopi Kenangan Senayan", parsed.merchantLabel)
    }

    @Test
    fun `penerima wins over a merchant label when both are present`() {
        val body = """
            Pembayaran Berhasil!
            Nominal: Rp175.000
            Tanggal: 19 Februari 2026 11:10:00
            Merchant: Toko Tidak Terverifikasi
            Penerima: Warung Nasi Ibu Siti
        """.trimIndent()
        val message = RawMessage(
            messageId = "penerima-priority",
            sender = "Livin <noreply.livin@bankmandiri.co.id>",
            subject = "Pembayaran Berhasil!",
            internalDate = null,
            textParts = listOf(body),
        )

        val parsed = parser.parse(message) as ParseResult.Parsed
        assertEquals("Warung Nasi Ibu Siti", parsed.merchantLabel)
    }

    @Test
    fun `a reference-only body parses to the identifier so the backend can flag it`() {
        // No Penerima at all: the row must still reach the backend, whose numeric-label guard
        // finishes it NEEDS_REVIEW/merchant_not_identifiable with zero provider calls. Dropping
        // the email here would lose a real payment.
        val body = """
            Pembayaran Berhasil!
            Nominal: Rp175.000
            Tanggal: 19 Februari 2026 11:10:00
            Merchant PAN
            9360012345678901234
            No. Referensi
            20260219ABC123
        """.trimIndent()
        val message = RawMessage(
            messageId = "reference-only",
            sender = "Livin <noreply.livin@bankmandiri.co.id>",
            subject = "Pembayaran Berhasil!",
            internalDate = null,
            textParts = listOf(body),
        )

        val parsed = parser.parse(message) as ParseResult.Parsed
        assertEquals("9360012345678901234", parsed.merchantLabel)
    }

    @Test
    fun `an inline merchant pan never becomes the merchant name`() {
        val body = """
            Pembayaran Berhasil!
            Nominal: Rp175.000
            Tanggal: 19 Februari 2026 11:10:00
            Penerima: Bakso Pak Kumis
            Merchant PAN: 9360012345678901234
        """.trimIndent()
        val message = RawMessage(
            messageId = "inline-pan",
            sender = "Livin <noreply.livin@bankmandiri.co.id>",
            subject = "Pembayaran Berhasil!",
            internalDate = null,
            textParts = listOf(body),
        )

        val parsed = parser.parse(message) as ParseResult.Parsed
        assertEquals("Bakso Pak Kumis", parsed.merchantLabel)
    }

    @Test
    fun `a bill payment with no success marker anywhere is unresolved`() {
        val body = """
            Nominal: Rp175.000
            Tanggal: 19 Februari 2026 11:10:00
            Merchant: Indomaret
        """.trimIndent()
        val message = RawMessage(
            messageId = "bill-2",
            sender = "Livin <noreply.livin@bankmandiri.co.id>",
            subject = "Pembayaran tagihan",
            internalDate = null,
            textParts = listOf(body),
        )

        assertEquals(
            RedactionReason.MISSING_STATUS,
            (parser.parse(message) as ParseResult.Unresolved).reason,
        )
    }

    @Test
    fun `missing recipient is unresolved`() {
        val body = """
            Transaksi QRIS Berhasil
            Tanggal: 25 Feb 2026 09:30:00
            Jenis Transaksi: QRIS
            Nominal: Rp20.000,00
            Status: Berhasil
        """.trimIndent()
        val message = RawMessage(
            messageId = "no-recipient",
            sender = LivinFixtures.SENDER,
            subject = "Notifikasi Transaksi QRIS",
            internalDate = null,
            textParts = listOf(body),
        )

        assertEquals(
            RedactionReason.MISSING_RECIPIENT,
            (parser.parse(message) as ParseResult.Unresolved).reason,
        )
    }

    @Test
    fun `a missing acquirer lowers confidence but still parses`() {
        val body = """
            Transaksi QRIS Berhasil
            Tanggal: 25 Feb 2026 09:30:00
            Jenis Transaksi: QRIS
            Merchant: Kedai Tanpa Acquirer
            Nominal: Rp20.000,00
            Status: Berhasil
        """.trimIndent()
        val message = RawMessage(
            messageId = "no-acquirer",
            sender = LivinFixtures.SENDER,
            subject = "Notifikasi Transaksi QRIS",
            internalDate = null,
            textParts = listOf(body),
        )

        val result = parser.parse(message) as ParseResult.Parsed

        assertEquals(null, result.acquirer)
        assertTrue(result.confidence < 1.0f)
    }

    @Test
    fun `rupiah parsing handles grouping decimals and symbols`() {
        assertEquals(Money.ofMajor(1_200_000), parser.parseRupiah("Rp1.200.000"))
        assertEquals(Money.ofMajor(1_200_000), parser.parseRupiah("Rp1.200.000,00"))
        assertEquals(Money(123_456_789), parser.parseRupiah("IDR 1.234.567,89"))
        assertEquals(Money.ofMajor(45_000), parser.parseRupiah("45.000"))
        assertEquals(null, parser.parseRupiah("Rp"))
        assertEquals(null, parser.parseRupiah("-"))
    }

    @Test
    fun `event time parsing is jakarta local time`() {
        assertEquals(
            Instant.parse("2026-02-03T01:12:44Z"),
            parser.parseEventTime("03 Feb 2026 08:12:44"),
        )
        assertEquals(
            Instant.parse("2026-02-03T01:12:44Z"),
            parser.parseEventTime("03/02/2026 08:12:44 WIB"),
        )
        assertEquals(null, parser.parseEventTime("kemarin sore"))
    }

    @Test
    fun `the registry only allow lists known senders and subjects`() {
        val registry = LivinTemplateRegistry()

        assertEquals("qris-payment", registry.match(LivinFixtures.SENDER, "Notifikasi Transaksi QRIS")?.id)
        assertEquals(null, registry.match("promo@bankmandiri.co.id", "Promo Belanja Akhir Tahun"))
        assertEquals(null, registry.match("Livin by Mandiri", "Notifikasi Transaksi QRIS"))
        assertTrue(registry.senderAddresses.contains("notifikasi@bankmandiri.co.id"))
    }
}
