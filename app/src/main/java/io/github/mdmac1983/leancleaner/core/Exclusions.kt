package io.github.mdmac1983.leancleaner.core

/** Paths the user never wants touched. A path excludes itself and everything below it. */
class Exclusions(paths: Collection<String>) {
    private val prefixes: List<String> =
        paths.map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() }.distinct()

    fun isExcluded(path: String): Boolean =
        prefixes.any { path == it || path.startsWith("$it/") }

    companion object {
        val NONE = Exclusions(emptyList())
    }
}
