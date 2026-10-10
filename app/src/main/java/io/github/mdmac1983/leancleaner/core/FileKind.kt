package io.github.mdmac1983.leancleaner.core

enum class FileKind(val label: String) {
    IMAGE("Images"),
    VIDEO("Videos"),
    AUDIO("Audio"),
    DOCUMENT("Documents"),
    ARCHIVE("Archives"),
    APK("App installers"),
    OTHER("Other"),
    ;

    val isMedia: Boolean get() = this == IMAGE || this == VIDEO || this == AUDIO

    companion object {
        private val byExtension: Map<String, FileKind> = buildMap {
            fun add(kind: FileKind, vararg ext: String) = ext.forEach { put(it, kind) }
            add(
                IMAGE, "jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "avif", "bmp",
                "dng", "raw", "tif", "tiff", "svg",
            )
            add(
                VIDEO, "mp4", "mkv", "webm", "3gp", "3g2", "mov", "avi", "m4v", "ts", "flv",
                "wmv", "mpg", "mpeg",
            )
            add(
                AUDIO, "mp3", "m4a", "aac", "ogg", "oga", "opus", "flac", "wav", "amr", "awb",
                "wma", "mid", "midi", "3ga",
            )
            add(
                DOCUMENT, "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "ods",
                "odp", "txt", "rtf", "csv", "epub", "md",
            )
            add(ARCHIVE, "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst")
            add(APK, "apk", "apks", "apkm", "xapk")
        }

        fun of(fileName: String): FileKind {
            val dot = fileName.lastIndexOf('.')
            if (dot <= 0 || dot == fileName.lastIndex) return OTHER
            return byExtension[fileName.substring(dot + 1).lowercase()] ?: OTHER
        }
    }
}
