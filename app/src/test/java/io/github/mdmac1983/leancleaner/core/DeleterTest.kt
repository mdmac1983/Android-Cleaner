package io.github.mdmac1983.leancleaner.core

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

class DeleterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var deleter: Deleter

    @Before
    fun setUp() {
        root = tmp.newFolder("emulated0")
        deleter = Deleter(SafetyGuard(listOf(root)))
    }

    private fun delete(vararg requests: DeleteRequest) = runBlocking { deleter.delete(requests.toList()) }

    @Test
    fun `deletes files and folders and reports freed space`() {
        val file = root.file("Download/a.tmp", 100)
        val dir = root.dir("Old")
        root.file("Old/x/y.bin", 50)

        val result = delete(DeleteRequest(file.path), DeleteRequest(dir.path))

        assertEquals(150L, result.freedBytes)
        assertEquals(setOf(file.path, dir.path), result.deleted)
        assertTrue(result.failed.isEmpty())
        assertFalse(file.exists())
        assertFalse(dir.exists())
    }

    @Test
    fun `contents-only keeps the folder`() {
        val lost = root.dir("LOST.DIR")
        root.file("LOST.DIR/frag1", 10)
        root.file("LOST.DIR/sub/frag2", 10)

        val result = delete(DeleteRequest(lost.path, contentsOnly = true))

        assertEquals(20L, result.freedBytes)
        assertTrue(lost.isDirectory)
        assertTrue(lost.list()!!.isEmpty())
    }

    @Test
    fun `structural folders and paths outside storage are refused`() {
        root.file("Android/data/com.app/f", 10)
        val outside = tmp.newFile("outside.txt")

        val result = delete(
            DeleteRequest(root.path),
            DeleteRequest(File(root, "Android").path),
            DeleteRequest(File(root, "Android/data").path),
            DeleteRequest(File(root, "Android/data/com.app/../../..").path),
            DeleteRequest(outside.path),
        )

        assertEquals(5, result.failed.size)
        assertEquals(0L, result.freedBytes)
        assertTrue(File(root, "Android/data/com.app/f").exists())
        assertTrue(outside.exists())
    }

    @Test
    fun `items already removed with their parent count as deleted`() {
        val parent = root.dir("P")
        val child = root.file("P/c.bin", 5)

        val result = delete(DeleteRequest(parent.path), DeleteRequest(child.path))

        assertEquals(setOf(parent.path, child.path), result.deleted)
        assertEquals(5L, result.freedBytes)
    }
}
