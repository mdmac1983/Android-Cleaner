package io.github.mdmac1983.leancleaner

import java.io.File

/** Creates [relativePath] under this directory, with [bytes] of content (or [content]). */
fun File.file(relativePath: String, bytes: Int = 1, content: ByteArray? = null): File {
    val file = File(this, relativePath)
    file.parentFile!!.mkdirs()
    file.writeBytes(content ?: ByteArray(bytes) { (it % 251).toByte() })
    return file
}

fun File.dir(relativePath: String): File = File(this, relativePath).also { it.mkdirs() }
