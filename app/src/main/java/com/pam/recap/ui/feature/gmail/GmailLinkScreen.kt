package com.pam.recap.ui.feature.gmail

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pam.recap.R
import com.pam.recap.domain.model.GmailLinkResult
import com.pam.recap.domain.model.GmailLinkStatus
import com.pam.recap.domain.model.GmailSyncState
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
import com.pam.recap.ui.theme.DisplayM
import com.pam.recap.ui.theme.OnInk
import com.pam.recap.ui.theme.OnInkMuted
import com.pam.recap.ui.theme.PamSpacing
import com.pam.recap.ui.theme.TitleM
import com.pam.recap.ui.util.PamDateFormat
import com.pam.recap.ui.util.appErrorRes

/** Test tags kept stable for the Compose regression tests. */
object GmailLinkTestTags {
    const val CONNECT = "gmail_link_connect"
    const val DISCONNECT = "gmail_link_disconnect"
    const val RETRY = "gmail_link_retry"
}

/**
 * "Tautkan Gmail" (contract §1–§4): the app only starts the OAuth flow on the server and opens the
 * returned URL in the browser/Custom Tab. No Google password, token or full address is handled here.
 *
 * Deep link `pam://gmail/connected?status=...&reason=...` arrives as [statusArg]/[reasonArg] from
 * the navigation route; this wrapper shows the outcome and refreshes the status. Resume also polls.
 */
@Composable
fun GmailLinkScreen(
    onBack: () -> Unit,
    statusArg: String? = null,
    reasonArg: String? = null,
    modifier: Modifier = Modifier,
    viewModel: GmailLinkViewModel = hiltViewModel(),
    /** Overridable in tests; production opens the URL in the browser/Custom Tab. */
    urlOpener: ((String) -> Boolean)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(statusArg, reasonArg) {
        if (statusArg != null) {
            viewModel.onDeepLink(GmailLinkResult.fromWire(statusArg, reasonArg))
        }
    }

    // Poll when the user comes back from the browser (contract §6). Cheap and owner-scoped.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    LaunchedEffect(state.authorizationUrl) {
        val url = state.authorizationUrl ?: return@LaunchedEffect
        val opened = urlOpener?.invoke(url) ?: runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.isSuccess
        if (opened) viewModel.consumeAuthorizationUrl() else viewModel.onOpenUrlFailed()
    }

    GmailLinkContent(
        state = state,
        onBack = onBack,
        onConnect = viewModel::connect,
        onRequestDisconnect = viewModel::requestDisconnect,
        onConfirmDisconnect = viewModel::confirmDisconnect,
        onDismissDisconnect = viewModel::dismissDisconnect,
        onRetry = viewModel::refresh,
        onDismissResult = viewModel::dismissLinkResult,
        modifier = modifier,
    )
}

/** Stateless body: every state and action is a parameter so Robolectric can drive it directly. */
@Composable
fun GmailLinkContent(
    state: GmailLinkUiState,
    onBack: () -> Unit,
    onConnect: () -> Unit,
    onRequestDisconnect: () -> Unit,
    onConfirmDisconnect: () -> Unit,
    onDismissDisconnect: () -> Unit,
    onRetry: () -> Unit,
    onDismissResult: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PamScaffold(
        modifier = modifier,
        topBar = {
            GlassTopBar(
                title = stringResource(R.string.gmail_link_title),
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
            EyebrowText(text = stringResource(R.string.gmail_link_eyebrow))
            Text(
                text = stringResource(R.string.gmail_link_headline),
                style = DisplayM,
                color = OnInk,
            )

            state.linkResult?.let { result ->
                NoticeBanner(
                    text = if (result.success) {
                        stringResource(R.string.gmail_link_success)
                    } else {
                        result.reason?.let { stringResource(R.string.gmail_link_failed, it) }
                            ?: stringResource(R.string.gmail_link_failed_generic)
                    },
                    tone = if (result.success) MoneyTone.Income else MoneyTone.Expense,
                    action = {
                        TextButton(onClick = onDismissResult) {
                            Text(stringResource(R.string.action_close))
                        }
                    },
                )
            }

            // A runtime-disabled backend is explained inside the card below, so it is not also
            // shown as a generic failure banner (that would read like a retryable outage).
            if (state.error != null && !state.backendGmailDisabled) {
                NoticeBanner(text = stringResource(appErrorRes(state.error)))
            }

            GlassCard(modifier = Modifier.fillMaxWidth()) {
                if (state.loading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(PamSpacing.lg),
                            color = OnInk,
                        )
                        Spacer(modifier = Modifier.height(PamSpacing.xs))
                        Text(
                            text = stringResource(R.string.gmail_link_connecting),
                            style = BodyM,
                            color = OnInkMuted,
                        )
                    }
                } else {
                    GmailStatusBody(status = state.status)
                }

                Spacer(modifier = Modifier.height(PamSpacing.sm))

                if (state.backendGmailDisabled) {
                    // The server answered `503 gmail_disabled`: no connect/disconnect CTA at all.
                    Text(
                        text = stringResource(R.string.gmail_link_backend_disabled),
                        style = BodyM,
                        color = OnInkMuted,
                    )
                } else if (!state.enabled) {
                    Text(
                        text = stringResource(R.string.gmail_link_server_required),
                        style = BodyM,
                        color = OnInkMuted,
                    )
                } else {
                    PillButton(
                        text = stringResource(
                            if (state.status?.connected == true) {
                                R.string.gmail_link_reconnect
                            } else {
                                R.string.gmail_link_connect
                            },
                        ),
                        onClick = onConnect,
                        style = PillButtonStyle.Primary,
                        enabled = !state.busy && state.signedIn,
                        loading = state.busy,
                        leadingIcon = Icons.Rounded.Link,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag(GmailLinkTestTags.CONNECT),
                    )
                    if (state.status?.connected == true) {
                        Spacer(modifier = Modifier.height(PamSpacing.xs))
                        PillButton(
                            text = stringResource(R.string.gmail_link_disconnect),
                            onClick = onRequestDisconnect,
                            style = PillButtonStyle.Secondary,
                            enabled = !state.busy,
                            leadingIcon = Icons.Rounded.LinkOff,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(GmailLinkTestTags.DISCONNECT),
                        )
                    }
                }
            }

            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.gmail_link_consent_title),
                    style = TitleM,
                    color = OnInk,
                )
                Spacer(modifier = Modifier.height(PamSpacing.xs))
                Text(
                    text = stringResource(R.string.gmail_link_consent_notice),
                    style = BodyM,
                    color = OnInkMuted,
                )
                Spacer(modifier = Modifier.height(PamSpacing.xs))
                Text(
                    text = stringResource(R.string.gmail_link_consent_separate),
                    style = BodyM,
                    color = OnInkMuted,
                )
                Spacer(modifier = Modifier.height(PamSpacing.xs))
                Text(
                    text = stringResource(R.string.gmail_link_retention),
                    style = BodyM,
                    color = OnInkMuted,
                )
            }

            if (state.error != null) {
                PillButton(
                    text = stringResource(R.string.action_retry),
                    onClick = onRetry,
                    style = PillButtonStyle.Secondary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(GmailLinkTestTags.RETRY),
                )
            }
        }
    }

    if (state.disconnectRequested) {
        AlertDialog(
            onDismissRequest = { if (!state.busy) onDismissDisconnect() },
            title = { Text(stringResource(R.string.gmail_link_disconnect_title)) },
            text = { Text(stringResource(R.string.gmail_link_disconnect_body)) },
            confirmButton = {
                TextButton(onClick = onConfirmDisconnect, enabled = !state.busy) {
                    Text(stringResource(R.string.gmail_link_disconnect))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissDisconnect, enabled = !state.busy) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun GmailStatusBody(status: GmailLinkStatus?) {
    val connected = status?.connected == true
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (connected) {
                status?.accountEmailMasked?.let { stringResource(R.string.gmail_link_connected_as, it) }
                    ?: stringResource(R.string.gmail_link_connected_generic)
            } else {
                stringResource(R.string.gmail_link_not_connected)
            },
            style = TitleM,
            color = OnInk,
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = if (connected) "Gmail tertaut" else "Gmail belum tertaut" },
        )
        StatusChip(kind = if (connected) StatusChipKind.Connected else StatusChipKind.Empty)
    }

    if (!connected) return

    Spacer(modifier = Modifier.height(PamSpacing.xs))
    Text(
        text = stringResource(
            when (status?.syncState) {
                GmailSyncState.IDLE -> R.string.gmail_link_sync_idle
                GmailSyncState.BACKFILL -> R.string.gmail_link_sync_backfill
                GmailSyncState.DELTA -> R.string.gmail_link_sync_delta
                GmailSyncState.ERROR -> R.string.gmail_link_sync_error
                GmailSyncState.UNKNOWN, null -> R.string.gmail_link_sync_unknown
            },
        ),
        style = BodyM,
        color = OnInkMuted,
    )
    Spacer(modifier = Modifier.height(PamSpacing.xs))
    KeyValueRow(
        label = stringResource(R.string.gmail_link_last_sync),
        value = status?.lastSyncAt?.let { PamDateFormat.dateTime(it) }
            ?: stringResource(R.string.gmail_link_never),
    )
    KeyValueRow(
        label = stringResource(R.string.gmail_link_watch_until),
        value = status?.watchExpiresAt?.let { PamDateFormat.dateTime(it) }
            ?: stringResource(R.string.gmail_link_never),
    )
}
