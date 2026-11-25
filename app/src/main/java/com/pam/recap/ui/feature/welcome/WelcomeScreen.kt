package com.pam.recap.ui.feature.welcome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pam.recap.R
import com.pam.recap.ui.components.PamLogoMark
import com.pam.recap.ui.components.PamScaffold
import com.pam.recap.ui.components.PillButton
import com.pam.recap.ui.components.PillButtonStyle
import com.pam.recap.ui.theme.Accent
import com.pam.recap.ui.theme.BodyM
import com.pam.recap.ui.theme.BodyS
import com.pam.recap.ui.theme.DisplayXl
import com.pam.recap.ui.theme.LabelM
import com.pam.recap.ui.theme.OnAccent
import com.pam.recap.ui.theme.OnInk
import com.pam.recap.ui.theme.OnInkFaint
import com.pam.recap.ui.theme.OnInkMuted
import com.pam.recap.ui.theme.OutlineVariant
import com.pam.recap.ui.theme.PamRadius
import com.pam.recap.ui.theme.PamSpacing
import com.pam.recap.ui.theme.Surface
import com.pam.recap.ui.theme.TitleM

/**
 * §8 Welcome: brand mark, tagline, feature card and account CTAs — Stitch layout.
 *
 * [showDemoNotice] shows the "mode demo aktif" hint when no backend is configured.
 */
@Composable
fun WelcomeScreen(
    onSignIn: () -> Unit,
    onCreateAccount: () -> Unit,
    onOpenPrivacy: () -> Unit,
    showDemoNotice: Boolean,
    modifier: Modifier = Modifier,
) {
    PamScaffold(modifier = modifier, showAurora = false) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PamSpacing.gutter),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(PamSpacing.xxl))

            // Brand identity block
            PamLogoMark(size = 56.dp)
            Spacer(modifier = Modifier.height(PamSpacing.md))
            Text(
                text = stringResource(R.string.app_name),
                style = DisplayXl,
                color = OnInk,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(PamSpacing.xs))
            Text(
                text = stringResource(R.string.welcome_tagline),
                style = BodyM,
                color = OnInkMuted,
                textAlign = TextAlign.Center,
            )

            Spacer(modifier = Modifier.height(PamSpacing.xl))

            // Feature card — white card with two feature rows
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(PamRadius.lg))
                    .background(Surface)
                    .then(
                        Modifier.padding(PamSpacing.md),
                    ),
                verticalArrangement = Arrangement.spacedBy(PamSpacing.sm),
            ) {
                FeatureRow(
                    iconBox = {
                        Icon(
                            imageVector = Icons.Rounded.EditNote,
                            contentDescription = null,
                            tint = OnAccent,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    title = stringResource(R.string.welcome_feature_manual_title),
                    body = stringResource(R.string.welcome_feature_manual_body),
                )
                FeatureRow(
                    iconBox = {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = null,
                            tint = OnInk,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    isLock = true,
                    title = stringResource(R.string.welcome_feature_record_title),
                    body = stringResource(R.string.welcome_feature_record_body),
                )
                if (showDemoNotice) {
                    Text(
                        text = stringResource(R.string.welcome_demo_hint),
                        style = BodyS,
                        color = OnInkFaint,
                    )
                }
                Text(
                    text = stringResource(R.string.welcome_disclaimer),
                    style = BodyS,
                    color = OnInkFaint,
                )
            }

            Spacer(modifier = Modifier.height(PamSpacing.xl))

            // CTAs
            PillButton(
                text = stringResource(R.string.welcome_sign_in),
                onClick = onSignIn,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(PamSpacing.xs))
            PillButton(
                text = stringResource(R.string.welcome_create_account),
                onClick = onCreateAccount,
                style = PillButtonStyle.Secondary,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(PamSpacing.xs))
            PillButton(
                text = stringResource(R.string.welcome_privacy_link),
                onClick = onOpenPrivacy,
                style = PillButtonStyle.Ghost,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(PamSpacing.xxl))
        }
    }
}

@Composable
private fun FeatureRow(
    iconBox: @Composable () -> Unit,
    title: String,
    body: String,
    isLock: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(PamSpacing.sm),
    ) {
        // Icon box: lime for the edit_note icon, ink border for lock
        val boxBg = if (isLock) Surface else Accent
        val boxBorder = if (isLock) OutlineVariant else Accent
        Row(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(PamRadius.sm))
                .background(boxBg)
                .then(
                    if (isLock) Modifier.padding(1.dp) else Modifier,
                ),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            iconBox()
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = TitleM, color = OnInk)
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = body, style = LabelM, color = OnInkMuted)
        }
    }
}
