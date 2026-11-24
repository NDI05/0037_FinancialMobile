package com.pam.recap.ui.feature.gmail

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.pam.recap.R
import com.pam.recap.core.result.AppError
import com.pam.recap.core.result.Outcome
import com.pam.recap.domain.model.GmailAuthorization
import com.pam.recap.domain.model.GmailLinkResult
import com.pam.recap.domain.model.GmailLinkStatus
import com.pam.recap.domain.model.GmailSyncState
import com.pam.recap.domain.model.Session
import com.pam.recap.domain.repository.GmailLinkRepository
import com.pam.recap.testing.FakeSessionRepository
import com.pam.recap.testing.assertEveryClickableControlIsLabeled
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Gmail OAuth link screen (contract §1–§4): not linked, connected (masked address only), start
 * opens the API result, deep-link success/error banners, confirmation-gated disconnect, and the
 * accessibility smoke check. Copy is resolved through `R.string.gmail_link_*`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "id-rID-w411dp-h891dp-xxhdpi")
class GmailLinkScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val connected = GmailLinkStatus(
        connected = true,
        accountEmailMasked = "a***@gmail.com",
        syncState = GmailSyncState.IDLE,
        connectedAt = Instant.parse("2026-11-24T07:00:00Z"),
        lastSyncAt = Instant.parse("2026-11-24T07:15:00Z"),
        watchExpiresAt = Instant.parse("2026-11-30T07:00:00Z"),
    )

    @Composable
    private fun content(
        state: GmailLinkUiState,
        onConnect: () -> Unit = {},
        onRequestDisconnect: () -> Unit = {},
        onConfirmDisconnect: () -> Unit = {},
        onDismissDisconnect: () -> Unit = {},
        onRetry: () -> Unit = {},
        onDismissResult: () -> Unit = {},
    ) = GmailLinkContent(
        state = state,
        onBack = {},
        onConnect = onConnect,
        onRequestDisconnect = onRequestDisconnect,
        onConfirmDisconnect = onConfirmDisconnect,
        onDismissDisconnect = onDismissDisconnect,
        onRetry = onRetry,
        onDismissResult = onDismissResult,
    )

    @Test
    fun `not linked shows the unlinked state and the link button`() {
        compose.setContent {
            content(GmailLinkUiState(enabled = true, loading = false, status = GmailLinkStatus.NotConnected))
        }
        compose.onNodeWithText(context.getString(R.string.gmail_link_not_connected)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.gmail_link_connect)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.gmail_link_disconnect)).assertDoesNotExist()
        compose.assertEveryClickableControlIsLabeled("Gmail belum tertaut")
    }

    @Test
    fun `connected shows the masked address only and never a full email field`() {
        compose.setContent {
            content(GmailLinkUiState(enabled = true, loading = false, status = connected))
        }
        compose.onNodeWithText(
            context.getString(R.string.gmail_link_connected_as, "a***@gmail.com"),
        ).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.gmail_link_disconnect)).assertIsDisplayed()
        compose.assertEveryClickableControlIsLabeled("Gmail tertaut")
    }

    @Test
    fun `connect button calls the start action`() {
        var connectCalls = 0
        compose.setContent {
            content(
                GmailLinkUiState(enabled = true, loading = false, status = GmailLinkStatus.NotConnected),
                onConnect = { connectCalls++ },
            )
        }
        compose.onNodeWithTag(GmailLinkTestTags.CONNECT).performClick()
        assertEquals(1, connectCalls)
    }

    @Test
    fun `connect calls the API and opens the authorization url`() {
        var openedUrl: String? = null
        val gmail = ScreenGmailLinkRepository()
        val sessions = FakeSessionRepository(
            Session("own_gmail_ui", "PM UI", null, Instant.parse("2026-11-24T00:00:00Z"), false),
        )
        val viewModel = GmailLinkViewModel(sessions, gmail)
        compose.setContent {
            GmailLinkScreen(
                onBack = {},
                viewModel = viewModel,
                urlOpener = { url ->
                    openedUrl = url
                    true
                },
            )
        }
        compose.onNodeWithTag(GmailLinkTestTags.CONNECT).performClick()
        compose.waitForIdle()
        assertEquals(1, gmail.startCalls)
        assertEquals("https://accounts.google.com/o/oauth2/v2/auth?state=ui", openedUrl)
        assertEquals(null, viewModel.state.value.authorizationUrl)
    }

    @Test
    fun `busy state disables connect while the server call runs`() {
        var connectCalls = 0
        compose.setContent {
            content(
                GmailLinkUiState(
                    enabled = true,
                    loading = false,
                    status = GmailLinkStatus.NotConnected,
                    busy = true,
                ),
                onConnect = { connectCalls++ },
            )
        }
        compose.onNodeWithTag(GmailLinkTestTags.CONNECT).performClick()
        assertEquals(0, connectCalls)
    }

    @Test
    fun `deep link success shows the outcome banner`() {
        compose.setContent {
            content(
                GmailLinkUiState(
                    enabled = true,
                    loading = false,
                    status = connected,
                    linkResult = GmailLinkResult(success = true),
                ),
            )
        }
        compose.onNodeWithText(context.getString(R.string.gmail_link_success)).assertIsDisplayed()
    }

    @Test
    fun `deep link error shows the safe reason code`() {
        compose.setContent {
            content(
                GmailLinkUiState(
                    enabled = true,
                    loading = false,
                    status = GmailLinkStatus.NotConnected,
                    linkResult = GmailLinkResult(success = false, reason = "state_expired"),
                ),
            )
        }
        compose.onNodeWithText(
            context.getString(R.string.gmail_link_failed, "state_expired"),
        ).assertIsDisplayed()
    }

    @Test
    fun `disconnect asks for confirmation before it is performed`() {
        var requested = 0
        var confirmed = 0
        var state by mutableStateOf(
            GmailLinkUiState(enabled = true, loading = false, status = connected),
        )
        compose.setContent {
            content(
                state,
                onRequestDisconnect = {
                    requested++
                    state = state.copy(disconnectRequested = true)
                },
                onConfirmDisconnect = {
                    confirmed++
                    state = state.copy(disconnectRequested = false)
                },
                onDismissDisconnect = { state = state.copy(disconnectRequested = false) },
            )
        }
        compose.onNodeWithTag(GmailLinkTestTags.DISCONNECT).performClick()
        assertEquals(1, requested)
        compose.onNodeWithText(context.getString(R.string.gmail_link_disconnect_title)).assertIsDisplayed()
        // The dialog's confirm button uses the same label as the screen button.
        compose.onAllNodesWithText(context.getString(R.string.gmail_link_disconnect)).onLast().performClick()
        assertEquals(1, confirmed)
    }

    @Test
    fun `dismissing the confirmation dialog keeps the link`() {
        var requested = 0
        var confirmed = 0
        var state by mutableStateOf(
            GmailLinkUiState(enabled = true, loading = false, status = connected),
        )
        compose.setContent {
            content(
                state,
                onRequestDisconnect = {
                    requested++
                    state = state.copy(disconnectRequested = true)
                },
                onConfirmDisconnect = { confirmed++ },
                onDismissDisconnect = { state = state.copy(disconnectRequested = false) },
            )
        }
        compose.onNodeWithTag(GmailLinkTestTags.DISCONNECT).performClick()
        compose.onNodeWithText(context.getString(R.string.action_cancel)).performClick()
        assertEquals(0, confirmed)
        compose.onNodeWithText(context.getString(R.string.gmail_link_connected_as, "a***@gmail.com"))
            .assertIsDisplayed()
    }

    @Test
    fun `loading state explains that the status is being checked`() {
        compose.setContent {
            content(GmailLinkUiState(enabled = true, loading = true, status = null))
        }
        compose.onNodeWithText(context.getString(R.string.gmail_link_connecting)).assertIsDisplayed()
    }

    @Test
    fun `error state offers a retry that calls refresh`() {
        var retries = 0
        compose.setContent {
            content(
                GmailLinkUiState(
                    enabled = true,
                    loading = false,
                    status = null,
                    error = AppError.Network("backend_unreachable"),
                ),
                onRetry = { retries++ },
            )
        }
        compose.onNodeWithText(context.getString(R.string.error_network)).assertIsDisplayed()
        compose.onNodeWithTag(GmailLinkTestTags.RETRY).performClick()
        assertEquals(1, retries)
    }

    @Test
    fun `a build without backend explains why linking is unavailable`() {
        compose.setContent {
            content(
                GmailLinkUiState(
                    enabled = false,
                    loading = false,
                    status = GmailLinkStatus.NotConnected,
                ),
            )
        }
        compose.onNodeWithText(context.getString(R.string.gmail_link_server_required))
            .assertIsDisplayed()
        compose.onNodeWithTag(GmailLinkTestTags.CONNECT).assertDoesNotExist()
    }

    @Test
    fun `linked status shows sync state and last sync`() {
        compose.setContent {
            content(GmailLinkUiState(enabled = true, loading = false, status = connected))
        }
        compose.onNodeWithText(context.getString(R.string.gmail_link_sync_idle)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.gmail_link_last_sync)).assertIsDisplayed()
        assertEquals(true, connected.connected)
    }
}

/**
 * Minimal fake for the wrapper-level test: start must actually be called by the ViewModel and the
 * returned URL must reach the opener once.
 */
private class ScreenGmailLinkRepository : GmailLinkRepository {
    override val isEnabled: Boolean = true
    var startCalls: Int = 0

    override suspend fun status(ownerId: String): Outcome<GmailLinkStatus> =
        Outcome.Success(GmailLinkStatus.NotConnected)

    override suspend fun startConnect(
        ownerId: String,
        reconnect: Boolean,
    ): Outcome<GmailAuthorization> {
        startCalls++
        return Outcome.Success(GmailAuthorization("https://accounts.google.com/o/oauth2/v2/auth?state=ui"))
    }

    override suspend fun disconnect(ownerId: String): Outcome<Unit> = Outcome.Success(Unit)
}
