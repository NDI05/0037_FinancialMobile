package com.pam.recap.di

import com.pam.recap.data.google.CredentialManagerGoogleSignInGateway
import com.pam.recap.domain.repository.GoogleSignInGateway
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Google SSO wiring. The production adapter is always bound; it reports `isAvailable=false` when
 * `PAM_GOOGLE_SSO_CLIENT_ID` is blank, which is what hides the Google entry points. Tests inject a
 * fake instead and never touch Play Services.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class GoogleSsoModule {

    @Binds
    @Singleton
    abstract fun bindGoogleSignInGateway(
        impl: CredentialManagerGoogleSignInGateway,
    ): GoogleSignInGateway
}
