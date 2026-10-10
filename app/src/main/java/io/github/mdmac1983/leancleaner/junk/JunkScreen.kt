package io.github.mdmac1983.leancleaner.junk

import android.app.Activity
import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.mdmac1983.leancleaner.core.FileKind
import io.github.mdmac1983.leancleaner.core.Format
import io.github.mdmac1983.leancleaner.core.SystemCacheCleaner
import io.github.mdmac1983.leancleaner.ui.components.ConfirmDeleteDialog
import io.github.mdmac1983.leancleaner.ui.components.EmptyResult
import io.github.mdmac1983.leancleaner.ui.components.FileThumbnail
import io.github.mdmac1983.leancleaner.ui.components.InfoCard
import io.github.mdmac1983.leancleaner.ui.components.ScanProgress
import io.github.mdmac1983.leancleaner.ui.components.SelectionBar
import io.github.mdmac1983.leancleaner.ui.components.StorageGate
import io.github.mdmac1983.leancleaner.ui.components.ToolIntro
import io.github.mdmac1983.leancleaner.ui.components.ToolScaffold
import io.github.mdmac1983.leancleaner.ui.components.prettyParent
import io.github.mdmac1983.leancleaner.ui.components.prettyPath
import io.github.mdmac1983.leancleaner.ui.components.rememberMessageSnackbar
import io.github.mdmac1983.leancleaner.ui.icons.AppIcons

@Composable
fun JunkScreen(onBack: () -> Unit, vm: JunkViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val snackbar = rememberMessageSnackbar(vm.messages)
    var confirm by remember { mutableStateOf(false) }

    val results = (state as? JunkState.Results)?.items
    val selectedItems = results?.filter { it.path in selected }.orEmpty()
    val selectedBytes = selectedItems.sumOf { it.size }

    ToolScaffold(
        title = "Junk cleaner",
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            if (results != null) {
                IconButton(onClick = vm::scan) { Icon(AppIcons.Refresh, "Scan again") }
            }
        },
        bottomBar = {
            if (!results.isNullOrEmpty()) {
                SelectionBar(
                    summary = "${Format.count(selectedItems.size, "item")} · ${Format.size(selectedBytes)}",
                    actionText = "Clean",
                    enabled = selectedItems.isNotEmpty(),
                    onAction = { confirm = true },
                )
            }
        },
    ) { padding ->
        StorageGate(Modifier.padding(padding)) {
            when (val s = state) {
                JunkState.Idle -> ToolIntro(
                    icon = AppIcons.CleaningServices,
                    title = "Junk cleaner",
                    description = "Finds app caches, temporary files, empty folders, old installers and other clutter. " +
                        "Nothing is deleted until you've reviewed it.",
                    buttonText = "Scan",
                    onStart = vm::scan,
                    modifier = Modifier.padding(padding),
                )
                is JunkState.Scanning -> ScanProgress(
                    title = "Looking for junk…",
                    detail = prettyPath(s.path),
                    onCancel = vm::cancel,
                    modifier = Modifier.padding(padding),
                )
                is JunkState.Deleting -> ScanProgress(
                    title = "Cleaning…",
                    detail = "${s.done} of ${s.total}",
                    progress = if (s.total > 0) s.done.toFloat() / s.total else null,
                    modifier = Modifier.padding(padding),
                )
                is JunkState.Results -> JunkResults(s.items, selected, vm, padding)
            }
        }
    }

    if (confirm) {
        ConfirmDeleteDialog(
            count = selectedItems.size,
            bytes = selectedBytes,
            onConfirm = {
                confirm = false
                vm.deleteSelected()
            },
            onDismiss = { confirm = false },
        )
    }
}

@Composable
private fun JunkResults(items: List<JunkItem>, selected: Set<String>, vm: JunkViewModel, padding: PaddingValues) {
    val byCategory = remember(items) { items.groupBy { it.category } }
    var expanded by rememberSaveable { mutableStateOf(emptySet<JunkCategory>()) }
    val appCache by vm.appCacheBytes.collectAsStateWithLifecycle()
    val clearCaches = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.onSystemCacheCleared(it.resultCode == Activity.RESULT_OK)
    }

    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "summary") {
            val total = items.sumOf { it.size }
            Column(Modifier.padding(vertical = 8.dp)) {
                Text(Format.size(total), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
                Text(
                    if (items.isEmpty()) "No junk found on shared storage" else "found in ${Format.count(items.size, "item")}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (SystemCacheCleaner.isSupported) {
            item(key = "appcache") {
                InfoCard(
                    title = "Internal app caches",
                    icon = AppIcons.Cached,
                    text = appCache?.let {
                        "${Format.size(it)} across all apps. Android can clear every app's cache in one go; apps rebuild them as needed."
                    } ?: "Android can clear every app's cache in one go. Allow usage access in Settings to see how much that frees.",
                    action = {
                        FilledTonalButton(onClick = {
                            try {
                                clearCaches.launch(SystemCacheCleaner.intent())
                            } catch (_: ActivityNotFoundException) {
                                vm.showMessage("This device doesn't offer clearing all app caches")
                            }
                        }) { Text("Clear all app caches") }
                    },
                )
            }
        }
        if (items.isEmpty()) {
            item(key = "empty") {
                EmptyResult(
                    title = "All clean",
                    message = "Nothing to remove right now.",
                    onRescan = vm::scan,
                    modifier = Modifier.padding(top = 32.dp),
                )
            }
        }
        for (category in JunkCategory.entries) {
            val list = byCategory[category] ?: continue
            val isOpen = category in expanded
            item(key = "cat-${category.name}") {
                CategoryHeader(
                    category = category,
                    items = list,
                    selected = selected,
                    expanded = isOpen,
                    onToggleExpanded = { expanded = if (isOpen) expanded - category else expanded + category },
                    onSelectAll = { vm.setCategory(category, it) },
                )
            }
            if (isOpen) {
                items(list, key = { it.path }) { item ->
                    JunkRow(
                        item = item,
                        checked = item.path in selected,
                        onToggle = { vm.toggle(item.path) },
                        onExclude = { vm.exclude(item.path) },
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryHeader(
    category: JunkCategory,
    items: List<JunkItem>,
    selected: Set<String>,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onSelectAll: (Boolean) -> Unit,
) {
    val selectedCount = items.count { it.path in selected }
    val state = when (selectedCount) {
        0 -> ToggleableState.Off
        items.size -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .clickable(onClick = onToggleExpanded)
                .padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TriStateCheckbox(state = state, onClick = { onSelectAll(state != ToggleableState.On) })
            Column(Modifier.weight(1f)) {
                Text(category.title, style = MaterialTheme.typography.titleMedium)
                val size = items.sumOf { it.size }
                Text(
                    "${Format.count(items.size, "item")}${if (size > 0) " · ${Format.size(size)}" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (expanded) {
                    Spacer(Modifier.size(4.dp))
                    Text(category.description, style = MaterialTheme.typography.bodySmall)
                }
            }
            Icon(
                if (expanded) AppIcons.ExpandLess else AppIcons.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun JunkRow(item: JunkItem, checked: Boolean, onToggle: () -> Unit, onExclude: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onToggle, onLongClick = { menu = true })
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = { onToggle() })
            if (item.isDirectory) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    Icon(AppIcons.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                FileThumbnail(item.path, FileKind.of(item.name), size = 40.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                val title = if (item.category == JunkCategory.APP_CACHE) item.detail ?: item.name else item.name
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (item.category == JunkCategory.APP_CACHE) prettyPath(item.path) else item.detail ?: prettyParent(item.path),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                if (item.isDirectory && item.size == 0L) "empty" else Format.size(item.size),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Exclude from future scans") },
                leadingIcon = { Icon(AppIcons.Block, contentDescription = null) },
                onClick = {
                    menu = false
                    onExclude()
                },
            )
        }
    }
}
