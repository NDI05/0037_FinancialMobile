package com.pam.recap.ui.feature.account

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.pam.recap.R
import com.pam.recap.testing.FakeAccountRepository
import com.pam.recap.testing.FakeGoogleSignInGateway
import com.pam.recap.testing.FakeSessionRepository
import com.pam.recap.ui.theme.PamTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Google SSO entry points on the account screen: the primary "Masuk dengan Google" button appears
 * only when a client ID is configured, and the email/password form stays visible next to it.
 * Copy is resolved through `R.string.*` so the test is locale-safe.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h851dp-mdpi")
class AccountSsoComposeTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun text(id: Int): String = context.getString(id)

    private fun viewModel(
        accounts: FakeAccountRepository,
        gateway: FakeGoogleSignInGateway,
    ) = AccountViewModel(accounts, FakeSessionRepository(), gateway, SavedStateHandle())

    @Test
    fun `google button is shown next to the email form when a client id is configured`() {
        val accounts = FakeAccountRepository(isBackendEnabled = true, googleSsoEnabled = true)
        val viewModel = viewModel(accounts, FakeGoogleSignInGateway())

        compose.setContent {
            PamTheme { AccountScreen(onBack = {}, viewModel = viewModel) }
        }

        compose.onNodeWithTag(AccountTestTags.GOOGLE_SIGN_IN).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.account_google_sign_in)).assertIsDisplayed()
        // The email door is never replaced by the Google door.
        compose.onNodeWithText(text(R.string.account_email_label)).assertIsDisplayed()
    }

    @Test
    fun `google button is offered in register mode above the email form`() {
        val accounts = FakeAccountRepository(isBackendEnabled = true, googleSsoEnabled = true)
        val viewModel = AccountViewModel(
            accounts,
            FakeSessionRepository(),
            FakeGoogleSignInGateway(),
            SavedStateHandle(mapOf("mode" to AccountMode.REGISTER.name)),
        )

        compose.setContent {
            PamTheme { AccountScreen(onBack = {}, viewModel = viewModel) }
        }

        compose.onNodeWithTag(AccountTestTags.GOOGLE_SIGN_IN).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.account_google_register)).assertIsDisplayed()
        // The email form is still there: Google is a second door, not a replacement.
        compose.onNodeWithText(text(R.string.account_email_label)).assertIsDisplayed()
    }

    @Test
    fun `google button is absent without a configured client id`() {
        val accounts = FakeAccountRepository(isBackendEnabled = true, googleSsoEnabled = true)
        val viewModel = viewModel(accounts, FakeGoogleSignInGateway(isAvailable = false))

        compose.setContent {
            PamTheme { AccountScreen(onBack = {}, viewModel = viewModel) }
        }

        compose.onNodeWithTag(AccountTestTags.GOOGLE_SIGN_IN).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.account_email_label)).assertIsDisplayed()
    }
}
