package io.github.mdmac1983.leancleaner.automation

import java.util.Locale

/**
 * Minimal view of an on-screen element. Backed by AccessibilityNodeInfo on devices and by a fake
 * tree in unit tests, so the navigation logic can be tested without Android.
 */
interface UiNode {
    val text: CharSequence?
    val contentDescription: CharSequence?
    val isClickable: Boolean
    val isEnabled: Boolean
    val isScrollable: Boolean
    val isVisibleToUser: Boolean
    /** Identifies the window this node lives in; changes when a new screen or dialog appears. */
    val windowId: Int
    val parent: UiNode?
    val children: List<UiNode>

    fun click(): Boolean
    fun scrollForward(): Boolean
}

object NodeFinder {

    fun normalize(label: CharSequence): String =
        label.toString()
            .replace(' ', ' ')
            .replace(WHITESPACE, " ")
            .trim()
            .lowercase(Locale.ROOT)

    /**
     * A visible node whose text or content description is *exactly* one of [labels] (ignoring case
     * and whitespace). Labels are tried in order, so a specific label ("Storage & cache") wins over a
     * generic one ("Storage") even if the generic one appears higher up, e.g. in a two-pane layout.
     * Exact matching is deliberate: "Clear cache" must never match "Clear storage" or "Clear data".
     */
    fun findByLabel(root: UiNode, labels: Collection<String>): UiNode? {
        val nodes = bfs(root).filter { it.isVisibleToUser }.toList()
        for (label in labels) {
            val wanted = normalize(label)
            if (wanted.isEmpty()) continue
            nodes.firstOrNull { node -> node.matches(wanted) }?.let { return it }
        }
        return null
    }

    /** Whether any visible node's text contains one of [candidates] (used to recognize the right page). */
    fun containsText(root: UiNode, candidates: Collection<String>): Boolean {
        val wanted = candidates.map(::normalize).filter { it.isNotEmpty() }
        if (wanted.isEmpty()) return false
        return bfs(root).any { node ->
            node.isVisibleToUser && node.text?.let { text -> normalize(text).let { t -> wanted.any(t::contains) } } == true
        }
    }

    /** Whether [node] or anything up to [depth] levels below it is labelled with one of [labels]. */
    fun mentions(node: UiNode, labels: Collection<String>, depth: Int = 2): Boolean {
        val wanted = labels.map(::normalize).filter { it.isNotEmpty() }.toSet()
        fun visit(n: UiNode, level: Int): Boolean =
            wanted.any { n.matches(it) } || (level < depth && n.children.any { visit(it, level + 1) })
        return visit(node, 0)
    }

    /**
     * What to click for a labelled node: the node itself, else a clickable child (Android 16's
     * expressive buttons put the label on a container around the real button), else the closest
     * clickable ancestor (list rows). Siblings are never considered: next to "Clear cache" sits
     * "Clear storage".
     */
    fun clickTarget(node: UiNode, maxDepth: Int = 6): UiNode? {
        if (node.isClickable) return node
        node.children.firstOrNull { it.isClickable && it.isVisibleToUser }?.let { return it }
        var current = node.parent
        var depth = 1
        while (current != null && depth <= maxDepth) {
            if (current.isClickable) return current
            current = current.parent
            depth++
        }
        return null
    }

    fun findScrollable(root: UiNode): UiNode? = bfs(root).firstOrNull { it.isScrollable && it.isVisibleToUser }

    private fun UiNode.matches(normalizedLabel: String): Boolean =
        text?.let { normalize(it) == normalizedLabel } == true ||
            contentDescription?.let { normalize(it) == normalizedLabel } == true

    private fun bfs(root: UiNode): Sequence<UiNode> = sequence {
        val queue = ArrayDeque<UiNode>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited++
            yield(node)
            queue.addAll(node.children)
        }
    }

    private val WHITESPACE = Regex("\\s+")
    private const val MAX_NODES = 2_000
}
