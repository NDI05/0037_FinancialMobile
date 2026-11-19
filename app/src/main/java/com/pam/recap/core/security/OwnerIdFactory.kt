package com.pam.recap.core.security

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Derives the opaque owner id used by every owner-scoped row (FR-02/FR-09).
 *
 * The Google account address is hashed once so the database never contains the raw mailbox
 * identity: `ownerId` is a stable pseudonym, and the only place a mailbox label appears is the
 * single `mailboxLabel` column the settings screen needs (FR-49).
 */
object OwnerIdFactory {

    fun ownerIdFor(accountId: String): String {
        require(accountId.isNotBlank()) { "accountId must not be blank" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(accountId.trim().lowercase().toByteArray(StandardCharsets.UTF_8))
        return PREFIX + digest.toHex().take(LENGTH)
    }

    /** Identifier used by the bundled fixture demo account (OI-01). */
    const val DEMO_ACCOUNT_ID: String = "demo.livin@pam.local"

    private const val PREFIX = "u_"
    private const val LENGTH = 24
    private const val HEX_DIGITS = "0123456789abcdef"

    private fun ByteArray.toHex(): String {
        val out = StringBuilder(size * 2)
        for (byte in this) {
            val value = byte.toInt() and 0xFF
            out.append(HEX_DIGITS[value ushr 4]).append(HEX_DIGITS[value and 0x0F])
        }
        return out.toString()
    }
}
