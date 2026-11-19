package com.pam.recap.domain.usecase

import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.CategoryOrigin
import javax.inject.Inject
import javax.inject.Singleton

/** Result of rule-based categorization; confidence drives the low-confidence marker (FR-24). */
data class CategoryAssignment(
    val category: Category,
    val origin: CategoryOrigin,
    val confidence: Float,
)

/**
 * Deterministic keyword rules for imported rows (FR-23/FR-24).
 *
 * Rules live in data, are ordered from most specific to least, and always produce a confidence so
 * the UI can mark uncertain rows for review instead of presenting a guess as fact. Unmatched rows
 * become [Category.UNCATEGORIZED] with a low confidence — never a silent best guess.
 */
@Singleton
class MerchantCategoryRules @Inject constructor() {

    fun classify(description: String, merchantLabel: String?): CategoryAssignment {
        val haystack = "${description} ${merchantLabel.orEmpty()}".lowercase()
        val match = RULES.firstOrNull { rule -> rule.keywords.any { haystack.contains(it) } }
        return if (match != null) {
            CategoryAssignment(match.category, CategoryOrigin.RULE, match.confidence)
        } else {
            CategoryAssignment(Category.UNCATEGORIZED, CategoryOrigin.RULE, UNMATCHED_CONFIDENCE)
        }
    }

    private data class Rule(
        val category: Category,
        val keywords: List<String>,
        val confidence: Float,
    )

    private companion object {
        const val UNMATCHED_CONFIDENCE = 0.35f

        /** Ordered: the first matching rule wins. */
        val RULES: List<Rule> = listOf(
            Rule(Category.TRANSFER, listOf("rekening sendiri", "antar rekening", "transfer ke rekening"), 0.95f),
            Rule(
                Category.HOUSING,
                listOf("apartemen", "apartment", "sewa", "kos", "properti", "iuran lingkungan"),
                0.9f,
            ),
            Rule(
                Category.UTILITIES,
                listOf("pln", "token listrik", "telkomsel", "indihome", "pam", "listrik", "internet", "air"),
                0.9f,
            ),
            Rule(
                Category.TRANSPORT,
                listOf("gojek", "grab", "transjakarta", "parkir", "pertamina", "bensin", "taksi", "kereta"),
                0.9f,
            ),
            Rule(
                Category.HEALTH,
                listOf("apotek", "klinik", "rumah sakit", "dokter", "gigi", "laboratorium"),
                0.9f,
            ),
            Rule(
                Category.EDUCATION,
                listOf("udemy", "kursus", "buku", "gramedia", "sekolah", "kampus", "pelatihan"),
                0.85f,
            ),
            Rule(
                Category.SHOPPING,
                listOf("tokopedia", "shopee", "headset", "sepatu", "alfamart", "indomaret", "belanja"),
                0.85f,
            ),
            Rule(
                Category.FOOD,
                listOf("kopi", "warung", "resto", "bakmi", "es teh", "makan", "kafe", "cafe", "kuliner"),
                0.9f,
            ),
            Rule(
                Category.OTHER_EXPENSE,
                listOf("donasi", "masjid", "yayasan", "bioskop", "hiburan", "hadiah"),
                0.8f,
            ),
        )
    }
}
