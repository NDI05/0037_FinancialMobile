package com.pam.recap.data.backend

import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.AppErrorRules
import kotlinx.serialization.Serializable

/**
 * HTTP → [AppError] mapping for the backend (contract §3). The body is parsed only for its
 * machine-readable `code`; nothing from the body, the token or the URL is ever logged (NFR-18).
 */
@Serializable
internal data class BackendErrorBody(
    val code: String? = null,
    val message: String? = null,
)

internal object BackendErrorCodes {
    const val CONSENT_REQUIRED = "consent_required"
    const val DELETION_IN_PROGRESS = "deletion_in_progress"
    const val INVALID_ANALYSIS_OUTPUT = "invalid_analysis_output"
    const val RATE_LIMITED = "rate_limited"
    const val DAILY_CEILING_REACHED = "daily_ceiling_reached"

    /** Every Gmail route answers this when the server ships with `GMAIL_ENABLED=false`. */
    const val GMAIL_DISABLED = "gmail_disabled"
}

/**
 * Maps one backend response to the typed taxonomy. `debugDetail` stays a technical identifier
 * (`backend_401`, `rate_limited`), never a body fragment.
 */
internal fun backendError(status: Int, code: String?): AppError = when {
    status == 401 -> AppError.Unauthorized("backend_401")
    status == 403 && code == BackendErrorCodes.CONSENT_REQUIRED ->
        AppError.PermissionRequired(BackendErrorCodes.CONSENT_REQUIRED)
    status == 403 -> AppError.Unauthorized("backend_403")
    status == 404 -> AppError.NotFound("backend_404")
    status == 409 && code == BackendErrorCodes.DELETION_IN_PROGRESS ->
        AppError.Validation(AppErrorRules.DELETION_IN_PROGRESS)
    status == 409 -> AppError.Conflict("backend_409")
    status == 422 -> AppError.Validation(AppErrorRules.ANALYSIS_REJECTED)
    status == 429 -> AppError.RateLimited(
        code ?: BackendErrorCodes.RATE_LIMITED,
    )
    status == 400 -> AppError.Validation(AppErrorRules.ANALYSIS_REJECTED)
    // A runtime-disabled Gmail integration is not a transient outage: the screen needs to say the
    // server has not enabled linking instead of offering a connect button that cannot work.
    status == 503 && code == BackendErrorCodes.GMAIL_DISABLED ->
        AppError.Validation(AppErrorRules.GMAIL_DISABLED)
    status >= 500 -> AppError.Network("backend_$status")
    else -> AppError.Network("backend_$status")
}
