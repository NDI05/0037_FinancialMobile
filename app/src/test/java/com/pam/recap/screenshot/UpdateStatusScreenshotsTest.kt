package com.pam.recap.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.pam.recap.ui.components.UpdateStatusPill
import com.pam.recap.ui.feature.status.AttentionItem
import com.pam.recap.ui.feature.status.UpdateHeadline
import com.pam.recap.ui.feature.status.UpdateStatusSheet
import com.pam.recap.ui.feature.status.UpdateStatusUi
import com.pam.recap.core.result.AppError
import com.pam.recap.core.time.JakartaMonth
import com.pam.recap.domain.model.CategoryQueuePhase
import com.pam.recap.ui.theme.Ink
import com.pam.recap.ui.theme.PamTheme
import com.pam.recap.ui.theme.PamSpacing
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * One deterministic image per user-visible status, so the states do not depend on catching a
 * background run at the right second. Recording: `-Proborazzi.test.record=true`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
class UpdateStatusScreenshotsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val now = Instant.parse("2026-11-23T02:00:00Z")

    /**
     * One composition, many images: Compose only allows setContent once per test, so the state is
     * swapped between captures instead of re-rendering from scratch.
     */
    private fun <T> shots(
        items: List<Pair<String, T>>,
        fontScale: Float = 1f,
        content: @Composable (T) -> Unit,
    ) {
        val current = mutableStateOf(items.first().second)
        composeRule.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                PamTheme {
                    Box(modifier = Modifier.fillMaxSize().background(Ink).padding(PamSpacing.md)) {
                        content(current.value)
                    }
                }
            }
        }
        items.forEach { (name, value) ->
            current.value = value
            composeRule.waitForIdle()
            composeRule.onRoot().captureRoboImage("build/screenshots/status/$name.png")
        }
    }

    private fun pill(headline: UpdateHeadline) = UpdateStatusUi(
        headline = headline,
        lastAttemptAt = now - 2.minutes,
        lastSuccessAt = now - 12.minutes,
    )

    @Test
    fun `pill states`() {
        val states = listOf(
            "01-idle" to pill(UpdateHeadline.IDLE),
            "02-checking" to pill(UpdateHeadline.CHECKING),
            "03-saving" to pill(UpdateHeadline.SAVING),
            "04-categorizing" to pill(UpdateHeadline.CATEGORIZING),
            "05-waiting" to pill(UpdateHeadline.WAITING),
            "06-succeeded" to pill(UpdateHeadline.SUCCEEDED),
            "07-offline" to pill(UpdateHeadline.OFFLINE),
            "08-attention" to pill(UpdateHeadline.NEEDS_ATTENTION),
        )
        shots(states) { status ->
            Column(modifier = Modifier.width(200.dp)) {
                UpdateStatusPill(status = status, onClick = {})
            }
        }
    }

    @Test
    fun `sheet states`() {
        val sheets = listOf(
            "10-sheet-success" to UpdateStatusUi(
                headline = UpdateHeadline.SUCCEEDED,
                lastAttemptAt = now - 2.minutes,
                lastSuccessAt = now - 12.minutes,
                newTransactionsLastRun = 3,
                hasEverSucceeded = true,
            ),
            "11-sheet-waiting-budget" to UpdateStatusUi(
                headline = UpdateHeadline.WAITING,
                lastAttemptAt = now - 2.minutes,
                lastSuccessAt = now - 45.minutes,
                categoryPhase = CategoryQueuePhase.PAUSED,
                categoryPending = 7,
                categoryNextDueAt = now + 90.minutes,
                categoryLastDrained = 5,
                attention = listOf(AttentionItem("budget_exhausted", AttentionItem.Scope.CATEGORIES)),
                hasEverSucceeded = true,
            ),
            "12-sheet-offline" to UpdateStatusUi(
                headline = UpdateHeadline.OFFLINE,
                online = false,
                lastAttemptAt = now - 3.minutes,
                lastSuccessAt = now - 26.minutes,
                pendingUpload = true,
                hasEverSucceeded = true,
            ),
            "16-sheet-running-progress" to UpdateStatusUi(
                headline = UpdateHeadline.FETCHING,
                month = JakartaMonth.of(now),
                lastAttemptAt = now,
                lastSuccessAt = now - 30.minutes,
                syncRunning = true,
                syncAttempt = 2,
                pagesProcessed = 2,
                pagesExpected = 6,
                hasEverSucceeded = true,
            ),
            "17-sheet-running-unknown-total" to UpdateStatusUi(
                headline = UpdateHeadline.SAVING,
                month = JakartaMonth.of(now),
                lastAttemptAt = now,
                syncRunning = true,
                syncAttempt = 1,
                pagesProcessed = 1,
                pendingUpload = true,
                hasEverSucceeded = true,
            ),
            "18-sheet-failed-retry" to UpdateStatusUi(
                headline = UpdateHeadline.NEEDS_ATTENTION,
                month = JakartaMonth.of(now),
                lastAttemptAt = now,
                syncError = AppError.Network("io"),
                attention = listOf(AttentionItem("network", AttentionItem.Scope.TRANSACTIONS)),
                hasEverSucceeded = true,
            ),
            "19-sheet-conflict" to UpdateStatusUi(
                headline = UpdateHeadline.CHECKING,
                month = JakartaMonth.of(now),
                lastAttemptAt = now,
                syncConflict = true,
                hasEverSucceeded = true,
            ),
            "13-sheet-attention" to UpdateStatusUi(
                headline = UpdateHeadline.NEEDS_ATTENTION,
                lastAttemptAt = now - 3.minutes,
                attention = listOf(AttentionItem("unauthorized", AttentionItem.Scope.TRANSACTIONS)),
            ),
        )
        shots(sheets) { status ->
            UpdateStatusSheet(status = status, now = now, onDismiss = {})
        }
    }

    @Test
    fun `large text sheet keeps every line readable`() {
        val sheets = listOf(
            "14-sheet-success-200pct" to UpdateStatusUi(
                headline = UpdateHeadline.SUCCEEDED,
                lastAttemptAt = now - 2.minutes,
                lastSuccessAt = now - 12.minutes,
                newTransactionsLastRun = 3,
                hasEverSucceeded = true,
            ),
            "15-sheet-waiting-budget-200pct" to UpdateStatusUi(
                headline = UpdateHeadline.WAITING,
                lastAttemptAt = now - 2.minutes,
                lastSuccessAt = now - 45.minutes,
                categoryPhase = CategoryQueuePhase.PAUSED,
                categoryPending = 7,
                categoryNextDueAt = now + 90.minutes,
                attention = listOf(AttentionItem("budget_exhausted", AttentionItem.Scope.CATEGORIES)),
                hasEverSucceeded = true,
            ),
        )
        shots(sheets, fontScale = 2f) { status ->
            UpdateStatusSheet(status = status, now = now, onDismiss = {})
        }
    }
}
