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
import com.pam.recap.ui.theme.PamTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single-activity host for the Compose navigation graph.
 *
 * CR-001/Fase 2 removed the Google hooks ([ActivityHolder] and the Activity-result forwarder); the
 * account flow is pure Compose + HTTP. `FLAG_SECURE` stays on so the recap never appears in the
 * recents thumbnail (NFR-16).
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

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
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        setContent {
            PamTheme {
                Box(modifier = Modifier.fillMaxSize().background(Ink)) {
                    PamNavHost()
                }
            }
        }
    }
}
