package com.pam.recap.ui.feature.welcome

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MailOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pam.recap.R
import com.pam.recap.ui.components.GlassCard
import com.pam.recap.ui.components.NoticeBanner
import com.pam.recap.ui.components.PamScaffold
import com.pam.recap.ui.components.PillButton
import com.pam.recap.ui.components.PillButtonStyle
import com.pam.recap.ui.theme.BodyL
import com.pam.recap.ui.theme.BodyS
import com.pam.recap.ui.theme.DisplayXl
import com.pam.recap.ui.theme.GlassFill
import com.pam.recap.ui.theme.OnInk
import com.pam.recap.ui.theme.OnInkFaint
import com.pam.recap.ui.theme.OnInkMuted
import com.pam.recap.ui.theme.PamSpacing
import com.pam.recap.ui.theme.Violet
import com.pam.recap.ui.util.appErrorRes

/**
 * §8 Welcome: wordmark, tagline, privacy link and a single Google sign-in action over the
 * hero + aurora background. Rejection produces neutral inline copy, never a red screen (FR-01).
 */
@Composable
fun WelcomeScreen(
    onOpenPrivacy: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WelcomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    PamScaffold(modifier = modifier, showAurora = true) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(GlassFill),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.MailOutline,
                    contentDescription = null,
                    tint = Violet,
                    modifier = Modifier.size(30.dp),
                )
            }

            Spacer(modifier = Modifier.height(PamSpacing.lg))
            Text(
                text = stringResource(R.string.app_name),
                style = DisplayXl,
                color = OnInk,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(PamSpacing.sm))
            Text(
                text = stringResource(R.string.welcome_tagline),
                style = BodyL,
                color = OnInkMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 320.dp),
            )

            Spacer(modifier = Modifier.height(PamSpacing.xxl))

            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.welcome_disclaimer),
                    style = BodyS,
                    color = OnInkMuted,
                )
                if (state.isDemoMode) {
                    Spacer(modifier = Modifier.height(PamSpacing.xs))
                    Text(
                        text = stringResource(R.string.welcome_demo_hint),
                        style = BodyS,
                        color = OnInkFaint,
                    )
                }
            }

            if (state.error != null) {
                Spacer(modifier = Modifier.height(PamSpacing.sm))
                NoticeBanner(
                    text = stringResource(appErrorRes(requireNotNull(state.error))),
                    tone = com.pam.recap.ui.components.MoneyTone.Neutral,
                    icon = Icons.Rounded.MailOutline,
                )
            }

            Spacer(modifier = Modifier.height(PamSpacing.lg))

            PillButton(
                text = stringResource(R.string.welcome_sign_in),
                onClick = viewModel::signInWithGoogle,
                loading = state.isSigningIn,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(PamSpacing.xs))
            PillButton(
                text = stringResource(R.string.welcome_privacy_link),
                onClick = onOpenPrivacy,
                style = PillButtonStyle.Ghost,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
