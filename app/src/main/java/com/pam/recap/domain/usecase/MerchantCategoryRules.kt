package com.pam.recap.domain.usecase

import com.pam.recap.domain.model.Category
import com.pam.recap.domain.model.CategoryOrigin
import java.text.Normalizer
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Result of rule-based categorization; confidence drives the low-confidence marker (FR-24). */
data class CategoryAssignment(
    val category: Category,
    val origin: CategoryOrigin,
    val confidence: Float,
)

/**
 * Deterministic merchant/phrase rules for imported rows (FR-23/FR-24).
 *
 * Matching is normalized (NFKD, diacritics, case, punctuation, whitespace) and bounded by whole
 * tokens, so `kosmetik` is not `kos`, `chair` is not `air`, and `kopiah` is not `kopi`. Rules are
 * ordered from specific to general; no merchant is special-cased and QRIS alone never implies food.
 */
@Singleton
class MerchantCategoryRules @Inject constructor() {

    fun classify(description: String, merchantLabel: String?): CategoryAssignment {
        val tokens = tokenize("${description} ${merchantLabel.orEmpty()}")
        val match = RULES.firstOrNull { rule ->
            rule.phrases.any { phrase -> containsPhrase(tokens, phrase) }
        }
        return if (match != null) {
            CategoryAssignment(match.category, CategoryOrigin.RULE, match.confidence)
        } else {
            CategoryAssignment(Category.UNCATEGORIZED, CategoryOrigin.RULE, UNMATCHED_CONFIDENCE)
        }
    }

    private fun tokenize(value: String): List<String> {
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
        return normalized.split(' ').filter { it.isNotEmpty() }
    }

    private fun containsPhrase(tokens: List<String>, phrase: String): Boolean {
        val wanted = tokenize(phrase)
        if (wanted.isEmpty() || wanted.size > tokens.size) return false
        return (0..tokens.size - wanted.size).any { start ->
            wanted.indices.all { offset -> tokens[start + offset] == wanted[offset] }
        }
    }

    private data class Rule(
        val category: Category,
        val phrases: List<String>,
        val confidence: Float,
    )

    companion object {
        const val UNMATCHED_CONFIDENCE = 0.35f
        const val RULE_VERSION = "merchant-rules/2"

        /** Ordered: the first matching rule wins. Keep specific phrases before broad ones. */
        private val RULES: List<Rule> = listOf(
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
                listOf(
                    "bakso", "mie ayam", "mi ayam", "coffee", "kopi", "warung", "resto",
                    "bakmi", "es teh", "makanan", "minuman", "makan", "kafe", "cafe",
                    "kuliner", "nasi", "sate", "martabak", "roti", "kue",
                ),
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
