package io.github.mdmac1983.leancleaner.dupes

import io.github.mdmac1983.leancleaner.file
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

class DuplicateFinderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File

    @Before
    fun setUp() {
        root = tmp.newFolder("emulated0")
    }

    private fun find(minSize: Long = 1, skipHidden: Boolean = true): List<DupeGroup> =
        runBlocking { DuplicateFinder(minSize = minSize, skipHidden = skipHidden).find(listOf(root)) }

    private fun random(size: Int, seed: Int) = Random(seed).nextBytes(size)

    @Test
    fun `identical small and large files are grouped`() {
        val small = random(500, 1)
        val large = random(200_000, 2)
        root.file("DCIM/a.jpg", content = small)
        root.file("Download/a (1).jpg", content = small)
        root.file("Movies/clip.mp4", content = large)
        root.file("Download/clip.mp4", content = large)
        root.file("Download/clip-copy.mp4", content = large)
        root.file("Download/unique.bin", content = random(500, 3))

        val groups = find()

        assertEquals(2, groups.size)
        val big = groups.first()
        assertEquals("largest waste first", 200_000L, big.size)
        assertEquals(3, big.files.size)
        assertEquals(400_000L, big.wasted)
        assertEquals(2, groups[1].files.size)
    }

    @Test
    fun `same head and tail but different middle are not duplicates`() {
        val a = random(100_000, 5)
        val b = a.copyOf().also { it[50_000] = (it[50_000] + 1).toByte() }
        root.file("x/a.bin", content = a)
        root.file("y/b.bin", content = b)

        assertTrue(find().isEmpty())
    }

    @Test
    fun `hidden folders, app data, thumbnails, trash and tiny files are ignored`() {
        val content = random(2_000, 7)
        root.file("Pictures/p.jpg", content = content)
        root.file(".hidden/p.jpg", content = content)
        root.file("Android/data/com.app/p.jpg", content = content)
        root.file("DCIM/.thumbnails/p.jpg", content = content)
        root.file("DCIM/.trashed-1-p.jpg", content = content)

        assertTrue(find().isEmpty())
        assertEquals(1, find(skipHidden = false).size)
        assertTrue(find(minSize = 10_000, skipHidden = false).isEmpty())
    }

    @Test
    fun `empty files are never duplicates`() {
        root.file("a/empty1", content = ByteArray(0))
        root.file("b/empty2", content = ByteArray(0))
        assertTrue(find(minSize = 0).isEmpty())
    }

    @Test
    fun `auto selection keeps exactly one copy`() {
        val group = DupeGroup(
            "h", 10,
            listOf(
                DupeFile("/s/DCIM/Camera/IMG.jpg", 10, lastModified = 100),
                DupeFile("/s/Download/IMG.jpg", 10, lastModified = 300),
                DupeFile("/s/WhatsApp/Media/Images/IMG-copy.jpg", 10, lastModified = 200),
            ),
        )

        assertEquals(
            setOf("/s/Download/IMG.jpg", "/s/WhatsApp/Media/Images/IMG-copy.jpg"),
            group.autoSelect(KeepStrategy.OLDEST),
        )
        assertEquals(
            setOf("/s/DCIM/Camera/IMG.jpg", "/s/WhatsApp/Media/Images/IMG-copy.jpg"),
            group.autoSelect(KeepStrategy.NEWEST),
        )
        assertEquals(
            setOf("/s/DCIM/Camera/IMG.jpg", "/s/WhatsApp/Media/Images/IMG-copy.jpg"),
            group.autoSelect(KeepStrategy.SHORTEST_PATH),
        )
    }
}
