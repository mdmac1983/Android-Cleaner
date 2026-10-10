package io.github.mdmac1983.leancleaner.monitor

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import java.io.File
import kotlin.math.abs

data class CpuSnapshot(
    val cores: Int,
    /** Current frequency per core in MHz; null when offline or not readable. */
    val coreMhz: List<Int?>,
    val maxMhz: Int?,
    val minMhz: Int?,
)

data class MemorySnapshot(val totalBytes: Long, val availableBytes: Long, val lowMemory: Boolean) {
    val usedBytes: Long get() = totalBytes - availableBytes
}

data class BatterySnapshot(
    val percent: Int,
    val status: String,
    val plugged: String?,
    val health: String,
    val temperatureC: Float?,
    val voltageMv: Int?,
    val technology: String?,
    /** Instantaneous current in mA (absolute value), if the device reports it. */
    val currentMa: Int?,
    val isCharging: Boolean,
    /** Estimated full-charge capacity in mAh, derived from the charge counter. */
    val capacityMah: Int?,
    val cycleCount: Int?,
)

data class DeviceSnapshot(
    val model: String,
    val android: String,
    val securityPatch: String?,
    val soc: String,
    val abis: String,
    val kernel: String,
    val display: String,
)

object SystemInfo {

    fun cpu(): CpuSnapshot {
        val cpuDir = File("/sys/devices/system/cpu")
        val coreCount = cpuDir.listFiles { f -> CORE_DIR.matches(f.name) }?.size
            ?.takeIf { it > 0 } ?: Runtime.getRuntime().availableProcessors()
        val freqs = (0 until coreCount).map { readInt("/sys/devices/system/cpu/cpu$it/cpufreq/scaling_cur_freq")?.div(1000) }
        val max = (0 until coreCount).mapNotNull { readInt("/sys/devices/system/cpu/cpu$it/cpufreq/cpuinfo_max_freq") }.maxOrNull()
        val min = (0 until coreCount).mapNotNull { readInt("/sys/devices/system/cpu/cpu$it/cpufreq/cpuinfo_min_freq") }.minOrNull()
        return CpuSnapshot(coreCount, freqs, max?.div(1000), min?.div(1000))
    }

    fun memory(context: Context): MemorySnapshot {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        return MemorySnapshot(info.totalMem, info.availMem, info.lowMemory)
    }

    fun battery(context: Context): BatterySnapshot? {
        // Sticky broadcast: passing a null receiver just reads the last value, nothing is registered.
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val bm = context.getSystemService(BatteryManager::class.java)
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val percent = if (level >= 0) level * 100 / scale else bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val statusCode = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val charging = statusCode == BatteryManager.BATTERY_STATUS_CHARGING || statusCode == BatteryManager.BATTERY_STATUS_FULL

        val rawCurrent = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        // Most devices report µA, a few report mA.
        val currentMa = rawCurrent.takeIf { it != 0 && it != Int.MIN_VALUE }?.let { if (abs(it) > 10_000) abs(it) / 1000 else abs(it) }

        val chargeCounter = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val capacity = if (chargeCounter > 0 && percent in 5..100) (chargeCounter / 1000L * 100 / percent).toInt() else null

        val cycles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            intent.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1).takeIf { it >= 0 }
        } else {
            null
        }

        return BatterySnapshot(
            percent = percent,
            status = when (statusCode) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
                BatteryManager.BATTERY_STATUS_FULL -> "Full"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
                else -> "Unknown"
            },
            plugged = when (intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
                BatteryManager.BATTERY_PLUGGED_AC -> "AC charger"
                BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
                0 -> null
                else -> "Dock"
            },
            health = when (intent.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheating"
                BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
                BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
                BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure"
                else -> "Unknown"
            },
            temperatureC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                .takeIf { it != Int.MIN_VALUE }?.div(10f),
            voltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1).takeIf { it > 0 },
            technology = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)?.takeIf { it.isNotBlank() },
            currentMa = currentMa,
            isCharging = charging,
            capacityMah = capacity,
            cycleCount = cycles,
        )
    }

    /** Android's own thermal verdict (Android 10+), more meaningful than raw sensor values. */
    fun thermalStatus(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return when (context.getSystemService(PowerManager::class.java).currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "Normal"
            PowerManager.THERMAL_STATUS_LIGHT -> "Warm"
            PowerManager.THERMAL_STATUS_MODERATE -> "Hot (moderate throttling)"
            PowerManager.THERMAL_STATUS_SEVERE -> "Very hot (throttling)"
            PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "Shutting down"
            else -> null
        }
    }

    fun uptimeMillis(): Long = SystemClock.elapsedRealtime()

    fun device(context: Context): DeviceSnapshot {
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && Build.SOC_MODEL != Build.UNKNOWN) {
            "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}"
        } else {
            Build.HARDWARE
        }
        val metrics = context.resources.displayMetrics
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val refresh = display?.refreshRate?.let { " · ${it.toInt()} Hz" }.orEmpty()
        return DeviceSnapshot(
            model = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}",
            android = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            securityPatch = Build.VERSION.SECURITY_PATCH.takeIf { it.isNotBlank() },
            soc = soc,
            abis = Build.SUPPORTED_ABIS.joinToString(),
            kernel = System.getProperty("os.version").orEmpty(),
            display = "${metrics.widthPixels} × ${metrics.heightPixels} · ${metrics.densityDpi} dpi$refresh",
        )
    }

    private val CORE_DIR = Regex("cpu\\d+")

    private fun readInt(path: String): Int? = runCatching { File(path).readText().trim().toInt() }.getOrNull()
}
