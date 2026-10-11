package io.github.mdmac1983.leancleaner.settings

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mdmac1983.leancleaner.automation.AccessibilitySetupDialog
import io.github.mdmac1983.leancleaner.automation.CacheAutomation
import io.github.mdmac1983.leancleaner.automation.rememberAutomationEnabled
import io.github.mdmac1983.leancleaner.core.Format
import io.github.mdmac1983.leancleaner.core.Permissions
import io.github.mdmac1983.leancleaner.core.Volumes
import io.github.mdmac1983.leancleaner.core.startActivitySafely
import io.github.mdmac1983.leancleaner.prefs
import io.github.mdmac1983.leancleaner.ui.components.SectionTitle
import io.github.mdmac1983.leancleaner.ui.components.ToolScaffold
import io.github.mdmac1983.leancleaner.ui.components.prettyPath
import io.github.mdmac1983.leancleaner.ui.components.rememberPermissionState
import io.github.mdmac1983.leancleaner.ui.icons.AppIcons

private val LARGE_FILE_OPTIONS = listOf(10_000_000L, 50_000_000L, 100_000_000L, 500_000_000L)

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.prefs
    val storage = rememberPermissionState { Permissions.hasStorageAccess(context) }
    val usage = rememberPermissionState { Permissions.hasUsageAccess(context) }
    val exclusions by prefs.exclusions.collectAsStateWithLifecycle()
    val threshold by prefs.largeFileThreshold.collectAsStateWithLifecycle()
    val freed by prefs.totalFreed.collectAsStateWithLifecycle()
    var addExclusion by rememberSaveable { mutableStateOf(false) }
    var automationSetup by rememberSaveable { mutableStateOf(false) }
    if (automationSetup) AccessibilitySetupDialog(onDismiss = { automationSetup = false })

    ToolScaffold(title = "Settings", onBack = onBack) { padding ->
        LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp)) {
            item { SectionTitle("Permissions") }
            item {
                PermissionItem(
                    title = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) "All files access" else "Storage access",
                    description = "Scan and clean shared storage",
                    granted = storage,
                    onGrant = {
                        context.startActivitySafely(
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Permissions.allFilesAccessIntent(context)
                            else Permissions.appDetailsIntent(context.packageName),
                            fallback = Permissions.appDetailsIntent(context.packageName),
                        )
                    },
                )
            }
            item {
                PermissionItem(
                    title = "Usage access",
                    description = "App sizes, cache sizes and last-used dates",
                    granted = usage,
                    onGrant = {
                        context.startActivitySafely(Permissions.usageAccessIntent(context), Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    },
                )
            }

            item {
                val automation = rememberAutomationEnabled()
                ListItem(
                    headlineContent = { Text("One-tap cache cleaning") },
                    supportingContent = { Text("Accessibility service that presses \"Clear cache\" in Settings for you") },
                    leadingContent = {
                        Icon(
                            if (automation) AppIcons.CheckCircle else AppIcons.Info,
                            null,
                            tint = if (automation) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent = {
                        if (automation) {
                            TextButton(onClick = {
                                context.startActivitySafely(CacheAutomation.settingsIntent(context), CacheAutomation.fallbackSettingsIntent())
                            }) { Text("Manage") }
                        } else {
                            FilledTonalButton(onClick = { automationSetup = true }) { Text("Set up") }
                        }
                    },
                )
            }

            item { SectionTitle("Exclusions") }
            item {
                Text(
                    "Excluded folders and files are skipped by every scan and never deleted. " +
                        "Tip: long-press an item in scan results to exclude it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            items(exclusions.sorted(), key = { it }) { path ->
                ListItem(
                    headlineContent = { Text(prettyPath(path)) },
                    leadingContent = { Icon(AppIcons.Shield, null) },
                    trailingContent = {
                        IconButton(onClick = { prefs.removeExclusion(path) }) { Icon(AppIcons.Close, "Remove exclusion") }
                    },
                )
            }
            item {
                TextButton(onClick = { addExclusion = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                    Icon(AppIcons.Add, null)
                    Text("Add exclusion", modifier = Modifier.padding(start = 8.dp))
                }
            }

            item { SectionTitle("Storage analyzer") }
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text("List files larger than", style = MaterialTheme.typography.bodyMedium)
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LARGE_FILE_OPTIONS.forEach { bytes ->
                            FilterChip(
                                selected = threshold == bytes,
                                onClick = { prefs.setLargeFileThreshold(bytes) },
                                label = { Text("${bytes / 1_000_000} MB") },
                            )
                        }
                    }
                }
            }

            item { SectionTitle("About") }
            item {
                ListItem(
                    headlineContent = { Text("Lean Cleaner ${versionName(context)}") },
                    supportingContent = {
                        Text(
                            "No ads, no trackers, no accounts and no internet permission. Nothing runs in the background " +
                                "and everything stays on your device. Space freed so far: ${Format.size(freed)}.",
                        )
                    },
                )
            }
        }
    }

    if (addExclusion) {
        AddExclusionDialog(
            initial = Volumes.roots(context).first().absolutePath + "/",
            onAdd = {
                prefs.addExclusion(it)
                addExclusion = false
            },
            onDismiss = { addExclusion = false },
        )
    }
}

@Composable
private fun PermissionItem(title: String, description: String, granted: Boolean, onGrant: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        leadingContent = {
            Icon(
                if (granted) AppIcons.CheckCircle else AppIcons.Warning,
                null,
                tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        },
        trailingContent = {
            if (granted) Text("Granted", color = MaterialTheme.colorScheme.primary) else FilledTonalButton(onClick = onGrant) { Text("Grant") }
        },
    )
}

@Composable
private fun AddExclusionDialog(initial: String, onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    var path by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add exclusion") },
        text = {
            Column {
                Text("Full path of a folder or file to protect:", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(path) }, enabled = path.isNotBlank()) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun versionName(context: Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName
}.getOrNull().orEmpty()
