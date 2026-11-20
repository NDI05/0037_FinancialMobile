package com.pam.recap.data.gmail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * FR-11: eligibility is decided by the mailbox address plus the subject, never by a display name.
 *
 * The second sender is the one a real inbox actually used; an import against it matched nothing
 * while the allow-list held only the address from the SRS sample, so this pins both.
 */
class LivinTemplateRegistryTest {

    private val registry = LivinTemplateRegistry()

    @Test
    fun `the address from the srs sample still matches`() {
        val template = registry.match(
            sender = "notifikasi@bankmandiri.co.id",
            subject = "Transaksi QRIS Berhasil",
        )
        assertNotNull(template)
        assertEquals("qris-payment", template?.id)
    }

    @Test
    fun `the address observed in a real inbox matches a payment notice`() {
        val template = registry.match(
            sender = "noreply.livin@bankmandiri.co.id",
            subject = "Pembayaran Berhasil!",
        )
        assertNotNull(template)
        assertEquals("bill-payment", template?.id)
    }

    @Test
    fun `a display name carrying the bank name is not enough`() {
        assertNull(registry.match(sender = "Bank Mandiri", subject = "Pembayaran Berhasil!"))
    }

    @Test
    fun `an unrelated address with a matching subject is refused`() {
        assertNull(
            registry.match(sender = "promo@contoh.test", subject = "Pembayaran Berhasil!"),
        )
    }

    @Test
    fun `an allow-listed address with an unrelated subject is refused`() {
        assertNull(registry.match(sender = "noreply.livin@bankmandiri.co.id", subject = "Promo spesial"))
    }

    @Test
    fun `matching ignores case and the angle-bracket form`() {
        val template = registry.match(
            sender = "Livin <NoReply.Livin@BankMandiri.co.id>",
            subject = "pembayaran berhasil",
        )
        assertNotNull(template)
    }
}
