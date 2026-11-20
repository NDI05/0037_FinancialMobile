package com.pam.recap.data.gmail

import com.pam.recap.core.security.FingerprintFactory
import com.pam.recap.core.security.FingerprintInput
import com.pam.recap.core.security.HmacKeyProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import kotlin.time.Instant

/**
 * G4/FR-18/DR-02: the dedupe fingerprint is `HMAC(UIDVALIDITY + UID + fields)`, stable within a
 * mailbox generation and opaque — the raw reference is never recoverable from it.
 */
class ImapFingerprintTest {

    private val factory = FingerprintFactory(
        // Per-user key: derived from the owner id itself, like the Keystore-backed provider.
        HmacKeyProvider { ownerId -> ownerId.padEnd(32, 'x').take(32).toByteArray() },
    )
    private val owner = "u_owner"

    private fun input(uidValidity: Long = 1_700_000_000L, uid: Long = 42): FingerprintInput =
        FingerprintInput(
            messageId = "$uidValidity:$uid",
            amountMinor = 17_500_000,
            eventAtEpochMilliseconds = Instant.parse("2026-02-19T04:10:00Z").toEpochMilliseconds(),
            directionCode = "DEBIT",
            recipientLabel = "Indomaret",
        )

    @Test
    fun `the same message yields the same fingerprint`() {
        assertEquals(factory.fingerprint(owner, input()), factory.fingerprint(owner, input()))
    }

    @Test
    fun `a different uid or uidvalidity yields a different fingerprint`() {
        assertNotEquals(factory.fingerprint(owner, input(uid = 43)), factory.fingerprint(owner, input()))
        assertNotEquals(
            factory.fingerprint(owner, input(uidValidity = 1_700_000_001L)),
            factory.fingerprint(owner, input()),
        )
    }

    @Test
    fun `the fingerprint is opaque and never contains the raw reference`() {
        val fingerprint = factory.fingerprint(owner, input())
        assertEquals(32, fingerprint.length)
        assertTrueHex(fingerprint)
        assertFalse(fingerprint.contains("1700000000"))
        assertFalse(fingerprint.contains("42"))
    }

    @Test
    fun `two owners never share a fingerprint`() {
        assertNotEquals(factory.fingerprint(owner, input()), factory.fingerprint("u_other", input()))
    }

    private fun assertTrueHex(value: String) {
        assertTrue(value.all { it in '0'..'9' || it in 'a'..'f' })
    }

    private fun assertTrue(condition: Boolean) {
        org.junit.Assert.assertTrue(condition)
    }
}
