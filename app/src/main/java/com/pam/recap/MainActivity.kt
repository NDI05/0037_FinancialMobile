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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.pam.recap.core.util.ActivityHolder
import com.pam.recap.data.gmail.GoogleAuthBridge
import com.pam.recap.ui.theme.PamTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Temporary host for the Stage A foundation. The navigation graph and the real screens arrive in
 * the UI stage; this activity only proves the data layer runs and provides the two platform hooks
 * the real Gmail authorization needs.
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
        // NFR-16: keep the recap out of the recents thumbnail.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        setContent {
            PamTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.stage_a_ready),
                        color = MaterialTheme.colorScheme.onBackground,
                    )
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
