package io.github.mdmac1983.leancleaner.core

import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object Format {

    /** Decimal (SI) units, matching how Android's own storage settings report sizes. */
    fun size(bytes: Long, locale: Locale = Locale.getDefault()): String {
        if (bytes < 1000) return "$bytes B"
        val units = arrayOf("kB", "MB", "GB", "TB", "PB")
        var value = bytes / 1000.0
        var unit = 0
        while (value >= 999.95 && unit < units.lastIndex) {
            value /= 1000.0
            unit++
        }
        val pattern = when {
            value >= 100 -> "%.0f %s"
            value >= 10 -> "%.1f %s"
            else -> "%.2f %s"
        }
        return String.format(locale, pattern, value, units[unit])
    }

    /** Transfer speed: KB/s below 1 MB/s, MB/s above (decimal units, like [size]). */
    fun speed(bytesPerSecond: Long, locale: Locale = Locale.getDefault()): String {
        val kb = bytesPerSecond.coerceAtLeast(0) / 1000.0
        return when {
            kb < 10 -> String.format(locale, "%.1f KB/s", kb)
            kb < 999.5 -> String.format(locale, "%.0f KB/s", kb)
            kb < 99_950 -> String.format(locale, "%.1f MB/s", kb / 1000)
            else -> String.format(locale, "%.0f MB/s", kb / 1000)
        }
    }

    fun count(n: Int, singular: String, plural: String = singular + "s"): String =
        "$n ${if (n == 1) singular else plural}"

    fun date(millis: Long): String =
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(millis))

    fun dateTime(millis: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))

    /** "today", "yesterday", "5 days ago", "3 months ago", "2 years ago". */
    fun ago(millis: Long, now: Long = System.currentTimeMillis()): String {
        val days = TimeUnit.MILLISECONDS.toDays((now - millis).coerceAtLeast(0))
        return when {
            days < 1 -> "today"
            days < 2 -> "yesterday"
            days < 31 -> "$days days ago"
            days < 365 -> count((days / 30).toInt().coerceAtLeast(1), "month") + " ago"
            else -> count((days / 365).toInt(), "year") + " ago"
        }
    }

    /** "3d 4h 12m" style duration. */
    fun duration(millis: Long): String {
        val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(millis)
        val d = totalMinutes / (60 * 24)
        val h = (totalMinutes / 60) % 24
        val m = totalMinutes % 60
        return buildString {
            if (d > 0) append("${d}d ")
            if (d > 0 || h > 0) append("${h}h ")
            append("${m}m")
        }
    }

    fun percent(part: Long, total: Long): Int =
        if (total <= 0) 0 else ((part * 100.0) / total).toInt().coerceIn(0, 100)
}
