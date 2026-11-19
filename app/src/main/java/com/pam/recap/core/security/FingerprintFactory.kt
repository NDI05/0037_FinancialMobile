package com.pam.recap.core.security

import java.nio.charset.StandardCharsets
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supplies the per-user HMAC key. The Android implementation derives it from a master secret
 * wrapped by the Keystore, so no key material is ever stored in clear (NFR-16).
 */
fun interface HmacKeyProvider {
    fun keyFor(ownerId: String): ByteArray
}

/**
 * The exact fields that take part in a fingerprint (FR-18):
 * `messageId + amount + eventAt + direction + recipient`.
 *
 * Kept as a plain data class with primitives so `core` does not depend on `domain`.
 */
data class FingerprintInput(
    val messageId: String,
    val amountMinor: Long,
    val eventAtEpochMilliseconds: Long,
    val directionCode: String,
    val recipientLabel: String?,
)

/**
 * Per-user, opaque dedupe fingerprint (FR-18, NFR-02).
 *
 * - HMAC-SHA256, so the value cannot be reversed into a message id or an amount by an attacker
 *   who reads the database.
 * - Truncated to 128 bits / 32 hex chars: enough to make accidental collisions impossible in a
 *   10k-row month, short enough to display a preview in the provenance block.
 * - The key is per user, so the same email in two accounts never produces the same fingerprint.
 * - The raw Gmail message id is **never** persisted; only this digest is (hard rule 7).
 *
 * A collision is not silently dropped: the import pipeline compares the stored content of an
 * existing row with the candidate and records a `FINGERPRINT_COLLISION` skipped candidate.
 */
@Singleton
class FingerprintFactory @Inject constructor(
    private val keyProvider: HmacKeyProvider,
) {

    /** Opaque fingerprint for one parsed candidate. */
    fun fingerprint(ownerId: String, input: FingerprintInput): String {
        require(ownerId.isNotBlank()) { "ownerId must not be blank" }
        require(input.messageId.isNotBlank()) { "messageId must not be blank" }
        val payload = canonicalize(input)
        val digest = hmacSha256(keyProvider.keyFor(ownerId), payload)
        return digest.toHex().take(HEX_LENGTH)
    }

    /** True when [stored] was produced from exactly the same normalized field set. */
    fun matches(ownerId: String, input: FingerprintInput, stored: String): Boolean =
        fingerprint(ownerId, input) == stored

    /**
     * Ambiguity-free canonical form: every field is length-prefixed so
     * `("ab","c")` and `("a","bc")` cannot collide before hashing.
     */
    private fun canonicalize(input: FingerprintInput): String = buildList {
        add(input.messageId)
        add(input.amountMinor.toString())
        add(input.eventAtEpochMilliseconds.toString())
        add(input.directionCode.uppercase())
        add(normalizeRecipient(input.recipientLabel))
    }.joinToString(separator = FIELD_SEPARATOR) { value -> "${value.length}:$value" }

    /**
     * Merchant labels are content: casing, surrounding whitespace and internal runs of spaces
     * are normalized so a cosmetic template change does not create a duplicate transaction.
     */
    private fun normalizeRecipient(label: String?): String =
        label.orEmpty().trim().lowercase().replace(WHITESPACE_RUN, " ")

    private fun hmacSha256(key: ByteArray, payload: String): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(key, HMAC_ALGORITHM))
        return mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8))
    }

    private fun ByteArray.toHex(): String {
        val out = StringBuilder(size * 2)
        for (byte in this) {
            val value = byte.toInt() and 0xFF
            out.append(HEX_DIGITS[value ushr 4]).append(HEX_DIGITS[value and 0x0F])
        }
        return out.toString()
    }

    companion object {
        private const val HMAC_ALGORITHM = "HmacSHA256"
        private const val FIELD_SEPARATOR = "\u001F"
        private val WHITESPACE_RUN = Regex("\\s+")
        private const val HEX_DIGITS = "0123456789abcdef"

        /** 16 bytes of the HMAC digest => 32 hex characters. */
        const val TRUNCATED_BYTES: Int = 16
        const val HEX_LENGTH: Int = TRUNCATED_BYTES * 2
    }
}
