package io.github.mdmac1983.leancleaner.automation

import android.view.accessibility.AccessibilityNodeInfo

/** [UiNode] backed by the real accessibility tree. */
class AccessibilityUiNode(private val info: AccessibilityNodeInfo) : UiNode {
    override val text: CharSequence? get() = info.text
    override val contentDescription: CharSequence? get() = info.contentDescription
    override val isClickable: Boolean get() = info.isClickable
    override val isEnabled: Boolean get() = info.isEnabled
    override val isScrollable: Boolean get() = info.isScrollable
    override val isVisibleToUser: Boolean get() = info.isVisibleToUser
    override val windowId: Int get() = info.windowId
    override val parent: UiNode? get() = info.parent?.let(::AccessibilityUiNode)
    override val children: List<UiNode>
        get() = (0 until info.childCount).mapNotNull { index -> info.getChild(index)?.let(::AccessibilityUiNode) }

    override fun click(): Boolean = info.performAction(AccessibilityNodeInfo.ACTION_CLICK)

    override fun scrollForward(): Boolean = info.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
}
