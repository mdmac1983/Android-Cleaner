package io.github.mdmac1983.leancleaner.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mdmac1983.leancleaner.core.FileKind
import io.github.mdmac1983.leancleaner.core.Format
import io.github.mdmac1983.leancleaner.ui.icons.AppIcons
import kotlinx.coroutines.flow.Flow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolScaffold(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit = {},
    snackbarHostState: SnackbarHostState? = null,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) { Icon(AppIcons.ArrowBack, contentDescription = "Back") }
                    }
                },
                actions = actions,
            )
        },
        bottomBar = bottomBar,
        snackbarHost = { if (snackbarHostState != null) SnackbarHost(snackbarHostState) },
        content = content,
    )
}

/** Shows one-off messages (e.g. "Freed 1.2 GB") from a ViewModel flow as snackbars. */
@Composable
fun rememberMessageSnackbar(messages: Flow<String>): SnackbarHostState {
    val host = remember { SnackbarHostState() }
    LaunchedEffect(messages) {
        messages.collect { host.showSnackbar(it) }
    }
    return host
}

@Composable
fun ToolIntro(
    icon: ImageVector,
    title: String,
    description: String,
    buttonText: String,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        extra()
        Spacer(Modifier.height(24.dp))
        Button(onClick = onStart) { Text(buttonText) }
    }
}

@Composable
fun ScanProgress(
    title: String,
    detail: String?,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    onCancel: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (progress != null) {
            CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(64.dp), strokeCap = StrokeCap.Round)
        } else {
            CircularProgressIndicator(modifier = Modifier.size(64.dp), strokeCap = StrokeCap.Round)
        }
        Spacer(Modifier.height(24.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (!detail.isNullOrEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
        if (onCancel != null) {
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
fun EmptyResult(title: String, message: String, onRescan: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(AppIcons.CheckCircle, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        TextButton(onClick = onRescan) { Text("Scan again") }
    }
}

/** Bottom bar summarizing the selection with the destructive action. */
@Composable
fun SelectionBar(
    summary: String,
    actionText: String,
    enabled: Boolean,
    onAction: () -> Unit,
    secondary: (@Composable RowScope.() -> Unit)? = null,
) {
    Surface(tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(summary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            secondary?.invoke(this)
            Button(
                onClick = onAction,
                enabled = enabled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) {
                Icon(AppIcons.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(actionText)
            }
        }
    }
}

@Composable
fun ConfirmDeleteDialog(
    count: Int,
    bytes: Long,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    warning: String? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(AppIcons.Delete, contentDescription = null) },
        title = { Text("Delete ${Format.count(count, "item")}?") },
        text = {
            Column {
                Text("This frees ${Format.size(bytes)} and can't be undone.")
                if (warning != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(warning, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun InfoCard(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = AppIcons.Info,
    title: String? = null,
    container: Color = MaterialTheme.colorScheme.secondaryContainer,
    action: (@Composable () -> Unit)? = null,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, modifier = Modifier.padding(top = 2.dp).size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                if (title != null) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                }
                Text(text, style = MaterialTheme.typography.bodyMedium)
                if (action != null) {
                    Spacer(Modifier.height(8.dp))
                    action()
                }
            }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
fun UsageBar(fraction: Float, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    LinearProgressIndicator(
        progress = { fraction.coerceIn(0f, 1f) },
        modifier = modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp)),
        color = color,
        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        strokeCap = StrokeCap.Round,
        gapSize = 0.dp,
        drawStopIndicator = {},
    )
}

/** Label/value row used on info screens. */
@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.42f))
        Text(value, modifier = Modifier.weight(0.58f), textAlign = TextAlign.End)
    }
}

fun FileKind.icon(): ImageVector = when (this) {
    FileKind.IMAGE -> AppIcons.Image
    FileKind.VIDEO -> AppIcons.Movie
    FileKind.AUDIO -> AppIcons.AudioFile
    FileKind.DOCUMENT -> AppIcons.Description
    FileKind.ARCHIVE -> AppIcons.FolderZip
    FileKind.APK -> AppIcons.Android
    FileKind.OTHER -> AppIcons.InsertDriveFile
}

/** Shared storage paths shown relative to their volume: "DCIM/Camera" or "SD card/Music". */
fun prettyPath(path: String): String {
    // Internal storage of the current user: /storage/emulated/0, or /storage/emulated/10 etc. for other profiles.
    INTERNAL_ROOT.find(path)?.let { internal ->
        val rest = path.removePrefix(internal.value).trimStart('/')
        return rest.ifEmpty { "Internal storage" }
    }
    val sd = SD_ROOT.find(path) ?: return path
    val rest = path.removePrefix(sd.value).trimStart('/')
    return if (rest.isEmpty()) "SD card" else "SD card/$rest"
}

/** Parent folder of [path] in [prettyPath] form. */
fun prettyParent(path: String): String = prettyPath(path.substringBeforeLast('/'))

private val INTERNAL_ROOT = Regex("^/storage/emulated/\\d+(?=/|$)")
private val SD_ROOT = Regex("^/storage/[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}(?=/|$)")
