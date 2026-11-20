package com.pam.recap.data.gmail

import java.io.ByteArrayInputStream
import java.util.Properties
import javax.mail.Session
import javax.mail.internet.MimeMessage
import org.junit.Assert.assertEquals
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
