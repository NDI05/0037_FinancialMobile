package com.pam.recap.ui.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MarkEmailRead
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pam.recap.R
import com.pam.recap.ui.components.PillButton
import com.pam.recap.ui.components.PillButtonStyle
import com.pam.recap.ui.components.SheetScaffold
import com.pam.recap.ui.theme.BodyM
import com.pam.recap.ui.theme.LabelM
import com.pam.recap.ui.theme.OnInk
import com.pam.recap.ui.theme.OnInkMuted
import com.pam.recap.ui.theme.PamSpacing
import com.pam.recap.ui.theme.TitleL

/** Test tags for the one-time Gmail onboarding sheet. */
object GmailOnboardingTestTags {
    const val SHEET = "gmail_onboarding_sheet"
    const val LINK = "gmail_onboarding_link"
    const val SKIP = "gmail_onboarding_skip"
}

/**
 * One-time offer, shown after login/SSO/refresh when Gmail is not connected and this owner has not
 * skipped it: "Tautkan Gmail untuk impor Livin".
 *
 * It explains exactly what the link does (Livin emails, 12 months, sync every 15 minutes while the
 * backend is alive), that AI consent is separate, and that skipping is fine. Both actions close the
 * sheet; only "Lewati" is remembered. Gmail unlinked/revoked never blocks the app.
 */
@Composable
fun GmailOnboardingSheet(
    onLinkGmail: () -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GmailOnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (!state.visible) return

    SheetScaffold(onDismissRequest = {
        viewModel.dismissForNow()
        onDismiss()
    }) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .testTag(GmailOnboardingTestTags.SHEET),
            verticalArrangement = Arrangement.spacedBy(PamSpacing.sm),
        ) {
            Text(
                text = stringResource(R.string.gmail_onboarding_title),
                style = TitleL,
                color = OnInk,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(R.string.gmail_onboarding_body),
                style = BodyM,
                color = OnInkMuted,
            )
            Text(
                text = stringResource(R.string.gmail_onboarding_ai_note),
                style = LabelM,
                color = OnInkMuted,
            )
            Spacer(modifier = Modifier.height(PamSpacing.xs))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(PamSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PillButton(
                    text = stringResource(R.string.gmail_onboarding_link),
                    onClick = {
                        viewModel.markConnected()
                        onLinkGmail()
                    },
                    leadingIcon = Icons.Rounded.MarkEmailRead,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(GmailOnboardingTestTags.LINK),
                )
                PillButton(
                    text = stringResource(R.string.gmail_onboarding_skip),
                    onClick = {
                        viewModel.skip()
                        onSkip()
                    },
                    style = PillButtonStyle.Ghost,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(GmailOnboardingTestTags.SKIP),
                )
            }
            Text(
                text = stringResource(R.string.gmail_onboarding_skip_hint),
                style = LabelM,
                color = OnInkMuted,
            )
        }
    }
}
