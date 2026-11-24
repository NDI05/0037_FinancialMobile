package com.pam.recap.ui.feature.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MarkEmailRead
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.pam.recap.R
import com.pam.recap.ui.components.EyebrowText
import com.pam.recap.ui.components.GlassCard
import com.pam.recap.ui.components.GlassTopBar
import com.pam.recap.ui.components.LabelledField
import com.pam.recap.ui.components.NoticeBanner
import com.pam.recap.ui.components.PamScaffold
import com.pam.recap.ui.components.PillButton
import com.pam.recap.ui.components.PillButtonStyle
import com.pam.recap.ui.theme.BodyM
import com.pam.recap.ui.theme.HeadlineL
import com.pam.recap.ui.theme.LabelM
import com.pam.recap.ui.theme.OnInk
import com.pam.recap.ui.theme.OnInkMuted
import com.pam.recap.ui.theme.PamSpacing
import com.pam.recap.ui.theme.TitleL
import com.pam.recap.ui.util.appErrorRes

/** Stable test tags for the Google SSO / account Compose tests. */
object AccountTestTags {
    const val GOOGLE_SIGN_IN = "account_google_sign_in"
    const val GOOGLE_LINK = "account_google_link"
    const val GOOGLE_LINKED = "account_google_linked"
    const val GMAIL_MANAGE = "account_gmail_manage"
}

/**
 * FR-51/FR-52 account surface: sign in or register against the PAM backend, or create a local
 * profile when no backend is configured. The difference is stated in the copy and in the badge —
 * never implied.
 *
 * Google SSO (contract: `/v1/auth/google*`) adds one more door instead of replacing the form:
 * "Masuk dengan Google" sits above the email/password form and only appears when the build
 * configured a Web client ID. The same screen doubles as the signed-in "Akun" editor reached from
 * Settings, where an existing account can link Google and reopen the Gmail link flow.
 */
@Composable
fun AccountScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenGmailLink: () -> Unit = {},
    viewModel: AccountViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    PamScaffold(
        modifier = modifier,
        showAurora = false,
        topBar = { GlassTopBar(title = stringResource(R.string.account_title), onBack = onBack) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            EyebrowText(text = stringResource(R.string.account_eyebrow))
            Spacer(modifier = Modifier.height(PamSpacing.xs))
            Text(
                text = stringResource(
                    when {
                        state.isSignedIn -> R.string.account_signed_in_headline
                        !state.isBackendEnabled -> R.string.account_headline_offline
                        state.mode == AccountMode.REGISTER -> R.string.account_headline_register
                        else -> R.string.account_headline_sign_in
                    },
                ),
                style = HeadlineL,
                color = OnInk,
            )
            Spacer(modifier = Modifier.height(PamSpacing.xs))
            Text(
                text = stringResource(
                    when {
                        state.isSignedIn -> R.string.account_signed_in_body
                        !state.isBackendEnabled -> R.string.account_body_offline
                        state.mode == AccountMode.REGISTER -> R.string.account_body_register
                        else -> R.string.account_body_sign_in
                    },
                ),
                style = BodyM,
                color = OnInkMuted,
            )

            if (state.isSignedIn) {
                SignedInSections(
                    state = state,
                    onLinkGoogle = viewModel::linkGoogle,
                    onOpenGmailLink = onOpenGmailLink,
                    onClearLinkNotices = {
                        viewModel.clearGoogleLinkError()
                        viewModel.clearGoogleLinkNotice()
                    },
                )
                return@Column
            }

            Spacer(modifier = Modifier.height(PamSpacing.sm))
            // The badge is the explicit marker of *where this account lives*.
            Text(
                text = stringResource(
                    if (state.isBackendEnabled) R.string.account_backend_badge else R.string.account_offline_badge,
                ),
                style = LabelM,
                color = OnInkMuted,
                modifier = Modifier.padding(top = PamSpacing.xxs),
            )

            if (state.showGoogleSignIn) {
                Spacer(modifier = Modifier.height(PamSpacing.md))
                PillButton(
                    text = stringResource(R.string.account_google_sign_in),
                    onClick = viewModel::signInWithGoogle,
                    loading = state.googleBusy,
                    enabled = !state.isBusy && !state.googleBusy,
                    leadingIcon = Icons.Rounded.AccountCircle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(AccountTestTags.GOOGLE_SIGN_IN),
                )
                Spacer(modifier = Modifier.height(PamSpacing.xs))
                Text(
                    text = stringResource(R.string.account_google_sign_in_body),
                    style = LabelM,
                    color = OnInkMuted,
                )
                Spacer(modifier = Modifier.height(PamSpacing.md))
                Text(
                    text = stringResource(R.string.account_google_or_email),
                    style = LabelM,
                    color = OnInkMuted,
                )
            }

            if (state.isBackendEnabled) {
                Spacer(modifier = Modifier.height(PamSpacing.md))
                com.pam.recap.ui.components.PillToggleGroup(
                    options = listOf(
                        com.pam.recap.ui.components.PillToggleOption(
                            AccountMode.SIGN_IN,
                            stringResource(R.string.account_submit_sign_in),
                        ),
                        com.pam.recap.ui.components.PillToggleOption(
                            AccountMode.REGISTER,
                            stringResource(R.string.account_submit_register),
                        ),
                    ),
                    selected = state.mode,
                    onSelect = viewModel::setMode,
                )
            }

            Spacer(modifier = Modifier.height(PamSpacing.md))
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(PamSpacing.sm)) {
                    LabelledField(
                        label = stringResource(R.string.account_email_label),
                        value = state.email,
                        onValueChange = viewModel::setEmail,
                        placeholder = stringResource(R.string.account_email_placeholder),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Next,
                        ),
                    )

                    if (state.isBackendEnabled) {
                        LabelledField(
                            label = stringResource(R.string.account_password_label),
                            value = state.password,
                            onValueChange = viewModel::setPassword,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done,
                            ),
                            visualTransformation = if (state.passwordVisible) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailing = {
                                IconButton(onClick = viewModel::togglePasswordVisibility) {
                                    Icon(
                                        imageVector = if (state.passwordVisible) {
                                            Icons.Rounded.VisibilityOff
                                        } else {
                                            Icons.Rounded.Visibility
                                        },
                                        contentDescription = stringResource(
                                            if (state.passwordVisible) {
                                                R.string.account_hide_password
                                            } else {
                                                R.string.account_show_password
                                            },
                                        ),
                                        tint = OnInkMuted,
                                    )
                                }
                            },
                        )
                    }

                    if (!state.isBackendEnabled || state.mode == AccountMode.REGISTER) {
                        LabelledField(
                            label = stringResource(R.string.account_display_name_label),
                            value = state.displayName,
                            onValueChange = viewModel::setDisplayName,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        )
                    }

                    if (state.isBackendEnabled && state.mode == AccountMode.REGISTER) {
                        LabelledField(
                            label = stringResource(R.string.account_invite_label),
                            value = state.inviteCode,
                            onValueChange = viewModel::setInviteCode,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        )
                        Text(
                            text = stringResource(R.string.account_invite_hint),
                            style = LabelM,
                            color = OnInkMuted,
                        )
                    }
                }
            }

            if (state.error != null) {
                Spacer(modifier = Modifier.height(PamSpacing.sm))
                NoticeBanner(
                    text = stringResource(appErrorRes(requireNotNull(state.error))),
                    tone = com.pam.recap.ui.components.MoneyTone.Neutral,
                )
            }

            Spacer(modifier = Modifier.height(PamSpacing.lg))
            PillButton(
                text = stringResource(
                    when {
                        !state.isBackendEnabled -> R.string.account_submit_offline
                        state.mode == AccountMode.REGISTER -> R.string.account_submit_register
                        else -> R.string.account_submit_sign_in
                    },
                ),
                onClick = viewModel::submit,
                loading = state.isBusy,
                enabled = state.canSubmit,
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.isBackendEnabled) {
                Spacer(modifier = Modifier.height(PamSpacing.xs))
                PillButton(
                    text = stringResource(
                        if (state.mode == AccountMode.REGISTER) {
                            R.string.account_switch_to_sign_in
                        } else {
                            R.string.account_switch_to_register
                        },
                    ),
                    onClick = {
                        viewModel.setMode(
                            if (state.mode == AccountMode.REGISTER) {
                                AccountMode.SIGN_IN
                            } else {
                                AccountMode.REGISTER
                            },
                        )
                    },
                    style = PillButtonStyle.Ghost,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Signed-in "Akun" editor: identity, Google link state and the Gmail entry point. */
@Composable
private fun SignedInSections(
    state: AccountUiState,
    onLinkGoogle: () -> Unit,
    onOpenGmailLink: () -> Unit,
    onClearLinkNotices: () -> Unit,
) {
    Spacer(modifier = Modifier.height(PamSpacing.md))
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(PamSpacing.xxs)) {
            Text(
                text = state.signedInLabel ?: stringResource(R.string.account_signed_in_profile),
                style = TitleL,
                color = OnInk,
            )
            Text(
                text = stringResource(R.string.account_backend_badge),
                style = LabelM,
                color = OnInkMuted,
            )
        }
    }

    if (state.googleLinked) {
        Spacer(modifier = Modifier.height(PamSpacing.sm))
        GlassCard(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(AccountTestTags.GOOGLE_LINKED),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(PamSpacing.xxs)) {
                Text(
                    text = stringResource(R.string.account_google_linked_title),
                    style = TitleL,
                    color = OnInk,
                )
                Text(
                    text = stringResource(R.string.account_google_linked_body),
                    style = BodyM,
                    color = OnInkMuted,
                )
            }
        }
    } else if (state.showGoogleLinkCard) {
        Spacer(modifier = Modifier.height(PamSpacing.sm))
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(PamSpacing.xs)) {
                Text(
                    text = stringResource(R.string.account_google_link_title),
                    style = TitleL,
                    color = OnInk,
                )
                Text(
                    text = stringResource(R.string.account_google_link_body),
                    style = BodyM,
                    color = OnInkMuted,
                )
                PillButton(
                    text = stringResource(R.string.account_google_link_action),
                    onClick = onLinkGoogle,
                    loading = state.googleBusy,
                    enabled = !state.googleBusy,
                    leadingIcon = Icons.Rounded.Link,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(AccountTestTags.GOOGLE_LINK),
                )
            }
        }
    }

    if (state.googleLinkError != null) {
        Spacer(modifier = Modifier.height(PamSpacing.sm))
        NoticeBanner(
            text = stringResource(appErrorRes(requireNotNull(state.googleLinkError))),
            tone = com.pam.recap.ui.components.MoneyTone.Neutral,
        )
        Spacer(modifier = Modifier.height(PamSpacing.xxs))
        PillButton(
            text = stringResource(R.string.account_dismiss),
            onClick = onClearLinkNotices,
            style = PillButtonStyle.Ghost,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    if (state.googleLinkNotice) {
        Spacer(modifier = Modifier.height(PamSpacing.sm))
        NoticeBanner(
            text = stringResource(R.string.account_google_link_done),
            tone = com.pam.recap.ui.components.MoneyTone.Income,
        )
        Spacer(modifier = Modifier.height(PamSpacing.xxs))
        PillButton(
            text = stringResource(R.string.account_dismiss),
            onClick = onClearLinkNotices,
            style = PillButtonStyle.Ghost,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    // Gmail is independent of Google SSO: a PAM session, manual rows and Gmail access all keep
    // working when the mailbox is disconnected (requirement: never block the app).
    Spacer(modifier = Modifier.height(PamSpacing.md))
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(PamSpacing.xs)) {
            Text(
                text = stringResource(R.string.account_gmail_title),
                style = TitleL,
                color = OnInk,
            )
            Text(
                text = stringResource(R.string.account_gmail_body),
                style = BodyM,
                color = OnInkMuted,
            )
            PillButton(
                text = stringResource(R.string.account_gmail_action),
                onClick = onOpenGmailLink,
                style = PillButtonStyle.Secondary,
                leadingIcon = Icons.Rounded.MarkEmailRead,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(AccountTestTags.GMAIL_MANAGE),
            )
        }
    }
}
