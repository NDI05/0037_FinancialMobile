package com.pam.recap.ui.util

import androidx.annotation.StringRes
import com.pam.recap.R
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.ErrorKind

/**
 * B9: the single mapping from a typed [AppError] to user-visible copy. Screens never invent an
 * error message and never render `debugDetail` (NFR-18); they call this mapper.
 *
 * Rule-specific copy is used where the user can act on it (consent, amount, re-auth); everything
 * else falls back to a kind-level message.
 */
@StringRes
fun appErrorRes(error: AppError): Int = when (error) {
    is AppError.Validation -> when (error.rule) {
        com.pam.recap.core.result.AppErrorRules.CONSENT_REQUIRED -> R.string.error_consent_required
        com.pam.recap.core.result.AppErrorRules.AI_CONSENT_WITHDRAWN -> R.string.error_ai_consent_withdrawn
        com.pam.recap.core.result.AppErrorRules.INVALID_AMOUNT -> R.string.error_invalid_amount
        com.pam.recap.core.result.AppErrorRules.MONTH_HAS_NO_DATA -> R.string.error_month_has_no_data
        com.pam.recap.core.result.AppErrorRules.ANALYSIS_REJECTED -> R.string.error_analysis_rejected
        com.pam.recap.core.result.AppErrorRules.ANALYSIS_NOT_ELIGIBLE -> R.string.error_analysis_not_eligible
        com.pam.recap.core.result.AppErrorRules.BUDGET_GAP_NEGATIVE -> R.string.error_budget_gap_negative
        com.pam.recap.core.result.AppErrorRules.REAUTH_REQUIRED -> R.string.error_reauth_required
        com.pam.recap.core.result.AppErrorRules.DELETION_IN_PROGRESS -> R.string.error_deletion_in_progress
        com.pam.recap.core.result.AppErrorRules.DESCRIPTION_REQUIRED -> R.string.error_description_required
        com.pam.recap.core.result.AppErrorRules.REFUND_MUST_BE_CREDIT -> R.string.error_refund_must_be_credit
        com.pam.recap.core.result.AppErrorRules.REFUND_TARGET_MUST_BE_EXPENSE ->
            R.string.error_refund_target_must_be_expense

        com.pam.recap.core.result.AppErrorRules.PLAN_VALUE_NEGATIVE -> R.string.error_plan_value_negative
        com.pam.recap.core.result.AppErrorRules.INVALID_EMAIL -> R.string.error_invalid_email
        com.pam.recap.core.result.AppErrorRules.PASSWORD_TOO_SHORT -> R.string.error_password_too_short
        com.pam.recap.core.result.AppErrorRules.INVALID_CREDENTIALS -> R.string.error_invalid_credentials
        com.pam.recap.core.result.AppErrorRules.INVALID_INVITE -> R.string.error_invalid_invite
        com.pam.recap.core.result.AppErrorRules.EMAIL_TAKEN -> R.string.error_email_taken
        com.pam.recap.core.result.AppErrorRules.IMAP_CREDENTIALS_REQUIRED -> R.string.gmail_credentials_required
        com.pam.recap.core.result.AppErrorRules.NOT_SIGNED_IN -> R.string.error_not_signed_in
        else -> R.string.error_validation
    }

    else -> when (error.kind) {
        ErrorKind.NETWORK -> R.string.error_network
        ErrorKind.UNAUTHORIZED -> R.string.error_unauthorized
        ErrorKind.PERMISSION_REQUIRED -> R.string.error_permission_required
        ErrorKind.CONFIGURATION -> R.string.error_configuration
        ErrorKind.PARSING -> R.string.error_parsing
        ErrorKind.NOT_FOUND -> R.string.error_not_found
        ErrorKind.CONFLICT -> R.string.error_conflict
        ErrorKind.VALIDATION -> R.string.error_validation
        ErrorKind.RATE_LIMITED -> R.string.error_rate_limited
        ErrorKind.STORAGE -> R.string.error_storage
        ErrorKind.UNKNOWN -> R.string.error_unknown
    }
}
