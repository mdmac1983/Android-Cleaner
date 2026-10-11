package io.github.mdmac1983.leancleaner.widget

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/** Shared plumbing for the home screen widgets. */
object Widgets {
    const val ACTION_QUICK_CLEAN = "io.github.mdmac1983.leancleaner.action.QUICK_CLEAN"
    const val ACTION_CLEAR_CACHES = "io.github.mdmac1983.leancleaner.action.CLEAR_CACHES"
    const val ACTION_MONITOR = "io.github.mdmac1983.leancleaner.action.MONITOR"
    const val ACTION_OPEN = "io.github.mdmac1983.leancleaner.action.OPEN"

    /**
     * A non-exported alias of MainActivity. Widget buttons launch it, so other apps can't trigger
     * actions such as a cache-cleaning run by starting the (exported) launcher activity.
     */
    private const val ACTION_ALIAS = "io.github.mdmac1983.leancleaner.WidgetAction"

    /** The widget action in [intent], or null if it didn't come from one of our widgets. */
    fun actionOf(intent: Intent?): String? =
        intent?.takeIf { it.component?.className == ACTION_ALIAS }?.action

    fun activityIntent(context: Context, action: String): PendingIntent {
        val intent = Intent(action)
            .setComponent(ComponentName(context.packageName, ACTION_ALIAS))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** Redraws every widget, e.g. after a cleaning run freed space. */
    fun refreshAll(context: Context) {
        QuickCleanWidget.update(context)
        StatsWidget.requestRefresh(context)
    }
}
