package com.pam.recap.data.google

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.pam.recap.BuildConfig
import com.pam.recap.core.platform.ActivityHost
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.AppErrorRules
import com.pam.recap.core.result.SafeLogger
import com.pam.recap.domain.model.GoogleIdToken
import com.pam.recap.domain.model.GoogleSignInResult
import com.pam.recap.domain.repository.GoogleSignInGateway
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Credential Manager implementation of [GoogleSignInGateway] (contract: Google SSO).
 *
 * The ID token exists only between [CredentialManager] and the PAM backend call: it is never
 * logged, cached, put in a crash report or copied into UI state. The nonce comes from
 * `GET /v1/auth/google/nonce`, so a captured credential cannot be replayed with a stale challenge.
 * No Google password or client secret is ever handled on the device.
 */
@Singleton
class CredentialManagerGoogleSignInGateway @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val activityHost: ActivityHost,
) : GoogleSignInGateway {

    override val isAvailable: Boolean
        get() = BuildConfig.PAM_GOOGLE_SSO_ENABLED && BuildConfig.PAM_GOOGLE_SSO_CLIENT_ID.isNotBlank()

    override suspend fun requestIdToken(nonce: String): GoogleSignInResult {
        if (!isAvailable) {
            return GoogleSignInResult.Failure(
                AppError.Configuration(AppErrorRules.GOOGLE_SSO_DISABLED),
            )
        }
        val activity = activityHost.activity()
            ?: return GoogleSignInResult.Failure(
                AppError.Configuration(AppErrorRules.GOOGLE_PLAY_SERVICES_UNAVAILABLE),
            )
        return try {
            // filterByAuthorizedAccounts=false + autoSelect=false: the user always sees the account
            // picker, so adding a *different* Google account works and nothing is chosen silently.
            val option = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(BuildConfig.PAM_GOOGLE_SSO_CLIENT_ID)
                .setNonce(nonce)
                .setAutoSelectEnabled(false)
                .build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(option)
                .build()
            val credential = CredentialManager.create(context)
                .getCredential(activity, request)
                .credential
            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val google = GoogleIdTokenCredential.createFrom(credential.data)
                GoogleSignInResult.Success(GoogleIdToken(google.idToken))
            } else {
                GoogleSignInResult.Failure(
                    AppError.Configuration(AppErrorRules.GOOGLE_INVALID_TOKEN),
                )
            }
        } catch (cancelled: GetCredentialCancellationException) {
            // Dismissing the Google sheet is a normal outcome, not a failure.
            GoogleSignInResult.Cancelled
        } catch (noAccount: NoCredentialException) {
            // No Google account on this device can satisfy the request (or Play Services is absent).
            // The copy asks the user to add a Google account, so it must not become a crash.
            SafeLogger.failure(TAG, "google_no_credential")
            GoogleSignInResult.Failure(
                AppError.Configuration(AppErrorRules.GOOGLE_PLAY_SERVICES_UNAVAILABLE),
            )
        } catch (error: GetCredentialException) {
            SafeLogger.failure(TAG, "google_credential_failed")
            GoogleSignInResult.Failure(
                AppError.Configuration(AppErrorRules.GOOGLE_PLAY_SERVICES_UNAVAILABLE),
            )
        } catch (error: RuntimeException) {
            SafeLogger.failure(TAG, "google_credential_invalid")
            GoogleSignInResult.Failure(
                AppError.Configuration(AppErrorRules.GOOGLE_PLAY_SERVICES_UNAVAILABLE),
            )
        }
    }

    private companion object {
        const val TAG = "GoogleSignIn"
    }
}
