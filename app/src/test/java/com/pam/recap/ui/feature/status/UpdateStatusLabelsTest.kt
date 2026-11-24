package com.pam.recap.ui.feature.status

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import com.pam.recap.R
import com.pam.recap.core.result.AppError
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.core.time.RetentionWindow
import com.pam.recap.ui.components.UpdateStatusPill
import com.pam.recap.ui.theme.PamTheme
import kotlin.time.Instant
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Short status labels for the one top-bar surface (mandate §6): an import says it is taking history,
 * a category backlog says it is waiting for AI, unresolved rows say they need a review, and a failed
 * run offers exactly one "Coba lagi" action inside the single sheet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h851dp-mdpi")
class UpdateStatusLabelsTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val open = context.getString(R.string.update_status_open)

    private fun spoken(labelRes: Int): String = "$open: ${context.getString(labelRes)}"

    private fun setPill(status: UpdateStatusUi, fontScale: Float = 1f) {
        compose.setContent {
            PamTheme {
                val base = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(base.density, fontScale = fontScale),
                ) {
                    UpdateStatusPill(status = status, onClick = {})
                }
            }
        }
    }

    @Test
    fun `an import in flight reads as taking history`() {
        setPill(UpdateStatusUi(headline = UpdateHeadline.FETCHING))

        compose.onNodeWithContentDescription(spoken(R.string.update_status_indicator_fetching))
            .assertExists()
    }

    @Test
    fun `a category backlog reads as waiting for AI`() {
        setPill(UpdateStatusUi(headline = UpdateHeadline.WAITING))

        compose.onNodeWithContentDescription(spoken(R.string.update_status_indicator_waiting))
            .assertExists()
    }

    @Test
    fun `unresolved rows read as needing review instead of a failure`() {
        setPill(
            UpdateStatusUi(
                headline = UpdateHeadline.NEEDS_ATTENTION,
                attention = listOf(
                    AttentionItem("unresolved_items", AttentionItem.Scope.TRANSACTIONS),
                ),
            ),
        )

        val review = spoken(R.string.update_status_indicator_review)
        compose.onNodeWithContentDescription(
            "$review. ${context.getString(R.string.update_status_cd_review)}",
            substring = true,
        ).assertExists()
    }

    @Test
    fun `a failed run keeps the generic attention label`() {
        setPill(
            UpdateStatusUi(
                headline = UpdateHeadline.NEEDS_ATTENTION,
                attention = listOf(
                    AttentionItem("permission_required", AttentionItem.Scope.TRANSACTIONS),
                ),
            ),
        )

        compose.onNodeWithContentDescription(
            spoken(R.string.update_status_indicator_attention),
            substring = true,
        ).assertExists()
    }

    @Test
    fun `the status sheet has one retry action for a failed run`() {
        compose.setContent {
            PamTheme {
                UpdateStatusSheet(
                    status = UpdateStatusUi(
                        headline = UpdateHeadline.NEEDS_ATTENTION,
                        month = JakartaMonth(2026, 11),
                        syncError = AppError.Network(),
                    ),
                    now = Instant.parse("2026-11-24T06:26:59Z"),
                    onDismiss = {},
                    onSyncNow = {},
                )
            }
        }

        compose.onNodeWithText(context.getString(R.string.update_status_sync_retry))
            .assertIsDisplayed()
    }

    @Test
    fun `the sheet explains the rolling year and the partial boundary month`() {
        compose.setContent {
            PamTheme {
                UpdateStatusSheet(
                    status = UpdateStatusUi(
                        headline = UpdateHeadline.SUCCEEDED,
                        month = JakartaMonth(2026, 11),
                        lastSuccessAt = Instant.parse("2026-11-24T02:00:00Z"),
                    ),
                    now = Instant.parse("2026-11-24T06:26:59Z"),
                    onDismiss = {},
                    onSyncNow = {},
                    retention = RetentionWindow.of(Instant.parse("2026-11-24T06:26:59Z")),
                )
            }
        }

        compose.onNodeWithText(
            context.getString(
                R.string.update_status_retention_window,
                "November 2025",
                "November 2026",
            ),
        ).assertIsDisplayed()
        compose.onNodeWithText(
            context.getString(R.string.update_status_retention_partial, "November 2025"),
        ).assertIsDisplayed()
    }

    @Test
    fun `the sheet survives two hundred percent text on a 360 dp screen`() {
        compose.setContent {
            PamTheme {
                CompositionLocalProvider(
                    LocalDensity provides Density(density = 2.75f, fontScale = 2f),
                ) {
                    UpdateStatusSheet(
                        status = UpdateStatusUi(
                            headline = UpdateHeadline.FETCHING,
                            month = JakartaMonth(2026, 11),
                            syncRunning = true,
                            pagesProcessed = 2,
                            pagesExpected = 5,
                        ),
                        now = Instant.parse("2026-11-24T06:26:59Z"),
                        onDismiss = {},
                        onSyncNow = {},
                        retention = RetentionWindow.of(Instant.parse("2026-11-24T06:26:59Z")),
                    )
                }
            }
        }

        compose.onNodeWithText(context.getString(R.string.update_status_title)).assertIsDisplayed()
        compose.onNodeWithText(
            context.getString(R.string.update_status_sync_progress, 2, 5),
        ).assertIsDisplayed()
    }
}
