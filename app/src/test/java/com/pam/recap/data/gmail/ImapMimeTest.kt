package com.pam.recap.data.gmail

import java.io.ByteArrayInputStream
import java.util.Properties
import javax.mail.Session
import javax.mail.internet.MimeMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G4: MIME handling for the notification shapes that matter — plain text, HTML-only, multipart with
 * an attachment (the attachment is skipped, the text is still parsed), and no usable body.
 */
class ImapMimeTest {

    private val session = Session.getInstance(Properties())

    private fun parse(raw: String): MimeMessage =
        MimeMessage(session, ByteArrayInputStream(raw.trimIndent().encodeToByteArray()))

    @Test
    fun `text plain is read directly`() {
        val body = extractMimeBody(
            parse(
                """
                Subject: Notifikasi Transaksi
                From: Livin <no-reply@livin.id>
                Content-Type: text/plain; charset=UTF-8

                Nominal: Rp175.000
                Jenis Transaksi: DEBIT
                """,
            ),
        )
        assertTrue(body.text.contains("Nominal: Rp175.000"))
        assertEquals(0, body.attachmentCount)
    }

    @Test
    fun `html only is stripped to text`() {
        val body = extractMimeBody(
            parse(
                """
                Subject: Notifikasi Transaksi
                Content-Type: text/html; charset=UTF-8

                <html><body><p>Nominal: <b>Rp250.000</b></p><script>x()</script></body></html>
                """,
            ),
        )
        assertTrue(body.text.contains("Nominal: Rp250.000"))
        assertTrue(!body.text.contains("script"))
        assertTrue(!body.text.contains("<"))
    }

    @Test
    fun `an attachment is skipped and the text part still parsed`() {
        val body = extractMimeBody(
            parse(
                """
                Subject: Notifikasi Transaksi
                MIME-Version: 1.0
                Content-Type: multipart/mixed; boundary="BOUNDARY"

                --BOUNDARY
                Content-Type: text/plain; charset=UTF-8

                Nominal: Rp99.000
                Jenis Transaksi: DEBIT

                --BOUNDARY
                Content-Type: application/pdf; name="nota.pdf"
                Content-Disposition: attachment; filename="nota.pdf"
                Content-Transfer-Encoding: base64

                JVBERi0xLjQK
                --BOUNDARY--
                """,
            ),
        )
        assertTrue(body.text.contains("Nominal: Rp99.000"))
        assertEquals(1, body.attachmentCount)
        assertTrue(!body.text.contains("JVBERi0"))
    }

    @Test
    fun `an attachment-only message yields no text`() {
        val body = extractMimeBody(
            parse(
                """
                Subject: Notifikasi Transaksi
                MIME-Version: 1.0
                Content-Type: multipart/mixed; boundary="BOUNDARY"

                --BOUNDARY
                Content-Type: application/pdf; name="nota.pdf"
                Content-Disposition: attachment; filename="nota.pdf"
                Content-Transfer-Encoding: base64

                JVBERi0xLjQK
                --BOUNDARY--
                """,
            ),
        )
        assertEquals("", body.text)
        assertEquals(1, body.attachmentCount)
    }

    @Test
    fun `a table with a label cell and a value cell keeps them on separate lines`() {
        val body = extractMimeBody(
            parse(
                """
                Subject: Pembayaran Berhasil!
                Content-Type: text/html; charset=UTF-8

                <html><body><table>
                <tr><td>Nominal Pembayaran</td><td>Rp175.000</td></tr>
                <tr><td>Tanggal Transaksi</td><td>19 Februari 2026 11:10:00</td></tr>
                <tr><td>Merchant</td><td>Indomaret</td></tr>
                </table></body></html>
                """,
            ),
        )

        // Regression: collapsing `</td>` to a space produced "Nominal Pembayaran Rp175.000".
        assertTrue(body.text.lines().any { it.trim() == "Nominal Pembayaran" })
        assertTrue(body.text.lines().any { it.trim() == "Rp175.000" })
        assertTrue(body.text.lines().any { it.trim() == "Indomaret" })
    }

    /**
     * The device shape: Gmail hands the root part back as an untyped `application/octet-stream`
     * whose bytes are a MIME entity. Without recovery this was `parts=1 plain=0 html=0 chars=0` and
     * every notification was rejected.
     */
    @Test
    fun `an untyped body that is itself a MIME entity is recovered`() {
        val body = extractMimeBody(
            parse(
                """
                Subject: Notifikasi Transaksi
                From: Livin <noreply.livin@bankmandiri.co.id>
                Content-Type: application/octet-stream

                Content-Type: text/plain; charset=UTF-8

                Nominal: Rp175.000
                Tanggal: 19 Februari 2026 11:10:00
                Jenis Transaksi: DEBIT
                Merchant: Indomaret
                Status: Berhasil
                """,
            ),
            session,
        )

        assertTrue(body.text.contains("Nominal: Rp175.000"))
        assertTrue(body.text.contains("Merchant: Indomaret"))
        assertEquals(0, body.attachmentCount)
        assertEquals(1, body.kindCounts["embedded_mime"])
    }

    @Test
    fun `an untyped HTML body is stripped`() {
        val body = extractMimeBody(
            parse(
                """
                Subject: Notifikasi Transaksi
                Content-Type: application/octet-stream

                <html><body><p>Nominal: <b>Rp250.000</b></p><script>x()</script></body></html>
                """,
            ),
            session,
        )

        assertTrue(body.text.contains("Nominal: Rp250.000"))
        assertTrue(!body.text.contains("<"))
        assertTrue(!body.text.contains("script"))
    }

    @Test
    fun `an untyped binary body never becomes text and is not an attachment`() {
        val pdf = "%PDF-1.4\u0000\u0001\u0002\u0003binary\u0000data".toByteArray()
        val part = javax.mail.internet.MimeBodyPart()
        part.setHeader("Content-Type", "application/octet-stream")
        part.setDataHandler(
            javax.activation.DataHandler(javax.mail.util.ByteArrayDataSource(pdf, "application/octet-stream")),
        )

        val body = extractMimeBody(part, session)

        assertEquals("", body.text)
        // A body without a filename is not an attachment; it is simply not text.
        assertEquals(0, body.attachmentCount)
        assertEquals(1, body.kindCounts["binary_skipped"])
    }

    @Test
    fun `an unknown-size message that exceeds the bound is rejected while writing`() {
        // The provider can report size = -1; the old check then wrote the whole body before
        // checking the byte count. The bounded stream must stop at the limit.
        var written = 0
        val oversized = object : MimeMessage(session) {
            override fun getSize(): Int = -1
            override fun writeTo(out: java.io.OutputStream) {
                val chunk = ByteArray(8 * 1024)
                repeat((1024 * 1024 / chunk.size) + 2) {
                    out.write(chunk)
                    written += chunk.size
                }
            }
        }

        assertNull(oversized.reparseRaw(session))
        assertTrue("wrote $written bytes past the bound", written <= 1024 * 1024)
    }

    @Test
    fun `a message without a usable body yields empty text`() {
        val body = extractMimeBody(
            parse(
                """
                Subject: Notifikasi Transaksi
                Content-Type: text/plain; charset=UTF-8

                """,
            ),
        )
        assertEquals("", body.text)
    }
}
