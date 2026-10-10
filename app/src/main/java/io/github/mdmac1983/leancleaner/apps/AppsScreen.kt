package io.github.mdmac1983.leancleaner.apps

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.mdmac1983.leancleaner.core.Format
import io.github.mdmac1983.leancleaner.core.Permissions
import io.github.mdmac1983.leancleaner.core.startActivitySafely
import io.github.mdmac1983.leancleaner.ui.components.AppIcon
import io.github.mdmac1983.leancleaner.ui.components.InfoCard
import io.github.mdmac1983.leancleaner.ui.components.InfoRow
import io.github.mdmac1983.leancleaner.ui.components.ScanProgress
import io.github.mdmac1983.leancleaner.ui.components.ToolScaffold
import io.github.mdmac1983.leancleaner.ui.components.rememberMessageSnackbar
import io.github.mdmac1983.leancleaner.ui.icons.AppIcons

@Composable
fun AppsScreen(onBack: () -> Unit, vm: AppsViewModel = viewModel()) {
    val context = LocalContext.current
    val loaded by vm.isLoaded.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val apps by vm.visible.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val queue by vm.uninstallQueue.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val usageAccess by vm.hasUsageAccess.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val snackbar = rememberMessageSnackbar(vm.messages)

    var searching by rememberSaveable { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    var details by rememberSaveable { mutableStateOf<String?>(null) }

    // Reload when returning from Settings (usage access may have been granted).
    LifecycleResumeEffect(Unit) {
        vm.load()
        onPauseOrDispose {}
    }

    val uninstaller = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.onUninstallDialogClosed()
    }
    LaunchedEffect(queue) {
        vm.nextUninstall()?.let { pkg ->
            uninstaller.launch(
                Intent(Intent.ACTION_DELETE, "package:$pkg".toUri()).putExtra(Intent.EXTRA_RETURN_RESULT, true),
            )
        }
    }

    BackHandler(enabled = selected.isNotEmpty()) { vm.clearSelection() }

    ToolScaffold(
        title = if (selected.isEmpty()) "Apps" else "${selected.size} selected",
        onBack = onBack,
        snackbarHostState = snackbar,
        actions = {
            IconButton(onClick = {
                searching = !searching
                if (!searching) vm.query.value = ""
            }) { Icon(if (searching) AppIcons.Close else AppIcons.Search, "Search") }
            Box {
                IconButton(onClick = { sortMenu = true }) { Icon(AppIcons.Sort, "Sort") }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    AppSort.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label) },
                            trailingIcon = { if (option == sort) Icon(AppIcons.Check, null) },
                            enabled = usageAccess || option == AppSort.NAME || option == AppSort.INSTALLED,
                            onClick = {
                                sortMenu = false
                                vm.sort.value = option
                            },
                        )
                    }
                }
            }
            IconButton(onClick = { vm.load(force = true) }) { Icon(AppIcons.Refresh, "Reload") }
        },
        bottomBar = {
            if (selected.isNotEmpty()) {
                val removable = selected.count { vm.find(it)?.isSystem == false }
                Surface(tonalElevation = 3.dp, shadowElevation = 3.dp) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    ) {
                        OutlinedButton(onClick = { vm.export(selected) }, enabled = !busy) { Text("Export APKs") }
                        Button(
                            onClick = {
                                vm.uninstall(selected)
                                vm.clearSelection()
                            },
                            enabled = removable > 0,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            ),
                        ) { Text(if (removable > 0) "Uninstall $removable" else "Uninstall") }
                    }
                }
            }
        },
    ) { padding ->
        if (!loaded) {
            ScanProgress(
                title = "Loading apps…",
                detail = "${(progress * 100).toInt()}%",
                progress = progress.takeIf { it > 0f },
                modifier = Modifier.padding(padding),
            )
            return@ToolScaffold
        }
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            if (searching) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { vm.query.value = it },
                    placeholder = { Text("Search apps") },
                    singleLine = true,
                    leadingIcon = { Icon(AppIcons.Search, null) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            FilterRow(vm, usageAccess)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 16.dp)) {
                if (!usageAccess) {
                    item(key = "usage") {
                        InfoCard(
                            title = "See sizes and usage",
                            text = "Allow usage access to see each app's size, cache and when you last used it. " +
                                "It's only read on your phone.",
                            modifier = Modifier.padding(16.dp),
                            action = {
                                Button(onClick = {
                                    context.startActivitySafely(
                                        Permissions.usageAccessIntent(context),
                                        fallback = Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS),
                                    )
                                }) { Text("Allow usage access") }
                            },
                        )
                    }
                }
                item(key = "count") {
                    val sized = apps.mapNotNull { it.totalBytes }
                    Text(
                        Format.count(apps.size, "app") + if (sized.isNotEmpty()) " · ${Format.size(sized.sum())}" else "",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(apps, key = { it.packageName }) { app ->
                    AppRow(
                        app = app,
                        sort = sort,
                        selecting = selected.isNotEmpty(),
                        checked = app.packageName in selected,
                        onClick = { if (selected.isNotEmpty()) vm.toggle(app.packageName) else details = app.packageName },
                        onLongClick = { vm.toggle(app.packageName) },
                    )
                }
            }
        }
    }

    // The sheet disappears by itself if its app gets uninstalled meanwhile.
    details?.let(vm::find)?.let { app ->
        AppDetailsSheet(
            app = app,
            onDismiss = { details = null },
            onUninstall = {
                details = null
                vm.uninstall(listOf(app.packageName))
            },
            onExport = { vm.export(listOf(app.packageName)) },
        )
    }
}

@Composable
private fun FilterRow(vm: AppsViewModel, usageAccess: Boolean) {
    val filter by vm.filter.collectAsStateWithLifecycle()
    val unused by vm.unusedOnly.collectAsStateWithLifecycle()
    Row(
        Modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppFilter.entries.forEach { f ->
            FilterChip(selected = filter == f, onClick = { vm.filter.value = f }, label = { Text(f.label) })
        }
        FilterChip(
            selected = unused,
            enabled = usageAccess,
            onClick = { vm.unusedOnly.value = !unused },
            label = { Text("Unused ${AppsViewModel.UNUSED_DAYS}+ days") },
            leadingIcon = { Icon(AppIcons.History, null, Modifier.size(18.dp)) },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppRow(
    app: AppInfo,
    sort: AppSort,
    selecting: Boolean,
    checked: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app.packageName, size = 40.dp)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val detail = buildList {
                when (sort) {
                    AppSort.CACHE -> app.cacheBytes?.let { add("cache ${Format.size(it)}") }
                    else -> app.totalBytes?.let { add(Format.size(it)) }
                }
                when (sort) {
                    AppSort.INSTALLED -> add("installed ${Format.ago(app.installTime)}")
                    else -> if (app.lastUsed != null) add("used ${Format.ago(app.lastUsed)}")
                }
                if (!app.isEnabled) add("disabled")
            }.ifEmpty { listOf(app.packageName) }
            Text(
                detail.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selecting) Checkbox(checked = checked, onCheckedChange = { onLongClick() })
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun AppDetailsSheet(app: AppInfo, onDismiss: () -> Unit, onUninstall: () -> Unit, onExport: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(app.packageName, size = 56.dp)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(app.label, style = MaterialTheme.typography.titleLarge)
                    Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "Version ${app.versionName ?: app.versionCode}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (app.isLaunchable) {
                    FilledTonalButton(onClick = {
                        context.packageManager.getLaunchIntentForPackage(app.packageName)?.let { context.startActivitySafely(it) }
                    }) { Text("Open") }
                }
                FilledTonalButton(onClick = { context.startActivitySafely(Permissions.appDetailsIntent(app.packageName)) }) {
                    Text("App info")
                }
                OutlinedButton(onClick = onExport) { Text("Export APK") }
                if (!app.isSystem) {
                    Button(
                        onClick = onUninstall,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) { Text("Uninstall") }
                }
            }
            Text(
                "Clear cache, clear data and force stop are in App info.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
            if (app.appBytes != null) {
                InfoRow("App", Format.size(app.appBytes))
                InfoRow("Data", Format.size((app.dataBytes ?: 0) - (app.cacheBytes ?: 0)))
                InfoRow("Cache", Format.size(app.cacheBytes ?: 0))
                InfoRow("Total", Format.size(app.totalBytes ?: 0))
            }
            InfoRow("Last used", app.lastUsed?.let { Format.ago(it) } ?: "Unknown")
            InfoRow("Installed", Format.date(app.installTime))
            InfoRow("Updated", Format.date(app.updateTime))
            InfoRow("Type", if (app.isSystem) "System app" else "User app")
            InfoRow("Status", if (app.isEnabled) "Enabled" else "Disabled")
            InfoRow("Target / min SDK", "${app.targetSdk} / ${app.minSdk}")
            if (app.splitPaths.isNotEmpty()) InfoRow("Split APKs", "${app.splitPaths.size}")
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Close") }
        }
    }
}
