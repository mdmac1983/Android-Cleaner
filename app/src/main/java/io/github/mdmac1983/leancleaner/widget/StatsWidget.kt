package io.github.mdmac1983.leancleaner.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.text.format.DateFormat
import android.widget.RemoteViews
import io.github.mdmac1983.leancleaner.R
import io.github.mdmac1983.leancleaner.core.Format
import io.github.mdmac1983.leancleaner.core.Volumes
import io.github.mdmac1983.leancleaner.monitor.NetworkTraffic
import io.github.mdmac1983.leancleaner.monitor.SystemInfo
import io.github.mdmac1983.leancleaner.monitor.TrafficRates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Date
import java.util.Locale

/**
 * Battery, RAM, storage and network speed in one widget.
 *
 * Refreshes about once a minute through a non-wakeup alarm: it never wakes the phone, and while the
 * screen is off it only re-arms itself. The network speed is measured over one second at each refresh.
 */
class StatsWidget : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_REFRESH) {
            refresh(context, scheduled = intent.getBooleanExtra(EXTRA_SCHEDULED, false))
        } else {
            super.onReceive(context, intent)
        }
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        refresh(context, scheduled = false)
    }

    override fun onDisabled(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context))
    }

    private fun refresh(context: Context, scheduled: Boolean) {
        val app = context.applicationContext
        val pending = goAsync()
        scope.launch {
            try {
                // A failed refresh just leaves the previous numbers; it must never crash the app.
                runCatching { update(app, scheduled) }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val ACTION_REFRESH = "io.github.mdmac1983.leancleaner.widget.REFRESH_STATS"
        private const val EXTRA_SCHEDULED = "scheduled"
        private const val INTERVAL_MS = 60_000L
        private const val SAMPLE_MS = 1_000L

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        fun requestRefresh(context: Context) {
            if (ids(context).isNotEmpty()) context.sendBroadcast(refreshIntent(context, scheduled = false))
        }

        /** Re-arms the refresh alarm if a widget is placed (alarms don't survive a force stop). */
        fun ensureScheduled(context: Context) {
            if (ids(context).isNotEmpty()) schedule(context)
        }

        private fun ids(context: Context): IntArray =
            AppWidgetManager.getInstance(context)?.getAppWidgetIds(ComponentName(context, StatsWidget::class.java))
                ?: IntArray(0)

        private suspend fun update(context: Context, scheduled: Boolean) {
            val ids = ids(context)
            if (ids.isEmpty()) return
            schedule(context)
            if (scheduled && !context.getSystemService(PowerManager::class.java).isInteractive) return
            val before = NetworkTraffic.read()
            delay(SAMPLE_MS)
            val after = NetworkTraffic.read()
            val rates = if (before != null && after != null) NetworkTraffic.rates(before, after) else null
            AppWidgetManager.getInstance(context).updateAppWidget(ids, views(context, rates, network = after != null))
        }

        private fun views(context: Context, rates: TrafficRates?, network: Boolean) =
            RemoteViews(context.packageName, R.layout.widget_stats).apply {
                SystemInfo.battery(context)?.let { b ->
                    setTextViewText(R.id.battery_percent, "${b.percent}%")
                    setProgressBar(R.id.battery_bar, 100, b.percent, false)
                    val temperature = b.temperatureC?.let { String.format(Locale.getDefault(), "%.1f °C", it) }
                    setTextViewText(R.id.battery_detail, listOfNotNull(temperature, b.status).joinToString(" · "))
                }

                val memory = SystemInfo.memory(context)
                gauge(R.id.memory_percent, R.id.memory_bar, R.id.memory_detail, memory.usedBytes, memory.totalBytes)

                Volumes.list(context).let { volumes -> volumes.firstOrNull { it.isPrimary } ?: volumes.firstOrNull() }
                    ?.let { gauge(R.id.storage_percent, R.id.storage_bar, R.id.storage_detail, it.usedBytes, it.totalBytes) }

                // Show mobile data instead of Wi-Fi only while mobile is the one carrying traffic.
                val mobile = rates != null && rates.wifiDown + rates.wifiUp == 0L && rates.mobileDown + rates.mobileUp > 0
                setImageViewResource(R.id.network_icon, if (mobile) R.drawable.ic_widget_mobile else R.drawable.ic_widget_wifi)
                setTextViewText(R.id.network_label, context.getString(if (mobile) R.string.widget_mobile else R.string.widget_wifi))
                val down = if (mobile) rates?.mobileDown else rates?.wifiDown
                val up = if (mobile) rates?.mobileUp else rates?.wifiUp
                setTextViewText(R.id.network_down, "↓ ${down?.let { Format.speed(it) } ?: if (network) "–" else "n/a"}")
                setTextViewText(R.id.network_up, "↑ ${up?.let { Format.speed(it) } ?: if (network) "–" else "n/a"}")
                setTextViewText(R.id.network_time, DateFormat.getTimeFormat(context).format(Date()))

                val monitor = Widgets.activityIntent(context, Widgets.ACTION_MONITOR)
                listOf(R.id.battery_cell, R.id.memory_cell, R.id.storage_cell).forEach { setOnClickPendingIntent(it, monitor) }
                setOnClickPendingIntent(
                    R.id.network_cell,
                    PendingIntent.getBroadcast(
                        context,
                        REQUEST_REFRESH,
                        refreshIntent(context, scheduled = false),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
            }

        private fun RemoteViews.gauge(percentId: Int, barId: Int, detailId: Int, used: Long, total: Long) {
            if (total <= 0) return
            val percent = Format.percent(used, total)
            setTextViewText(percentId, "$percent%")
            setProgressBar(barId, 100, percent, false)
            setTextViewText(detailId, "${Format.size(used)} / ${Format.size(total)}")
        }

        private fun refreshIntent(context: Context, scheduled: Boolean) =
            Intent(context, StatsWidget::class.java).setAction(ACTION_REFRESH).putExtra(EXTRA_SCHEDULED, scheduled)

        private const val REQUEST_REFRESH = 1
        private const val REQUEST_ALARM = 2

        private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_ALARM,
            refreshIntent(context, scheduled = true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /** Inexact and non-wakeup: delivered the next time the phone is awake anyway. */
        private fun schedule(context: Context) {
            context.getSystemService(AlarmManager::class.java)
                .set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + INTERVAL_MS, alarmIntent(context))
        }
    }
}
