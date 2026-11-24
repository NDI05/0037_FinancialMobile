package com.pam.recap.domain.repository

import com.pam.recap.domain.model.GoogleSignInResult

/**
 * The only seam between the account UI and Google Credential Manager.
 *
 * Keeping this an interface means JVM/Robolectric tests never need Play Services, the Google
 * account picker, or a real client ID: they inject a fake and assert the surrounding flow. The
 * production implementation never logs, stores or returns the ID token anywhere except the PAM
 * backend call.
 */
interface GoogleSignInGateway {

    /**
     * True when a Web client ID is configured (`PAM_GOOGLE_SSO_CLIENT_ID`). When false the screens
     * must hide the Google entry points entirely instead of showing a dead button.
     */
    val isAvailable: Boolean

    /**
     * Runs the Credential Manager request for [nonce]. The caller obtains the nonce from the PAM
     * backend first, so a replayed Google credential cannot be paired with a stale challenge.
     */
    suspend fun requestIdToken(nonce: String): GoogleSignInResult
}
