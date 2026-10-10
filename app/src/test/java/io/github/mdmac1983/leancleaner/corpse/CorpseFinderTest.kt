package io.github.mdmac1983.leancleaner.corpse

import io.github.mdmac1983.leancleaner.core.Exclusions
import io.github.mdmac1983.leancleaner.dir
import io.github.mdmac1983.leancleaner.file
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CorpseFinderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private val installed = setOf("com.installed.app", "com.instagram.android")

    @Before
    fun setUp() {
        root = tmp.newFolder("emulated0")
    }

    private fun find(exclusions: Exclusions = Exclusions.NONE): List<Corpse> =
        runBlocking { CorpseFinder({ it in installed }, exclusions).find(listOf(root)) }

    private fun List<Corpse>.paths() = map { it.path.removePrefix(root.absolutePath + "/") }.toSet()

    @Test
    fun `app folders of uninstalled packages are leftovers`() {
        root.file("Android/data/com.gone.app/files/a.bin", 100)
        root.file("Android/obb/com.gone.game/main.obb", 300)
        root.file("Android/data/com.installed.app/files/a.bin", 100)
        root.dir("Android/data/.hidden")

        val corpses = find()

        assertEquals(setOf("Android/data/com.gone.app", "Android/obb/com.gone.game"), corpses.paths())
        val obb = corpses.first()
        assertEquals("sorted by size", CorpseLocation.APP_OBB, obb.location)
        assertEquals(300, obb.size)
        assertEquals("com.gone.game", obb.owner)
        assertTrue(obb.preselected)
    }

    @Test
    fun `leftovers containing media are not pre-selected`() {
        root.file("Android/media/com.gone.chat/Media/photo.jpg", 10)
        root.file("Android/media/com.gone.chat/Media/db.crypt", 10)

        val corpse = find().single()

        assertEquals(CorpseLocation.APP_MEDIA, corpse.location)
        assertEquals(2, corpse.fileCount)
        assertEquals(1, corpse.mediaCount)
        assertFalse(corpse.preselected)
    }

    @Test
    fun `known folders and package-named folders in shared storage`() {
        root.file("whatsapp/Databases/msgstore.db.crypt14", 5)
        root.file("Pictures/Instagram/saved.jpg", 5)
        root.file("com.vendor.sdk/cache.bin", 5)
        root.file("com.installed.app/config", 5)
        root.file("my.photos/pic.jpg", 5)
        root.file("Documents/notes.txt", 5)

        val corpses = find()

        assertEquals(setOf("whatsapp", "com.vendor.sdk"), corpses.paths())
        assertEquals("WhatsApp", corpses.single { it.path.endsWith("whatsapp") }.owner)
    }

    @Test
    fun `excluded leftovers are skipped`() {
        root.file("Android/data/com.gone.app/x", 1)
        val exclusions = Exclusions(listOf(File(root, "Android/data/com.gone.app").absolutePath))
        assertTrue(find(exclusions).isEmpty())
    }

    @Test
    fun `package name heuristics`() {
        assertTrue(CorpseFinder.looksLikePackage("com.whatsapp"))
        assertTrue(CorpseFinder.looksLikePackage("org.telegram.messenger"))
        assertFalse(CorpseFinder.looksLikePackage("WhatsApp"))
        assertFalse(CorpseFinder.looksLikePackage(".hidden.dir"))
        assertFalse(CorpseFinder.looksLikePackage("com.1bad"))
        assertTrue(CorpseFinder.looksLikePublicPackageFolder("com.example"))
        assertTrue(CorpseFinder.looksLikePublicPackageFolder("acme.tools.app"))
        assertFalse(CorpseFinder.looksLikePublicPackageFolder("my.photos"))
    }
}
