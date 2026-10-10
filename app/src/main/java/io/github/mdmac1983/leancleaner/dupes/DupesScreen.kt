package io.github.mdmac1983.leancleaner.dupes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.mdmac1983.leancleaner.core.FileKind
import io.github.mdmac1983.leancleaner.core.Format
import io.github.mdmac1983.leancleaner.ui.components.ConfirmDeleteDialog
import io.github.mdmac1983.leancleaner.ui.components.EmptyResult
import io.github.mdmac1983.leancleaner.ui.components.FileThumbnail
import io.github.mdmac1983.leancleaner.ui.components.ScanProgress
import io.github.mdmac1983.leancleaner.ui.components.SelectionBar
import io.github.mdmac1983.leancleaner.ui.components.StorageGate
import io.github.mdmac1983.leancleaner.ui.components.ToolIntro
import io.github.mdmac1983.leancleaner.ui.components.ToolScaffold
import io.github.mdmac1983.leancleaner.ui.components.prettyParent
import io.github.mdmac1983.leancleaner.ui.components.rememberMessageSnackbar
import io.github.mdmac1983.leancleaner.ui.icons.AppIcons

private val MIN_SIZE_OPTIONS = listOf(1L to "Any size", 100_000L to "100 kB+", 1_000_000L to "1 MB+", 10_000_000L to "10 MB+")

@Composable
fun DupesScreen(onBack: () -> Unit, vm: DupesViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val snackbar = rememberMessageSnackbar(vm.messages)
    var confirm by remember { mutableStateOf(false) }
    var strategyMenu by remember { mutableStateOf(false) }

    val groups = (state as? DupesState.Results)?.groups
    val chosen = groups?.flatMap { g -> g.files.filter { it.path in selected } }.orEmpty()
    val chosenBytes = chosen.sumOf { it.size }

    ToolScaffold(
        title = "Duplicates",
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            if (!groups.isNullOrEmpty()) {
                Box {
                    IconButton(onClick = { strategyMenu = true }) { Icon(AppIcons.SelectAll, "Auto-select") }
                    DropdownMenu(expanded = strategyMenu, onDismissRequest = { strategyMenu = false }) {
                        KeepStrategy.entries.forEach { strategy ->
                            DropdownMenuItem(
                                text = { Text(strategy.label) },
                                onClick = {
                                    strategyMenu = false
                                    vm.autoSelect(strategy)
                                },
                            )
                        }
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text("Select none") },
                            leadingIcon = { Icon(AppIcons.Deselect, null) },
                            onClick = {
                                strategyMenu = false
                                vm.clearSelection()
                            },
                        )
                    }
                }
            }
            if (groups != null) IconButton(onClick = vm::scan) { Icon(AppIcons.Refresh, "Scan again") }
        },
        bottomBar = {
            if (!groups.isNullOrEmpty()) {
                SelectionBar(
                    summary = "${Format.count(chosen.size, "copy", "copies")} · ${Format.size(chosenBytes)}",
                    actionText = "Delete",
                    enabled = chosen.isNotEmpty(),
                    onAction = { confirm = true },
                )
            }
        },
    ) { padding ->
        StorageGate(Modifier.padding(padding)) {
            when (val s = state) {
                DupesState.Idle -> DupesIntro(vm, Modifier.padding(padding))
                is DupesState.Scanning -> {
                    val p = s.progress
                    ScanProgress(
                        title = if (p?.phase == DupeProgress.Phase.COMPARING) "Comparing files…" else "Collecting files…",
                        detail = when {
                            p == null -> null
                            p.phase == DupeProgress.Phase.COMPARING -> "${p.done} of ${p.total}"
                            else -> "${p.done} files found"
                        },
                        progress = p?.takeIf { it.phase == DupeProgress.Phase.COMPARING && it.total > 0 }
                            ?.let { it.done.toFloat() / it.total },
                        onCancel = vm::cancel,
                        modifier = Modifier.padding(padding),
                    )
                }
                is DupesState.Deleting -> ScanProgress(
                    title = "Deleting…",
                    detail = "${s.done} of ${s.total}",
                    progress = if (s.total > 0) s.done.toFloat() / s.total else null,
                    modifier = Modifier.padding(padding),
                )
                is DupesState.Results -> DupeGroups(s.groups, selected, vm, padding)
            }
        }
    }

    if (confirm) {
        ConfirmDeleteDialog(
            count = chosen.size,
            bytes = chosenBytes,
            onConfirm = {
                confirm = false
                vm.deleteSelected()
            },
            onDismiss = { confirm = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DupesIntro(vm: DupesViewModel, modifier: Modifier) {
    val minSize by vm.minSize.collectAsStateWithLifecycle()
    val skipHidden by vm.skipHidden.collectAsStateWithLifecycle()
    ToolIntro(
        icon = AppIcons.ContentCopy,
        title = "Duplicates",
        description = "Finds byte-for-byte identical files: the same photo saved twice, forwarded videos, " +
            "repeated downloads. One copy of every file is always kept.",
        buttonText = "Find duplicates",
        onStart = vm::scan,
        modifier = modifier,
    ) {
        Spacer(Modifier.height(20.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            MIN_SIZE_OPTIONS.forEach { (bytes, label) ->
                FilterChip(selected = minSize == bytes, onClick = { vm.setMinSize(bytes) }, label = { Text(label) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Include hidden folders", modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(12.dp))
            Switch(checked = !skipHidden, onCheckedChange = { vm.setSkipHidden(!it) })
        }
    }
}

@Composable
private fun DupeGroups(groups: List<DupeGroup>, selected: Set<String>, vm: DupesViewModel, padding: PaddingValues) {
    if (groups.isEmpty()) {
        EmptyResult("No duplicates", "Every file is one of a kind.", vm::scan, Modifier.padding(padding))
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "header") {
            Column(Modifier.padding(vertical = 4.dp)) {
                Text(
                    Format.size(groups.sumOf { it.wasted }),
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "reclaimable from ${Format.count(groups.size, "set")} of duplicates",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(groups, key = { it.hash }) { group ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    val first = group.files.first()
                    FileThumbnail(first.path, FileKind.of(first.name), size = 56.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(first.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${Format.count(group.files.size, "copy", "copies")} · ${Format.size(group.size)} each",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider()
                group.files.forEach { file ->
                    val checked = file.path in selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { vm.toggle(group, file.path) }
                            .padding(end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = checked, onCheckedChange = { vm.toggle(group, file.path) })
                        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                            Text(prettyParent(file.path), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${file.name} · ${Format.date(file.lastModified)}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (!checked) {
                            Text("Keep", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}
