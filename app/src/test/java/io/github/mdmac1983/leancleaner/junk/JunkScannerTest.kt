package io.github.mdmac1983.leancleaner.junk

import io.github.mdmac1983.leancleaner.core.Exclusions
import io.github.mdmac1983.leancleaner.dir
import io.github.mdmac1983.leancleaner.file
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class JunkScannerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File

    @Before
    fun setUp() {
        root = tmp.newFolder("emulated0")
    }

    private fun scan(
        exclusions: Exclusions = Exclusions.NONE,
        inspector: ApkInspector? = null,
    ): List<JunkItem> = runBlocking { JunkScanner(exclusions, inspector).scan(listOf(root)) }

    private fun List<JunkItem>.at(relative: String): JunkItem? = firstOrNull { it.path == File(root, relative).absolutePath }

    @Test
    fun `thumbnail caches are reported as whole folders`() {
        root.file("DCIM/.thumbnails/1.jpg", 100)
        root.file("DCIM/.thumbnails/2.jpg", 50)
        root.file("DCIM/Camera/photo.jpg", 10)

        val items = scan()

        val thumbs = items.at("DCIM/.thumbnails")!!
        assertEquals(JunkCategory.THUMBNAILS, thumbs.category)
        assertEquals(150, thumbs.size)
        assertTrue(thumbs.isDirectory)
        assertNull(items.at("DCIM/Camera/photo.jpg"))
    }

    @Test
    fun `temp and log files are found anywhere outside Android`() {
        root.file("Download/setup.tmp", 7)
        root.file("SomeApp/logs/debug.LOG", 3)
        root.file("Android/data/com.example/files/app.log", 3)

        val items = scan()

        assertEquals(JunkCategory.TEMP_FILES, items.at("Download/setup.tmp")!!.category)
        assertEquals(JunkCategory.TEMP_FILES, items.at("SomeApp/logs/debug.LOG")!!.category)
        assertNull("app-private files are left alone", items.at("Android/data/com.example/files/app.log"))
    }

    @Test
    fun `only the top-most empty folder is reported`() {
        root.dir("Old/Nested/Deeper")
        root.dir("Music/EmptyAlbum")
        root.dir("Download")
        root.file("Kept/file.txt")
        root.dir("Kept/EmptyChild")

        val items = scan().filter { it.category == JunkCategory.EMPTY_FOLDERS }.map { it.path }.toSet()

        assertEquals(
            setOf(
                File(root, "Old").absolutePath,
                File(root, "Music/EmptyAlbum").absolutePath,
                File(root, "Kept/EmptyChild").absolutePath,
            ),
            items,
        )
    }

    @Test
    fun `excluded folders are skipped and keep their parents from looking empty`() {
        root.dir("Parent/Protected")
        root.file("Download/keep.tmp")

        val exclusions = Exclusions(
            listOf(File(root, "Parent/Protected").absolutePath, File(root, "Download/keep.tmp").absolutePath),
        )
        val items = scan(exclusions)

        assertTrue(items.isEmpty())
    }

    @Test
    fun `lost dir contents are cleaned but the folder is kept`() {
        root.file("LOST.DIR/1234", 20)
        root.file("FOUND.000/FILE0000.CHK", 5)

        val items = scan()

        val lost = items.at("LOST.DIR")!!
        assertEquals(JunkCategory.LOST_FOUND, lost.category)
        assertTrue(lost.contentsOnly)
        assertEquals(20, lost.size)
        val found = items.at("FOUND.000")!!
        assertFalse(found.contentsOnly)
    }

    @Test
    fun `empty lost dir is not reported`() {
        root.dir("LOST.DIR")
        assertTrue(scan().isEmpty())
    }

    @Test
    fun `desktop leftovers`() {
        root.file("Music/.DS_Store")
        root.file("Music/Thumbs.db")
        root.file("Music/._song.mp3")
        root.file("Music/desktop.ini")
        root.file("Music/.Trash-1000/files/x", 5)
        root.file("Music/song.mp3")

        val junk = scan().filter { it.category == JunkCategory.DESKTOP_JUNK }.map { it.name }.toSet()

        assertEquals(setOf(".DS_Store", "Thumbs.db", "._song.mp3", "desktop.ini", ".Trash-1000"), junk)
    }

    @Test
    fun `app caches on shared storage are cleaned contents-only`() {
        root.file("Android/data/com.example/cache/img.bin", 40)
        root.dir("Android/data/com.empty/cache")
        root.file("Android/data/com.example/files/save.dat", 40)

        val items = scan()

        val cache = items.single { it.category == JunkCategory.APP_CACHE }
        assertEquals(File(root, "Android/data/com.example/cache").absolutePath, cache.path)
        assertEquals("com.example", cache.detail)
        assertTrue(cache.contentsOnly)
        assertEquals(40, cache.size)
    }

    @Test
    fun `apks are pre-selected only when the app is already installed`() {
        root.file("Download/old.apk", 10)
        root.file("Download/new.apk", 10)
        root.file("Download/bundle.xapk", 10)
        val inspector = ApkInspector { file ->
            when (file.name) {
                "old.apk" -> ApkInfo("Old", "com.old", "1.0", 1, installedVersionCode = 5)
                "new.apk" -> ApkInfo("New", "com.new", "2.0", 9, installedVersionCode = null)
                else -> null
            }
        }

        val items = scan(inspector = inspector)

        val old = items.at("Download/old.apk")!!
        assertTrue(old.preselected)
        assertEquals("Old · already installed", old.detail)
        val new = items.at("Download/new.apk")!!
        assertFalse(new.preselected)
        assertEquals("New · not installed", new.detail)
        assertFalse(items.at("Download/bundle.xapk")!!.preselected)
    }

    @Test
    fun `trashed media is found but not pre-selected`() {
        root.file("DCIM/Camera/.trashed-1700000000-IMG_1.jpg", 30)

        val item = scan().single()

        assertEquals(JunkCategory.TRASHED, item.category)
        assertFalse(item.preselected)
    }
}
