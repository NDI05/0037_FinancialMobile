package com.pam.recap.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Instant

/**
 * §9 test 5 — the dedupe fingerprint (FR-18, NFR-02): deterministic, per-user, field-sensitive,
 * opaque and truncated to 128 bits.
 */
class FingerprintFactoryTest {

    private val owner = "u_ownerA"
    private val otherOwner = "u_ownerB"

    private val factory = FingerprintFactory { ownerId -> "test-key::$ownerId".toByteArray() }

    private fun input(
        messageId: String = "msg-1",
        amountMinor: Long = 4_500_000,
        eventAt: Long = Instant.parse("2026-02-03T01:12:44Z").toEpochMilliseconds(),
        directionCode: String = "DEBIT",
        recipient: String? = "Kopi Kenangan Senayan",
    ) = FingerprintInput(messageId, amountMinor, eventAt, directionCode, recipient)

    @Test
    fun `is deterministic for the same owner and input`() {
        assertEquals(
            factory.fingerprint(owner, input()),
            factory.fingerprint(owner, input()),
        )
    }

    @Test
    fun `is opaque, lowercase hex and truncated to 128 bits`() {
        val fingerprint = factory.fingerprint(owner, input())

        assertEquals(FingerprintFactory.HEX_LENGTH, fingerprint.length)
        assertEquals(32, fingerprint.length)
        assertTrue(fingerprint.all { it in "0123456789abcdef" })
        assertFalse(fingerprint.contains("msg-1"))
        assertFalse(fingerprint.contains("Kopi"))
    }

    @Test
    fun `is per user`() {
        assertNotEquals(
            factory.fingerprint(owner, input()),
            factory.fingerprint(otherOwner, input()),
        )
    }

    @Test
    fun `changes when the message id changes`() {
        assertNotEquals(
            factory.fingerprint(owner, input(messageId = "msg-1")),
            factory.fingerprint(owner, input(messageId = "msg-2")),
        )
    }

    @Test
    fun `changes when the amount changes`() {
        assertNotEquals(
            factory.fingerprint(owner, input(amountMinor = 4_500_000)),
            factory.fingerprint(owner, input(amountMinor = 4_500_001)),
        )
    }

    @Test
    fun `changes when the event time changes`() {
        assertNotEquals(
            factory.fingerprint(owner, input(eventAt = 1_000L)),
            factory.fingerprint(owner, input(eventAt = 2_000L)),
        )
    }

    @Test
    fun `changes when the direction changes`() {
        assertNotEquals(
            factory.fingerprint(owner, input(directionCode = "DEBIT")),
            factory.fingerprint(owner, input(directionCode = "CREDIT")),
        )
    }

    @Test
    fun `changes when the recipient changes`() {
        assertNotEquals(
            factory.fingerprint(owner, input(recipient = "Kopi Kenangan Senayan")),
            factory.fingerprint(owner, input(recipient = "Warung Padang Sederhana")),
        )
    }

    @Test
    fun `normalizes cosmetic recipient differences`() {
        assertEquals(
            factory.fingerprint(owner, input(recipient = "Kopi Kenangan Senayan")),
            factory.fingerprint(owner, input(recipient = "  kopi   kenangan senayan ")),
        )
    }

    @Test
    fun `field boundaries cannot be shifted to forge a collision`() {
        // ("ab","c") vs ("a","bc") must not hash the same: fields are length-prefixed.
        val left = factory.fingerprint(owner, input(messageId = "ab", recipient = "c"))
        val right = factory.fingerprint(owner, input(messageId = "a", recipient = "bc"))

        assertNotEquals(left, right)
    }

    @Test
    fun `matches reports whether a stored digest belongs to the same content`() {
        val stored = factory.fingerprint(owner, input())

        assertTrue(factory.matches(owner, input(), stored))
        assertFalse(factory.matches(owner, input(amountMinor = 1), stored))
        assertFalse(factory.matches(otherOwner, input(), stored))
    }

    @Test
    fun `a different key produces a different digest`() {
        val otherFactory = FingerprintFactory { "another-key".toByteArray() }

        assertNotEquals(
            factory.fingerprint(owner, input()),
            otherFactory.fingerprint(owner, input()),
        )
    }
}
