package io.github.mdmac1983.leancleaner.automation

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.net.toUri

/**
 * Reads the exact, localized texts the device's own Settings app shows, by looking up its string
 * resources by name. That makes the automation work in every language without a translation table.
 */
object SettingsLabelResolver {

    /** Packages whose windows the automation may act on: whoever handles App info, plus AOSP Settings. */
    fun settingsPackages(context: Context): Set<String> {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.resolveActivity(intent, 0)
        }
        return setOfNotNull(resolved?.activityInfo?.packageName, AOSP_SETTINGS)
    }

    fun resolve(context: Context, packages: Set<String>): SettingsLabels {
        val storage = ArrayList<String>()
        val clear = ArrayList<String>()
        val confirm = ArrayList<String>()
        val danger = ArrayList<String>()
        for (pkg in packages) {
            val strings = SettingsStrings(context, pkg)
            STORAGE_ENTRY_KEYS.mapNotNullTo(storage, strings::get)
            CLEAR_CACHE_KEYS.mapNotNullTo(clear, strings::get)
            CONFIRM_KEYS.mapNotNullTo(confirm, strings::get)
            DANGER_KEYS.mapNotNullTo(danger, strings::get)
        }
        danger += DANGER_FALLBACK
        confirm += context.getString(android.R.string.ok)
        // English fallbacks for Settings builds whose resource names differ.
        storage += STORAGE_ENTRY_FALLBACK
        clear += CLEAR_CACHE_FALLBACK
        confirm += CONFIRM_FALLBACK
        // A dialog's positive button may repeat the action ("Clear cache"); only pressed in dialogs.
        confirm += clear
        return SettingsLabels(storage.distinct(), clear.distinct(), confirm.distinct(), danger.distinct())
    }

    private class SettingsStrings(context: Context, private val pkg: String) {
        private val resources = runCatching { context.packageManager.getResourcesForApplication(pkg) }.getOrNull()

        @SuppressLint("DiscouragedApi") // Looking up another app's strings by name is the whole point here.
        fun get(name: String): String? {
            val res = resources ?: return null
            val id = res.getIdentifier(name, "string", pkg)
            if (id == 0) return null
            return runCatching { res.getString(id) }.getOrNull()?.takeIf { it.isNotBlank() && !it.contains('%') }
        }
    }

    private const val AOSP_SETTINGS = "com.android.settings"

    /**
     * App info → storage entry, most specific first: "Storage & cache" (AOSP 10+), "Storage usage"
     * (OnePlus/OPPO/realme), "Storage" (AOSP 8-9, Samsung). Order matters on two-pane layouts where the
     * device-wide "Storage" item is visible next to the app's page.
     */
    private val STORAGE_ENTRY_KEYS = listOf("storage_settings_for_app", "storage_use", "storage_settings")
    private val STORAGE_ENTRY_FALLBACK = listOf("Storage & cache", "Storage and cache", "Storage usage", "Storage")

    private val CLEAR_CACHE_KEYS = listOf("clear_cache_btn_text")
    private val CLEAR_CACHE_FALLBACK = listOf("Clear cache")

    private val CONFIRM_KEYS = listOf("dlg_ok", "okay")
    // Never "Delete"/"Clear": those confirm data-wiping dialogs, which this flow must not touch.
    private val CONFIRM_FALLBACK = listOf("OK")

    /** Destructive buttons near "Clear cache"; anything mentioning them is never clicked. */
    private val DANGER_KEYS = listOf("clear_user_data_text", "force_stop", "uninstall_text", "app_manager_menu_clear_data")
    private val DANGER_FALLBACK = listOf("Clear storage", "Clear data", "Clear all data", "Force stop", "Uninstall", "Delete")
}
