package com.pam.recap.ui.feature.consent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pam.recap.R
import com.pam.recap.ui.components.ConsentSwitchRow
import com.pam.recap.ui.components.EyebrowText
import com.pam.recap.ui.components.GlassCard
import com.pam.recap.ui.components.GlassTopBar
import com.pam.recap.ui.components.NoticeBanner
import com.pam.recap.ui.components.PamScaffold
import com.pam.recap.ui.components.PillButton
import com.pam.recap.ui.theme.BodyM
import com.pam.recap.ui.theme.DisplayM
import com.pam.recap.ui.theme.OnInk
import com.pam.recap.ui.theme.OnInkMuted
import com.pam.recap.ui.theme.PamSpacing
import com.pam.recap.ui.theme.TitleM
import com.pam.recap.ui.util.appErrorRes

/**
 * §8 Consent: two independent switches with exact disclosure copy, and a `Lanjut` that only
 * requires the Gmail decision (AI may be declined without losing the recap).
 */
@Composable
fun ConsentScreen(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ConsentViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.finished) {
        if (state.finished) onFinished()
    }

    PamScaffold(
        modifier = modifier,
        showAurora = true,
        topBar = { GlassTopBar(title = stringResource(R.string.consent_title)) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(PamSpacing.sm),
        ) {
            EyebrowText(text = stringResource(R.string.consent_eyebrow))
            Text(
                text = stringResource(R.string.consent_headline),
                style = DisplayM,
                color = OnInk,
            )
            Text(
                text = stringResource(R.string.consent_intro),
                style = BodyM,
                color = OnInkMuted,
            )

            Spacer(modifier = Modifier.height(PamSpacing.xs))

            ConsentSwitchRow(
                title = stringResource(R.string.consent_gmail_title),
                body = stringResource(R.string.consent_gmail_body),
                checked = state.gmailGranted == true,
                onCheckedChange = viewModel::decideGmail,
            )
            ConsentSwitchRow(
                title = stringResource(R.string.consent_ai_title),
                body = stringResource(R.string.consent_ai_body),
                checked = state.aiGranted == true,
                onCheckedChange = viewModel::decideAi,
            )

            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.consent_disclosure_title),
                    style = TitleM,
                    color = OnInk,
                )
                Spacer(modifier = Modifier.height(PamSpacing.xs))
                Text(
                    text = stringResource(R.string.consent_disclosure_leaves),
                    style = BodyM,
                    color = OnInkMuted,
                )
                Spacer(modifier = Modifier.height(PamSpacing.xs))
                Text(
                    text = stringResource(R.string.consent_disclosure_stays),
                    style = BodyM,
                    color = OnInkMuted,
                )
            }

            if (state.error != null) {
                NoticeBanner(
                    text = stringResource(appErrorRes(requireNotNull(state.error))),
                    tone = com.pam.recap.ui.components.MoneyTone.Neutral,
                )
            }

            Spacer(modifier = Modifier.height(PamSpacing.sm))

            PillButton(
                text = stringResource(R.string.action_continue),
                onClick = viewModel::submit,
                enabled = state.canContinue,
                loading = state.isSaving,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.consent_footer),
                style = TitleM,
                color = OnInkMuted,
            )
        }
    }
}
