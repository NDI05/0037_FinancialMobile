package com.pam.recap.ui.feature.gmail

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
import com.pam.recap.domain.model.GmailConnection
import com.pam.recap.domain.model.GmailConnectionState
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
import com.pam.recap.ui.theme.TitleM
import com.pam.recap.ui.util.PamDateFormat
import com.pam.recap.ui.util.appErrorRes

/**
 * §8 Gmail connect: a state card with one of the six states, actionable guidance, connect and
 * disconnect, the last successful import, and the scope shown as a label — never a token.
 */
@Composable
fun GmailConnectScreen(
    onBack: () -> Unit,
    onOpenConsent: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GmailConnectViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val connection = state.connection
    val connectionState = connection?.state ?: GmailConnectionState.NOT_CONNECTED

    PamScaffold(
        modifier = modifier,
        topBar = {
            GlassTopBar(
                title = stringResource(R.string.gmail_title),
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
            EyebrowText(text = stringResource(R.string.gmail_eyebrow))
            Text(
                text = stringResource(R.string.gmail_headline),
                style = DisplayM,
                color = OnInk,
            )

            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(stateLabelRes(connectionState)),
                        style = TitleM,
                        color = OnInk,
                    )
                    StatusChip(kind = stateChipKind(connectionState))
                }
                if (connection?.mailboxLabel != null) {
                    Spacer(modifier = Modifier.height(PamSpacing.xs))
                    Text(
                        text = connection.mailboxLabel,
                        style = BodyM,
                        color = OnInkMuted,
                    )
                }
                Spacer(modifier = Modifier.height(PamSpacing.xs))
                Text(
                    text = stringResource(R.string.gmail_scope_label),
                    style = BodyM,
                    color = OnInkMuted,
                )
                Spacer(modifier = Modifier.height(PamSpacing.xs))
                Text(
                    text = stringResource(
                        R.string.gmail_last_import,
                        connection?.lastImportAt?.let { PamDateFormat.dateTime(it) }
                            ?: stringResource(R.string.gmail_never_imported),
                    ),
                    style = BodyM,
                    color = OnInkMuted,
                )
                Spacer(modifier = Modifier.height(PamSpacing.sm))
                Text(
                    text = stringResource(guidanceRes(connectionState)),
                    style = BodyM,
                    color = OnInkMuted,
                )
            }

            if (!state.consentGranted) {
                NoticeBanner(
                    text = stringResource(R.string.gmail_consent_needed),
                    tone = MoneyTone.Neutral,
                )
                PillButton(
                    text = stringResource(R.string.gmail_open_consent),
                    onClick = onOpenConsent,
                    style = PillButtonStyle.Secondary,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (state.error != null) {
                NoticeBanner(text = stringResource(appErrorRes(requireNotNull(state.error))))
            }
            if (state.revokeFailed) {
                NoticeBanner(
                    text = stringResource(R.string.gmail_revoke_failed),
                    tone = MoneyTone.Expense,
                )
            }

            Spacer(modifier = Modifier.height(PamSpacing.xs))

            if (connectionState == GmailConnectionState.CONNECTED) {
                PillButton(
                    text = stringResource(R.string.gmail_disconnect),
                    onClick = viewModel::disconnect,
                    style = PillButtonStyle.Secondary,
                    loading = state.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                PillButton(
                    text = stringResource(R.string.gmail_connect),
                    onClick = viewModel::connect,
                    enabled = state.consentGranted,
                    loading = state.isBusy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun stateLabelRes(state: GmailConnectionState): Int = when (state) {
    GmailConnectionState.NOT_CONNECTED -> R.string.gmail_state_not_connected
    GmailConnectionState.AUTHORIZATION_PENDING -> R.string.gmail_state_pending
    GmailConnectionState.CONNECTED -> R.string.gmail_state_connected
    GmailConnectionState.REAUTHORIZATION_REQUIRED -> R.string.gmail_state_reauth
    GmailConnectionState.UNAVAILABLE -> R.string.gmail_state_unavailable
    GmailConnectionState.DISCONNECTED -> R.string.gmail_state_disconnected
}

private fun guidanceRes(state: GmailConnectionState): Int = when (state) {
    GmailConnectionState.NOT_CONNECTED -> R.string.gmail_guidance_not_connected
    GmailConnectionState.AUTHORIZATION_PENDING -> R.string.gmail_guidance_pending
    GmailConnectionState.CONNECTED -> R.string.gmail_guidance_connected
    GmailConnectionState.REAUTHORIZATION_REQUIRED -> R.string.gmail_guidance_reauth
    GmailConnectionState.UNAVAILABLE -> R.string.gmail_guidance_unavailable
    GmailConnectionState.DISCONNECTED -> R.string.gmail_guidance_disconnected
}

private fun stateChipKind(state: GmailConnectionState): StatusChipKind = when (state) {
    GmailConnectionState.CONNECTED -> StatusChipKind.Connected
    GmailConnectionState.AUTHORIZATION_PENDING -> StatusChipKind.Provisional
    GmailConnectionState.REAUTHORIZATION_REQUIRED -> StatusChipKind.Partial
    GmailConnectionState.UNAVAILABLE -> StatusChipKind.Unavailable
    GmailConnectionState.DISCONNECTED, GmailConnectionState.NOT_CONNECTED -> StatusChipKind.Empty
}
