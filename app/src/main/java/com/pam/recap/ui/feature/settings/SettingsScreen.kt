package com.pam.recap.ui.feature.settings

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Label
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pam.recap.R
import com.pam.recap.data.notification.TransactionNotificationSettings
import com.pam.recap.data.notification.TransactionNotifications
import com.pam.recap.domain.model.Consent
import com.pam.recap.ui.components.MoneyTone
import com.pam.recap.ui.components.NoticeBanner
import com.pam.recap.ui.components.PamScaffold
import com.pam.recap.ui.components.PillButton
import com.pam.recap.ui.components.PillButtonStyle
import com.pam.recap.ui.feature.status.UpdateStatusTopBarAction
import com.pam.recap.ui.theme.BodyM
import com.pam.recap.ui.theme.BodyS
import com.pam.recap.ui.theme.LabelEyebrow
import com.pam.recap.ui.theme.LabelM
import com.pam.recap.ui.theme.OnInk
import com.pam.recap.ui.theme.OnInkFaint
import com.pam.recap.ui.theme.OnInkMuted
import com.pam.recap.ui.theme.OnPrimary
import com.pam.recap.ui.theme.OutlineVariant
import com.pam.recap.ui.theme.PamRadius
import com.pam.recap.ui.theme.PamSpacing
import com.pam.recap.ui.theme.Primary
import com.pam.recap.ui.theme.Surface
import com.pam.recap.ui.theme.SurfaceContainer
import com.pam.recap.ui.theme.SurfaceLow
import com.pam.recap.ui.theme.SuccessContainer
import com.pam.recap.ui.theme.TitleL
import com.pam.recap.ui.theme.TitleM
import com.pam.recap.ui.widget.PamSummaryWidgetReceiver
import com.pam.recap.ui.util.appErrorRes

/**
 * §8 Settings, Stitch "Pengaturan" layout: centered title, labelled sections in flat white cards.
 * Every control keeps its real behaviour — Gmail disconnect/link, AI consent visibility (decisions
 * are recorded on the consent surface, reachable from here), notification permission handling,
 * category navigation, widget pinning, privacy actions and sign out. No toggle is decorative.
 */
@Composable
fun SettingsScreen(
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

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val notificationSettings = remember(context) { TransactionNotificationSettings(context) }
    var notificationsEnabled by remember { mutableStateOf(notificationSettings.isEnabled()) }
    var notificationsDenied by remember {
        mutableStateOf(
            notificationSettings.isEnabled() &&
                !TransactionNotifications.canPost(context),
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        notificationsDenied = notificationsEnabled && !TransactionNotifications.canPost(context)
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val enabled = notificationSettings.isEnabled()
                notificationsEnabled = enabled
                notificationsDenied = enabled && !TransactionNotifications.canPost(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { TransactionNotifications.ensureChannel(context) }

    var confirmingGmailDisconnect by remember { mutableStateOf(false) }
    val connected = state.connection?.state?.grantsAccess == true
    val mailbox = state.connection?.mailboxLabel

    PamScaffold(
        modifier = modifier,
        topBar = {
            SettingsHeader()
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(PamSpacing.lg),
        ) {
            if (state.error != null) {
                NoticeBanner(text = stringResource(appErrorRes(requireNotNull(state.error))))
            }
            if (state.revokeFailed) {
                NoticeBanner(
                    text = stringResource(R.string.gmail_revoke_failed),
                    tone = MoneyTone.Expense,
                )
            }

            // Akun Google ----------------------------------------------------------------
            SettingsSection(title = stringResource(R.string.settings_section_google)) {
                SettingsCard(onClick = onOpenAccount) {
                    Avatar(
                        initials = mailbox
                            ?.substringBefore('@')
                            ?.take(2)
                            ?.uppercase()
                            ?.ifBlank { null },
                    )
                    Spacer(Modifier.width(PamSpacing.sm))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_account),
                            style = TitleM,
                            color = OnInk,
                        )
                        Text(
                            text = mailbox
                                ?: stringResource(R.string.settings_account_row),
                            style = BodyS,
                            color = OnInkMuted,
                        )
                    }
                    Chevron()
                }
            }

            // Koneksi Gmail -------------------------------------------------------------
            SettingsSection(title = stringResource(R.string.settings_section_gmail)) {
                SettingsCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(PamSpacing.sm),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_gmail_card_title),
                                style = TitleM,
                                color = OnInk,
                            )
                            Text(
                                text = stringResource(R.string.settings_gmail_readonly),
                                style = BodyS,
                                color = OnInkMuted,
                            )
                        }
                        StatusBadge(
                            text = if (connected) {
                                stringResource(R.string.settings_notif_on)
                            } else {
                                stringResource(R.string.settings_gmail_disconnected)
                            },
                            tone = if (connected) BadgeTone.Success else BadgeTone.Neutral,
                        )
                    }
                    Spacer(Modifier.height(PamSpacing.sm))
                    PillButton(
                        text = if (connected) {
                            stringResource(R.string.gmail_link_disconnect)
                        } else {
                            stringResource(R.string.gmail_link_connect)
                        },
                        onClick = {
                            if (connected) confirmingGmailDisconnect = true else onOpenAccount()
                        },
                        style = PillButtonStyle.Secondary,
                        enabled = !state.isBusy,
                        loading = state.isBusy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // Izin AI -------------------------------------------------------------------
            // The decisions themselves are recorded on the consent surface; these rows show the
            // recorded state and open the consent history. Nothing is toggled locally.
            SettingsSection(title = stringResource(R.string.settings_section_ai)) {
                SettingsCard {
                    AiConsentRow(
                        title = stringResource(R.string.settings_ai_categorization),
                        body = stringResource(R.string.settings_ai_categorization_sub),
                        onClick = onOpenConsentHistory,
                    )
                    SectionDivider()
                    AiConsentRow(
                        title = stringResource(R.string.settings_ai_analysis),
                        body = stringResource(R.string.settings_ai_analysis_sub),
                        onClick = onOpenConsentHistory,
                        status = state.aiConsent,
                    )
                    SectionDivider()
                    LinkRow(
                        icon = Icons.Rounded.Shield,
                        title = stringResource(R.string.settings_consent_history),
                        body = stringResource(R.string.settings_consent_history_body),
                        onClick = onOpenConsentHistory,
                    )
                }
            }

            // Notifikasi -----------------------------------------------------------------
            SettingsSection(title = stringResource(R.string.settings_section_notifications)) {
                SettingsCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(PamSpacing.sm),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_notifications_title),
                                style = TitleM,
                                color = OnInk,
                            )
                            Text(
                                text = stringResource(R.string.settings_notifications_body),
                                style = BodyS,
                                color = OnInkMuted,
                            )
                        }
                        Switch(
                            checked = notificationsEnabled,
                            onCheckedChange = { checked ->
                                notificationSettings.setEnabled(checked)
                                notificationsEnabled = checked
                                if (checked) {
                                    TransactionNotifications.ensureChannel(context)
                                    if (
                                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                        !TransactionNotifications.hasPermission(context)
                                    ) {
                                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    }
                                    notificationsDenied = !TransactionNotifications.canPost(context)
                                } else {
                                    notificationsDenied = false
                                    TransactionNotifications.cancel(context)
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = OnPrimary,
                                checkedTrackColor = Primary,
                                checkedBorderColor = Primary,
                                uncheckedThumbColor = Surface,
                                uncheckedTrackColor = SurfaceContainer,
                                uncheckedBorderColor = OnInkFaint,
                            ),
                        )
                    }
                    Spacer(Modifier.height(PamSpacing.sm))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(PamSpacing.xs),
                    ) {
                        StatusBadge(
                            text = if (notificationsEnabled) {
                                stringResource(R.string.settings_notif_on)
                            } else {
                                stringResource(R.string.settings_notif_off)
                            },
                            tone = if (notificationsEnabled) {
                                BadgeTone.Success
                            } else {
                                BadgeTone.Neutral
                            },
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = stringResource(R.string.settings_notifications_open_settings),
                            style = LabelM,
                            color = OnInk,
                            modifier = Modifier
                                .clip(RoundedCornerShape(PamRadius.xs))
                                .clickable(role = Role.Button) {
                                    context.startActivity(
                                        TransactionNotifications.systemSettingsIntent(context),
                                    )
                                }
                                .padding(PamSpacing.xs),
                        )
                    }
                    if (notificationsEnabled && notificationsDenied) {
                        Spacer(Modifier.height(PamSpacing.sm))
                        NoticeBanner(
                            text = stringResource(R.string.settings_notifications_denied),
                            tone = MoneyTone.Expense,
                        )
                    }
                }
            }

            // Kategori -------------------------------------------------------------------
            SettingsSection(title = stringResource(R.string.settings_section_categories)) {
                SettingsCard(onClick = onOpenCategories) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(PamSpacing.sm),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Label,
                            contentDescription = null,
                            tint = OnInk,
                            modifier = Modifier.size(22.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.category_manage_title),
                                style = TitleM,
                                color = OnInk,
                            )
                            Text(
                                text = stringResource(R.string.settings_categories_sub),
                                style = BodyS,
                                color = OnInkMuted,
                            )
                        }
                    }
                    Chevron()
                }
            }

            // Widget ---------------------------------------------------------------------
            val widgetUnsupported = stringResource(R.string.settings_widget_unsupported)
            SettingsSection(title = stringResource(R.string.settings_section_widget)) {
                SettingsCard(
                    onClick = {
                        val manager = AppWidgetManager.getInstance(context)
                        val provider = ComponentName(context, PamSummaryWidgetReceiver::class.java)
                        val requested = manager.requestPinAppWidget(provider, null, null)
                        if (!requested) {
                            Toast.makeText(
                                context,
                                widgetUnsupported,
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(PamSpacing.sm),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Widgets,
                            contentDescription = null,
                            tint = OnInk,
                            modifier = Modifier.size(22.dp),
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_widget_title),
                                style = TitleM,
                                color = OnInk,
                            )
                            Text(
                                text = stringResource(R.string.settings_widget_sub),
                                style = BodyS,
                                color = OnInkMuted,
                            )
                        }
                    }
                    Chevron()
                }
            }

            // Data & privasi (extra section kept from the previous layout) ------------------
            SettingsSection(title = stringResource(R.string.settings_section_privacy)) {
                Column(verticalArrangement = Arrangement.spacedBy(PamSpacing.sm)) {
                    SettingsCard(onClick = onOpenDeletion) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
                            contentDescription = null,
                            tint = OnInkMuted,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(Modifier.width(PamSpacing.sm))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.settings_delete),
                                style = TitleM,
                                color = OnInk,
                            )
                            Text(
                                text = stringResource(R.string.settings_delete_body),
                                style = BodyS,
                                color = OnInkMuted,
                            )
                        }
                        Chevron()
                    }

                    if (state.demoRowCount > 0) {
                        SettingsCard {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    text = stringResource(R.string.settings_demo_eyebrow),
                                    style = LabelEyebrow,
                                    color = OnInkMuted,
                                )
                                Spacer(Modifier.height(PamSpacing.xs))
                                Text(
                                    text = stringResource(
                                        R.string.settings_demo_count,
                                        state.demoRowCount,
                                    ),
                                    style = TitleM,
                                    color = OnInk,
                                )
                                Spacer(Modifier.height(PamSpacing.xxs))
                                Text(
                                    text = stringResource(R.string.settings_demo_body),
                                    style = BodyM,
                                    color = OnInkMuted,
                                )
                                Spacer(Modifier.height(PamSpacing.sm))
                                if (state.confirmingPurge) {
                                    NoticeBanner(
                                        text = stringResource(
                                            R.string.settings_demo_confirm,
                                            state.demoRowCount,
                                        ),
                                        tone = MoneyTone.Expense,
                                    )
                                    Spacer(Modifier.height(PamSpacing.xs))
                                    PillButton(
                                        text = stringResource(R.string.settings_demo_confirm_action),
                                        onClick = viewModel::confirmPurge,
                                        loading = state.purgeBusy,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Spacer(Modifier.height(PamSpacing.xxs))
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
                }
            }

            // Keluar ---------------------------------------------------------------------
            PillButton(
                text = stringResource(R.string.settings_logout_cta),
                onClick = viewModel::signOut,
                style = PillButtonStyle.DangerSoft,
                enabled = !state.isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
            )
            Spacer(Modifier.height(PamSpacing.xxs))
        }
    }

    if (confirmingGmailDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmingGmailDisconnect = false },
            title = {
                Text(
                    text = stringResource(R.string.gmail_link_disconnect_title),
                    style = TitleM,
                    color = OnInk,
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.gmail_link_disconnect_body),
                    style = BodyM,
                    color = OnInkMuted,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingGmailDisconnect = false
                        viewModel.disconnect()
                    },
                ) {
                    Text(stringResource(R.string.gmail_link_disconnect))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingGmailDisconnect = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            containerColor = Surface,
            shape = RoundedCornerShape(PamRadius.xl),
        )
    }
}

@Composable
private fun SettingsHeader() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = PamSpacing.gutter),
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = TitleL,
            color = OnInk,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center),
        )
        Box(modifier = Modifier.align(Alignment.CenterEnd)) {
            UpdateStatusTopBarAction()
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(PamSpacing.xs)) {
        Text(
            text = title.uppercase(),
            style = LabelM,
            color = OnInkMuted,
            modifier = Modifier.padding(horizontal = PamSpacing.xxs),
        )
        content()
    }
}

@Composable
private fun SettingsCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val base = modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(PamRadius.xl))
        .background(Surface)
        .then(
            if (onClick != null) {
                Modifier.clickable(role = Role.Button, onClick = onClick)
            } else {
                Modifier
            },
        )
        .padding(PamSpacing.md)
    Row(
        modifier = base,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

@Composable
private fun Avatar(initials: String?) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Primary),
        contentAlignment = Alignment.Center,
    ) {
        if (initials == null) {
            Icon(
                imageVector = Icons.Rounded.Person,
                contentDescription = null,
                tint = OnPrimary,
                modifier = Modifier.size(22.dp),
            )
        } else {
            Text(text = initials, style = TitleM, color = OnPrimary)
        }
    }
}

private enum class BadgeTone { Success, Neutral }

@Composable
private fun StatusBadge(text: String, tone: BadgeTone) {
    val container = if (tone == BadgeTone.Success) SuccessContainer else SurfaceLow
    val foreground = if (tone == BadgeTone.Success) OnInk else OnInkMuted
    Text(
        text = text,
        style = LabelEyebrow,
        color = foreground,
        modifier = Modifier
            .clip(RoundedCornerShape(PamRadius.pill))
            .background(container)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun Chevron() {
    Icon(
        imageVector = Icons.Rounded.ChevronRight,
        contentDescription = null,
        tint = OnInkMuted,
        modifier = Modifier.size(20.dp),
    )
}

@Composable
private fun SectionDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = PamSpacing.sm)
            .height(1.dp)
            .background(OutlineVariant),
    )
}

@Composable
private fun AiConsentRow(
    title: String,
    body: String,
    onClick: () -> Unit,
    status: Consent? = null,
) {
    val suffix = status?.let {
        " · " + if (it.isGranted) {
            stringResource(R.string.settings_notif_on)
        } else {
            stringResource(R.string.settings_notif_off)
        }
    }.orEmpty()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PamRadius.sm))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = PamSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PamSpacing.sm),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = TitleM, color = OnInk)
            Text(text = body + suffix, style = BodyS, color = OnInkMuted)
        }
        Chevron()
    }
}

@Composable
private fun LinkRow(
    icon: ImageVector,
    title: String,
    body: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PamRadius.sm))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = PamSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PamSpacing.sm),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = OnInkMuted, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = TitleM, color = OnInk)
            Text(text = body, style = BodyS, color = OnInkMuted)
        }
        Chevron()
    }
}
