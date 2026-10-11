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
    // Longer than foregroundLostMs, so "something else is on screen" is detected before "not the app page".
    val appPageTimeoutMs: Long = 6_000,
    val scrollAfterMs: Long = 700,
    val maxScrolls: Int = 10,
    /** Settings shows the button disabled until it has calculated the app's sizes. */
    val buttonSettleMs: Long = 3_000,
    val confirmTimeoutMs: Long = 2_500,
    val pollMs: Long = 150,
    /** Settings not in the foreground for this long means something else took over (e.g. a call). */
    val foregroundLostMs: Long = 5_000,
)

/** Why a run ended early. */
enum class StopReason { USER, SCREEN_OFF, PHONE_CALL, LEFT_SETTINGS }

/** An app to clear; [cacheBytes] (when known) tells "already empty" apart from "can't be cleared". */
data class CleanTarget(val packageName: String, val label: String, val cacheBytes: Long? = null)

sealed interface ClearOutcome {
    data object Cleared : ClearOutcome
    data object AlreadyEmpty : ClearOutcome

    /** "Clear cache" was pressed, but Settings never confirmed it (the button stayed enabled). */
    data object Unconfirmed : ClearOutcome
    data class Failed(val reason: Reason) : ClearOutcome

    /** Stopped mid-app; nothing more is clicked. */
    data class Interrupted(val reason: StopReason) : ClearOutcome

    enum class Reason {
        OPEN_FAILED,
        /** The opened page never showed the app's name or package, so nothing was clicked. */
        APP_PAGE_NOT_FOUND,
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
    /** Checked on every poll, so a stop, a locked screen or a phone call ends the current app at once. */
    private val interruption: () -> StopReason? = { null },
) {

    private class Interrupted(val reason: StopReason) : Exception()

    suspend fun clear(target: CleanTarget): ClearOutcome = try {
        clearOrThrow(target)
    } catch (e: Interrupted) {
        ClearOutcome.Interrupted(e.reason)
    }

    private suspend fun clearOrThrow(target: CleanTarget): ClearOutcome {
        interruption()?.let { return ClearOutcome.Interrupted(it) }
        val previousWindow = driver.root()?.windowId
        if (!driver.openAppDetails(target.packageName)) return ClearOutcome.Failed(ClearOutcome.Reason.OPEN_FAILED)

        // Make sure we're looking at the newly opened page, not the previous app's storage screen.
        if (previousWindow != null) {
            waitFor(timing.freshWindowTimeoutMs) { root -> root.takeIf { it.windowId != previousWindow } }
        }
        // Only act on a page that shows this app's name (or package): App info has it in its header.
        // That also keeps a two-pane Settings homepage (with the device-wide "Storage" item) out of play.
        val appWindow = waitFor(timing.appPageTimeoutMs) { root -> root.takeIf { isAppPage(it, target) }?.windowId }
            ?: return ClearOutcome.Failed(ClearOutcome.Reason.APP_PAGE_NOT_FOUND)

        // Scrolling can move the header out of the tree, so the verified window id is what counts.
        val entry = waitFor(timing.pageTimeoutMs, scroll = true) { root ->
            if (root.windowId != appWindow && !isAppPage(root, target)) return@waitFor null
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

        return if (confirmIfAsked(storageWindow)) ClearOutcome.Cleared else ClearOutcome.Unconfirmed
    }

    /** Exact app name on some node (e.g. "Settings" must not match "Search settings"), or the package name. */
    private fun isAppPage(root: UiNode, target: CleanTarget): Boolean =
        NodeFinder.findByLabel(root, listOf(target.label)) != null ||
            NodeFinder.containsText(root, listOf(target.packageName))

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
    /** @return true once Settings shows the cache as cleared (the button turned disabled). */
    private suspend fun confirmIfAsked(storageWindow: Int?): Boolean {
        var confirmed = false
        return waitFor(timing.confirmTimeoutMs) { root ->
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
        } != null
    }

    private suspend fun <T : Any> waitFor(timeoutMs: Long, scroll: Boolean = false, find: (UiNode) -> T?): T? {
        val start = driver.now()
        var lastScroll = start
        var scrolls = 0
        var awaySince: Long? = null
        while (driver.now() - start < timeoutMs) {
            currentCoroutineContext().ensureActive()
            interruption()?.let { throw Interrupted(it) }
            val root = driver.root()
            if (root == null) {
                val since = awaySince ?: driver.now().also { awaySince = it }
                if (driver.now() - since >= timing.foregroundLostMs) throw Interrupted(StopReason.LEFT_SETTINGS)
            } else {
                awaySince = null
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
    /** "Clear cache" pressed but not confirmed by Settings. */
    val unconfirmed: List<String>,
    val failed: List<Pair<String, ClearOutcome.Reason>>,
    /** Why the run ended early, or null if it went through the whole list. */
    val stoppedBy: StopReason?,
    /** True when the run stopped because this device's Settings layout isn't recognized. */
    val unsupported: Boolean,
) {
    val cancelled: Boolean get() = stoppedBy != null
}

/**
 * Runs [CacheClearFlow] over many apps, giving up early if the Settings layout isn't recognized.
 * A stop ([shouldStop], or an interruption detected by the flow) keeps the results so far.
 */
class CacheCleanRun(private val flow: CacheClearFlow, private val giveUpAfterFailures: Int = 3) {

    suspend fun run(
        targets: List<CleanTarget>,
        shouldStop: () -> StopReason? = { null },
        onProgress: (index: Int, target: CleanTarget) -> Unit = { _, _ -> },
    ): RunResult {
        val cleared = ArrayList<String>()
        val empty = ArrayList<String>()
        val unconfirmed = ArrayList<String>()
        val failed = ArrayList<Pair<String, ClearOutcome.Reason>>()
        var unsupported = false
        var stoppedBy: StopReason? = null
        for ((index, target) in targets.withIndex()) {
            currentCoroutineContext().ensureActive()
            shouldStop()?.let {
                stoppedBy = it
                break
            }
            onProgress(index, target)
            when (val outcome = flow.clear(target)) {
                ClearOutcome.Cleared -> cleared += target.packageName
                ClearOutcome.AlreadyEmpty -> empty += target.packageName
                ClearOutcome.Unconfirmed -> unconfirmed += target.packageName
                is ClearOutcome.Failed -> failed += target.packageName to outcome.reason
                is ClearOutcome.Interrupted -> {
                    stoppedBy = outcome.reason
                    break
                }
            }
            // Nothing worked for the first few apps: the screens don't look like what we expect.
            if (cleared.isEmpty() && empty.isEmpty() && unconfirmed.isEmpty() && failed.size >= giveUpAfterFailures) {
                unsupported = true
                break
            }
        }
        return RunResult(cleared, empty, unconfirmed, failed, stoppedBy, unsupported)
    }
}
