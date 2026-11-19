package com.pam.recap

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.pam.recap.core.util.ActivityHolder
import com.pam.recap.data.gmail.GoogleAuthBridge
import com.pam.recap.ui.navigation.PamNavHost
import com.pam.recap.ui.theme.Ink
import com.pam.recap.ui.theme.PamTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Single-activity host for the Compose navigation graph.
 *
 * Keeps the two platform hooks the real Gmail flow needs: [ActivityHolder] for Credential Manager /
 * Google authorization, and the [GoogleAuthBridge] Activity-result forwarder. `FLAG_SECURE` stays on
 * so the recap never appears in the recents thumbnail (NFR-16).
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var activityHolder: ActivityHolder

    @Inject
    lateinit var googleAuthBridge: GoogleAuthBridge

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        activityHolder.attach(this)
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

    override fun onDestroy() {
        activityHolder.detach(this)
        super.onDestroy()
    }

    @Deprecated("Deprecated in the platform, still the hook used by Google Identity Services")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (googleAuthBridge.onActivityResult(requestCode, resultCode, data)) return
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
    }
}
