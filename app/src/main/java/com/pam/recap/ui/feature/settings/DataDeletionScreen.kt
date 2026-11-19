package com.pam.recap.ui.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pam.recap.R
import com.pam.recap.ui.components.EyebrowText
import com.pam.recap.ui.components.GlassCard
import com.pam.recap.ui.components.GlassTopBar
import com.pam.recap.ui.components.MoneyTone
import com.pam.recap.ui.components.NoticeBanner
import com.pam.recap.ui.components.PamScaffold
import com.pam.recap.ui.components.PillButton
import com.pam.recap.ui.components.PillButtonStyle
import com.pam.recap.ui.components.StatusChip
import com.pam.recap.ui.components.StatusChipKind
import com.pam.recap.ui.theme.BodyM
import com.pam.recap.ui.theme.DisplayM
import com.pam.recap.ui.theme.OnInk
import com.pam.recap.ui.theme.OnInkMuted
import com.pam.recap.ui.theme.PamSpacing

/**
 * §8 Data deletion: retention explanation before anything happens (FR-48), a re-authentication
 * affordance (FR-47), an explicit confirmation, and a recorded outcome.
 */
@Composable
fun DataDeletionScreen(
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DataDeletionViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    PamScaffold(
        modifier = modifier,
        topBar = {
            GlassTopBar(
                title = stringResource(R.string.deletion_title),
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
            EyebrowText(text = stringResource(R.string.deletion_eyebrow))
            Text(
                text = stringResource(R.string.deletion_headline),
                style = DisplayM,
                color = OnInk,
            )

            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.deletion_retention),
                    style = BodyM,
                    color = OnInkMuted,
                )
                Spacer(modifier = Modifier.height(PamSpacing.xs))
                Text(
                    text = stringResource(R.string.deletion_what_removed),
                    style = BodyM,
                    color = OnInkMuted,
                )
                Spacer(modifier = Modifier.height(PamSpacing.xxs))
                Text(
                    text = stringResource(R.string.deletion_what_stays),
                    style = BodyM,
                    color = OnInkMuted,
                )
            }

            when (state.step) {
                DeletionStep.Explanation -> {
                    PillButton(
                        text = stringResource(R.string.action_continue),
                        onClick = viewModel::continueToConfirm,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    PillButton(
                        text = stringResource(R.string.action_cancel),
                        onClick = onBack,
                        style = PillButtonStyle.Ghost,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                DeletionStep.Confirm -> {
                    StatusChip(
                        kind = if (state.reauthenticated) {
                            StatusChipKind.Complete
                        } else {
                            StatusChipKind.Provisional
                        },
                    )
                    Spacer(modifier = Modifier.height(PamSpacing.xxs))
                    Text(
                        text = if (state.reauthenticated) {
                            stringResource(R.string.deletion_reauth_done)
                        } else {
                            stringResource(R.string.deletion_reauth_required)
                        },
                        style = BodyM,
                        color = OnInkMuted,
                    )
                    if (!state.reauthenticated) {
                        PillButton(
                            text = stringResource(R.string.deletion_reauth_action),
                            onClick = viewModel::reauthenticate,
                            loading = state.isBusy,
                            style = PillButtonStyle.Secondary,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    PillButton(
                        text = stringResource(R.string.deletion_confirm),
                        onClick = viewModel::confirmDeletion,
                        enabled = state.reauthenticated,
                        loading = state.isBusy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    PillButton(
                        text = stringResource(R.string.action_cancel),
                        onClick = onBack,
                        style = PillButtonStyle.Ghost,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                DeletionStep.Done -> {
                    NoticeBanner(
                        text = stringResource(R.string.deletion_done),
                        tone = MoneyTone.Income,
                        icon = Icons.Rounded.CheckCircle,
                    )
                    PillButton(
                        text = stringResource(R.string.action_close),
                        onClick = onDeleted,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (state.error != null) {
                NoticeBanner(
                    text = stringResource(requireNotNull(state.error)),
                    tone = MoneyTone.Expense,
                )
            }
        }
    }
}
