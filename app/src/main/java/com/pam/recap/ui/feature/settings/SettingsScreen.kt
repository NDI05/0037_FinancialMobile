package com.pam.recap.ui.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pam.recap.R
import com.pam.recap.domain.model.Consent
import com.pam.recap.ui.components.EyebrowText
import com.pam.recap.ui.components.GlassCard
import com.pam.recap.ui.components.GlassTopBar
import com.pam.recap.ui.components.KeyValueRow
import com.pam.recap.ui.components.MoneyTone
import com.pam.recap.ui.components.NoticeBanner
import com.pam.recap.ui.components.PamScaffold
import com.pam.recap.ui.components.PillButton
import com.pam.recap.ui.components.PillButtonStyle
import com.pam.recap.ui.components.StatusChip
import com.pam.recap.ui.components.StatusChipKind
import com.pam.recap.ui.theme.BodyM
import com.pam.recap.ui.theme.GlassFill
import com.pam.recap.ui.theme.OnInk
import com.pam.recap.ui.theme.OnInkMuted
import com.pam.recap.ui.theme.PamRadius
import com.pam.recap.ui.theme.PamSize
import com.pam.recap.ui.theme.PamSpacing
import com.pam.recap.ui.theme.TitleM
import com.pam.recap.ui.util.PamDateFormat
import com.pam.recap.ui.util.appErrorRes

/** §8 Settings: connection state, consent entry points, privacy actions, sign out. */
@Composable
fun SettingsScreen(
    onOpenGmailLink: () -> Unit,
    onOpenConsentHistory: () -> Unit,
    onOpenCategories: () -> Unit,
    onOpenDeletion: () -> Unit,
    onSignedOut: () -> Unit,
    /** Opens the signed-in "Akun" editor (Google link state + Gmail shortcut). */
    onOpenAccount: () -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state.signedOut) {
        if (state.signedOut) onSignedOut()
    }

    PamScaffold(
        modifier = modifier,
        topBar = {
            GlassTopBar(title = stringResource(R.string.settings_title)) {
                com.pam.recap.ui.feature.status.UpdateStatusTopBarAction()
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(PamSpacing.sm),
        ) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                EyebrowText(text = stringResource(R.string.settings_mailbox_eyebrow))
                Spacer(modifier = Modifier.height(PamSpacing.xs))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = state.connection?.mailboxLabel
                            ?: stringResource(R.string.gmail_state_not_connected),
                        style = TitleM,
                        color = OnInk,
                        modifier = Modifier.weight(1f),
                    )
                    StatusChip(
                        kind = if (state.connection?.state?.grantsAccess == true) {
                            StatusChipKind.Connected
                        } else {
                            StatusChipKind.Empty
                        },
                    )
                }
                Spacer(modifier = Modifier.height(PamSpacing.xs))
                KeyValueRow(
                    label = stringResource(R.string.settings_last_import),
                    value = state.connection?.lastImportAt?.let { PamDateFormat.dateTime(it) }
                        ?: stringResource(R.string.gmail_never_imported),
                )
                KeyValueRow(
                    label = stringResource(R.string.settings_last_ai),
                    value = state.connection?.lastAiAt?.let { PamDateFormat.dateTime(it) }
                        ?: stringResource(R.string.settings_never),
                )
                KeyValueRow(
                    label = stringResource(R.string.settings_scope),
                    value = stringResource(R.string.settings_scope_value),
                )
                Spacer(modifier = Modifier.height(PamSpacing.sm))
                PillButton(
                    text = stringResource(R.string.settings_open_gmail_link),
                    onClick = onOpenGmailLink,
                    style = PillButtonStyle.Secondary,
                    leadingIcon = Icons.Rounded.Link,
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

            GlassCard(modifier = Modifier.fillMaxWidth()) {
                EyebrowText(text = stringResource(R.string.settings_privacy_eyebrow))
                Spacer(modifier = Modifier.height(PamSpacing.xs))
                ConsentSummaryRow(
                    label = stringResource(R.string.settings_consent_gmail),
                    consent = state.gmailConsent,
                )
                ConsentSummaryRow(
                    label = stringResource(R.string.settings_consent_ai),
                    consent = state.aiConsent,
                )
            }

            SettingsRow(
                icon = Icons.Rounded.AccountCircle,
                title = stringResource(R.string.settings_account),
                subtitle = stringResource(R.string.settings_account_row),
                onClick = onOpenAccount,
            )
            SettingsRow(
                icon = Icons.Rounded.Shield,
                title = stringResource(R.string.settings_consent_history),
                subtitle = stringResource(R.string.settings_consent_history_body),
                onClick = onOpenConsentHistory,
            )
            SettingsRow(
                icon = Icons.Rounded.Category,
                title = stringResource(R.string.category_manage_title),
                subtitle = stringResource(R.string.settings_categories_body),
                onClick = onOpenCategories,
            )
            if (state.demoRowCount > 0) {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    EyebrowText(text = stringResource(R.string.settings_demo_eyebrow))
                    Spacer(modifier = Modifier.height(PamSpacing.xs))
                    Text(
                        text = stringResource(R.string.settings_demo_count, state.demoRowCount),
                        style = TitleM,
                        color = OnInk,
                    )
                    Spacer(modifier = Modifier.height(PamSpacing.xxs))
                    Text(
                        text = stringResource(R.string.settings_demo_body),
                        style = BodyM,
                        color = OnInkMuted,
                    )
                    Spacer(modifier = Modifier.height(PamSpacing.sm))
                    if (state.confirmingPurge) {
                        NoticeBanner(
                            text = stringResource(
                                R.string.settings_demo_confirm,
                                state.demoRowCount,
                            ),
                            tone = MoneyTone.Expense,
                        )
                        Spacer(modifier = Modifier.height(PamSpacing.xs))
                        PillButton(
                            text = stringResource(R.string.settings_demo_confirm_action),
                            onClick = viewModel::confirmPurge,
                            loading = state.purgeBusy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(PamSpacing.xxs))
                        PillButton(
                            text = stringResource(R.string.settings_demo_cancel),
                            onClick = viewModel::cancelPurge,
                            style = PillButtonStyle.Ghost,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        PillButton(
                            text = stringResource(R.string.settings_demo_action),
                            onClick = viewModel::requestPurge,
                            style = PillButtonStyle.Secondary,
                            leadingIcon = Icons.Rounded.DeleteOutline,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            val purge = state.purgeResult
            if (purge != null) {
                NoticeBanner(
                    text = if (purge.isEmpty) {
                        stringResource(R.string.settings_demo_none)
                    } else {
                        stringResource(R.string.settings_demo_done, purge.total)
                    },
                    tone = MoneyTone.Income,
                )
            }

            SettingsRow(
                icon = Icons.Rounded.DeleteOutline,
                title = stringResource(R.string.settings_delete),
                subtitle = stringResource(R.string.settings_delete_body),
                onClick = onOpenDeletion,
            )
            SettingsRow(
                icon = Icons.Rounded.Logout,
                title = stringResource(R.string.settings_sign_out),
                subtitle = stringResource(R.string.settings_sign_out_body),
                onClick = viewModel::signOut,
            )

            PillButton(
                text = stringResource(R.string.settings_disconnect),
                onClick = viewModel::disconnect,
                style = PillButtonStyle.Ghost,
                loading = state.isBusy,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.settings_footer),
                style = BodyM,
                color = OnInkMuted,
            )
        }
    }
}

@Composable
private fun ConsentSummaryRow(label: String, consent: Consent?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = PamSpacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = TitleM, color = OnInkMuted, modifier = Modifier.weight(1f))
        when {
            consent == null -> StatusChip(kind = StatusChipKind.Empty)
            consent.isGranted -> StatusChip(kind = StatusChipKind.Granted)
            consent.isWithdrawn -> StatusChip(kind = StatusChipKind.Historical)
            else -> StatusChip(kind = StatusChipKind.Declined)
        }
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PamRadius.md))
            .background(GlassFill)
            .clickable(onClick = onClick)
            .padding(PamSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PamSpacing.sm),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = OnInkMuted)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = TitleM, color = OnInk)
            Text(text = subtitle, style = BodyM, color = OnInkMuted)
        }
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = OnInkMuted,
            modifier = Modifier.size(18.dp),
        )
    }
}
