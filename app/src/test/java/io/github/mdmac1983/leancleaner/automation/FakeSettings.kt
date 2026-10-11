package io.github.mdmac1983.leancleaner.automation

/** How the simulated Settings app looks and behaves; defaults model stock Android 10-15. */
data class Variant(
    val storageEntryLabel: String = "Storage & cache",
    val clearCacheLabel: String = "Clear cache",
    val clearStorageLabel: String = "Clear storage",
    /** Entries shown above the storage entry; with a small viewport it starts off-screen. */
    val entriesAbove: Int = 3,
    val viewport: Int = 10,
    /** MIUI-style "Clear cache?" dialog after pressing the button. */
    val confirmDialog: Boolean = false,
    /** A broken/hostile variant that answers "Clear cache" with a data-wipe dialog. */
    val dataWipeDialog: Boolean = false,
    /** Polls during which the previous screen is still reported after opening a new page. */
    val staleRootPolls: Int = 0,
    /** Polls during which the buttons are disabled while sizes are being calculated. */
    val computingPolls: Int = 0,
    /** Clear cache stays disabled even though there is cache (mainline module, admin policy). */
    val locked: Boolean = false,
    /** Android 9: two overlapping "Clear cache" buttons, the first one invisible. */
    val invisibleDuplicate: Boolean = false,
    /** Android 16 expressive buttons: label on a container (content description) around the real button. */
    val expressive: Expressive = Expressive.NONE,
    /** Large-screen layout with the top-level Settings list (incl. device "Storage") next to App info. */
    val twoPane: Boolean = false,
    val openSucceeds: Boolean = true,
    /** Something else (e.g. an incoming call) covers Settings right after it is opened. */
    val foreignAfterOpen: Boolean = false,
    /** Polls during which a separate Settings homepage window (two-pane trampoline) is active after opening. */
    val homepagePolls: Int = 0,
    /** Clearing works but the button never turns disabled (completion can't be observed). */
    val buttonStaysEnabled: Boolean = false,
) {
    enum class Expressive {
        NONE,
        /** 16 r1/r2: the container isn't clickable, the inner button is. */
        INNER_BUTTON,
        /** 16 r3+/QPR1+: the container is clickable. */
        CLICKABLE_CONTAINER,
    }
}

/** A tiny simulation of the Settings app: App info → Storage page → (optional) dialog. */
class FakeSettings(
    apps: Map<String, Pair<String, Long>>,
    private val v: Variant = Variant(),
) : SettingsDriver {

    private val cache = apps.mapValues { it.value.second }.toMutableMap()
    private val labels = apps.mapValues { it.value.first }

    val clicks = mutableListOf<String>()
    val destructiveClicks = mutableListOf<String>()
    private var clock = 0L
    private var nextWindow = 100

    private sealed interface Screen { val windowId: Int }
    private data class Home(override val windowId: Int) : Screen
    private data class AppInfo(val pkg: String, override val windowId: Int, var scrolled: Int = 0) : Screen
    private data class Storage(val pkg: String, override val windowId: Int, var computingLeft: Int) : Screen
    private data class Dialog(val storage: Storage, override val windowId: Int, val wipe: Boolean) : Screen

    private var screen: Screen = Home(nextWindow++)
    private var stale: Screen? = null
    private var staleLeft = 0
    private var homepageLeft = 0
    private val homepageWindow = nextWindow++

    fun cacheOf(pkg: String): Long = cache.getValue(pkg)

    override suspend fun openAppDetails(packageName: String): Boolean {
        if (!v.openSucceeds) return false
        stale = screen
        staleLeft = v.staleRootPolls
        homepageLeft = v.homepagePolls
        screen = AppInfo(packageName, nextWindow++)
        return true
    }

    override fun root(): UiNode? {
        if (v.foreignAfterOpen && screen !is Home) return null
        if (homepageLeft > 0) return homepage()
        return build(if (staleLeft > 0) stale!! else screen)
    }

    override suspend fun awaitUiChange(maxWaitMs: Long) {
        clock += maxWaitMs
        if (staleLeft > 0) staleLeft--
        if (homepageLeft > 0) homepageLeft--
        (screen as? Storage)?.let { if (it.computingLeft > 0) it.computingLeft-- }
    }

    override fun now(): Long = clock

    /** Settings homepage in its own window, with the device-wide Storage item. */
    private fun homepage(): FakeNode = FakeNode(
        homepageWindow,
        children = listOf(FakeNode(homepageWindow, text = "Settings"), FakeNode(homepageWindow, text = "Search settings")) +
            listOf("Network & internet", "Storage", "Apps").map { title ->
                row(homepageWindow, title) { if (title == "Storage") clicks += "DEVICE STORAGE" }
            },
    )

    private fun build(s: Screen): UiNode = when (s) {
        is Home -> FakeNode(s.windowId, text = "Settings")
        is AppInfo -> appInfo(s)
        is Storage -> storage(s)
        is Dialog -> dialog(s)
    }

    private fun appInfo(s: AppInfo): FakeNode {
        val entries = buildList {
            repeat(v.entriesAbove) { add("Entry $it") }
            add(v.storageEntryLabel)
            add("Mobile data & Wi-Fi")
            add("Open by default")
        }
        val visible = entries.drop(s.scrolled).take(v.viewport)
        val details = FakeNode(
            s.windowId,
            children = listOf(
                FakeNode(s.windowId, text = "App info"),
                FakeNode(s.windowId, text = labels.getValue(s.pkg)),
                FakeNode(
                    s.windowId,
                    scrollable = true,
                    onScroll = { if (s.scrolled + v.viewport < entries.size) { s.scrolled++; true } else false },
                    children = visible.map { title -> row(s.windowId, title) { if (title == v.storageEntryLabel) open(s.pkg) } },
                ),
            ),
        )
        if (!v.twoPane) return details
        val leftPane = FakeNode(
            s.windowId,
            children = listOf("Network & internet", "Storage", "Apps").map { title ->
                row(s.windowId, title) { if (title == "Storage") clicks += "DEVICE STORAGE" }
            },
        )
        return FakeNode(s.windowId, children = listOf(leftPane, details))
    }

    /** Like a Preference row: the title TextView isn't clickable, the row is. */
    private fun row(windowId: Int, title: String, onClick: () -> Unit) = FakeNode(
        windowId,
        clickable = true,
        onClick = { clicks += title; onClick(); true },
        children = listOf(FakeNode(windowId, text = title)),
    )

    private fun open(pkg: String) {
        screen = Storage(pkg, nextWindow++, v.computingPolls)
    }

    private fun storage(s: Storage): FakeNode {
        val cacheEnabled = (cache.getValue(s.pkg) > 0 || v.buttonStaysEnabled) && s.computingLeft == 0 && !v.locked
        val buttons = buildList {
            add(button(s.windowId, v.clearStorageLabel, enabled = s.computingLeft == 0) {
                clicks += v.clearStorageLabel
                destructiveClicks += s.pkg
                cache[s.pkg] = 0
            })
            if (v.invisibleDuplicate) {
                add(button(s.windowId, v.clearCacheLabel, enabled = cacheEnabled, visible = false) { clicks += "INVISIBLE" })
            }
            add(button(s.windowId, v.clearCacheLabel, enabled = cacheEnabled) {
                clicks += v.clearCacheLabel
                when {
                    v.dataWipeDialog -> screen = Dialog(s, nextWindow++, wipe = true)
                    v.confirmDialog -> screen = Dialog(s, nextWindow++, wipe = false)
                    else -> cache[s.pkg] = 0
                }
            })
        }
        return FakeNode(
            s.windowId,
            children = listOf(
                FakeNode(s.windowId, text = v.storageEntryLabel), // toolbar title, not clickable
                FakeNode(s.windowId, text = labels.getValue(s.pkg)),
                FakeNode(s.windowId, children = buttons), // the button band
                FakeNode(s.windowId, text = "Cache"),
            ),
        )
    }

    private fun button(windowId: Int, label: String, enabled: Boolean, visible: Boolean = true, onClick: () -> Unit): FakeNode =
        when (v.expressive) {
            Variant.Expressive.NONE -> FakeNode(
                windowId, text = label, clickable = true, enabled = enabled, visible = visible,
                onClick = { onClick(); true },
            )
            else -> FakeNode(
                windowId,
                contentDescription = label,
                clickable = v.expressive == Variant.Expressive.CLICKABLE_CONTAINER,
                enabled = enabled,
                visible = visible,
                onClick = { onClick(); true },
                children = listOf(
                    FakeNode(windowId, clickable = v.expressive == Variant.Expressive.INNER_BUTTON, enabled = enabled, onClick = { onClick(); true }),
                    FakeNode(windowId, text = label),
                ),
            )
        }

    private fun dialog(s: Dialog): FakeNode = FakeNode(
        s.windowId,
        children = listOf(
            FakeNode(s.windowId, text = if (s.wipe) "Delete app data?" else "Clear cache?"),
            FakeNode(s.windowId, text = "Cancel", clickable = true, onClick = { clicks += "Cancel"; screen = s.storage; true }),
            FakeNode(
                s.windowId,
                text = "OK",
                clickable = true,
                onClick = {
                    clicks += "OK"
                    if (s.wipe) destructiveClicks += s.storage.pkg
                    cache[s.storage.pkg] = 0
                    screen = s.storage
                    true
                },
            ),
        ),
    )
}

class FakeNode(
    override val windowId: Int,
    override val text: CharSequence? = null,
    override val contentDescription: CharSequence? = null,
    clickable: Boolean = false,
    enabled: Boolean = true,
    scrollable: Boolean = false,
    visible: Boolean = true,
    private val onClick: () -> Boolean = { false },
    private val onScroll: () -> Boolean = { false },
    children: List<FakeNode> = emptyList(),
) : UiNode {
    override val isClickable = clickable
    override val isEnabled = enabled
    override val isScrollable = scrollable
    override val isVisibleToUser = visible
    override var parent: UiNode? = null
        private set
    override val children: List<UiNode> = children.onEach { it.parent = this }

    override fun click(): Boolean = isClickable && isEnabled && onClick()
    override fun scrollForward(): Boolean = isScrollable && onScroll()
}
