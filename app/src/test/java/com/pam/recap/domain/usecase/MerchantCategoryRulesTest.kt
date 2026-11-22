package com.pam.recap.domain.usecase

import com.pam.recap.domain.model.Category
import org.junit.Assert.assertEquals
import org.junit.Test

class MerchantCategoryRulesTest {
    private val rules = MerchantCategoryRules()

    @Test fun `synthetic food merchants classify as food`() {
        for (merchant in listOf("BAKSO UJI", "MIE AYAM UJI", "COFFEE UJI", "KOPI UJI")) {
            assertEquals(merchant, Category.FOOD, rules.classify("Pembayaran QRIS", merchant).category)
        }
    }

    @Test fun `substring collisions do not become housing or utilities`() {
        assertEquals(Category.UNCATEGORIZED, rules.classify("QRIS", "TOKO KOSMETIK UJI").category)
        assertEquals(Category.UNCATEGORIZED, rules.classify("QRIS", "CHAIR STORE UJI").category)
        assertEquals(Category.UNCATEGORIZED, rules.classify("QRIS", "KOPIAH UJI").category)
    }

    @Test fun `punctuation unicode and whitespace normalize without accepting ambiguity`() {
        assertEquals(Category.FOOD, rules.classify("QRIS", "  BAKSO—UJI!!! ").category)
        assertEquals(Category.FOOD, rules.classify("QRIS", "COFFÉE   UJI").category)
        assertEquals(Category.UNCATEGORIZED, rules.classify("QRIS", "QRIS UJI").category)
    }

    @Test fun `specific transfer rule wins over generic words`() {
        assertEquals(Category.TRANSFER, rules.classify("Transfer ke rekening sendiri", "Bank").category)
    }
}
