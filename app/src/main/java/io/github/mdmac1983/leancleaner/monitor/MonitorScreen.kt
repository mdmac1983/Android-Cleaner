package io.github.mdmac1983.leancleaner.monitor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.mdmac1983.leancleaner.core.Format
import io.github.mdmac1983.leancleaner.core.Volume
import io.github.mdmac1983.leancleaner.core.Volumes
import io.github.mdmac1983.leancleaner.ui.components.InfoRow
import io.github.mdmac1983.leancleaner.ui.components.ToolScaffold
import io.github.mdmac1983.leancleaner.ui.components.UsageBar
import io.github.mdmac1983.leancleaner.ui.components.rememberPolled
import io.github.mdmac1983.leancleaner.ui.icons.AppIcons

private class LiveStats(
    val cpu: CpuSnapshot,
    val memory: MemorySnapshot,
    val battery: BatterySnapshot?,
    val thermal: String?,
    val volumes: List<Volume>,
    val uptime: Long,
)

/** Live system overview. Polls once per second only while visible; nothing runs in the background. */
@Composable
fun MonitorScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val device = remember { SystemInfo.device(context) }
    val stats by rememberPolled(1_000) {
        LiveStats(
            cpu = SystemInfo.cpu(),
            memory = SystemInfo.memory(context),
            battery = SystemInfo.battery(context),
            thermal = SystemInfo.thermalStatus(context),
            volumes = Volumes.list(context),
            uptime = SystemInfo.uptimeMillis(),
        )
    }

    ToolScaffold(title = "System monitor", onBack = onBack) { padding ->
        // The first reading takes milliseconds. Building the list only once it's there keeps the list
        // anchored at the top (inserting cards above an already visible one would scroll past them).
        val s = stats ?: return@ToolScaffold
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "cpu") { CpuCard(s.cpu, s.thermal) }
            item(key = "memory") { MemoryCard(s.memory) }
            s.battery?.let { battery -> item(key = "battery") { BatteryCard(battery) } }
            item(key = "storage") {
                StatCard("Storage", AppIcons.SdStorage) {
                    s.volumes.forEach { v ->
                        Text(v.label, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(4.dp))
                        UsageBar(if (v.totalBytes > 0) v.usedBytes.toFloat() / v.totalBytes else 0f)
                        Text(
                            "${Format.size(v.usedBytes)} used · ${Format.size(v.freeBytes)} free of ${Format.size(v.totalBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                        )
                    }
                }
            }
            item(key = "device") {
                StatCard("Device", AppIcons.PhoneAndroid) {
                    InfoRow("Model", device.model)
                    InfoRow("System", device.android)
                    device.securityPatch?.let { InfoRow("Security patch", it) }
                    InfoRow("Chipset", device.soc)
                    InfoRow("ABIs", device.abis)
                    InfoRow("Kernel", device.kernel)
                    InfoRow("Display", device.display)
                    InfoRow("Uptime", Format.duration(s.uptime))
                }
            }
        }
    }
}

@Composable
private fun StatCard(title: String, icon: ImageVector, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CpuCard(cpu: CpuSnapshot, thermal: String?) {
    StatCard("Processor", AppIcons.Memory) {
        InfoRow("Cores", "${cpu.cores}")
        if (cpu.minMhz != null && cpu.maxMhz != null) InfoRow("Frequency range", "${cpu.minMhz}–${cpu.maxMhz} MHz")
        thermal?.let { InfoRow("Thermal state", it) }
        if (cpu.coreMhz.any { it != null }) {
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                cpu.coreMhz.forEachIndexed { index, mhz ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.widthIn(min = 72.dp),
                    ) {
                        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Core $index", style = MaterialTheme.typography.labelSmall)
                            Text(mhz?.let { "$it MHz" } ?: "idle", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MemoryCard(memory: MemorySnapshot) {
    StatCard("Memory", AppIcons.DeveloperBoard) {
        UsageBar(if (memory.totalBytes > 0) memory.usedBytes.toFloat() / memory.totalBytes else 0f)
        Spacer(Modifier.height(8.dp))
        InfoRow("In use", "${Format.size(memory.usedBytes)} (${Format.percent(memory.usedBytes, memory.totalBytes)}%)")
        InfoRow("Available", Format.size(memory.availableBytes))
        InfoRow("Total", Format.size(memory.totalBytes))
        Text(
            "Android manages memory itself; unused RAM is wasted RAM, so there's no \"boost\" button here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun BatteryCard(battery: BatterySnapshot) {
    StatCard("Battery", if (battery.isCharging) AppIcons.BatteryChargingFull else AppIcons.BatteryFull) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${battery.percent}%", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.width(8.dp))
            Text(
                listOfNotNull(battery.status, battery.plugged).joinToString(" · "),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        UsageBar(battery.percent / 100f)
        Spacer(Modifier.height(8.dp))
        battery.temperatureC?.let { InfoRow("Temperature", "%.1f °C".format(it)) }
        battery.currentMa?.let { InfoRow(if (battery.isCharging) "Charging current" else "Discharge current", "$it mA") }
        battery.voltageMv?.let { InfoRow("Voltage", "%.2f V".format(it / 1000f)) }
        InfoRow("Health", battery.health)
        battery.capacityMah?.let { InfoRow("Estimated capacity", "$it mAh") }
        battery.cycleCount?.let { InfoRow("Charge cycles", "$it") }
        battery.technology?.let { InfoRow("Technology", it) }
    }
}
