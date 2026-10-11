package io.github.mdmac1983.leancleaner.automation

import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mdmac1983.leancleaner.core.Permissions
import io.github.mdmac1983.leancleaner.core.startActivitySafely
import io.github.mdmac1983.leancleaner.ui.components.rememberPermissionState
import io.github.mdmac1983.leancleaner.ui.icons.AppIcons

/** Whether the cache-cleaning accessibility service is switched on (re-checked on resume). */
@Composable
fun rememberAutomationEnabled(): Boolean {
    val context = LocalContext.current
    return rememberPermissionState { CacheAutomation.isEnabled(context) }
}

/** Prominent disclosure + setup steps for the accessibility service. */
@Composable
fun AccessibilitySetupDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val restrictedSettings = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(AppIcons.CleaningServices, contentDescription = null) },
        title = { Text("One-tap cache cleaning") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Android only lets apps clear other apps' caches from Settings. Lean Cleaner can do the " +
                        "tapping for you: it opens each app's Storage page and presses \"Clear cache\".",
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "This uses Lean Cleaner's accessibility service. It is only active during a cleaning run " +
                        "you start, only acts on the Settings app, never touches app data, and nothing leaves " +
                        "your phone.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Android may later remind you that Lean Cleaner \"can view and control your screen\". That's the " +
                        "standard notice for this kind of permission; you can switch the service off any time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "On the next screen, open \"Lean Cleaner: clear app caches\" (it may be under \"Downloaded apps\" " +
                        "or \"Installed apps\") and turn it on.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (restrictedSettings) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "If Android says the setting is restricted (normal for apps installed from an APK file): " +
                            "open App info, tap ⋮ in the top corner, choose \"Allow restricted settings\", then try again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                context.startActivitySafely(CacheAutomation.settingsIntent(context), CacheAutomation.fallbackSettingsIntent())
                onDismiss()
            }) { Text("Open settings") }
        },
        dismissButton = {
            Row {
                if (restrictedSettings) {
                    TextButton(onClick = {
                        context.startActivitySafely(Permissions.appDetailsIntent(context.packageName))
                    }) { Text("App info") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** Live progress of a running cache-cleaning run with a Stop button; shows nothing when idle. */
@Composable
fun CacheRunProgress(modifier: Modifier = Modifier) {
    val state by CacheAutomation.state.collectAsStateWithLifecycle()
    val preparing by CacheCleaner.preparing.collectAsStateWithLifecycle()
    val running = state as? CacheAutomation.State.Running
    if (running == null) {
        if (preparing) {
            Column(modifier.fillMaxWidth()) {
                Text("Finding apps with cache…", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp))
            }
        }
        return
    }
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Clearing ${running.index + 1} of ${running.total} · ${running.current}",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = CacheAutomation::stop) { Text("Stop") }
        }
        LinearProgressIndicator(
            progress = { if (running.total > 0) (running.index + 1f) / running.total else 0f },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}
