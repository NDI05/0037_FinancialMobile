package com.pam.recap.ui.components

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.pam.recap.R
import com.pam.recap.ui.feature.status.UpdateHeadline
import com.pam.recap.ui.feature.status.UpdateStatusUi
import com.pam.recap.ui.theme.PamTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The indicator must stay usable when the system font is doubled: the word may disappear, the state
 * and the touch target may not.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateStatusPillTest {

    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val label = context.getString(R.string.update_status_indicator_attention)
    private val spoken = context.getString(R.string.update_status_open) + ": " + label

    private fun setContent(fontScale: Float, onOpen: () -> Unit = {}) {
        compose.setContent {
            PamTheme {
                CompositionLocalProvider(LocalDensity provides Density(density = 2.75f, fontScale = fontScale)) {
                    UpdateStatusPill(
                        status = UpdateStatusUi(headline = UpdateHeadline.NEEDS_ATTENTION),
                        onClick = onOpen,
                    )
                }
            }
        }
    }

    @Test
    fun `the label is visible at the default font size and the target is 48 dp`() {
        var opened = false
        setContent(fontScale = 1f) { opened = true }

        compose.onNodeWithText(label).assertExists()
        compose.onNodeWithContentDescription(spoken)
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        assertTrue(opened)
    }

    @Test
    fun `at two hundred percent the word hides but the state stays announced`() {
        setContent(fontScale = 2f)

        compose.onNodeWithText(label).assertDoesNotExist()
        compose.onNodeWithContentDescription(spoken)
            .assertHeightIsAtLeast(48.dp)
    }
}
