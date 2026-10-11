package io.github.mdmac1983.leancleaner.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import io.github.mdmac1983.leancleaner.R
import io.github.mdmac1983.leancleaner.core.Format
import io.github.mdmac1983.leancleaner.prefs

/**
 * "Scan junk" opens the Junk cleaner and starts a scan (nothing is deleted before you review it);
 * "Clear caches" starts the one-tap app cache cleaning.
 */
class QuickCleanWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        manager.updateAppWidget(ids, views(context))
    }

    companion object {
        fun update(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, QuickCleanWidget::class.java))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(context))
        }

        private fun views(context: Context) = RemoteViews(context.packageName, R.layout.widget_quick_clean).apply {
            val freed = context.prefs.totalFreed.value
            if (freed > 0) setTextViewText(R.id.quick_freed, "${Format.size(freed)} freed so far")
            setOnClickPendingIntent(R.id.quick_header, Widgets.activityIntent(context, Widgets.ACTION_OPEN))
            setOnClickPendingIntent(R.id.quick_scan, Widgets.activityIntent(context, Widgets.ACTION_QUICK_CLEAN))
            setOnClickPendingIntent(R.id.quick_caches, Widgets.activityIntent(context, Widgets.ACTION_CLEAR_CACHES))
        }
    }
}
