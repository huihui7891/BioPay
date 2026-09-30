/*
 * BioPay - biometric payment assistance for supported payment apps.
 * Copyright (C) 2026 kiriashi
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package io.github.kiriashi.biopay.apps.unionpay

import android.app.Activity
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import io.github.kiriashi.biopay.apps.shared.ENTRY_TAG
import io.github.kiriashi.biopay.apps.shared.EntryInstaller
import io.github.kiriashi.biopay.apps.shared.SettingsEntry
import io.github.kiriashi.biopay.apps.shared.ancestor
import io.github.kiriashi.biopay.apps.shared.ancestorByClassPart
import io.github.kiriashi.biopay.apps.shared.ancestorOfType
import io.github.kiriashi.biopay.apps.shared.findText
import io.github.kiriashi.biopay.core.util.dp
import io.github.kiriashi.biopay.settings.ui.Theme
import java.lang.ref.WeakReference

internal object UnionPayEntry : SettingsEntry {
    override fun install(host: EntryInstaller, activity: Activity, root: ViewGroup): Boolean =
        host.installUnionPay(activity, root)
}

internal fun EntryInstaller.installUnionPay(activity: Activity, root: ViewGroup): Boolean {
    if (!activity.javaClass.name.endsWith(".UPActivityReactNative")) return false
    val general = findText(root, "通用设置") ?: return false
    val payment = findText(root, "支付设置") ?: return false
    val reactRoot = ancestorByClassPart(payment, "UPReactView") ?: return false
    val paySection = ancestor(payment, 3) ?: return false
    val content = paySection.parent as? ViewGroup ?: return false
    val entryHeight = 55
    val entryTopOffset = 55
    val shift = activity.dp(entryHeight)
    val originalBounds = (0 until content.childCount).map { child ->
        val view = content.getChildAt(child)
        WeakReference(view) to (view.top to view.bottom)
    }
    val oldBottom = content.bottom
    originalBounds.forEach { (viewRef, _) -> viewRef.get()?.offsetTopAndBottom(shift) }
    content.bottom = oldBottom + shift

    val scroll = ancestorOfType(payment, ScrollView::class.java)
    val scrollRef = scroll?.let(::WeakReference)
    val contentRef = WeakReference(content)
    val generalRef = WeakReference(general)
    val reactRootRef = WeakReference(reactRoot)
    val overlay = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(if (Theme.isDark(activity)) 0xFF191919.toInt() else Color.WHITE)
        addView(createRow(activity, "生物支付", entryHeight, 15, false, rightPadding = 20), LinearLayout.LayoutParams(-1, activity.dp(entryHeight)))
        tag = ENTRY_TAG
    }
    val overlayRef = WeakReference(overlay)
    val params = FrameLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = activity.dp(entryTopOffset)
    }
    try {
        reactRoot.addView(overlay, params)
    } catch (e: Throwable) {
        originalBounds.forEach { (viewRef, bounds) ->
            viewRef.get()?.apply { top = bounds.first; bottom = bounds.second }
        }
        content.bottom = oldBottom
        return false
    }
    val scrollListener = ViewTreeObserver.OnScrollChangedListener {
        val current = scrollRef?.get() ?: return@OnScrollChangedListener
        val currentOverlay = overlayRef.get() ?: return@OnScrollChangedListener
        val currentGeneral = generalRef.get() ?: return@OnScrollChangedListener
        val currentReactRoot = reactRootRef.get() ?: return@OnScrollChangedListener
        currentOverlay.translationY = -current.scrollY.toFloat()
        val scrollPosition = IntArray(2).also(current::getLocationInWindow)
        val reactPosition = IntArray(2).also(currentReactRoot::getLocationInWindow)
        currentOverlay.translationX = (scrollPosition[0] - reactPosition[0]).toFloat()
        currentOverlay.visibility = if (currentGeneral.getGlobalVisibleRect(android.graphics.Rect())) View.VISIBLE else View.GONE
    }
    scroll?.viewTreeObserver?.addOnScrollChangedListener(scrollListener)
    scrollListener.onScrollChanged()
    record(activity) {
        scrollRef?.get()?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnScrollChangedListener(scrollListener)
        overlayRef.get()?.let { (it.parent as? ViewGroup)?.removeView(it) }
        originalBounds.forEach { (viewRef, bounds) ->
            viewRef.get()?.apply { top = bounds.first; bottom = bounds.second }
        }
        contentRef.get()?.bottom = oldBottom
    }
    return true
}
