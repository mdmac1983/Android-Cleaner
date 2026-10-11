package io.github.mdmac1983.leancleaner.automation

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** What the automation needs from the system: open an app's App info page and look at Settings' windows. */
interface SettingsDriver {
    /** Opens Settings > Apps > [packageName] (App info) in a fresh task. */
    suspend fun openAppDetails(packageName: String): Boolean

    /** Root of the active window if it belongs to Settings, else null (never acts on other apps). */
    fun root(): UiNode?

    /** Suspends until the UI changes or [maxWaitMs] elapses, whichever comes first. */
    suspend fun awaitUiChange(maxWaitMs: Long)

    fun now(): Long
}

/** Localized texts to look for, best candidates first. */
data class SettingsLabels(
    /** App info entry leading to the app's storage page ("Storage & cache", "Storage"). */
    val storageEntry: List<String>,
    /** Button on the storage page ("Clear cache"). */
    val clearCache: List<String>,
    /** Positive button of a confirmation dialog, on Settings variants that ask ("OK", "Clear cache"). */
    val confirm: List<String>,
    /** Buttons that must never be pressed ("Clear storage", "Clear data", "Force stop", "Uninstall"). */
    val danger: List<String>,
)

data class FlowTiming(
    val pageTimeoutMs: Long = 8_000,
    val freshWindowTimeoutMs: Long = 3_000,
    val appPageTimeoutMs: Long = 3_000,
    val scrollAfterMs: Long = 700,
    val maxScrolls: Int = 10,
    /** Settings shows the button disabled until it has calculated the app's sizes. */
    val buttonSettleMs: Long = 3_000,
    val confirmTimeoutMs: Long = 2_500,
    val pollMs: Long = 150,
)

/** An app to clear; [cacheBytes] (when known) tells "already empty" apart from "can't be cleared". */
data class CleanTarget(val packageName: String, val label: String, val cacheBytes: Long? = null)

sealed interface ClearOutcome {
    data object Cleared : ClearOutcome
    data object AlreadyEmpty : ClearOutcome
    data class Failed(val reason: Reason) : ClearOutcome

    enum class Reason {
        OPEN_FAILED,
        STORAGE_ENTRY_NOT_FOUND,
        CLEAR_BUTTON_NOT_FOUND,
        /** The button stays disabled although the app has cache (e.g. system modules, admin policy). */
        BUTTON_DISABLED,
        /** The only clickable target found would also hit a destructive button; refused. */
        UNSAFE_TARGET,
        CLICK_FAILED,
    }
}

/**
 * Clears one app's cache the way a person would: App info → Storage → Clear cache.
 * Only exact label matches are ever clicked, only inside Settings windows, and never anything that
 * mentions a destructive action.
 */
class CacheClearFlow(
    private val driver: SettingsDriver,
    private val labels: SettingsLabels,
    private val timing: FlowTiming = FlowTiming(),
) {

    suspend fun clear(target: CleanTarget): ClearOutcome {
        val previousWindow = driver.root()?.windowId
        if (!driver.openAppDetails(target.packageName)) return ClearOutcome.Failed(ClearOutcome.Reason.OPEN_FAILED)

        // Make sure we're looking at the newly opened page, not the previous app's storage screen.
        if (previousWindow != null) {
            waitFor(timing.freshWindowTimeoutMs) { root -> root.takeIf { it.windowId != previousWindow } }
        }
        // App info shows the app's name in its header; waiting for it guards against stale content.
        waitFor(timing.appPageTimeoutMs) { root ->
            root.takeIf { NodeFinder.containsText(it, listOf(target.label, target.packageName)) }
        }

        val entry = waitFor(timing.pageTimeoutMs, scroll = true) { root ->
            NodeFinder.findByLabel(root, labels.storageEntry)?.let(NodeFinder::clickTarget)
        } ?: return ClearOutcome.Failed(ClearOutcome.Reason.STORAGE_ENTRY_NOT_FOUND)
        if (isDangerous(entry)) return ClearOutcome.Failed(ClearOutcome.Reason.UNSAFE_TARGET)
        if (!entry.click()) return ClearOutcome.Failed(ClearOutcome.Reason.CLICK_FAILED)

        if (waitFor(timing.pageTimeoutMs) { _ -> findClearButtonNow() } == null) {
            return ClearOutcome.Failed(ClearOutcome.Reason.CLEAR_BUTTON_NOT_FOUND)
        }
        // Sizes are calculated asynchronously; the button only becomes enabled once that's done.
        val button = waitFor(timing.buttonSettleMs) { _ -> findClearButtonNow()?.takeIf { it.usable } }?.target
        if (button == null) {
            return if ((target.cacheBytes ?: 0L) > 0L) {
                ClearOutcome.Failed(ClearOutcome.Reason.BUTTON_DISABLED)
            } else {
                ClearOutcome.AlreadyEmpty
            }
        }
        if (isDangerous(button)) return ClearOutcome.Failed(ClearOutcome.Reason.UNSAFE_TARGET)
        val storageWindow = driver.root()?.windowId
        if (!button.click()) return ClearOutcome.Failed(ClearOutcome.Reason.CLICK_FAILED)

        confirmIfAsked(storageWindow)
        return ClearOutcome.Cleared
    }

    /** The "Clear cache" label and what to click for it. Disabled buttons may not report as clickable. */
    private class ClearButton(val label: UiNode, val target: UiNode?) {
        val usable: Boolean get() = target != null && target.isEnabled && label.isEnabled
    }

    private fun findClearButtonNow(): ClearButton? {
        val root = driver.root() ?: return null
        val label = NodeFinder.findByLabel(root, labels.clearCache) ?: return null
        return ClearButton(label, NodeFinder.clickTarget(label))
    }

    private fun isDangerous(node: UiNode): Boolean = NodeFinder.mentions(node, labels.danger)

    /**
     * Some Settings variants (e.g. MIUI) ask for confirmation in a dialog. A dialog is only confirmed if
     * it is a *different* window than the storage page and talks about clearing the cache, so neither
     * the page's own button nor a "Delete app data?" dialog can ever be confirmed. Stock Android shows
     * no dialog and just disables the button once the cache is gone, which ends the wait early.
     */
    private suspend fun confirmIfAsked(storageWindow: Int?) {
        var confirmed = false
        waitFor(timing.confirmTimeoutMs) { root ->
            if (root.windowId != storageWindow) {
                if (!confirmed && NodeFinder.containsText(root, labels.clearCache)) {
                    NodeFinder.findByLabel(root, labels.confirm)
                        ?.let { NodeFinder.clickTarget(it) ?: it }
                        ?.takeIf { it.isEnabled && !isDangerous(it) }
                        ?.let { confirmed = it.click() }
                }
                null
            } else {
                // Back on (or still on) the storage page: done once the button is disabled.
                findClearButtonNow()?.takeIf { !it.usable }
            }
        }
    }

    private suspend fun <T : Any> waitFor(timeoutMs: Long, scroll: Boolean = false, find: (UiNode) -> T?): T? {
        val start = driver.now()
        var lastScroll = start
        var scrolls = 0
        while (driver.now() - start < timeoutMs) {
            currentCoroutineContext().ensureActive()
            val root = driver.root()
            if (root != null) {
                find(root)?.let { return it }
                if (scroll && scrolls < timing.maxScrolls && driver.now() - lastScroll >= timing.scrollAfterMs) {
                    if (NodeFinder.findScrollable(root)?.scrollForward() == true) scrolls++
                    lastScroll = driver.now()
                }
            }
            driver.awaitUiChange(timing.pollMs)
        }
        return null
    }
}

data class RunResult(
    val cleared: List<String>,
    val alreadyEmpty: List<String>,
    val failed: List<Pair<String, ClearOutcome.Reason>>,
    val cancelled: Boolean,
    /** True when the run stopped because this device's Settings layout isn't recognized. */
    val unsupported: Boolean,
)

/**
 * Runs [CacheClearFlow] over many apps, giving up early if the Settings layout isn't recognized.
 * A user stop ([shouldStop]) is honoured between apps so the results so far are kept.
 */
class CacheCleanRun(private val flow: CacheClearFlow, private val giveUpAfterFailures: Int = 3) {

    suspend fun run(
        targets: List<CleanTarget>,
        shouldStop: () -> Boolean = { false },
        onProgress: (index: Int, target: CleanTarget) -> Unit = { _, _ -> },
    ): RunResult {
        val cleared = ArrayList<String>()
        val empty = ArrayList<String>()
        val failed = ArrayList<Pair<String, ClearOutcome.Reason>>()
        var unsupported = false
        var cancelled = false
        for ((index, target) in targets.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (shouldStop()) {
                cancelled = true
                break
            }
            onProgress(index, target)
            when (val outcome = flow.clear(target)) {
                ClearOutcome.Cleared -> cleared += target.packageName
                ClearOutcome.AlreadyEmpty -> empty += target.packageName
                is ClearOutcome.Failed -> failed += target.packageName to outcome.reason
            }
            // Nothing worked for the first few apps: the screens don't look like what we expect.
            if (cleared.isEmpty() && empty.isEmpty() && failed.size >= giveUpAfterFailures) {
                unsupported = true
                break
            }
        }
        return RunResult(cleared, empty, failed, cancelled = cancelled, unsupported = unsupported)
    }
}
