package io.github.mdmac1983.leancleaner.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.concurrent.TimeUnit

class FormatTest {

    @Test
    fun `sizes use decimal units like Android settings`() {
        assertEquals("0 B", Format.size(0, Locale.US))
        assertEquals("999 B", Format.size(999, Locale.US))
        assertEquals("1.00 kB", Format.size(1_000, Locale.US))
        assertEquals("1.50 MB", Format.size(1_500_000, Locale.US))
        assertEquals("12.3 MB", Format.size(12_340_000, Locale.US))
        assertEquals("123 MB", Format.size(123_456_789, Locale.US))
        assertEquals("1.00 MB", Format.size(999_999, Locale.US))
        assertEquals("128 GB", Format.size(128_000_000_000, Locale.US))
    }

    @Test
    fun `relative ages`() {
        val now = 1_000_000_000_000L
        val day = TimeUnit.DAYS.toMillis(1)
        assertEquals("today", Format.ago(now - 1000, now))
        assertEquals("yesterday", Format.ago(now - day - 1000, now))
        assertEquals("5 days ago", Format.ago(now - 5 * day, now))
        assertEquals("2 months ago", Format.ago(now - 61 * day, now))
        assertEquals("1 year ago", Format.ago(now - 400 * day, now))
    }

    @Test
    fun `duration formatting`() {
        assertEquals("5m", Format.duration(TimeUnit.MINUTES.toMillis(5)))
        assertEquals("2h 0m", Format.duration(TimeUnit.HOURS.toMillis(2)))
        assertEquals("1d 3h 7m", Format.duration(TimeUnit.MINUTES.toMillis(24 * 60 + 3 * 60 + 7)))
    }

    @Test
    fun `file kinds by extension`() {
        assertEquals(FileKind.IMAGE, FileKind.of("IMG_0001.JPG"))
        assertEquals(FileKind.VIDEO, FileKind.of("clip.mp4"))
        assertEquals(FileKind.APK, FileKind.of("app.apk"))
        assertEquals(FileKind.OTHER, FileKind.of(".nomedia"))
        assertEquals(FileKind.OTHER, FileKind.of("README"))
        assertTrue(FileKind.AUDIO.isMedia)
        assertFalse(FileKind.DOCUMENT.isMedia)
    }

    @Test
    fun `exclusions match whole path segments only`() {
        val exclusions = Exclusions(listOf("/storage/emulated/0/Keep/", "  "))
        assertTrue(exclusions.isExcluded("/storage/emulated/0/Keep"))
        assertTrue(exclusions.isExcluded("/storage/emulated/0/Keep/a/b.txt"))
        assertFalse(exclusions.isExcluded("/storage/emulated/0/Keeper"))
        assertFalse(exclusions.isExcluded("/storage/emulated/0"))
    }
}
