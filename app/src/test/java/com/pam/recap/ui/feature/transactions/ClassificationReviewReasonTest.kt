package com.pam.recap.ui.feature.transactions

import com.pam.recap.R
import com.pam.recap.domain.repository.ServerCategoryJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the "kategori tidak terbaca" fix: the category-job API already reports why
 * a row did not get a category, so the app must explain that reason and must never present a
 * numeric reference number (Livin bill-payment "Merchant" slot) as if it were a merchant name.
 */
class ClassificationReviewReasonTest {

    private fun job(
        state: String,
        reasonCode: String? = null,
        errorCode: String? = null,
    ) = ServerCategoryJob(
        id = "job_1",
        transactionId = "tx_1",
        state = state,
        reasonCode = reasonCode,
        errorCode = errorCode,
    )

    @Test
    fun `review reasons are distinct, sorted and ignore classified rows`() {
        val reasons = reviewReasonCodes(
            listOf(
                job("NEEDS_REVIEW", reasonCode = "unsafe_merchant"),
                job("NEEDS_REVIEW", reasonCode = "merchant_not_identifiable"),
                job("NEEDS_REVIEW", reasonCode = "unsafe_merchant"),
                job("CLASSIFIED", reasonCode = null),
                job("PENDING"),
                job("FAILED", errorCode = "provider_transient"),
            ),
        )

        assertEquals(
            listOf("merchant_not_identifiable", "provider_transient", "unsafe_merchant"),
            reasons,
        )
    }

    @Test
    fun `blank and null codes are dropped so no technical token is rendered`() {
        val reasons = reviewReasonCodes(
            listOf(
                job("NEEDS_REVIEW", reasonCode = "  "),
                job("NEEDS_REVIEW"),
                job("FAILED"),
            ),
        )

        assertTrue(reasons.isEmpty())
    }

    @Test
    fun `every known reason code maps to its own explanation`() {
        val known = listOf(
            "merchant_not_identifiable",
            "merchant_missing",
            "unsafe_merchant",
            "not_eligible",
            "not_eligible_personal_transfer",
        )
        val mapped = known.map(::classificationReasonRes)

        assertEquals(known.size, mapped.distinct().size)
        mapped.forEach { res -> assertNotEquals(R.string.transactions_classify_review_generic, res) }
    }

    @Test
    fun `unknown reason code falls back to the generic manual-review copy`() {
        assertEquals(
            R.string.transactions_classify_review_generic,
            classificationReasonRes("some_future_reason_code"),
        )
    }

    @Test
    fun `numeric reference labels are detected but real merchant names are not`() {
        // Shape only: the numbers below are synthetic, not owner data.
        assertTrue(isReferenceMerchantLabel("9380147528123456789"))
        assertTrue(isReferenceMerchantLabel(" 9380147528123456789 "))
        assertTrue(isReferenceMerchantLabel("938-014-7528"))
        assertTrue(isReferenceMerchantLabel("938 014 7528"))

        assertFalse(isReferenceMerchantLabel("Indomaret"))
        assertFalse(isReferenceMerchantLabel("7-Eleven"))
        assertFalse(isReferenceMerchantLabel("Kopi Uji 2"))
        assertFalse(isReferenceMerchantLabel(null))
        assertFalse(isReferenceMerchantLabel(""))
        assertFalse(isReferenceMerchantLabel("   "))
    }
}
