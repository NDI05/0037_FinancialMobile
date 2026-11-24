package com.pam.recap.ui.feature.status

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.pam.recap.R
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.domain.model.GmailSyncProgress
import com.pam.recap.domain.model.GmailSyncState
import com.pam.recap.ui.theme.PamTheme
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Rendered server-sync section of the "Status pembaruan" sheet (contract §9). Each test pins one
 * contract state and checks the visible copy and affordance; the device-sync sheet must survive
 * untouched whenever Gmail is not linked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h851dp-mdpi")
class UpdateStatusSheetServerSyncTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = Instant.parse("2026-11-24T05:00:00Z")

    private fun setSheet(
        serverSync: GmailServerSyncUi,
        fontScale: Float = 1f,
        onSyncNow: () -> Unit = {},
        onReconnect: () -> Unit = {},
    ) {
        compose.setContent {
            PamTheme {
                val base = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                    UpdateStatusSheet(
                        status = UpdateStatusUi(),
                        now = now,
                        onDismiss = {},
                        serverSync = serverSync,
                        onServerSyncNow = onSyncNow,
                        onReconnectGmail = onReconnect,
                    )
                }
            }
        }
        // The sheet is a ModalBottomSheet: its content enters the semantics tree only once the show
        // animation has settled, which Robolectric does not drive on its own.
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    @Test
    fun `not linked keeps the old device-sync trigger`() {
        setSheet(GmailServerSyncUi.Hidden)

        compose.onNodeWithText(context.getString(R.string.update_status_sync_now))
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.update_status_server_section))
            .assertDoesNotExist()
    }

    @Test
    fun `backfill shows the running state and month progress`() {
        val month = requireNotNull(JakartaMonth.parse("2026-08"))
        setSheet(
            GmailServerSyncUi(
                available = true,
                syncState = GmailSyncState.BACKFILL,
                progress = GmailSyncProgress(
                    phase = "backfill",
                    month = month,
                    monthsDone = 2,
                    monthsTotal = 12,
                    pagesDone = 7,
                ),
                observedAt = now,
            ),
        )

        compose.onNodeWithText(context.getString(R.string.update_status_server_section))
            .assertIsDisplayed()
        // The legacy sheet also labels a device fetch with the same short state word.
        compose.onAllNodesWithText(context.getString(R.string.update_status_server_state_fetching))
            .onFirst()
            .assertIsDisplayed()
        compose.onNodeWithText(
            context.getString(R.string.update_status_server_progress_months, 2, 12),
        ).assertIsDisplayed()
        compose.onNodeWithText(
            context.getString(R.string.update_status_server_progress_month, month.label()),
        ).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.update_status_server_progress_pages, 7))
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.update_status_sync_running))
            .assertHasNoClickAction()
    }

    @Test
    fun `idle server state waits and the trigger enqueues`() {
        var taps = 0
        setSheet(
            GmailServerSyncUi(available = true, syncState = GmailSyncState.IDLE, observedAt = now),
            onSyncNow = { taps++ },
        )

        compose.onAllNodesWithText(context.getString(R.string.update_status_server_state_waiting))
            .onFirst()
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.update_status_sync_now))
            .assertHasClickAction()
            .performClick()
        assertEquals(1, taps)
    }

    @Test
    fun `cooldown copy counts the remaining seconds and blocks the trigger`() {
        setSheet(
            GmailServerSyncUi(
                available = true,
                syncState = GmailSyncState.IDLE,
                notice = GmailSyncNotice.COOLDOWN,
                cooldownUntil = now + 60.seconds,
                observedAt = now,
            ),
        )

        compose.onNodeWithText(context.getString(R.string.update_status_server_cooldown, 60))
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.update_status_sync_now))
            .assertHasNoClickAction()
    }

    @Test
    fun `needs reauth offers the relink CTA and no enqueue`() {
        var reconnects = 0
        setSheet(
            GmailServerSyncUi(available = true, needsReauth = true, observedAt = now),
            onReconnect = { reconnects++ },
        )

        compose.onNodeWithText(context.getString(R.string.update_status_server_reauth))
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.update_status_server_reconnect))
            .assertHasClickAction()
            .performClick()
        assertEquals(1, reconnects)
        compose.onNodeWithText(context.getString(R.string.update_status_sync_now))
            .assertDoesNotExist()
    }

    @Test
    fun `not connected notice explains the refusal`() {
        setSheet(
            GmailServerSyncUi(
                available = true,
                syncState = GmailSyncState.IDLE,
                notice = GmailSyncNotice.NOT_CONNECTED,
                observedAt = now,
            ),
        )
        compose.onNodeWithText(context.getString(R.string.update_status_server_not_connected))
            .assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.update_status_sync_now))
            .assertIsDisplayed()
    }

    @Test
    fun `disabled notice says the feature is off`() {
        setSheet(
            GmailServerSyncUi(
                available = true,
                syncState = GmailSyncState.IDLE,
                notice = GmailSyncNotice.DISABLED,
                observedAt = now,
            ),
        )
        compose.onNodeWithText(context.getString(R.string.update_status_server_disabled))
            .assertIsDisplayed()
    }

    @Test
    fun `queued notice confirms the server job`() {
        setSheet(
            GmailServerSyncUi(
                available = true,
                notice = GmailSyncNotice.QUEUED,
                observedAt = now,
            ),
        )
        compose.onNodeWithText(context.getString(R.string.update_status_server_queued))
            .assertIsDisplayed()
    }

    /**
     * 200 % font on a 360 dp screen: the primary control must still be a full 48 dp target and the
     * copy must stay present (the sheet scrolls; it must not clip the action away).
     */
    @Test
    @Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
    fun `relink CTA keeps a 48 dp target at 200 percent font`() {
        setSheet(
            GmailServerSyncUi(available = true, needsReauth = true, observedAt = now),
            fontScale = 2f,
        )

        val bounds = compose.onNodeWithText(context.getString(R.string.update_status_server_reconnect))
            .assertHasClickAction()
            .fetchSemanticsNode()
            .touchBoundsInRoot
        val minTouchPx = with(compose.density) { 48.dp.toPx() }
        assertTrue("target was ${bounds.height}px", bounds.height >= minTouchPx)
    }
}
