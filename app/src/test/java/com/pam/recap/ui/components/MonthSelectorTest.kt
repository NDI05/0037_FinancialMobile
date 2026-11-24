package com.pam.recap.ui.components

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.pam.recap.R
import com.pam.recap.ui.theme.PamTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Month navigator boundaries (DRAFT-RET-01/02) and its large-text layout.
 *
 * The arrows carry their own descriptions, so a disabled arrow stays discoverable by TalkBack but
 * must report itself as disabled instead of vanishing. The screen width is pinned through the
 * Robolectric qualifier so 360/393/412 dp behave like the owner's reference devices.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h851dp-mdpi")
class MonthSelectorTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val previous = context.getString(R.string.action_previous)
    private val next = context.getString(R.string.action_next)

    private fun setContent(
        width: Dp? = null,
        fontScale: Float = 1f,
        canGoPrevious: Boolean = true,
        canGoNext: Boolean = true,
        partial: Boolean = false,
        onPrevious: () -> Unit = {},
        onNext: () -> Unit = {},
    ) {
        compose.setContent {
            PamTheme {
                val base = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(base.density, fontScale = fontScale),
                ) {
                    Box(modifier = if (width == null) Modifier.fillMaxSize() else Modifier.width(width)) {
                        MonthSelector(
                            label = "November 2026",
                            onPrevious = onPrevious,
                            onNext = onNext,
                            canGoPrevious = canGoPrevious,
                            canGoNext = canGoNext,
                            partial = partial,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `the forward arrow is disabled on the current month`() {
        setContent(canGoNext = false)

        compose.onNodeWithContentDescription(next).assertIsNotEnabled()
        compose.onNodeWithContentDescription(previous).assertIsEnabled()
    }

    @Test
    fun `the back arrow is disabled on the oldest available month`() {
        setContent(canGoPrevious = false, canGoNext = true)

        compose.onNodeWithContentDescription(previous).assertIsNotEnabled()
        compose.onNodeWithContentDescription(next).assertIsEnabled()
    }

    @Test
    fun `enabled arrows still navigate`() {
        var back = 0
        var forward = 0
        setContent(onPrevious = { back++ }, onNext = { forward++ })

        compose.onNodeWithContentDescription(previous)
            .assertIsEnabled()
            .performClick()
        compose.onNodeWithContentDescription(next)
            .assertIsEnabled()
            .performClick()

        assertEquals(1, back)
        assertEquals(1, forward)
    }

    @Test
    fun `the boundary month is labelled as partial`() {
        setContent(canGoPrevious = false, partial = true)

        compose.onNodeWithText(context.getString(R.string.month_selector_partial)).assertIsDisplayed()
        compose.onNodeWithText("November 2026").assertIsDisplayed()
    }

    @Test
    fun `a non-partial month carries no extra label`() {
        setContent(partial = false)

        compose.onNodeWithText(context.getString(R.string.month_selector_partial)).assertDoesNotExist()
    }

    @Test
    fun `two hundred percent text at 360, 393 and 412 dp still renders`() {
        var width by mutableStateOf(360.dp)
        compose.setContent {
            PamTheme {
                val base = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(base.density, fontScale = 2f),
                ) {
                    Box(modifier = Modifier.width(width)) {
                        MonthSelector(
                            label = "November 2026",
                            onPrevious = {},
                            onNext = {},
                            canGoPrevious = true,
                            canGoNext = false,
                            partial = true,
                        )
                    }
                }
            }
        }

        for (dp in listOf(360.dp, 393.dp, 412.dp)) {
            width = dp
            compose.waitForIdle()

            compose.onNodeWithText("November 2026").assertIsDisplayed()
            compose.onNodeWithContentDescription(previous).assertIsDisplayed()
            compose.onNodeWithContentDescription(next).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.month_selector_partial))
                .assertIsDisplayed()

            val arrow = compose.onNodeWithContentDescription(previous)
            val bounds = arrow.getUnclippedBoundsInRoot()
            assertTrue("arrow exceeds ${dp.value}dp screen", bounds.right.value <= dp.value)
            // The visual well may stay 44 dp; the touch target must still reach 48 dp.
            val touch = arrow.fetchSemanticsNode().touchBoundsInRoot
            with(compose.density) {
                assertTrue("touch target narrower than 48 dp", touch.width.toDp() >= 48.dp)
                assertTrue("touch target shorter than 48 dp", touch.height.toDp() >= 48.dp)
            }
        }
    }
}
