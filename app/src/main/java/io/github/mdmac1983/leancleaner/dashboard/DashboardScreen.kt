package io.github.mdmac1983.leancleaner.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.mdmac1983.leancleaner.Route
import io.github.mdmac1983.leancleaner.core.Format
import io.github.mdmac1983.leancleaner.core.Volume
import io.github.mdmac1983.leancleaner.core.Volumes
import io.github.mdmac1983.leancleaner.monitor.BatterySnapshot
import io.github.mdmac1983.leancleaner.monitor.MemorySnapshot
import io.github.mdmac1983.leancleaner.monitor.SystemInfo
import io.github.mdmac1983.leancleaner.prefs
import io.github.mdmac1983.leancleaner.ui.components.rememberPolled
import io.github.mdmac1983.leancleaner.ui.icons.AppIcons

private class Tool(val route: Route, val icon: ImageVector, val title: String, val subtitle: String)

private val TOOLS = listOf(
    Tool(Route.JUNK, AppIcons.CleaningServices, "Junk cleaner", "Caches, temp files, empty folders, old APKs"),
    Tool(Route.LEFTOVERS, AppIcons.FolderDelete, "Leftovers", "Folders from apps you've uninstalled"),
    Tool(Route.DUPLICATES, AppIcons.ContentCopy, "Duplicates", "Identical photos, videos and files"),
    Tool(Route.ANALYZER, AppIcons.PieChart, "Storage analyzer", "What's using your space, folder by folder"),
    Tool(Route.APPS, AppIcons.Apps, "Apps", "Sizes, unused apps, batch uninstall, APK backup"),
    Tool(Route.MONITOR, AppIcons.Speed, "System monitor", "CPU, memory, battery and device info"),
)

private class Overview(val volumes: List<Volume>, val memory: MemorySnapshot, val battery: BatterySnapshot?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(onOpen: (Route) -> Unit, onQuickClean: () -> Unit) {
    val context = LocalContext.current
    val freed by context.prefs.totalFreed.collectAsStateWithLifecycle()
    val overview by rememberPolled(3_000) {
        Overview(Volumes.list(context), SystemInfo.memory(context), SystemInfo.battery(context))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lean Cleaner") },
                actions = {
                    IconButton(onClick = { onOpen(Route.SETTINGS) }) { Icon(AppIcons.Settings, "Settings") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "hero") { StorageHero(overview?.volumes?.firstOrNull(), onQuickClean) }
            item(key = "stats") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val memory = overview?.memory?.takeIf { it.totalBytes > 0 }
                    MiniStat(
                        icon = AppIcons.DeveloperBoard,
                        title = "Memory",
                        value = memory?.let { "${Format.percent(it.usedBytes, it.totalBytes)}%" } ?: "–",
                        detail = memory?.let { "${Format.size(it.availableBytes)} free" } ?: "",
                        onClick = { onOpen(Route.MONITOR) },
                        modifier = Modifier.weight(1f),
                    )
                    val battery = overview?.battery
                    MiniStat(
                        icon = if (battery?.isCharging == true) AppIcons.BatteryChargingFull else AppIcons.BatteryFull,
                        title = "Battery",
                        value = battery?.let { "${it.percent}%" } ?: "–",
                        detail = battery?.temperatureC?.let { "%.1f °C".format(it) } ?: battery?.status.orEmpty(),
                        onClick = { onOpen(Route.MONITOR) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            overview?.volumes?.drop(1)?.let { extra ->
                items(extra, key = { it.root.path }) { volume ->
                    MiniStat(
                        icon = AppIcons.SdStorage,
                        title = volume.label,
                        value = "${Format.percent(volume.usedBytes, volume.totalBytes)}%",
                        detail = "${Format.size(volume.freeBytes)} free of ${Format.size(volume.totalBytes)}",
                        onClick = { onOpen(Route.ANALYZER) },
                    )
                }
            }
            items(TOOLS, key = { it.route.name }) { tool -> ToolCard(tool) { onOpen(tool.route) } }
            item(key = "footer") {
                Text(
                    (if (freed > 0) "${Format.size(freed)} freed so far · " else "") +
                        "No ads · No trackers · No internet access",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun StorageHero(volume: Volume?, onQuickClean: () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            val fraction = volume?.let { if (it.totalBytes > 0) it.usedBytes.toFloat() / it.totalBytes else 0f } ?: 0f
            Box(contentAlignment = Alignment.Center) {
                Ring(fraction, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.headlineSmall)
                    Text("used", style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Text(volume?.label ?: "Internal storage", style = MaterialTheme.typography.titleMedium)
                if (volume != null) {
                    Text(
                        "${Format.size(volume.freeBytes)} free of ${Format.size(volume.totalBytes)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = onQuickClean) {
                    Icon(AppIcons.CleaningServices, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Quick clean")
                }
            }
        }
    }
}

@Composable
private fun Ring(fraction: Float, color: Color, track: Color) {
    Canvas(Modifier.size(104.dp)) {
        val stroke = 12.dp.toPx()
        val inset = stroke / 2
        val arcSize = Size(size.width - stroke, size.height - stroke)
        drawArc(track, -90f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
        drawArc(color, -90f, 360f * fraction.coerceIn(0f, 1f), false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
    }
}

@Composable
private fun MiniStat(
    icon: ImageVector,
    title: String,
    value: String,
    detail: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.titleLarge)
                if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ToolCard(tool: Tool, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(tool.icon, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(tool.title, style = MaterialTheme.typography.titleMedium)
                Text(tool.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(AppIcons.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
