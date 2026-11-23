package com.pam.recap

import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.pam.recap.core.util.AppLocale
import com.pam.recap.ui.navigation.PamNavHost
import com.pam.recap.ui.theme.Ink
import com.pam.recap.BuildConfig
import com.pam.recap.ui.theme.PamTheme
import androidx.lifecycle.lifecycleScope
import com.pam.recap.domain.repository.AutoSyncScheduler
import com.pam.recap.domain.repository.SessionRepository
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

    @Inject
    lateinit var sessions: SessionRepository

    @Inject
    lateinit var autoSync: AutoSyncScheduler

    @Inject
    lateinit var incrementalSync: IncrementalSyncUseCase

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
        setContent {
            PamTheme {
                Box(modifier = Modifier.fillMaxSize().background(Ink)) {
                    PamNavHost()
                }
            }
        }
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
            runCatching { incrementalSync.runIfStale() }
        }
    }
}
