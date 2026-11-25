package com.pam.recap.ui.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import com.pam.recap.R
import com.pam.recap.data.widget.WidgetBank
import com.pam.recap.data.widget.WidgetSnapshotStore
import com.pam.recap.ui.theme.Canvas
import com.pam.recap.ui.theme.Ink
import com.pam.recap.ui.theme.Lime
import com.pam.recap.ui.theme.OnInk
import com.pam.recap.ui.theme.OnInkMuted
import com.pam.recap.ui.theme.OutlineVariant
import com.pam.recap.ui.theme.PamTheme
import com.pam.recap.ui.theme.Surface as PamSurface
import kotlinx.coroutines.launch

/**
 * CR-010 W-01: one bank per widget. Mandiri is selectable; Jago/BRI remain visible but disabled
 * because the backend has no bank dimension yet. Selecting a bank only stores the instance choice;
 * the aggregate itself remains shared per account/month.
 */
class WidgetConfigActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val appWidgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val store = WidgetSnapshotStore(this)
        val selected = store.widgetBank(appWidgetId).takeIf { it.isAvailable } ?: WidgetBank.MANDIRI

        setContent {
            PamTheme {
                WidgetConfigScreen(
                    selected = selected,
                    onConfirm = { bank ->
                        store.saveWidgetBank(appWidgetId, bank)
                        setResult(
                            RESULT_OK,
                            Intent().putExtra(
                                AppWidgetManager.EXTRA_APPWIDGET_ID,
                                appWidgetId,
                            ),
                        )
                        lifecycleScope.launch {
                            runCatching { PamWidgetRefresher(applicationContext).refreshAll() }
                            finish()
                        }
                    },
                    onCancel = { finish() },
                )
            }
        }
    }
}

@Composable
private fun WidgetConfigScreen(
    selected: WidgetBank,
    onConfirm: (WidgetBank) -> Unit,
    onCancel: () -> Unit,
) {
    var choice by remember { mutableStateOf(selected) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Canvas)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = stringResource(R.string.widget_config_eyebrow),
            color = OnInkMuted,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = stringResource(R.string.widget_config_title),
            color = Ink,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = stringResource(R.string.widget_config_body),
            color = OnInkMuted,
            fontSize = 14.sp,
        )
        Spacer(modifier = Modifier.height(4.dp))
        WidgetBank.entries.forEach { bank ->
            BankChoiceRow(
                bank = bank,
                selected = choice == bank,
                onSelect = { if (bank.isAvailable) choice = bank },
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.widget_config_preview),
            color = OnInkMuted,
            fontSize = 12.sp,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = { onConfirm(choice) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(22.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Lime,
                contentColor = Ink,
            ),
        ) {
            Text(
                text = stringResource(R.string.widget_config_confirm),
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            text = stringResource(R.string.widget_config_cancel),
            color = OnInkMuted,
            fontSize = 14.sp,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .clickable(onClick = onCancel)
                .padding(10.dp),
        )
    }
}

@Composable
private fun BankChoiceRow(
    bank: WidgetBank,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val enabled = bank.isAvailable
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onSelect),
        shape = RoundedCornerShape(20.dp),
        color = if (selected) Lime.copy(alpha = 0.28f) else PamSurface,
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) Lime else OutlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = bank.displayName,
                    color = if (enabled) Ink else OnInkMuted,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = if (enabled) {
                        stringResource(R.string.widget_config_available)
                    } else {
                        stringResource(R.string.widget_config_unavailable)
                    },
                    color = OnInkMuted,
                    fontSize = 12.sp,
                )
            }
            if (selected) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Lime,
                    modifier = Modifier.size(18.dp),
                ) {}
            }
        }
    }
}
