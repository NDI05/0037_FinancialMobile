package com.pam.recap.domain.model

/** Money direction as reported by the source notification. */
enum class Direction {
    CREDIT,
    DEBIT,
}

/** Provenance of a row: an imported email or a user's manual entry (FR-26). */
enum class TransactionSource {
    EMAIL,
    MANUAL,
}

/** How the category was decided (FR-24): import default, rule, or explicit user choice. */
enum class CategoryOrigin {
    IMPORTED,
    RULE,
    USER,
    LLM,
}

/** Coarse classification used by BR-02 so transfers never pollute income/expense totals. */
enum class ClassifiedKind {
    INCOME,
    EXPENSE,
    TRANSFER,
    UNCATEGORIZED,
}

/** Parse outcome of a single source notification (FR-13). */
enum class ParseStatus {
    POSTED,
    FAILED,
    UNRESOLVED,
}

/**
 * Design-token colour role for a category. Domain stays free of Compose; the UI maps this role
 * to `ui/theme` tokens, so a colour can never be inlined in a feature file.
 */
enum class CategoryColorRole {
    INCOME_MINT,

    /** Deep saturated violet; reserved for the category that must never look like another. */
    VIOLET_DEEP,
    BRAND_VIOLET,
    BRAND_MAGENTA,
    BRAND_PINK,
    VIOLET_SOFT,
    AMBER,
    AMBER_DEEP,
    ROSE,

    /** Two grey steps so a transfer and an "other expense" cannot be confused. */
    SLATE,
    NEUTRAL,
    WARNING,
}

/**
 * FR-23: income, transfer, uncategorized plus eight expense categories. [code] is the stable
 * storage/SQL/JSON identifier; display names live in `strings.xml` and are resolved by the UI.
 */
enum class Category(
    val code: String,
    val kind: ClassifiedKind,
    val colorRole: CategoryColorRole,
) {
    INCOME("income", ClassifiedKind.INCOME, CategoryColorRole.INCOME_MINT),
    HOUSING("housing", ClassifiedKind.EXPENSE, CategoryColorRole.VIOLET_DEEP),
    FOOD("food", ClassifiedKind.EXPENSE, CategoryColorRole.AMBER),
    TRANSPORT("transport", ClassifiedKind.EXPENSE, CategoryColorRole.BRAND_MAGENTA),
    UTILITIES("utilities", ClassifiedKind.EXPENSE, CategoryColorRole.VIOLET_SOFT),
    SHOPPING("shopping", ClassifiedKind.EXPENSE, CategoryColorRole.BRAND_PINK),
    HEALTH("health", ClassifiedKind.EXPENSE, CategoryColorRole.ROSE),
    EDUCATION("education", ClassifiedKind.EXPENSE, CategoryColorRole.AMBER_DEEP),
    OTHER_EXPENSE("other_expense", ClassifiedKind.EXPENSE, CategoryColorRole.NEUTRAL),
    TRANSFER("transfer", ClassifiedKind.TRANSFER, CategoryColorRole.SLATE),
    UNCATEGORIZED("uncategorized", ClassifiedKind.UNCATEGORIZED, CategoryColorRole.WARNING);

    val isExpenseCategory: Boolean get() = kind == ClassifiedKind.EXPENSE

    val isIncome: Boolean get() = kind == ClassifiedKind.INCOME

    val isTransfer: Boolean get() = kind == ClassifiedKind.TRANSFER

    val isUncategorized: Boolean get() = kind == ClassifiedKind.UNCATEGORIZED

    companion object {
        const val CODE_INCOME = "income"
        const val CODE_TRANSFER = "transfer"
        const val CODE_UNCATEGORIZED = "uncategorized"

        /** The eight budgetable expense categories, in display order (FR-42). */
        val expenseCategories: List<Category> = entries.filter { it.isExpenseCategory }

        /** Stable lookup for storage, JSON and query parameters; unknown codes return null. */
        fun fromCode(code: String?): Category? =
            entries.firstOrNull { it.code.equals(code?.trim(), ignoreCase = true) }

        /** Storage-safe fallback: an unknown persisted code degrades to uncategorized. */
        fun fromCodeOrUncategorized(code: String?): Category = fromCode(code) ?: UNCATEGORIZED
    }
}
