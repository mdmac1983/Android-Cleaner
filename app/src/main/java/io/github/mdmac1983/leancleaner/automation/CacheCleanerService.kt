package io.github.mdmac1983.leancleaner.automation

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.net.toUri
import io.github.mdmac1983.leancleaner.MainActivity
import io.github.mdmac1983.leancleaner.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Presses "Clear cache" in Settings for a list of apps. It does nothing and listens to nothing until
 * a run is started from the app, and it only ever acts on Settings windows.
 */
class CacheCleanerService : AccessibilityService(), SettingsDriver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    @Volatile private var stopRequested = false
    @Volatile private var settingsPackages: Set<String> = emptySet()
    private val uiChanges = MutableStateFlow(0L)

    private var overlay: View? = null
    private var overlayText: TextView? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        listen(false)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (job?.isActive == true) uiChanges.value++
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        // Cancelling the run lets its finally block publish the final state.
        scope.cancel()
        removeOverlay()
        super.onDestroy()
    }

    /** Starts a run unless one is already going. */
    fun start(requested: List<CleanTarget>): Boolean {
        if (job?.isActive == true) return false
        settingsPackages = SettingsLabelResolver.settingsPackages(this)
        // Settings itself is what we drive; clearing its cache mid-run makes no sense.
        val targets = requested.filterNot { it.packageName in settingsPackages }
        if (targets.isEmpty()) return false
        stopRequested = false
        CacheAutomation.state.value = CacheAutomation.State.Running(0, targets.size, targets.first().label)
        job = scope.launch {
            var result: RunResult? = null
            var error: String? = null
            try {
                listen(true)
                showOverlay()
                result = withContext(Dispatchers.Default) {
                    val labels = SettingsLabelResolver.resolve(this@CacheCleanerService, settingsPackages)
                    val flow = CacheClearFlow(this@CacheCleanerService, labels, interruption = ::interruption)
                    CacheCleanRun(flow).run(targets, shouldStop = ::interruption) { index, target ->
                        CacheAutomation.state.value = CacheAutomation.State.Running(index, targets.size, target.label)
                        scope.launch { overlayText?.text = progressText(index, targets.size, target.label) }
                    }
                }
            } catch (e: CancellationException) {
                error = "the accessibility service was switched off"
                throw e
            } catch (e: Exception) {
                // Accessibility calls can throw if Settings goes away mid-run; never crash the app over it.
                error = e.message ?: e.javaClass.simpleName
            } finally {
                listen(false)
                removeOverlay()
                // Always end in a terminal state (even when cancelled), so later runs aren't refused as busy.
                CacheAutomation.state.value = CacheAutomation.State.Finished(result, targets.size, error)
            }
            // Don't jump over a phone call or the lock screen; only come back after a normal end or a user stop.
            val stoppedBy = result?.stoppedBy
            if (stoppedBy == null || stoppedBy == StopReason.USER) returnToApp()
        }
        return true
    }

    /** Takes effect within one poll; the overlay goes away at once so the screen is usable immediately. */
    fun stop() {
        stopRequested = true
        removeOverlay()
    }

    /** Checked on every poll: a stop, a locked/off screen or a call ends the run immediately. */
    private fun interruption(): StopReason? = when {
        stopRequested -> StopReason.USER
        !screenUsable() -> StopReason.SCREEN_OFF
        phoneBusy() -> StopReason.PHONE_CALL
        else -> null
    }

    /** Settings can't be driven while the screen is off or locked. */
    private fun screenUsable(): Boolean =
        getSystemService(PowerManager::class.java).isInteractive &&
            !getSystemService(KeyguardManager::class.java).isKeyguardLocked

    /** Ringing, in a call or in a VoIP call; needs no phone permission. */
    private fun phoneBusy(): Boolean = getSystemService(AudioManager::class.java).mode != AudioManager.MODE_NORMAL

    // region SettingsDriver

    override suspend fun openAppDetails(packageName: String): Boolean = withContext(Dispatchers.Main) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri())
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or Intent.FLAG_ACTIVITY_NO_HISTORY,
            )
        try {
            startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    override fun root(): UiNode? {
        val root = rootInActiveWindow ?: return null
        if (root.packageName?.toString() !in settingsPackages) return null
        return AccessibilityUiNode(root)
    }

    override suspend fun awaitUiChange(maxWaitMs: Long) {
        val seen = uiChanges.value
        withTimeoutOrNull(maxWaitMs) { uiChanges.first { it != seen } }
    }

    override fun now(): Long = SystemClock.uptimeMillis()

    // endregion

    /** Window events are only needed during a run; idle, the service receives nothing at all. */
    private fun listen(active: Boolean) {
        val info = serviceInfo ?: return
        info.eventTypes = if (active) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        } else {
            0
        }
        serviceInfo = info
    }

    private fun returnToApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        runCatching { startActivity(intent) }
    }

    private fun progressText(index: Int, total: Int, label: String) =
        getString(R.string.automation_overlay_progress, index + 1, total, label)

    /**
     * Full-screen, touch-blocking overlay with progress and a Stop button, so stray taps can't interfere
     * with the run. Accessibility overlays need no extra permission and don't affect the service's
     * own clicks, which go through the accessibility API rather than touch events.
     */
    private fun showOverlay() {
        if (overlay != null) return
        val wm = getSystemService(WindowManager::class.java)
        fun dp(value: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
        val title = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            setText(R.string.automation_overlay_title)
        }
        val hint = TextView(this).apply {
            setTextColor(Color.argb(200, 255, 255, 255))
            textSize = 14f
            setText(R.string.automation_overlay_hint)
            setPadding(0, dp(8), 0, dp(16))
        }
        val progress = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 15f
            maxLines = 2
            setText(R.string.automation_overlay_starting)
        }
        val button = Button(this).apply {
            setText(R.string.automation_overlay_stop)
            setOnClickListener { stop() }
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(Color.argb(255, 30, 36, 34))
            }
            addView(title)
            addView(hint)
            addView(progress)
            addView(
                button,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { gravity = Gravity.END; topMargin = dp(16) },
            )
        }
        val scrim = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(204, 0, 0, 0))
            isClickable = true // swallow touches
            addView(
                card,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
                    .apply { setMargins(dp(24), 0, dp(24), 0) },
            )
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        runCatching { wm.addView(scrim, params) }.onSuccess {
            overlay = scrim
            overlayText = progress
        }
    }

    private fun removeOverlay() {
        val view = overlay ?: return
        runCatching { getSystemService(WindowManager::class.java).removeView(view) }
        overlay = null
        overlayText = null
    }

    companion object {
        /** The bound service, if any. Cleared in onDestroy, so it never outlives the service. */
        @SuppressLint("StaticFieldLeak")
        @Volatile
        internal var instance: CacheCleanerService? = null
            private set
    }
}

/** Entry point for the UI: setup checks, starting/stopping runs and observing progress. */
object CacheAutomation {

    sealed interface State {
        data object Idle : State
        data class Running(val index: Int, val total: Int, val current: String) : State
        /** [result] is null when the run ended with an unexpected [error]. */
        data class Finished(val result: RunResult?, val total: Int, val error: String? = null) : State
    }

    val state = MutableStateFlow<State>(State.Idle)

    fun isEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val component = ComponentName(context, CacheCleanerService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == component }
    }

    /** True once the system has bound the service (shortly after it's switched on). */
    val isConnected: Boolean get() = CacheCleanerService.instance != null

    fun start(targets: List<CleanTarget>): Boolean = CacheCleanerService.instance?.start(targets) ?: false

    fun stop() {
        CacheCleanerService.instance?.stop()
    }

    /** Called by the UI once it has shown a finished run's summary. */
    fun acknowledge() {
        if (state.value is State.Finished) state.value = State.Idle
    }

    /**
     * The system's accessibility settings. The per-service details page is a system-only API, so this
     * opens the list and (on AOSP-based Settings) scrolls to and highlights our entry.
     */
    fun settingsIntent(context: Context): Intent {
        val key = ComponentName(context, CacheCleanerService::class.java).flattenToString()
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .putExtra(EXTRA_FRAGMENT_ARG_KEY, key)
            .putExtra(EXTRA_SHOW_FRAGMENT_ARGUMENTS, Bundle().apply { putString(EXTRA_FRAGMENT_ARG_KEY, key) })
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun fallbackSettingsIntent(): Intent = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
    private const val EXTRA_SHOW_FRAGMENT_ARGUMENTS = ":settings:show_fragment_args"
}
