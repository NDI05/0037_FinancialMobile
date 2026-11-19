package com.pam.recap.data.gmail

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.pam.recap.BuildConfig
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.core.result.SafeLogger
import com.pam.recap.core.security.OwnerIdFactory
import com.pam.recap.core.util.ActivityHolder
import com.pam.recap.domain.model.AccountIdentity
import com.pam.recap.domain.repository.AccountIdentitySource
import com.pam.recap.domain.repository.IdentityTokenProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Google identity via Credential Manager (FR-01/FR-08).
 *
 * In demo mode (blank `PAM_GOOGLE_WEB_CLIENT_ID`, OI-01) it returns the bundled fixture account so
 * the whole app can run without a Cloud project. Otherwise it performs a real Google ID sign-in and
 * maps the account to an opaque owner id; the raw address only reaches the single `mailboxLabel`
 * column the settings screen needs (FR-49). No credential is logged or persisted by this class.
 */
@Singleton
class GoogleIdentitySource @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val activityHolder: ActivityHolder,
) : AccountIdentitySource, IdentityTokenProvider {

    override val isDemoMode: Boolean get() = BuildConfig.PAM_GOOGLE_WEB_CLIENT_ID.isBlank()

    override suspend fun signIn(): Outcome<AccountIdentity> {
        if (isDemoMode) {
            return Outcome.Success(
                AccountIdentity(
                    accountId = OwnerIdFactory.DEMO_ACCOUNT_ID,
                    displayName = null,
                    mailboxLabel = null,
                    isDemo = true,
                ),
            )
        }

        return when (val credential = googleCredential()) {
            is Outcome.Failure -> Outcome.Failure(credential.error)
            is Outcome.Success -> {
                SafeLogger.event(TAG, "identity_signed_in")
                Outcome.Success(
                    AccountIdentity(
                        accountId = credential.value.googleId,
                        displayName = credential.value.displayName,
                        mailboxLabel = credential.value.googleId,
                        isDemo = false,
                    ),
                )
            }
        }
    }

    /**
     * The backend needs the raw Google ID token to verify who is calling (contract §2.1). It is
     * handed straight to [com.pam.recap.domain.repository.BackendSessionRepository] for a single
     * exchange and is never stored or logged.
     */
    override suspend fun idToken(): Outcome<String> {
        if (isDemoMode) {
            // There is no Google token in demo mode; the backend accepts a development token and
            // the session repository is the only place that knows how to build one.
            return Outcome.Failure(AppError.Configuration("demo_mode_no_google_token"))
        }
        return when (val credential = googleCredential()) {
            is Outcome.Failure -> Outcome.Failure(credential.error)
            is Outcome.Success -> Outcome.Success(credential.value.idToken)
        }
    }

    private suspend fun googleCredential(): Outcome<GoogleCredential> {
        val activity = activityHolder.current
            ?: return Outcome.Failure(AppError.PermissionRequired("no_foreground_activity"))
        return try {
            val manager = CredentialManager.create(activity)
            val option = GetGoogleIdOption.Builder()
                .setServerClientId(BuildConfig.PAM_GOOGLE_WEB_CLIENT_ID)
                .setFilterByAuthorizedAccounts(false)
                .setAutoSelectEnabled(false)
                .build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(option)
                .build()
            val response = manager.getCredential(context = activity, request = request)
            val credential = response.credential
            if (credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                val googleId = GoogleIdTokenCredential.createFrom(credential.data)
                Outcome.Success(
                    GoogleCredential(
                        googleId = googleId.id,
                        displayName = googleId.displayName,
                        idToken = googleId.idToken,
                    ),
                )
            } else {
                Outcome.Failure(AppError.PermissionRequired("unexpected_credential_type"))
            }
        } catch (noCredential: NoCredentialException) {
            // No signed-in Google account on this device yet: actionable, not an unknown error.
            SafeLogger.warning(TAG, "identity_no_credential")
            Outcome.Failure(AppError.PermissionRequired("no_credential"))
        } catch (cancelled: GetCredentialCancellationException) {
            Outcome.Failure(AppError.PermissionRequired("sign_in_cancelled"))
        } catch (error: GetCredentialException) {
            SafeLogger.failure(TAG, "identity_sign_in_failed")
            Outcome.Failure(AppError.PermissionRequired("credential_${error.type}"))
        }
    }

    private data class GoogleCredential(
        val googleId: String,
        val displayName: String?,
        val idToken: String,
    )

    private companion object {
        const val TAG = "GoogleIdentity"
    }
}
