package com.pam.recap.core.result

/**
 * Typed failure taxonomy. The `code`/`rule` strings are technical identifiers used for logging
 * and analytics, never shown to the user: the UI maps [ErrorKind] to a `strings.xml` message
 * (hard rule 2).
 *
 * [debugDetail] must stay PII-free — no body, subject, sender, mailbox, token, PAN, merchant or
 * message id (NFR-18).
 */
sealed interface AppError {

    val kind: ErrorKind

    val debugDetail: String?

    data class Network(override val debugDetail: String? = null) : AppError {
        override val kind: ErrorKind get() = ErrorKind.NETWORK
    }

    /** 401/403 from the provider: stop retrying and ask for reauthorisation (NFR-05, FR-21). */
    data class Unauthorized(override val debugDetail: String? = null) : AppError {
        override val kind: ErrorKind get() = ErrorKind.UNAUTHORIZED
    }

    /** Consent or OAuth scope is missing; recoverable by the user re-connecting (FR-07, FR-10). */
    data class PermissionRequired(override val debugDetail: String? = null) : AppError {
        override val kind: ErrorKind get() = ErrorKind.PERMISSION_REQUIRED
    }

    /** Missing/incomplete build configuration, e.g. blank OAuth client id (FR-10). */
    data class Configuration(override val debugDetail: String? = null) : AppError {
        override val kind: ErrorKind get() = ErrorKind.CONFIGURATION
    }

    data class Parsing(override val debugDetail: String? = null) : AppError {
        override val kind: ErrorKind get() = ErrorKind.PARSING
    }

    data class NotFound(override val debugDetail: String? = null) : AppError {
        override val kind: ErrorKind get() = ErrorKind.NOT_FOUND
    }

    data class Conflict(override val debugDetail: String? = null) : AppError {
        override val kind: ErrorKind get() = ErrorKind.CONFLICT
    }

    /** A domain/business rule rejected the request; [rule] is a stable identifier. */
    data class Validation(val rule: String) : AppError {
        override val kind: ErrorKind get() = ErrorKind.VALIDATION
        override val debugDetail: String? get() = rule
    }

    data class Storage(override val debugDetail: String? = null) : AppError {
        override val kind: ErrorKind get() = ErrorKind.STORAGE
    }

    data class Unknown(override val debugDetail: String? = null) : AppError {
        override val kind: ErrorKind get() = ErrorKind.UNKNOWN
    }

    companion object {
        /** Stable rule ids for [Validation]; keep them in sync with the unit tests. */
        object Rules {
            const val NOT_SIGNED_IN = "not_signed_in"
            const val CONSENT_REQUIRED = "consent_required"
            const val AI_CONSENT_WITHDRAWN = "ai_consent_withdrawn"
            const val INVALID_AMOUNT = "invalid_amount"
            const val INVALID_DATE = "invalid_date"
            const val MONTH_HAS_NO_DATA = "month_has_no_data"
            const val ANALYSIS_NOT_ELIGIBLE = "analysis_not_eligible"
            const val ANALYSIS_REJECTED = "analysis_rejected"
            const val BUDGET_GAP_NEGATIVE = "budget_gap_negative"
            const val DELETION_IN_PROGRESS = "deletion_in_progress"
            const val REAUTH_REQUIRED = "reauth_required"
        }
    }
}

enum class ErrorKind {
    NETWORK,
    UNAUTHORIZED,
    PERMISSION_REQUIRED,
    CONFIGURATION,
    PARSING,
    NOT_FOUND,
    CONFLICT,
    VALIDATION,
    STORAGE,
    UNKNOWN,
}
