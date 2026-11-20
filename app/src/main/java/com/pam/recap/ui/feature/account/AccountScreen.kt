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
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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

/**
 * FR-51/FR-52 account surface: sign in or register against the PAM backend, or create a local
 * profile when no backend is configured. The difference is stated in the copy and in the badge —
 * never implied.
 */
@Composable
fun AccountScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
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
                        !state.isBackendEnabled -> R.string.account_body_offline
                        state.mode == AccountMode.REGISTER -> R.string.account_body_register
                        else -> R.string.account_body_sign_in
                    },
                ),
                style = BodyM,
                color = OnInkMuted,
            )
            Spacer(modifier = Modifier.height(PamSpacing.sm))
            // The badge is the explicit marker of *where this account lives* (FR-52).
            Text(
                text = stringResource(
                    if (state.isBackendEnabled) R.string.account_backend_badge else R.string.account_offline_badge,
                ),
                style = LabelM,
                color = OnInkMuted,
                modifier = Modifier.padding(top = PamSpacing.xxs),
            )

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
