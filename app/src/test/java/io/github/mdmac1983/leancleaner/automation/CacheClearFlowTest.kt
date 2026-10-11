package io.github.mdmac1983.leancleaner.automation

import io.github.mdmac1983.leancleaner.automation.ClearOutcome.Reason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CacheClearFlowTest {

    private val english = SettingsLabels(
        storageEntry = listOf("Storage & cache", "Storage and cache", "Storage usage", "Storage"),
        clearCache = listOf("Clear cache"),
        confirm = listOf("OK", "Clear cache"),
        danger = listOf("Clear storage", "Clear data", "Clear all data", "Force stop", "Uninstall", "Delete"),
    )

    private val apps = mapOf(
        "com.a" to ("Alpha" to 5_000L),
        "com.b" to ("Beta" to 0L),
        "com.c" to ("Gamma" to 9_000L),
    )

    private fun target(pkg: String) = CleanTarget(pkg, apps.getValue(pkg).first, apps.getValue(pkg).second)

    private fun clear(settings: FakeSettings, pkg: String, labels: SettingsLabels = english) =
        runBlocking { CacheClearFlow(settings, labels).clear(target(pkg)) }

    private fun FakeSettings.assertSafe() = assertTrue("destructive click!", destructiveClicks.isEmpty())

    @Test
    fun `stock flow opens storage and clears the cache only`() {
        val settings = FakeSettings(apps)

        assertEquals(ClearOutcome.Cleared, clear(settings, "com.a"))

        assertEquals(0L, settings.cacheOf("com.a"))
        assertEquals(listOf("Storage & cache", "Clear cache"), settings.clicks)
        settings.assertSafe()
    }

    @Test
    fun `empty cache is reported without pressing anything on the storage page`() {
        val settings = FakeSettings(apps)

        assertEquals(ClearOutcome.AlreadyEmpty, clear(settings, "com.b"))

        assertEquals(listOf("Storage & cache"), settings.clicks)
    }

    @Test
    fun `waits for sizes to be calculated before deciding`() {
        val settings = FakeSettings(apps, Variant(computingPolls = 8))

        assertEquals(ClearOutcome.Cleared, clear(settings, "com.a"))
        assertEquals(0L, settings.cacheOf("com.a"))
    }

    @Test
    fun `a button that stays disabled despite cache is reported, not counted as empty`() {
        val settings = FakeSettings(apps, Variant(locked = true))

        assertEquals(ClearOutcome.Failed(Reason.BUTTON_DISABLED), clear(settings, "com.a"))
        assertEquals(5_000L, settings.cacheOf("com.a"))
    }

    @Test
    fun `storage entry below the fold is reached by scrolling`() {
        val settings = FakeSettings(apps, Variant(entriesAbove = 12, viewport = 6))

        assertEquals(ClearOutcome.Cleared, clear(settings, "com.c"))
        assertEquals(0L, settings.cacheOf("com.c"))
    }

    @Test
    fun `MIUI-style confirmation dialogs are accepted without pressing clear cache twice`() {
        val settings = FakeSettings(apps, Variant(confirmDialog = true))

        assertEquals(ClearOutcome.Cleared, clear(settings, "com.a"))

        assertEquals(0L, settings.cacheOf("com.a"))
        assertEquals(listOf("Storage & cache", "Clear cache", "OK"), settings.clicks)
    }

    @Test
    fun `a data-wipe dialog is never confirmed`() {
        val settings = FakeSettings(apps, Variant(dataWipeDialog = true))

        clear(settings, "com.a")

        assertFalse("OK must not be pressed", "OK" in settings.clicks)
        settings.assertSafe()
        assertEquals(5_000L, settings.cacheOf("com.a"))
    }

    @Test
    fun `a stale previous screen is never acted on`() {
        val settings = FakeSettings(apps, Variant(staleRootPolls = 5))
        val flow = CacheClearFlow(settings, english)

        runBlocking {
            assertEquals(ClearOutcome.Cleared, flow.clear(target("com.a")))
            // com.a's storage page stays on screen for a while after opening com.c.
            assertEquals(ClearOutcome.Cleared, flow.clear(target("com.c")))
        }

        assertEquals(listOf("Storage & cache", "Clear cache", "Storage & cache", "Clear cache"), settings.clicks)
        assertEquals(0L, settings.cacheOf("com.c"))
        settings.assertSafe()
    }

    @Test
    fun `two-pane layouts pick the app's storage entry, not the device storage item`() {
        val settings = FakeSettings(apps, Variant(twoPane = true))

        assertEquals(ClearOutcome.Cleared, clear(settings, "com.a"))

        assertFalse("DEVICE STORAGE" in settings.clicks)
        assertEquals(0L, settings.cacheOf("com.a"))
    }

    @Test
    fun `Android 8-9 style storage entry`() {
        val settings = FakeSettings(apps, Variant(storageEntryLabel = "Storage", clearStorageLabel = "Clear data"))

        assertEquals(ClearOutcome.Cleared, clear(settings, "com.a"))
        settings.assertSafe()
    }

    @Test
    fun `Android 9 invisible duplicate button is ignored`() {
        val settings = FakeSettings(apps, Variant(invisibleDuplicate = true))

        assertEquals(ClearOutcome.Cleared, clear(settings, "com.a"))

        assertFalse("INVISIBLE" in settings.clicks)
        assertEquals(0L, settings.cacheOf("com.a"))
    }

    @Test
    fun `Android 16 expressive buttons with a clickable container`() {
        val settings = FakeSettings(apps, Variant(expressive = Variant.Expressive.CLICKABLE_CONTAINER))

        assertEquals(ClearOutcome.Cleared, clear(settings, "com.a"))
        assertEquals(0L, settings.cacheOf("com.a"))
        settings.assertSafe()
    }

    @Test
    fun `Android 16 expressive buttons where only the inner button is clickable`() {
        val settings = FakeSettings(apps, Variant(expressive = Variant.Expressive.INNER_BUTTON))

        assertEquals(ClearOutcome.Cleared, clear(settings, "com.a"))
        assertEquals(0L, settings.cacheOf("com.a"))
        settings.assertSafe()
    }

    @Test
    fun `Android 16 expressive empty cache`() {
        val settings = FakeSettings(apps, Variant(expressive = Variant.Expressive.INNER_BUTTON))

        assertEquals(ClearOutcome.AlreadyEmpty, clear(settings, "com.b"))
        settings.assertSafe()
    }

    @Test
    fun `localized labels work`() {
        val settings = FakeSettings(
            apps,
            Variant(storageEntryLabel = "Speicher & Cache", clearCacheLabel = "Cache leeren", clearStorageLabel = "Speicherinhalt löschen"),
        )
        val german = SettingsLabels(listOf("Speicher & Cache"), listOf("Cache leeren"), listOf("OK"), listOf("Speicherinhalt löschen"))

        assertEquals(ClearOutcome.Cleared, clear(settings, "com.a", german))
        settings.assertSafe()
    }

    @Test
    fun `unknown layouts fail without clicking anything`() {
        val settings = FakeSettings(apps, Variant(storageEntryLabel = "Speicher"))

        val outcome = clear(settings, "com.a")

        assertEquals(ClearOutcome.Failed(Reason.STORAGE_ENTRY_NOT_FOUND), outcome)
        assertTrue(settings.clicks.isEmpty())
    }

    @Test
    fun `labels never match by substring`() {
        val settings = FakeSettings(apps, Variant(clearCacheLabel = "Clear cache and data"))

        val outcome = clear(settings, "com.a")

        assertEquals(ClearOutcome.Failed(Reason.CLEAR_BUTTON_NOT_FOUND), outcome)
        assertEquals(listOf("Storage & cache"), settings.clicks)
        assertEquals(5_000L, settings.cacheOf("com.a"))
    }

    @Test
    fun `targets that mention a destructive action are refused`() {
        // Pretend Settings labelled the clear-storage button "Clear cache" too: its container mentions both.
        val labels = english.copy(clearCache = listOf("Clear cache"), danger = english.danger + "Clear cache")

        val outcome = clear(FakeSettings(apps), "com.a", labels)

        assertEquals(ClearOutcome.Failed(Reason.UNSAFE_TARGET), outcome)
    }

    @Test
    fun `run clears every app and reports outcomes`() {
        val settings = FakeSettings(apps)
        val progress = mutableListOf<Int>()

        val result = runBlocking {
            CacheCleanRun(CacheClearFlow(settings, english)).run(apps.keys.map(::target)) { i, _ -> progress += i }
        }

        assertEquals(listOf("com.a", "com.c"), result.cleared)
        assertEquals(listOf("com.b"), result.alreadyEmpty)
        assertTrue(result.failed.isEmpty())
        assertFalse(result.unsupported)
        assertEquals(listOf(0, 1, 2), progress)
        settings.assertSafe()
    }

    @Test
    fun `run gives up when the settings layout is not recognized`() {
        val many = (1..10).associate { "com.app$it" to ("App $it" to 100L) }
        val settings = FakeSettings(many, Variant(storageEntryLabel = "Something else"))

        val result = runBlocking {
            CacheCleanRun(CacheClearFlow(settings, english)).run(many.map { CleanTarget(it.key, it.value.first, it.value.second) })
        }

        assertTrue(result.unsupported)
        assertEquals(3, result.failed.size)
        assertTrue(settings.clicks.isEmpty())
    }

    @Test
    fun `a user stop keeps the results so far`() {
        val settings = FakeSettings(apps)
        var stop = false

        val result = runBlocking {
            CacheCleanRun(CacheClearFlow(settings, english)).run(apps.keys.map(::target), shouldStop = { stop }) { i, _ ->
                if (i == 1) stop = true
            }
        }

        assertTrue(result.cancelled)
        assertEquals(listOf("com.a"), result.cleared)
        assertEquals(listOf("com.b"), result.alreadyEmpty)
        assertEquals(9_000L, settings.cacheOf("com.c"))
    }

    @Test
    fun `coroutine cancellation stops immediately`() {
        val settings = FakeSettings(apps)
        try {
            runBlocking {
                CacheCleanRun(CacheClearFlow(settings, english)).run(apps.keys.map(::target)) { i, _ ->
                    if (i == 1) throw CancellationException("service destroyed")
                }
            }
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
        assertEquals(0L, settings.cacheOf("com.a"))
        assertEquals(9_000L, settings.cacheOf("com.c"))
    }

    @Test
    fun `node finder normalizes whitespace and case, and honours label order`() {
        val node = FakeNode(
            1,
            children = listOf(
                FakeNode(1, text = "Storage"),
                FakeNode(1, text = "  STORAGE &   Cache "),
                FakeNode(1, text = "Hidden", visible = false),
            ),
        )
        assertEquals("  STORAGE &   Cache ", NodeFinder.findByLabel(node, listOf("Storage & cache", "Storage"))?.text)
        assertEquals("Storage", NodeFinder.findByLabel(node, listOf("Storage"))?.text)
        assertEquals(null, NodeFinder.findByLabel(node, listOf("Hidden")))
        assertTrue(NodeFinder.containsText(node, listOf("storage &")))
    }
}
