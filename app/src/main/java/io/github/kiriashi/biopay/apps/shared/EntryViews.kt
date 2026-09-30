/*
 * BioPay - biometric payment assistance for supported payment apps.
 * Copyright (C) 2026 kiriashi
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.kiriashi.biopay.apps.shared

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView

internal fun findText(root: View, vararg labels: String): View? {
    return findText(root, false, *labels)
}

internal fun findVisibleText(root: View, vararg labels: String): View? {
    return findText(root, true, *labels)
}

private fun findText(root: View, visibleOnly: Boolean, vararg labels: String): View? {
    val expected = labels.toSet()
    var found: View? = null
    visitViews(root) { view ->
        if ((!visibleOnly || view.isShown) &&
            view is TextView && view.text?.toString()?.trim() in expected
        ) {
            found = view
            true
        } else false
    }
    return found
}

internal fun findByEntryName(root: View, vararg names: String): View? {
    val expected = names.toSet()
    var found: View? = null
    visitViews(root) { view ->
        if (view.id != View.NO_ID) {
            val entry = runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
            if (entry in expected) {
                found = view
                return@visitViews true
            }
        }
        false
    }
    return found
}

internal inline fun visitViews(root: View, action: (View) -> Boolean) {
    val stack = ArrayDeque<View>()
    stack.add(root)
    var count = 0
    while (stack.isNotEmpty() && count++ < 3_000) {
        val view = stack.removeLast()
        if (action(view)) return
        if (view is ViewGroup) for (index in view.childCount - 1 downTo 0) stack.add(view.getChildAt(index))
    }
}

internal fun ancestor(view: View, generations: Int): View? {
    var current: View? = view
    repeat(generations) { current = current?.parent as? View }
    return current
}

internal fun ancestorByClassPart(view: View, classPart: String): ViewGroup? {
    var current: View? = view
    while (current != null) {
        if (current is ViewGroup && current.javaClass.name.contains(classPart)) return current
        current = current.parent as? View
    }
    return null
}

internal fun <T : View> ancestorOfType(view: View, type: Class<T>): T? {
    var current: View? = view
    while (current != null) {
        if (type.isInstance(current)) return type.cast(current)
        current = current.parent as? View
    }
    return null
}

internal fun versionCode(activity: Activity): Long = runCatching {
    activity.packageManager.getPackageInfo(activity.packageName, 0).longVersionCode
}.getOrDefault(0L)
