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

    /**
     * FR-50: the analysis quota is exhausted (per-user window or the daily ceiling). Recoverable by
     * waiting, so the UI offers that instead of a failure state.
     */
    data class RateLimited(
        override val debugDetail: String? = null,
        /**
         * Server-provided wait before the same request may be retried (`sync_cooldown`). Null when
         * the response did not carry it; the UI then falls back to generic copy.
         */
        val retryAfterSeconds: Int? = null,
    ) : AppError {
        override val kind: ErrorKind get() = ErrorKind.RATE_LIMITED
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
    RATE_LIMITED,
    STORAGE,
    UNKNOWN,
}

/**
 * Stable rule ids for [AppError.Validation]; keep them in sync with the unit tests.
 */
object AppErrorRules {

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
    const val DESCRIPTION_REQUIRED = "description_required"
    const val REFUND_MUST_BE_CREDIT = "refund_must_be_credit"
    const val REFUND_TARGET_MUST_BE_EXPENSE = "refund_target_must_be_expense"
    const val PLAN_VALUE_NEGATIVE = "plan_value_negative"
    const val INVALID_EMAIL = "invalid_email"
    const val PASSWORD_TOO_SHORT = "password_too_short"
    const val INVALID_CREDENTIALS = "invalid_credentials"
    const val INVALID_INVITE = "invalid_invite"
    const val EMAIL_TAKEN = "email_taken"
    const val IMAP_CREDENTIALS_REQUIRED = "imap_credentials_required"
    const val BROWSER_UNAVAILABLE = "browser_unavailable"
    const val GMAIL_DISABLED = "gmail_disabled"
    const val GMAIL_NOT_CONNECTED = "gmail_not_connected"
    const val SYNC_COOLDOWN = "sync_cooldown"

    // Google SSO (contract: /v1/auth/google*). The backend codes are stable; the UI turns each one
    // into a different next step instead of one generic "sign-in failed".
    const val GOOGLE_SSO_DISABLED = "google_sso_disabled"
    const val GOOGLE_INVALID_TOKEN = "google_invalid_token"
    const val GOOGLE_IDENTITY_NOT_LINKED = "google_identity_not_linked"
    const val GOOGLE_IDENTITY_CONFLICT = "google_identity_conflict"
    const val GOOGLE_ALREADY_LINKED = "google_already_linked"
    const val ACCOUNT_INACTIVE = "account_inactive"
    /** Device-side: Credential Manager was cancelled or unavailable, not a backend refusal. */
    const val GOOGLE_SIGN_IN_CANCELLED = "google_sign_in_cancelled"
    const val GOOGLE_PLAY_SERVICES_UNAVAILABLE = "google_play_services_unavailable"
}
