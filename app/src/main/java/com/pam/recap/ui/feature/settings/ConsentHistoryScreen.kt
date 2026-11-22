package com.pam.recap.ui.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pam.recap.R
import com.pam.recap.domain.model.Consent
import com.pam.recap.domain.model.ConsentDecision
import com.pam.recap.domain.model.ConsentPurpose
import com.pam.recap.ui.components.EyebrowText
import com.pam.recap.ui.components.GlassCard
import com.pam.recap.ui.components.GlassTopBar
import com.pam.recap.ui.components.LoadingShimmer
import com.pam.recap.ui.components.PamScaffold
import com.pam.recap.ui.components.StatusChip
import com.pam.recap.ui.components.StatusChipKind
import com.pam.recap.ui.theme.BodyM
import com.pam.recap.ui.theme.DisplayM
import com.pam.recap.ui.theme.LabelM
import com.pam.recap.ui.theme.OnInk
import com.pam.recap.ui.theme.OnInkMuted
import com.pam.recap.ui.theme.PamSpacing
import com.pam.recap.ui.theme.TitleM
import com.pam.recap.ui.util.PamDateFormat

/** FR-03/FR-49: the consent ledger, newest first. */
@Composable
fun ConsentHistoryScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ConsentHistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    PamScaffold(
        modifier = modifier,
        topBar = {
            GlassTopBar(
                title = stringResource(R.string.consent_history_title),
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(PamSpacing.sm),
        ) {
            EyebrowText(text = stringResource(R.string.consent_history_eyebrow))
            Text(
                text = stringResource(R.string.consent_history_headline),
                style = DisplayM,
                color = OnInk,
            )
            Text(
                text = stringResource(R.string.consent_history_body),
                style = BodyM,
                color = OnInkMuted,
            )

            if (state.isLoading) {
                LoadingShimmer(rows = 3, showHero = false)
                return@PamScaffold
            }

            state.entries.forEach { entry ->
                ConsentRow(entry)
            }
        }
    }
}

@Composable
private fun ConsentRow(entry: Consent) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(purposeRes(entry.purpose)),
                style = TitleM,
                color = OnInk,
                modifier = Modifier.weight(1f),
            )
            StatusChip(kind = decisionChip(entry.decision))
        }
        Spacer(modifier = Modifier.height(PamSpacing.xxs))
        Text(
            text = stringResource(
                R.string.consent_history_meta,
                entry.noticeVersion,
                PamDateFormat.dateTime(entry.decidedAt),
            ),
            style = LabelM,
            color = OnInkMuted,
        )
    }
}

private fun purposeRes(purpose: ConsentPurpose): Int = when (purpose) {
    ConsentPurpose.GMAIL_READ -> R.string.settings_consent_gmail
    ConsentPurpose.AI_PROCESSING -> R.string.settings_consent_ai
    ConsentPurpose.AI_CATEGORIZATION -> R.string.settings_consent_category
}

private fun decisionChip(decision: ConsentDecision): StatusChipKind = when (decision) {
    ConsentDecision.GRANTED -> StatusChipKind.Granted
    ConsentDecision.DECLINED -> StatusChipKind.Declined
    ConsentDecision.WITHDRAWN -> StatusChipKind.Historical
}
