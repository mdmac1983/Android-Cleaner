package io.github.mdmac1983.leancleaner

import android.app.Application
import android.content.Context
import io.github.mdmac1983.leancleaner.core.Prefs
import io.github.mdmac1983.leancleaner.widget.StatsWidget
import io.github.mdmac1983.leancleaner.widget.Widgets
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

class LeanCleanerApp : Application() {
    val prefs: Prefs by lazy { Prefs(this) }

    override fun onCreate() {
        super.onCreate()
        StatsWidget.ensureScheduled(this)
        // Show freed space and new storage numbers on the widgets after every clean.
        MainScope().launch { prefs.totalFreed.drop(1).collect { Widgets.refreshAll(this@LeanCleanerApp) } }
    }
}

val Context.prefs: Prefs get() = (applicationContext as LeanCleanerApp).prefs
