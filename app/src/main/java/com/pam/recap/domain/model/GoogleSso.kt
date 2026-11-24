package com.pam.recap.domain.model

/**
 * Google SSO models (backend contract: `GET /v1/auth/google/nonce`, `POST /v1/auth/google`,
 * `POST /v1/auth/google/link`).
 *
 * Nothing here ever carries the Google client secret: Android only receives a short-lived ID token
 * from Credential Manager and hands it to the PAM backend, which verifies it.
 */
data class GoogleNonce(
    val nonce: String,
    /** Raw ISO-8601 instant from the backend; kept as a string so a clock skew cannot lose it. */
    val expiresAt: String? = null,
    val expiresInSeconds: Long? = null,
)

/**
 * Result of a successful `POST /v1/auth/google`. The session tokens are already wrapped and stored
 * by the backend session repository; this type only tells the identity layer which local profile to
 * create (the same path email/password sign-in uses).
 */
data class GoogleSignInSession(
    val email: String,
    val displayName: String? = null,
    val googleLinked: Boolean = true,
    /** Server `user.ownerId` when the backend reports it; the local profile still uses the email. */
    val serverOwnerId: String? = null,
)

/** One ID token returned by Credential Manager. */
data class GoogleIdToken(val value: String)

/**
 * Why a Google Credential Manager request ended. Cancellation is deliberately **not** an error:
 * dismissing the Google sheet must leave the account screen idle, never show a failure banner.
 */
sealed interface GoogleSignInResult {
    data class Success(val token: GoogleIdToken) : GoogleSignInResult

    /** The user closed the Google sheet or pressed back. */
    data object Cancelled : GoogleSignInResult

    /**
     * Credential Manager could not produce an ID token (no Play Services, no eligible account,
     * configuration problem). [error] is already mapped to the app taxonomy and never contains the
     * token.
     */
    data class Failure(val error: com.pam.recap.core.result.AppError) : GoogleSignInResult
}
