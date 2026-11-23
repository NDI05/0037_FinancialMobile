package com.pam.recap.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.Dp
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

/** The pill has to share a top bar with the screen title on every phone width we support. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpdateStatusTopBarTest {

    @get:Rule
    val compose = createComposeRule()

    private val label = ApplicationProvider.getApplicationContext<android.content.Context>()
        .getString(R.string.update_status_indicator_attention)

    private val width = mutableStateOf(360.dp)

    private fun render(widthDp: Dp) {
        width.value = widthDp
        compose.waitForIdle()
    }

    @Test
    fun `the title and the pill both fit at 360_393_412 dp`() {
        compose.setContent {
            PamTheme {
                Box(modifier = Modifier.width(width.value)) {
                    GlassTopBar(title = "Ringkasan") {
                        UpdateStatusPill(
                            status = UpdateStatusUi(headline = UpdateHeadline.NEEDS_ATTENTION),
                            onClick = {},
                        )
                    }
                }
            }
        }

        listOf(360.dp, 393.dp, 412.dp).forEach { widthDp ->
            render(widthDp)
            val width = widthDp

            compose.onNodeWithText("Ringkasan").assertIsDisplayed()
            val pill = compose.onNodeWithContentDescription(label, substring = true)
            pill.assertIsDisplayed()
            val bounds = pill.getUnclippedBoundsInRoot()

            assertTrue(
                "pill must stay inside $width (was ${bounds.right})",
                bounds.right <= width + 0.5.dp,
            )
        }
    }
}
