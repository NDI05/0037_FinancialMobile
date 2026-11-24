package com.pam.recap

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.pam.recap.core.platform.ActivityHost
import com.pam.recap.core.util.AppLocale
import com.pam.recap.ui.navigation.PamNavHost
import com.pam.recap.ui.theme.Ink
import com.pam.recap.BuildConfig
import com.pam.recap.ui.theme.PamTheme
import androidx.lifecycle.lifecycleScope
import com.pam.recap.domain.repository.AutoCategoryScheduler
import com.pam.recap.domain.repository.AutoSyncScheduler
import com.pam.recap.domain.repository.SessionRepository
import com.pam.recap.domain.usecase.AutoCategoryUseCase
import com.pam.recap.domain.model.GmailLinkResult
import com.pam.recap.ui.feature.gmail.GmailLinkIntentGate
import com.pam.recap.domain.usecase.IncrementalSyncUseCase
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

/**
 * Single-activity host for the Compose navigation graph.
 *
 * CR-001/Fase 2 removed the Google hooks ([ActivityHolder] and the Activity-result forwarder); the
 * account flow is pure Compose + HTTP. `FLAG_SECURE` stays on so the recap never appears in the
 * recents thumbnail (NFR-16).
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** Result of `pam://gmail/connected…`, consumed by the nav host once the gate is Ready. */
    private var gmailLinkResult by mutableStateOf<GmailLinkResult?>(null)

    /** Drops duplicate/replayed callback intents (recreate, duplicate delivery). */
    private lateinit var gmailLinkIntentGate: GmailLinkIntentGate

    @Inject
    lateinit var sessions: SessionRepository

    @Inject
    lateinit var autoCategory: AutoCategoryScheduler

    @Inject
    lateinit var autoSync: AutoSyncScheduler

    @Inject
    lateinit var incrementalSync: IncrementalSyncUseCase

    @Inject
    lateinit var autoCategoryRun: AutoCategoryUseCase

    /** Credential Manager needs the running Activity for the Google account sheet. */
    @Inject
    lateinit var activityHost: ActivityHost

    /**
     * The only activity. Its base context is created from the system configuration, so the
     * Indonesian pin has to be applied here as well as in the application (see [AppLocale]).
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // NFR-16 keeps the recap out of the recents thumbnail and blocks screen capture.
        // Debug builds opt out (PAM_ALLOW_SCREEN_CAPTURE) so the screen can be mirrored while
        // testing; the release build type hard-codes the flag to false.
        if (!BuildConfig.PAM_ALLOW_SCREEN_CAPTURE) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        }
        activityHost.attach(this)
        gmailLinkIntentGate = GmailLinkIntentGate(
            savedInstanceState?.getString(GmailLinkIntentGate.KEY_HANDLED_INTENT),
        )
        readGmailLink(intent)
        setContent {
            PamTheme {
                com.pam.recap.ui.feature.status.UpdateStatusActionProvider {
                    Box(modifier = Modifier.fillMaxSize().background(Ink)) {
                        PamNavHost(
                            gmailLinkResult = gmailLinkResult,
                            onGmailLinkHandled = { gmailLinkResult = null },
                        )
                    }
                }
            }
        }
    }

    /**
     * The OAuth callback returns from the browser as `pam://gmail/connected?status=…&reason=…`
     * (contract §2). Only the projection is kept; no token or code is read or stored.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readGmailLink(intent)
    }

    override fun onDestroy() {
        activityHost.detach(this)
        super.onDestroy()
    }

    private fun readGmailLink(intent: Intent?) {
        val data = intent?.data
        val result = gmailLinkIntentGate.accept(
            action = intent?.action,
            data = data?.toString(),
            scheme = data?.scheme,
            host = data?.host,
            path = data?.path,
            status = data?.getQueryParameter("status"),
            reason = data?.getQueryParameter("reason"),
        ) ?: return
        gmailLinkResult = result
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(
            GmailLinkIntentGate.KEY_HANDLED_INTENT,
            gmailLinkIntentGate.handledKeySnapshot,
        )
    }

    /**
     * Foreground freshness check: on every return to the app, if the last attempt is older than the
     * cooldown, run one delta. It shares the single-flight gate with the worker, so a background run
     * in progress makes this a no-op instead of a second import.
     */
    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            val ownerId = sessions.current()?.ownerId ?: return@launch
            // Idempotent; also covers an install that was already signed in before this feature.
            runCatching { autoSync.ensureScheduled(ownerId) }
            runCatching { incrementalSync.hydrate(ownerId) }
            runCatching { incrementalSync.runIfStale() }
            // The drain itself is throttled by the server; a foreground visit just makes sure the
            // schedule exists and the panel has a fresh backlog number.
            runCatching { autoCategory.ensureScheduled(ownerId) }
            // One bounded drain attempt: the server answers "not_due" without any provider call
            // when its 2-hour window is closed, and this is what fills the status panel.
            runCatching { autoCategoryRun.runIfConnected(ownerId) }
        }
    }
}
