package com.pam.recap.ui.feature.status

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pam.recap.ui.components.UpdateStatusPill
import com.pam.recap.ui.navigation.GmailLinkOpener
import com.pam.recap.ui.navigation.LocalGmailLinkOpener

/**
 * Slot the shell installs once, so a screen can render the indicator without owning a view model.
 *
 * Screens and previews/screenshot tests never touch Hilt: with no provider the slot is simply
 * absent and the top bar keeps its original layout.
 */
val LocalUpdateStatusAction = staticCompositionLocalOf<(@Composable (Modifier) -> Unit)?> { null }

/**
 * App-level "open the status sheet" entry point. The bottom bar's raised action uses it after the
 * retired import route was removed, so the one surface that starts a sync is the one sheet.
 */
val LocalOpenUpdateStatusSheet = staticCompositionLocalOf<(() -> Unit)?> { null }

/**
 * App-level provider: resolves the shared view model once and publishes the pill + sheet as a slot.
 * Mounting it performs no import and no category request; only an explicit tap inside the sheet does.
 */
@Composable
fun UpdateStatusActionProvider(content: @Composable () -> Unit) {
    val viewModel: UpdateStatusViewModel = hiltViewModel()
    val status by viewModel.state.collectAsStateWithLifecycle()
    val serverSync by viewModel.serverSync.collectAsStateWithLifecycle()
    var sheetOpen by remember { mutableStateOf(false) }
    // Freeze "now" when the sheet opens instead of re-reading it on every recomposition.
    val openedAt = remember(sheetOpen) { viewModel.now() }
    val retention = remember(sheetOpen) { viewModel.window() }
    val gmailLinkOpener = remember { GmailLinkOpener() }

    // Polling lives exactly as long as the sheet: a GET while open, nothing after dismissal. It
    // never starts or cancels the server job, which is the whole point of contract §9.3.
    LaunchedEffect(sheetOpen) {
        if (sheetOpen) viewModel.onSheetOpened() else viewModel.onSheetClosed()
    }
    DisposableEffect(Unit) { onDispose { viewModel.onSheetClosed() } }
    // Re-read the projection when the app returns to the foreground with the sheet still open.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshServerSync()
        onPauseOrDispose { }
    }

    CompositionLocalProvider(
        LocalUpdateStatusAction provides { modifier ->
            UpdateStatusPill(status = status, onClick = { sheetOpen = true }, modifier = modifier)
        },
        LocalOpenUpdateStatusSheet provides { sheetOpen = true },
        LocalGmailLinkOpener provides gmailLinkOpener,
    ) {
        content()
        if (sheetOpen) {
            UpdateStatusSheet(
                status = status,
                now = openedAt,
                onDismiss = { sheetOpen = false },
                onSyncNow = viewModel::syncNow,
                retention = retention,
                serverSync = serverSync,
                onServerSyncNow = viewModel::syncServerNow,
                onReconnectGmail = {
                    // Leave the sheet first so the Gmail screen is not covered on return.
                    sheetOpen = false
                    gmailLinkOpener.open()
                },
            )
        }
    }
}

/** Screen-side entry point: renders the slot when the shell provides one. */
@Composable
fun UpdateStatusTopBarAction(modifier: Modifier = Modifier) {
    val slot = LocalUpdateStatusAction.current ?: return
    slot(modifier)
}
