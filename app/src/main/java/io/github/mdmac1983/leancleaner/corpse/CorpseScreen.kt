package io.github.mdmac1983.leancleaner.corpse

import android.os.Build
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import io.github.mdmac1983.leancleaner.core.Format
import io.github.mdmac1983.leancleaner.ui.components.ConfirmDeleteDialog
import io.github.mdmac1983.leancleaner.ui.components.EmptyResult
import io.github.mdmac1983.leancleaner.ui.components.InfoCard
import io.github.mdmac1983.leancleaner.ui.components.ScanProgress
import io.github.mdmac1983.leancleaner.ui.components.SelectionBar
import io.github.mdmac1983.leancleaner.ui.components.StorageGate
import io.github.mdmac1983.leancleaner.ui.components.ToolIntro
import io.github.mdmac1983.leancleaner.ui.components.ToolScaffold
import io.github.mdmac1983.leancleaner.ui.components.prettyPath
import io.github.mdmac1983.leancleaner.ui.components.rememberMessageSnackbar
import io.github.mdmac1983.leancleaner.ui.icons.AppIcons

@Composable
fun CorpseScreen(onBack: () -> Unit, vm: CorpseViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val snackbar = rememberMessageSnackbar(vm.messages)
    var confirm by remember { mutableStateOf(false) }

    val results = (state as? CorpseState.Results)?.corpses
    val chosen = results?.filter { it.path in selected }.orEmpty()
    val chosenBytes = chosen.sumOf { it.size }

    ToolScaffold(
        title = "Leftovers",
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            if (results != null) IconButton(onClick = vm::scan) { Icon(AppIcons.Refresh, "Scan again") }
        },
        bottomBar = {
            if (!results.isNullOrEmpty()) {
                SelectionBar(
                    summary = "${Format.count(chosen.size, "folder")} · ${Format.size(chosenBytes)}",
                    actionText = "Delete",
                    enabled = chosen.isNotEmpty(),
                    onAction = { confirm = true },
                )
            }
        },
    ) { padding ->
        StorageGate(Modifier.padding(padding)) {
            when (val s = state) {
                CorpseState.Idle -> ToolIntro(
                    icon = AppIcons.FolderDelete,
                    title = "Leftovers",
                    description = "Finds folders left behind by apps you've uninstalled. Folders with photos, " +
                        "videos or music are never pre-selected.",
                    buttonText = "Scan",
                    onStart = vm::scan,
                    modifier = Modifier.padding(padding),
                )
                is CorpseState.Scanning -> ScanProgress(
                    title = "Looking for leftovers…",
                    detail = prettyPath(s.path),
                    onCancel = vm::cancel,
                    modifier = Modifier.padding(padding),
                )
                is CorpseState.Deleting -> ScanProgress(
                    title = "Deleting…",
                    detail = "${s.done} of ${s.total}",
                    progress = if (s.total > 0) s.done.toFloat() / s.total else null,
                    modifier = Modifier.padding(padding),
                )
                is CorpseState.Results -> CorpseList(s.corpses, selected, vm, padding)
            }
        }
    }

    if (confirm) {
        val media = chosen.sumOf { it.mediaCount }
        ConfirmDeleteDialog(
            count = chosen.size,
            bytes = chosenBytes,
            warning = if (media > 0) "The selection contains ${Format.count(media, "photo, video or audio file", "photos, videos or audio files")}." else null,
            onConfirm = {
                confirm = false
                vm.deleteSelected()
            },
            onDismiss = { confirm = false },
        )
    }
}

@Composable
private fun CorpseList(corpses: List<Corpse>, selected: Set<String>, vm: CorpseViewModel, padding: PaddingValues) {
    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            item(key = "note") {
                InfoCard(
                    "Android 11 and newer keep Android/data and Android/obb off-limits to all apps, so leftovers " +
                        "there can't be detected. Android removes most of them when an app is uninstalled anyway.",
                )
            }
        }
        if (corpses.isEmpty()) {
            item(key = "empty") {
                EmptyResult("No leftovers", "No folders from uninstalled apps were found.", vm::scan, Modifier.padding(top = 32.dp))
            }
            return@LazyColumn
        }
        item(key = "header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${Format.count(corpses.size, "leftover")} · ${Format.size(corpses.sumOf { it.size })}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                val all = selected.size == corpses.size
                TextButton(onClick = { vm.selectAll(!all) }) { Text(if (all) "Select none" else "Select all") }
            }
        }
        items(corpses, key = { it.path }) { corpse ->
            CorpseRow(corpse, corpse.path in selected, { vm.toggle(corpse.path) }, { vm.exclude(corpse.path) })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CorpseRow(corpse: Corpse, checked: Boolean, onToggle: () -> Unit, onExclude: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Box {
            Row(
                modifier = Modifier
                    .combinedClickable(onClick = onToggle, onLongClick = { menu = true })
                    .padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = checked, onCheckedChange = { onToggle() })
                Column(Modifier.weight(1f)) {
                    Text(corpse.owner, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        prettyPath(corpse.path),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${corpse.location.label} · ${Format.count(corpse.fileCount, "file")}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (corpse.mediaCount > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                            Icon(AppIcons.Warning, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "Contains ${Format.count(corpse.mediaCount, "media file")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(Format.size(corpse.size), style = MaterialTheme.typography.bodyMedium)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Exclude from future scans") },
                    leadingIcon = { Icon(AppIcons.Block, null) },
                    onClick = {
                        menu = false
                        onExclude()
                    },
                )
            }
        }
    }
}
