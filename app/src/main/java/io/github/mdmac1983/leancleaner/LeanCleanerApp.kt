package io.github.mdmac1983.leancleaner

import android.app.Application
import android.content.Context
import io.github.mdmac1983.leancleaner.core.Prefs

class LeanCleanerApp : Application() {
    val prefs: Prefs by lazy { Prefs(this) }
}

val Context.prefs: Prefs get() = (applicationContext as LeanCleanerApp).prefs
