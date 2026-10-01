/*
 * BioPay - biometric payment assistance for supported payment apps.
 * Copyright (C) 2026 kiriashi
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.kiriashi.biopay.apps.shared

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import java.util.IdentityHashMap

/** One walk over a window, shared by the app-specific recognizers. */
internal class PaymentViewTree(val root: ViewGroup) {
    private val names = IdentityHashMap<View, String?>()
    var complete = true
        private set
    val all: List<View> = buildList {
        if (!root.isShown) return@buildList
        val pending = ArrayDeque<View>()
        pending.add(root)
        while (pending.isNotEmpty()) {
            if (size >= MAX_VISIBLE_VIEWS) {
                complete = false
                break
            }
            val view = pending.removeLast()
            // Every ancestor in this walk has already passed the visibility check.
            if (view.visibility != View.VISIBLE) continue
            add(view)
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) pending.add(view.getChildAt(index))
            }
        }
    }

    private val nameIndex by lazy(LazyThreadSafetyMode.NONE) {
        all.mapNotNull(::resourceName).toHashSet()
    }

    fun hasName(name: String): Boolean = name in nameIndex

    fun hasAnyName(vararg candidates: String): Boolean = candidates.any(nameIndex::contains)

    /** Alipay's six-digit prompt can be a FrameLayout content description. */
    fun hasText(vararg labels: String): Boolean = all.any { view ->
        val contentDescription = view.contentDescription?.toString()?.trim()
        val text = (view as? TextView)?.takeUnless { it is EditText }?.text?.toString()?.trim()
        val hint = (view as? EditText)?.hint?.toString()?.trim()
        labels.any { label ->
            contentDescription.equals(label, ignoreCase = true) ||
                text.equals(label, ignoreCase = true) ||
                hint.equals(label, ignoreCase = true)
        }
    }

    fun resourceName(view: View): String? {
        if (names.containsKey(view)) return names[view]
        val name = try {
            if (view.id == View.NO_ID) null else view.resources.getResourceEntryName(view.id)
        } catch (_: Throwable) {
            null
        }
        names[view] = name
        return name
    }

    private companion object {
        const val MAX_VISIBLE_VIEWS = 8_192
    }
}

internal object PaymentKeypad {
    private class KeyGrid {
        val targets = arrayOfNulls<View>(10)
        val bottoms = IntArray(10) { Int.MIN_VALUE }
        var hasDescendant = false

        fun add(digit: Int, target: View, bottom: Int) {
            if (bottom > bottoms[digit]) {
                targets[digit] = target
                bottoms[digit] = bottom
            }
        }
    }

    fun namedKeys(views: PaymentViewTree, prefixes: Array<String>): List<View>? =
        completeGrid(views, false) { view ->
            val name = views.resourceName(view) ?: return@completeGrid null
            prefixes.firstNotNullOfOrNull { prefix ->
                if (!name.startsWith(prefix)) null else name.removePrefix(prefix)
                    .singleOrNull()?.takeIf { it in '0'..'9' }?.minus('0')
            }
        }

    fun textKeys(views: PaymentViewTree): List<View>? = completeGrid(views, true) { view ->
        val value = (view as? TextView)?.text?.toString()?.trim() ?: return@completeGrid null
        value.singleOrNull()?.takeIf { it in '0'..'9' }?.minus('0')
    }

    private fun completeGrid(views: PaymentViewTree, requireGrid: Boolean, digitOf: (View) -> Int?): List<View>? {
        if (!views.complete) return null
        val containers = IdentityHashMap<ViewGroup, KeyGrid>()
        val location = IntArray(2)
        for (view in views.all) {
            val digit = digitOf(view) ?: continue
            val target = clickableTarget(view, views.root)
            if (!target.isAttachedToWindow || target.width <= 0 || target.height <= 0) continue
            target.getLocationInWindow(location)
            val bottom = location[1] + target.height
            // A clickable ViewGroup may itself be one of another key's ancestors.
            if (target is ViewGroup) {
                containers.getOrPut(target) { KeyGrid() }.add(digit, target, bottom)
            }
            var parent = target.parent as? ViewGroup
            while (parent != null) {
                if (parent.width > 0 && parent.height > 0) {
                    containers.getOrPut(parent) { KeyGrid() }.apply {
                        hasDescendant = true
                        add(digit, target, bottom)
                    }
                }
                if (parent === views.root) break
                parent = parent.parent as? ViewGroup
            }
        }
        var best: List<View>? = null
        var bestArea = Long.MAX_VALUE
        var bestBottom = Int.MIN_VALUE
        for ((container, grid) in containers) {
            if (!grid.hasDescendant || grid.targets.any { it == null }) continue
            val keys = grid.targets.filterNotNull()
            if (keys.toSet().size != 10 || (requireGrid && !looksLikeGrid(keys))) continue
            val area = container.width.toLong() * container.height
            val bottom = grid.bottoms.maxOrNull() ?: continue
            if (area < bestArea || (area == bestArea && bottom > bestBottom)) {
                best = keys
                bestArea = area
                bestBottom = bottom
            }
        }
        return best
    }

    private fun looksLikeGrid(keys: List<View>): Boolean {
        val centers = keys.map { view ->
            val point = IntArray(2).also(view::getLocationInWindow)
            (point[0] + view.width / 2) to (point[1] + view.height / 2)
        }
        if (centers.map { it.first / 20 }.toSet().size < 3 ||
            centers.map { it.second / 20 }.toSet().size < 3) return false
        return true
    }

    fun group(root: ViewGroup, keys: List<View>): ViewGroup? {
        var parent = keys.first().parent as? ViewGroup
        while (parent != null) {
            val candidate = parent
            if (keys.all { isDescendant(candidate, it) }) {
                return candidate.takeIf { it.width > 0 && it.height > 0 }
            }
            if (candidate === root) break
            parent = candidate.parent as? ViewGroup
        }
        return null
    }

    private fun isDescendant(parent: ViewGroup, child: View): Boolean {
        var current: View? = child
        while (current != null) {
            if (current === parent) return true
            current = current.parent as? View
        }
        return false
    }

    private fun clickableTarget(view: View, boundary: ViewGroup): View {
        var current = view
        repeat(3) {
            if (current.isClickable || current === boundary) return current
            current = current.parent as? View ?: return view
        }
        return view
    }
}
