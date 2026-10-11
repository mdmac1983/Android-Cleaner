package io.github.mdmac1983.leancleaner.ui.components

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.mdmac1983.leancleaner.core.Permissions
import io.github.mdmac1983.leancleaner.core.startActivitySafely
import io.github.mdmac1983.leancleaner.ui.icons.AppIcons

/** Re-checks [check] whenever the screen resumes (e.g. after returning from system settings). */
@Composable
fun rememberPermissionState(check: () -> Boolean): Boolean {
    var granted by remember { mutableStateOf(check()) }
    LifecycleResumeEffect(Unit) {
        granted = check()
        onPauseOrDispose {}
    }
    return granted
}

/** Shows [content] only once shared-storage access is granted, otherwise explains and asks. */
@Composable
fun StorageGate(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val context = LocalContext.current
    // The permission dialog pauses/resumes this screen, so the resume check picks up the result.
    val granted = rememberPermissionState { Permissions.hasStorageAccess(context) }
    var denials by remember { mutableIntStateOf(0) }
    val legacyRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (!Permissions.hasStorageAccess(context) && ++denials >= 2) {
            // Denied again (possibly "don't ask again"): the app's settings page is the only way left.
            context.startActivitySafely(Permissions.appDetailsIntent(context.packageName))
        }
    }
    if (granted) {
        content()
        return
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(AppIcons.Storage, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("Storage access needed", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                "To find junk, leftovers and duplicates, allow \"All files access\" on the next screen. " +
                    "Lean Cleaner has no internet permission, so nothing it sees ever leaves your phone."
            } else {
                "To find junk, leftovers and duplicates, allow access to your files. " +
                    "Lean Cleaner has no internet permission, so nothing it sees ever leaves your phone."
            },
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.startActivitySafely(
                    Permissions.allFilesAccessIntent(context),
                    fallback = Permissions.appDetailsIntent(context.packageName),
                )
            } else {
                legacyRequest.launch(Permissions.legacyStoragePermissions)
            }
        }) { Text("Grant access") }
    }
}
