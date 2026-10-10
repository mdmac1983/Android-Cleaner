package io.github.mdmac1983.leancleaner.analyzer

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.mdmac1983.leancleaner.core.FileKind
import io.github.mdmac1983.leancleaner.core.Format
import io.github.mdmac1983.leancleaner.core.Volume
import io.github.mdmac1983.leancleaner.prefs
import io.github.mdmac1983.leancleaner.ui.components.ConfirmDeleteDialog
import io.github.mdmac1983.leancleaner.ui.components.FileThumbnail
import io.github.mdmac1983.leancleaner.ui.components.InfoCard
import io.github.mdmac1983.leancleaner.ui.components.ScanProgress
import io.github.mdmac1983.leancleaner.ui.components.SelectionBar
import io.github.mdmac1983.leancleaner.ui.components.StorageGate
import io.github.mdmac1983.leancleaner.ui.components.ToolIntro
import io.github.mdmac1983.leancleaner.ui.components.ToolScaffold
import io.github.mdmac1983.leancleaner.ui.components.UsageBar
import io.github.mdmac1983.leancleaner.ui.components.prettyParent
import io.github.mdmac1983.leancleaner.ui.components.prettyPath
import io.github.mdmac1983.leancleaner.ui.components.rememberMessageSnackbar
import io.github.mdmac1983.leancleaner.ui.icons.AppIcons

private enum class AnalyzerTab(val label: String) { OVERVIEW("Overview"), FOLDERS("Folders"), LARGE("Large files") }

private val KIND_COLORS = mapOf(
    FileKind.IMAGE to Color(0xFF42A5F5),
    FileKind.VIDEO to Color(0xFFAB47BC),
    FileKind.AUDIO to Color(0xFFFFA726),
    FileKind.DOCUMENT to Color(0xFF66BB6A),
    FileKind.ARCHIVE to Color(0xFF8D6E63),
    FileKind.APK to Color(0xFF26A69A),
    FileKind.OTHER to Color(0xFF78909C),
)
private val SYSTEM_COLOR = Color(0xFFBDBDBD)

@Composable
fun AnalyzerScreen(onBack: () -> Unit, vm: AnalyzerViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val folder by vm.folder.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val snackbar = rememberMessageSnackbar(vm.messages)
    var tab by rememberSaveable { mutableStateOf(AnalyzerTab.OVERVIEW) }
    var confirm by remember { mutableStateOf(false) }

    val ready = state as? AnalyzerState.Ready
    val sizes: Map<String, Long> = when {
        ready == null -> emptyMap()
        tab == AnalyzerTab.FOLDERS -> entries.associate { it.path to it.size }
        tab == AnalyzerTab.LARGE -> ready.largeFiles.associate { it.path to it.size }
        else -> emptyMap()
    }
    val selectedBytes = selected.sumOf { sizes[it] ?: 0L }
    val canGoUp = folder != null && (folder?.parent != null || (ready?.result?.roots?.size ?: 0) > 1)

    BackHandler(enabled = ready != null && tab == AnalyzerTab.FOLDERS && canGoUp) { vm.up() }

    ToolScaffold(
        title = "Storage analyzer",
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            if (ready != null) IconButton(onClick = vm::scan) { Icon(AppIcons.Refresh, "Scan again") }
        },
        bottomBar = {
            if (ready != null && selected.isNotEmpty()) {
                SelectionBar(
                    summary = "${Format.count(selected.size, "item")} · ${Format.size(selectedBytes)}",
                    actionText = "Delete",
                    enabled = true,
                    onAction = { confirm = true },
                )
            }
        },
    ) { padding ->
        StorageGate(Modifier.padding(padding)) {
            when (val s = state) {
                AnalyzerState.Idle -> ToolIntro(
                    icon = AppIcons.PieChart,
                    title = "Storage analyzer",
                    description = "See what is using your storage: a breakdown by file type, every folder sorted by size, " +
                        "and your largest files.",
                    buttonText = "Analyze",
                    onStart = vm::scan,
                    modifier = Modifier.padding(padding),
                )
                is AnalyzerState.Scanning -> ScanProgress(
                    title = "Measuring folders…",
                    detail = prettyPath(s.path),
                    onCancel = vm::cancel,
                    modifier = Modifier.padding(padding),
                )
                is AnalyzerState.Deleting -> ScanProgress(
                    title = "Deleting…",
                    detail = "${s.done} of ${s.total}",
                    progress = if (s.total > 0) s.done.toFloat() / s.total else null,
                    modifier = Modifier.padding(padding),
                )
                is AnalyzerState.Ready -> Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
                    PrimaryTabRow(selectedTabIndex = tab.ordinal) {
                        AnalyzerTab.entries.forEach { t ->
                            Tab(
                                selected = tab == t,
                                onClick = {
                                    tab = t
                                    vm.clearSelection()
                                },
                                text = { Text(t.label) },
                            )
                        }
                    }
                    val bottom = PaddingValues(bottom = padding.calculateBottomPadding() + 16.dp)
                    when (tab) {
                        AnalyzerTab.OVERVIEW -> Overview(s.result, s.volumes, bottom)
                        AnalyzerTab.FOLDERS -> Folders(s.result, folder, entries, selected, canGoUp, vm, bottom)
                        AnalyzerTab.LARGE -> LargeFiles(s.largeFiles, selected, vm, bottom)
                    }
                }
            }
        }
    }

    if (confirm) {
        ConfirmDeleteDialog(
            count = selected.size,
            bytes = selectedBytes,
            onConfirm = {
                confirm = false
                vm.deleteSelected(sizes)
            },
            onDismiss = { confirm = false },
        )
    }
}

@Composable
private fun Overview(result: AnalyzerResult, volumes: List<Volume>, bottom: PaddingValues) {
    val used = volumes.sumOf { it.usedBytes }
    val kinds = result.bytesByKind.entries.filter { it.value > 0 }.sortedByDescending { it.value }
    val systemBytes = (used - result.scannedBytes).coerceAtLeast(0)
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = bottom.calculateBottomPadding()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(volumes, key = { it.root.path }) { volume ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(volume.label, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${Format.size(volume.usedBytes)} used of ${Format.size(volume.totalBytes)} · ${Format.size(volume.freeBytes)} free",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    UsageBar(if (volume.totalBytes > 0) volume.usedBytes.toFloat() / volume.totalBytes else 0f)
                }
            }
        }
        item(key = "breakdown") {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("What's using space", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(12.dp))
                    val segments = kinds.map { KIND_COLORS.getValue(it.key) to it.value } + (SYSTEM_COLOR to systemBytes)
                    StackedBar(segments)
                    Spacer(Modifier.height(12.dp))
                    kinds.forEach { (kind, bytes) ->
                        LegendRow(KIND_COLORS.getValue(kind), kind.label, Format.count(result.countByKind[kind] ?: 0, "file"), bytes, used)
                    }
                    if (systemBytes > 0) LegendRow(SYSTEM_COLOR, "Apps, system & other", null, systemBytes, used)
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            item(key = "note") {
                InfoCard(
                    "Apps' private data (including Android/data and Android/obb) can't be read by other apps on " +
                        "Android 11+, so it's counted under \"Apps, system & other\". The Apps screen shows per-app sizes.",
                )
            }
        }
    }
}

@Composable
private fun StackedBar(segments: List<Pair<Color, Long>>) {
    val total = segments.sumOf { it.second }.coerceAtLeast(1)
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(16.dp)
            .clip(CircleShape),
    ) {
        drawRoundRect(track, cornerRadius = CornerRadius(size.height / 2))
        var x = 0f
        for ((color, bytes) in segments) {
            val w = size.width * bytes / total
            if (w <= 0f) continue
            drawRect(color, topLeft = Offset(x, 0f), size = Size(w, size.height))
            x += w
        }
    }
}

@Composable
private fun LegendRow(color: Color, label: String, detail: String?, bytes: Long, total: Long) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(Format.size(bytes))
            Text("${Format.percent(bytes, total)}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Folders(
    result: AnalyzerResult,
    folder: DirNode?,
    entries: List<Entry>,
    selected: Set<String>,
    canGoUp: Boolean,
    vm: AnalyzerViewModel,
    bottom: PaddingValues,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { vm.up() }, enabled = canGoUp) { Icon(AppIcons.ArrowBack, "Up one folder") }
            Column(Modifier.weight(1f)) {
                Text(
                    folder?.let { prettyPath(it.path) } ?: "All storage",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (folder != null) {
                    Text(
                        "${Format.size(folder.size)} · ${Format.count(folder.fileCount, "file")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        val rows = folder?.let { entries } ?: result.roots.map { Entry(it.path, it.size, it, it.fileCount) }
        val parentSize = folder?.size ?: result.scannedBytes
        LazyColumn(contentPadding = bottom) {
            items(rows, key = { it.path }) { entry ->
                EntryRow(
                    entry = entry,
                    parentSize = parentSize,
                    checked = entry.path in selected,
                    selectable = folder != null,
                    onClick = { if (entry.node != null) vm.open(entry.node) else vm.toggle(entry.path) },
                    onCheck = { vm.toggle(entry.path) },
                )
            }
            if (folder != null && folder.unreadable) {
                item(key = "unreadable") {
                    InfoCard("Android doesn't let apps look inside this folder.", Modifier.padding(16.dp))
                }
            }
        }
    }
}

@Composable
private fun EntryRow(entry: Entry, parentSize: Long, checked: Boolean, selectable: Boolean, onClick: () -> Unit, onCheck: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (entry.isDirectory) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Icon(AppIcons.Folder, null, tint = MaterialTheme.colorScheme.primary)
            }
        } else {
            FileThumbnail(entry.path, entry.kind, size = 40.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val title = if (entry.node != null && entry.node.parent == null) prettyPath(entry.path) else entry.name
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(Format.size(entry.size), style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(4.dp))
            UsageBar(if (parentSize > 0) entry.size.toFloat() / parentSize else 0f, Modifier.height(4.dp))
            if (entry.isDirectory) {
                Text(
                    Format.count(entry.fileCount, "file"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (selectable) {
            Checkbox(checked = checked, onCheckedChange = { onCheck() })
        } else {
            Spacer(Modifier.width(12.dp))
        }
    }
}

@Composable
private fun LargeFiles(files: List<LargeFile>, selected: Set<String>, vm: AnalyzerViewModel, bottom: PaddingValues) {
    var filter by rememberSaveable { mutableStateOf<FileKind?>(null) }
    val kinds = remember(files) { files.map { it.kind }.distinct().sortedBy { it.ordinal } }
    val shown = if (filter == null) files else files.filter { it.kind == filter }
    val threshold = LocalContext.current.prefs.largeFileThreshold.collectAsStateWithLifecycle().value

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("All") })
            kinds.forEach { kind ->
                FilterChip(selected = filter == kind, onClick = { filter = kind }, label = { Text(kind.label) })
            }
        }
        if (shown.isEmpty()) {
            Text(
                "No files larger than ${Format.size(threshold)}. You can change the threshold in Settings.",
                modifier = Modifier.padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        LazyColumn(contentPadding = bottom) {
            items(shown, key = { it.path }) { file ->
                val checked = file.path in selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.toggle(file.path) }
                        .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FileThumbnail(file.path, file.kind, size = 48.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${prettyParent(file.path)} · ${Format.date(file.lastModified)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(Format.size(file.size), style = MaterialTheme.typography.bodyMedium)
                    Checkbox(checked = checked, onCheckedChange = { vm.toggle(file.path) })
                }
            }
        }
    }
}
