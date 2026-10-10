package io.github.mdmac1983.leancleaner.analyzer

import io.github.mdmac1983.leancleaner.core.FileKind
import io.github.mdmac1983.leancleaner.file
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StorageAnalyzerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `sizes roll up, kinds are totalled and large files are ranked`() {
        val root = tmp.newFolder("emulated0")
        root.file("DCIM/Camera/a.jpg", 300)
        root.file("DCIM/Camera/b.mp4", 1_000)
        root.file("Movies/big.mkv", 5_000)
        root.file("Download/doc.pdf", 50)
        root.file("Download/setup.apk", 700)

        val result = runBlocking { StorageAnalyzer(largeFileThreshold = 500, maxLargeFiles = 2).analyze(listOf(root)) }

        val node = result.roots.single()
        assertEquals(7_050L, node.size)
        assertEquals(5, node.fileCount)
        assertEquals(listOf("Movies", "DCIM", "Download"), node.children.map { it.name })
        assertEquals(1_300L, node.children[1].size)
        assertEquals(300L, result.bytesByKind[FileKind.IMAGE])
        assertEquals(6_000L, result.bytesByKind[FileKind.VIDEO])
        assertEquals(1, result.countByKind[FileKind.DOCUMENT])
        assertEquals("capped and sorted", listOf("big.mkv", "b.mp4"), result.largeFiles.map { it.name })
    }

    @Test
    fun `removing a child shrinks every ancestor`() {
        val root = tmp.newFolder("emulated0")
        root.file("A/B/C/f.bin", 100)
        root.file("A/g.bin", 10)

        val node = runBlocking { StorageAnalyzer(Long.MAX_VALUE).analyze(listOf(root)) }.roots.single()
        val a = node.children.single()
        val b = a.children.single()
        val c = b.children.single()

        b.removeChild(c)

        assertEquals(0L, b.size)
        assertEquals(10L, a.size)
        assertEquals(10L, node.size)
        assertEquals(1, node.fileCount)
    }
}
