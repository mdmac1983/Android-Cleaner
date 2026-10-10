package io.github.mdmac1983.leancleaner.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class PrettyPathTest {

    @Test
    fun `internal storage of any user is shown relative to its root`() {
        assertEquals("Internal storage", prettyPath("/storage/emulated/0"))
        assertEquals("DCIM/Camera", prettyPath("/storage/emulated/0/DCIM/Camera"))
        assertEquals("Download/a.apk", prettyPath("/storage/emulated/10/Download/a.apk"))
        assertEquals("Internal storage", prettyPath("/storage/emulated/10/"))
    }

    @Test
    fun `sd cards and unknown paths`() {
        assertEquals("SD card", prettyPath("/storage/1A2B-3C4D"))
        assertEquals("SD card/Music", prettyPath("/storage/1A2B-3C4D/Music"))
        assertEquals("/storage/1A2B-3C4DX/Music", prettyPath("/storage/1A2B-3C4DX/Music"))
        assertEquals("/data/local/tmp", prettyPath("/data/local/tmp"))
    }

    @Test
    fun `parent folder`() {
        assertEquals("DCIM/Camera", prettyParent("/storage/emulated/0/DCIM/Camera/IMG_1.jpg"))
        assertEquals("Internal storage", prettyParent("/storage/emulated/0/notes.txt"))
    }
}
