package com.pam.recap.ui.feature.welcome

import androidx.lifecycle.ViewModel
import com.pam.recap.domain.repository.AccountRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * G2: the welcome screen is honest about the demo. The bundled sample data only exists in offline
 * demo mode, so the notice must not be shown to a server-backed installation — it would claim the
 * app runs on sample data when it does not.
 */
@HiltViewModel
class WelcomeViewModel @Inject constructor(
    accounts: AccountRepository,
) : ViewModel() {

    /** True only in the pure offline demo (no backend configured). */
    val showDemoNotice: Boolean = !accounts.isBackendEnabled
}
